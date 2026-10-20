package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.Token;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.chutian0610.jolt.test.CorpusSupport.GateCase;
import static io.github.chutian0610.jolt.test.CorpusSupport.tokens;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Round-trip gate (T6): every y_ corpus case is parsed, rewritten through the JoltWriter, and
 * re-parsed — the regenerated token stream must equal the original, and rewriting the rewrite
 * must be a fixed point (stable canonical form). Runs on both engines.
 */
@Tag("json-test-suite")
class RoundTripTest {

    @TestFactory
    Iterable<DynamicTest> yCasesRoundTripThroughTheWriter() {
        List<DynamicTest> tests = new ArrayList<>();
        for (GateCase c : CorpusSupport.yCases().toList()) {
            tests.add(DynamicTest.dynamicTest("bytes/" + c.name(), () -> {
                byte[] rewritten = rewrite(c.bytes());
                assertArrayEquals(tokens(Jolt.parse(c.bytes())), tokens(Jolt.parse(rewritten)), c.name());
                assertArrayEquals(rewrite(rewritten), rewritten, "canonical form must be a fixed point: " + c.name());
            }));
            tests.add(DynamicTest.dynamicTest("string/" + c.name(), () -> {
                String orig = new String(c.bytes(), StandardCharsets.UTF_8);
                byte[] rewritten = rewriteUtf16(orig);
                assertArrayEquals(tokens(Jolt.parse(orig)),
                        tokens(Jolt.parse(new String(rewritten, StandardCharsets.UTF_8))), c.name());
                assertArrayEquals(rewriteUtf16(new String(rewritten, StandardCharsets.UTF_8)), rewritten,
                        "canonical form must be a fixed point: " + c.name());
            }));
        }
        return tests;
    }

    /** Parses UTF-8 bytes and re-emits the full token stream through a fresh writer. */
    static byte[] rewrite(byte[] json) {
        JoltParser parser = Jolt.parse(json);
        JoltWriter writer = Jolt.write();
        copyTokens(parser, writer);
        return writer.toByteArray();
    }

    /** Parses a String (UTF-16 engine) and re-emits through a fresh writer. */
    static byte[] rewriteUtf16(String json) {
        JoltParser parser = Jolt.parse(json);
        JoltWriter writer = Jolt.write();
        copyTokens(parser, writer);
        return writer.toByteArray();
    }

    private static void copyTokens(JoltParser parser, JoltWriter writer) {
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            switch (t) {
                case START_OBJECT -> writer.startObject();
                case END_OBJECT -> writer.endObject();
                case START_ARRAY -> writer.startArray();
                case END_ARRAY -> writer.endArray();
                case FIELD_NAME -> writer.fieldName(parser.textValue());
                case VALUE_STRING -> writer.value(parser.textValue());
                case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                    Number n = parser.numberValue();
                    if (n instanceof Long l) {
                        writer.value(l);
                    } else if (n instanceof Double d) {
                        writer.value(d);
                    } else {
                        writer.value((BigDecimal) n);
                    }
                }
                case VALUE_TRUE -> writer.value(true);
                case VALUE_FALSE -> writer.value(false);
                case VALUE_NULL -> writer.nullValue();
                default -> throw new AssertionError("unexpected token " + t);
            }
        }
    }
}
