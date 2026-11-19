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

    boolean requireIdempotencyKey() default false;

    String evidencePrefix() default "E";
}
