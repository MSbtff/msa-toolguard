package io.github.goldenpage.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ToolSessionPolicyTest {

    @Test
    void enforcesPrerequisiteScopeBindingDenyRulesAndBudgets() {
        AtomicReference<Map<String, Object>> capturedArgs = new AtomicReference<>();

        ToolPolicy detailPolicy = ToolPolicy.builder("get_disposal")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(1)
            .boundParameters("accountId", "disposalId")
            .build();

        ToolPolicy ledgerPolicy = ToolPolicy.builder("get_stock_movements")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(2)
            .requires("get_disposal")
            .boundParameters("accountId")
            .denyRule(DenyRules.integerRange("page", 0, 10))
            .denyRule(DenyRules.forbidKeys(Set.of("authorization")))
            .build();

        ToolPolicy retryPolicy = ToolPolicy.builder("retry_disposal")
            .accessLevel(AccessLevel.MUTATING)
            .maxCalls(1)
            .build();

        ToolSession session = ToolSession.builder()
            .maxAccessLevel(AccessLevel.READ_ONLY)
            .maxTotalCalls(3)
            .bindParameter("accountId", 7L)
            .bindParameter("disposalId", "disp-100")
            .registerTool(detailPolicy, args -> {
                capturedArgs.set(args);
                return ToolExecutionOutcome.completed(Map.of("status", "PENDING", "foodMaterialId", 11L));
            })
            .registerTool(ledgerPolicy, args -> ToolExecutionOutcome.completed(Map.of("page", args.get("page"))))
            .registerTool(retryPolicy, args -> ToolExecutionOutcome.completed(Map.of("status", "COMPLETED")))
            .build();

        // MUTATING tool blocked in READ_ONLY session
        GuardrailException accessDenied = assertThrows(
            GuardrailException.class,
            () -> session.execute("retry_disposal", Map.of(), "req-1")
        );
        assertEquals(ViolationCode.ACCESS_DENIED, accessDenied.violation().code());

        // Prerequisite not met
        GuardrailException prereq = assertThrows(
            GuardrailException.class,
            () -> session.execute("get_stock_movements", Map.of("page", 0))
        );
        assertEquals(ViolationCode.PREREQUISITE_NOT_MET, prereq.violation().code());

        // Host-bound parameter tampering blocked
        GuardrailException scopeViolation = assertThrows(
            GuardrailException.class,
            () -> session.execute("get_disposal", Map.of("disposalId", "disp-999"))
        );
        assertEquals(ViolationCode.SCOPE_VIOLATION, scopeViolation.violation().code());

        // Valid detail call injects bound scope parameters automatically
        ToolExecutionOutcome detail = session.execute("get_disposal", Map.of());
        assertEquals(ToolExecutionStatus.COMPLETED, detail.status());
        assertEquals("E1", detail.evidenceId());
        assertEquals(7L, capturedArgs.get().get("accountId"));
        assertEquals("disp-100", capturedArgs.get().get("disposalId"));

        // Deny rule blocks out-of-range page and forbidden secret key
        GuardrailException rangeDenied = assertThrows(
            GuardrailException.class,
            () -> session.execute("get_stock_movements", Map.of("page", -1))
        );
        assertEquals(ViolationCode.POLICY_DENIED, rangeDenied.violation().code());

        GuardrailException keyDenied = assertThrows(
            GuardrailException.class,
            () -> session.execute("get_stock_movements", Map.of("page", 0, "authorization", "Bearer secret"))
        );
        assertEquals(ViolationCode.POLICY_DENIED, keyDenied.violation().code());

        // Per-tool call budget enforced
        GuardrailException detailBudget = assertThrows(
            GuardrailException.class,
            () -> session.execute("get_disposal", Map.of())
        );
        assertEquals(ViolationCode.CALL_BUDGET_EXCEEDED, detailBudget.violation().code());

        // Two valid ledger calls exhaust total session budget (1 + 2 = 3)
        assertEquals("E2", session.execute("get_stock_movements", Map.of("page", 0)).evidenceId());
        assertEquals("E3", session.execute("get_stock_movements", Map.of("page", 1)).evidenceId());

        GuardrailException totalBudget = assertThrows(
            GuardrailException.class,
            () -> session.execute("get_stock_movements", Map.of("page", 2))
        );
        assertEquals(ViolationCode.CALL_BUDGET_EXCEEDED, totalBudget.violation().code());
    }
}
