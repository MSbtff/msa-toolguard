package io.github.msbtff.toolguard.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure Java 21 RFC 8259 JSON serializer and parser with zero external runtime dependencies.
 * Used by audit log formatters and persistent ToolExecutionStore implementations.
 */
public final class CanonicalJson {
    private static final int MAX_DEPTH = 64;

    private CanonicalJson() {}

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false, 0);
        return sb.toString();
    }

    public static String stringifySorted(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true, 0);
        return sb.toString();
    }

    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.isAtEnd()) {
            throw new IllegalArgumentException("Unexpected trailing character at index " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object parsed = parse(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static void writeValue(StringBuilder sb, Object value, boolean sortKeys, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting exceeds maximum depth of " + MAX_DEPTH);
        }
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean bool) {
            sb.append(bool);
        } else if (value instanceof Number number) {
            if (number instanceof Double d && (!Double.isFinite(d))) {
                throw new IllegalArgumentException("Non-finite double cannot be serialized to JSON: " + d);
            }
            if (number instanceof Float f && (!Float.isFinite(f))) {
                throw new IllegalArgumentException("Non-finite float cannot be serialized to JSON: " + f);
            }
            sb.append(number);
        } else if (value instanceof CharSequence text) {
            writeString(sb, text.toString());
        } else if (value instanceof Enum<?> enumValue) {
            writeString(sb, enumValue.name());
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = sortKeys ? new TreeMap<>() : new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                ordered.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ordered.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, entry.getKey());
                sb.append(':');
                writeValue(sb, entry.getValue(), sortKeys, depth + 1);
            }
            sb.append('}');
        } else if (value instanceof Collection<?> collection) {
            sb.append('[');
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item, sortKeys, depth + 1);
            }
            sb.append(']');
        } else if (value instanceof Object[] array) {
            sb.append('[');
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(sb, array[i], sortKeys, depth + 1);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String input;
        private int pos;

        private Parser(String input) {
            this.input = input;
        }

        private boolean isAtEnd() {
            return pos >= input.length();
        }

        private void skipWhitespace() {
            while (!isAtEnd()) {
                char ch = input.charAt(pos);
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting exceeds maximum depth of " + MAX_DEPTH);
            }
            skipWhitespace();
            if (isAtEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char ch = input.charAt(pos);
            return switch (ch) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (ch == '-' || (ch >= '0' && ch <= '9')) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character '" + ch + "' at index " + pos);
                }
            };
        }

        private Map<String, Object> parseObject(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!isAtEnd() && input.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (isAtEnd() || input.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key in object at index " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (isAtEnd() || input.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' after key at index " + pos);
                }
                pos++;
                Object value = parseValue(depth + 1);
                map.put(key, value);
                skipWhitespace();
                if (isAtEnd()) {
                    throw new IllegalArgumentException("Unterminated JSON object");
                }
                char next = input.charAt(pos++);
                if (next == '}') {
                    return map;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or '}' in object at index " + (pos - 1));
                }
            }
        }

        private List<Object> parseArray(int depth) {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!isAtEnd() && input.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth + 1));
                skipWhitespace();
                if (isAtEnd()) {
                    throw new IllegalArgumentException("Unterminated JSON array");
                }
                char next = input.charAt(pos++);
                if (next == ']') {
                    return list;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' in array at index " + (pos - 1));
                }
            }
        }

        private String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (!isAtEnd()) {
                char ch = input.charAt(pos++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch < 0x20) {
                    throw new IllegalArgumentException("Unescaped control character in string at index " + (pos - 1));
                }
                if (ch == '\\') {
                    if (isAtEnd()) {
                        throw new IllegalArgumentException("Unterminated escape sequence in string");
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > input.length()) {
                                throw new IllegalArgumentException("Incomplete unicode escape at index " + pos);
                            }
                            String hex = input.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("Invalid unicode escape \\u" + hex, e);
                            }
                        }
                        default -> throw new IllegalArgumentException("Invalid escape '\\" + esc + "' at index " + (pos - 1));
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Invalid literal at index " + pos);
            }
            pos += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') {
                pos++;
            }
            if (isAtEnd()) {
                throw new IllegalArgumentException("Incomplete number at index " + start);
            }
            char first = input.charAt(pos);
            if (first == '0') {
                pos++;
                if (!isAtEnd() && Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Leading zeros are not allowed in JSON numbers at index " + start);
                }
            } else if (first >= '1' && first <= '9') {
                while (!isAtEnd() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            } else {
                throw new IllegalArgumentException("Invalid number at index " + start);
            }
            boolean isFloat = false;
            if (!isAtEnd() && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                if (isAtEnd() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid fractional number at index " + start);
                }
                while (!isAtEnd() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            if (!isAtEnd() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (!isAtEnd() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                if (isAtEnd() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid exponent in number at index " + start);
                }
                while (!isAtEnd() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            String token = input.substring(start, pos);
            if (!isFloat) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException overflow) {
                    return Double.parseDouble(token);
                }
            }
            double value = Double.parseDouble(token);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Number out of finite range at index " + start);
            }
            return value;
        }
    }
}
