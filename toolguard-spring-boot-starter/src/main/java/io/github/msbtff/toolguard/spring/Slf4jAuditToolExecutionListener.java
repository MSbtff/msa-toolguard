package io.github.msbtff.toolguard.spring;

import io.github.msbtff.toolguard.core.AuditLogFormatter;
import io.github.msbtff.toolguard.core.ToolExecutionEvent;
import io.github.msbtff.toolguard.core.ToolExecutionListener;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits structured JSONL audit records to SLF4J on every guarded tool invocation or violation.
 */
public final class Slf4jAuditToolExecutionListener implements ToolExecutionListener {
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("io.github.msbtff.toolguard.audit");

    private final AuditLogFormatter formatter;
    private final Clock clock;

    public Slf4jAuditToolExecutionListener(AuditLogFormatter formatter) {
        this(formatter, Clock.systemUTC());
    }

    public Slf4jAuditToolExecutionListener(AuditLogFormatter formatter, Clock clock) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onEvent(ToolExecutionEvent event) {
        String jsonLine = formatter.formatEventJson(event, clock.instant());
        if (event.allowed() && event.status() != null) {
            AUDIT_LOG.info(jsonLine);
        } else {
            AUDIT_LOG.warn(jsonLine);
        }
    }
}
