package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CanonicalJsonTest {

    @Test
    void roundTripsNestedObjectsArraysNumbersBooleansAndEscapes() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("movementId", 9001L);
        nested.put("ratio", 0.75);
        nested.put("matched", true);
        nested.put("empty", null);
        nested.put("reason", "유통기한 \"경과\"\n줄바꿈\t탭\\역슬래시");
        nested.put("tags", List.of("disposal", 42L, false));

        String json = CanonicalJson.stringify(nested);
        Map<String, Object> parsed = CanonicalJson.parseObject(json);

        assertEquals(9001L, parsed.get("movementId"));
        assertEquals(0.75, parsed.get("ratio"));
        assertEquals(true, parsed.get("matched"));
        assertNull(parsed.get("empty"));
        assertEquals("유통기한 \"경과\"\n줄바꿈\t탭\\역슬래시", parsed.get("reason"));
        assertEquals(List.of("disposal", 42L, false), parsed.get("tags"));
    }

    @Test
    void rejectsMalformedOrExcessivelyNestedJson() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":01}"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.stringify(Double.NaN));

        String deeplyNested = "[".repeat(70) + "]".repeat(70);
        IllegalArgumentException depthError = assertThrows(
            IllegalArgumentException.class,
            () -> CanonicalJson.parse(deeplyNested)
        );
        assertTrue(depthError.getMessage().contains("maximum depth"));
    }

    @Test
    void stringifySortedHandlesEnumsArraysControlCharsAndExponents() {
        Map<String, Object> unsorted = new LinkedHashMap<>();
        unsorted.put("z", AccessLevel.MUTATING);
        unsorted.put("a", new Object[] { "x", 1, null });
        unsorted.put("ctrl", "\b\f\r\n\t\u0001");

        String sortedJson = CanonicalJson.stringifySorted(unsorted);
        assertEquals(
            "{\"a\":[\"x\",1,null],\"ctrl\":\"\\b\\f\\r\\n\\t\\u0001\",\"z\":\"MUTATING\"}",
            sortedJson
        );

        Map<String, Object> parsed = CanonicalJson.parseObject(sortedJson);
        assertEquals("\b\f\r\n\t\u0001", parsed.get("ctrl"));
        assertEquals("MUTATING", parsed.get("z"));
        assertEquals(Arrays.asList("x", 1L, null), parsed.get("a"));

        // Unicode escapes, scientific notation, and negative numbers
        Map<String, Object> parsedComplex = CanonicalJson.parseObject(
            "{\"uni\":\"\\u0041\\/B\",\"exp\":1.5e2,\"neg\":-12,\"zero\":0}"
        );
        assertEquals("A/B", parsedComplex.get("uni"));
        assertEquals(150.0, parsedComplex.get("exp"));
        assertEquals(-12L, parsedComplex.get("neg"));
        assertEquals(0L, parsedComplex.get("zero"));

        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parseObject("[1,2]"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.stringify(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("\"\\q\""));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.parse("\"\\u12\""));
    }
}
