package io.github.goldenpage.toolguard.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class InMemoryToolExecutionStore implements ToolExecutionStore {
    private record StoredExecution(String payloadDigest, ToolExecutionOutcome outcome) {}

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StoredExecution> entries = new ConcurrentHashMap<>();

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
        Object lock = locks.computeIfAbsent(compositeKey, k -> new Object());
        synchronized (lock) {
            StoredExecution existing = entries.get(compositeKey);
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
                entries.put(compositeKey, new StoredExecution(
                    payloadDigest,
                    ToolExecutionOutcome.pending(requestId, "Execution in progress")
                ));
            }

            try {
                ToolExecutionOutcome result = Objects.requireNonNull(action.get(), "tool action returned null");
                entries.put(compositeKey, new StoredExecution(payloadDigest, result));
                return result;
            } catch (UncertainExecutionException uncertain) {
                ToolExecutionOutcome pending = ToolExecutionOutcome.pending(requestId, uncertain.getMessage());
                entries.put(compositeKey, new StoredExecution(payloadDigest, pending));
                return pending;
            }
        }
    }
}
