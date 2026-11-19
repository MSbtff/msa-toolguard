package io.github.goldenpage.toolguard.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.goldenpage.toolguard.core.AccessLevel;
import io.github.goldenpage.toolguard.core.AgentOutput;
import io.github.goldenpage.toolguard.core.OutputVerifier;
import io.github.goldenpage.toolguard.core.ToolExecutionOutcome;
import io.github.goldenpage.toolguard.core.ToolPolicy;
import io.github.goldenpage.toolguard.core.ToolSession;
import io.github.goldenpage.toolguard.core.ViolationCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReplayHarnessTest {

    @Test
    void recordsAndDeterministicallyReplaysToolFlowWithAssertions() {
        ToolTranscriptRecorder recorder = new ToolTranscriptRecorder();
        ToolPolicy detailPolicy = ToolPolicy.builder("get_disposal")
            .accessLevel(AccessLevel.READ_ONLY)
            .boundParameters("disposalId")
            .build();

        // Record phase
        ToolSession recordSession = ToolSession.builder()
            .bindParameter("disposalId", "disp-77")
            .registerTool(detailPolicy, recorder.record("get_disposal",
                args -> ToolExecutionOutcome.completed(Map.of("status", "PENDING"))))
            .build();
        recordSession.execute("get_disposal", Map.of());
        assertEquals(1, recorder.entries().size());

        // Replay phase (deterministic, no live dependency)
        ToolSession replaySession = ToolSession.builder()
            .bindParameter("disposalId", "disp-77")
            .registerTool(detailPolicy, recorder.replayHandler("get_disposal"))
            .build();

        GuardrailAssertions.assertBlocked(
            ViolationCode.SCOPE_VIOLATION,
            () -> replaySession.execute("get_disposal", Map.of("disposalId", "disp-wrong"))
        );

        ToolExecutionOutcome replayed = replaySession.execute("get_disposal", Map.of());
        assertEquals("PENDING", replayed.facts().get("status"));

        OutputVerifier verifier = OutputVerifier.builder().requireToolExecuted("get_disposal").build();
        GuardrailAssertions.assertVerificationFailsWith(
            "UNKNOWN_EVIDENCE_ID",
            verifier.verify(new AgentOutput("pending", List.of("E99"), Map.of()), replaySession.evidenceLedger())
        );

        // Exhausted replay tape fails deterministically
        assertThrows(AssertionError.class, () -> replaySession.execute("get_disposal", Map.of()));
    }
}
