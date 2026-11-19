package io.github.msbtff.toolguard.core;

import java.util.Map;
import java.util.Optional;

@FunctionalInterface
public interface DenyRule {
    /**
     * Evaluates the normalized arguments before execution.
     *
     * @return non-empty denial reason if the call must be blocked.
     */
    Optional<String> denyReason(String toolName, Map<String, Object> arguments);
}
