package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AuditLogFormatterTest {

    @Test
    void formatsJsonlAndMarkdownWithRecursiveSecretRedaction() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-19T09:00:00Z"), ZoneOffset.UTC);
        List<String> streamedLines = new ArrayList<>();
        AuditTrailListener auditTrail = new AuditTrailListener(
            AuditLogFormatter.defaults(),
            fixedClock,
            streamedLines::add
        );

        ToolSession session = ToolSession.builder()
            .sessionId("sess-audit-1")
            .clock(fixedClock)
            .maxAccessLevel(AccessLevel.READ_ONLY)
            .listener(auditTrail)
            .registerTool(
                ToolPolicy.builder("get_disposal").maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of(
                    "status", "PENDING",
                    "authorization", "Bearer super-secret-jwt",
                    "nested", Map.of("api_key", "sk-live-secret", "foodMaterialId", 7L)
                ))
            )
            .registerTool(
                ToolPolicy.builder("retry_disposal").accessLevel(AccessLevel.MUTATING).build(),
                args -> ToolExecutionOutcome.completed(Map.of("status", "COMPLETED"))
            )
            .build();

        session.execute("get_disposal", Map.of());
        assertThrows(GuardrailException.class, () -> session.execute("retry_disposal", Map.of(), "req-1"));

        assertEquals(2, streamedLines.size());

        Map<String, Object> firstEvent = CanonicalJson.parseObject(streamedLines.get(0));
        assertEquals("2026-11-19T09:00:00Z", firstEvent.get("timestamp"));
        assertEquals("sess-audit-1", firstEvent.get("sessionId"));
        assertEquals("get_disposal", firstEvent.get("toolName"));
        assertEquals(true, firstEvent.get("allowed"));
        assertEquals("COMPLETED", firstEvent.get("status"));
        assertEquals(false, firstEvent.get("replayed"));

        Map<String, Object> secondEvent = CanonicalJson.parseObject(streamedLines.get(1));
        assertEquals(false, secondEvent.get("allowed"));
        assertEquals("ACCESS_DENIED", secondEvent.get("violationCode"));

        String evidenceJson = AuditLogFormatter.defaults().formatEvidenceJson(session.evidenceLedger().all().getFirst());
        assertFalse(evidenceJson.contains("super-secret-jwt"));
        assertFalse(evidenceJson.contains("sk-live-secret"));
        assertTrue(evidenceJson.contains("[REDACTED]"));

        VerificationResult verification = OutputVerifier.builder()
            .requireToolExecuted("get_disposal")
            .build()
            .verify(new AgentOutput("pending", List.of("E1"), Map.of()), session.evidenceLedger());

        String sessionJson = auditTrail.toSessionJson(session.sessionId(), session.evidenceLedger(), verification);
        assertFalse(sessionJson.contains("super-secret-jwt"));
        assertTrue(sessionJson.contains("\"verified\":true"));

        String markdown = auditTrail.toMarkdown(session.sessionId(), session.evidenceLedger(), verification);
        assertTrue(markdown.contains("## ToolGuard Audit Report (`sess-audit-1`)"));
        assertTrue(markdown.contains("ACCESS_DENIED"));
        assertTrue(markdown.contains("PASSED"));
        assertFalse(markdown.contains("super-secret-jwt"));
    }

    @Test
    void coversDefaultAuditTrailListenerToJsonLinesAndFailedVerificationMarkdown() {
        AuditLogFormatter customFormatter = new AuditLogFormatter(List.of("custom_secret"));
        assertTrue(customFormatter.redactedKeys().contains("custom_secret"));

        AuditTrailListener defaultListener = new AuditTrailListener();
        ToolSession session = ToolSession.builder()
            .sessionId("sess-audit-2")
            .maxAccessLevel(AccessLevel.MUTATING)
            .addListener(defaultListener)
            .registerTool(
                ToolPolicy.builder("uncertain_tool").accessLevel(AccessLevel.MUTATING).build(),
                args -> {
                    throw new UncertainExecutionException("network drop", new RuntimeException("io error"));
                }
            )
            .build();

        ToolExecutionOutcome pending = session.execute("uncertain_tool", Map.of(), "req-uncertain");
        assertEquals(ToolExecutionStatus.PENDING, pending.status());

        List<String> jsonLines = defaultListener.toJsonLines();
        assertEquals(1, jsonLines.size());
        Map<String, Object> parsed = CanonicalJson.parseObject(jsonLines.getFirst());
        assertEquals("PENDING", parsed.get("status"));

        VerificationResult failed = VerificationResult.fail(List.of(
            new VerificationFailure("STATUS_MISMATCH", "Pipe | and `tick`", "Retry after checking `ledger`")
        ));
        String sessionJson = defaultListener.toSessionJson(session.sessionId(), session.evidenceLedger(), failed);
        assertTrue(sessionJson.contains("\"verified\":false"));
        assertTrue(sessionJson.contains("STATUS_MISMATCH"));

        String markdown = defaultListener.toMarkdown(session.sessionId(), session.evidenceLedger(), failed);
        assertTrue(markdown.contains("FAILED"));
        assertTrue(markdown.contains("STATUS_MISMATCH"));

        // Verify array redaction inside nested facts
        EvidenceLedger ledger = new EvidenceLedger();
        EvidenceRecord record = ledger.record(
            "E",
            "tool_a",
            ToolExecutionStatus.COMPLETED,
            null,
            Map.of("items", List.of(Map.of("custom_secret", "top-secret", "visible", "ok")))
        );
        String redactedEvidence = customFormatter.formatEvidenceJson(record);
        assertFalse(redactedEvidence.contains("top-secret"));
        assertTrue(redactedEvidence.contains("[REDACTED]"));
        assertTrue(redactedEvidence.contains("ok"));
    }
}
