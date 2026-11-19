"""Deterministic OutputVerifier and VerifiedAgentRunner auto-repair loop."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from .evidence import EvidenceLedger
from .models import AgentOutput, EvidenceRecord, VerificationFailure, VerificationResult
from .session import ToolSession

DomainPredicate = Callable[[AgentOutput, EvidenceLedger], bool]
AgentAttempt = Callable[[ToolSession, tuple[VerificationFailure, ...], int], AgentOutput]


@dataclass(frozen=True)
class _DomainRule:
    code: str
    predicate: DomainPredicate
    message: str
    repair_hint: str


class OutputVerifier:
    def __init__(
        self,
        *,
        require_exact_evidence_order: bool = True,
        required_tools: list[str] | tuple[str, ...] = (),
        invariants: list[tuple[str, DomainPredicate, str, str]] | None = None,
    ) -> None:
        self.require_exact_evidence_order = require_exact_evidence_order
        self.required_tools = tuple(dict.fromkeys(required_tools))
        self._rules = tuple(
            _DomainRule(code=c, predicate=p, message=m, repair_hint=h)
            for c, p, m, h in (invariants or [])
        )

    def verify(self, output: AgentOutput, ledger: EvidenceLedger) -> VerificationResult:
        failures: list[VerificationFailure] = []
        recorded_ids = ledger.all_ids()

        if not recorded_ids:
            failures.append(
                VerificationFailure(
                    code="NO_EVIDENCE_COLLECTED",
                    message="No tool evidence was recorded before submitting the final output",
                    repair_hint="Execute the required read tools before submitting a final conclusion.",
                )
            )

        for required_tool in self.required_tools:
            if not ledger.has_completed_tool(required_tool):
                failures.append(
                    VerificationFailure(
                        code="REQUIRED_TOOL_MISSING",
                        message=f"Required tool '{required_tool}' has no completed evidence record",
                        repair_hint=f"Call '{required_tool}' and cite its evidence ID in your final answer.",
                    )
                )

        seen: set[str] = set()
        for cited in output.cited_evidence_ids:
            if ledger.find(cited) is None:
                failures.append(
                    VerificationFailure(
                        code="UNKNOWN_EVIDENCE_ID",
                        message=f"Cited evidence ID '{cited}' does not exist in the session ledger",
                        repair_hint=f"Cite only recorded evidence IDs: {recorded_ids}",
                    )
                )
            elif cited in seen:
                failures.append(
                    VerificationFailure(
                        code="DUPLICATE_EVIDENCE_ID",
                        message=f"Evidence ID '{cited}' was cited multiple times",
                        repair_hint="List each evidence ID once.",
                    )
                )
            else:
                seen.add(cited)

        if self.require_exact_evidence_order and recorded_ids and list(output.cited_evidence_ids) != recorded_ids:
            failures.append(
                VerificationFailure(
                    code="EVIDENCE_MISMATCH",
                    message=f"Cited evidence {list(output.cited_evidence_ids)} does not match recorded evidence {recorded_ids}",
                    repair_hint=f"Set citedEvidenceIds to exactly {recorded_ids} in recorded order.",
                )
            )

        for rule in self._rules:
            if not rule.predicate(output, ledger):
                failures.append(
                    VerificationFailure(code=rule.code, message=rule.message, repair_hint=rule.repair_hint)
                )

        return VerificationResult.ok() if not failures else VerificationResult.fail(failures)


@dataclass(frozen=True)
class VerifiedRunResult:
    output: AgentOutput
    verification: VerificationResult
    attempts: int
    evidence: tuple[EvidenceRecord, ...]
    failure_history: tuple[tuple[VerificationFailure, ...], ...]

    @property
    def verified(self) -> bool:
        return self.verification.verified


class VerificationExhaustedError(Exception):
    def __init__(self, result: VerifiedRunResult) -> None:
        super().__init__(
            f"Agent output failed deterministic verification after {result.attempts} attempt(s): "
            f"{result.verification.failures}"
        )
        self.result = result


class VerifiedAgentRunner:
    def __init__(
        self,
        verifier: OutputVerifier,
        *,
        max_attempts: int = 3,
        on_failure: Callable[[int, VerificationResult], None] | None = None,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        self.verifier = verifier
        self.max_attempts = max_attempts
        self.on_failure = on_failure

    def run(self, session: ToolSession, attempt: AgentAttempt) -> VerifiedRunResult:
        prior: tuple[VerificationFailure, ...] = ()
        history: list[tuple[VerificationFailure, ...]] = []
        last_output: AgentOutput | None = None
        last_result: VerificationResult | None = None

        for current in range(1, self.max_attempts + 1):
            last_output = attempt(session, prior, current)
            last_result = self.verifier.verify(last_output, session.evidence_ledger)
            if last_result.verified:
                return VerifiedRunResult(
                    output=last_output,
                    verification=last_result,
                    attempts=current,
                    evidence=tuple(session.evidence_ledger.all()),
                    failure_history=tuple(history),
                )
            history.append(last_result.failures)
            prior = last_result.failures
            if self.on_failure is not None:
                self.on_failure(current, last_result)

        assert last_output is not None and last_result is not None
        return VerifiedRunResult(
            output=last_output,
            verification=last_result,
            attempts=self.max_attempts,
            evidence=tuple(session.evidence_ledger.all()),
            failure_history=tuple(history),
        )

    def run_or_raise(self, session: ToolSession, attempt: AgentAttempt) -> VerifiedRunResult:
        result = self.run(session, attempt)
        if not result.verified:
            raise VerificationExhaustedError(result)
        return result

    @staticmethod
    def format_repair_feedback(result: VerificationResult, ledger: EvidenceLedger) -> str:
        if result.verified:
            return "Verification passed. No repair required."
        lines = ["Your previous output failed deterministic verification. Fix every item below:"]
        for idx, failure in enumerate(result.failures, start=1):
            suffix = f" -> Repair: {failure.repair_hint}" if failure.repair_hint else ""
            lines.append(f"{idx}. [{failure.code}] {failure.message}{suffix}")
        lines.append(f"Recorded evidence IDs in order: {ledger.all_ids()}")
        return "\n".join(lines)
