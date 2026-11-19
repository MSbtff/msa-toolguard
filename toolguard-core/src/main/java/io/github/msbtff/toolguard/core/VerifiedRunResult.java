package io.github.msbtff.toolguard.core;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a bounded {@link VerifiedAgentRunner} verification and auto-repair loop.
 */
public record VerifiedRunResult(
    AgentOutput output,
    VerificationResult verification,
    int attempts,
    List<EvidenceRecord> evidence,
    List<List<VerificationFailure>> failureHistory
) {
    public VerifiedRunResult {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(verification, "verification");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be >= 1");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        failureHistory = failureHistory == null
            ? List.of()
            : failureHistory.stream().map(List::copyOf).toList();
    }

    public boolean verified() {
        return verification.verified();
    }
}
