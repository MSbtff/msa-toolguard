package io.github.goldenpage.toolguard.core;

import java.util.Objects;

public record GuardrailViolation(
    ViolationCode code,
    String toolName,
    String message,
    String repairHint
) {
    public GuardrailViolation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(message, "message");
        repairHint = repairHint == null ? "" : repairHint;
    }
}
