package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class EvidenceVerifierTest {

    @Test
    void verifiesEvidenceGroundedOutputAndReturnsRepairHintsOnMismatch() {
        ToolSession session = ToolSession.builder()
            .registerTool(
                ToolPolicy.builder("get_disposal").build(),
                args -> ToolExecutionOutcome.completed(Map.of("status", "PENDING"))
            )
            .registerTool(
                ToolPolicy.builder("get_stock_movements").requires("get_disposal").build(),
                args -> ToolExecutionOutcome.completed(Map.of("matched", true, "movementId", 501L))
            )
            .build();

        session.execute("get_disposal", Map.of());
        session.execute("get_stock_movements", Map.of("page", 0));

        OutputVerifier verifier = OutputVerifier.builder()
            .requireExactEvidenceOrder(true)
            .requireToolExecuted("get_disposal")
            .requireToolExecuted("get_stock_movements")
            .addInvariant(
                "CONCLUSION_MATCHES_LEDGER",
                (out, ledger) -> {
                    boolean matched = Boolean.TRUE.equals(ledger.find("E2").orElseThrow().facts().get("matched"));
                    return matched ? "matched".equals(out.conclusion()) : "no_match".equals(out.conclusion());
                },
                "Conclusion contradicts stock movement evidence",
                "Evidence E2 shows matched=true, so set conclusion to 'matched'."
            )
            .build();

        VerificationResult passed = verifier.verify(
            new AgentOutput("matched", List.of("E1", "E2"), Map.of()),
            session.evidenceLedger()
        );
        assertTrue(passed.verified());

        VerificationResult fabricated = verifier.verify(
            new AgentOutput("no_match", List.of("E1", "E99"), Map.of()),
            session.evidenceLedger()
        );
        assertFalse(fabricated.verified());
        assertEquals(3, fabricated.failures().size());
        assertTrue(fabricated.failures().stream().anyMatch(f -> f.code().equals("UNKNOWN_EVIDENCE_ID")));
        assertTrue(fabricated.failures().stream().anyMatch(f -> f.code().equals("EVIDENCE_MISMATCH")));
        assertTrue(fabricated.failures().stream().anyMatch(
            f -> f.code().equals("CONCLUSION_MATCHES_LEDGER") && f.repairHint().contains("matched")
        ));
    }
}
