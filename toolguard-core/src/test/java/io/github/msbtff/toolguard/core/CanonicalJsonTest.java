package io.github.msbtff.toolguard.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
