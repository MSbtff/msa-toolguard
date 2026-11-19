package io.github.msbtff.toolguard.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Bounded Auto-Repair loop helper that executes an {@link AgentAttempt}, verifies its structured
 * {@link AgentOutput} against the session's {@link EvidenceLedger}, and feeds deterministic
 * {@link VerificationFailure} repair hints back into subsequent attempts.
 */
public final class VerifiedAgentRunner {
    private final OutputVerifier verifier;
    private final int maxAttempts;
    private final BiConsumer<Integer, VerificationResult> failureCallback;

    private VerifiedAgentRunner(Builder builder) {
        this.verifier = Objects.requireNonNull(builder.verifier, "verifier");
        this.maxAttempts = builder.maxAttempts;
        this.failureCallback = builder.failureCallback;
    }

    public static Builder builder(OutputVerifier verifier) {
        return new Builder(verifier);
    }

    public VerifiedRunResult run(ToolSession session, AgentAttempt attempt) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(attempt, "attempt");

        List<VerificationFailure> priorFailures = List.of();
        List<List<VerificationFailure>> history = new ArrayList<>();
        AgentOutput lastOutput = null;
        VerificationResult lastResult = null;

        for (int current = 1; current <= maxAttempts; current++) {
            lastOutput = Objects.requireNonNull(
                attempt.execute(session, priorFailures, current),
                "agent attempt returned null AgentOutput"
            );
            lastResult = verifier.verify(lastOutput, session.evidenceLedger());
            if (lastResult.verified()) {
                return new VerifiedRunResult(
                    lastOutput,
                    lastResult,
                    current,
                    session.evidenceLedger().all(),
                    history
                );
            }
            history.add(lastResult.failures());
            priorFailures = lastResult.failures();
            if (failureCallback != null) {
                failureCallback.accept(current, lastResult);
            }
        }

        return new VerifiedRunResult(
            lastOutput,
            lastResult,
            maxAttempts,
            session.evidenceLedger().all(),
            history
        );
    }

    public VerifiedRunResult runOrThrow(ToolSession session, AgentAttempt attempt) {
        VerifiedRunResult result = run(session, attempt);
        if (!result.verified()) {
            throw new VerificationExhaustedException(result);
        }
        return result;
    }

    /**
     * Renders deterministic, structured feedback suitable for injecting into an LLM self-repair prompt.
     */
    public static String formatRepairFeedback(VerificationResult result, EvidenceLedger ledger) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(ledger, "ledger");
        if (result.verified()) {
            return "Verification passed. No repair required.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous output failed deterministic verification. Fix every item below:\n");
        for (int i = 0; i < result.failures().size(); i++) {
            VerificationFailure failure = result.failures().get(i);
            sb.append(i + 1)
                .append(". [")
                .append(failure.code())
                .append("] ")
                .append(failure.message());
            if (!failure.repairHint().isBlank()) {
                sb.append(" -> Repair: ").append(failure.repairHint());
            }
            sb.append('\n');
        }
        sb.append("Recorded evidence IDs in order: ").append(ledger.allIds());
        return sb.toString();
    }

    public static final class Builder {
        private final OutputVerifier verifier;
        private int maxAttempts = 3;
        private BiConsumer<Integer, VerificationResult> failureCallback;

        private Builder(OutputVerifier verifier) {
            this.verifier = Objects.requireNonNull(verifier, "verifier");
        }

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder onVerificationFailure(BiConsumer<Integer, VerificationResult> callback) {
            this.failureCallback = callback;
            return this;
        }

        public VerifiedAgentRunner build() {
            return new VerifiedAgentRunner(this);
        }
    }
}
