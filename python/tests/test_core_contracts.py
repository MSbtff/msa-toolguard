"""Contract compatibility and core guardrail unit tests for msa_toolguard."""

from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import unittest

from msa_toolguard import (
    AccessLevel,
    AgentOutput,
    AuditLogFormatter,
    AuditTrailListener,
    GuardrailError,
    InMemoryToolExecutionStore,
    OutputVerifier,
    ToolExecutionOutcome,
    ToolExecutionStatus,
    ToolPolicy,
    ToolSession,
    UncertainExecutionError,
    VerificationExhaustedError,
    VerifiedAgentRunner,
    ViolationCode,
    forbid_keys_rule,
    integer_range_rule,
)

CONTRACTS_DIR = Path(__file__).resolve().parents[2] / "contracts"


class CoreContractsTest(unittest.TestCase):
    def test_contracts_json_schema_alignment(self) -> None:
        policy_schema = json.loads((CONTRACTS_DIR / "tool-policy-v1.schema.json").read_text(encoding="utf-8"))
        evidence_schema = json.loads((CONTRACTS_DIR / "evidence-record-v1.schema.json").read_text(encoding="utf-8"))
        audit_schema = json.loads((CONTRACTS_DIR / "audit-event-v1.schema.json").read_text(encoding="utf-8"))

        policy = ToolPolicy(
            name="get_stock_movements",
            access_level=AccessLevel.READ_ONLY,
            max_calls=3,
            requires=("get_disposal",),
            bound_parameters=("accountId",),
            evidence_prefix="E",
        )
        policy_dict = policy.to_contract_dict()
        self.assertTrue(set(policy_schema["required"]).issubset(policy_dict))
        self.assertTrue(set(policy_dict).issubset(policy_schema["properties"]))
        self.assertRegex(policy_dict["name"], policy_schema["properties"]["name"]["pattern"])
        self.assertIn(policy_dict["accessLevel"], policy_schema["properties"]["accessLevel"]["enum"])
        self.assertRegex(policy_dict["evidencePrefix"], policy_schema["properties"]["evidencePrefix"]["pattern"])
        self.assertEqual(policy, ToolPolicy.from_contract_dict(policy_dict))

        fixed_time = datetime(2026, 11, 19, 9, 0, 0, tzinfo=timezone.utc)
        audit = AuditTrailListener(AuditLogFormatter(), clock=lambda: fixed_time)
        session = ToolSession(
            session_id="sess-py-1",
            clock=lambda: fixed_time,
            listeners=[audit],
        )
        session.register_tool(
            ToolPolicy(name="get_disposal", max_calls=1),
            lambda args: ToolExecutionOutcome.completed({"status": "PENDING", "authorization": "Bearer secret-val"}),
        )
        session.execute("get_disposal", {})

        evidence_dict = json.loads(AuditLogFormatter().format_evidence_json(session.evidence_ledger.all()[0]))
        self.assertTrue(set(evidence_schema["required"]).issubset(evidence_dict))
        self.assertTrue(set(evidence_dict).issubset(evidence_schema["properties"]))
        self.assertRegex(evidence_dict["evidenceId"], evidence_schema["properties"]["evidenceId"]["pattern"])
        self.assertRegex(evidence_dict["digest"], evidence_schema["properties"]["digest"]["pattern"])
        self.assertEqual("[REDACTED]", evidence_dict["facts"]["authorization"])

        audit_dict = json.loads(audit.to_json_lines()[0])
        self.assertTrue(set(audit_schema["required"]).issubset(audit_dict))
        self.assertTrue(set(audit_dict).issubset(audit_schema["properties"]))
        self.assertIn(audit_dict["status"], audit_schema["properties"]["status"]["enum"])
        self.assertTrue(audit_dict["allowed"])

    def test_session_enforces_policy_scope_deny_rules_and_idempotency(self) -> None:
        attempts = 0
        store = InMemoryToolExecutionStore()
        session = ToolSession(
            scope_key="acct-1",
            max_access_level=AccessLevel.MUTATING,
            max_total_calls=10,
            bound_parameters={"accountId": 7, "disposalId": "disp-1"},
            execution_store=store,
        )
        session.register_tool(
            ToolPolicy(
                name="get_disposal",
                access_level=AccessLevel.READ_ONLY,
                max_calls=1,
                bound_parameters=("accountId", "disposalId"),
            ),
            lambda args: ToolExecutionOutcome.completed({"status": "PENDING", "accountId": args["accountId"]}),
        )
        session.register_tool(
            ToolPolicy(
                name="get_stock_movements",
                access_level=AccessLevel.READ_ONLY,
                max_calls=2,
                requires=("get_disposal",),
                deny_rules=(integer_range_rule("page", 0, 10), forbid_keys_rule(["authorization"])),
            ),
            lambda args: ToolExecutionOutcome.completed({"page": args["page"], "matched": True}),
        )

        def retry_handler(args: dict) -> ToolExecutionOutcome:
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise UncertainExecutionError("response lost")
            return ToolExecutionOutcome.completed({"movementId": 9001, "quantityAfter": 17})

        session.register_tool(
            ToolPolicy(
                name="retry_disposal",
                access_level=AccessLevel.MUTATING,
                max_calls=5,
                requires=("get_disposal",),
                bound_parameters=("accountId", "disposalId"),
            ),
            retry_handler,
        )

        # Prerequisite violation
        with self.assertRaises(GuardrailError) as ctx:
            session.execute("get_stock_movements", {"page": 0})
        self.assertEqual(ViolationCode.PREREQUISITE_NOT_MET, ctx.exception.violation.code)

        # Scope override violation
        with self.assertRaises(GuardrailError) as ctx:
            session.execute("get_disposal", {"disposalId": "disp-other"})
        self.assertEqual(ViolationCode.SCOPE_VIOLATION, ctx.exception.violation.code)

        # Valid read
        detail = session.execute("get_disposal", {})
        self.assertEqual("E1", detail.evidence_id)
        self.assertEqual(7, detail.facts["accountId"])

        # Deny rules
        with self.assertRaises(GuardrailError) as ctx:
            session.execute("get_stock_movements", {"page": -1})
        self.assertEqual(ViolationCode.POLICY_DENIED, ctx.exception.violation.code)

        with self.assertRaises(GuardrailError) as ctx:
            session.execute("get_stock_movements", {"page": 0, "authorization": "Bearer x"})
        self.assertEqual(ViolationCode.POLICY_DENIED, ctx.exception.violation.code)

        # Missing idempotency key
        with self.assertRaises(GuardrailError) as ctx:
            session.execute("retry_disposal", {})
        self.assertEqual(ViolationCode.MISSING_IDEMPOTENCY_KEY, ctx.exception.violation.code)

        # Uncertain execution stays PENDING
        first = session.execute("retry_disposal", {"reason": "expired"}, request_id="req-1")
        self.assertEqual(ToolExecutionStatus.PENDING, first.status)
        self.assertFalse(first.replayed)

        # Conflicting arguments with same requestId -> IDEMPOTENCY_CONFLICT
        with self.assertRaises(GuardrailError) as ctx:
            session.execute("retry_disposal", {"reason": "different"}, request_id="req-1")
        self.assertEqual(ViolationCode.IDEMPOTENCY_CONFLICT, ctx.exception.violation.code)

        # Safe retry completes once
        second = session.execute("retry_disposal", {"reason": "expired"}, request_id="req-1")
        self.assertEqual(ToolExecutionStatus.COMPLETED, second.status)
        self.assertFalse(second.replayed)
        self.assertEqual(9001, second.facts["movementId"])

        # Subsequent retry replays without invoking handler again
        third = session.execute("retry_disposal", {"reason": "expired"}, request_id="req-1")
        self.assertEqual(ToolExecutionStatus.COMPLETED, third.status)
        self.assertTrue(third.replayed)
        self.assertEqual(2, attempts)

    def test_verified_agent_runner_auto_repairs_and_exhausts(self) -> None:
        session = ToolSession()
        session.register_tool(
            ToolPolicy(name="get_disposal", max_calls=1),
            lambda args: ToolExecutionOutcome.completed({"status": "PENDING"}),
        )
        verifier = OutputVerifier(
            required_tools=["get_disposal"],
            invariants=[
                (
                    "STATUS_MATCH",
                    lambda out, ledger: out.conclusion == "pending",
                    "Conclusion must match evidence",
                    "Set conclusion to 'pending'.",
                )
            ],
        )
        runner = VerifiedAgentRunner(verifier, max_attempts=3)

        def attempt(sess: ToolSession, prior: tuple, num: int) -> AgentOutput:
            if num == 1:
                sess.execute("get_disposal", {})
                return AgentOutput(conclusion="completed", cited_evidence_ids=("E99",))
            self.assertTrue(any(f.code == "UNKNOWN_EVIDENCE_ID" for f in prior))
            return AgentOutput(conclusion="pending", cited_evidence_ids=tuple(sess.evidence_ledger.all_ids()))

        result = runner.run_or_raise(session, attempt)
        self.assertTrue(result.verified)
        self.assertEqual(2, result.attempts)
        self.assertEqual(("E1",), result.output.cited_evidence_ids)

        failing_runner = VerifiedAgentRunner(verifier, max_attempts=2)
        with self.assertRaises(VerificationExhaustedError) as ctx:
            failing_runner.run_or_raise(session, lambda s, p, n: AgentOutput("wrong", ("E99",)))
        self.assertEqual(2, ctx.exception.result.attempts)


if __name__ == "__main__":
    unittest.main()
