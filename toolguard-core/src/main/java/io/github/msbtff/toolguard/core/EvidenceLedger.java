package io.github.msbtff.toolguard.core;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EvidenceLedger {
    private static final Pattern EVIDENCE_ID_PATTERN = Pattern.compile("^([A-Z]{1,4})([1-9][0-9]*)$");

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
        return appendRecord(evidenceId, toolName, status, requestId, facts);
    }

    /**
     * Synchronizes an idempotently replayed (or store-synthesized) outcome into this session's ledger.
     * Avoids duplicates if already present in this session, preserves the original evidenceId when unoccupied,
     * and allocates a collision-free evidenceId if another record already occupies that ID.
     */
    public synchronized EvidenceRecord recordReplayed(
        String existingEvidenceId,
        String prefix,
        String toolName,
        ToolExecutionStatus status,
        String requestId,
        Map<String, Object> facts
    ) {
        if (requestId != null) {
            for (EvidenceRecord existing : records) {
                if (existing.toolName().equals(toolName)
                    && Objects.equals(existing.requestId(), requestId)
                    && existing.status() == status) {
                    return existing;
                }
            }
        }
        if (existingEvidenceId != null && !existingEvidenceId.isBlank() && !byId.containsKey(existingEvidenceId)) {
            advanceSequenceIfMatching(existingEvidenceId);
            return appendRecord(existingEvidenceId, toolName, status, requestId, facts);
        }
        return record(prefix, toolName, status, requestId, facts);
    }

    private EvidenceRecord appendRecord(
        String evidenceId,
        String toolName,
        ToolExecutionStatus status,
        String requestId,
        Map<String, Object> facts
    ) {
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

    private void advanceSequenceIfMatching(String evidenceId) {
        Matcher matcher = EVIDENCE_ID_PATTERN.matcher(evidenceId);
        if (!matcher.matches()) {
            return;
        }
        String parsedPrefix = matcher.group(1);
        try {
            int parsedNumber = Integer.parseInt(matcher.group(2));
            sequences.computeIfAbsent(parsedPrefix, k -> new AtomicInteger(0))
                .accumulateAndGet(parsedNumber, Math::max);
        } catch (NumberFormatException ignored) {
            // Ignore numbers overflowing int range.
        }
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
