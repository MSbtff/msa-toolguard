"""Immutable evidence ledger matching contracts/evidence-record-v1.schema.json."""

from __future__ import annotations

from datetime import datetime, timezone
import re
from threading import Lock
from typing import Any, Callable

from .digest import canonical_sha256
from .models import EvidenceRecord, ToolExecutionStatus

_EVIDENCE_ID_RE = re.compile(r"^([A-Z]{1,4})([1-9][0-9]*)$")


class EvidenceLedger:
    def __init__(self, clock: Callable[[], datetime] | None = None) -> None:
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._lock = Lock()
        self._sequences: dict[str, int] = {}
        self._records: list[EvidenceRecord] = []
        self._by_id: dict[str, EvidenceRecord] = {}

    def record(
        self,
        prefix: str,
        tool_name: str,
        status: ToolExecutionStatus,
        request_id: str | None,
        facts: dict[str, Any] | None,
    ) -> EvidenceRecord:
        normalized_prefix = prefix.strip() if prefix and prefix.strip() else "E"
        with self._lock:
            seq = self._sequences.get(normalized_prefix, 0) + 1
            self._sequences[normalized_prefix] = seq
            return self._append_locked(f"{normalized_prefix}{seq}", tool_name, status, request_id, facts)

    def record_replayed(
        self,
        existing_evidence_id: str | None,
        prefix: str,
        tool_name: str,
        status: ToolExecutionStatus,
        request_id: str | None,
        facts: dict[str, Any] | None,
    ) -> EvidenceRecord:
        with self._lock:
            if request_id is not None:
                for existing in self._records:
                    if (
                        existing.tool_name == tool_name
                        and existing.request_id == request_id
                        and existing.status == status
                    ):
                        return existing
            if existing_evidence_id and existing_evidence_id.strip() and existing_evidence_id not in self._by_id:
                match = _EVIDENCE_ID_RE.fullmatch(existing_evidence_id)
                if match:
                    parsed_prefix, parsed_num = match.group(1), int(match.group(2))
                    self._sequences[parsed_prefix] = max(self._sequences.get(parsed_prefix, 0), parsed_num)
                return self._append_locked(existing_evidence_id, tool_name, status, request_id, facts)
            normalized_prefix = prefix.strip() if prefix and prefix.strip() else "E"
            seq = self._sequences.get(normalized_prefix, 0) + 1
            self._sequences[normalized_prefix] = seq
            return self._append_locked(f"{normalized_prefix}{seq}", tool_name, status, request_id, facts)

    def _append_locked(
        self,
        evidence_id: str,
        tool_name: str,
        status: ToolExecutionStatus,
        request_id: str | None,
        facts: dict[str, Any] | None,
    ) -> EvidenceRecord:
        safe_facts = dict(facts or {})
        digest = canonical_sha256(
            {
                "toolName": tool_name,
                "status": status.value,
                "requestId": request_id or "",
                "facts": safe_facts,
            }
        )
        record = EvidenceRecord(
            evidence_id=evidence_id,
            tool_name=tool_name,
            status=status,
            request_id=request_id,
            recorded_at=self._clock(),
            digest=digest,
            facts=safe_facts,
        )
        self._records.append(record)
        self._by_id[evidence_id] = record
        return record

    def all(self) -> list[EvidenceRecord]:
        with self._lock:
            return list(self._records)

    def find(self, evidence_id: str) -> EvidenceRecord | None:
        with self._lock:
            return self._by_id.get(evidence_id)

    def all_ids(self) -> list[str]:
        with self._lock:
            return list(self._by_id.keys())

    def has_completed_tool(self, tool_name: str) -> bool:
        with self._lock:
            return any(
                r.tool_name == tool_name and r.status == ToolExecutionStatus.COMPLETED
                for r in self._records
            )
