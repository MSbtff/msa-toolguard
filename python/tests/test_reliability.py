"""Reliability, replay ledger sync, budget rollback, bounded store, and MCP concurrency tests."""

from __future__ import annotations

import http.client
import json
from threading import Event, Thread
import unittest

from msa_toolguard import (
    AccessLevel,
    AgentOutput,
    AuditTrailListener,
    GuardrailError,
    InMemoryToolExecutionStore,
    LoopbackMcpServer,
    OutputVerifier,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    ToolPolicy,
    ToolSession,
    UncertainExecutionError,
    ViolationCode,
    allowed_keys_rule,
)


class ReliabilityAndMcpSafetyTest(unittest.TestCase):
    def test_replayed_execution_syncs_evidence_ledger_and_satisfies_requires_and_verifier(self) -> None:
        shared_store = InMemoryToolExecutionStore()
        retry_policy = ToolPolicy(name="retry_disposal", access_level=AccessLevel.MUTATING, max_calls=3)
        confirm_policy = ToolPolicy(name="confirm_settlement", requires=("retry_disposal",), max_calls=2)

        session1 = ToolSession(
            scope_key="acct-py-1",
            max_access_level=AccessLevel.MUTATING,
            execution_store=shared_store,
        )
        session1.register_tool(retry_policy, lambda args: ToolExecutionOutcome.completed({"movementId": 9001}))
        initial = session1.execute("retry_disposal", {"id": 1}, request_id="req-py-1")
        self.assertEqual("E1", initial.evidence_id)

        session2 = ToolSession(
            scope_key="acct-py-1",
            max_access_level=AccessLevel.MUTATING,
            execution_store=shared_store,
        )
        session2.register_tool(
            retry_policy,
            lambda args: (_ for _ in ()).throw(AssertionError("must not invoke handler on replay")),
        )
        session2.register_tool(confirm_policy, lambda args: ToolExecutionOutcome.completed({"confirmed": True}))

        replayed = session2.execute("retry_disposal", {"id": 1}, request_id="req-py-1")
        self.assertTrue(replayed.replayed)
        self.assertEqual("E1", replayed.evidence_id)
        self.assertTrue(session2.evidence_ledger.has_completed_tool("retry_disposal"))

        confirmed = session2.execute("confirm_settlement", {})
        self.assertEqual("E2", confirmed.evidence_id)

        verifier = OutputVerifier(required_tools=["retry_disposal", "confirm_settlement"])
        result = verifier.verify(AgentOutput("done", ("E1", "E2")), session2.evidence_ledger)
        self.assertTrue(result.verified)

    def test_replay_avoids_overwriting_occupied_evidence_id_and_deduplicates(self) -> None:
        shared_store = InMemoryToolExecutionStore()
        retry_policy = ToolPolicy(name="retry_disposal", access_level=AccessLevel.MUTATING, max_calls=5)
        read_policy = ToolPolicy(name="get_disposal", max_calls=2)

        s1 = ToolSession(scope_key="acct-py-2", max_access_level=AccessLevel.MUTATING, execution_store=shared_store)
        s1.register_tool(retry_policy, lambda args: ToolExecutionOutcome.completed({"ok": True}))
        self.assertEqual("E1", s1.execute("retry_disposal", {"id": 2}, request_id="req-py-2").evidence_id)

        s2 = ToolSession(scope_key="acct-py-2", max_access_level=AccessLevel.MUTATING, execution_store=shared_store)
        s2.register_tool(read_policy, lambda args: ToolExecutionOutcome.completed({"status": "PENDING"}))
        s2.register_tool(retry_policy, lambda args: (_ for _ in ()).throw(AssertionError("should replay")))

        self.assertEqual("E1", s2.execute("get_disposal", {}).evidence_id)
        first_replay = s2.execute("retry_disposal", {"id": 2}, request_id="req-py-2")
        self.assertTrue(first_replay.replayed)
        self.assertEqual("E2", first_replay.evidence_id)
        self.assertEqual("get_disposal", s2.evidence_ledger.find("E1").tool_name)  # type: ignore[union-attr]

        second_replay = s2.execute("retry_disposal", {"id": 2}, request_id="req-py-2")
        self.assertEqual("E2", second_replay.evidence_id)
        self.assertEqual(["E1", "E2"], s2.evidence_ledger.all_ids())

    def test_idempotency_conflict_does_not_leak_budget_and_handler_crash_emits_audit_event(self) -> None:
        audit = AuditTrailListener()
        calls = 0

        def flaky(args: dict) -> ToolExecutionOutcome:
            nonlocal calls
            calls += 1
            if calls == 1:
                raise UncertainExecutionError("dropped")
            return ToolExecutionOutcome.completed({"ok": True})

        session = ToolSession(
            scope_key="acct-budget-py",
            max_access_level=AccessLevel.MUTATING,
            max_total_calls=3,
            listeners=[audit],
        )
        session.register_tool(ToolPolicy(name="retry_disposal", access_level=AccessLevel.MUTATING, max_calls=2), flaky)
        session.register_tool(
            ToolPolicy(name="crash_tool", max_calls=1),
            lambda args: (_ for _ in ()).throw(RuntimeError("db down")),
        )

        self.assertEqual(
            ToolExecutionStatus.PENDING,
            session.execute("retry_disposal", {"qty": 1}, request_id="req-b").status,
        )
        with self.assertRaises(GuardrailError) as conflict:
            session.execute("retry_disposal", {"qty": 99}, request_id="req-b")
        self.assertEqual(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.exception.violation.code)
        self.assertEqual(1, session.total_call_count)

        completed = session.execute("retry_disposal", {"qty": 1}, request_id="req-b")
        self.assertEqual(ToolExecutionStatus.COMPLETED, completed.status)
        self.assertEqual(2, session.total_call_count)

        with self.assertRaises(RuntimeError):
            session.execute("crash_tool", {})
        last_event = audit.entries()[-1].event
        self.assertEqual("crash_tool", last_event.tool_name)
        self.assertTrue(last_event.allowed)
        self.assertIsNone(last_event.status)
        self.assertIsNone(last_event.violation_code)

    def test_in_memory_store_prevents_delimiter_collisions_and_enforces_bounds_and_ttl(self) -> None:
        now = [100.0]
        store = InMemoryToolExecutionStore(max_entries=2, entry_ttl_seconds=60.0, clock=lambda: now[0])
        runs = 0

        def next_ok() -> ToolExecutionOutcome:
            nonlocal runs
            runs += 1
            return ToolExecutionOutcome.completed({"run": runs})

        o1 = store.execute_idempotent("a::b", "c", "r1", "d1", next_ok)
        o2 = store.execute_idempotent("a", "b::c", "r1", "d2", next_ok)
        self.assertFalse(o1.replayed)
        self.assertFalse(o2.replayed)
        self.assertEqual(2, store.size())

        store.execute_idempotent("s3", "t", "r3", "d3", next_ok)
        self.assertEqual(2, store.size())

        now[0] += 61.0
        self.assertEqual(0, store.size())
        o4 = store.execute_idempotent("s3", "t", "r3", "d3", next_ok)
        self.assertFalse(o4.replayed)
        self.assertEqual(4, runs)

    def test_mcp_strips_request_id_preserves_fact_errors_and_serves_concurrent_requests(self) -> None:
        slow_started = Event()
        allow_finish = Event()

        session = ToolSession(max_access_level=AccessLevel.MUTATING, bound_parameters={"accountId": 7})
        session.register_tool(
            ToolPolicy(
                name="inspect_failure",
                max_calls=2,
                bound_parameters=("accountId",),
            ),
            lambda args: ToolExecutionOutcome.completed(
                {"status": "PENDING", "error": "previous downstream timeout", "accountId": args["accountId"]}
            ),
        )
        session.register_tool(
            ToolPolicy(
                name="retry_strict",
                access_level=AccessLevel.MUTATING,
                max_calls=2,
                bound_parameters=("accountId",),
                deny_rules=(allowed_keys_rule(["accountId", "reason"]),),
            ),
            lambda args: ToolExecutionOutcome.completed({"reason": args["reason"]}),
        )

        def slow_tool(args: dict) -> ToolExecutionOutcome:
            slow_started.set()
            allow_finish.wait(timeout=3.0)
            return ToolExecutionOutcome.completed({"done": True})

        session.register_tool(ToolPolicy(name="slow_read", max_calls=2), slow_tool)
        session.register_tool(
            ToolPolicy(name="boom_tool", max_calls=1),
            lambda args: (_ for _ in ()).throw(RuntimeError("unexpected crash")),
        )

        tools = [
            {"name": "inspect_failure", "description": "Inspect", "inputSchema": {"type": "object"}},
            {"name": "retry_strict", "description": "Retry", "inputSchema": {"type": "object"}},
            {"name": "slow_read", "description": "Slow", "inputSchema": {"type": "object"}},
            {"name": "boom_tool", "description": "Boom", "inputSchema": {"type": "object"}},
        ]

        with LoopbackMcpServer.for_session(session, tools) as server:
            def post(body: dict):
                conn = http.client.HTTPConnection("127.0.0.1", server.port, timeout=3)
                headers = {"Content-Type": "application/json", "Authorization": f"Bearer {server.secret}"}
                try:
                    conn.request("POST", "/mcp", json.dumps(body).encode("utf-8"), headers)
                    resp = conn.getresponse()
                    return resp.status, json.loads(resp.read())
                finally:
                    conn.close()

            # Domain fact containing "status": "PENDING" and "error": "..." must NOT trigger isError
            status, inspect_resp = post(
                {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "inspect_failure", "arguments": {}}}
            )
            self.assertEqual(200, status)
            self.assertFalse(inspect_resp["result"]["isError"])
            inspect_payload = json.loads(inspect_resp["result"]["content"][0]["text"])
            self.assertEqual("COMPLETED", inspect_payload["executionStatus"])
            self.assertEqual("PENDING", inspect_payload["status"])
            self.assertEqual("previous downstream timeout", inspect_payload["error"])

            # requestId is stripped before allowed_keys_rule runs
            status, strict_resp = post(
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "tools/call",
                    "params": {
                        "name": "retry_strict",
                        "arguments": {"reason": "expired", "requestId": "req-mcp-strict-1"},
                    },
                }
            )
            self.assertEqual(200, status)
            self.assertFalse(strict_resp["result"]["isError"])
            strict_payload = json.loads(strict_resp["result"]["content"][0]["text"])
            self.assertEqual("COMPLETED", strict_payload["executionStatus"])
            self.assertEqual("expired", strict_payload["reason"])

            # Concurrent requests: slow_read in flight must NOT block ping
            slow_result: list[tuple[int, dict]] = []
            worker = Thread(
                target=lambda: slow_result.append(
                    post({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "slow_read", "arguments": {}}})
                )
            )
            worker.start()
            self.assertTrue(slow_started.wait(timeout=2.0))
            ping_status, ping_resp = post({"jsonrpc": "2.0", "id": 4, "method": "ping"})
            self.assertEqual(200, ping_status)
            self.assertEqual({}, ping_resp["result"])
            allow_finish.set()
            worker.join(timeout=3.0)
            self.assertEqual(200, slow_result[0][0])

            # Unhandled crash returns clean JSON-RPC -32603 error instead of dropping HTTP connection
            boom_status, boom_resp = post(
                {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "boom_tool", "arguments": {}}}
            )
            self.assertEqual(200, boom_status)
            self.assertEqual(-32603, boom_resp["error"]["code"])


if __name__ == "__main__":
    unittest.main()
