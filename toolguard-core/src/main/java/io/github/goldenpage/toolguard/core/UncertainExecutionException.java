package io.github.goldenpage.toolguard.core;

/**
 * Thrown by a tool handler when downstream state cannot be determined
 * (e.g., network timeout or lost response). The session keeps the execution
 * in PENDING instead of fabricating success or rejection.
 */
public class UncertainExecutionException extends RuntimeException {
    public UncertainExecutionException(String message) {
        super(message);
    }

    public UncertainExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
