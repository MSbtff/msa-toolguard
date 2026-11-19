package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class VerifiedAgentRunnerTest {

    @Test
    void autoRepairsAgentOutputUsingDeterministicFailureFeedback() {
        ToolSession session = ToolSession.builder()
            .registerTool(
                ToolPolicy.builder("get_disposal").maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of("status", "PENDING"))
            )
            .registerTool(
                ToolPolicy.builder("get_stock_movements").requires("get_disposal").maxCalls(2).build(),
                args -> ToolExecutionOutcome.completed(Map.of("matched", true, "movementId", 77L))
            )
            .build();

        OutputVerifier verifier = OutputVerifier.builder()
            .requireExactEvidenceOrder(true)
            .requireToolExecuted("get_disposal")
            .requireToolExecuted("get_stock_movements")
            .addInvariant(
                "CONCLUSION_MATCHES_EVIDENCE",
                (out, ledger) -> Boolean.TRUE.equals(ledger.find("E2").orElseThrow().facts().get("matched"))
                    && "matched".equals(out.conclusion()),
                "Conclusion does not match stock movement ledger",
                "Set conclusion to 'matched' because E2 confirms movementId=77."
            )
            .build();

        List<String> feedbackLog = new ArrayList<>();
        VerifiedAgentRunner runner = VerifiedAgentRunner.builder(verifier)
            .maxAttempts(3)
            .onVerificationFailure((attempt, res) ->
                feedbackLog.add(VerifiedAgentRunner.formatRepairFeedback(res, session.evidenceLedger())))
            .build();

        VerifiedRunResult result = runner.runOrThrow(session, (s, priorFailures, attempt) -> {
            if (attempt == 1) {
                s.execute("get_disposal", Map.of());
                s.execute("get_stock_movements", Map.of("page", 0));
                // Hallucinated conclusion & evidence ID on first try
                return new AgentOutput("no_match", List.of("E1", "E99"), Map.of());
            }
            assertFalse(priorFailures.isEmpty());
            assertTrue(priorFailures.stream().anyMatch(f -> f.code().equals("UNKNOWN_EVIDENCE_ID")));
            assertTrue(priorFailures.stream().anyMatch(f -> f.code().equals("CONCLUSION_MATCHES_EVIDENCE")));
            // Self-repaired output grounded strictly in recorded ledger
            return new AgentOutput("matched", s.evidenceLedger().allIds(), Map.of("movementId", 77L));
        });

        assertTrue(result.verified());
        assertEquals(2, result.attempts());
        assertEquals(1, result.failureHistory().size());
        assertEquals("matched", result.output().conclusion());
        assertEquals(List.of("E1", "E2"), result.output().citedEvidenceIds());
        assertEquals(1, feedbackLog.size());
        assertTrue(feedbackLog.getFirst().contains("UNKNOWN_EVIDENCE_ID"));
        assertTrue(feedbackLog.getFirst().contains("[E1, E2]"));
    }

    @Test
    void throwsVerificationExhaustedExceptionWhenAllAttemptsFail() {
        ToolSession session = ToolSession.builder()
            .registerTool(
                ToolPolicy.builder("get_disposal").build(),
                args -> ToolExecutionOutcome.completed(Map.of("status", "PENDING"))
            )
            .build();
        session.execute("get_disposal", Map.of());

        OutputVerifier verifier = OutputVerifier.builder()
            .requireToolExecuted("get_disposal")
            .build();

        VerifiedAgentRunner runner = VerifiedAgentRunner.builder(verifier)
            .maxAttempts(2)
            .build();

        VerificationExhaustedException thrown = assertThrows(
            VerificationExhaustedException.class,
            () -> runner.runOrThrow(session, (s, prior, attempt) ->
                new AgentOutput("pending", List.of("E999"), Map.of()))
        );
        assertFalse(thrown.result().verified());
        assertEquals(2, thrown.result().attempts());
        assertEquals(2, thrown.result().failureHistory().size());
    }
}
