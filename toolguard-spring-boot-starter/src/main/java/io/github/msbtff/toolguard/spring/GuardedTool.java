package io.github.msbtff.toolguard.spring;

import io.github.msbtff.toolguard.core.AccessLevel;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GuardedTool {
    String name();

    AccessLevel accessLevel() default AccessLevel.READ_ONLY;

    int maxCalls() default 10;

    String[] requires() default {};

    String[] boundParameters() default {};

    /**
     * Convenience flag to force idempotency key enforcement. For explicit override (including disabling on MUTATING),
     * prefer {@link #idempotency()}.
     */
    boolean requireIdempotencyKey() default false;

    IdempotencyMode idempotency() default IdempotencyMode.AUTO;

    String evidencePrefix() default "E";

    /** Case-insensitive parameter names forbidden by policy. */
    String[] forbidKeys() default {};

    /**
     * Allowlist of parameter names permitted by policy. Host-bound parameters listed in {@link #boundParameters()}
     * are automatically included when this allowlist is non-empty.
     */
    String[] allowedKeys() default {};
}
