"""Human-in-the-Loop approval gate, execution timeout, and session deadline tests."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import time
import unittest

from msa_toolguard import (
    AccessLevel,
    ApprovalDecision,
    AuditLogFormatter,
    AuditTrailListener,
    GuardrailError,
    InMemoryApprovalGate,
    InMemoryToolExecutionStore,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    ToolPolicy,
    ToolSession,
    UncertainExecutionError,
    ViolationCode,
)

CONTRACTS_DIR = Path(__file__).resolve().parents[2] / "contracts"


class ApprovalAndTimeoutTest(unittest.TestCase):
    def test_fail_closed_payload_digest_binding_single_use_and_idempotent_retry(self) -> None:
        now = [datetime(2026, 11, 22, 0, 0, 0, tzinfo=timezone.utc)]
        gate = InMemoryApprovalGate(clock=lambda: now[0])
        calls = 0

        policy = ToolPolicy(
            name="refund_payment",
            access_level=AccessLevel.MUTATING,
            max_calls=5,
            bound_parameters=("accountId",),
            require_approval=True,
        )

        # Fail-closed default without ApprovalGate
        fail_closed = ToolSession(
            scope_key="acct-py-7",
            max_access_level=AccessLevel.MUTATING,
            bound_parameters={"accountId": 7},
        )
        fail_closed.register_tool(policy, lambda args: ToolExecutionOutcome.completed({"refunded": True}))
        with self.assertRaises(GuardrailError) as denied_default:
            fail_closed.execute("refund_payment", {"amount": 500}, request_id="req-0")
        self.assertEqual(ViolationCode.APPROVAL_REQUIRED, denied_default.exception.violation.code)
        self.assertIn("payloadDigest=", denied_default.exception.violation.repair_hint)
        self.assertEqual(0, fail_closed.total_call_count)

        def refund_handler(args: dict) -> ToolExecutionOutcome:
            nonlocal calls
            calls += 1
            if calls == 1:
                raise UncertainExecutionError("gateway timeout")
            return ToolExecutionOutcome.completed({"amount": args["amount"], "refunded": True})

        session = ToolSession(
            scope_key="acct-py-7",
            max_access_level=AccessLevel.MUTATING,
            bound_parameters={"accountId": 7},
            approval_gate=gate,
            clock=lambda: now[0],
        )
        session.register_tool(policy, refund_handler)

        approval_id = gate.grant("acct-py-7", "refund_payment", {"accountId": 7, "amount": 500}, "ops", 300.0)
        self.assertTrue(approval_id.startswith("apr-"))

        # Tampered arguments blocked
        with self.assertRaises(GuardrailError) as tampered:
            session.execute("refund_payment", {"amount": 99999}, request_id="req-1")
        self.assertEqual(ViolationCode.APPROVAL_REQUIRED, tampered.exception.violation.code)
        self.assertEqual(0, calls)

        # Exact payload executes -> PENDING on uncertain failure
        pending = session.execute("refund_payment", {"amount": 500}, request_id="req-1")
        self.assertEqual(ToolExecutionStatus.PENDING, pending.status)

        # Safe retry with SAME requestId & payload passes single-use grant and completes
        completed = session.execute("refund_payment", {"amount": 500}, request_id="req-1")
        self.assertEqual(ToolExecutionStatus.COMPLETED, completed.status)
        self.assertEqual(2, calls)

        # Reusing consumed grant for NEW requestId is blocked
        with self.assertRaises(GuardrailError) as reused:
            session.execute("refund_payment", {"amount": 500}, request_id="req-2")
        self.assertEqual(ViolationCode.APPROVAL_REQUIRED, reused.exception.violation.code)

        # TTL expiration & revocation
        exp_id = gate.grant("acct-py-7", "refund_payment", {"accountId": 7, "amount": 300}, "ops", 10.0)
        now[0] += timedelta(seconds=11)
        with self.assertRaises(GuardrailError):
            session.execute("refund_payment", {"amount": 300}, request_id="req-exp")
        self.assertTrue(gate.revoke(exp_id))
        self.assertFalse(gate.revoke(exp_id))

        rev_id = gate.grant("acct-py-7", "refund_payment", {"accountId": 7, "amount": 200}, "ops", 300.0)
        self.assertTrue(gate.revoke(rev_id))
        with self.assertRaises(GuardrailError):
            session.execute("refund_payment", {"amount": 200}, request_id="req-rev")

    def test_conditional_approval_predicate_and_pending_ticket_hint(self) -> None:
        class TicketGate:
            def check(self, request):
                return ApprovalDecision.pending_approval("ticket-99", "Needs finance approval")

        policy = ToolPolicy(
            name="dispose_stock",
            access_level=AccessLevel.MUTATING,
            max_calls=5,
            approval_predicate=lambda _tool, args: int(args.get("qty", 0)) >= 50,
        )
        session = ToolSession(max_access_level=AccessLevel.MUTATING, approval_gate=TicketGate())
        session.register_tool(policy, lambda args: ToolExecutionOutcome.completed({"qty": args["qty"]}))

        small = session.execute("dispose_stock", {"qty": 10}, request_id="req-small")
        self.assertEqual(ToolExecutionStatus.COMPLETED, small.status)

        with self.assertRaises(GuardrailError) as ctx:
            session.execute("dispose_stock", {"qty": 100}, request_id="req-large")
        self.assertEqual(ViolationCode.APPROVAL_REQUIRED, ctx.exception.violation.code)
        self.assertIn("ticket-99", ctx.exception.violation.repair_hint)
        self.assertIn("payloadDigest=", ctx.exception.violation.repair_hint)

    def test_execution_timeout_converts_hung_call_to_pending_and_recovers_on_retry(self) -> None:
        store = InMemoryToolExecutionStore()
        calls = 0

        def slow_handler(args: dict) -> ToolExecutionOutcome:
            nonlocal calls
            calls += 1
            if calls == 1:
                time.sleep(1.5)
                return ToolExecutionOutcome.completed({"late": True})
            return ToolExecutionOutcome.completed({"recovered": True})

        session = ToolSession(max_access_level=AccessLevel.MUTATING, execution_store=store)
        session.register_tool(
            ToolPolicy(
                name="slow_downstream",
                access_level=AccessLevel.MUTATING,
                max_calls=5,
                execution_timeout_ms=80,
            ),
            slow_handler,
        )

        started = time.monotonic()
        pending = session.execute("slow_downstream", {"id": 1}, request_id="req-slow-py")
        elapsed = time.monotonic() - started
        self.assertEqual(ToolExecutionStatus.PENDING, pending.status)
        self.assertIn("exceeded execution timeout", pending.message or "")
        self.assertEqual("E1", pending.evidence_id)
        self.assertLess(elapsed, 0.8)

        recovered = session.execute("slow_downstream", {"id": 1}, request_id="req-slow-py")
        self.assertEqual(ToolExecutionStatus.COMPLETED, recovered.status)
        self.assertTrue(recovered.facts["recovered"])

    def test_session_timeout_and_contract_schema_alignment(self) -> None:
        now = [datetime(2026, 11, 22, 1, 0, 0, tzinfo=timezone.utc)]
        audit = AuditTrailListener(AuditLogFormatter(), clock=lambda: now[0])
        policy = ToolPolicy(
            name="high_risk_write",
            access_level=AccessLevel.MUTATING,
            max_calls=3,
            require_approval=True,
            execution_timeout_ms=2500,
        )

        policy_schema = json.loads((CONTRACTS_DIR / "tool-policy-v1.schema.json").read_text(encoding="utf-8"))
        policy_dict = policy.to_contract_dict()
        self.assertTrue(set(policy_dict).issubset(policy_schema["properties"]))
        self.assertTrue(policy_dict["requireApproval"])
        self.assertEqual(2500, policy_dict["executionTimeoutMs"])
        self.assertEqual(policy, ToolPolicy.from_contract_dict(policy_dict))

        session = ToolSession(
            session_id="sess-py-deadline",
            max_access_level=AccessLevel.MUTATING,
            session_timeout_seconds=5.0,
            clock=lambda: now[0],
            listeners=[audit],
        )
        session.register_tool(policy, lambda args: ToolExecutionOutcome.completed({"ok": True}))

        with self.assertRaises(GuardrailError):
            session.execute("high_risk_write", {}, request_id="req-1")

        now[0] += timedelta(seconds=6)
        with self.assertRaises(GuardrailError) as timeout_ctx:
            session.execute("high_risk_write", {}, request_id="req-1")
        self.assertEqual(ViolationCode.SESSION_TIMEOUT, timeout_ctx.exception.violation.code)

        audit_schema = json.loads((CONTRACTS_DIR / "audit-event-v1.schema.json").read_text(encoding="utf-8"))
        violation_enum = audit_schema["properties"]["violationCode"]["enum"]
        lines = [json.loads(line) for line in audit.to_json_lines()]
        self.assertEqual(["APPROVAL_REQUIRED", "SESSION_TIMEOUT"], [line["violationCode"] for line in lines])
        for line in lines:
            self.assertIn(line["violationCode"], violation_enum)


if __name__ == "__main__":
    unittest.main()
