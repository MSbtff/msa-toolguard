package io.github.msbtff.toolguard.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable context passed to an {@link ApprovalGate} before executing a high-risk tool.
 * The {@link #payloadDigest()} binds approval strictly to the exact host-resolved arguments.
 */
public record ApprovalRequest(
    String sessionId,
    String scopeKey,
    String toolName,
    String requestId,
    String payloadDigest,
    Map<String, Object> arguments
) {
    public ApprovalRequest {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(scopeKey, "scopeKey");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(payloadDigest, "payloadDigest");
        arguments = arguments == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
