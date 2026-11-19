package io.github.msbtff.toolguard.testing;

import io.github.msbtff.toolguard.core.ToolExecutionOutcome;
import io.github.msbtff.toolguard.core.ToolHandler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic Record/Replay (VCR) harness for testing agent tool flows without live LLM/network calls.
 */
public final class ToolTranscriptRecorder {
    public record Entry(String toolName, Map<String, Object> arguments, ToolExecutionOutcome outcome) {
        public Entry {
            Objects.requireNonNull(toolName, "toolName");
            arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public ToolHandler record(String toolName, ToolHandler delegate) {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(delegate, "delegate");
        return resolvedArgs -> {
            ToolExecutionOutcome outcome = delegate.invoke(resolvedArgs);
            synchronized (entries) {
                entries.add(new Entry(toolName, resolvedArgs, outcome));
            }
            return outcome;
        };
    }

    public List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public ToolHandler replayHandler(String toolName) {
        Deque<Entry> queue = new ArrayDeque<>();
        synchronized (entries) {
            for (Entry entry : entries) {
                if (entry.toolName().equals(toolName)) {
                    queue.addLast(entry);
                }
            }
        }
        return resolvedArgs -> {
            Entry next = queue.pollFirst();
            if (next == null) {
                throw new AssertionError("No remaining recorded replay entry for tool '" + toolName + "'");
            }
            if (!next.arguments().equals(resolvedArgs)) {
                throw new AssertionError(
                    "Replay argument mismatch for tool '" + toolName + "': expected "
                        + next.arguments() + " but got " + resolvedArgs
                );
            }
            return next.outcome();
        };
    }
}
