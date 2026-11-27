package io.github.msbtff.toolguard.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-safe, bounded in-memory {@link ToolExecutionStore} using lock striping, collision-free composite keys,
 * and TTL/LRU eviction to prevent unbounded memory growth.
 */
public final class InMemoryToolExecutionStore implements ToolExecutionStore {
    public static final int DEFAULT_MAX_ENTRIES = 10_000;
    public static final Duration DEFAULT_ENTRY_TTL = Duration.ofHours(24);
    private static final int LOCK_STRIPES = 256;

    private record ExecutionKey(String scopeKey, String toolName, String requestId) {}

    private record StoredExecution(String payloadDigest, ToolExecutionOutcome outcome, long updatedAtMillis) {}

    private final int maxEntries;
    private final long entryTtlMillis;
    private final Clock clock;
    private final Object[] lockStripes = new Object[LOCK_STRIPES];
    private final Map<ExecutionKey, StoredExecution> entries = new LinkedHashMap<>(64, 0.75f, true);

    public InMemoryToolExecutionStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_ENTRY_TTL, Clock.systemUTC());
    }

    public InMemoryToolExecutionStore(int maxEntries, Duration entryTtl, Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1");
        }
        Objects.requireNonNull(entryTtl, "entryTtl");
        if (entryTtl.isZero() || entryTtl.isNegative()) {
            throw new IllegalArgumentException("entryTtl must be positive");
        }
        this.maxEntries = maxEntries;
        this.entryTtlMillis = entryTtl.toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
        for (int i = 0; i < LOCK_STRIPES; i++) {
            lockStripes[i] = new Object();
        }
    }

    @Override
    public ToolExecutionOutcome executeIdempotent(
        String scopeKey,
        String toolName,
        String requestId,
        String payloadDigest,
        Supplier<ToolExecutionOutcome> action
    ) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        Objects.requireNonNull(action, "action");

        ExecutionKey key = new ExecutionKey(scopeKey, toolName, requestId);
        Object stripeLock = lockStripes[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
        synchronized (stripeLock) {
            long now = clock.millis();
            StoredExecution existing = getNonExpired(key, now);
            if (existing != null) {
                if (!existing.payloadDigest().equals(payloadDigest)) {
                    throw new GuardrailException(new GuardrailViolation(
                        ViolationCode.IDEMPOTENCY_CONFLICT,
                        toolName,
                        "requestId '" + requestId + "' was already used with different arguments",
                        "Use the exact same arguments when retrying requestId '" + requestId + "', or generate a new requestId."
                    ));
                }
                if (existing.outcome().status() != ToolExecutionStatus.PENDING) {
                    return existing.outcome().asReplayed();
                }
            } else {
                putBounded(
                    key,
                    new StoredExecution(
                        payloadDigest,
                        ToolExecutionOutcome.pending(requestId, "Execution in progress"),
                        now
                    ),
                    now
                );
            }

            try {
                ToolExecutionOutcome result = Objects.requireNonNull(action.get(), "tool action returned null");
                long completedAt = clock.millis();
                putBounded(key, new StoredExecution(payloadDigest, result, completedAt), completedAt);
                return result;
            } catch (UncertainExecutionException uncertain) {
                ToolExecutionOutcome pending = ToolExecutionOutcome.pending(requestId, uncertain.getMessage());
                long failedAt = clock.millis();
                putBounded(key, new StoredExecution(payloadDigest, pending, failedAt), failedAt);
                return pending;
            }
        }
    }

    public int size() {
        synchronized (entries) {
            pruneExpiredLocked(clock.millis());
            return entries.size();
        }
    }

    private StoredExecution getNonExpired(ExecutionKey key, long now) {
        synchronized (entries) {
            StoredExecution existing = entries.get(key);
            if (existing == null) {
                return null;
            }
            if (isExpired(existing, now)) {
                entries.remove(key);
                return null;
            }
            return existing;
        }
    }

    private void putBounded(ExecutionKey key, StoredExecution value, long now) {
        synchronized (entries) {
            entries.put(key, value);
            if (entries.size() > maxEntries) {
                pruneExpiredLocked(now);
            }
            if (entries.size() > maxEntries) {
                Iterator<Map.Entry<ExecutionKey, StoredExecution>> it = entries.entrySet().iterator();
                while (entries.size() > maxEntries && it.hasNext()) {
                    Map.Entry<ExecutionKey, StoredExecution> candidate = it.next();
                    if (!candidate.getKey().equals(key)
                        && candidate.getValue().outcome().status() != ToolExecutionStatus.PENDING) {
                        it.remove();
                    }
                }
            }
            if (entries.size() > maxEntries) {
                Iterator<Map.Entry<ExecutionKey, StoredExecution>> it = entries.entrySet().iterator();
                while (entries.size() > maxEntries && it.hasNext()) {
                    Map.Entry<ExecutionKey, StoredExecution> candidate = it.next();
                    if (!candidate.getKey().equals(key)) {
                        it.remove();
                    }
                }
            }
        }
    }

    private void pruneExpiredLocked(long now) {
        Iterator<Map.Entry<ExecutionKey, StoredExecution>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (isExpired(it.next().getValue(), now)) {
                it.remove();
            }
        }
    }

    private boolean isExpired(StoredExecution record, long now) {
        return now - record.updatedAtMillis() >= entryTtlMillis;
    }
}
