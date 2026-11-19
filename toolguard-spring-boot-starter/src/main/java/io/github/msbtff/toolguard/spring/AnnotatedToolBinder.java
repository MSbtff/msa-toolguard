package io.github.msbtff.toolguard.spring;

import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolHandler;
import io.github.msbtff.toolguard.core.ToolPolicy;
import io.github.msbtff.toolguard.core.ToolSession;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.annotation.AnnotationUtils;

/**
 * Registers @GuardedTool-annotated methods on a target Spring bean into a ToolSession.Builder.
 * Annotated methods must accept a single Map&lt;String, Object&gt; parameter and return ToolExecutionOutcome.
 */
public final class AnnotatedToolBinder {
    private AnnotatedToolBinder() {}

    public static void registerAll(ToolSession.Builder builder, Object targetBean) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(targetBean, "targetBean");
        for (Method method : targetBean.getClass().getMethods()) {
            GuardedTool annotation = AnnotationUtils.findAnnotation(method, GuardedTool.class);
            if (annotation == null) {
                continue;
            }
            if (method.getParameterCount() != 1
                || !Map.class.isAssignableFrom(method.getParameterTypes()[0])
                || !ToolExecutionOutcome.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException(
                    "@GuardedTool method must have signature ToolExecutionOutcome(Map<String, Object>): " + method
                );
            }
            ToolPolicy.Builder policyBuilder = ToolPolicy.builder(annotation.name())
                .accessLevel(annotation.accessLevel())
                .maxCalls(annotation.maxCalls())
                .requires(annotation.requires())
                .boundParameters(annotation.boundParameters())
                .evidencePrefix(annotation.evidencePrefix());
            if (annotation.requireIdempotencyKey()) {
                policyBuilder.requireIdempotencyKey(true);
            }
            ToolHandler handler = resolvedArgs -> invoke(method, targetBean, resolvedArgs);
            builder.registerTool(policyBuilder.build(), handler);
        }
    }

    private static ToolExecutionOutcome invoke(Method method, Object target, Map<String, Object> resolvedArgs) {
        try {
            return (ToolExecutionOutcome) method.invoke(target, resolvedArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Guarded tool invocation failed: " + method.getName(), cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access @GuardedTool method: " + method.getName(), e);
        }
    }
}
