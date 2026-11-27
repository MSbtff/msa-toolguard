"""Durable SQLiteToolExecutionStore restart recovery, purge, concurrency, and lock-timeout tests."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
from threading import Barrier
import time
import unittest

from msa_toolguard import (
    AccessLevel,
    AgentOutput,
    GuardrailError,
    OutputVerifier,
    SQLiteToolExecutionStore,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    ToolPolicy,
    ToolSession,
    UncertainExecutionError,
    ViolationCode,
)


class SQLiteToolExecutionStoreTest(unittest.TestCase):
    def test_restart_recovery_conflict_replay_ledger_sync_and_purge(self) -> None:
        with TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "nested" / "toolguard.db"
            t0 = datetime(2026, 11, 26, 0, 0, 0, tzinfo=timezone.utc)
            t0_ms = int(t0.timestamp() * 1000)

            retry_policy = ToolPolicy(name="retry_disposal", access_level=AccessLevel.MUTATING, max_calls=5)
            calls = 0

            # Process 1: uncertain failure persisted as PENDING
            store1 = SQLiteToolExecutionStore(db_path, clock_ms=lambda: t0_ms)
            store1.initialize_schema()  # idempotent
            s1 = ToolSession(
                scope_key="acct-sqlite-1",
                max_access_level=AccessLevel.MUTATING,
                execution_store=store1,
            )

            def fail_uncertain(_args: dict) -> ToolExecutionOutcome:
                nonlocal calls
                calls += 1
                raise UncertainExecutionError("response dropped")

            s1.register_tool(retry_policy, fail_uncertain)
            pending = s1.execute("retry_disposal", {"qty": 3}, request_id="req-sq-1")
            self.assertEqual(ToolExecutionStatus.PENDING, pending.status)
            self.assertFalse(pending.replayed)
            self.assertEqual(ToolExecutionStatus.PENDING, store1.find("acct-sqlite-1", "retry_disposal", "req-sq-1").status)  # type: ignore[union-attr]

            # Process 2 (fresh store instance simulating restart): conflict blocked, valid retry completes once
            store2 = SQLiteToolExecutionStore(db_path, initialize_schema=False, clock_ms=lambda: t0_ms)
            s2 = ToolSession(
                scope_key="acct-sqlite-1",
                max_access_level=AccessLevel.MUTATING,
                execution_store=store2,
            )

            def succeed(_args: dict) -> ToolExecutionOutcome:
                nonlocal calls
                calls += 1
                return ToolExecutionOutcome.completed({"movementId": 9901, "quantityAfter": 14})

            s2.register_tool(retry_policy, succeed)
            with self.assertRaises(GuardrailError) as conflict:
                s2.execute("retry_disposal", {"qty": 99}, request_id="req-sq-1")
            self.assertEqual(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.exception.violation.code)
            self.assertEqual(0, s2.total_call_count)

            completed = s2.execute("retry_disposal", {"qty": 3}, request_id="req-sq-1")
            self.assertEqual(ToolExecutionStatus.COMPLETED, completed.status)
            self.assertFalse(completed.replayed)
            self.assertEqual(9901, completed.facts["movementId"])
            self.assertEqual(2, calls)

            # Process 3: replays persisted COMPLETED outcome, syncs EvidenceLedger, satisfies requires + verifier
            store3 = SQLiteToolExecutionStore(db_path, clock_ms=lambda: t0_ms)
            s3 = ToolSession(
                scope_key="acct-sqlite-1",
                max_access_level=AccessLevel.MUTATING,
                execution_store=store3,
            )
            s3.register_tool(
                retry_policy,
                lambda _args: (_ for _ in ()).throw(AssertionError("must not invoke downstream on SQLite replay")),
            )
            s3.register_tool(
                ToolPolicy(name="confirm_sqlite", requires=("retry_disposal",), max_calls=1),
                lambda _args: ToolExecutionOutcome.completed({"confirmed": True}),
            )

            replayed = s3.execute("retry_disposal", {"qty": 3}, request_id="req-sq-1")
            self.assertTrue(replayed.replayed)
            self.assertEqual(completed.evidence_id, replayed.evidence_id)
            self.assertTrue(s3.evidence_ledger.has_completed_tool("retry_disposal"))

            confirmed = s3.execute("confirm_sqlite", {})
            vr = OutputVerifier(required_tools=["retry_disposal", "confirm_sqlite"]).verify(
                AgentOutput("confirmed", (replayed.evidence_id or "", confirmed.evidence_id or "")),
                s3.evidence_ledger,
            )
            self.assertTrue(vr.verified)

            # Persist REJECTED + PENDING, verify purge_terminal_before cleans terminal rows and keeps PENDING
            store3.execute_idempotent(
                "acct-sqlite-1",
                "retry_disposal",
                "req-sq-rej",
                "digest-rej",
                lambda: ToolExecutionOutcome.rejected("NO_STOCK", "empty", {"available": 0}),
            )
            rej_replay = store3.execute_idempotent(
                "acct-sqlite-1",
                "retry_disposal",
                "req-sq-rej",
                "digest-rej",
                lambda: (_ for _ in ()).throw(AssertionError("rejected should replay")),
            )
            self.assertTrue(rej_replay.replayed)
            self.assertEqual(ToolExecutionStatus.REJECTED, rej_replay.status)
            self.assertEqual("NO_STOCK", rej_replay.rejection_code)

            store3.execute_idempotent(
                "acct-sqlite-1",
                "retry_disposal",
                "req-sq-pend",
                "digest-pend",
                lambda: ToolExecutionOutcome.pending("req-sq-pend", "waiting"),
            )

            purged = store3.purge_terminal_before(t0 + timedelta(hours=1))
            self.assertEqual(2, purged)
            self.assertIsNone(store3.find("acct-sqlite-1", "retry_disposal", "req-sq-1"))
            self.assertIsNone(store3.find("acct-sqlite-1", "retry_disposal", "req-sq-rej"))
            self.assertEqual(
                ToolExecutionStatus.PENDING,
                store3.find("acct-sqlite-1", "retry_disposal", "req-sq-pend").status,  # type: ignore[union-attr]
            )

    def test_serializes_16_concurrent_threads_with_zero_duplicate_mutations(self) -> None:
        with TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "concurrent.db"
            store = SQLiteToolExecutionStore(db_path)
            policy = ToolPolicy(name="retry_disposal", access_level=AccessLevel.MUTATING, max_calls=32)
            concurrency = 16
            barrier = Barrier(concurrency)
            executions = [0]

            def worker() -> ToolExecutionOutcome:
                session = ToolSession(
                    scope_key="acct-sq-16",
                    max_access_level=AccessLevel.MUTATING,
                    execution_store=store,
                )

                def handler(_args: dict) -> ToolExecutionOutcome:
                    executions[0] += 1
                    time.sleep(0.05)
                    return ToolExecutionOutcome.completed({"movementId": 1616})

                session.register_tool(policy, handler)
                barrier.wait(timeout=10)
                return session.execute("retry_disposal", {"id": 16}, request_id="req-sq-16")

            with ThreadPoolExecutor(max_workers=concurrency) as pool:
                outcomes = [f.result(timeout=15) for f in [pool.submit(worker) for _ in range(concurrency)]]

            fresh = sum(1 for o in outcomes if not o.replayed)
            replayed = sum(1 for o in outcomes if o.replayed)
            self.assertEqual(1, executions[0])
            self.assertEqual(1, fresh)
            self.assertEqual(concurrency - 1, replayed)

    def test_returns_pending_on_external_lock_timeout_and_rejects_invalid_table_name(self) -> None:
        with self.assertRaises(ValueError):
            SQLiteToolExecutionStore(":memory:", table_name="bad; DROP TABLE x")
        with self.assertRaises(ValueError):
            SQLiteToolExecutionStore(":memory:", lock_timeout_seconds=0)

        with TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "lock_timeout.db"
            store = SQLiteToolExecutionStore(db_path, lock_timeout_seconds=0.1)

            # Hold an external EXCLUSIVE lock from another SQLite connection
            blocker = sqlite3.connect(str(db_path), isolation_level=None)
            try:
                blocker.execute("BEGIN EXCLUSIVE")
                outcome = store.execute_idempotent(
                    "acct-lock",
                    "retry_disposal",
                    "req-lock-1",
                    "digest-lock",
                    lambda: ToolExecutionOutcome.completed({"shouldNotRun": True}),
                )
                self.assertEqual(ToolExecutionStatus.PENDING, outcome.status)
                self.assertIn("Timed out waiting for SQLite execution lock", outcome.message or "")
            finally:
                blocker.execute("ROLLBACK")
                blocker.close()


if __name__ == "__main__":
    unittest.main()
