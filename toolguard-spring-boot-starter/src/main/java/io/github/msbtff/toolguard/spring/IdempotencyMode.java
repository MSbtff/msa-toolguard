package io.github.msbtff.toolguard.spring;

/**
 * Controls whether a {@link GuardedTool} method requires an idempotency key ({@code requestId}).
 */
public enum IdempotencyMode {
    /** Defaults to required for {@code MUTATING} tools and optional for {@code READ_ONLY} tools. */
    AUTO,
    /** Always requires a non-blank {@code requestId}, regardless of access level. */
    REQUIRED,
    /** Disables the {@code requestId} requirement, including for {@code MUTATING} tools. */
    DISABLED
}
