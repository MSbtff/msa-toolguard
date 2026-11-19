package io.github.msbtff.toolguard.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ToolPolicy(
    String name,
    AccessLevel accessLevel,
    int maxCalls,
    Set<String> requires,
    Set<String> boundParameters,
    boolean requireIdempotencyKey,
    String evidencePrefix,
    List<DenyRule> denyRules
) {
    public ToolPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(accessLevel, "accessLevel");
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be >= 1");
        }
        requires = requires == null ? Set.of() : Set.copyOf(requires);
        boundParameters = boundParameters == null ? Set.of() : Set.copyOf(boundParameters);
        evidencePrefix = (evidencePrefix == null || evidencePrefix.isBlank()) ? "E" : evidencePrefix;
        denyRules = denyRules == null ? List.of() : List.copyOf(denyRules);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private AccessLevel accessLevel = AccessLevel.READ_ONLY;
        private int maxCalls = 10;
        private final Set<String> requires = new LinkedHashSet<>();
        private final Set<String> boundParameters = new LinkedHashSet<>();
        private boolean requireIdempotencyKey;
        private String evidencePrefix = "E";
        private final List<DenyRule> denyRules = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder accessLevel(AccessLevel accessLevel) {
            this.accessLevel = accessLevel;
            if (accessLevel == AccessLevel.MUTATING) {
                this.requireIdempotencyKey = true;
            }
            return this;
        }

        public Builder maxCalls(int maxCalls) {
            this.maxCalls = maxCalls;
            return this;
        }

        public Builder requires(String... toolNames) {
            for (String toolName : toolNames) {
                this.requires.add(Objects.requireNonNull(toolName, "toolName"));
            }
            return this;
        }

        public Builder boundParameters(String... paramNames) {
            for (String paramName : paramNames) {
                this.boundParameters.add(Objects.requireNonNull(paramName, "paramName"));
            }
            return this;
        }

        public Builder requireIdempotencyKey(boolean required) {
            this.requireIdempotencyKey = required;
            return this;
        }

        public Builder evidencePrefix(String prefix) {
            this.evidencePrefix = prefix;
            return this;
        }

        public Builder denyRule(DenyRule rule) {
            this.denyRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public ToolPolicy build() {
            return new ToolPolicy(
                name,
                accessLevel,
                maxCalls,
                requires,
                boundParameters,
                requireIdempotencyKey,
                evidencePrefix,
                denyRules
            );
        }
    }
}
