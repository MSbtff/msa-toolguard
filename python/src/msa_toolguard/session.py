"""Deterministic ToolSession guardrail boundary matching Java toolguard-core."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from threading import Event, Lock, Thread
import time
from typing import Any, Callable
from uuid import uuid4

from .approval import DENY_ALL_APPROVAL_GATE, ApprovalGate, ApprovalRequest
from .digest import canonical_sha256
from .evidence import EvidenceLedger
from .models import (
    AccessLevel,
    GuardrailError,
    GuardrailViolation,
    ToolExecutionEvent,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    UncertainExecutionError,
    ViolationCode,
)
from .policy import ToolPolicy
from .store import InMemoryToolExecutionStore, ToolExecutionStore

ToolHandler = Callable[[dict[str, Any]], ToolExecutionOutcome]
ToolExecutionListener = Callable[[ToolExecutionEvent], None]


class ToolSession:
    def __init__(
        self,
        *,
        session_id: str | None = None,
        scope_key: str = "default",
        max_access_level: AccessLevel = AccessLevel.READ_ONLY,
        max_total_calls: int = 50,
        bound_parameters: dict[str, Any] | None = None,
        execution_store: ToolExecutionStore | None = None,
        listeners: list[ToolExecutionListener] | None = None,
        approval_gate: ApprovalGate | None = None,
        session_timeout_seconds: float | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        if max_total_calls < 1:
            raise ValueError("max_total_calls must be >= 1")
        if session_timeout_seconds is not None and session_timeout_seconds <= 0:
            raise ValueError("session_timeout_seconds must be positive")
        self.session_id = session_id or str(uuid4())
        self.scope_key = scope_key
        self.max_access_level = max_access_level
        self.max_total_calls = max_total_calls
        self.bound_parameters: dict[str, Any] = dict(bound_parameters or {})
        self.execution_store: ToolExecutionStore = execution_store or InMemoryToolExecutionStore()
        self._listeners: list[ToolExecutionListener] = list(listeners or [])
        self._approval_gate: ApprovalGate = approval_gate or DENY_ALL_APPROVAL_GATE
        self._clock: Callable[[], datetime] = clock or (lambda: datetime.now(timezone.utc))
        self._session_deadline: datetime | None = (
            self._clock() + timedelta(seconds=session_timeout_seconds)
            if session_timeout_seconds is not None
            else None
        )
        self.evidence_ledger = EvidenceLedger(self._clock)
        self._tools: dict[str, tuple[ToolPolicy, ToolHandler]] = {}
        self._budget_lock = Lock()
        self._total_calls = 0
        self._tool_calls: dict[str, int] = {}

    def register_tool(self, policy: ToolPolicy, handler: ToolHandler) -> "ToolSession":
        self._tools[policy.name] = (policy, handler)
        return self

    def add_listener(self, listener: ToolExecutionListener) -> "ToolSession":
        self._listeners.append(listener)
        return self

    @property
    def total_call_count(self) -> int:
        with self._budget_lock:
            return self._total_calls

    def execute(
        self,
        tool_name: str,
        raw_arguments: dict[str, Any] | None = None,
        request_id: str | None = None,
    ) -> ToolExecutionOutcome:
        started = time.perf_counter()
        safe_name = "" if tool_name is None else str(tool_name)
        policy: ToolPolicy | None = None
        budget_reserved = False
        try:
            if self._session_deadline is not None and self._clock() >= self._session_deadline:
                raise self._deny(
                    ViolationCode.SESSION_TIMEOUT,
                    safe_name,
                    f"Session '{self.session_id}' has exceeded its execution deadline",
                    "Stop calling additional tools and produce a final report with the collected evidence.",
                )

            registered = self._tools.get(safe_name)
            if registered is None:
                raise self._deny(
                    ViolationCode.UNREGISTERED_TOOL,
                    safe_name,
                    f"Tool '{safe_name}' is not registered in this session",
                    f"Call only registered tools: {sorted(self._tools)}",
                )
            policy, handler = registered

            if policy.access_level == AccessLevel.MUTATING and self.max_access_level == AccessLevel.READ_ONLY:
                raise self._deny(
                    ViolationCode.ACCESS_DENIED,
                    safe_name,
                    f"Session is READ_ONLY and cannot invoke MUTATING tool '{safe_name}'",
                    "Use read-only tools or escalate to an approved mutating session.",
                )

            for required in policy.requires:
                if not self.evidence_ledger.has_completed_tool(required):
                    raise self._deny(
                        ViolationCode.PREREQUISITE_NOT_MET,
                        safe_name,
                        f"Tool '{safe_name}' requires completed prerequisite tool '{required}'",
                        f"Invoke '{required}' and verify its completion before calling '{safe_name}'.",
                    )

            resolved_args = self._resolve_and_validate_arguments(policy, raw_arguments)

            for rule in policy.deny_rules:
                reason = rule(safe_name, resolved_args)
                if reason:
                    raise self._deny(
                        ViolationCode.POLICY_DENIED,
                        safe_name,
                        reason,
                        "Adjust tool arguments to comply with deterministic policy constraints.",
                    )

            normalized_request_id = self._normalize_request_id(policy, request_id)
            needs_approval = policy.requires_approval_for(resolved_args)
            payload_digest = canonical_sha256(resolved_args) if (needs_approval or normalized_request_id is not None) else None

            if needs_approval:
                assert payload_digest is not None
                decision = self._approval_gate.check(
                    ApprovalRequest(
                        session_id=self.session_id,
                        scope_key=self.scope_key,
                        tool_name=safe_name,
                        request_id=normalized_request_id,
                        payload_digest=payload_digest,
                        arguments=dict(resolved_args),
                    )
                )
                if not decision.approved:
                    message = decision.reason or f"Tool '{safe_name}' requires explicit approval"
                    hint = (
                        f"Await approval '{decision.approval_id}' for payloadDigest={payload_digest}"
                        if decision.approval_id
                        else f"Obtain explicit approval for payloadDigest={payload_digest} before retrying '{safe_name}'."
                    )
                    raise self._deny(ViolationCode.APPROVAL_REQUIRED, safe_name, message, hint)

            self._check_and_increment_budgets(policy)
            budget_reserved = True

            if normalized_request_id is not None:
                assert payload_digest is not None
                outcome = self.execution_store.execute_idempotent(
                    self.scope_key,
                    safe_name,
                    normalized_request_id,
                    payload_digest,
                    lambda: self._invoke_and_record(policy, handler, resolved_args, normalized_request_id),
                )
                if outcome.replayed or outcome.evidence_id is None:
                    synced = self.evidence_ledger.record_replayed(
                        outcome.evidence_id,
                        policy.evidence_prefix,
                        policy.name,
                        outcome.status,
                        normalized_request_id,
                        outcome.facts,
                    )
                    outcome = outcome.with_evidence(synced.evidence_id, normalized_request_id)
            else:
                outcome = self._invoke_and_record(policy, handler, resolved_args, None)

            self._emit(
                ToolExecutionEvent(
                    session_id=self.session_id,
                    tool_name=safe_name,
                    allowed=True,
                    violation_code=None,
                    status=outcome.status,
                    replayed=outcome.replayed,
                    elapsed_ms=round((time.perf_counter() - started) * 1000.0, 3),
                )
            )
            return outcome
        except GuardrailError as blocked:
            if budget_reserved and policy is not None:
                self._release_budget(policy)
            self._emit(
                ToolExecutionEvent(
                    session_id=self.session_id,
                    tool_name=safe_name,
                    allowed=False,
                    violation_code=blocked.violation.code,
                    status=None,
                    replayed=False,
                    elapsed_ms=round((time.perf_counter() - started) * 1000.0, 3),
                )
            )
            raise
        except Exception:
            self._emit(
                ToolExecutionEvent(
                    session_id=self.session_id,
                    tool_name=safe_name,
                    allowed=True,
                    violation_code=None,
                    status=None,
                    replayed=False,
                    elapsed_ms=round((time.perf_counter() - started) * 1000.0, 3),
                )
            )
            raise

    def _invoke_and_record(
        self,
        policy: ToolPolicy,
        handler: ToolHandler,
        resolved_args: dict[str, Any],
        request_id: str | None,
    ) -> ToolExecutionOutcome:
        try:
            raw = self._invoke_with_timeout(policy, handler, resolved_args)
            if raw is None:
                raise ValueError("tool handler returned None")
            record = self.evidence_ledger.record(
                policy.evidence_prefix,
                policy.name,
                raw.status,
                request_id,
                raw.facts,
            )
            return raw.with_evidence(record.evidence_id, request_id)
        except UncertainExecutionError as uncertain:
            message = str(uncertain) or "uncertain_execution"
            record = self.evidence_ledger.record(
                policy.evidence_prefix,
                policy.name,
                ToolExecutionStatus.PENDING,
                request_id,
                {"reason": message},
            )
            return ToolExecutionOutcome.pending(request_id, message).with_evidence(record.evidence_id, request_id)

    def _invoke_with_timeout(
        self,
        policy: ToolPolicy,
        handler: ToolHandler,
        resolved_args: dict[str, Any],
    ) -> ToolExecutionOutcome:
        timeout_seconds = self._compute_effective_timeout_seconds(policy.execution_timeout_ms)
        if timeout_seconds is None:
            return handler(dict(resolved_args))
        if timeout_seconds <= 0:
            raise UncertainExecutionError("Session deadline exceeded before tool invocation")
        done = Event()
        box: dict[str, Any] = {}

        def worker() -> None:
            try:
                box["outcome"] = handler(dict(resolved_args))
            except BaseException as exc:
                box["error"] = exc
            finally:
                done.set()

        Thread(target=worker, name=f"toolguard-exec-{policy.name}", daemon=True).start()
        if not done.wait(timeout_seconds):
            raise UncertainExecutionError(
                f"Tool '{policy.name}' exceeded execution timeout ({int(round(timeout_seconds * 1000))}ms)"
            )
        if "error" in box:
            raise box["error"]
        return box["outcome"]

    def _compute_effective_timeout_seconds(self, tool_timeout_ms: int | None) -> float | None:
        tool_sec = None if tool_timeout_ms is None else tool_timeout_ms / 1000.0
        remaining = (
            None
            if self._session_deadline is None
            else (self._session_deadline - self._clock()).total_seconds()
        )
        if tool_sec is None:
            return remaining
        if remaining is None:
            return tool_sec
        return min(tool_sec, remaining)

    def _resolve_and_validate_arguments(
        self,
        policy: ToolPolicy,
        raw_arguments: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if raw_arguments is not None and not isinstance(raw_arguments, dict):
            raise self._deny(
                ViolationCode.POLICY_DENIED,
                policy.name,
                "Tool arguments must be a JSON object",
                "Pass a mapping of argument names to values.",
            )
        resolved = dict(raw_arguments or {})
        for bound_key in policy.bound_parameters:
            if bound_key not in self.bound_parameters:
                raise self._deny(
                    ViolationCode.SCOPE_VIOLATION,
                    policy.name,
                    f"Required host-bound parameter '{bound_key}' is not configured in session scope",
                    f"Ensure the host session binds '{bound_key}' before invoking this tool.",
                )
            expected = self.bound_parameters[bound_key]
            if bound_key in resolved and resolved[bound_key] != expected:
                raise self._deny(
                    ViolationCode.SCOPE_VIOLATION,
                    policy.name,
                    f"Parameter '{bound_key}' is host-bound and cannot be overridden by the agent",
                    f"Omit '{bound_key}' or pass only the host-bound scope value.",
                )
            resolved[bound_key] = expected
        return resolved

    def _normalize_request_id(self, policy: ToolPolicy, request_id: str | None) -> str | None:
        trimmed = request_id.strip() if isinstance(request_id, str) else None
        if trimmed == "":
            trimmed = None
        if policy.require_idempotency_key and trimmed is None:
            raise self._deny(
                ViolationCode.MISSING_IDEMPOTENCY_KEY,
                policy.name,
                f"Tool '{policy.name}' requires a non-blank requestId for idempotent execution",
                "Provide a stable requestId that stays identical across retries of the same operation.",
            )
        return trimmed

    def _check_and_increment_budgets(self, policy: ToolPolicy) -> None:
        with self._budget_lock:
            if self._total_calls >= self.max_total_calls:
                raise self._deny(
                    ViolationCode.CALL_BUDGET_EXCEEDED,
                    policy.name,
                    f"Session total tool call budget ({self.max_total_calls}) exceeded",
                    "Stop calling additional tools and produce a final report with the collected evidence.",
                )
            used = self._tool_calls.get(policy.name, 0)
            if used >= policy.max_calls:
                raise self._deny(
                    ViolationCode.CALL_BUDGET_EXCEEDED,
                    policy.name,
                    f"Tool '{policy.name}' call budget ({policy.max_calls}) exceeded",
                    f"Do not invoke '{policy.name}' again in this session.",
                )
            self._total_calls += 1
            self._tool_calls[policy.name] = used + 1

    def _release_budget(self, policy: ToolPolicy) -> None:
        with self._budget_lock:
            if self._total_calls > 0:
                self._total_calls -= 1
            used = self._tool_calls.get(policy.name, 0)
            if used > 0:
                self._tool_calls[policy.name] = used - 1

    def _emit(self, event: ToolExecutionEvent) -> None:
        for listener in self._listeners:
            listener(event)

    @staticmethod
    def _deny(code: ViolationCode, tool_name: str, message: str, repair_hint: str) -> GuardrailError:
        return GuardrailError(GuardrailViolation(code=code, tool_name=tool_name, message=message, repair_hint=repair_hint))
