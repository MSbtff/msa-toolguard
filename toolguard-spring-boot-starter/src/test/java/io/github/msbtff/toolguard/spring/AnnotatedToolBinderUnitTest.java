package io.github.msbtff.toolguard.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.msbtff.toolguard.core.AccessLevel;
import io.github.msbtff.toolguard.core.GuardrailException;
import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolExecutionStatus;
import io.github.msbtff.toolguard.core.ToolSession;
import io.github.msbtff.toolguard.core.ViolationCode;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AnnotatedToolBinderUnitTest {

    static class DeclarativeTools {
        @GuardedTool(
            name = "search_disposals",
            accessLevel = AccessLevel.READ_ONLY,
            maxCalls = 5,
            boundParameters = {"accountId"},
            forbidKeys = {"authorization", "api_key"},
            allowedKeys = {"query"},
            evidencePrefix = "SR"
        )
        public ToolExecutionOutcome searchDisposals(Map<String, Object> args) {
            return ToolExecutionOutcome.completed(Map.of(
                "accountId", args.get("accountId"),
                "query", args.getOrDefault("query", "")
            ));
        }

        @GuardedTool(
            name = "idempotent_read",
            accessLevel = AccessLevel.READ_ONLY,
            maxCalls = 2,
            idempotency = IdempotencyMode.REQUIRED
        )
        public ToolExecutionOutcome idempotentRead(Map<String, Object> args) {
            return ToolExecutionOutcome.completed(Map.of("ok", true));
        }

        @GuardedTool(
            name = "non_idempotent_mutate",
            accessLevel = AccessLevel.MUTATING,
            maxCalls = 2,
            idempotency = IdempotencyMode.DISABLED
        )
        public ToolExecutionOutcome nonIdempotentMutate(Map<String, Object> args) {
            return ToolExecutionOutcome.completed(Map.of("mutated", true));
        }

        @GuardedTool(name = "throws_runtime", maxCalls = 1)
        public ToolExecutionOutcome throwsRuntime(Map<String, Object> args) {
            throw new IllegalStateException("boom");
        }

        @GuardedTool(name = "throws_checked", maxCalls = 1)
        public ToolExecutionOutcome throwsChecked(Map<String, Object> args) throws IOException {
            throw new IOException("io failure");
        }
    }

    static class InvalidSignatureTool {
        @GuardedTool(name = "bad_sig")
        public String badSignature(String input) {
            return input;
        }
    }

    static class ConflictingIdempotencyTool {
        @GuardedTool(
            name = "conflict_tool",
            accessLevel = AccessLevel.MUTATING,
            requireIdempotencyKey = true,
            idempotency = IdempotencyMode.DISABLED
        )
        public ToolExecutionOutcome conflictTool(Map<String, Object> args) {
            return ToolExecutionOutcome.completed(Map.of());
        }
    }

    @Test
    void bindsDeclarativeForbidKeysAllowedKeysAndIdempotencyModes() {
        ToolSession.Builder builder = ToolSession.builder()
            .maxAccessLevel(AccessLevel.MUTATING)
            .bindParameter("accountId", 7L);
        AnnotatedToolBinder.registerAll(builder, new DeclarativeTools());
        ToolSession session = builder.build();

        // Case-insensitive forbidKeys blocks "Authorization"
        GuardrailException authBlocked = assertThrows(
            GuardrailException.class,
            () -> session.execute("search_disposals", Map.of("Authorization", "Bearer leak", "query", "milk"))
        );
        assertEquals(ViolationCode.POLICY_DENIED, authBlocked.violation().code());

        // allowedKeys blocks undeclared argument while automatically allowing boundParameter "accountId"
        GuardrailException extraBlocked = assertThrows(
            GuardrailException.class,
            () -> session.execute("search_disposals", Map.of("query", "milk", "extra", 1))
        );
        assertEquals(ViolationCode.POLICY_DENIED, extraBlocked.violation().code());

        ToolExecutionOutcome searchOk = session.execute("search_disposals", Map.of("query", "milk"));
        assertEquals(ToolExecutionStatus.COMPLETED, searchOk.status());
        assertEquals("SR1", searchOk.evidenceId());
        assertEquals(7L, searchOk.facts().get("accountId"));

        // IdempotencyMode.REQUIRED enforces requestId on READ_ONLY tool
        GuardrailException missingKey = assertThrows(
            GuardrailException.class,
            () -> session.execute("idempotent_read", Map.of())
        );
        assertEquals(ViolationCode.MISSING_IDEMPOTENCY_KEY, missingKey.violation().code());
        assertEquals(ToolExecutionStatus.COMPLETED, session.execute("idempotent_read", Map.of(), "req-read-1").status());

        // IdempotencyMode.DISABLED allows MUTATING tool without requestId
        ToolExecutionOutcome mutOk = session.execute("non_idempotent_mutate", Map.of());
        assertEquals(ToolExecutionStatus.COMPLETED, mutOk.status());
        assertEquals(true, mutOk.facts().get("mutated"));
    }

    @Test
    void propagatesRuntimeAndWrapsCheckedExceptionsAndRejectsInvalidAnnotations() {
        ToolSession.Builder builder = ToolSession.builder();
        AnnotatedToolBinder.registerAll(builder, new DeclarativeTools());
        ToolSession session = builder.build();

        IllegalStateException runtime = assertThrows(
            IllegalStateException.class,
            () -> session.execute("throws_runtime", Map.of())
        );
        assertEquals("boom", runtime.getMessage());

        IllegalStateException wrapped = assertThrows(
            IllegalStateException.class,
            () -> session.execute("throws_checked", Map.of())
        );
        assertTrue(wrapped.getCause() instanceof IOException);

        assertThrows(
            IllegalArgumentException.class,
            () -> AnnotatedToolBinder.registerAll(ToolSession.builder(), new InvalidSignatureTool())
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> AnnotatedToolBinder.registerAll(ToolSession.builder(), new ConflictingIdempotencyTool())
        );
    }
}
