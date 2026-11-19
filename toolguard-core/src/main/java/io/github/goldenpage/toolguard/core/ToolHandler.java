package io.github.goldenpage.toolguard.core;

import java.util.Map;

@FunctionalInterface
public interface ToolHandler {
    ToolExecutionOutcome invoke(Map<String, Object> resolvedArguments);
}
