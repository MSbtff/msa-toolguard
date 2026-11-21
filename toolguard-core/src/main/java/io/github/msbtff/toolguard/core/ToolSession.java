package io.github.msbtff.toolguard.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic policy, scope, budget, idempotency, and evidence boundary for one agent session.
 */
public final class ToolSession {
    private record RegisteredTool(ToolPolicy policy, ToolHandler handler) {}

    private final String sessionId;
    private final String scopeKey;
    private final AccessLevel maxAccessLevel;
    private final int maxTotalCalls;
    private final Map<String, Object> boundParameters;
    private final Map<String, RegisteredTool> tools;
    private final ToolExecutionStore executionStore;
    private final ToolExecutionListener listener;
    private final EvidenceLedger evidenceLedger;
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> toolCalls = new ConcurrentHashMap<>();

    private ToolSession(Builder builder) {
        this.sessionId = builder.sessionId;
        this.scopeKey = builder.scopeKey;
        this.maxAccessLevel = builder.maxAccessLevel;
        this.maxTotalCalls = builder.maxTotalCalls;
        this.boundParameters = Collections.unmodifiableMap(new LinkedHashMap<>(builder.boundParameters));
        this.tools = Map.copyOf(builder.tools);
        this.executionStore = builder.executionStore;
        this.listener = builder.listener;
        this.evidenceLedger = new EvidenceLedger(builder.clock);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String sessionId() {
        return sessionId;
    }

    public EvidenceLedger evidenceLedger() {
        return evidenceLedger;
    }

    public int totalCallCount() {
        return totalCalls.get();
    }

    public ToolExecutionOutcome execute(String toolName, Map<String, Object> rawArguments) {
        return execute(toolName, rawArguments, null);
    }

    public ToolExecutionOutcome execute(String toolName, Map<String, Object> rawArguments, String requestId) {
        long startNanos = System.nanoTime();
        String safeToolName = toolName == null ? "" : toolName;
        ToolPolicy policy = null;
        boolean budgetReserved = false;
        try {
            RegisteredTool registered = tools.get(safeToolName);
            if (registered == null) {
                throw deny(ViolationCode.UNREGISTERED_TOOL, safeToolName,
                    "Tool '" + safeToolName + "' is not registered in this session",
                    "Call only registered tools: " + tools.keySet());
            }
            policy = registered.policy();

            if (policy.accessLevel() == AccessLevel.MUTATING && maxAccessLevel == AccessLevel.READ_ONLY) {
                throw deny(ViolationCode.ACCESS_DENIED, safeToolName,
                    "Session is READ_ONLY and cannot invoke MUTATING tool '" + safeToolName + "'",
                    "Use read-only tools or escalate to an approved mutating session.");
            }

            for (String requiredTool : policy.requires()) {
                if (!evidenceLedger.hasCompletedTool(requiredTool)) {
                    throw deny(ViolationCode.PREREQUISITE_NOT_MET, safeToolName,
                        "Tool '" + safeToolName + "' requires completed prerequisite tool '" + requiredTool + "'",
                        "Invoke '" + requiredTool + "' and verify its completion before calling '" + safeToolName + "'.");
                }
            }

            Map<String, Object> resolvedArgs = resolveAndValidateArguments(policy, rawArguments);

            for (DenyRule denyRule : policy.denyRules()) {
                Optional<String> reason = denyRule.denyReason(safeToolName, resolvedArgs);
                if (reason.isPresent()) {
                    throw deny(ViolationCode.POLICY_DENIED, safeToolName, reason.get(),
                        "Adjust tool arguments to comply with deterministic policy constraints.");
                }
            }

            String normalizedRequestId = normalizeRequestId(policy, requestId);

            checkAndIncrementBudgets(policy);
            budgetReserved = true;

            ToolPolicy activePolicy = policy;
            ToolExecutionOutcome outcome;
            if (normalizedRequestId != null) {
                String payloadDigest = CanonicalDigest.sha256(resolvedArgs);
                outcome = executionStore.executeIdempotent(
                    scopeKey,
                    safeToolName,
                    normalizedRequestId,
                    payloadDigest,
                    () -> invokeAndRecord(activePolicy, registered.handler(), resolvedArgs, normalizedRequestId)
                );
                if (outcome.replayed() || outcome.evidenceId() == null) {
                    EvidenceRecord synced = evidenceLedger.recordReplayed(
                        outcome.evidenceId(),
                        activePolicy.evidencePrefix(),
                        activePolicy.name(),
                        outcome.status(),
                        normalizedRequestId,
                        outcome.facts()
                    );
                    outcome = outcome.withEvidence(synced.evidenceId(), normalizedRequestId);
                }
            } else {
                outcome = invokeAndRecord(activePolicy, registered.handler(), resolvedArgs, null);
            }

            listener.onEvent(new ToolExecutionEvent(
                sessionId,
                safeToolName,
                true,
                null,
                outcome.status(),
                outcome.replayed(),
                Duration.ofNanos(System.nanoTime() - startNanos)
            ));
            return outcome;
        } catch (GuardrailException blocked) {
            if (budgetReserved && policy != null) {
                releaseBudget(policy);
            }
            listener.onEvent(new ToolExecutionEvent(
                sessionId,
                safeToolName,
                false,
                blocked.violation().code(),
                null,
                false,
                Duration.ofNanos(System.nanoTime() - startNanos)
            ));
            throw blocked;
        } catch (RuntimeException | Error unexpected) {
            try {
                listener.onEvent(new ToolExecutionEvent(
                    sessionId,
                    safeToolName,
                    true,
                    null,
                    null,
                    false,
                    Duration.ofNanos(System.nanoTime() - startNanos)
                ));
            } catch (RuntimeException listenerError) {
                unexpected.addSuppressed(listenerError);
            }
            throw unexpected;
        }
    }

    private ToolExecutionOutcome invokeAndRecord(
        ToolPolicy policy,
        ToolHandler handler,
        Map<String, Object> resolvedArgs,
        String requestId
    ) {
        try {
            ToolExecutionOutcome raw = Objects.requireNonNull(handler.invoke(resolvedArgs), "tool handler returned null");
            EvidenceRecord record = evidenceLedger.record(
                policy.evidencePrefix(),
                policy.name(),
                raw.status(),
                requestId,
                raw.facts()
            );
            return raw.withEvidence(record.evidenceId(), requestId);
        } catch (UncertainExecutionException uncertain) {
            String message = uncertain.getMessage() == null ? "uncertain_execution" : uncertain.getMessage();
            EvidenceRecord record = evidenceLedger.record(
                policy.evidencePrefix(),
                policy.name(),
                ToolExecutionStatus.PENDING,
                requestId,
                Map.of("reason", message)
            );
            return ToolExecutionOutcome.pending(requestId, message).withEvidence(record.evidenceId(), requestId);
        }
    }

    private Map<String, Object> resolveAndValidateArguments(ToolPolicy policy, Map<String, Object> rawArguments) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (rawArguments != null) {
            resolved.putAll(rawArguments);
        }
        for (String boundKey : policy.boundParameters()) {
            if (!boundParameters.containsKey(boundKey)) {
                throw deny(ViolationCode.SCOPE_VIOLATION, policy.name(),
                    "Required host-bound parameter '" + boundKey + "' is not configured in session scope",
                    "Ensure the host session binds '" + boundKey + "' before invoking this tool.");
            }
            Object expected = boundParameters.get(boundKey);
            if (resolved.containsKey(boundKey) && !Objects.equals(resolved.get(boundKey), expected)) {
                throw deny(ViolationCode.SCOPE_VIOLATION, policy.name(),
                    "Parameter '" + boundKey + "' is host-bound and cannot be overridden by the agent",
                    "Omit '" + boundKey + "' or pass only the host-bound scope value.");
            }
            resolved.put(boundKey, expected);
        }
        return Collections.unmodifiableMap(resolved);
    }

