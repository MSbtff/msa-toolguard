"""Audit log JSONL and Markdown formatter matching contracts/audit-event-v1.schema.json."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
from threading import Lock
from typing import Any, Callable, Iterable

from .evidence import EvidenceLedger
from .models import EvidenceRecord, ToolExecutionEvent, VerificationResult

DEFAULT_REDACTED_KEYS: frozenset[str] = frozenset(
    {
        "authorization",
        "password",
        "token",
        "access_token",
        "refresh_token",
        "secret",
        "apikey",
        "api_key",
        "cookie",
        "credential",
        "private_key",
    }
)


@dataclass(frozen=True)
class AuditEntry:
    timestamp: datetime
    event: ToolExecutionEvent


class AuditLogFormatter:
    def __init__(self, redacted_keys: Iterable[str] = DEFAULT_REDACTED_KEYS) -> None:
        self.redacted_keys = frozenset(k.strip().lower() for k in redacted_keys if k and k.strip())

    def redact(self, value: Any) -> Any:
        if isinstance(value, dict):
            return {
                str(k): "[REDACTED]" if str(k).strip().lower() in self.redacted_keys else self.redact(v)
                for k, v in value.items()
            }
        if isinstance(value, (list, tuple)):
            return [self.redact(item) for item in value]
        return value

    def format_event_dict(self, event: ToolExecutionEvent, timestamp: datetime) -> dict[str, Any]:
        return {
            "timestamp": timestamp.isoformat().replace("+00:00", "Z"),
            "sessionId": event.session_id,
            "toolName": event.tool_name,
            "allowed": event.allowed,
            "violationCode": event.violation_code.value if event.violation_code else None,
            "status": event.status.value if event.status else None,
            "replayed": event.replayed,
            "elapsedMs": round(max(0.0, float(event.elapsed_ms)), 3),
        }

    def format_event_json(self, event: ToolExecutionEvent, timestamp: datetime) -> str:
        return json.dumps(self.format_event_dict(event, timestamp), separators=(",", ":"), ensure_ascii=False)

    def format_evidence_json(self, record: EvidenceRecord) -> str:
        payload = record.to_contract_dict()
        payload["facts"] = self.redact(payload["facts"])
        return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)

    def format_session_markdown(
        self,
        session_id: str,
        entries: list[AuditEntry],
        ledger: EvidenceLedger,
        verification: VerificationResult | None = None,
    ) -> str:
        allowed_count = sum(1 for item in entries if item.event.allowed)
        blocked_count = len(entries) - allowed_count
        replayed_count = sum(1 for item in entries if item.event.replayed)
        lines = [
            f"## ToolGuard Audit Report (`{session_id}`)",
            "",
            f"- **Total Tool Calls**: {len(entries)} (Allowed: {allowed_count}, Blocked: {blocked_count}, Replayed: {replayed_count})",
            f"- **Evidence Records**: {len(ledger.all())}",
        ]
        if verification is not None:
            status = "PASSED" if verification.verified else f"FAILED ({len(verification.failures)} issue(s))"
            lines.append(f"- **Verification**: {status}")
        lines.extend(["", "### Execution Timeline", ""])
        if not entries:
            lines.append("_No tool invocations recorded._")
        else:
            lines.append("| Timestamp | Tool | Allowed | Status / Violation | Replayed | Elapsed (ms) |")
            lines.append("|---|---|---|---|---|---|".rstrip("|"))
            for item in entries:
                ev = item.event
                code = ev.status.value if ev.allowed and ev.status else (ev.violation_code.value if ev.violation_code else "BLOCKED")
                ts = item.timestamp.isoformat().replace("+00:00", "Z")
                lines.append(
                    f"| {ts} | `{ev.tool_name}` | {'YES' if ev.allowed else 'NO'} | {code} | "
                    f"{'YES' if ev.replayed else 'NO'} | {ev.elapsed_ms:.3f} |"
                )
        lines.extend(["", "### Evidence Ledger", ""])
        records = ledger.all()
        if not records:
            lines.append("_No evidence recorded._")
        else:
            lines.append("| Evidence ID | Tool | Status | Digest (prefix) | Facts |")
            lines.append("|---|---|---|---|---|")
            for record in records:
                facts_json = json.dumps(self.redact(record.facts), separators=(",", ":"), ensure_ascii=False)
                lines.append(
                    f"| `{record.evidence_id}` | `{record.tool_name}` | {record.status.value} | "
                    f"`{record.digest[:12]}` | `{facts_json}` |"
                )
        return "\n".join(lines) + "\n"


class AuditTrailListener:
    def __init__(
        self,
        formatter: AuditLogFormatter | None = None,
        clock: Callable[[], datetime] | None = None,
        sink: Callable[[str], None] | None = None,
    ) -> None:
        self.formatter = formatter or AuditLogFormatter()
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._sink = sink
        self._lock = Lock()
        self._entries: list[AuditEntry] = []

    def __call__(self, event: ToolExecutionEvent) -> None:
        entry = AuditEntry(timestamp=self._clock(), event=event)
        with self._lock:
            self._entries.append(entry)
        if self._sink is not None:
            self._sink(self.formatter.format_event_json(event, entry.timestamp))

    def entries(self) -> list[AuditEntry]:
        with self._lock:
            return list(self._entries)

    def to_json_lines(self) -> list[str]:
        return [self.formatter.format_event_json(e.event, e.timestamp) for e in self.entries()]
