package io.github.goldenpage.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class IdempotentExecutionTest {

    @Test
    void keepsPendingOnUncertainFailureAndRetriesSafelyWithoutDuplicateMutation() {
        AtomicInteger attempts = new AtomicInteger(0);
        ToolExecutionStore store = new InMemoryToolExecutionStore();

        ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .build();

        ToolSession session = ToolSession.builder()
            .scopeKey("account-7")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store)
            .registerTool(retryPolicy, args -> {
                int call = attempts.incrementAndGet();
                if (call == 1) {
                    throw new UncertainExecutionException("Lost response after downstream stock call");
                }
                return ToolExecutionOutcome.completed(Map.of("movementId", 9001L, "quantityAfter", 17));
            })
            .build();

        // Missing requestId is blocked
        GuardrailException missingKey = assertThrows(
            GuardrailException.class,
            () -> session.execute("retry_disposal", Map.of("disposalId", "disp-1"))
        );
        assertEquals(ViolationCode.MISSING_IDEMPOTENCY_KEY, missingKey.violation().code());

        // First attempt loses response -> stays PENDING (never fabricated as COMPLETED)
        ToolExecutionOutcome first = session.execute("retry_disposal", Map.of("disposalId", "disp-1"), "req-disp-1");
        assertEquals(ToolExecutionStatus.PENDING, first.status());
        assertFalse(first.replayed());
        assertEquals("E1", first.evidenceId());

        // Different payload with same requestId -> IDEMPOTENCY_CONFLICT
        GuardrailException conflict = assertThrows(
            GuardrailException.class,
            () -> session.execute("retry_disposal", Map.of("disposalId", "disp-2"), "req-disp-1")
        );
        assertEquals(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.violation().code());

        // Safe retry with identical requestId and arguments -> completes once
        ToolExecutionOutcome second = session.execute("retry_disposal", Map.of("disposalId", "disp-1"), "req-disp-1");
        assertEquals(ToolExecutionStatus.COMPLETED, second.status());
        assertFalse(second.replayed());
        assertEquals(9001L, second.facts().get("movementId"));

        // Further retry replays stored COMPLETED result without invoking downstream handler again
        ToolExecutionOutcome third = session.execute("retry_disposal", Map.of("disposalId", "disp-1"), "req-disp-1");
        assertEquals(ToolExecutionStatus.COMPLETED, third.status());
        assertTrue(third.replayed());
        assertEquals(second.evidenceId(), third.evidenceId());
        assertEquals(2, attempts.get());
    }

    @Test
    void serializesConcurrentRetriesForSameRequestId() throws Exception {
        AtomicInteger executions = new AtomicInteger(0);
        ToolExecutionStore store = new InMemoryToolExecutionStore();
        ToolPolicy policy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .build();

        ToolSession session = ToolSession.builder()
            .scopeKey("account-9")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store)
            .registerTool(policy, args -> {
                executions.incrementAndGet();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolExecutionOutcome.completed(Map.of("movementId", 42L));
            })
            .build();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<ToolExecutionOutcome> task = () ->
                session.execute("retry_disposal", Map.of("disposalId", "disp-concurrent"), "req-same");
            Future<ToolExecutionOutcome> f1 = pool.submit(task);
            Future<ToolExecutionOutcome> f2 = pool.submit(task);

            ToolExecutionOutcome o1 = f1.get();
            ToolExecutionOutcome o2 = f2.get();

            assertEquals(1, executions.get());
            assertEquals(ToolExecutionStatus.COMPLETED, o1.status());
            assertEquals(ToolExecutionStatus.COMPLETED, o2.status());
            assertTrue(o1.replayed() ^ o2.replayed());
            assertEquals(o1.evidenceId(), o2.evidenceId());
        }
    }
}
