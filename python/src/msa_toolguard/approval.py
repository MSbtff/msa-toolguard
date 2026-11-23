"""Human-in-the-Loop (HITL) approval gate primitives matching Java toolguard-core."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from threading import Lock
from typing import Any, Callable, Protocol
from uuid import uuid4

from .digest import canonical_sha256

ApprovalPredicate = Callable[[str, dict[str, Any]], bool]


@dataclass(frozen=True)
class ApprovalRequest:
    session_id: str
    scope_key: str
    tool_name: str
    request_id: str | None
    payload_digest: str
    arguments: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ApprovalDecision:
    approved: bool
    approval_id: str | None = None
    approver: str | None = None
    reason: str = ""

    @classmethod
    def approve(cls, approval_id: str, approver: str | None = None) -> "ApprovalDecision":
        if not approval_id:
            raise ValueError("approval_id must not be empty")
        return cls(approved=True, approval_id=approval_id, approver=approver, reason="")

    @classmethod
    def deny(cls, reason: str) -> "ApprovalDecision":
        return cls(approved=False, approval_id=None, approver=None, reason=reason)

    @classmethod
    def pending_approval(cls, approval_id: str, reason: str) -> "ApprovalDecision":
        if not approval_id:
            raise ValueError("approval_id must not be empty")
        return cls(approved=False, approval_id=approval_id, approver=None, reason=reason)


class ApprovalGate(Protocol):
    def check(self, request: ApprovalRequest) -> ApprovalDecision: ...


class _DenyAllApprovalGate:
    def check(self, request: ApprovalRequest) -> ApprovalDecision:
        return ApprovalDecision.deny(
            f"Tool '{request.tool_name}' requires explicit approval for payload digest {request.payload_digest}"
        )


DENY_ALL_APPROVAL_GATE: ApprovalGate = _DenyAllApprovalGate()


@dataclass
class _Grant:
    approval_id: str
    scope_key: str
    tool_name: str
    payload_digest: str
    approver: str
    expires_at: datetime
    consumed: bool = False
    consumed_request_id: str | None = None
    revoked: bool = False


class InMemoryApprovalGate:
    """Thread-safe payload-digest-bound, TTL-bounded, single-use approval gate."""

    def __init__(self, clock: Callable[[], datetime] | None = None) -> None:
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._lock = Lock()
        self._grants: dict[str, _Grant] = {}

    def grant(
        self,
        scope_key: str,
        tool_name: str,
        expected_arguments: dict[str, Any],
        approver: str,
        ttl_seconds: float,
    ) -> str:
        return self.grant_by_digest(
            scope_key=scope_key,
            tool_name=tool_name,
            payload_digest=canonical_sha256(expected_arguments),
            approver=approver,
            ttl_seconds=ttl_seconds,
        )

    def grant_by_digest(
        self,
        scope_key: str,
        tool_name: str,
        payload_digest: str,
        approver: str,
        ttl_seconds: float,
    ) -> str:
        if ttl_seconds <= 0:
            raise ValueError("ttl_seconds must be positive")
        approval_id = f"apr-{uuid4()}"
        expires_at = self._clock() + timedelta(seconds=ttl_seconds)
        with self._lock:
            self._grants[approval_id] = _Grant(
                approval_id=approval_id,
                scope_key=scope_key,
                tool_name=tool_name,
                payload_digest=payload_digest,
                approver=approver,
                expires_at=expires_at,
            )
        return approval_id

    def revoke(self, approval_id: str) -> bool:
        with self._lock:
            grant = self._grants.get(approval_id)
            if grant is None or grant.revoked:
                return False
            grant.revoked = True
            return True

    def check(self, request: ApprovalRequest) -> ApprovalDecision:
        now = self._clock()
        with self._lock:
            if request.request_id is not None:
                for grant in self._grants.values():
                    if (
                        self._matches(grant, request)
                        and not grant.revoked
                        and now <= grant.expires_at
                        and grant.consumed
                        and grant.consumed_request_id == request.request_id
                    ):
                        return ApprovalDecision.approve(grant.approval_id, grant.approver)
            for grant in self._grants.values():
                if (
                    self._matches(grant, request)
                    and not grant.revoked
                    and not grant.consumed
                    and now <= grant.expires_at
                ):
                    grant.consumed = True
                    grant.consumed_request_id = request.request_id
                    return ApprovalDecision.approve(grant.approval_id, grant.approver)
        return ApprovalDecision.deny(
            f"No valid approval grant for tool '{request.tool_name}' "
            f"in scope '{request.scope_key}' with payload digest {request.payload_digest}"
        )

    @staticmethod
    def _matches(grant: _Grant, request: ApprovalRequest) -> bool:
        return (
            grant.scope_key == request.scope_key
            and grant.tool_name == request.tool_name
            and grant.payload_digest == request.payload_digest
        )
