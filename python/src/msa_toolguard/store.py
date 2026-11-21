"""Idempotent execution store SPI and bounded, thread-safe in-memory implementation."""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass
from threading import Lock
import time
from typing import Callable, Protocol

from .models import (
    GuardrailError,
    GuardrailViolation,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    UncertainExecutionError,
    ViolationCode,
)

DEFAULT_MAX_ENTRIES = 10_000
DEFAULT_ENTRY_TTL_SECONDS = 86_400.0
_LOCK_STRIPES = 256


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
    updated_at: float


class InMemoryToolExecutionStore:
    def __init__(
        self,
        *,
        max_entries: int = DEFAULT_MAX_ENTRIES,
        entry_ttl_seconds: float = DEFAULT_ENTRY_TTL_SECONDS,
        clock: Callable[[], float] | None = None,
    ) -> None:
        if max_entries < 1:
            raise ValueError("max_entries must be >= 1")
        if entry_ttl_seconds <= 0:
            raise ValueError("entry_ttl_seconds must be positive")
        self._max_entries = max_entries
        self._entry_ttl_seconds = entry_ttl_seconds
        self._clock = clock or time.monotonic
        self._stripes = tuple(Lock() for _ in range(_LOCK_STRIPES))
        self._entries_lock = Lock()
        self._entries: OrderedDict[tuple[str, str, str], _StoredExecution] = OrderedDict()

    def size(self) -> int:
        with self._entries_lock:
            self._prune_expired_locked(self._clock())
            return len(self._entries)

    def execute_idempotent(
        self,
        scope_key: str,
        tool_name: str,
        request_id: str,
        payload_digest: str,
        action: Callable[[], ToolExecutionOutcome],
    ) -> ToolExecutionOutcome:
        key = (scope_key, tool_name, request_id)
        stripe_lock = self._stripes[hash(key) % _LOCK_STRIPES]
        with stripe_lock:
            now = self._clock()
            existing = self._get_non_expired(key, now)
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
                self._put_bounded(
                    key,
                    _StoredExecution(
                        payload_digest=payload_digest,
                        outcome=ToolExecutionOutcome.pending(request_id, "Execution in progress"),
                        updated_at=now,
                    ),
                    now,
                )

            try:
                result = action()
                if result is None:
                    raise ValueError("tool action returned None")
                completed_at = self._clock()
                self._put_bounded(
                    key,
                    _StoredExecution(payload_digest=payload_digest, outcome=result, updated_at=completed_at),
                    completed_at,
                )
                return result
            except UncertainExecutionError as uncertain:
                pending = ToolExecutionOutcome.pending(request_id, str(uncertain) or "uncertain_execution")
                failed_at = self._clock()
                self._put_bounded(
                    key,
                    _StoredExecution(payload_digest=payload_digest, outcome=pending, updated_at=failed_at),
                    failed_at,
                )
                return pending

    def _get_non_expired(self, key: tuple[str, str, str], now: float) -> _StoredExecution | None:
        with self._entries_lock:
            existing = self._entries.get(key)
            if existing is None:
                return None
            if now - existing.updated_at >= self._entry_ttl_seconds:
                del self._entries[key]
                return None
            self._entries.move_to_end(key)
            return existing

    def _put_bounded(self, key: tuple[str, str, str], record: _StoredExecution, now: float) -> None:
        with self._entries_lock:
            self._entries[key] = record
            self._entries.move_to_end(key)
            if len(self._entries) > self._max_entries:
                self._prune_expired_locked(now)
            if len(self._entries) > self._max_entries:
                for candidate_key in list(self._entries.keys()):
                    if len(self._entries) <= self._max_entries:
                        break
                    if (
                        candidate_key != key
                        and self._entries[candidate_key].outcome.status != ToolExecutionStatus.PENDING
                    ):
                        del self._entries[candidate_key]
            while len(self._entries) > self._max_entries:
                oldest_key = next(k for k in self._entries if k != key)
                del self._entries[oldest_key]

    def _prune_expired_locked(self, now: float) -> None:
        expired = [k for k, v in self._entries.items() if now - v.updated_at >= self._entry_ttl_seconds]
        for k in expired:
            del self._entries[k]
