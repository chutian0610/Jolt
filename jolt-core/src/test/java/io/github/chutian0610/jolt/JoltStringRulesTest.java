package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import static io.github.chutian0610.jolt.JoltTestSupport.drain;
import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltStringRulesTest {

    private static byte[] bytes(int... raw) {
        byte[] a = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            a[i] = (byte) raw[i];
        }
        return a;
    }

    private static void assertAccepted(String json) {
        assertDoesNotThrow(() -> drain(utf8(json)), json);
    }

    private static void assertRejected(byte[] json) {
        assertThrows(JsonParseException.class, () -> drain(json));
    }

    @Test
    void acceptsAllLegalEscapes() {
        assertAccepted("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u0041 \\uD83D\\uDE00\"");
    }

    @Test
    void acceptsEscapesInFieldNames() {
        assertAccepted("{\"k\\u00e9y\":1}");
    }

    @Test
    void acceptsLegalUtf8Sequences() {
        // U+00E9 (2-byte), U+20AC (3-byte), U+1D11E (4-byte)
        assertAccepted("\"é € 𝄞\"");
    }

    @Test
    void acceptsNonAsciiInKeys() {
        assertAccepted("{\"键\":1}");
    }

    @Test
    void rejectsRawControlCharacters() {
        assertRejected(bytes('"', '\t', '"'));
        assertRejected(bytes('"', 0x00, '"'));
        assertRejected(bytes('"', 0x1F, '"'));
        assertRejected(bytes('"', 'a', 0x0B, '"'));
    }

    @Test
    void rejectsInvalidEscapeCharacters() {
        assertRejected(utf8("\"\\x\""));
        assertRejected(utf8("\"\\q\""));
        assertRejected(utf8("\"\\'\""));
    }

    @Test
    void rejectsMalformedUnicodeEscapes() {
        assertRejected(utf8("\"\\u004\""));   // only 3 hex digits
        assertRejected(utf8("\"\\uZZZZ\""));  // not hex
        assertRejected(utf8("\"\\u12\""));    // truncated
    }

    @Test
    void rejectsTruncatedUtf8() {
        assertRejected(bytes('"', 0xC3, '"'));            // dangling lead byte
        assertRejected(bytes('"', 0xE2, 0x82, '"'));      // 3-byte cut to 2
        assertRejected(bytes('"', 0xF0, 0x9D, 0x84, '"')); // 4-byte cut to 3
    }

    @Test
    void rejectsInvalidLeadingBytes() {
        assertRejected(bytes('"', 0x80, '"'));            // continuation as lead
        assertRejected(bytes('"', 0xC0, 0xAF, '"'));      // overlong 2-byte
        assertRejected(bytes('"', 0xC1, 0xBF, '"'));      // overlong 2-byte
        assertRejected(bytes('"', 0xF5, 0x80, 0x80, 0x80, '"')); // beyond U+10FFFF range
        assertRejected(bytes('"', 0xFE, '"'));
    }

    @Test
    void rejectsOverlongEncodings() {
        assertRejected(bytes('"', 0xE0, 0x80, 0x80, '"')); // overlong 3-byte
        assertRejected(bytes('"', 0xE0, 0x9F, 0xBF, '"')); // just below 3-byte minimum
        assertRejected(bytes('"', 0xF0, 0x8F, 0xBF, 0xBF, '"')); // overlong 4-byte
    }

    @Test
    void rejectsUtf8EncodedSurrogates() {
        assertRejected(bytes('"', 0xED, 0xA0, 0x80, '"')); // D800
        assertRejected(bytes('"', 0xED, 0xBF, 0xBF, '"')); // DFFF
    }

    @Test
    void rejectsCodePointsBeyondUnicodeMaximum() {
        assertRejected(bytes('"', 0xF4, 0x90, 0x80, 0x80, '"')); // U+110000
    }

    @Test
    void acceptsThreeByteMinimumAndMaximumForms() {
        assertDoesNotThrow(() -> drain(bytes('"', 0xE0, 0xA0, 0x80, '"'))); // U+0800, 3-byte minimum
        assertDoesNotThrow(() -> drain(bytes('"', 0xEF, 0xBF, 0xBF, '"'))); // U+FFFF, 3-byte maximum
    }

    @Test
    void rejectsUnterminatedString() {
        assertRejected(utf8("\"abc"));
        assertRejected(utf8("\"abc\\"));
    }

    @Test
    void rejectsUtf8BomAtDocumentStart() {
        assertRejected(bytes(0xEF, 0xBB, 0xBF, '1'));
    }
}
