package io.github.msbtff.toolguard.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Thread-safe in-memory {@link ApprovalGate} that binds every approval strictly to
 * {@code (scopeKey, toolName, payloadDigest)}, enforces TTL expiration, supports revocation,
 * and consumes each grant once while still allowing idempotent retries of the same {@code requestId}.
 */
public final class InMemoryApprovalGate implements ApprovalGate {
    private static final class Grant {
        final String approvalId;
        final String scopeKey;
        final String toolName;
        final String payloadDigest;
        final String approver;
        final Instant expiresAt;
        boolean consumed;
        String consumedRequestId;
        boolean revoked;

        Grant(
            String approvalId,
            String scopeKey,
            String toolName,
            String payloadDigest,
            String approver,
            Instant expiresAt
        ) {
            this.approvalId = approvalId;
            this.scopeKey = scopeKey;
            this.toolName = toolName;
            this.payloadDigest = payloadDigest;
            this.approver = approver;
            this.expiresAt = expiresAt;
        }
    }

    private final Clock clock;
    private final Map<String, Grant> grantsById = new LinkedHashMap<>();

    public InMemoryApprovalGate() {
        this(Clock.systemUTC());
    }

    public InMemoryApprovalGate(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Grants one-time execution approval bound strictly to the canonical SHA-256 digest of {@code expectedArguments}.
     */
    public String grant(
        String scopeKey,
        String toolName,
        Map<String, Object> expectedArguments,
        String approver,
        Duration ttl
    ) {
        Objects.requireNonNull(expectedArguments, "expectedArguments");
        return grantByDigest(scopeKey, toolName, CanonicalDigest.sha256(expectedArguments), approver, ttl);
    }

    /**
     * Grants one-time execution approval bound strictly to a precomputed canonical SHA-256 {@code payloadDigest}.
     */
    public synchronized String grantByDigest(
        String scopeKey,
        String toolName,
        String payloadDigest,
        String approver,
        Duration ttl
    ) {
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        Objects.requireNonNull(approver, "approver");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        String approvalId = "apr-" + UUID.randomUUID();
        Instant expiresAt = clock.instant().plus(ttl);
        grantsById.put(approvalId, new Grant(approvalId, scopeKey, toolName, payloadDigest, approver, expiresAt));
        return approvalId;
    }

    public synchronized boolean revoke(String approvalId) {
        Grant grant = grantsById.get(approvalId);
        if (grant == null || grant.revoked) {
            return false;
        }
        grant.revoked = true;
        return true;
    }

    @Override
    public synchronized ApprovalDecision check(ApprovalRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        if (request.requestId() != null) {
            for (Grant grant : grantsById.values()) {
                if (matchesTarget(grant, request)
                    && !grant.revoked
                    && !now.isAfter(grant.expiresAt)
                    && grant.consumed
                    && Objects.equals(grant.consumedRequestId, request.requestId())) {
                    return ApprovalDecision.approve(grant.approvalId, grant.approver);
                }
            }
        }
        for (Grant grant : grantsById.values()) {
            if (matchesTarget(grant, request)
                && !grant.revoked
                && !grant.consumed
                && !now.isAfter(grant.expiresAt)) {
                grant.consumed = true;
                grant.consumedRequestId = request.requestId();
                return ApprovalDecision.approve(grant.approvalId, grant.approver);
            }
        }
        return ApprovalDecision.deny(
            "No valid approval grant for tool '" + request.toolName()
                + "' in scope '" + request.scopeKey()
                + "' with payload digest " + request.payloadDigest()
        );
    }

    private static boolean matchesTarget(Grant grant, ApprovalRequest request) {
        return grant.scopeKey.equals(request.scopeKey())
            && grant.toolName.equals(request.toolName())
            && grant.payloadDigest.equals(request.payloadDigest());
    }
}
