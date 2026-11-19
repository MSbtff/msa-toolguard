package io.github.goldenpage.toolguard.core;

import java.time.Duration;

public record ToolExecutionEvent(
    String sessionId,
    String toolName,
    boolean allowed,
    ViolationCode violationCode,
    ToolExecutionStatus status,
    boolean replayed,
    Duration elapsed
) {}
