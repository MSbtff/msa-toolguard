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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class H2JdbcToolExecutionStoreUnitTest {

    private static HikariDataSource newH2DataSource(int poolSize, int lockTimeoutMs) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(
            "jdbc:h2:mem:tg_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=" + lockTimeoutMs
        );
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(poolSize);
        return new HikariDataSource(config);
    }

    @Test
    void verifiesH2SchemaIndexRecoveryReplayLedgerSyncAndPurge() throws Exception {
        Instant t0 = Instant.parse("2026-11-26T00:00:00Z");
        try (HikariDataSource ds = newH2DataSource(8, 5000)) {
            JdbcToolExecutionStore store = new JdbcToolExecutionStore(
                ds,
                "toolguard_h2_exec",
                Clock.fixed(t0, ZoneOffset.UTC),
                true,
                Duration.ofSeconds(5)
            );
            // Re-running initializeSchema is idempotent
            store.initializeSchema();

            try (var conn = ds.getConnection();
                 var rs = conn.getMetaData().getIndexInfo(null, null, "TOOLGUARD_H2_EXEC", false, false)) {
                boolean foundIndex = false;
                while (rs.next()) {
                    if ("IDX_TOOLGUARD_H2_EXEC_STATUS_UPDATED".equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        foundIndex = true;
                        break;
                    }
                }
                assertTrue(foundIndex, "Expected purge index to exist on H2");
            }

            ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
                .accessLevel(AccessLevel.MUTATING)
                .maxCalls(5)
                .build();

            AtomicInteger calls = new AtomicInteger(0);
            ToolSession s1 = ToolSession.builder()
                .scopeKey("acct-h2")
                .maxAccessLevel(AccessLevel.MUTATING)
                .executionStore(store)
                .registerTool(retryPolicy, args -> {
                    calls.incrementAndGet();
                    throw new UncertainExecutionException("lost response");
                })
                .build();
            assertEquals(ToolExecutionStatus.PENDING, s1.execute("retry_disposal", Map.of("qty", 3L), "req-h2-1").status());

            ToolSession s2 = ToolSession.builder()
                .scopeKey("acct-h2")
                .maxAccessLevel(AccessLevel.MUTATING)
                .executionStore(store)
                .registerTool(retryPolicy, args -> {
                    calls.incrementAndGet();
                    return ToolExecutionOutcome.completed(Map.of("movementId", 7701L));
                })
                .build();
            GuardrailException conflict = assertThrows(
                GuardrailException.class,
                () -> s2.execute("retry_disposal", Map.of("qty", 99L), "req-h2-1")
            );
            assertEquals(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.violation().code());

            ToolExecutionOutcome completed = s2.execute("retry_disposal", Map.of("qty", 3L), "req-h2-1");
            assertEquals(ToolExecutionStatus.COMPLETED, completed.status());
            assertFalse(completed.replayed());

            // Fresh session replays, syncs EvidenceLedger, satisfies requires + OutputVerifier
            ToolSession s3 = ToolSession.builder()
                .scopeKey("acct-h2")
                .maxAccessLevel(AccessLevel.MUTATING)
                .executionStore(store)
                .registerTool(retryPolicy, args -> {
                    throw new AssertionError("must not run on H2 replay");
                })
                .registerTool(
                    ToolPolicy.builder("verify_h2").requires("retry_disposal").maxCalls(1).build(),
                    args -> ToolExecutionOutcome.completed(Map.of("ok", true))
                )
                .build();
            ToolExecutionOutcome replayed = s3.execute("retry_disposal", Map.of("qty", 3L), "req-h2-1");
            assertTrue(replayed.replayed());
            ToolExecutionOutcome verifiedStep = s3.execute("verify_h2", Map.of());
            VerificationResult vr = OutputVerifier.builder()
                .requireToolExecuted("retry_disposal")
                .requireToolExecuted("verify_h2")
                .build()
                .verify(new AgentOutput("ok", List.of(replayed.evidenceId(), verifiedStep.evidenceId()), Map.of()), s3.evidenceLedger());
            assertTrue(vr.verified());

            // Keep one PENDING row and confirm purgeTerminalBefore deletes only COMPLETED row
            store.executeIdempotent(
                "acct-h2", "retry_disposal", "req-h2-pending", "digest-p",
                () -> ToolExecutionOutcome.pending("req-h2-pending", "waiting")
            );
            assertEquals(1, store.purgeTerminalBefore(t0.plus(Duration.ofHours(1))));
            assertTrue(store.find("acct-h2", "retry_disposal", "req-h2-1").isEmpty());
            assertEquals(ToolExecutionStatus.PENDING, store.find("acct-h2", "retry_disposal", "req-h2-pending").orElseThrow().status());
        }
    }

    @Test
    void serializes32ConcurrentThreadsOnH2WithZeroDuplicateMutations() throws Exception {
        int concurrency = 32;
        try (HikariDataSource ds = newH2DataSource(concurrency + 4, 15_000)) {
            JdbcToolExecutionStore store = new JdbcToolExecutionStore(ds);
            AtomicInteger executions = new AtomicInteger(0);
            ToolPolicy policy = ToolPolicy.builder("retry_disposal")
                .accessLevel(AccessLevel.MUTATING)
                .maxCalls(concurrency)
                .build();

            CyclicBarrier barrier = new CyclicBarrier(concurrency);
            try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
                List<Future<ToolExecutionOutcome>> futures = new ArrayList<>();
                for (int i = 0; i < concurrency; i++) {
                    futures.add(pool.submit(() -> {
                        ToolSession session = ToolSession.builder()
                            .scopeKey("acct-h2-32")
                            .maxAccessLevel(AccessLevel.MUTATING)
                            .executionStore(store)
                            .registerTool(policy, args -> {
                                executions.incrementAndGet();
                                try {
                                    Thread.sleep(60);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return ToolExecutionOutcome.completed(Map.of("movementId", 3232L));
                            })
                            .build();
                        barrier.await(10, TimeUnit.SECONDS);
                        return session.execute("retry_disposal", Map.of("id", 32L), "req-h2-32");
                    }));
                }
                int fresh = 0;
                int replayed = 0;
                for (Future<ToolExecutionOutcome> f : futures) {
                    ToolExecutionOutcome outcome = f.get(20, TimeUnit.SECONDS);
                    assertEquals(ToolExecutionStatus.COMPLETED, outcome.status());
                    assertEquals(3232L, outcome.facts().get("movementId"));
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
    }

    @Test
    void returnsPendingWhenH2RowLockTimesOut() throws Exception {
        try (HikariDataSource ds = newH2DataSource(4, 300)) {
            JdbcToolExecutionStore store = new JdbcToolExecutionStore(
                ds,
                "toolguard_h2_lock",
                Clock.systemUTC(),
                true,
                Duration.ofSeconds(1)
            );
            CountDownLatch holderEntered = new CountDownLatch(1);
            CountDownLatch releaseHolder = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<ToolExecutionOutcome> holder = pool.submit(() -> store.executeIdempotent(
                    "acct-lock", "retry_disposal", "req-lock-to", "same-digest",
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
                    "acct-lock", "retry_disposal", "req-lock-to", "same-digest",
                    () -> ToolExecutionOutcome.completed(Map.of("shouldNotRun", true))
                );
                assertEquals(ToolExecutionStatus.PENDING, waiterOutcome.status());
                assertTrue(waiterOutcome.message().contains("Timed out waiting for JDBC execution lock"));

                releaseHolder.countDown();
                assertEquals(ToolExecutionStatus.COMPLETED, holder.get(5, TimeUnit.SECONDS).status());
            }
        }
    }
}
