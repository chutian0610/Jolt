package io.github.chutian0610.jolt;

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void rejectionsMatch(String engine, BiFunction<String, Integer, JoltParser> parse) {
        for (String bad : new String[]{
                "{\"a\":1,}", "[1,]", "{\"a\" 1}", "{1:2}", "[1 2]", "123 456", "",
                "{\"a\":}", "[1", "{\"a\":1", "01", "1.", "1e", "-", "+1", ".5",
                "\"\\q\"", "\"\\u12\"", "tru", "\"unterminated", "{\"a\":1]\"", "[1}]"}) {
            assertThrows(JsonParseException.class, () -> tokensOf(parse.apply(bad, 512)), bad);
        }
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
}
