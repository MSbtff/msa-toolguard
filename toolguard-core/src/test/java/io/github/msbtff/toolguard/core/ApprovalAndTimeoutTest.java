package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ApprovalAndTimeoutTest {

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

    private static Path locateContractsDir() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate contracts/ directory");
    }

    @Test
    void enforcesFailClosedApprovalPayloadDigestBindingSingleUseAndIdempotentRetry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T00:00:00Z"));
        InMemoryApprovalGate gate = new InMemoryApprovalGate(clock);
        AtomicInteger calls = new AtomicInteger(0);

        ToolPolicy refundPolicy = ToolPolicy.builder("refund_payment")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .boundParameters("accountId")
            .requireApproval(true)
            .build();

        ToolSession failClosed = ToolSession.builder()
            .scopeKey("acct-7")
            .maxAccessLevel(AccessLevel.MUTATING)
            .bindParameter("accountId", 7L)
            .registerTool(refundPolicy, args -> ToolExecutionOutcome.completed(Map.of("refunded", true)))
            .build();
        GuardrailException deniedDefault = assertThrows(
            GuardrailException.class,
            () -> failClosed.execute("refund_payment", Map.of("amount", 500L), "req-0")
        );
        assertEquals(ViolationCode.APPROVAL_REQUIRED, deniedDefault.violation().code());
        assertTrue(deniedDefault.violation().repairHint().contains("payloadDigest="));
        assertEquals(0, failClosed.totalCallCount());

        ToolSession session = ToolSession.builder()
            .scopeKey("acct-7")
            .maxAccessLevel(AccessLevel.MUTATING)
            .bindParameter("accountId", 7L)
            .approvalGate(gate)
            .clock(clock)
            .registerTool(refundPolicy, args -> {
                if (calls.incrementAndGet() == 1) {
                    throw new UncertainExecutionException("downstream gateway timeout");
                }
                return ToolExecutionOutcome.completed(Map.of("amount", args.get("amount"), "refunded", true));
            })
            .build();

        String approvalId = gate.grant(
            "acct-7",
            "refund_payment",
            Map.of("accountId", 7L, "amount", 500L),
            "ops-lead@company.com",
            Duration.ofMinutes(5)
        );
        assertTrue(approvalId.startsWith("apr-"));

        GuardrailException tampered = assertThrows(
            GuardrailException.class,
            () -> session.execute("refund_payment", Map.of("amount", 99999L), "req-1")
        );
        assertEquals(ViolationCode.APPROVAL_REQUIRED, tampered.violation().code());
        assertEquals(0, calls.get());

        ToolExecutionOutcome pending = session.execute("refund_payment", Map.of("amount", 500L), "req-1");
        assertEquals(ToolExecutionStatus.PENDING, pending.status());
        assertEquals(1, calls.get());

        ToolExecutionOutcome completed = session.execute("refund_payment", Map.of("amount", 500L), "req-1");
        assertEquals(ToolExecutionStatus.COMPLETED, completed.status());
        assertEquals(2, calls.get());

        GuardrailException reused = assertThrows(
            GuardrailException.class,
            () -> session.execute("refund_payment", Map.of("amount", 500L), "req-2")
        );
        assertEquals(ViolationCode.APPROVAL_REQUIRED, reused.violation().code());
        assertEquals(2, calls.get());

        String expiredGrant = gate.grant(
            "acct-7", "refund_payment", Map.of("accountId", 7L, "amount", 300L), "ops", Duration.ofSeconds(10)
        );
        clock.advance(Duration.ofSeconds(11));
        assertThrows(
            GuardrailException.class,
            () -> session.execute("refund_payment", Map.of("amount", 300L), "req-expired")
        );
        assertTrue(gate.revoke(expiredGrant));
        assertFalse(gate.revoke(expiredGrant));

        String revokedGrant = gate.grant(
            "acct-7", "refund_payment", Map.of("accountId", 7L, "amount", 200L), "ops", Duration.ofMinutes(5)
        );
        assertTrue(gate.revoke(revokedGrant));
        assertThrows(
            GuardrailException.class,
            () -> session.execute("refund_payment", Map.of("amount", 200L), "req-revoked")
        );
    }

    @Test
    void supportsConditionalApprovalPredicateAndPendingTicketHint() {
        ToolPolicy conditionalPolicy = ToolPolicy.builder("dispose_stock")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .requireApprovalWhen((tool, args) -> ((Number) args.getOrDefault("qty", 0)).longValue() >= 50)
            .build();

        ApprovalGate ticketGate = req -> ApprovalDecision.pendingApproval("ticket-42", "Needs manager review");
        ToolSession session = ToolSession.builder()
            .scopeKey("acct-cond")
            .maxAccessLevel(AccessLevel.MUTATING)
            .approvalGate(ticketGate)
            .registerTool(conditionalPolicy, args -> ToolExecutionOutcome.completed(Map.of("qty", args.get("qty"))))
            .build();

        ToolExecutionOutcome small = session.execute("dispose_stock", Map.of("qty", 10L), "req-small");
        assertEquals(ToolExecutionStatus.COMPLETED, small.status());

        GuardrailException blocked = assertThrows(
            GuardrailException.class,
            () -> session.execute("dispose_stock", Map.of("qty", 100L), "req-large")
        );
        assertEquals(ViolationCode.APPROVAL_REQUIRED, blocked.violation().code());
        assertEquals("Needs manager review", blocked.violation().message());
        assertTrue(blocked.violation().repairHint().contains("ticket-42"));
        assertTrue(blocked.violation().repairHint().contains("payloadDigest="));
    }

    @Test
    void enforcesVirtualThreadExecutionTimeoutAndConvertsHungCallsToPending() throws Exception {
        AtomicBoolean ranOnVirtualThread = new AtomicBoolean(false);
        CountDownLatch interruptedLatch = new CountDownLatch(1);
        InMemoryToolExecutionStore store = new InMemoryToolExecutionStore();

        ToolPolicy slowPolicy = ToolPolicy.builder("slow_downstream")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(5)
            .executionTimeout(Duration.ofMillis(80))
            .build();
        ToolPolicy fastPolicy = ToolPolicy.builder("fast_tool")
            .accessLevel(AccessLevel.READ_ONLY)
            .executionTimeout(Duration.ofSeconds(2))
            .build();
        ToolPolicy crashPolicy = ToolPolicy.builder("crash_timed_tool")
            .accessLevel(AccessLevel.READ_ONLY)
            .executionTimeout(Duration.ofSeconds(2))
            .build();

        AtomicInteger slowCalls = new AtomicInteger(0);
        ToolSession session = ToolSession.builder()
            .scopeKey("acct-timeout")
            .maxAccessLevel(AccessLevel.MUTATING)
            .executionStore(store)
            .registerTool(slowPolicy, args -> {
                ranOnVirtualThread.set(Thread.currentThread().isVirtual());
                if (slowCalls.incrementAndGet() == 1) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        interruptedLatch.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return ToolExecutionOutcome.completed(Map.of("late", true));
                }
                return ToolExecutionOutcome.completed(Map.of("recovered", true));
            })
            .registerTool(fastPolicy, args -> ToolExecutionOutcome.completed(Map.of("ok", true)))
            .registerTool(crashPolicy, args -> {
                throw new IllegalArgumentException("bad argument inside virtual thread");
            })
            .build();

        long startMs = System.currentTimeMillis();
        ToolExecutionOutcome timedOut = session.execute("slow_downstream", Map.of("id", 1L), "req-slow-1");
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertEquals(ToolExecutionStatus.PENDING, timedOut.status());
        assertFalse(timedOut.replayed());
        assertTrue(timedOut.message().contains("exceeded execution timeout"));
        assertEquals("E1", timedOut.evidenceId());
        assertTrue(ranOnVirtualThread.get(), "Timed tool should execute on a Java 21 virtual thread");
        assertTrue(interruptedLatch.await(2, TimeUnit.SECONDS), "Timed-out virtual thread should be interrupted");
        assertTrue(elapsedMs < 1000, "Timeout should return promptly without waiting for 2000ms sleep");

        ToolExecutionOutcome recovered = session.execute("slow_downstream", Map.of("id", 1L), "req-slow-1");
        assertEquals(ToolExecutionStatus.COMPLETED, recovered.status());
        assertEquals(true, recovered.facts().get("recovered"));

        assertEquals(ToolExecutionStatus.COMPLETED, session.execute("fast_tool", Map.of()).status());

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> session.execute("crash_timed_tool", Map.of())
        );
        assertEquals("bad argument inside virtual thread", thrown.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforcesSessionTimeoutAndAlignsNewPolicyAndAuditFieldsWithContracts() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T01:00:00Z"));
        AuditTrailListener audit = new AuditTrailListener(AuditLogFormatter.defaults(), clock, null);

        ToolPolicy policy = ToolPolicy.builder("high_risk_write")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(3)
            .requireApproval(true)
            .executionTimeout(Duration.ofMillis(2500))
            .build();

        Map<String, Object> policySchema = CanonicalJson.parseObject(
            Files.readString(locateContractsDir().resolve("tool-policy-v1.schema.json"))
        );
        Map<String, Object> policyProps = (Map<String, Object>) policySchema.get("properties");
        Map<String, Object> serializedPolicy = CanonicalJson.parseObject(policy.toContractJson());
        assertTrue(policyProps.keySet().containsAll(serializedPolicy.keySet()));
        assertEquals(true, serializedPolicy.get("requireApproval"));
        assertEquals(2500L, serializedPolicy.get("executionTimeoutMs"));
        assertEquals(policy, ToolPolicy.fromContractJson(policy.toContractJson()));

        ToolSession session = ToolSession.builder()
            .sessionId("sess-deadline-1")
            .maxAccessLevel(AccessLevel.MUTATING)
            .sessionTimeout(Duration.ofSeconds(5))
            .clock(clock)
            .addListener(audit)
            .registerTool(policy, args -> ToolExecutionOutcome.completed(Map.of()))
            .build();

        assertThrows(GuardrailException.class, () -> session.execute("high_risk_write", Map.of(), "req-1"));

        clock.advance(Duration.ofSeconds(6));
        GuardrailException expired = assertThrows(
            GuardrailException.class,
            () -> session.execute("high_risk_write", Map.of(), "req-1")
        );
        assertEquals(ViolationCode.SESSION_TIMEOUT, expired.violation().code());

        Map<String, Object> auditSchema = CanonicalJson.parseObject(
            Files.readString(locateContractsDir().resolve("audit-event-v1.schema.json"))
        );
        Map<String, Object> auditProps = (Map<String, Object>) auditSchema.get("properties");
        List<Object> violationEnum = (List<Object>) ((Map<String, Object>) auditProps.get("violationCode")).get("enum");

        List<String> lines = audit.toJsonLines();
        assertEquals(2, lines.size());
        Map<String, Object> approvalEvent = CanonicalJson.parseObject(lines.get(0));
        Map<String, Object> timeoutEvent = CanonicalJson.parseObject(lines.get(1));
        assertEquals("APPROVAL_REQUIRED", approvalEvent.get("violationCode"));
        assertEquals("SESSION_TIMEOUT", timeoutEvent.get("violationCode"));
        assertTrue(violationEnum.contains(approvalEvent.get("violationCode")));
        assertTrue(violationEnum.contains(timeoutEvent.get("violationCode")));

        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.builder("bad_t").executionTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ToolSession.builder().sessionTimeout(Duration.ofMillis(-1)));
    }
}
