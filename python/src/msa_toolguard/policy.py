"""Tool policies and deterministic DenyRules aligned with tool-policy-v1.schema.json."""

from __future__ import annotations

from dataclasses import dataclass, field
import re
from typing import Any, Callable, Iterable

from .models import AccessLevel

TOOL_NAME_RE = re.compile(r"^[a-z][a-z0-9_.-]*$")
EVIDENCE_PREFIX_RE = re.compile(r"^[A-Z]{1,4}$")

DenyRule = Callable[[str, dict[str, Any]], str | None]


def integer_range_rule(param: str, minimum: int, maximum: int) -> DenyRule:
    def check(_tool_name: str, args: dict[str, Any]) -> str | None:
        if param not in args:
            return None
        value = args[param]
        if type(value) is not int or not (minimum <= value <= maximum):
            return f"Parameter '{param}' must be an integer between {minimum} and {maximum}"
        return None

    return check


def forbid_keys_rule(forbidden_keys: Iterable[str]) -> DenyRule:
    normalized = {key.lower() for key in forbidden_keys}

    def check(_tool_name: str, args: dict[str, Any]) -> str | None:
        for key in args:
            if str(key).lower() in normalized:
                return f"Parameter '{key}' is forbidden by policy"
        return None

    return check


def allowed_keys_rule(allowed_keys: Iterable[str]) -> DenyRule:
    allowed = set(allowed_keys)

    def check(_tool_name: str, args: dict[str, Any]) -> str | None:
        extra = set(args) - allowed
        if extra:
            return f"Unexpected argument(s): {', '.join(sorted(extra))}"
        return None

    return check


@dataclass(frozen=True)
class ToolPolicy:
    name: str
    access_level: AccessLevel = AccessLevel.READ_ONLY
    max_calls: int = 10
    requires: tuple[str, ...] = ()
    bound_parameters: tuple[str, ...] = ()
    require_idempotency_key: bool | None = None
    evidence_prefix: str = "E"
    deny_rules: tuple[DenyRule, ...] = field(default=(), repr=False, compare=False)

    def __post_init__(self) -> None:
        if not self.name or not TOOL_NAME_RE.fullmatch(self.name):
            raise ValueError(f"Invalid tool policy name '{self.name}'")
        if type(self.max_calls) is not int or self.max_calls < 1:
            raise ValueError("max_calls must be an integer >= 1")
        if not EVIDENCE_PREFIX_RE.fullmatch(self.evidence_prefix):
            raise ValueError(f"Invalid evidence_prefix '{self.evidence_prefix}'")
        if self.require_idempotency_key is None:
            object.__setattr__(self, "require_idempotency_key", self.access_level == AccessLevel.MUTATING)

    def to_contract_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "accessLevel": self.access_level.value,
            "maxCalls": self.max_calls,
            "requires": list(self.requires),
            "boundParameters": list(self.bound_parameters),
            "requireIdempotencyKey": bool(self.require_idempotency_key),
            "evidencePrefix": self.evidence_prefix,
        }

    @classmethod
    def from_contract_dict(cls, payload: dict[str, Any]) -> "ToolPolicy":
        return cls(
            name=payload["name"],
            access_level=AccessLevel(payload["accessLevel"]),
            max_calls=payload["maxCalls"],
            requires=tuple(payload.get("requires", ())),
            bound_parameters=tuple(payload.get("boundParameters", ())),
            require_idempotency_key=payload.get("requireIdempotencyKey"),
            evidence_prefix=payload.get("evidencePrefix", "E"),
        )
