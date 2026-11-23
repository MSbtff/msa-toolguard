package io.github.msbtff.toolguard.core;

import java.util.Map;

/**
 * Dynamic predicate determining whether a specific tool invocation requires Human-in-the-Loop approval
 * based on resolved arguments (for example, mutations exceeding a quantity or monetary threshold).
 */
@FunctionalInterface
public interface ApprovalPredicate {
    boolean requiresApproval(String toolName, Map<String, Object> resolvedArguments);
}
