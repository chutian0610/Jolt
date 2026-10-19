package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JoltParseTest {

    @Test
    void emptyObjectYieldsStartAndEndThenEof() {
        JoltParser parser = Jolt.parse("{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.END_OBJECT, parser.nextToken());
        assertEquals(Token.EOF, parser.nextToken());
    }

    @Test
    void objectWithOneFieldYieldsFullTokenStream() {
        JoltParser parser = Jolt.parse("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals("a", parser.textValue());
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(1L, parser.longValue());
        assertEquals(Token.END_OBJECT, parser.nextToken());
        assertEquals(Token.EOF, parser.nextToken());
    }
}
