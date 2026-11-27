package io.github.msbtff.toolguard.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.msbtff.toolguard.core.AccessLevel;
import io.github.msbtff.toolguard.core.AgentOutput;
import io.github.msbtff.toolguard.core.OutputVerifier;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.core.ToolPolicy;
import io.github.msbtff.toolguard.core.ToolSession;
import io.github.msbtff.toolguard.core.ViolationCode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void serializesRedactedVcrCassetteToDiskAndReplaysAcrossIntegerAndLongArguments(@TempDir Path tempDir) throws Exception {
        ToolTranscriptRecorder recorder = new ToolTranscriptRecorder();
        ToolPolicy queryPolicy = ToolPolicy.builder("get_stock_movements")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(3)
            .boundParameters("accountId")
            .build();
        ToolPolicy rejectPolicy = ToolPolicy.builder("validate_disposal")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(2)
            .build();

        ToolSession recordSession = ToolSession.builder()
            .bindParameter("accountId", 7L)
            .registerTool(queryPolicy, recorder.record("get_stock_movements", args ->
                ToolExecutionOutcome.completed(Map.of(
                    "page", args.get("page"),
                    "authorization", "Bearer live-secret-token",
                    "nested", List.of(Map.of("api_key", "sk-live-999", "matched", true))
                ))
            ))
            .registerTool(rejectPolicy, recorder.record("validate_disposal", args ->
                ToolExecutionOutcome.rejected("INSUFFICIENT_STOCK", "Not enough quantity", Map.of("available", 2L))
            ))
            .build();

        recordSession.execute("get_stock_movements", Map.of("page", 0));
        recordSession.execute("validate_disposal", Map.of("qty", 5));

        Path cassetteFile = tempDir.resolve("cassettes/disposal-flow.json");
        recorder.saveTo(cassetteFile);
        String rawJson = java.nio.file.Files.readString(cassetteFile);
        assertFalse(rawJson.contains("live-secret-token"));
        assertFalse(rawJson.contains("sk-live-999"));
        assertTrue(rawJson.contains("[REDACTED]"));

        ToolTranscriptRecorder loaded = ToolTranscriptRecorder.loadFrom(cassetteFile);
        assertEquals(2, loaded.entries().size());

        ToolSession replaySession = ToolSession.builder()
            .bindParameter("accountId", 7) // Integer 7 matches recorded Long 7L via canonical JSON equality
            .registerTool(queryPolicy, loaded.replayHandler("get_stock_movements"))
            .registerTool(rejectPolicy, loaded.replayHandler("validate_disposal"))
            .build();

        ToolExecutionOutcome queryReplayed = replaySession.execute("get_stock_movements", Map.of("page", 0));
        assertEquals(ToolExecutionStatus.COMPLETED, queryReplayed.status());
        assertEquals("[REDACTED]", queryReplayed.facts().get("authorization"));

        ToolExecutionOutcome rejectReplayed = replaySession.execute("validate_disposal", Map.of("qty", 5L));
        assertEquals(ToolExecutionStatus.REJECTED, rejectReplayed.status());
        assertEquals("INSUFFICIENT_STOCK", rejectReplayed.rejectionCode());
        assertEquals("Not enough quantity", rejectReplayed.message());

        // Argument mismatch fails deterministically
        ToolTranscriptRecorder loadedAgain = ToolTranscriptRecorder.fromJson(rawJson);
        ToolSession mismatchSession = ToolSession.builder()
            .bindParameter("accountId", 7L)
            .registerTool(queryPolicy, loadedAgain.replayHandler("get_stock_movements"))
            .build();
        assertThrows(AssertionError.class, () -> mismatchSession.execute("get_stock_movements", Map.of("page", 99)));

        // Malformed / unsupported cassette versions rejected
        assertThrows(IllegalArgumentException.class, () -> ToolTranscriptRecorder.fromJson("{\"version\":99,\"entries\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> ToolTranscriptRecorder.fromJson("{\"version\":1,\"entries\":\"bad\"}"));
    }
}
