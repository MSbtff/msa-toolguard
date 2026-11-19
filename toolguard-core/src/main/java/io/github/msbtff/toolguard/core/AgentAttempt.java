package io.github.msbtff.toolguard.core;

import java.util.List;

/**
 * One execution attempt of an AI agent inside a {@link VerifiedAgentRunner} auto-repair loop.
 */
@FunctionalInterface
public interface AgentAttempt {
    /**
     * Executes one attempt against the bounded {@link ToolSession}.
     *
     * @param session       the deterministic guardrail session for tool calls
     * @param priorFailures verification failures from the immediately preceding attempt (empty on attempt 1)
     * @param attemptNumber 1-based attempt number
     * @return the model's candidate structured output for verification
     */
    AgentOutput execute(ToolSession session, List<VerificationFailure> priorFailures, int attemptNumber);
}
