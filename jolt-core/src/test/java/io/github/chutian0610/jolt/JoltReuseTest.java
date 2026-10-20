package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltReuseTest {

    private static List<Token> drainCollect(JoltParser parser) {
        List<Token> out = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out;
    }

    @Test
    void reusedUtf8InstanceParsesSequentialDocuments() {
        Utf8Parser p = Jolt.utf8Parser(utf8("{\"a\":1}"));
        assertArrayEquals(new Token[]{Token.START_OBJECT, Token.FIELD_NAME, Token.VALUE_NUMBER_INT, Token.END_OBJECT},
                tokensOf(p));
        p.reset(utf8("[1,2,3]"));
        assertArrayEquals(new Token[]{Token.START_ARRAY, Token.VALUE_NUMBER_INT, Token.VALUE_NUMBER_INT,
                Token.VALUE_NUMBER_INT, Token.END_ARRAY}, tokensOf(p));
        p.reset(utf8("\"s\""));
        assertArrayEquals(new Token[]{Token.VALUE_STRING}, tokensOf(p));
    }

    private static Token[] tokensOf(JoltParser parser) {
        return drainCollect(parser).toArray(new Token[0]);
    }

    @Test
    void resetClearsStateLeftByAnInterruptedDocument() {
        Utf8Parser p = Jolt.utf8Parser(utf8("[1,2,3]"));
        assertEqualsFirst(Token.START_ARRAY, p);
        assertEqualsFirst(Token.VALUE_NUMBER_INT, p);
        // abandon mid-document, re-arm on a fresh one
        p.reset(utf8("true"));
        assertArrayEquals(new Token[]{Token.VALUE_TRUE}, tokensOf(p));
    }

    @Test
    void resetAfterFailureRecovers() {
        Utf8Parser p = Jolt.utf8Parser(utf8("[1,]"));
        assertThrows(JsonParseException.class, () -> drainCollect(p));
        p.reset(utf8("{\"ok\":true}"));
        assertDoesNotThrow(() -> drainCollect(p));
    }

    @Test
    void reusedUtf16InstanceBehavesIdentically() {
        Utf16Parser p = Jolt.utf16Parser("{\"a\":\"b\"}");
        assertArrayEquals(new Token[]{Token.START_OBJECT, Token.FIELD_NAME, Token.VALUE_STRING, Token.END_OBJECT},
                tokensOf(p));
        p.reset("[1]");
        assertArrayEquals(new Token[]{Token.START_ARRAY, Token.VALUE_NUMBER_INT, Token.END_ARRAY}, tokensOf(p));
    }

    @Test
    void reusedInstanceMatchesFreshInstancesAcrossDocuments() {
        String[] docs = {"{\"a\":[1,-2.5e3,true,null]}", "[]", "123", "\"x\\ty\"", "{\"deep\":{\"deeper\":[[[1]]]}}"};
        Utf8Parser reused = Jolt.utf8Parser(utf8(docs[0]));
        for (String doc : docs) {
            assertArrayEquals(tokensOf(Jolt.parse(utf8(doc))), tokensOf(reused.reset(utf8(doc))), doc);
        }
    }

    @Test
    void concreteFactoriesHonorConfig() {
        Utf8Parser p = Jolt.utf8Parser(utf8("[1,]"), JoltConfig.strict().withTrailingCommas());
        assertDoesNotThrow(() -> drainCollect(p));
        p.reset(utf8("[1,]"));
        assertDoesNotThrow(() -> drainCollect(p));
    }

    private static void assertEqualsFirst(Token expected, JoltParser parser) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, parser.nextToken());
    }
}
