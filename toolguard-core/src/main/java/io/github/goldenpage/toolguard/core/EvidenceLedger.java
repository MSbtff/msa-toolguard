package io.github.goldenpage.toolguard.core;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class EvidenceLedger {
    private final Clock clock;
    private final Map<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private final List<EvidenceRecord> records = new ArrayList<>();
    private final Map<String, EvidenceRecord> byId = new LinkedHashMap<>();

    public EvidenceLedger() {
        this(Clock.systemUTC());
    }

    public EvidenceLedger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized EvidenceRecord record(
        String prefix,
        String toolName,
        ToolExecutionStatus status,
        String requestId,
        Map<String, Object> facts
    ) {
        String normalizedPrefix = (prefix == null || prefix.isBlank()) ? "E" : prefix;
        int next = sequences.computeIfAbsent(normalizedPrefix, k -> new AtomicInteger(0)).incrementAndGet();
        String evidenceId = normalizedPrefix + next;
        Map<String, Object> safeFacts = facts == null ? Map.of() : new LinkedHashMap<>(facts);
        String digest = CanonicalDigest.sha256(Map.of(
            "toolName", toolName,
            "status", status.name(),
            "requestId", requestId == null ? "" : requestId,
            "facts", safeFacts
        ));
        EvidenceRecord record = new EvidenceRecord(
            evidenceId,
            toolName,
            status,
            requestId,
            clock.instant(),
            digest,
            safeFacts
        );
        records.add(record);
        byId.put(evidenceId, record);
        return record;
    }

    public synchronized List<EvidenceRecord> all() {
        return List.copyOf(records);
    }

    public synchronized Optional<EvidenceRecord> find(String evidenceId) {
        return Optional.ofNullable(byId.get(evidenceId));
    }

    public synchronized List<String> allIds() {
        return List.copyOf(byId.keySet());
    }

    public synchronized boolean hasCompletedTool(String toolName) {
        return records.stream()
            .anyMatch(r -> r.toolName().equals(toolName) && r.status() == ToolExecutionStatus.COMPLETED);
    }
}
