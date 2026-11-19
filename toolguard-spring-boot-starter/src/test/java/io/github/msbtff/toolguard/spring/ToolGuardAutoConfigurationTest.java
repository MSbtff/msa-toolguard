package io.github.msbtff.toolguard.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.msbtff.toolguard.core.AccessLevel;
import io.github.msbtff.toolguard.core.AuditLogFormatter;
import io.github.msbtff.toolguard.core.GuardrailException;
import io.github.msbtff.toolguard.core.InMemoryToolExecutionStore;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStore;
import io.github.msbtff.toolguard.core.ToolSession;
import io.github.msbtff.toolguard.core.ViolationCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("integration")
class ToolGuardAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ToolGuardAutoConfiguration.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(
            "toolguard.default-max-total-calls=4",
            "toolguard.default-access-level=READ_ONLY"
        );

    static class SampleDisposalTools {
        @GuardedTool(name = "get_disposal", accessLevel = AccessLevel.READ_ONLY, maxCalls = 1, boundParameters = {"disposalId"})
        public ToolExecutionOutcome getDisposal(Map<String, Object> args) {
            return ToolExecutionOutcome.completed(Map.of("disposalId", args.get("disposalId"), "status", "PENDING"));
        }

        @GuardedTool(name = "retry_disposal", accessLevel = AccessLevel.MUTATING, maxCalls = 1, requireIdempotencyKey = true)
        public ToolExecutionOutcome retryDisposal(Map<String, Object> args) {
            return ToolExecutionOutcome.completed(Map.of("status", "COMPLETED"));
        }
    }

    @Test
    void autoConfiguresSessionFactoryAnnotatedToolsAuditAndMicrometerMetrics() {
        contextRunner.run(context -> {
            ToolSessionFactory factory = context.getBean(ToolSessionFactory.class);
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            assertNotNull(factory);
            assertNotNull(context.getBean(AuditLogFormatter.class));
            assertNotNull(context.getBean(Slf4jAuditToolExecutionListener.class));
            assertEquals(InMemoryToolExecutionStore.class, context.getBean(ToolExecutionStore.class).getClass());

            ToolSession.Builder builder = factory.newSession("account-1")
                .bindParameter("disposalId", "disp-42");
            AnnotatedToolBinder.registerAll(builder, new SampleDisposalTools());
            ToolSession session = builder.build();

            ToolExecutionOutcome readOutcome = session.execute("get_disposal", Map.of());
            assertEquals("E1", readOutcome.evidenceId());

            GuardrailException blocked = assertThrows(
                GuardrailException.class,
                () -> session.execute("retry_disposal", Map.of(), "req-1")
            );
            assertEquals(ViolationCode.ACCESS_DENIED, blocked.violation().code());

            assertEquals(
                1.0,
                meters.get("toolguard.tool.calls").tag("tool", "get_disposal").tag("outcome", "completed").counter().count()
            );
            assertEquals(
                1.0,
                meters.get("toolguard.tool.violations").tag("tool", "retry_disposal").tag("code", "ACCESS_DENIED").counter().count()
            );
        });
    }
}
