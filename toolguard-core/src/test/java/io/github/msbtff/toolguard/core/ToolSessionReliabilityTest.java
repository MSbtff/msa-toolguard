package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ToolSessionReliabilityTest {

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration delta) {
            current.updateAndGet(t -> t.plus(delta));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    @Test
    void replayedExecutionInFreshSessionSyncsEvidenceLedgerSatisfiesRequiresAndPassesVerifier() {
        InMemoryToolExecutionStore sharedStore = new InMemoryToolExecutionStore();
        ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(3)
            .build();
        ToolPolicy followUpPolicy = ToolPolicy.builder("verify_settlement")
            .accessLevel(AccessLevel.READ_ONLY)
            .requires("retry_disposal")
            .maxCalls(2)
            .build();

        ToolSession session1 = ToolSession.builder()
            .scopeKey("acct-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(sharedStore)
            .registerTool(retryPolicy, args -> ToolExecutionOutcome.completed(Map.of("movementId", 9001L)))
            .build();

        ToolExecutionOutcome initial = session1.execute("retry_disposal", Map.of("disposalId", "d-1"), "req-1");
        assertEquals("E1", initial.evidenceId());
        assertFalse(initial.replayed());

        ToolSession session2 = ToolSession.builder()
            .scopeKey("acct-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(sharedStore)
            .registerTool(retryPolicy, args -> {
                throw new AssertionError("Downstream handler must not run on replay");
            })
            .registerTool(followUpPolicy, args -> ToolExecutionOutcome.completed(Map.of("settled", true)))
            .build();

        ToolExecutionOutcome replayed = session2.execute("retry_disposal", Map.of("disposalId", "d-1"), "req-1");
        assertTrue(replayed.replayed());
        assertEquals("E1", replayed.evidenceId());
        assertTrue(session2.evidenceLedger().hasCompletedTool("retry_disposal"));
        assertTrue(session2.evidenceLedger().find("E1").isPresent());

        ToolExecutionOutcome followUp = session2.execute("verify_settlement", Map.of());
        assertEquals("E2", followUp.evidenceId());

        OutputVerifier verifier = OutputVerifier.builder()
            .requireToolExecuted("retry_disposal")
            .requireToolExecuted("verify_settlement")
            .build();
        VerificationResult verified = verifier.verify(
            new AgentOutput("settled", List.of("E1", "E2"), Map.of()),
            session2.evidenceLedger()
        );
        assertTrue(verified.verified());
    }

    @Test
    void replayedExecutionAllocatesFreshEvidenceIdWhenOccupiedAndDeduplicatesRepeatedReplays() {
        InMemoryToolExecutionStore sharedStore = new InMemoryToolExecutionStore();
        ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .build();
        ToolPolicy readPolicy = ToolPolicy.builder("get_disposal")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(2)
            .build();

        ToolSession session1 = ToolSession.builder()
            .scopeKey("acct-2")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(sharedStore)
            .registerTool(retryPolicy, args -> ToolExecutionOutcome.completed(Map.of("movementId", 77L)))
            .build();
        assertEquals("E1", session1.execute("retry_disposal", Map.of("id", 1L), "req-2").evidenceId());

        ToolSession session2 = ToolSession.builder()
            .scopeKey("acct-2")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(sharedStore)
            .registerTool(readPolicy, args -> ToolExecutionOutcome.completed(Map.of("status", "PENDING")))
            .registerTool(retryPolicy, args -> {
                throw new AssertionError("Should replay from store");
            })
            .build();

        ToolExecutionOutcome readOutcome = session2.execute("get_disposal", Map.of());
        assertEquals("E1", readOutcome.evidenceId());

        ToolExecutionOutcome firstReplay = session2.execute("retry_disposal", Map.of("id", 1L), "req-2");
        assertTrue(firstReplay.replayed());
        assertEquals("E2", firstReplay.evidenceId());
        assertEquals("get_disposal", session2.evidenceLedger().find("E1").orElseThrow().toolName());
        assertEquals("retry_disposal", session2.evidenceLedger().find("E2").orElseThrow().toolName());

        ToolExecutionOutcome secondReplay = session2.execute("retry_disposal", Map.of("id", 1L), "req-2");
        assertTrue(secondReplay.replayed());
        assertEquals("E2", secondReplay.evidenceId());
        assertEquals(List.of("E1", "E2"), session2.evidenceLedger().allIds());
    }

    @Test
    void idempotencyConflictDoesNotLeakCallBudget() {
        AtomicInteger calls = new AtomicInteger(0);
        ToolSession session = ToolSession.builder()
            .scopeKey("acct-budget")
            .maxAccessLevel(AccessLevel.MUTATING)
            .maxTotalCalls(2)
            .registerTool(
                ToolPolicy.builder("retry_disposal").accessLevel(AccessLevel.MUTATING).maxCalls(2).build(),
                args -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new UncertainExecutionException("lost response");
                    }
                    return ToolExecutionOutcome.completed(Map.of("ok", true));
                }
            )
            .build();

        assertEquals(ToolExecutionStatus.PENDING, session.execute("retry_disposal", Map.of("qty", 3L), "req-b").status());
        assertEquals(1, session.totalCallCount());

        GuardrailException conflict = assertThrows(
            GuardrailException.class,
            () -> session.execute("retry_disposal", Map.of("qty", 99L), "req-b")
        );
        assertEquals(ViolationCode.IDEMPOTENCY_CONFLICT, conflict.violation().code());
        assertEquals(1, session.totalCallCount());

        ToolExecutionOutcome completed = session.execute("retry_disposal", Map.of("qty", 3L), "req-b");
        assertEquals(ToolExecutionStatus.COMPLETED, completed.status());
        assertEquals(2, session.totalCallCount());
    }

    @Test
    void unhandledHandlerExceptionEmitsAuditEventAndRethrowsOriginalError() {
        AuditTrailListener audit = new AuditTrailListener();
        ToolSession session = ToolSession.builder()
            .sessionId("sess-err-1")
            .addListener(audit)
            .registerTool(
                ToolPolicy.builder("failing_tool").maxCalls(2).build(),
                args -> {
                    throw new IllegalStateException("downstream connection reset");
                }
            )
            .build();

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> session.execute("failing_tool", Map.of())
        );
        assertEquals("downstream connection reset", thrown.getMessage());

        assertEquals(1, audit.entries().size());
        ToolExecutionEvent event = audit.entries().getFirst().event();
        assertTrue(event.allowed());
        assertNull(event.violationCode());
        assertNull(event.status());
        assertFalse(event.replayed());
        assertEquals("failing_tool", event.toolName());

        Map<String, Object> parsedJson = CanonicalJson.parseObject(audit.toJsonLines().getFirst());
        assertEquals(true, parsedJson.get("allowed"));
        assertNull(parsedJson.get("status"));
        assertNull(parsedJson.get("violationCode"));
    }

    @Test
    void inMemoryStorePreventsDelimiterCollisionsAndEnforcesMaxEntriesAndTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
        InMemoryToolExecutionStore store = new InMemoryToolExecutionStore(2, Duration.ofMinutes(10), clock);
        AtomicInteger executions = new AtomicInteger(0);

        ToolExecutionOutcome o1 = store.executeIdempotent(
            "a::b", "c", "r1", "digest-1",
            () -> ToolExecutionOutcome.completed(Map.of("n", executions.incrementAndGet()))
        );
        ToolExecutionOutcome o2 = store.executeIdempotent(
            "a", "b::c", "r1", "digest-2",
            () -> ToolExecutionOutcome.completed(Map.of("n", executions.incrementAndGet()))
        );
        assertFalse(o1.replayed());
        assertFalse(o2.replayed());
        assertEquals(2, executions.get());
        assertEquals(2, store.size());

        store.executeIdempotent(
            "scope-3", "tool", "r3", "digest-3",
            () -> ToolExecutionOutcome.completed(Map.of("n", executions.incrementAndGet()))
        );
        assertEquals(2, store.size());

        clock.advance(Duration.ofMinutes(11));
        assertEquals(0, store.size());
        ToolExecutionOutcome afterTtl = store.executeIdempotent(
            "scope-3", "tool", "r3", "digest-3",
            () -> ToolExecutionOutcome.completed(Map.of("n", executions.incrementAndGet()))
        );
        assertFalse(afterTtl.replayed());
        assertEquals(4, executions.get());
    }

    @Test
    void storeSynthesizedPendingOutcomeWithoutEvidenceIdReceivesEvidenceRecord() {
        ToolExecutionStore timeoutStore = (scope, tool, reqId, digest, action) ->
            ToolExecutionOutcome.pending(reqId, "Timed out waiting for distributed execution lock");

        ToolSession session = ToolSession.builder()
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(timeoutStore)
            .registerTool(
                ToolPolicy.builder("retry_disposal").accessLevel(AccessLevel.MUTATING).build(),
                args -> ToolExecutionOutcome.completed(Map.of())
            )
            .build();

        ToolExecutionOutcome outcome = session.execute("retry_disposal", Map.of(), "req-timeout");
        assertEquals(ToolExecutionStatus.PENDING, outcome.status());
        assertNotNull(outcome.evidenceId());
        assertEquals("E1", outcome.evidenceId());
        assertEquals(1, session.evidenceLedger().all().size());
        assertEquals(ToolExecutionStatus.PENDING, session.evidenceLedger().find("E1").orElseThrow().status());
    }
}
