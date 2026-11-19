package io.github.msbtff.toolguard.core;

import java.util.function.Supplier;

/**
 * SPI for idempotent and durable tool execution state (in-memory, JDBC, or Redis).
 */
public interface ToolExecutionStore {
    ToolExecutionOutcome executeIdempotent(
        String scopeKey,
        String toolName,
        String requestId,
        String payloadDigest,
        Supplier<ToolExecutionOutcome> action
    );
}
