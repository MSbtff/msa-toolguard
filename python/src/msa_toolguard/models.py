"""Core types, enums, outcomes, and guardrail exceptions shared with msa-toolguard contracts."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any


class AccessLevel(str, Enum):
    READ_ONLY = "READ_ONLY"
    MUTATING = "MUTATING"


class ToolExecutionStatus(str, Enum):
    PENDING = "PENDING"
    COMPLETED = "COMPLETED"
    REJECTED = "REJECTED"


class ViolationCode(str, Enum):
    UNREGISTERED_TOOL = "UNREGISTERED_TOOL"
    ACCESS_DENIED = "ACCESS_DENIED"
    CALL_BUDGET_EXCEEDED = "CALL_BUDGET_EXCEEDED"
    PREREQUISITE_NOT_MET = "PREREQUISITE_NOT_MET"
    SCOPE_VIOLATION = "SCOPE_VIOLATION"
    POLICY_DENIED = "POLICY_DENIED"
    MISSING_IDEMPOTENCY_KEY = "MISSING_IDEMPOTENCY_KEY"
    IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"


@dataclass(frozen=True)
class GuardrailViolation:
    code: ViolationCode
    tool_name: str
    message: str
    repair_hint: str = ""


class GuardrailError(Exception):
    def __init__(self, violation: GuardrailViolation) -> None:
        super().__init__(f"[{violation.code.value}] {violation.tool_name}: {violation.message}")
        self.violation = violation


class UncertainExecutionError(Exception):
    """Raised when downstream mutation status is unknown; must remain PENDING."""


@dataclass(frozen=True)
class ToolExecutionOutcome:
    status: ToolExecutionStatus
    evidence_id: str | None = None
    request_id: str | None = None
    rejection_code: str | None = None
    message: str | None = None
    replayed: bool = False
    facts: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def completed(cls, facts: dict[str, Any] | None = None) -> "ToolExecutionOutcome":
        return cls(status=ToolExecutionStatus.COMPLETED, facts=dict(facts or {}))

    @classmethod
    def rejected(
        cls,
        rejection_code: str,
        message: str | None = None,
        facts: dict[str, Any] | None = None,
    ) -> "ToolExecutionOutcome":
        if not rejection_code:
            raise ValueError("rejection_code must not be empty")
        return cls(
            status=ToolExecutionStatus.REJECTED,
            rejection_code=rejection_code,
            message=message,
            facts=dict(facts or {}),
        )

    @classmethod
    def pending(
        cls,
        request_id: str | None = None,
        message: str | None = None,
    ) -> "ToolExecutionOutcome":
        return cls(status=ToolExecutionStatus.PENDING, request_id=request_id, message=message)

    def with_evidence(self, evidence_id: str | None, request_id: str | None) -> "ToolExecutionOutcome":
        return ToolExecutionOutcome(
            status=self.status,
            evidence_id=evidence_id,
            request_id=request_id,
            rejection_code=self.rejection_code,
            message=self.message,
            replayed=self.replayed,
            facts=dict(self.facts),
        )

    def as_replayed(self) -> "ToolExecutionOutcome":
        return ToolExecutionOutcome(
            status=self.status,
            evidence_id=self.evidence_id,
            request_id=self.request_id,
            rejection_code=self.rejection_code,
            message=self.message,
            replayed=True,
            facts=dict(self.facts),
        )


@dataclass(frozen=True)
class ToolExecutionEvent:
    session_id: str
    tool_name: str
    allowed: bool
    violation_code: ViolationCode | None
    status: ToolExecutionStatus | None
    replayed: bool
    elapsed_ms: float


@dataclass(frozen=True)
class EvidenceRecord:
    evidence_id: str
    tool_name: str
    status: ToolExecutionStatus
    request_id: str | None
    recorded_at: datetime
    digest: str
    facts: dict[str, Any] = field(default_factory=dict)

    def to_contract_dict(self) -> dict[str, Any]:
        return {
            "evidenceId": self.evidence_id,
            "toolName": self.tool_name,
            "status": self.status.value,
            "requestId": self.request_id,
            "recordedAt": self.recorded_at.isoformat().replace("+00:00", "Z"),
            "digest": self.digest,
            "facts": dict(self.facts),
        }


@dataclass(frozen=True)
class AgentOutput:
    conclusion: str
    cited_evidence_ids: tuple[str, ...] = ()
    attributes: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class VerificationFailure:
    code: str
    message: str
    repair_hint: str = ""


@dataclass(frozen=True)
class VerificationResult:
    verified: bool
    failures: tuple[VerificationFailure, ...] = ()

    @classmethod
    def ok(cls) -> "VerificationResult":
        return cls(verified=True, failures=())

    @classmethod
    def fail(cls, failures: list[VerificationFailure] | tuple[VerificationFailure, ...]) -> "VerificationResult":
        items = tuple(failures)
        if not items:
            raise ValueError("failures must not be empty")
        return cls(verified=False, failures=items)
