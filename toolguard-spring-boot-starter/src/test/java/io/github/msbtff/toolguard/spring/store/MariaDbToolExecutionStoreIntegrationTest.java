package io.github.msbtff.toolguard.spring.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.msbtff.toolguard.core.ToolPolicy;
import io.github.msbtff.toolguard.core.ToolSession;
import io.github.msbtff.toolguard.core.UncertainExecutionException;
import io.github.msbtff.toolguard.core.VerificationResult;
import io.github.msbtff.toolguard.core.ViolationCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

@Tag("integration")
@Testcontainers
class MariaDbToolExecutionStoreIntegrationTest {

    @Container
    static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4")
        .withDatabaseName("toolguard_test")
        .withUsername("toolguard")
        .withPassword("toolguard")
        .withCommand("--innodb-lock-wait-timeout=1");

    static HikariDataSource dataSource;

    @BeforeAll
    static void setUpDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MARIADB.getJdbcUrl());
        config.setUsername(MARIADB.getUsername());
        config.setPassword(MARIADB.getPassword());
        config.setMaximumPoolSize(24);
        dataSource = new HikariDataSource(config);
    }

    @AfterAll
    static void tearDownDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void verifiesMariaDbSchemaIndexRecoveryReplayLedgerSyncAndPurge() throws Exception {
        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        JdbcToolExecutionStore store = new JdbcToolExecutionStore(
            dataSource,
            "toolguard_maria_exec",
            Clock.fixed(t0, ZoneOffset.UTC),
            true,
            Duration.ofSeconds(5)
        );
        store.initializeSchema();

        try (var conn = dataSource.getConnection();
             var rs = conn.getMetaData().getIndexInfo(null, null, "toolguard_maria_exec", false, false)) {
            boolean foundIndex = false;
            while (rs.next()) {
                if ("idx_toolguard_maria_exec_status_updated".equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    foundIndex = true;
                    break;
                }
            }
            assertTrue(foundIndex, "Expected purge index idx_toolguard_maria_exec_status_updated on MariaDB");
        }

        ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .build();
        AtomicInteger calls = new AtomicInteger(0);

        ToolSession s1 = ToolSession.builder()
            .scopeKey("acct-maria-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store)
            .registerTool(retryPolicy, args -> {
                calls.incrementAndGet();
                throw new UncertainExecutionException("lost response");
            })
            .build();
        assertEquals(ToolExecutionStatus.PENDING, s1.execute("retry_disposal", Map.of("qty", 4L), "req-m-1").status());

        JdbcToolExecutionStore store2 = new JdbcToolExecutionStore(
            dataSource, "toolguard_maria_exec", Clock.fixed(t0, ZoneOffset.UTC), false, Duration.ofSeconds(5)
        );
        ToolSession s2 = ToolSession.builder()
            .scopeKey("acct-maria-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store2)
            .registerTool(retryPolicy, args -> {
                calls.incrementAndGet();
                return ToolExecutionOutcome.completed(Map.of("movementId", 6601L));
            })
            .build();

        GuardrailException conflict = assertThrows(
            GuardrailException.class,
            () -> s2.execute("retry_disposal", Map.of("qty", 99L), "req-m-1")
        );
        assertEquals(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.violation().code());

        ToolExecutionOutcome completed = s2.execute("retry_disposal", Map.of("qty", 4L), "req-m-1");
        assertEquals(ToolExecutionStatus.COMPLETED, completed.status());
        assertFalse(completed.replayed());

        ToolSession s3 = ToolSession.builder()
            .scopeKey("acct-maria-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store2)
            .registerTool(retryPolicy, args -> {
                throw new AssertionError("Downstream must not run on MariaDB replay");
            })
            .registerTool(
                ToolPolicy.builder("confirm_maria").requires("retry_disposal").maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of("ok", true))
            )
            .build();

        ToolExecutionOutcome replayed = s3.execute("retry_disposal", Map.of("qty", 4L), "req-m-1");
        assertTrue(replayed.replayed());
        assertEquals(completed.evidenceId(), replayed.evidenceId());
        ToolExecutionOutcome confirmed = s3.execute("confirm_maria", Map.of());
        VerificationResult vr = OutputVerifier.builder()
            .requireToolExecuted("retry_disposal")
            .requireToolExecuted("confirm_maria")
            .build()
            .verify(new AgentOutput("ok", List.of(replayed.evidenceId(), confirmed.evidenceId()), Map.of()), s3.evidenceLedger());
        assertTrue(vr.verified());

        store.executeIdempotent(
            "acct-maria-1", "retry_disposal", "req-m-pending", "digest-mp",
            () -> ToolExecutionOutcome.pending("req-m-pending", "waiting")
        );
        assertEquals(1, store.purgeTerminalBefore(t0.plus(Duration.ofHours(1))));
        assertTrue(store.find("acct-maria-1", "retry_disposal", "req-m-1").isEmpty());
        assertEquals(ToolExecutionStatus.PENDING, store.find("acct-maria-1", "retry_disposal", "req-m-pending").orElseThrow().status());
    }

    @Test
    void serializes16ConcurrentRetriesOnMariaDbWithZeroDuplicates() throws Exception {
        JdbcToolExecutionStore store = new JdbcToolExecutionStore(dataSource);
        AtomicInteger executions = new AtomicInteger(0);
        ToolPolicy policy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(32)
            .build();

        int concurrency = 16;
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<ToolExecutionOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    ToolSession session = ToolSession.builder()
                        .scopeKey("acct-maria-16")
                        .maxAccessLevel(AccessLevel.MUTATING)
                        .executionStore(store)
                        .registerTool(policy, args -> {
                            executions.incrementAndGet();
                            try {
                                Thread.sleep(60);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return ToolExecutionOutcome.completed(Map.of("movementId", 1616L));
                        })
                        .build();
                    barrier.await(10, TimeUnit.SECONDS);
                    return session.execute("retry_disposal", Map.of("id", 16L), "req-maria-16");
                }));
            }
            int fresh = 0;
            int replayed = 0;
            for (Future<ToolExecutionOutcome> f : futures) {
                ToolExecutionOutcome outcome = f.get(30, TimeUnit.SECONDS);
                assertEquals(ToolExecutionStatus.COMPLETED, outcome.status());
                assertEquals(1616L, outcome.facts().get("movementId"));
                if (outcome.replayed()) {
                    replayed++;
                } else {
                    fresh++;
                }
            }
            assertEquals(1, executions.get());
            assertEquals(1, fresh);
            assertEquals(concurrency - 1, replayed);
        }
    }

    @Test
    void returnsPendingWhenMariaDbInnoDbLockWaitTimesOut() throws Exception {
        JdbcToolExecutionStore store = new JdbcToolExecutionStore(
            dataSource,
            "toolguard_maria_lock",
            Clock.systemUTC(),
            true,
            Duration.ofSeconds(2)
        );
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ToolExecutionOutcome> holder = pool.submit(() -> store.executeIdempotent(
                "acct-mlock", "retry_disposal", "req-mlock-1", "same-digest",
                () -> {
                    holderEntered.countDown();
                    try {
                        releaseHolder.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolExecutionOutcome.completed(Map.of("done", true));
                }
            ));

            assertTrue(holderEntered.await(5, TimeUnit.SECONDS));
            ToolExecutionOutcome waiterOutcome = store.executeIdempotent(
                "acct-mlock", "retry_disposal", "req-mlock-1", "same-digest",
                () -> ToolExecutionOutcome.completed(Map.of("shouldNotRun", true))
            );
            assertEquals(ToolExecutionStatus.PENDING, waiterOutcome.status());
            assertTrue(waiterOutcome.message().contains("Timed out waiting for JDBC execution lock"));

            releaseHolder.countDown();
            assertEquals(ToolExecutionStatus.COMPLETED, holder.get(5, TimeUnit.SECONDS).status());
        }
    }
}
