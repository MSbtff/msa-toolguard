package io.github.goldenpage.toolguard.core;

@FunctionalInterface
public interface ToolExecutionListener {
    ToolExecutionListener NOOP = event -> {};

    void onEvent(ToolExecutionEvent event);
}
