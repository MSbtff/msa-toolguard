package io.github.goldenpage.toolguard.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Deterministically cross-checks a model's structured output against the session's EvidenceLedger
 * and returns structured repair hints when invariants fail.
 */
public final class OutputVerifier {
    private record DomainRule(
        String code,
        BiPredicate<AgentOutput, EvidenceLedger> predicate,
        String message,
        String repairHint
    ) {}

    private final boolean requireExactEvidenceOrder;
    private final Set<String> requiredTools;
    private final List<DomainRule> domainRules;

    private OutputVerifier(Builder builder) {
        this.requireExactEvidenceOrder = builder.requireExactEvidenceOrder;
        this.requiredTools = Set.copyOf(builder.requiredTools);
        this.domainRules = List.copyOf(builder.domainRules);
    }

    public static Builder builder() {
        return new Builder();
    }

    public VerificationResult verify(AgentOutput output, EvidenceLedger ledger) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(ledger, "ledger");

        List<VerificationFailure> failures = new ArrayList<>();
        List<String> recordedIds = ledger.allIds();

        if (recordedIds.isEmpty()) {
            failures.add(new VerificationFailure(
                "NO_EVIDENCE_COLLECTED",
                "No tool evidence was recorded before submitting the final output",
                "Execute the required read tools before submitting a final conclusion."
            ));
        }

        for (String requiredTool : requiredTools) {
            if (!ledger.hasCompletedTool(requiredTool)) {
                failures.add(new VerificationFailure(
                    "REQUIRED_TOOL_MISSING",
                    "Required tool '" + requiredTool + "' has no completed evidence record",
                    "Call '" + requiredTool + "' and cite its evidence ID in your final answer."
                ));
            }
        }

        Set<String> seenCited = new LinkedHashSet<>();
        for (String cited : output.citedEvidenceIds()) {
            if (ledger.find(cited).isEmpty()) {
                failures.add(new VerificationFailure(
                    "UNKNOWN_EVIDENCE_ID",
                    "Cited evidence ID '" + cited + "' does not exist in the session ledger",
                    "Cite only recorded evidence IDs: " + recordedIds
                ));
            } else if (!seenCited.add(cited)) {
                failures.add(new VerificationFailure(
                    "DUPLICATE_EVIDENCE_ID",
                    "Evidence ID '" + cited + "' was cited multiple times",
                    "List each evidence ID once."
                ));
            }
        }

        if (requireExactEvidenceOrder && !recordedIds.isEmpty() && !output.citedEvidenceIds().equals(recordedIds)) {
            failures.add(new VerificationFailure(
                "EVIDENCE_MISMATCH",
                "Cited evidence " + output.citedEvidenceIds() + " does not match recorded evidence " + recordedIds,
                "Set citedEvidenceIds to exactly " + recordedIds + " in recorded order."
            ));
        }

        for (DomainRule rule : domainRules) {
            if (!rule.predicate().test(output, ledger)) {
                failures.add(new VerificationFailure(rule.code(), rule.message(), rule.repairHint()));
            }
        }

        return failures.isEmpty() ? VerificationResult.pass() : VerificationResult.fail(failures);
    }

    public static final class Builder {
        private boolean requireExactEvidenceOrder = true;
        private final Set<String> requiredTools = new LinkedHashSet<>();
        private final List<DomainRule> domainRules = new ArrayList<>();

        private Builder() {}

        public Builder requireExactEvidenceOrder(boolean required) {
            this.requireExactEvidenceOrder = required;
            return this;
        }

        public Builder requireToolExecuted(String toolName) {
            this.requiredTools.add(Objects.requireNonNull(toolName, "toolName"));
            return this;
        }

        public Builder addInvariant(
            String code,
            BiPredicate<AgentOutput, EvidenceLedger> predicate,
            String message,
            String repairHint
        ) {
            this.domainRules.add(new DomainRule(
                Objects.requireNonNull(code, "code"),
                Objects.requireNonNull(predicate, "predicate"),
                Objects.requireNonNull(message, "message"),
                repairHint
            ));
            return this;
        }

        public OutputVerifier build() {
            return new OutputVerifier(this);
        }
    }
}
