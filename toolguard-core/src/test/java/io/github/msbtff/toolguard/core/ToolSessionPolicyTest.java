package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
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

    @Test
    void enforcesCaseInsensitiveForbidKeysAllowedKeysAndValuePatternRules() {
        ToolPolicy queryPolicy = ToolPolicy.builder("search_disposals")
            .accessLevel(AccessLevel.READ_ONLY)
            .maxCalls(5)
            .boundParameters("accountId")
            .denyRule(DenyRules.forbidKeys(Set.of("authorization", "api_key")))
            .denyRule(DenyRules.allowedKeys(Set.of("accountId", "query", "page")))
            .denyRule(DenyRules.forbidValuePattern("query", Pattern.compile("(?i)drop\\s+table"), "SQL injection pattern blocked"))
            .denyRule(DenyRules.integerRange("page", 0, 100))
            .build();

        ToolSession session = ToolSession.builder()
            .bindParameter("accountId", 7L)
            .registerTool(queryPolicy, args -> ToolExecutionOutcome.completed(Map.of("ok", true)))
            .build();

        // Case-insensitive forbidden key blocking ("Authorization", "API_KEY")
        GuardrailException upperAuth = assertThrows(
            GuardrailException.class,
            () -> session.execute("search_disposals", Map.of("Authorization", "Bearer leak", "query", "milk"))
        );
        assertEquals(ViolationCode.POLICY_DENIED, upperAuth.violation().code());

        GuardrailException upperApiKey = assertThrows(
            GuardrailException.class,
            () -> session.execute("search_disposals", Map.of("API_KEY", "sk-123"))
        );
        assertEquals(ViolationCode.POLICY_DENIED, upperApiKey.violation().code());

        // Unexpected keys blocked with sorted parameter names
        GuardrailException extraKeys = assertThrows(
            GuardrailException.class,
            () -> session.execute("search_disposals", Map.of("zeta", 1, "alpha", 2))
        );
        assertEquals(ViolationCode.POLICY_DENIED, extraKeys.violation().code());
        assertTrue(extraKeys.violation().message().contains("alpha, zeta"));

        // Forbidden value pattern blocked
        GuardrailException sqlBlocked = assertThrows(
            GuardrailException.class,
            () -> session.execute("search_disposals", Map.of("query", "milk; DROP TABLE disposals"))
        );
        assertEquals(ViolationCode.POLICY_DENIED, sqlBlocked.violation().code());
        assertEquals("SQL injection pattern blocked", sqlBlocked.violation().message());

        // Non-integer page blocked by integerRange
        GuardrailException nonIntPage = assertThrows(
            GuardrailException.class,
            () -> session.execute("search_disposals", Map.of("page", 1.5))
        );
        assertEquals(ViolationCode.POLICY_DENIED, nonIntPage.violation().code());

        // Valid invocation passes (query absent also passes forbidValuePattern)
        ToolExecutionOutcome ok = session.execute("search_disposals", Map.of("page", 0));
        assertEquals(ToolExecutionStatus.COMPLETED, ok.status());
        ToolExecutionOutcome okQuery = session.execute("search_disposals", Map.of("query", "milk", "page", 2));
        assertEquals(ToolExecutionStatus.COMPLETED, okQuery.status());
    }

    @Test
    void handlesRejectedOutcomesUnregisteredToolsMissingScopeAndChainedListeners() {
        List<String> firstEvents = new ArrayList<>();
        List<String> secondEvents = new ArrayList<>();

        ToolSession session = ToolSession.builder()
            .maxTotalCalls(5)
            .addListener(event -> firstEvents.add(event.toolName() + ":" + event.allowed()))
            .addListener(event -> secondEvents.add(event.toolName() + ":" + event.status()))
            .registerTool(
                ToolPolicy.builder("validate_item").maxCalls(2).build(),
                args -> ToolExecutionOutcome.rejected("INVALID_STATE", "Item already closed", Map.of("itemId", 10L))
            )
            .registerTool(
                ToolPolicy.builder("close_item").requires("validate_item").maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of("closed", true))
            )
            .registerTool(
                ToolPolicy.builder("scoped_tool").boundParameters("tenantId").maxCalls(1).build(),
                args -> ToolExecutionOutcome.completed(Map.of())
            )
            .build();

        // Rejected outcome records REJECTED evidence and does NOT satisfy prerequisite
        ToolExecutionOutcome rejected = session.execute("validate_item", Map.of());
        assertEquals(ToolExecutionStatus.REJECTED, rejected.status());
        assertEquals("INVALID_STATE", rejected.rejectionCode());
        assertEquals("Item already closed", rejected.message());
        assertEquals("E1", rejected.evidenceId());
        assertFalse(session.evidenceLedger().hasCompletedTool("validate_item"));

        GuardrailException prereqBlocked = assertThrows(
            GuardrailException.class,
            () -> session.execute("close_item", Map.of())
        );
        assertEquals(ViolationCode.PREREQUISITE_NOT_MET, prereqBlocked.violation().code());

        // Unregistered tool -> UNREGISTERED_TOOL
        GuardrailException unknown = assertThrows(
            GuardrailException.class,
            () -> session.execute("nonexistent_tool", Map.of())
        );
        assertEquals(ViolationCode.UNREGISTERED_TOOL, unknown.violation().code());

        // Missing host-bound scope parameter -> SCOPE_VIOLATION
        GuardrailException missingScope = assertThrows(
            GuardrailException.class,
            () -> session.execute("scoped_tool", Map.of())
        );
        assertEquals(ViolationCode.SCOPE_VIOLATION, missingScope.violation().code());

        assertEquals(4, firstEvents.size());
        assertEquals(4, secondEvents.size());
        assertEquals("validate_item:REJECTED", secondEvents.get(0));
        assertEquals(1, session.totalCallCount());

        // Invalid session maxTotalCalls rejected
        assertThrows(IllegalArgumentException.class, () -> ToolSession.builder().maxTotalCalls(0));
    }
}
