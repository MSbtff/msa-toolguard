package io.github.goldenpage.toolguard.core;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class DenyRules {
    private DenyRules() {}

    public static DenyRule forbidKeys(Set<String> forbiddenKeys) {
        Set<String> keys = Set.copyOf(Objects.requireNonNull(forbiddenKeys, "forbiddenKeys"));
        return (toolName, args) -> args.keySet().stream()
            .filter(keys::contains)
            .findFirst()
            .map(key -> "Forbidden parameter '" + key + "' is not allowed for tool '" + toolName + "'");
    }

    public static DenyRule forbidValuePattern(String key, Pattern forbiddenPattern, String reason) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(forbiddenPattern, "forbiddenPattern");
        Objects.requireNonNull(reason, "reason");
        return (toolName, args) -> {
            Object value = args.get(key);
            if (value != null && forbiddenPattern.matcher(String.valueOf(value)).find()) {
                return Optional.of(reason);
            }
            return Optional.empty();
        };
    }

    public static DenyRule integerRange(String key, long min, long max) {
        Objects.requireNonNull(key, "key");
        return (toolName, args) -> {
            Object value = args.get(key);
            if (value == null) {
                return Optional.empty();
            }
            if (!(value instanceof Number number) || value instanceof Double || value instanceof Float) {
                return Optional.of("Parameter '" + key + "' must be an integer");
            }
            long actual = number.longValue();
            if (actual < min || actual > max) {
                return Optional.of("Parameter '" + key + "' must be within [" + min + ", " + max + "]");
            }
            return Optional.empty();
        };
    }
}
