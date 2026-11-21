package io.github.msbtff.toolguard.core;

import java.util.Objects;

/**
 * Result returned by an {@link ApprovalGate}.
 */
public record ApprovalDecision(
    boolean approved,
    String approvalId,
    String approver,
    String reason
) {
    public ApprovalDecision {
        reason = reason == null ? "" : reason;
    }

    public static ApprovalDecision approve(String approvalId, String approver) {
        Objects.requireNonNull(approvalId, "approvalId");
        return new ApprovalDecision(true, approvalId, approver, "");
    }

    public static ApprovalDecision deny(String reason) {
        return new ApprovalDecision(false, null, null, Objects.requireNonNull(reason, "reason"));
    }

    public static ApprovalDecision pendingApproval(String approvalId, String reason) {
        Objects.requireNonNull(approvalId, "approvalId");
        return new ApprovalDecision(false, approvalId, null, Objects.requireNonNull(reason, "reason"));
    }
}
