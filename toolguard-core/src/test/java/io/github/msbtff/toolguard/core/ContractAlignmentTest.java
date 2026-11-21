package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ContractAlignmentTest {

    private static Path locateContractsDir() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate contracts/ directory from " + Path.of("").toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readContractObject(String fileName) throws IOException {
        Path file = locateContractsDir().resolve(fileName);
        return CanonicalJson.parseObject(Files.readString(file));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolPolicyValidatesAndRoundTripsAgainstToolPolicyV1Schema() throws IOException {
        Map<String, Object> schema = readContractObject("tool-policy-v1.schema.json");
        List<String> required = ((List<Object>) schema.get("required")).stream().map(String::valueOf).toList();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        String nameRegex = (String) ((Map<String, Object>) properties.get("name")).get("pattern");
        List<Object> accessEnum = (List<Object>) ((Map<String, Object>) properties.get("accessLevel")).get("enum");
        String prefixRegex = (String) ((Map<String, Object>) properties.get("evidencePrefix")).get("pattern");

        ToolPolicy policy = ToolPolicy.builder("get_stock_movements")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(3)
            .requires("get_disposal")
            .boundParameters("accountId")
            .evidencePrefix("EV")
            .build();

        Map<String, Object> serialized = CanonicalJson.parseObject(policy.toContractJson());
        assertTrue(serialized.keySet().containsAll(required));
        assertTrue(properties.keySet().containsAll(serialized.keySet()));
        assertTrue(Pattern.matches(nameRegex, (String) serialized.get("name")));
        assertTrue(accessEnum.contains(serialized.get("accessLevel")));
        assertTrue(((Number) serialized.get("maxCalls")).longValue() >= 1);
        assertTrue(Pattern.matches(prefixRegex, (String) serialized.get("evidencePrefix")));
        assertEquals(false, serialized.get("requireIdempotencyKey"));

        ToolPolicy roundTripped = ToolPolicy.fromContractJson(policy.toContractJson());
        assertEquals(policy, roundTripped);

        // Builder order independence: explicit requireIdempotencyKey(false) survives accessLevel(MUTATING)
        ToolPolicy nonIdempotentMutating = ToolPolicy.builder("flush_cache")
            .requireIdempotencyKey(false)
            .accessLevel(AccessLevel.MUTATING)
            .build();
        assertFalse(nonIdempotentMutating.requireIdempotencyKey());

        // Contract regexes are enforced at construction time
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.builder("InvalidCamelCase").build());
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.builder("1starts_with_digit").build());
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.builder(" ").build());
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.builder("valid_tool").evidencePrefix("e1").build());
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.builder("valid_tool").evidencePrefix("TOOLONG").build());
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.builder("valid_tool").maxCalls(0).build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void evidenceRecordAndAuditEventAlignWithV1Schemas() throws IOException {
        Map<String, Object> evidenceSchema = readContractObject("evidence-record-v1.schema.json");
        List<String> evidenceRequired = ((List<Object>) evidenceSchema.get("required")).stream().map(String::valueOf).toList();
        Map<String, Object> evidenceProps = (Map<String, Object>) evidenceSchema.get("properties");
        String evidenceIdRegex = (String) ((Map<String, Object>) evidenceProps.get("evidenceId")).get("pattern");
        List<Object> evidenceStatusEnum = (List<Object>) ((Map<String, Object>) evidenceProps.get("status")).get("enum");
        String digestRegex = (String) ((Map<String, Object>) evidenceProps.get("digest")).get("pattern");

        Map<String, Object> auditSchema = readContractObject("audit-event-v1.schema.json");
        List<String> auditRequired = ((List<Object>) auditSchema.get("required")).stream().map(String::valueOf).toList();
        Map<String, Object> auditProps = (Map<String, Object>) auditSchema.get("properties");
        List<Object> violationEnum = (List<Object>) ((Map<String, Object>) auditProps.get("violationCode")).get("enum");
        List<Object> auditStatusEnum = (List<Object>) ((Map<String, Object>) auditProps.get("status")).get("enum");

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
        AuditTrailListener audit = new AuditTrailListener(AuditLogFormatter.defaults(), fixedClock, null);

        ToolSession session = ToolSession.builder()
            .sessionId("sess-contract-1")
            .clock(fixedClock)
            .maxAccessLevel(AccessLevel.READ_ONLY)
            .addListener(audit)
            .registerTool(
                ToolPolicy.builder("get_disposal").maxCalls(2).evidencePrefix("EV").build(),
                args -> ToolExecutionOutcome.completed(Map.of("status", "PENDING", "authorization", "Bearer secret"))
            )
            .registerTool(
                ToolPolicy.builder("reject_tool").maxCalls(1).build(),
                args -> ToolExecutionOutcome.rejected("VALIDATION_FAILED", "bad input", Map.of("field", "qty"))
            )
            .registerTool(
                ToolPolicy.builder("retry_disposal").accessLevel(AccessLevel.MUTATING).build(),
                args -> ToolExecutionOutcome.completed(Map.of("ok", true))
            )
            .build();

        session.execute("get_disposal", Map.of());
        session.execute("reject_tool", Map.of());
        assertThrows(GuardrailException.class, () -> session.execute("retry_disposal", Map.of(), "req-1"));

        for (EvidenceRecord record : session.evidenceLedger().all()) {
            Map<String, Object> json = CanonicalJson.parseObject(AuditLogFormatter.defaults().formatEvidenceJson(record));
            assertTrue(json.keySet().containsAll(evidenceRequired));
            assertTrue(evidenceProps.keySet().containsAll(json.keySet()));
            assertTrue(Pattern.matches(evidenceIdRegex, (String) json.get("evidenceId")));
            assertTrue(evidenceStatusEnum.contains(json.get("status")));
            assertTrue(Pattern.matches(digestRegex, (String) json.get("digest")));
            assertTrue(json.get("facts") instanceof Map<?, ?>);
        }

        List<String> lines = audit.toJsonLines();
        assertEquals(3, lines.size());
        for (String line : lines) {
            Map<String, Object> event = CanonicalJson.parseObject(line);
            assertTrue(event.keySet().containsAll(auditRequired));
            assertTrue(auditProps.keySet().containsAll(event.keySet()));
            assertTrue(violationEnum.contains(event.get("violationCode")));
            assertTrue(auditStatusEnum.contains(event.get("status")));
            assertTrue(((Number) event.get("elapsedMs")).doubleValue() >= 0.0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void canonicalJsonAndSha256MatchSharedCrossLanguageContractVectors() throws IOException {
        Map<String, Object> doc = readContractObject("canonical-digest-vectors-v1.json");
        List<Map<String, Object>> vectors = (List<Map<String, Object>>) doc.get("vectors");
        assertNotNull(vectors);
        assertFalse(vectors.isEmpty());

        for (Map<String, Object> vector : vectors) {
            String name = (String) vector.get("name");
            Object input = vector.get("input");
            String expectedCanonical = (String) vector.get("expectedCanonicalJson");
            String expectedSha256 = (String) vector.get("expectedSha256");

            assertEquals(expectedCanonical, CanonicalJson.stringifySorted(input), "canonical JSON mismatch for " + name);
            assertEquals(expectedSha256, CanonicalDigest.sha256(input), "SHA-256 mismatch for " + name);
        }
    }

    @Test
    void policyContractDeserializationRejectsInvalidPayloads() {
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.fromContractMap(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.fromContractMap(Map.of(
            "name", "get_disposal",
            "accessLevel", "READ_ONLY",
            "maxCalls", 1.5
        )));
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.fromContractMap(Map.of(
            "name", "get_disposal",
            "accessLevel", "READ_ONLY",
            "maxCalls", 1,
            "requireIdempotencyKey", "yes"
        )));
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.fromContractMap(Map.of(
            "name", "get_disposal",
            "accessLevel", "READ_ONLY",
            "maxCalls", 1,
            "requires", "not_a_list"
        )));
        assertThrows(IllegalArgumentException.class, () -> new ToolPolicy(
            "get_disposal", AccessLevel.READ_ONLY, 1, Set.of(" "), Set.of(), false, "E", List.of()
        ));
    }
}
