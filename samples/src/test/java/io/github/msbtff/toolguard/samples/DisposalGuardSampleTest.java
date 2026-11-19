package io.github.msbtff.toolguard.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.testing.GuardrailAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DisposalGuardSampleTest {

    @Test
    void standaloneSampleDemonstratesAutoRepairPendingRecoveryAndRedactedAudit() {
        DisposalGuardSample.SampleReport report = DisposalGuardSample.runDemo();

        assertTrue(report.investigation().verified());
        assertEquals(2, report.investigation().attempts());
        assertEquals(ToolExecutionStatus.PENDING, report.retryPending().status());
        assertEquals(ToolExecutionStatus.COMPLETED, report.retryCompleted().status());
        GuardrailAssertions.assertIdempotentReplay(report.retryCompleted(), report.retryReplayed());

        assertTrue(report.auditMarkdown().contains("## ToolGuard Audit Report (`sample-disposal-session`)"));
        assertTrue(report.auditMarkdown().contains("[REDACTED]"));
        assertFalse(report.auditMarkdown().contains("demo-token-must-be-redacted"));
    }
}
