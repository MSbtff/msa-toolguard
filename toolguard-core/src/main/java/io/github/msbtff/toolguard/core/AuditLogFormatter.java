package io.github.msbtff.toolguard.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic JSON/JSONL and Markdown audit log formatter with recursive secret redaction.
 * Output conforms to {@code contracts/audit-event-v1.schema.json} and {@code contracts/evidence-record-v1.schema.json}.
 */
public final class AuditLogFormatter {
    public static final Set<String> DEFAULT_REDACTED_KEYS = Set.of(
        "authorization",
        "password",
        "token",
        "access_token",
        "refresh_token",
        "secret",
        "apikey",
        "api_key",
        "cookie",
        "credential",
        "private_key"
    );

    private static final String REDACTED = "[REDACTED]";
    private static final AuditLogFormatter DEFAULT_INSTANCE = new AuditLogFormatter(DEFAULT_REDACTED_KEYS);

    public record AuditEntry(Instant timestamp, ToolExecutionEvent event) {
        public AuditEntry {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(event, "event");
        }
    }

    private final Set<String> redactedKeys;

    public AuditLogFormatter(Collection<String> redactedKeys) {
        Set<String> normalized = new LinkedHashSet<>();
        if (redactedKeys != null) {
            for (String key : redactedKeys) {
                if (key != null && !key.isBlank()) {
                    normalized.add(key.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.redactedKeys = Set.copyOf(normalized);
    }

    public static AuditLogFormatter defaults() {
        return DEFAULT_INSTANCE;
    }

    public Set<String> redactedKeys() {
        return redactedKeys;
    }

    public String formatEventJson(ToolExecutionEvent event, Instant timestamp) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(timestamp, "timestamp");
        return CanonicalJson.stringify(toEventMap(event, timestamp));
    }

    public String formatEvidenceJson(EvidenceRecord record) {
        Objects.requireNonNull(record, "record");
        return CanonicalJson.stringify(toEvidenceMap(record));
    }

    public String formatSessionJson(
        String sessionId,
        List<AuditEntry> entries,
        EvidenceLedger ledger,
        VerificationResult verification
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(ledger, "ledger");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sessionId", sessionId);
        List<Map<String, Object>> eventMaps = new ArrayList<>(entries.size());
        for (AuditEntry entry : entries) {
            eventMaps.add(toEventMap(entry.event(), entry.timestamp()));
        }
        root.put("events", eventMaps);

        List<EvidenceRecord> records = ledger.all();
        List<Map<String, Object>> evidenceMaps = new ArrayList<>(records.size());
        for (EvidenceRecord record : records) {
            evidenceMaps.add(toEvidenceMap(record));
        }
        root.put("evidence", evidenceMaps);

        if (verification != null) {
            Map<String, Object> verMap = new LinkedHashMap<>();
            verMap.put("verified", verification.verified());
            List<Map<String, Object>> failures = new ArrayList<>();
            for (VerificationFailure failure : verification.failures()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("code", failure.code());
                f.put("message", failure.message());
                f.put("repairHint", failure.repairHint());
                failures.add(f);
            }
            verMap.put("failures", failures);
            root.put("verification", verMap);
        } else {
            root.put("verification", null);
        }
        return CanonicalJson.stringify(root);
    }

    public String formatSessionMarkdown(
        String sessionId,
        List<AuditEntry> entries,
        EvidenceLedger ledger,
        VerificationResult verification
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(ledger, "ledger");

        StringBuilder sb = new StringBuilder();
        sb.append("## ToolGuard Audit Report (`").append(escapeInline(sessionId)).append("`)\n\n");

        long allowedCount = entries.stream().filter(e -> e.event().allowed()).count();
        long blockedCount = entries.size() - allowedCount;
        long replayedCount = entries.stream().filter(e -> e.event().replayed()).count();

        sb.append("- **Total Tool Calls**: ").append(entries.size())
            .append(" (Allowed: ").append(allowedCount)
            .append(", Blocked: ").append(blockedCount)
            .append(", Replayed: ").append(replayedCount).append(")\n");
        sb.append("- **Evidence Records**: ").append(ledger.all().size()).append('\n');
        if (verification != null) {
            sb.append("- **Verification**: ")
                .append(verification.verified() ? "PASSED" : "FAILED (" + verification.failures().size() + " issue(s))")
                .append('\n');
        }

        sb.append("\n### Execution Timeline\n\n");
        if (entries.isEmpty()) {
            sb.append("_No tool invocations recorded._\n");
        } else {
            sb.append("| Timestamp | Tool | Allowed | Status / Violation | Replayed | Elapsed (ms) |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (AuditEntry entry : entries) {
                ToolExecutionEvent ev = entry.event();
                String statusOrViolation = ev.allowed()
                    ? (ev.status() == null ? "-" : ev.status().name())
                    : (ev.violationCode() == null ? "BLOCKED" : ev.violationCode().name());
                sb.append("| ").append(entry.timestamp())
                    .append(" | `").append(escapeCell(ev.toolName())).append("`")
                    .append(" | ").append(ev.allowed() ? "YES" : "NO")
                    .append(" | ").append(escapeCell(statusOrViolation))
                    .append(" | ").append(ev.replayed() ? "YES" : "NO")
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", elapsedMillis(ev)))
                    .append(" |\n");
            }
        }

        sb.append("\n### Evidence Ledger\n\n");
        List<EvidenceRecord> records = ledger.all();
        if (records.isEmpty()) {
            sb.append("_No evidence recorded._\n");
        } else {
            sb.append("| Evidence ID | Tool | Status | Request ID | Digest (prefix) | Facts |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (EvidenceRecord record : records) {
                String shortDigest = record.digest().length() > 12
                    ? record.digest().substring(0, 12)
                    : record.digest();
                String sanitizedFacts = CanonicalJson.stringify(redactValue(record.facts()));
                sb.append("| `").append(escapeCell(record.evidenceId())).append("`")
                    .append(" | `").append(escapeCell(record.toolName())).append("`")
                    .append(" | ").append(record.status().name())
                    .append(" | ").append(record.requestId() == null ? "-" : "`" + escapeCell(record.requestId()) + "`")
                    .append(" | `").append(shortDigest).append("`")
                    .append(" | `").append(escapeCell(sanitizedFacts)).append("` |\n");
            }
        }

        if (verification != null && !verification.verified()) {
            sb.append("\n### Verification Failures\n\n");
            for (VerificationFailure failure : verification.failures()) {
                sb.append("- **").append(escapeInline(failure.code())).append("**: ")
                    .append(failure.message());
                if (!failure.repairHint().isBlank()) {
                    sb.append(" _(Repair: ").append(failure.repairHint()).append(")_");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isRedactedKey(key)) {
                    sanitized.put(key, REDACTED);
                } else {
                    sanitized.put(key, redactValue(entry.getValue()));
                }
            }
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                sanitized.add(redactValue(item));
            }
            return sanitized;
        }
        return value;
    }

    private boolean isRedactedKey(String key) {
        return redactedKeys.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> toEventMap(ToolExecutionEvent event, Instant timestamp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp.toString());
        map.put("sessionId", event.sessionId());
        map.put("toolName", event.toolName());
        map.put("allowed", event.allowed());
        map.put("violationCode", event.violationCode() == null ? null : event.violationCode().name());
        map.put("status", event.status() == null ? null : event.status().name());
        map.put("replayed", event.replayed());
        map.put("elapsedMs", elapsedMillis(event));
        return map;
    }

    private Map<String, Object> toEvidenceMap(EvidenceRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("evidenceId", record.evidenceId());
        map.put("toolName", record.toolName());
        map.put("status", record.status().name());
        map.put("requestId", record.requestId());
        map.put("recordedAt", record.recordedAt().toString());
        map.put("digest", record.digest());
        map.put("facts", redactValue(record.facts()));
        return map;
    }

    private static double elapsedMillis(ToolExecutionEvent event) {
        if (event.elapsed() == null) {
            return 0.0;
        }
        double raw = Math.max(0L, event.elapsed().toNanos()) / 1_000_000.0;
        return Math.round(raw * 1000.0) / 1000.0;
    }

    private static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private static String escapeInline(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("`", "'").replace("\n", " ");
    }
}
