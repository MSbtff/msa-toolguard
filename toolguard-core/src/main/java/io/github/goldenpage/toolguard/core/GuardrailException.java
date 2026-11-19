package io.github.goldenpage.toolguard.core;

import java.util.Objects;

public class GuardrailException extends RuntimeException {
    private final GuardrailViolation violation;

    public GuardrailException(GuardrailViolation violation) {
        super(Objects.requireNonNull(violation, "violation").code() + ": " + violation.message());
        this.violation = violation;
    }

    public GuardrailViolation violation() {
        return violation;
    }
}
