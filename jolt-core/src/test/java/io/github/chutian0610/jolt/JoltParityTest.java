package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine parity: the UTF-8 byte[] engine and the UTF-16 String engine must produce identical
 * token streams, values, error semantics, and depth limits for the same document text.
 */
class JoltParityTest {

    static Stream<Arguments> engines() {
        return Stream.of(
                Arguments.of("utf8", (BiFunction<String, Integer, JoltParser>)
                        (s, depth) -> Jolt.parse(s.getBytes(StandardCharsets.UTF_8), depth)),
                Arguments.of("utf16", (BiFunction<String, Integer, JoltParser>)
                        (s, depth) -> Jolt.parse(s, depth)));
    }

    private static Token[] tokensOf(JoltParser parser) {
        List<Token> out = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out.toArray(new Token[0]);
    }

    private static void drain(JoltParser parser) {
        while (parser.nextToken() != Token.EOF) {
            // consume
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void tokenStreamsMatch(String engine, BiFunction<String, Integer, JoltParser> parse) {
        Token[] expected = {
                Token.START_OBJECT, Token.FIELD_NAME, Token.START_ARRAY,
                Token.VALUE_NUMBER_INT, Token.VALUE_NUMBER_FLOAT, Token.VALUE_TRUE,
                Token.VALUE_NULL, Token.VALUE_STRING, Token.END_ARRAY, Token.END_OBJECT};
        assertEquals(List.of(expected),
                List.of(tokensOf(parse.apply("{\"a\":[1,-2.5e3,true,null,\"s\"]}", 512))));
        assertEquals(List.of(Token.VALUE_NUMBER_INT), List.of(tokensOf(parse.apply("123", 512))));
        assertEquals(List.of(Token.VALUE_STRING), List.of(tokensOf(parse.apply("\"héllo 😀\"", 512))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void valuesMaterializeIdentically(String engine, BiFunction<String, Integer, JoltParser> parse) {
        JoltParser p = parse.apply("{\"k\":\"a\\tb\\uD83D\\uDE00\",\"n\":9007199254740993,"
                + "\"big\":9223372036854775808,\"f\":1.5}", 512);
        assertEquals(Token.START_OBJECT, p.nextToken());
        assertEquals(Token.FIELD_NAME, p.nextToken());
        assertEquals("k", p.textValue());
        assertEquals(Token.VALUE_STRING, p.nextToken());
        assertEquals("a\tb😀", p.textValue());
        assertEquals("a\\tb\\uD83D\\uDE00", p.rawText());
        assertEquals(Token.FIELD_NAME, p.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, p.nextToken());
        assertEquals(9007199254740993L, p.longValue());
        assertEquals(Long.valueOf(9007199254740993L), p.numberValue());
        assertEquals(Token.FIELD_NAME, p.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, p.nextToken());
        assertEquals(new BigDecimal("9223372036854775808"), p.numberValue());
        assertEquals(Token.FIELD_NAME, p.nextToken());
        assertEquals(Token.VALUE_NUMBER_FLOAT, p.nextToken());
        assertEquals(1.5, p.doubleValue());
    }

    /**
     * The rejection corpus, ASCII only: byte offsets equal char offsets, so the FULL message
     * (detail + offset + line + column) is comparable across the engines. The
     * {@code \uD800}-escape forms exercise the materialization-time surrogate rejection both
     * engines share. Excluded: the value-position family ("{"a" 1}", "{1:2}", "[1 2]") and
     * the raw forms — one rejection, each engine's own unit word / own domain — pinned in
     * the lexical-family test below.
     */
    private static final String[] ASCII_REJECTIONS = {
            "{\"a\":1,}", "[1,]",
            "{\"a\":}", "[1", "{\"a\":1", "1.", "1e", "-", "+1", ".5",
            "\"\\q\"", "\"\\u12\"", "tru", "\"unterminated", "{\"a\":1]\"", "[1}]",
            "\"\\uD800\"", "[\"\\uDFFF\"]"};

    private static JsonParseException thrown(Runnable parse) {
        try {
            parse.run();
        } catch (JsonParseException e) {
            return e;
        }
        return null;
    }

    /** Cross-engine message parity: same ASCII document, same full diagnostic — not just
     * the same throw. The one home for the detail strings makes drift structurally hard;
     * this matrix is the regression net. */
    @Test
    void rejectionMessagesMatchAcrossEngines() {
        for (String bad : ASCII_REJECTIONS) {
            JsonParseException utf8 = thrown(() -> drain(Jolt.parse(
                    bad.getBytes(StandardCharsets.UTF_8), 512)));
            JsonParseException utf16 = thrown(() -> drain(Jolt.parse(bad, 512)));
            assertEquals(utf8 == null, utf16 == null, bad);
            assertEquals(utf8 == null ? null : utf8.getMessage(),
                    utf16 == null ? null : utf16.getMessage(), bad);
        }
    }

    /**
     * The two lexical families each reject their OWN domain's raw illegality, and the
     * contract maps them: the UTF-8 engine's byte-level sequence rejection is the byte
     * rendering of what the char engine catches as a raw surrogate — an input one engine can
     * never even see (raw bytes are inexpressible as a String; a raw surrogate survives
     * UTF-8 encoding as {@code ?}). The {@code value not allowed} family is the same in
     * reverse: one rejection, two engines' own unit words (byte vs char). Their detail
     * strings therefore never compare; each is pinned in its own domain here.
     */
    @Test
    void lexicalFamiliesRejectTheirOwnDomain() {
        // byte domain: an illegal leading byte — invisible to the String entrance
        JsonParseException utf8 = thrown(() -> drain(Jolt.parse(
                new byte[]{'"', (byte) 0xFF, '"'}, 512)));
        assertTrue(utf8 != null && utf8.getMessage().startsWith("invalid UTF-8 leading byte"),
                utf8 == null ? "no throw" : utf8.getMessage());
        // char domain: a raw unpaired high surrogate inside a JSON string — invisible to the
        // byte[] entrance (it survives getBytes as '?')
        JsonParseException utf16 = thrown(() -> drain(Jolt.parse("\"\uD800x\"", 512)));
        assertTrue(utf16 != null && utf16.getMessage().startsWith("unpaired surrogate in string"),
                utf16 == null ? "no throw" : utf16.getMessage());
        // the value-position family: same rejection, each engine's own unit word
        JsonParseException vb = thrown(() -> drain(Jolt.parse("{\"a\" 1}".getBytes(StandardCharsets.UTF_8), 512)));
        assertTrue(vb != null && vb.getMessage().startsWith("value not allowed here (byte '1'"),
                vb == null ? "no throw" : vb.getMessage());
        JsonParseException vc = thrown(() -> drain(Jolt.parse("{\"a\" 1}", 512)));
        assertTrue(vc != null && vc.getMessage().startsWith("value not allowed here (char '1'"),
                vc == null ? "no throw" : vc.getMessage());
        JsonParseException vk = thrown(() -> drain(Jolt.parse("{1:2}", 512)));
        assertTrue(vk != null && vk.getMessage().startsWith("value not allowed here (char '1'"),
                vk == null ? "no throw" : vk.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void depthLimitsMatch(String engine, BiFunction<String, Integer, JoltParser> parse) {
        assertDoesNotThrow(() -> drain(parse.apply("[[1]]", 2)));
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> drain(parse.apply("[[[1]]]", 2)));
        assertTrue(e.getMessage().contains("depth"), e.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void skipAndRawTextMatch(String engine, BiFunction<String, Integer, JoltParser> parse) {
        JoltParser p = parse.apply("{\"a\":{\"x\":[1,2]},\"b\":7}", 512);
        assertEquals(Token.START_OBJECT, p.nextToken());
        assertEquals(Token.FIELD_NAME, p.nextToken());
        assertEquals(Token.START_OBJECT, p.nextToken());
        assertEquals(Token.END_OBJECT, p.skipChildren());
        assertEquals("{\"x\":[1,2]}", p.rawText());
        assertEquals(Token.FIELD_NAME, p.nextToken());
        assertEquals("b", p.textValue());
    }

    /**
     * Raw lone surrogates are rejected symmetrically: the UTF-8 engine rejects their byte
     * encoding at scan time (JoltStringRulesTest), the UTF-16 engine rejects the unpaired char.
     * Escaped forms (\uD800) keep the token-level accept / materialization-reject policy.
     */
    @org.junit.jupiter.api.Test
    void utf16RejectsRawLoneSurrogatesLikeUtf8RejectsTheirEncoding() {
        // Jolt.parse is lazy: tokens are scanned on nextToken, so the assertions must drain.
        assertThrows(JsonParseException.class,
                () -> drain(Jolt.parse(new String(new char[]{'"', 0xD800, '"'}))));
        assertThrows(JsonParseException.class,
                () -> drain(Jolt.parse(new String(new char[]{'"', 'a', 0xDC00, 'b', '"'}))));
        // A well-formed pair passes.
        assertDoesNotThrow(() -> drain(Jolt.parse(new String(new char[]{'"', 0xD83D, 0xDE00, '"'}))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void fieldNameEqualsComparesAcrossEngines(String engine, BiFunction<String, Integer, JoltParser> parse) {
        JoltParser parser = parse.apply("{\"name\":\"v\",\"\\u0071uantity\":2}", 512);
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertTrue(parser.fieldNameEquals("name"));
        assertEquals(false, parser.fieldNameEquals("name "));
        assertEquals(false, parser.fieldNameEquals("nam"));
        assertEquals(Token.VALUE_STRING, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        // the escaped form decodes to "quantity" — the rare fallback path of the comparison
        assertTrue(parser.fieldNameEquals("quantity"));
        assertEquals(false, parser.fieldNameEquals("quantity "));
        // non-FIELD_NAME state reports false rather than lying
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(false, parser.fieldNameEquals("2"));
        drain(parser);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void bakedFieldNameKeysCompareAcrossEngines(String engine, BiFunction<String, Integer, JoltParser> parse) {
        // the M8 baked-key chain (#55's #1 lever): pre-encoded keys, no re-encode per miss;
        // semantics identical to the String overload, token for token
        JoltParser parser = parse.apply("{\"name\":\"v\",\"\\u0071uantity\":2,\"nape\":3}", 512);
        FieldName name = FieldName.of("name");
        FieldName quantity = FieldName.of("quantity");
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertTrue(parser.fieldNameEquals(name));
        assertEquals(false, parser.fieldNameEquals(FieldName.of("name ")));
        assertEquals(false, parser.fieldNameEquals(FieldName.of("nam")));
        // a chain of baked keys keeps comparing after misses (the String overload's
        // single-entry cache thrashes exactly here — the dissection's #1 cost)
        assertEquals(false, parser.fieldNameEquals(quantity));
        assertTrue(parser.fieldNameEquals(name));
        assertEquals(Token.VALUE_STRING, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        // the escaped form decodes to "quantity" — the rare fallback path
        assertTrue(parser.fieldNameEquals(quantity));
        assertEquals(false, parser.fieldNameEquals(FieldName.of("quantity ")));
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertTrue(parser.fieldNameEquals(FieldName.of("nape")));
        // non-FIELD_NAME state reports false rather than lying
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(false, parser.fieldNameEquals(FieldName.of("3")));
        drain(parser);
    }
}
