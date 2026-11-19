package io.github.goldenpage.toolguard.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ToolExecutionOutcome(
    ToolExecutionStatus status,
    String evidenceId,
    String requestId,
    String rejectionCode,
    String message,
    boolean replayed,
    Map<String, Object> facts
) {
    public ToolExecutionOutcome {
        Objects.requireNonNull(status, "status");
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    public static ToolExecutionOutcome completed(Map<String, Object> facts) {
        return new ToolExecutionOutcome(ToolExecutionStatus.COMPLETED, null, null, null, null, false, facts);
    }

    public static ToolExecutionOutcome rejected(String rejectionCode, String message, Map<String, Object> facts) {
        Objects.requireNonNull(rejectionCode, "rejectionCode");
        return new ToolExecutionOutcome(ToolExecutionStatus.REJECTED, null, null, rejectionCode, message, false, facts);
    }

    public static ToolExecutionOutcome pending(String requestId, String message) {
        return new ToolExecutionOutcome(ToolExecutionStatus.PENDING, null, requestId, null, message, false, Map.of());
    }

    public ToolExecutionOutcome withEvidence(String newEvidenceId, String newRequestId) {
        return new ToolExecutionOutcome(status, newEvidenceId, newRequestId, rejectionCode, message, replayed, facts);
    }

    public ToolExecutionOutcome asReplayed() {
        return new ToolExecutionOutcome(status, evidenceId, requestId, rejectionCode, message, true, facts);
    }
}
