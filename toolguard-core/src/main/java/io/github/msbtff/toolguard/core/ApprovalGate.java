package io.github.msbtff.toolguard.core;

/**
 * SPI for Human-in-the-Loop (HITL) approval checks on tools that require explicit sign-off.
 * Defaults to fail-closed ({@link #DENY_ALL}) when no gate is configured on a session.
 */
@FunctionalInterface
public interface ApprovalGate {
    ApprovalGate DENY_ALL = request -> ApprovalDecision.deny(
        "Tool '" + request.toolName() + "' requires explicit approval for payload digest " + request.payloadDigest()
    );

    ApprovalDecision check(ApprovalRequest request);
}
