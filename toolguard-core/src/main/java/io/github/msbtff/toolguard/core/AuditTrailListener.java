package io.github.msbtff.toolguard.core;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-safe {@link ToolExecutionListener} that captures timestamped audit events
 * and optionally streams JSONL lines via {@link AuditLogFormatter}.
 */
public final class AuditTrailListener implements ToolExecutionListener {
    private final AuditLogFormatter formatter;
    private final Clock clock;
    private final Consumer<String> jsonLineSink;
    private final List<AuditLogFormatter.AuditEntry> entries = new ArrayList<>();

    public AuditTrailListener() {
        this(AuditLogFormatter.defaults(), Clock.systemUTC(), null);
    }

    public AuditTrailListener(AuditLogFormatter formatter, Clock clock, Consumer<String> jsonLineSink) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jsonLineSink = jsonLineSink;
    }

    @Override
    public void onEvent(ToolExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        Instant now = clock.instant();
        AuditLogFormatter.AuditEntry entry = new AuditLogFormatter.AuditEntry(now, event);
        synchronized (entries) {
            entries.add(entry);
        }
        if (jsonLineSink != null) {
            jsonLineSink.accept(formatter.formatEventJson(event, now));
        }
    }

    public List<AuditLogFormatter.AuditEntry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public List<String> toJsonLines() {
        List<AuditLogFormatter.AuditEntry> snapshot = entries();
        List<String> lines = new ArrayList<>(snapshot.size());
        for (AuditLogFormatter.AuditEntry entry : snapshot) {
            lines.add(formatter.formatEventJson(entry.event(), entry.timestamp()));
        }
        return List.copyOf(lines);
    }

    public String toSessionJson(String sessionId, EvidenceLedger ledger, VerificationResult verification) {
        return formatter.formatSessionJson(sessionId, entries(), ledger, verification);
    }

    public String toMarkdown(String sessionId, EvidenceLedger ledger, VerificationResult verification) {
        return formatter.formatSessionMarkdown(sessionId, entries(), ledger, verification);
    }
}
