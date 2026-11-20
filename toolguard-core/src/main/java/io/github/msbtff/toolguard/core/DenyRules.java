package io.github.msbtff.toolguard.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class DenyRules {
    private DenyRules() {}

    public static DenyRule forbidKeys(Set<String> forbiddenKeys) {
        Objects.requireNonNull(forbiddenKeys, "forbiddenKeys");
        Set<String> normalized = new LinkedHashSet<>();
        for (String key : forbiddenKeys) {
            Objects.requireNonNull(key, "forbiddenKeys element");
            normalized.add(key.toLowerCase(Locale.ROOT));
        }
        Set<String> keys = Set.copyOf(normalized);
        return (toolName, args) -> args.keySet().stream()
            .filter(key -> key != null && keys.contains(key.toLowerCase(Locale.ROOT)))
            .findFirst()
            .map(key -> "Forbidden parameter '" + key + "' is not allowed for tool '" + toolName + "'");
    }

    public static DenyRule allowedKeys(Set<String> allowedKeys) {
        Objects.requireNonNull(allowedKeys, "allowedKeys");
        Set<String> allowed = new LinkedHashSet<>();
        for (String key : allowedKeys) {
            Objects.requireNonNull(key, "allowedKeys element");
            allowed.add(key);
        }
        Set<String> immutableAllowed = Set.copyOf(allowed);
        return (toolName, args) -> {
            List<String> unexpected = new ArrayList<>();
            for (String key : args.keySet()) {
                if (key == null || !immutableAllowed.contains(key)) {
                    unexpected.add(String.valueOf(key));
                }
            }
            if (unexpected.isEmpty()) {
                return Optional.empty();
            }
            Collections.sort(unexpected);
            return Optional.of(
                "Unexpected parameter(s) " + String.join(", ", unexpected) + " for tool '" + toolName + "'"
            );
        };
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
