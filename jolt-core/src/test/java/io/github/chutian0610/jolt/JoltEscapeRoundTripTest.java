package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Escape-alphabet round-trip tripwire (architecture-review candidate C1): every JSON-representable
 * character is written through the JoltWriter and read back through BOTH engine adapters — the
 * Writer's escaping and the engines' unescaping are mutual inverses, asserted exhaustively over
 * the BMP instead of by sample. Lone surrogates are pinned as Writer rejections.
 */
class JoltEscapeRoundTripTest {

    @Test
    void everyBmpScalarCharRoundTripsThroughWriterAndBothEngines() {
        for (int c = 0; c <= 0xFFFF; c++) {
            if (c >= 0xD800 && c <= 0xDFFF) {
                continue; // not scalar characters; the Writer must reject them (pinned below)
            }
            assertRoundTrip(String.valueOf((char) c));
        }
    }

    @Test
    void astralBoundaryCodePointsRoundTrip() {
        for (int cp : new int[]{0x10000, 0x10001, 0x1D11E, 0x1F600, 0x10FFFC, 0x10FFFD, 0x10FFFE, 0x10FFFF}) {
            assertRoundTrip(new String(Character.toChars(cp)));
        }
    }

    @Test
    void fullAsciiAlphabetInOneString() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < 0x80; c++) {
            sb.append((char) c);
        }
        assertRoundTrip(sb.toString());
    }

    @Test
    void writerRejectsLoneSurrogates() {
        assertThrows(JsonParseException.class, () -> Jolt.write().value("\uD800").toByteArray());
        assertThrows(JsonParseException.class, () -> Jolt.write().value("\uDC00").toByteArray());
        assertThrows(JsonParseException.class,
                () -> Jolt.write().value("high\uD800then").toByteArray());
    }

    private static void assertRoundTrip(String original) {
        byte[] written = Jolt.write().value(original).toByteArray();

        JoltParser utf8 = Jolt.parse(written);
        assertEquals(Token.VALUE_STRING, utf8.nextToken(),
                "char U+" + hex(original) + " (utf8): wrong token");
        assertEquals(original, utf8.textValue(),
                "char U+" + hex(original) + " (utf8): round-trip diverged");

        JoltParser utf16 = Jolt.parse(new String(written, StandardCharsets.UTF_8));
        assertEquals(Token.VALUE_STRING, utf16.nextToken(),
                "char U+" + hex(original) + " (utf16): wrong token");
        assertEquals(original, utf16.textValue(),
                "char U+" + hex(original) + " (utf16): round-trip diverged");
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format("%04X ", (int) s.charAt(i)));
        }
        return sb.toString().trim();
    }
}