    private String normalizeRequestId(ToolPolicy policy, String requestId) {
        String trimmed = requestId == null ? null : requestId.trim();
        if (trimmed != null && trimmed.isEmpty()) {
            trimmed = null;
        }
        if (policy.requireIdempotencyKey() && trimmed == null) {
            throw deny(ViolationCode.MISSING_IDEMPOTENCY_KEY, policy.name(),
                "Tool '" + policy.name() + "' requires a non-blank requestId for idempotent execution",
                "Provide a stable requestId that stays identical across retries of the same operation.");
        }
        return trimmed;
    }

    private synchronized void checkAndIncrementBudgets(ToolPolicy policy) {
        if (totalCalls.get() >= maxTotalCalls) {
            throw deny(ViolationCode.CALL_BUDGET_EXCEEDED, policy.name(),
                "Session total tool call budget (" + maxTotalCalls + ") exceeded",
                "Stop calling additional tools and produce a final report with the collected evidence.");
        }
        AtomicInteger perTool = toolCalls.computeIfAbsent(policy.name(), k -> new AtomicInteger(0));
        if (perTool.get() >= policy.maxCalls()) {
            throw deny(ViolationCode.CALL_BUDGET_EXCEEDED, policy.name(),
                "Tool '" + policy.name() + "' call budget (" + policy.maxCalls() + ") exceeded",
                "Do not invoke '" + policy.name() + "' again in this session.");
        }
        totalCalls.incrementAndGet();
        perTool.incrementAndGet();
    }

