"""SQLite-backed durable ToolExecutionStore using only the Python standard library."""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sqlite3
from threading import Lock
import time
from typing import Callable

from .models import (
    GuardrailError,
    GuardrailViolation,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    UncertainExecutionError,
    ViolationCode,
)

DEFAULT_TABLE_NAME = "toolguard_executions"
DEFAULT_LOCK_TIMEOUT_SECONDS = 10.0
_SAFE_IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")


def _validate_table_name(table_name: str) -> str:
    if not isinstance(table_name, str) or not _SAFE_IDENTIFIER_RE.fullmatch(table_name):
        raise ValueError(f"Invalid SQL table identifier: {table_name!r}")
    return table_name


def _purge_index_name(table_name: str) -> str:
    candidate = f"idx_{table_name}_status_updated"
    if len(candidate) <= 63:
        return candidate
    suffix = "_status_updated"
    return f"idx_{table_name}"[: 63 - len(suffix)] + suffix


def _is_lock_timeout(error: sqlite3.OperationalError) -> bool:
    msg = str(error).lower()
    return "locked" in msg or "busy" in msg


class SQLiteToolExecutionStore:
    """Durable, restart-safe ToolExecutionStore backed by stdlib sqlite3 (WAL + BEGIN IMMEDIATE).

    Schema and behavior mirror Java's JdbcToolExecutionStore:
    - Persists PENDING / COMPLETED / REJECTED state across process restarts
    - Enforces payload digest equality on retries (IDEMPOTENCY_CONFLICT)
    - Returns PENDING (never fabricates COMPLETED) on UncertainExecutionError or lock timeout
    - Provides purge_terminal_before() that cleans old terminal rows while preserving PENDING rows
    """

    def __init__(
        self,
        db_path: str | Path,
        *,
        table_name: str = DEFAULT_TABLE_NAME,
        initialize_schema: bool = True,
        lock_timeout_seconds: float = DEFAULT_LOCK_TIMEOUT_SECONDS,
        clock_ms: Callable[[], int] | None = None,
    ) -> None:
        if lock_timeout_seconds <= 0:
            raise ValueError("lock_timeout_seconds must be positive")
        self._db_path = str(db_path)
        self._table_name = _validate_table_name(table_name)
        self._lock_timeout_seconds = float(lock_timeout_seconds)
        self._clock_ms = clock_ms or (lambda: int(time.time() * 1000))
        self._thread_lock = Lock()
        if self._db_path != ":memory:":
            Path(self._db_path).parent.mkdir(parents=True, exist_ok=True)
        if initialize_schema:
            self.initialize_schema()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(
            self._db_path,
            timeout=self._lock_timeout_seconds,
            isolation_level=None,
            check_same_thread=False,
        )
        busy_ms = max(1, int(self._lock_timeout_seconds * 1000))
        conn.execute(f"PRAGMA busy_timeout = {busy_ms}")
        if self._db_path != ":memory:":
            conn.execute("PRAGMA journal_mode = WAL")
        return conn

    def initialize_schema(self) -> None:
        index_name = _purge_index_name(self._table_name)
        with self._thread_lock:
            conn = self._connect()
            try:
                conn.execute(
                    f"""
                    CREATE TABLE IF NOT EXISTS {self._table_name} (
                        scope_key       TEXT    NOT NULL,
                        tool_name       TEXT    NOT NULL,
                        request_id      TEXT    NOT NULL,
                        payload_digest  TEXT    NOT NULL,
                        status          TEXT    NOT NULL,
                        evidence_id     TEXT,
                        rejection_code  TEXT,
                        message         TEXT,
                        facts_json      TEXT    NOT NULL,
                        created_at_ms   INTEGER NOT NULL,
                        updated_at_ms   INTEGER NOT NULL,
                        PRIMARY KEY (scope_key, tool_name, request_id)
                    )
                    """
                )
                conn.execute(
                    f"CREATE INDEX IF NOT EXISTS {index_name} "
                    f"ON {self._table_name} (status, updated_at_ms)"
                )
            finally:
                conn.close()

    def execute_idempotent(
        self,
        scope_key: str,
        tool_name: str,
        request_id: str,
        payload_digest: str,
        action: Callable[[], ToolExecutionOutcome],
    ) -> ToolExecutionOutcome:
        with self._thread_lock:
            conn = self._connect()
            try:
                if not self._ensure_pending_row(conn, scope_key, tool_name, request_id, payload_digest):
                    return ToolExecutionOutcome.pending(request_id, "Timed out waiting for SQLite execution lock")

                try:
                    conn.execute("BEGIN IMMEDIATE")
                except sqlite3.OperationalError as err:
                    if _is_lock_timeout(err):
                        return ToolExecutionOutcome.pending(request_id, "Timed out waiting for SQLite execution lock")
                    raise

                try:
                    row = conn.execute(
                        f"SELECT payload_digest, status, evidence_id, rejection_code, message, facts_json "
                        f"FROM {self._table_name} WHERE scope_key = ? AND tool_name = ? AND request_id = ?",
                        (scope_key, tool_name, request_id),
                    ).fetchone()
                    if row is None:
                        conn.execute("ROLLBACK")
                        raise RuntimeError(f"Execution row not found after registration for requestId '{request_id}'")

                    stored_digest, raw_status, evidence_id, rejection_code, message, facts_json = row
                    if stored_digest != payload_digest:
                        conn.execute("ROLLBACK")
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

                    status = ToolExecutionStatus(raw_status)
                    if status != ToolExecutionStatus.PENDING:
                        conn.execute("ROLLBACK")
                        return self._row_to_outcome(
                            status, evidence_id, request_id, rejection_code, message, facts_json, replayed=True
                        )

                    try:
                        result = action()
                        if result is None:
                            raise ValueError("tool action returned None")
                        self._update_row(conn, scope_key, tool_name, request_id, result)
                        conn.execute("COMMIT")
                        return result
                    except UncertainExecutionError as uncertain:
                        pending = ToolExecutionOutcome.pending(request_id, str(uncertain) or "uncertain_execution")
                        self._update_row(conn, scope_key, tool_name, request_id, pending)
                        conn.execute("COMMIT")
                        return pending
                    except BaseException:
                        self._rollback_quietly(conn)
                        raise
                except GuardrailError:
                    raise
                except sqlite3.OperationalError as err:
                    self._rollback_quietly(conn)
                    if _is_lock_timeout(err):
                        return ToolExecutionOutcome.pending(request_id, "Timed out waiting for SQLite execution lock")
                    raise
            finally:
                conn.close()

    def find(self, scope_key: str, tool_name: str, request_id: str) -> ToolExecutionOutcome | None:
        conn = self._connect()
        try:
            row = conn.execute(
                f"SELECT status, evidence_id, rejection_code, message, facts_json "
                f"FROM {self._table_name} WHERE scope_key = ? AND tool_name = ? AND request_id = ?",
                (scope_key, tool_name, request_id),
            ).fetchone()
            if row is None:
                return None
            raw_status, evidence_id, rejection_code, message, facts_json = row
            return self._row_to_outcome(
                ToolExecutionStatus(raw_status),
                evidence_id,
                request_id,
                rejection_code,
                message,
                facts_json,
                replayed=False,
            )
        finally:
            conn.close()

    def purge_terminal_before(self, cutoff: datetime | int) -> int:
        if isinstance(cutoff, datetime):
            dt = cutoff if cutoff.tzinfo is not None else cutoff.replace(tzinfo=timezone.utc)
            cutoff_ms = int(dt.timestamp() * 1000)
        else:
            cutoff_ms = int(cutoff)
        with self._thread_lock:
            conn = self._connect()
            try:
                conn.execute("BEGIN IMMEDIATE")
                cursor = conn.execute(
                    f"DELETE FROM {self._table_name} WHERE status <> ? AND updated_at_ms < ?",
                    (ToolExecutionStatus.PENDING.value, cutoff_ms),
                )
                deleted = cursor.rowcount
                conn.execute("COMMIT")
                return deleted
            except BaseException:
                self._rollback_quietly(conn)
                raise
            finally:
                conn.close()

    def _ensure_pending_row(
        self,
        conn: sqlite3.Connection,
        scope_key: str,
        tool_name: str,
        request_id: str,
        payload_digest: str,
    ) -> bool:
        now_ms = self._clock_ms()
        try:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute(
                f"INSERT OR IGNORE INTO {self._table_name} "
                f"(scope_key, tool_name, request_id, payload_digest, status, evidence_id, rejection_code, "
                f"message, facts_json, created_at_ms, updated_at_ms) "
                f"VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?)",
                (
                    scope_key,
                    tool_name,
                    request_id,
                    payload_digest,
                    ToolExecutionStatus.PENDING.value,
                    "Execution in progress",
                    "{}",
                    now_ms,
                    now_ms,
                ),
            )
            conn.execute("COMMIT")
            return True
        except sqlite3.OperationalError as err:
            self._rollback_quietly(conn)
            if _is_lock_timeout(err):
                return False
            raise

    def _update_row(
        self,
        conn: sqlite3.Connection,
        scope_key: str,
        tool_name: str,
        request_id: str,
        outcome: ToolExecutionOutcome,
    ) -> None:
        conn.execute(
            f"UPDATE {self._table_name} "
            f"SET status = ?, evidence_id = ?, rejection_code = ?, message = ?, facts_json = ?, updated_at_ms = ? "
            f"WHERE scope_key = ? AND tool_name = ? AND request_id = ?",
            (
                outcome.status.value,
                outcome.evidence_id,
                outcome.rejection_code,
                outcome.message,
                json.dumps(outcome.facts, ensure_ascii=False, separators=(",", ":")),
                self._clock_ms(),
                scope_key,
                tool_name,
                request_id,
            ),
        )

    @staticmethod
    def _row_to_outcome(
        status: ToolExecutionStatus,
        evidence_id: str | None,
        request_id: str,
        rejection_code: str | None,
        message: str | None,
        facts_json: str | None,
        *,
        replayed: bool,
    ) -> ToolExecutionOutcome:
        facts = json.loads(facts_json) if facts_json else {}
        return ToolExecutionOutcome(
            status=status,
            evidence_id=evidence_id,
            request_id=request_id,
            rejection_code=rejection_code,
            message=message,
            replayed=replayed,
            facts=facts if isinstance(facts, dict) else {},
        )

    @staticmethod
    def _rollback_quietly(conn: sqlite3.Connection) -> None:
        try:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
        except sqlite3.Error:
            pass
