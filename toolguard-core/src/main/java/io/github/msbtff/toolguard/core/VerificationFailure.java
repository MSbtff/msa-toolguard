package io.github.msbtff.toolguard.core;

import java.util.Objects;

public record VerificationFailure(
    String code,
    String message,
    String repairHint
) {
    public VerificationFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        repairHint = repairHint == null ? "" : repairHint;
    }
}
