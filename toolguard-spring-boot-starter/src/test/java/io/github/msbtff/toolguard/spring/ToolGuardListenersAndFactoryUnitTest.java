package io.github.msbtff.toolguard.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.msbtff.toolguard.core.AccessLevel;
import io.github.msbtff.toolguard.core.AuditLogFormatter;
import io.github.msbtff.toolguard.core.GuardrailException;
import io.github.msbtff.toolguard.core.InMemoryToolExecutionStore;
import io.github.msbtff.toolguard.core.ToolExecutionEvent;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.core.ToolPolicy;
import io.github.msbtff.toolguard.core.ToolSession;
import io.github.msbtff.toolguard.core.ViolationCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ToolGuardListenersAndFactoryUnitTest {

    @Test
    void sessionFactoryAppliesPropertiesAndFansOutToAllListeners() {
        ToolGuardProperties properties = new ToolGuardProperties();
        properties.setEnabled(true);
        properties.setDefaultAccessLevel(AccessLevel.READ_ONLY);
        properties.setDefaultMaxTotalCalls(3);
        properties.getStore().setType(ToolGuardProperties.StoreType.IN_MEMORY);
        properties.getStore().getJdbc().setTableName("custom_execs");
        properties.getStore().getJdbc().setInitializeSchema(false);
        properties.getStore().getRedis().setKeyPrefix("tg:test:");
        properties.getStore().getRedis().setLockTtl(Duration.ofSeconds(15));
        properties.getStore().getRedis().setLockAcquireTimeout(Duration.ofSeconds(5));
        properties.getStore().getRedis().setStateTtl(Duration.ofHours(12));
        properties.getAudit().setEnabled(true);
        properties.getAudit().setRedactKeys(List.of("secret", "token"));

        assertTrue(properties.isEnabled());
        assertEquals("custom_execs", properties.getStore().getJdbc().getTableName());
        assertFalse(properties.getStore().getJdbc().isInitializeSchema());
        assertEquals("tg:test:", properties.getStore().getRedis().getKeyPrefix());
        assertEquals(Duration.ofSeconds(15), properties.getStore().getRedis().getLockTtl());
        assertEquals(Duration.ofSeconds(5), properties.getStore().getRedis().getLockAcquireTimeout());
        assertEquals(Duration.ofHours(12), properties.getStore().getRedis().getStateTtl());
        assertTrue(properties.getAudit().isEnabled());
        assertEquals(List.of("secret", "token"), properties.getAudit().getRedactKeys());

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
        List<ToolExecutionEvent> captured = new ArrayList<>();

        ToolSessionFactory factory = new ToolSessionFactory(
            properties,
            new InMemoryToolExecutionStore(),
            List.of(
                new MicrometerToolExecutionListener(meters),
                new Slf4jAuditToolExecutionListener(AuditLogFormatter.defaults(), fixedClock),
                captured::add
            )
        );

        ToolSession session = factory.newSession("tenant-7")
            .registerTool(
                ToolPolicy.builder("get_disposal").maxCalls(2).build(),
                args -> ToolExecutionOutcome.completed(Map.of("status", "PENDING"))
            )
            .registerTool(
                ToolPolicy.builder("retry_disposal").accessLevel(AccessLevel.MUTATING).maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of("status", "COMPLETED"))
            )
            .build();

        assertEquals(ToolExecutionStatus.COMPLETED, session.execute("get_disposal", Map.of()).status());
        assertThrows(GuardrailException.class, () -> session.execute("retry_disposal", Map.of(), "req-1"));

        assertEquals(2, captured.size());
        assertEquals(
            1.0,
            meters.get("toolguard.tool.calls").tag("tool", "get_disposal").tag("outcome", "completed").counter().count()
        );
        assertEquals(
            1.0,
            meters.get("toolguard.tool.violations").tag("tool", "retry_disposal").tag("code", "ACCESS_DENIED").counter().count()
        );
        assertNotNull(meters.get("toolguard.tool.duration").tag("tool", "get_disposal").timer());
    }

    @Test
    void micrometerAndSlf4jListenersHandleBlankToolNamesNullStatusAndNullElapsed() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MicrometerToolExecutionListener micrometer = new MicrometerToolExecutionListener(meters);
        Slf4jAuditToolExecutionListener slf4j = new Slf4jAuditToolExecutionListener(AuditLogFormatter.defaults());

        ToolExecutionEvent blankAllowed = new ToolExecutionEvent("s1", "  ", true, null, null, true, null);
        ToolExecutionEvent blocked = new ToolExecutionEvent(
            "s1",
            "bad_tool",
            false,
            ViolationCode.POLICY_DENIED,
            null,
            false,
            Duration.ofMillis(3)
        );

        micrometer.onEvent(blankAllowed);
        micrometer.onEvent(blocked);
        slf4j.onEvent(blankAllowed);
        slf4j.onEvent(blocked);

        assertEquals(
            1.0,
            meters.get("toolguard.tool.calls")
                .tag("tool", "unknown")
                .tag("outcome", "unknown")
                .tag("replayed", "true")
                .counter()
                .count()
        );
        assertEquals(
            1.0,
            meters.get("toolguard.tool.violations")
                .tag("tool", "bad_tool")
                .tag("code", "POLICY_DENIED")
                .counter()
                .count()
        );
        assertEquals(
            1L,
            meters.get("toolguard.tool.duration")
                .tag("tool", "bad_tool")
                .tag("outcome", "blocked")
                .timer()
                .count()
        );
    }
}
