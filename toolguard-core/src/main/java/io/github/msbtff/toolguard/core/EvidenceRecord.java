package io.github.msbtff.toolguard.core;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record EvidenceRecord(
    String evidenceId,
    String toolName,
    ToolExecutionStatus status,
    String requestId,
    Instant recordedAt,
    String digest,
    Map<String, Object> facts
) {
    public EvidenceRecord {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(digest, "digest");
        if (facts == null) {
            facts = Map.of();
        } else {
            facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        }
    }
}
