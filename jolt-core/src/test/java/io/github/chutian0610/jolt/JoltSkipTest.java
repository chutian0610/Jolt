package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JoltSkipTest {

    @Test
    void skipChildrenFastForwardsNestedObject() {
        JoltParser parser = Jolt.parse(utf8("{\"a\":{\"x\":[1,2,{\"y\":3}]},\"b\":\"end\"}"));
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());   // "a"
        assertEquals(Token.START_OBJECT, parser.nextToken()); // nested object
        assertEquals(Token.END_OBJECT, parser.skipChildren());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals("b", parser.textValue());
        assertEquals(Token.VALUE_STRING, parser.nextToken());
        assertEquals("end", parser.textValue());
        assertEquals(Token.END_OBJECT, parser.nextToken());
        assertEquals(Token.EOF, parser.nextToken());
    }

    @Test
    void skipChildrenFastForwardsNestedArray() {
        JoltParser parser = Jolt.parse(utf8("{\"a\":[1,[2,3],{\"k\":\"v\"}],\"b\":1}"));
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals(Token.START_ARRAY, parser.nextToken());
        assertEquals(Token.END_ARRAY, parser.skipChildren());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals("b", parser.textValue());
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1L, parser.longValue());
    }

    @Test
    void skipChildrenOnScalarIsNoOp() {
        JoltParser parser = Jolt.parse(utf8("[42,\"x\"]"));
        assertEquals(Token.START_ARRAY, parser.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, parser.skipChildren()); // scalar: unchanged
        assertEquals(Token.VALUE_STRING, parser.nextToken());
    }

    @Test
    void skippingDecodesNothingAndLaterReadsStillWork() {
        // The skipped value holds an escaped lone surrogate, which textValue() would reject.
        // Draining past it without materializing proves skipping decodes nothing.
        JoltParser parser = Jolt.parse(utf8("{\"skip\":\"\\uD800\",\"keep\":\"ok\"}"));
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals(Token.VALUE_STRING, parser.nextToken()); // skip it: no reader called
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals("keep", parser.textValue());
        assertEquals(Token.VALUE_STRING, parser.nextToken());
        assertEquals("ok", parser.textValue());
    }

    @Test
    void rawTextReturnsUndecodedSourceOfLatestToken() {
        JoltParser parser = Jolt.parse(utf8("{\"k\":\"a\\tb\\u0041\"}"));
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals("k", parser.rawText());
        assertEquals(Token.VALUE_STRING, parser.nextToken());
        assertEquals("a\tbA", parser.textValue());
        assertEquals("a\\tb\\u0041", parser.rawText());
    }

    @Test
    void rawTextCoversNumbersAndLiterals() {
        JoltParser parser = Jolt.parse(utf8("[1.50e2,true,null]"));
        assertEquals(Token.START_ARRAY, parser.nextToken());
        assertEquals(Token.VALUE_NUMBER_FLOAT, parser.nextToken());
        assertEquals("1.50e2", parser.rawText());
        assertEquals(Token.VALUE_TRUE, parser.nextToken());
        assertEquals("true", parser.rawText());
        assertEquals(Token.VALUE_NULL, parser.nextToken());
        assertEquals("null", parser.rawText());
    }

    @Test
    void skippedContainerRemainsReadableAsRawText() {
        JoltParser parser = Jolt.parse(utf8("{\"a\":{\"x\":[1,2]},\"b\":7}"));
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.END_OBJECT, parser.skipChildren());
        // The whole skipped container is still addressable as raw source text.
        assertEquals("{\"x\":[1,2]}", parser.rawText());
        // And parsing continues unaffected.
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals("b", parser.textValue());
    }
}
