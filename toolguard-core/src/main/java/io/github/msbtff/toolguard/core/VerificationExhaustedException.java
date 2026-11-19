package io.github.msbtff.toolguard.core;

import java.util.Objects;

/**
 * Thrown by {@link VerifiedAgentRunner#runOrThrow} when all auto-repair attempts fail deterministic verification.
 */
public final class VerificationExhaustedException extends RuntimeException {
    private final VerifiedRunResult result;

    public VerificationExhaustedException(VerifiedRunResult result) {
        super(buildMessage(Objects.requireNonNull(result, "result")));
        this.result = result;
    }

    public VerifiedRunResult result() {
        return result;
    }

    private static String buildMessage(VerifiedRunResult result) {
        return "Agent output failed deterministic verification after " + result.attempts()
            + " attempt(s): " + result.verification().failures();
    }
}
