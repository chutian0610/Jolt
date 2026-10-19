package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JoltDepthLimitTest {

    private static byte[] nestedArrays(int depth) {
        return ("[".repeat(depth) + "1" + "]".repeat(depth)).getBytes(StandardCharsets.UTF_8);
    }

    private static void drain(byte[] json) {
        JoltParser parser = Jolt.parse(json);
        while (parser.nextToken() != Token.EOF) {
            // consume
        }
    }

    @Test
    void defaultLimitAllowsExactly512Levels() {
        assertDoesNotThrow(() -> drain(nestedArrays(512)));
    }

    @Test
    void defaultLimitRejects513Levels() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> drain(nestedArrays(513)));
        assertTrue(e.getMessage().contains("depth"), e.getMessage());
    }

    @Test
    void configuredLimitIsEnforced() {
        assertDoesNotThrow(() -> {
            JoltParser parser = Jolt.parse(nestedArrays(2), 2);
            while (parser.nextToken() != Token.EOF) {
                // consume
            }
        });
        JsonParseException e = assertThrows(JsonParseException.class, () -> {
            JoltParser parser = Jolt.parse(nestedArrays(3), 2);
            while (parser.nextToken() != Token.EOF) {
                // consume
            }
        });
        assertTrue(e.getMessage().contains("depth"));
    }

    @Test
    void depthLimitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Jolt.parse("1".getBytes(StandardCharsets.UTF_8), 0));
        assertThrows(IllegalArgumentException.class, () -> Jolt.parse("1".getBytes(StandardCharsets.UTF_8), -5));
    }

    @Test
    void siblingArraysDoNotAccumulateDepth() {
        // Depth is the current nesting, not the total number of containers ever opened.
        String json = "[[[1]],[[[2]]],[3]]";
        assertDoesNotThrow(() -> drain(json.getBytes(StandardCharsets.UTF_8)));
    }
}
