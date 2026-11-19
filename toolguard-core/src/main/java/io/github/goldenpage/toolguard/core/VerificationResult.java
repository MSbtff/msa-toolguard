package io.github.goldenpage.toolguard.core;

import java.util.List;

public record VerificationResult(
    boolean verified,
    List<VerificationFailure> failures
) {
    public VerificationResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static VerificationResult pass() {
        return new VerificationResult(true, List.of());
    }

    public static VerificationResult fail(List<VerificationFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            throw new IllegalArgumentException("failures must not be empty");
        }
        return new VerificationResult(false, failures);
    }
}
