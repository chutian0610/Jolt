package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltInputStreamTest {

    private static Token[] drain(InputStream in) throws IOException {
        JoltParser parser = Jolt.parse(in);
        java.util.List<Token> out = new java.util.ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out.toArray(new Token[0]);
    }

    @Test
    void parsesExactlyLikeTheByteArrayPath() throws IOException {
        assertArrayEquals(
                new Token[]{Token.START_OBJECT, Token.FIELD_NAME, Token.VALUE_NUMBER_INT, Token.END_OBJECT},
                drain(new ByteArrayInputStream(utf8("{\"a\":1}"))));
        assertArrayEquals(new Token[]{Token.START_ARRAY, Token.END_ARRAY},
                drain(new ByteArrayInputStream(utf8("[]"))));
    }

    @Test
    void honorsConfiguration() throws IOException {
        InputStream in = new ByteArrayInputStream(utf8("// c\n[1]"));
        JoltParser parser = Jolt.parse(in, JoltConfig.strict().withComments());
        assertArrayEquals(new Token[]{Token.START_ARRAY, Token.VALUE_NUMBER_INT, Token.END_ARRAY},
                tokensOf(parser));
    }

    @Test
    void rejectsInvalidDocumentsLikeTheFastPath() {
        assertThrows(JsonParseException.class, () ->
                drain(new ByteArrayInputStream(utf8("[1,]"))));
    }

    @Test
    void propagatesIoExceptions() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
        assertThrows(IOException.class, () -> Jolt.parse(broken));
    }

    private static Token[] tokensOf(JoltParser parser) {
        java.util.List<Token> out = new java.util.ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out.toArray(new Token[0]);
    }
}
