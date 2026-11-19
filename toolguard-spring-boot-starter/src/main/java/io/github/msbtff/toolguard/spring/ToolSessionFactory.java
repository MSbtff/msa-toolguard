package io.github.msbtff.toolguard.spring;

import io.github.msbtff.toolguard.core.ToolExecutionListener;
import io.github.msbtff.toolguard.core.ToolExecutionStore;
import io.github.msbtff.toolguard.core.ToolSession;
import java.util.List;
import java.util.Objects;

public final class ToolSessionFactory {
    private final ToolGuardProperties properties;
    private final ToolExecutionStore executionStore;
    private final List<ToolExecutionListener> listeners;

    public ToolSessionFactory(
        ToolGuardProperties properties,
        ToolExecutionStore executionStore,
        List<ToolExecutionListener> listeners
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    public ToolSession.Builder newSession(String scopeKey) {
        ToolExecutionListener composite = event -> {
            for (ToolExecutionListener listener : listeners) {
                listener.onEvent(event);
            }
        };
        return ToolSession.builder()
            .scopeKey(scopeKey)
            .maxAccessLevel(properties.getDefaultAccessLevel())
            .maxTotalCalls(properties.getDefaultMaxTotalCalls())
            .executionStore(executionStore)
            .listener(composite);
    }
}
