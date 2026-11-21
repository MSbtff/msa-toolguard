package io.github.msbtff.toolguard.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_.-]*$");
    private static final Pattern EVIDENCE_PREFIX_PATTERN = Pattern.compile("^[A-Z]{1,4}$");

    public ToolPolicy {
        if (name == null || !TOOL_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Invalid tool policy name '" + name + "': must match " + TOOL_NAME_PATTERN.pattern()
            );
        }
        Objects.requireNonNull(accessLevel, "accessLevel");
        if (maxCalls < 1) {
            throw new IllegalArgumentException("maxCalls must be >= 1");
        }
        requires = normalizeNonBlankSet(requires, "requires");
        boundParameters = normalizeNonBlankSet(boundParameters, "boundParameters");
        evidencePrefix = (evidencePrefix == null || evidencePrefix.isBlank()) ? "E" : evidencePrefix;
        if (!EVIDENCE_PREFIX_PATTERN.matcher(evidencePrefix).matches()) {
            throw new IllegalArgumentException(
                "Invalid evidencePrefix '" + evidencePrefix + "': must match " + EVIDENCE_PREFIX_PATTERN.pattern()
            );
        }
        denyRules = denyRules == null ? List.of() : List.copyOf(denyRules);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * Converts this policy into a contract map compatible with {@code contracts/tool-policy-v1.schema.json}.
     */
    public Map<String, Object> toContractMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("accessLevel", accessLevel.name());
        map.put("maxCalls", maxCalls);
        map.put("requires", List.copyOf(requires));
        map.put("boundParameters", List.copyOf(boundParameters));
        map.put("requireIdempotencyKey", requireIdempotencyKey);
        map.put("evidencePrefix", evidencePrefix);
        return Collections.unmodifiableMap(map);
    }

    /**
     * Serializes this policy into JSON compatible with {@code contracts/tool-policy-v1.schema.json}.
     */
    public String toContractJson() {
        return CanonicalJson.stringify(toContractMap());
    }

    /**
     * Deserializes a policy from a contract map matching {@code contracts/tool-policy-v1.schema.json}.
     */
    public static ToolPolicy fromContractMap(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload");
        String name = requireString(payload, "name");
        AccessLevel level = AccessLevel.valueOf(requireString(payload, "accessLevel"));
        int maxCalls = requirePositiveInt(payload, "maxCalls");
        Set<String> requires = optionalStringSet(payload, "requires");
        Set<String> boundParameters = optionalStringSet(payload, "boundParameters");
        Object rawIdempotency = payload.get("requireIdempotencyKey");
        boolean requireIdempotencyKey;
        if (rawIdempotency == null) {
            requireIdempotencyKey = level == AccessLevel.MUTATING;
        } else if (rawIdempotency instanceof Boolean bool) {
            requireIdempotencyKey = bool;
        } else {
            throw new IllegalArgumentException("Field 'requireIdempotencyKey' must be a boolean");
        }
        Object rawPrefix = payload.get("evidencePrefix");
        String prefix = rawPrefix == null ? "E" : String.valueOf(rawPrefix);
        return new ToolPolicy(
            name,
            level,
            maxCalls,
            requires,
            boundParameters,
            requireIdempotencyKey,
            prefix,
            List.of()
        );
    }

    /**
     * Deserializes a policy from JSON matching {@code contracts/tool-policy-v1.schema.json}.
     */
    public static ToolPolicy fromContractJson(String json) {
        return fromContractMap(CanonicalJson.parseObject(json));
    }

    private static Set<String> normalizeNonBlankSet(Set<String> input, String fieldName) {
        if (input == null || input.isEmpty()) {
            return Set.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String item : input) {
            if (item == null || item.isBlank()) {
                throw new IllegalArgumentException(fieldName + " elements must not be blank");
            }
            ordered.add(item);
        }
        return Collections.unmodifiableSet(ordered);
    }

    private static String requireString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof String str) || str.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid string field '" + key + "'");
        }
        return str;
    }

    private static int requirePositiveInt(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Number number) || value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException("Field '" + key + "' must be an integer");
        }
        long longValue = number.longValue();
        if (longValue < 1 || longValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Field '" + key + "' must be between 1 and Integer.MAX_VALUE");
        }
        return (int) longValue;
    }

    private static Set<String> optionalStringSet(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Field '" + key + "' must be a list of strings");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String str) || str.isBlank()) {
                throw new IllegalArgumentException("Field '" + key + "' elements must be non-blank strings");
            }
            result.add(str);
        }
        return Collections.unmodifiableSet(result);
    }

    public static final class Builder {
        private final String name;
        private AccessLevel accessLevel = AccessLevel.READ_ONLY;
        private int maxCalls = 10;
        private final Set<String> requires = new LinkedHashSet<>();
        private final Set<String> boundParameters = new LinkedHashSet<>();
        private Boolean requireIdempotencyKey;
        private String evidencePrefix = "E";
        private final List<DenyRule> denyRules = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder accessLevel(AccessLevel accessLevel) {
            this.accessLevel = Objects.requireNonNull(accessLevel, "accessLevel");
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
            boolean effectiveRequireIdempotency = requireIdempotencyKey != null
                ? requireIdempotencyKey
                : accessLevel == AccessLevel.MUTATING;
            return new ToolPolicy(
                name,
                accessLevel,
                maxCalls,
                requires,
                boundParameters,
                effectiveRequireIdempotency,
                evidencePrefix,
                denyRules
            );
        }
    }
}
