package io.github.msbtff.toolguard.spring.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.msbtff.toolguard.core.AccessLevel;
import io.github.msbtff.toolguard.core.AgentOutput;
import io.github.msbtff.toolguard.core.GuardrailException;
import io.github.msbtff.toolguard.core.OutputVerifier;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.core.ToolExecutionStore;
import io.github.msbtff.toolguard.core.ToolPolicy;
import io.github.msbtff.toolguard.core.ToolSession;
import io.github.msbtff.toolguard.core.UncertainExecutionException;
import io.github.msbtff.toolguard.core.VerificationResult;
import io.github.msbtff.toolguard.core.ViolationCode;
import io.github.msbtff.toolguard.spring.ToolGuardAutoConfiguration;
import io.github.msbtff.toolguard.spring.ToolSessionFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@Testcontainers
class JdbcToolExecutionStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("toolguard_test")
        .withUsername("toolguard")
        .withPassword("toolguard");

    static HikariDataSource dataSource;

    @BeforeAll
    static void setUpDataSource() {
        dataSource = newDataSource();
    }

    private static HikariDataSource newDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(16);
        return new HikariDataSource(config);
    }

    @AfterAll
    static void tearDownDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void recoversPendingAfterRestartAndBlocksConflictingPayloadAcrossStoreInstances() {
        AtomicInteger downstreamCalls = new AtomicInteger(0);
        ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .build();

        // Process 1: uncertain downstream failure -> persisted as PENDING in PostgreSQL
        JdbcToolExecutionStore storeBeforeRestart = new JdbcToolExecutionStore(dataSource);
        ToolSession session1 = ToolSession.builder()
            .scopeKey("account-jdbc-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(storeBeforeRestart)
            .registerTool(retryPolicy, args -> {
                downstreamCalls.incrementAndGet();
                throw new UncertainExecutionException("Lost response after downstream stock call");
            })
            .build();

        ToolExecutionOutcome pending = session1.execute(
            "retry_disposal",
            Map.of("disposalId", "disp-101", "quantity", 3L),
            "req-restart-1"
        );
        assertEquals(ToolExecutionStatus.PENDING, pending.status());
        assertFalse(pending.replayed());
        assertEquals(ToolExecutionStatus.PENDING,
            storeBeforeRestart.find("account-jdbc-1", "retry_disposal", "req-restart-1").orElseThrow().status());

        // Process 2 (fresh store & session simulating JVM restart):
        // Conflicting arguments with same requestId -> IDEMPOTENCY_CONFLICT
        JdbcToolExecutionStore storeAfterRestart = new JdbcToolExecutionStore(
            dataSource,
            JdbcToolExecutionStore.DEFAULT_TABLE_NAME,
            Clock.systemUTC(),
            false
        );
        ToolSession session2 = ToolSession.builder()
            .scopeKey("account-jdbc-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(storeAfterRestart)
            .registerTool(retryPolicy, args -> {
                downstreamCalls.incrementAndGet();
                return ToolExecutionOutcome.completed(Map.of("movementId", 8801L, "quantityAfter", 17L));
            })
            .build();

        GuardrailException conflict = assertThrows(
            GuardrailException.class,
            () -> session2.execute(
                "retry_disposal",
                Map.of("disposalId", "disp-101", "quantity", 99L),
                "req-restart-1"
            )
        );
        assertEquals(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.violation().code());
        assertEquals(1, downstreamCalls.get());

        // Same arguments after restart -> safely resumes PENDING and completes once
        ToolExecutionOutcome completed = session2.execute(
            "retry_disposal",
            Map.of("disposalId", "disp-101", "quantity", 3L),
            "req-restart-1"
        );
        assertEquals(ToolExecutionStatus.COMPLETED, completed.status());
        assertFalse(completed.replayed());
        assertEquals(8801L, completed.facts().get("movementId"));
        assertEquals(2, downstreamCalls.get());

        // Process 3 (another restart): replays persisted COMPLETED outcome without invoking handler,
        // synchronizes EvidenceLedger, and satisfies downstream requires + OutputVerifier
        JdbcToolExecutionStore storeThirdBoot = new JdbcToolExecutionStore(dataSource);
        ToolSession session3 = ToolSession.builder()
            .scopeKey("account-jdbc-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(storeThirdBoot)
            .registerTool(retryPolicy, args -> {
                downstreamCalls.incrementAndGet();
                throw new AssertionError("Downstream must not be invoked on durable replay");
            })
            .registerTool(
                ToolPolicy.builder("confirm_settlement").requires("retry_disposal").maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of("confirmed", true))
            )
            .build();

        ToolExecutionOutcome replayed = session3.execute(
            "retry_disposal",
            Map.of("disposalId", "disp-101", "quantity", 3L),
            "req-restart-1"
        );
        assertEquals(ToolExecutionStatus.COMPLETED, replayed.status());
        assertTrue(replayed.replayed());
        assertEquals(completed.evidenceId(), replayed.evidenceId());
        assertEquals(8801L, replayed.facts().get("movementId"));
        assertEquals(17L, replayed.facts().get("quantityAfter"));
        assertEquals(2, downstreamCalls.get());
        assertTrue(session3.evidenceLedger().hasCompletedTool("retry_disposal"));

        ToolExecutionOutcome confirmed = session3.execute("confirm_settlement", Map.of());
        assertEquals(ToolExecutionStatus.COMPLETED, confirmed.status());
        VerificationResult verified = OutputVerifier.builder()
            .requireToolExecuted("retry_disposal")
            .requireToolExecuted("confirm_settlement")
            .build()
            .verify(new AgentOutput("confirmed", List.of(replayed.evidenceId(), confirmed.evidenceId()), Map.of()), session3.evidenceLedger());
        assertTrue(verified.verified());
    }

    @Test
    void purgeTerminalBeforeRemovesOldCompletedRowsWhilePreservingPendingRows() {
        Instant t0 = Instant.parse("2026-11-01T00:00:00Z");
        JdbcToolExecutionStore store = new JdbcToolExecutionStore(
            dataSource,
            "toolguard_purge_test",
            Clock.fixed(t0, ZoneOffset.UTC),
            true,
            Duration.ofSeconds(5)
        );
        ToolSession session = ToolSession.builder()
            .scopeKey("acct-purge")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store)
            .registerTool(
                ToolPolicy.builder("done_tool").accessLevel(AccessLevel.MUTATING).build(),
                args -> ToolExecutionOutcome.completed(Map.of("ok", true))
            )
            .registerTool(
                ToolPolicy.builder("pending_tool").accessLevel(AccessLevel.MUTATING).build(),
                args -> {
                    throw new UncertainExecutionException("still uncertain");
                }
            )
            .build();

        session.execute("done_tool", Map.of(), "req-done");
        session.execute("pending_tool", Map.of(), "req-pending");

        int purged = store.purgeTerminalBefore(t0.plus(Duration.ofHours(1)));
        assertEquals(1, purged);
        assertTrue(store.find("acct-purge", "done_tool", "req-done").isEmpty());
        assertEquals(ToolExecutionStatus.PENDING, store.find("acct-purge", "pending_tool", "req-pending").orElseThrow().status());

        // Calling initializeSchema() again is idempotent and purge index exists on PostgreSQL
        store.initializeSchema();
        try (var conn = dataSource.getConnection();
             var rs = conn.getMetaData().getIndexInfo(null, null, "toolguard_purge_test", false, false)) {
            boolean foundPurgeIndex = false;
            while (rs.next()) {
                if ("idx_toolguard_purge_test_status_updated".equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    foundPurgeIndex = true;
                    break;
                }
            }
            assertTrue(foundPurgeIndex, "Expected purge index idx_toolguard_purge_test_status_updated to exist");
        } catch (Exception e) {
            throw new AssertionError("Failed to verify JDBC purge index metadata", e);
        }
    }

    @Test
    void serializesConcurrentRetriesWithDatabaseRowLock() throws Exception {
        JdbcToolExecutionStore store = new JdbcToolExecutionStore(dataSource);
        AtomicInteger executions = new AtomicInteger(0);
        ToolPolicy policy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(16)
            .build();

        int concurrency = 8;
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<ToolExecutionOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    ToolSession session = ToolSession.builder()
                        .scopeKey("account-jdbc-concurrent")
                        .maxAccessLevel(AccessLevel.MUTATING)
                        .executionStore(store)
                        .registerTool(policy, args -> {
                            executions.incrementAndGet();
                            try {
                                Thread.sleep(80);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return ToolExecutionOutcome.completed(Map.of("movementId", 555L));
                        })
                        .build();
                    barrier.await(10, TimeUnit.SECONDS);
                    return session.execute("retry_disposal", Map.of("disposalId", "disp-lock"), "req-lock-1");
                }));
            }

            int freshCount = 0;
            int replayedCount = 0;
            for (Future<ToolExecutionOutcome> future : futures) {
                ToolExecutionOutcome outcome = future.get(20, TimeUnit.SECONDS);
                assertEquals(ToolExecutionStatus.COMPLETED, outcome.status());
                assertEquals(555L, outcome.facts().get("movementId"));
                if (outcome.replayed()) {
                    replayedCount++;
                } else {
                    freshCount++;
                }
            }
            assertEquals(1, executions.get());
            assertEquals(1, freshCount);
            assertEquals(concurrency - 1, replayedCount);
        }
    }

    @Test
    void persistsAndReplaysRejectedOutcomeWithoutReInvokingDownstream() {
        JdbcToolExecutionStore store = new JdbcToolExecutionStore(dataSource);
        AtomicInteger calls = new AtomicInteger(0);
        ToolPolicy policy = ToolPolicy.builder("dispose_inventory")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .build();

        ToolSession session = ToolSession.builder()
            .scopeKey("account-jdbc-reject")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store)
            .registerTool(policy, args -> {
                calls.incrementAndGet();
                return ToolExecutionOutcome.rejected("INSUFFICIENT_STOCK", "Not enough stock", Map.of("available", 2L));
            })
            .build();

        ToolExecutionOutcome first = session.execute("dispose_inventory", Map.of("qty", 5L), "req-rej-1");
        assertEquals(ToolExecutionStatus.REJECTED, first.status());
        assertEquals("INSUFFICIENT_STOCK", first.rejectionCode());
        assertFalse(first.replayed());

        ToolExecutionOutcome second = session.execute("dispose_inventory", Map.of("qty", 5L), "req-rej-1");
        assertEquals(ToolExecutionStatus.REJECTED, second.status());
        assertEquals("INSUFFICIENT_STOCK", second.rejectionCode());
        assertEquals(2L, second.facts().get("available"));
        assertTrue(second.replayed());
        assertEquals(1, calls.get());
    }

    @Test
    void autoConfiguresJdbcToolExecutionStoreWhenPropertyIsSet() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolGuardAutoConfiguration.class))
            .withBean(DataSource.class, JdbcToolExecutionStoreIntegrationTest::newDataSource)
            .withPropertyValues(
                "toolguard.store.type=jdbc",
                "toolguard.store.jdbc.table-name=toolguard_auto_exec",
                "toolguard.store.jdbc.initialize-schema=true"
            )
            .run(context -> {
                ToolExecutionStore store = context.getBean(ToolExecutionStore.class);
                assertInstanceOf(JdbcToolExecutionStore.class, store);
                ToolSessionFactory factory = context.getBean(ToolSessionFactory.class);
                ToolSession session = factory.newSession("auto-jdbc-scope")
                    .maxAccessLevel(AccessLevel.MUTATING)
                    .registerTool(
                        ToolPolicy.builder("mutate_item").accessLevel(AccessLevel.MUTATING).build(),
                        args -> ToolExecutionOutcome.completed(Map.of("ok", true))
                    )
                    .build();
                ToolExecutionOutcome first = session.execute("mutate_item", Map.of("id", 1L), "req-auto-1");
                ToolExecutionOutcome second = session.execute("mutate_item", Map.of("id", 1L), "req-auto-1");
                assertFalse(first.replayed());
                assertTrue(second.replayed());
            });
    }
}
