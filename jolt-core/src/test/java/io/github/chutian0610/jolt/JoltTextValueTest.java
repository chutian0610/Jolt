package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltTextValueTest {

    private static String textOf(String json) {
        JoltParser parser = Jolt.parse(utf8(json));
        parser.nextToken();
        return parser.textValue();
    }

    @Test
    void cleanStringDecodesVerbatim() {
        assertEquals("", textOf("\"\""));
        assertEquals("hello", textOf("\"hello\""));
        assertEquals("héllo €", textOf("\"héllo €\""));
        assertEquals("中文", textOf("\"中文\""));
    }

    @Test
    void escapeTableDecodes() {
        assertEquals("\"", textOf("\"\\\"\""));
        assertEquals("\\", textOf("\"\\\\\""));
        assertEquals("/", textOf("\"\\/\""));
        assertEquals("\b", textOf("\"\\b\""));
        assertEquals("\f", textOf("\"\\f\""));
        assertEquals("\n", textOf("\"\\n\""));
        assertEquals("\r", textOf("\"\\r\""));
        assertEquals("\t", textOf("\"\\t\""));
    }

    @Test
    void unicodeEscapesDecode() {
        assertEquals("A", textOf("\"\\u0041\""));
        assertEquals("é", textOf("\"\\u00e9\""));
        assertEquals("é", textOf("\"\\u00E9\""));
        assertEquals("\uFFFF", textOf("\"\\uFFFF\""));
    }

    @Test
    void surrogatePairsCombine() {
        assertEquals("😀", textOf("\"\\uD83D\\uDE00\""));
        assertEquals("😀", textOf("\"\\ud83d\\ude00\""));
        assertEquals("𝄞", textOf("\"\\uD834\\uDD1E\""));
    }

    @Test
    void loneSurrogatesFailAtMaterialization() {
        assertThrows(JsonParseException.class, () -> textOf("\"\\uD800\""));
        assertThrows(JsonParseException.class, () -> textOf("\"\\uDC00\""));
        assertThrows(JsonParseException.class, () -> textOf("\"\\uD800x\""));
        assertThrows(JsonParseException.class, () -> textOf("\"a\\uDC00b\""));
    }

    @Test
    void mixedEscapesDecodeInOnePass() {
        assertEquals("a\tbAc", textOf("\"a\\tb\\u0041c\""));
        assertEquals("é\n😀", textOf("\"é\\n\\uD83D\\uDE00\""));
        assertEquals("q\"q\\q", textOf("\"q\\\"q\\\\q\""));
    }

    @Test
    void escapesDecodeInFieldNamesToo() {
        JoltParser parser = Jolt.parse(utf8("{\"k\\t1\":\"v\\n2\"}"));
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals("k\t1", parser.textValue());
        assertEquals(Token.VALUE_STRING, parser.nextToken());
        assertEquals("v\n2", parser.textValue());
        assertEquals(Token.END_OBJECT, parser.nextToken());
    }

    @Test
    void loneEscapeSurrogatesStillParseAsTokens() {
        // Token-level drain accepts lone escaped surrogates (recorded i-manifest behavior);
        // only materialization rejects them — lazy materialization in action.
        JoltTestSupport.drain(utf8("\"\\uD800\""));
    }
}
