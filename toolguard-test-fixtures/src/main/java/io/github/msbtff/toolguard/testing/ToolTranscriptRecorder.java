package io.github.msbtff.toolguard.testing;

import io.github.msbtff.toolguard.core.AuditLogFormatter;
import io.github.msbtff.toolguard.core.CanonicalJson;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.core.ToolHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic Record/Replay (VCR) harness for testing agent tool flows without live LLM/network calls.
 * Supports JSON cassette serialization ({@link #toJson()} / {@link #fromJson(String)}) with automatic
 * recursive secret redaction so cassettes can be safely committed for CI regression testing.
 */
public final class ToolTranscriptRecorder {
    public static final int CASSETTE_VERSION = 1;
    private static final String REDACTED = "[REDACTED]";

    public record Entry(String toolName, Map<String, Object> arguments, ToolExecutionOutcome outcome) {
        public Entry {
            Objects.requireNonNull(toolName, "toolName");
            arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public ToolTranscriptRecorder() {}

    private ToolTranscriptRecorder(List<Entry> initialEntries) {
        this.entries.addAll(initialEntries);
    }

    public ToolHandler record(String toolName, ToolHandler delegate) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(delegate, "delegate");
        return resolvedArgs -> {
            ToolExecutionOutcome outcome = delegate.invoke(resolvedArgs);
            synchronized (entries) {
                entries.add(new Entry(toolName, resolvedArgs, outcome));
            }
            return outcome;
        };
    }

    public List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public ToolHandler replayHandler(String toolName) {
        Deque<Entry> queue = new ArrayDeque<>();
        synchronized (entries) {
            for (Entry entry : entries) {
                if (entry.toolName().equals(toolName)) {
                    queue.addLast(entry);
                }
            }
        }
        return resolvedArgs -> {
            Entry next = queue.pollFirst();
            if (next == null) {
                throw new AssertionError("No remaining recorded replay entry for tool '" + toolName + "'");
            }
            Map<String, Object> actualArgs = resolvedArgs == null ? Map.of() : resolvedArgs;
            if (!CanonicalJson.stringifySorted(next.arguments()).equals(CanonicalJson.stringifySorted(actualArgs))) {
                throw new AssertionError(
                    "Replay argument mismatch for tool '" + toolName + "': expected "
                        + next.arguments() + " but got " + actualArgs
                );
            }
            return next.outcome();
        };
    }

    /**
     * Serializes recorded entries into a versioned JSON cassette, recursively redacting default secret keys.
     */
    public String toJson() {
        return toJson(AuditLogFormatter.DEFAULT_REDACTED_KEYS);
    }

    /**
     * Serializes recorded entries into a versioned JSON cassette, recursively redacting the given keys.
     */
    public String toJson(Collection<String> redactedKeys) {
        Set<String> normalizedKeys = normalizeRedactedKeys(redactedKeys);
        List<Entry> snapshot = entries();
        List<Map<String, Object>> serializedEntries = new ArrayList<>(snapshot.size());
        for (Entry entry : snapshot) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolName", entry.toolName());
            item.put("arguments", redactValue(entry.arguments(), normalizedKeys));
            item.put("outcome", serializeOutcome(entry.outcome(), normalizedKeys));
            serializedEntries.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", CASSETTE_VERSION);
        root.put("entries", serializedEntries);
        return CanonicalJson.stringify(root);
    }

    public void saveTo(Path cassettePath) throws IOException {
        saveTo(cassettePath, AuditLogFormatter.DEFAULT_REDACTED_KEYS);
    }

    public void saveTo(Path cassettePath, Collection<String> redactedKeys) throws IOException {
        Objects.requireNonNull(cassettePath, "cassettePath");
        Path parent = cassettePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(cassettePath, toJson(redactedKeys), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static ToolTranscriptRecorder fromJson(String json) {
        Map<String, Object> root = CanonicalJson.parseObject(json);
        Object rawVersion = root.get("version");
        if (!(rawVersion instanceof Number num) || num instanceof Double || num instanceof Float
            || num.intValue() != CASSETTE_VERSION) {
            throw new IllegalArgumentException("Unsupported cassette version: " + rawVersion);
        }
        Object rawEntries = root.get("entries");
        if (!(rawEntries instanceof List<?> list)) {
            throw new IllegalArgumentException("Cassette field 'entries' must be a JSON array");
        }
        List<Entry> parsed = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> entryMap)) {
                throw new IllegalArgumentException("Cassette entry must be a JSON object");
            }
            String toolName = requireNonBlankString(entryMap.get("toolName"), "toolName");
            Object rawArgs = entryMap.get("arguments");
            Map<String, Object> args = rawArgs == null ? Map.of() : requireObject(rawArgs, "arguments");
            Map<String, Object> outcomeMap = requireObject(entryMap.get("outcome"), "outcome");
            ToolExecutionOutcome outcome = deserializeOutcome(outcomeMap);
            parsed.add(new Entry(toolName, args, outcome));
        }
        return new ToolTranscriptRecorder(parsed);
    }

    public static ToolTranscriptRecorder loadFrom(Path cassettePath) throws IOException {
        Objects.requireNonNull(cassettePath, "cassettePath");
        return fromJson(Files.readString(cassettePath, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> serializeOutcome(ToolExecutionOutcome outcome, Set<String> redactedKeys) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", outcome.status().name());
        map.put("evidenceId", outcome.evidenceId());
        map.put("requestId", outcome.requestId());
        map.put("rejectionCode", outcome.rejectionCode());
        map.put("message", outcome.message());
        map.put("replayed", outcome.replayed());
        map.put("facts", redactValue(outcome.facts(), redactedKeys));
        return map;
    }

    private static ToolExecutionOutcome deserializeOutcome(Map<String, Object> map) {
        ToolExecutionStatus status = ToolExecutionStatus.valueOf(requireNonBlankString(map.get("status"), "outcome.status"));
        String evidenceId = optionalString(map.get("evidenceId"), "outcome.evidenceId");
        String requestId = optionalString(map.get("requestId"), "outcome.requestId");
        String rejectionCode = optionalString(map.get("rejectionCode"), "outcome.rejectionCode");
        String message = optionalString(map.get("message"), "outcome.message");
        Object rawReplayed = map.get("replayed");
        boolean replayed = rawReplayed instanceof Boolean bool && bool;
        Object rawFacts = map.get("facts");
        Map<String, Object> facts = rawFacts == null ? Map.of() : requireObject(rawFacts, "outcome.facts");
        return new ToolExecutionOutcome(status, evidenceId, requestId, rejectionCode, message, replayed, facts);
    }

    private static Set<String> normalizeRedactedKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                normalized.add(key.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static Object redactValue(Object value, Set<String> redactedKeys) {
        if (value == null || redactedKeys.isEmpty()) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (redactedKeys.contains(key.toLowerCase(Locale.ROOT))) {
                    sanitized.put(key, REDACTED);
                } else {
                    sanitized.put(key, redactValue(entry.getValue(), redactedKeys));
                }
            }
            return sanitized;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> sanitized = new ArrayList<>(collection.size());
            for (Object item : collection) {
                sanitized.add(redactValue(item, redactedKeys));
            }
            return sanitized;
        }
        return value;
    }

    private static String requireNonBlankString(Object value, String field) {
        if (!(value instanceof String str) || str.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid string field '" + field + "'");
        }
        return str;
    }

    private static String optionalString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        throw new IllegalArgumentException("Field '" + field + "' must be a string or null");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Field '" + field + "' must be a JSON object");
        }
        return (Map<String, Object>) map;
    }
}
