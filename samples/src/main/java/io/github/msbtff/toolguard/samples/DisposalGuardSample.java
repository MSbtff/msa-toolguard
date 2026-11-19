package io.github.msbtff.toolguard.samples;

import io.github.msbtff.toolguard.core.AccessLevel;
import io.github.msbtff.toolguard.core.AgentOutput;
import io.github.msbtff.toolguard.core.AuditTrailListener;
import io.github.msbtff.toolguard.core.DenyRules;
import io.github.msbtff.toolguard.core.InMemoryToolExecutionStore;
import io.github.msbtff.toolguard.core.OutputVerifier;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolPolicy;
import io.github.msbtff.toolguard.core.ToolSession;
import io.github.msbtff.toolguard.core.UncertainExecutionException;
import io.github.msbtff.toolguard.core.VerifiedAgentRunner;
import io.github.msbtff.toolguard.core.VerifiedRunResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone runnable sample demonstrating deterministic tool guardrails,
 * EvidenceLedger + VerifiedAgentRunner auto-repair, idempotent PENDING recovery,
 * and redacted Markdown audit reporting without requiring ErpMSA or an LLM API key.
 */
public final class DisposalGuardSample {

    public record SampleReport(
        VerifiedRunResult investigation,
        ToolExecutionOutcome retryPending,
        ToolExecutionOutcome retryCompleted,
        ToolExecutionOutcome retryReplayed,
        String auditMarkdown
    ) {}

    public static SampleReport runDemo() {
        AuditTrailListener auditTrail = new AuditTrailListener();
        InMemoryToolExecutionStore store = new InMemoryToolExecutionStore();
        AtomicInteger stockCalls = new AtomicInteger(0);

        ToolPolicy detailPolicy = ToolPolicy.builder("get_disposal")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(2)
            .boundParameters("accountId", "disposalId")
            .build();

        ToolPolicy ledgerPolicy = ToolPolicy.builder("get_stock_movements")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(3)
            .requires("get_disposal")
            .boundParameters("accountId")
            .denyRule(DenyRules.integerRange("page", 0, 10))
            .denyRule(DenyRules.forbidKeys(Set.of("authorization")))
            .build();

        ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .requires("get_disposal")
            .boundParameters("accountId", "disposalId")
            .requireIdempotencyKey(true)
            .build();

        ToolSession session = ToolSession.builder()
            .sessionId("sample-disposal-session")
            .scopeKey("sample-account-7")
            .maxAccessLevel(AccessLevel.MUTATING)
            .maxTotalCalls(10)
            .executionStore(store)
            .listener(auditTrail)
            .bindParameter("accountId", 7L)
            .bindParameter("disposalId", "disp-sample-001")
            .registerTool(detailPolicy, args -> ToolExecutionOutcome.completed(Map.of(
                "disposalId", args.get("disposalId"),
                "status", "PENDING",
                "foodMaterialId", 100L,
                "authorization", "Bearer demo-token-must-be-redacted"
            )))
            .registerTool(ledgerPolicy, args -> ToolExecutionOutcome.completed(Map.of(
                "page", args.get("page"),
                "matched", true,
                "movementId", 9001L
            )))
            .registerTool(retryPolicy, args -> {
                if (stockCalls.incrementAndGet() == 1) {
                    throw new UncertainExecutionException("Lost downstream response");
                }
                return ToolExecutionOutcome.completed(Map.of("movementId", 9001L, "quantityAfter", 17L));
            })
            .build();

        OutputVerifier verifier = OutputVerifier.builder()
            .requireExactEvidenceOrder(true)
            .requireToolExecuted("get_disposal")
            .requireToolExecuted("get_stock_movements")
            .addInvariant(
                "MATCHED_CONCLUSION",
                (out, ledger) -> Boolean.TRUE.equals(ledger.find("E2").orElseThrow().facts().get("matched"))
                    && "matched".equals(out.conclusion()),
                "Conclusion must match observed stock movement evidence",
                "Set conclusion to 'matched' because E2 confirmed movementId=9001."
            )
            .build();

        VerifiedAgentRunner runner = VerifiedAgentRunner.builder(verifier).maxAttempts(3).build();
        VerifiedRunResult investigation = runner.runOrThrow(session, (s, prior, attempt) -> {
            if (attempt == 1) {
                s.execute("get_disposal", Map.of());
                s.execute("get_stock_movements", Map.of("page", 0));
                return new AgentOutput("no_match", List.of("E1", "E99"), Map.of());
            }
            return new AgentOutput("matched", s.evidenceLedger().allIds(), Map.of("movementId", 9001L));
        });

        ToolExecutionOutcome pending = session.execute("retry_disposal", Map.of("mode", "safe"), "req-sample-1");
        ToolExecutionOutcome completed = session.execute("retry_disposal", Map.of("mode", "safe"), "req-sample-1");
        ToolExecutionOutcome replayed = session.execute("retry_disposal", Map.of("mode", "safe"), "req-sample-1");

        String markdown = auditTrail.toMarkdown(
            session.sessionId(),
            session.evidenceLedger(),
            investigation.verification()
        );
        return new SampleReport(investigation, pending, completed, replayed, markdown);
    }

    public static void main(String[] args) {
        SampleReport report = runDemo();
        System.out.println(report.auditMarkdown());
    }
}
