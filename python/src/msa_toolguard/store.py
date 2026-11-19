"""Idempotent execution store SPI and thread-safe in-memory implementation."""

from __future__ import annotations

from dataclasses import dataclass
from threading import Lock
from typing import Callable, Protocol

from .models import (
    GuardrailError,
    GuardrailViolation,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    UncertainExecutionError,
    ViolationCode,
)


class ToolExecutionStore(Protocol):
    def execute_idempotent(
        self,
        scope_key: str,
        tool_name: str,
        request_id: str,
        payload_digest: str,
        action: Callable[[], ToolExecutionOutcome],
    ) -> ToolExecutionOutcome: ...


@dataclass
class _StoredExecution:
    payload_digest: str
    outcome: ToolExecutionOutcome


class InMemoryToolExecutionStore:
    def __init__(self) -> None:
        self._guard = Lock()
        self._locks: dict[str, Lock] = {}
        self._entries: dict[str, _StoredExecution] = {}

    def execute_idempotent(
        self,
        scope_key: str,
        tool_name: str,
        request_id: str,
        payload_digest: str,
        action: Callable[[], ToolExecutionOutcome],
    ) -> ToolExecutionOutcome:
        composite_key = f"{scope_key}::{tool_name}::{request_id}"
        with self._guard:
            key_lock = self._locks.setdefault(composite_key, Lock())
        with key_lock:
            existing = self._entries.get(composite_key)
            if existing is not None:
                if existing.payload_digest != payload_digest:
                    raise GuardrailError(
                        GuardrailViolation(
                            code=ViolationCode.IDEMPOTENCY_CONFLICT,
                            tool_name=tool_name,
                            message=f"requestId '{request_id}' was already used with different arguments",
                            repair_hint=(
                                f"Use the exact same arguments when retrying requestId '{request_id}', "
                                "or generate a new requestId."
                            ),
                        )
                    )
                if existing.outcome.status != ToolExecutionStatus.PENDING:
                    return existing.outcome.as_replayed()
            else:
                self._entries[composite_key] = _StoredExecution(
                    payload_digest=payload_digest,
                    outcome=ToolExecutionOutcome.pending(request_id, "Execution in progress"),
                )

            try:
                result = action()
                if result is None:
                    raise ValueError("tool action returned None")
                self._entries[composite_key] = _StoredExecution(payload_digest=payload_digest, outcome=result)
                return result
            except UncertainExecutionError as uncertain:
                pending = ToolExecutionOutcome.pending(request_id, str(uncertain) or "uncertain_execution")
                self._entries[composite_key] = _StoredExecution(payload_digest=payload_digest, outcome=pending)
                return pending
