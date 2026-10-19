package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltStructureTest {

    private static Token[] tokens(String json) {
        JoltParser parser = Jolt.parse(json.getBytes(StandardCharsets.UTF_8));
        List<Token> collected = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            collected.add(t);
        }
        return collected.toArray(new Token[0]);
    }

    private static void assertRejected(String json) {
        assertThrows(JsonParseException.class, () -> tokens(json), json);
    }

    @Test
    void emptyArrayYieldsStartAndEnd() {
        assertArrayEquals(new Token[]{Token.START_ARRAY, Token.END_ARRAY}, tokens("[]"));
    }

    @Test
    void arrayOfIntegers() {
        assertArrayEquals(
                new Token[]{Token.START_ARRAY, Token.VALUE_NUMBER_INT, Token.VALUE_NUMBER_INT,
                        Token.VALUE_NUMBER_INT, Token.END_ARRAY},
                tokens("[1,2,3]"));
    }

    @Test
    void nestedObjectHoldingLiteralArray() {
        assertArrayEquals(
                new Token[]{Token.START_OBJECT, Token.FIELD_NAME, Token.START_ARRAY,
                        Token.VALUE_TRUE, Token.VALUE_FALSE, Token.VALUE_NULL,
                        Token.END_ARRAY, Token.END_OBJECT},
                tokens("{\"a\":[true,false,null]}"));
    }

    @Test
    void topLevelScalarsParseAlone() {
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_INT}, tokens("123"));
        assertArrayEquals(new Token[]{Token.VALUE_STRING}, tokens("\"x\""));
        assertArrayEquals(new Token[]{Token.VALUE_TRUE}, tokens("true"));
        assertArrayEquals(new Token[]{Token.VALUE_FALSE}, tokens("false"));
        assertArrayEquals(new Token[]{Token.VALUE_NULL}, tokens("null"));
    }

    @Test
    void objectStringValues() {
        assertArrayEquals(
                new Token[]{Token.START_OBJECT, Token.FIELD_NAME, Token.VALUE_STRING, Token.END_OBJECT},
                tokens("{\"a\":\"b\"}"));
    }

    @Test
    void whitespaceAroundTokensIsAccepted() {
        assertArrayEquals(
                new Token[]{Token.START_OBJECT, Token.FIELD_NAME, Token.VALUE_NUMBER_INT, Token.END_OBJECT},
                tokens(" \t\r\n{ \"a\" : 1 } \n"));
    }

    @Test
    void rejectsTrailingCommaInObject() {
        assertRejected("{\"a\":1,}");
    }

    @Test
    void rejectsTrailingCommaInArray() {
        assertRejected("[1,]");
    }

    @Test
    void rejectsMissingColon() {
        assertRejected("{\"a\" 1}");
    }

    @Test
    void rejectsMissingCommaBetweenMembers() {
        assertRejected("{\"a\":1 \"b\":2}");
    }

    @Test
    void rejectsNonStringKey() {
        assertRejected("{1:2}");
    }

    @Test
    void rejectsMissingCommaBetweenArrayItems() {
        assertRejected("[1 2]");
    }

    @Test
    void rejectsTwoTopLevelValues() {
        assertRejected("123 456");
    }

    @Test
    void rejectsEmptyInput() {
        assertRejected("");
    }

    @Test
    void rejectsMissingValueAfterColon() {
        assertRejected("{\"a\":}");
    }

    @Test
    void rejectsUnclosedArray() {
        assertRejected("[1");
    }

    @Test
    void rejectsUnclosedObject() {
        assertRejected("{\"a\":1");
    }

    @Test
    void rejectsValueAfterTopLevelValue() {
        assertRejected("1 x");
    }
}
