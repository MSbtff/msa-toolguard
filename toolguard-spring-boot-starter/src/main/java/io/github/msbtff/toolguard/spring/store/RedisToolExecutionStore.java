package io.github.msbtff.toolguard.spring.store;

import io.github.msbtff.toolguard.core.CanonicalJson;
import io.github.msbtff.toolguard.core.GuardrailException;
import io.github.msbtff.toolguard.core.GuardrailViolation;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.core.ToolExecutionStore;
import io.github.msbtff.toolguard.core.UncertainExecutionException;
import io.github.msbtff.toolguard.core.ViolationCode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis-backed durable {@link ToolExecutionStore} using atomic distributed locks ({@code SET NX PX}
 * + Lua compare-and-delete) and persistent JSON execution records for cross-restart recovery.
 */
public final class RedisToolExecutionStore implements ToolExecutionStore {
    public static final String DEFAULT_KEY_PREFIX = "toolguard:exec:";
    public static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_LOCK_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_STATE_TTL = Duration.ofHours(24);

    private static final long POLL_INTERVAL_MS = 20L;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration lockTtl;
    private final Duration lockAcquireTimeout;
    private final Duration stateTtl;

    public RedisToolExecutionStore(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_KEY_PREFIX, DEFAULT_LOCK_TTL, DEFAULT_LOCK_ACQUIRE_TIMEOUT, DEFAULT_STATE_TTL);
    }

    public RedisToolExecutionStore(
        StringRedisTemplate redisTemplate,
        String keyPrefix,
        Duration lockTtl,
        Duration lockAcquireTimeout,
        Duration stateTtl
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? DEFAULT_KEY_PREFIX : keyPrefix;
        this.lockTtl = requirePositive(lockTtl, "lockTtl");
        this.lockAcquireTimeout = requirePositive(lockAcquireTimeout, "lockAcquireTimeout");
        this.stateTtl = requirePositive(stateTtl, "stateTtl");
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

        String compositeKey = scopeKey + "::" + toolName + "::" + requestId;
        String stateKey = keyPrefix + compositeKey;
        String lockKey = keyPrefix + "lock:" + compositeKey;
        String lockToken = UUID.randomUUID().toString();

        if (!acquireLock(lockKey, lockToken)) {
            return ToolExecutionOutcome.pending(requestId, "Timed out waiting for distributed execution lock");
        }

        try {
            String rawState = redisTemplate.opsForValue().get(stateKey);
            if (rawState != null) {
                StoredRecord existing = deserialize(rawState);
                if (!existing.payloadDigest().equals(payloadDigest)) {
                    throw new GuardrailException(new GuardrailViolation(
                        ViolationCode.IDEMPOTENCY_CONFLICT,
                        toolName,
                        "requestId '" + requestId + "' was already used with different arguments",
                        "Use the exact same arguments when retrying requestId '" + requestId + "', or generate a new requestId."
                    ));
                }
                if (existing.status() != ToolExecutionStatus.PENDING) {
                    return existing.toOutcome(requestId, true);
                }
            } else {
                writeState(stateKey, payloadDigest, ToolExecutionOutcome.pending(requestId, "Execution in progress"));
            }

            try {
                ToolExecutionOutcome result = Objects.requireNonNull(action.get(), "tool action returned null");
                writeState(stateKey, payloadDigest, result);
                return result;
            } catch (UncertainExecutionException uncertain) {
                ToolExecutionOutcome pending = ToolExecutionOutcome.pending(requestId, uncertain.getMessage());
                writeState(stateKey, payloadDigest, pending);
                return pending;
            }
        } finally {
            releaseLock(lockKey, lockToken);
        }
    }

    public Optional<ToolExecutionOutcome> find(String scopeKey, String toolName, String requestId) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(requestId, "requestId");
        String stateKey = keyPrefix + scopeKey + "::" + toolName + "::" + requestId;
        String raw = redisTemplate.opsForValue().get(stateKey);
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(raw).toOutcome(requestId, false));
    }

    private boolean acquireLock(String lockKey, String lockToken) {
        long deadlineNanos = System.nanoTime() + lockAcquireTimeout.toNanos();
        while (true) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, lockTtl);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void releaseLock(String lockKey, String lockToken) {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
    }

    private void writeState(String stateKey, String payloadDigest, ToolExecutionOutcome outcome) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("payloadDigest", payloadDigest);
        record.put("status", outcome.status().name());
        record.put("evidenceId", outcome.evidenceId());
        record.put("rejectionCode", outcome.rejectionCode());
        record.put("message", outcome.message());
        record.put("facts", outcome.facts());
        redisTemplate.opsForValue().set(stateKey, CanonicalJson.stringify(record), stateTtl);
    }

    @SuppressWarnings("unchecked")
    private static StoredRecord deserialize(String rawJson) {
        Map<String, Object> map = CanonicalJson.parseObject(rawJson);
        String payloadDigest = Objects.requireNonNull((String) map.get("payloadDigest"), "payloadDigest");
        ToolExecutionStatus status = ToolExecutionStatus.valueOf(
            Objects.requireNonNull((String) map.get("status"), "status")
        );
        String evidenceId = (String) map.get("evidenceId");
        String rejectionCode = (String) map.get("rejectionCode");
        String message = (String) map.get("message");
        Object rawFacts = map.get("facts");
        Map<String, Object> facts = rawFacts instanceof Map<?, ?> m
            ? (Map<String, Object>) m
            : Map.of();
        return new StoredRecord(payloadDigest, status, evidenceId, rejectionCode, message, facts);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record StoredRecord(
        String payloadDigest,
        ToolExecutionStatus status,
        String evidenceId,
        String rejectionCode,
        String message,
        Map<String, Object> facts
    ) {
        ToolExecutionOutcome toOutcome(String requestId, boolean replayed) {
            return new ToolExecutionOutcome(status, evidenceId, requestId, rejectionCode, message, replayed, facts);
        }
    }
}
