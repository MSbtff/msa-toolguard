package io.github.msbtff.toolguard.spring.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Duration;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
class RedisToolExecutionStoreIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
        .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUpRedis() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
            REDIS.getHost(),
            REDIS.getMappedPort(6379)
        );
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void recoversPendingAfterRestartAndEnforcesIdempotencyConflictAcrossStoreInstances() {
        AtomicInteger calls = new AtomicInteger(0);
        ToolPolicy policy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .build();

        RedisToolExecutionStore store1 = new RedisToolExecutionStore(redisTemplate);
        ToolSession session1 = ToolSession.builder()
            .scopeKey("account-redis-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store1)
            .registerTool(policy, args -> {
                calls.incrementAndGet();
                throw new UncertainExecutionException("Downstream socket timeout");
            })
            .build();

        ToolExecutionOutcome pending = session1.execute("retry_disposal", Map.of("disposalId", "disp-r1"), "req-r1");
        assertEquals(ToolExecutionStatus.PENDING, pending.status());
        assertFalse(pending.replayed());
        assertEquals(ToolExecutionStatus.PENDING,
            store1.find("account-redis-1", "retry_disposal", "req-r1").orElseThrow().status());

        RedisToolExecutionStore store2 = new RedisToolExecutionStore(redisTemplate);
        ToolSession session2 = ToolSession.builder()
            .scopeKey("account-redis-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store2)
            .registerTool(policy, args -> {
                calls.incrementAndGet();
                return ToolExecutionOutcome.completed(Map.of("movementId", 4401L, "quantityAfter", 12L));
            })
            .build();

        GuardrailException conflict = assertThrows(
            GuardrailException.class,
            () -> session2.execute("retry_disposal", Map.of("disposalId", "disp-different"), "req-r1")
        );
        assertEquals(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.violation().code());

        ToolExecutionOutcome completed = session2.execute("retry_disposal", Map.of("disposalId", "disp-r1"), "req-r1");
        assertEquals(ToolExecutionStatus.COMPLETED, completed.status());
        assertFalse(completed.replayed());
        assertEquals(4401L, completed.facts().get("movementId"));

        RedisToolExecutionStore store3 = new RedisToolExecutionStore(redisTemplate);
        ToolSession session3 = ToolSession.builder()
            .scopeKey("account-redis-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store3)
            .registerTool(policy, args -> {
                throw new AssertionError("Downstream must not be invoked on Redis replay");
            })
            .registerTool(
                ToolPolicy.builder("confirm_redis_settlement").requires("retry_disposal").maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of("ok", true))
            )
            .build();

        ToolExecutionOutcome replayed = session3.execute("retry_disposal", Map.of("disposalId", "disp-r1"), "req-r1");
        assertEquals(ToolExecutionStatus.COMPLETED, replayed.status());
        assertTrue(replayed.replayed());
        assertEquals(completed.evidenceId(), replayed.evidenceId());
        assertEquals(4401L, replayed.facts().get("movementId"));
        assertEquals(2, calls.get());
        assertTrue(session3.evidenceLedger().hasCompletedTool("retry_disposal"));

        ToolExecutionOutcome confirmed = session3.execute("confirm_redis_settlement", Map.of());
        VerificationResult verified = OutputVerifier.builder()
            .requireToolExecuted("retry_disposal")
            .requireToolExecuted("confirm_redis_settlement")
            .build()
            .verify(new AgentOutput("confirmed", List.of(replayed.evidenceId(), confirmed.evidenceId()), Map.of()), session3.evidenceLedger());
        assertTrue(verified.verified());
    }

    @Test
    void fencedLuaWritePreventsExpiredLockHolderFromOverwritingNewerCommittedState() throws Exception {
        RedisToolExecutionStore store = new RedisToolExecutionStore(
            redisTemplate,
            "toolguard:fence:",
            Duration.ofMillis(200),
            Duration.ofSeconds(3),
            Duration.ofMinutes(5)
        );
        CountDownLatch worker1Started = new CountDownLatch(1);
        CountDownLatch worker2Committed = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ToolExecutionOutcome> staleWorker = pool.submit(() -> store.executeIdempotent(
                "acct-fence",
                "retry_disposal",
                "req-fence-1",
                "digest-same",
                () -> {
                    worker1Started.countDown();
                    try {
                        assertTrue(worker2Committed.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolExecutionOutcome.completed(Map.of("winner", "stale-worker-1"));
                }
            ));

            assertTrue(worker1Started.await(5, TimeUnit.SECONDS));
            Thread.sleep(280);

            ToolExecutionOutcome freshOutcome = store.executeIdempotent(
                "acct-fence",
                "retry_disposal",
                "req-fence-1",
                "digest-same",
                () -> ToolExecutionOutcome.completed(Map.of("winner", "fresh-worker-2"))
            );
            worker2Committed.countDown();

            assertEquals(ToolExecutionStatus.COMPLETED, freshOutcome.status());
            assertEquals("fresh-worker-2", freshOutcome.facts().get("winner"));

            ToolExecutionOutcome staleOutcome = staleWorker.get(5, TimeUnit.SECONDS);
            assertEquals(ToolExecutionStatus.PENDING, staleOutcome.status());
            assertTrue(staleOutcome.message().contains("lock expired"));

            ToolExecutionOutcome persisted = store.find("acct-fence", "retry_disposal", "req-fence-1").orElseThrow();
            assertEquals(ToolExecutionStatus.COMPLETED, persisted.status());
            assertEquals("fresh-worker-2", persisted.facts().get("winner"));
        }
    }

    @Test
    void serializesConcurrentRetriesWithDistributedRedisLock() throws Exception {
        RedisToolExecutionStore store = new RedisToolExecutionStore(redisTemplate);
        AtomicInteger executions = new AtomicInteger(0);
        ToolPolicy policy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(16)
            .build();

        int concurrency = 16;
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<ToolExecutionOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                futures.add(pool.submit(() -> {
                    ToolSession session = ToolSession.builder()
                        .scopeKey("account-redis-concurrent")
                        .maxAccessLevel(AccessLevel.MUTATING)
                        .executionStore(store)
                        .registerTool(policy, args -> {
                            executions.incrementAndGet();
                            try {
                                Thread.sleep(80);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return ToolExecutionOutcome.completed(Map.of("movementId", 999L));
                        })
                        .build();
                    barrier.await(10, TimeUnit.SECONDS);
                    return session.execute("retry_disposal", Map.of("disposalId", "disp-rlock"), "req-rlock-1");
                }));
            }

            int freshCount = 0;
            int replayedCount = 0;
            for (Future<ToolExecutionOutcome> future : futures) {
                ToolExecutionOutcome outcome = future.get(20, TimeUnit.SECONDS);
                assertEquals(ToolExecutionStatus.COMPLETED, outcome.status());
                assertEquals(999L, outcome.facts().get("movementId"));
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
    void autoConfiguresRedisToolExecutionStoreWhenPropertyIsSet() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolGuardAutoConfiguration.class))
            .withBean(StringRedisTemplate.class, () -> redisTemplate)
            .withPropertyValues(
                "toolguard.store.type=redis",
                "toolguard.store.redis.key-prefix=toolguard:auto:"
            )
            .run(context -> {
                ToolExecutionStore store = context.getBean(ToolExecutionStore.class);
                assertInstanceOf(RedisToolExecutionStore.class, store);
                ToolSessionFactory factory = context.getBean(ToolSessionFactory.class);
                ToolSession session = factory.newSession("auto-redis-scope")
                    .maxAccessLevel(AccessLevel.MUTATING)
                    .registerTool(
                        ToolPolicy.builder("mutate_stock").accessLevel(AccessLevel.MUTATING).build(),
                        args -> ToolExecutionOutcome.completed(Map.of("ok", true))
                    )
                    .build();
                ToolExecutionOutcome first = session.execute("mutate_stock", Map.of("id", 7L), "req-rauto-1");
                ToolExecutionOutcome second = session.execute("mutate_stock", Map.of("id", 7L), "req-rauto-1");
                assertFalse(first.replayed());
                assertTrue(second.replayed());
            });
    }
}