    private synchronized void releaseBudget(ToolPolicy policy) {
        if (totalCalls.get() > 0) {
            totalCalls.decrementAndGet();
        }
        AtomicInteger perTool = toolCalls.get(policy.name());
        if (perTool != null && perTool.get() > 0) {
            perTool.decrementAndGet();
        }
    }

    private GuardrailException deny(ViolationCode code, String toolName, String message, String repairHint) {
        return new GuardrailException(new GuardrailViolation(code, toolName, message, repairHint));
    }

    public static final class Builder {
        private String sessionId = UUID.randomUUID().toString();
        private String scopeKey = "default";
        private AccessLevel maxAccessLevel = AccessLevel.READ_ONLY;
        private int maxTotalCalls = 50;
        private final Map<String, Object> boundParameters = new LinkedHashMap<>();
        private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();
        private ToolExecutionStore executionStore = new InMemoryToolExecutionStore();
        private ToolExecutionListener listener = ToolExecutionListener.NOOP;
        private Clock clock = Clock.systemUTC();

        private Builder() {}

        public Builder sessionId(String sessionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            return this;
        }

        public Builder scopeKey(String scopeKey) {
            this.scopeKey = Objects.requireNonNull(scopeKey, "scopeKey");
            return this;
        }

        public Builder maxAccessLevel(AccessLevel maxAccessLevel) {
            this.maxAccessLevel = Objects.requireNonNull(maxAccessLevel, "maxAccessLevel");
            return this;
        }

        public Builder maxTotalCalls(int maxTotalCalls) {
            if (maxTotalCalls < 1) {
                throw new IllegalArgumentException("maxTotalCalls must be >= 1");
            }
            this.maxTotalCalls = maxTotalCalls;
            return this;
        }

        public Builder bindParameter(String name, Object value) {
            Objects.requireNonNull(name, "name");
            this.boundParameters.put(name, value);
            return this;
        }

        public Builder registerTool(ToolPolicy policy, ToolHandler handler) {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(handler, "handler");
            this.tools.put(policy.name(), new RegisteredTool(policy, handler));
            return this;
        }

        public Builder executionStore(ToolExecutionStore executionStore) {
            this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
            return this;
        }

        public Builder listener(ToolExecutionListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Builder addListener(ToolExecutionListener additionalListener) {
            Objects.requireNonNull(additionalListener, "additionalListener");
            ToolExecutionListener existing = this.listener;
            if (existing == ToolExecutionListener.NOOP) {
                this.listener = additionalListener;
            } else {
                this.listener = event -> {
                    existing.onEvent(event);
                    additionalListener.onEvent(event);
                };
            }
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public ToolSession build() {
            return new ToolSession(this);
        }
    }
}
