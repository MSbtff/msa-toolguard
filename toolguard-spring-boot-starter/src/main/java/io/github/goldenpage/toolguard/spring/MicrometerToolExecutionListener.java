package io.github.goldenpage.toolguard.spring;

import io.github.goldenpage.toolguard.core.ToolExecutionEvent;
import io.github.goldenpage.toolguard.core.ToolExecutionListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;

public final class MicrometerToolExecutionListener implements ToolExecutionListener {
    private final MeterRegistry meterRegistry;

    public MicrometerToolExecutionListener(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public void onEvent(ToolExecutionEvent event) {
        String tool = event.toolName().isBlank() ? "unknown" : event.toolName();
        String outcome = event.allowed()
            ? (event.status() == null ? "unknown" : event.status().name().toLowerCase())
            : "blocked";

        Counter.builder("toolguard.tool.calls")
            .description("Count of guarded AI tool invocations by outcome")
            .tag("tool", tool)
            .tag("outcome", outcome)
            .tag("replayed", String.valueOf(event.replayed()))
            .register(meterRegistry)
            .increment();

        if (!event.allowed() && event.violationCode() != null) {
            Counter.builder("toolguard.tool.violations")
                .description("Count of deterministic guardrail violations")
                .tag("tool", tool)
                .tag("code", event.violationCode().name())
                .register(meterRegistry)
                .increment();
        }

        if (event.elapsed() != null) {
            Timer.builder("toolguard.tool.duration")
                .description("Latency of guarded tool executions")
                .tag("tool", tool)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(event.elapsed());
        }
    }
}
