package io.github.goldenpage.toolguard.testing;

import io.github.goldenpage.toolguard.core.GuardrailException;
import io.github.goldenpage.toolguard.core.GuardrailViolation;
import io.github.goldenpage.toolguard.core.ToolExecutionOutcome;
import io.github.goldenpage.toolguard.core.VerificationResult;
import io.github.goldenpage.toolguard.core.ViolationCode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;

public final class GuardrailAssertions {
    private GuardrailAssertions() {}

    public static GuardrailViolation assertBlocked(ViolationCode expectedCode, Executable action) {
        GuardrailException thrown = Assertions.assertThrows(GuardrailException.class, action);
        Assertions.assertEquals(expectedCode, thrown.violation().code());
        return thrown.violation();
    }

    public static void assertVerificationFailsWith(String expectedFailureCode, VerificationResult result) {
        Assertions.assertFalse(result.verified(), "Expected verification to fail with " + expectedFailureCode);
        boolean matched = result.failures().stream().anyMatch(f -> f.code().equals(expectedFailureCode));
        Assertions.assertTrue(
            matched,
            "Expected failure code '" + expectedFailureCode + "' but got: " + result.failures()
        );
    }

    public static void assertIdempotentReplay(ToolExecutionOutcome first, ToolExecutionOutcome second) {
        Assertions.assertFalse(first.replayed(), "First execution must not be marked replayed");
        Assertions.assertTrue(second.replayed(), "Second execution must be marked replayed");
        Assertions.assertEquals(first.status(), second.status());
        Assertions.assertEquals(first.evidenceId(), second.evidenceId());
        Assertions.assertEquals(first.facts(), second.facts());
    }
}
