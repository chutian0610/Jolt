package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.Token;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip gate (T6): every y_ corpus case is parsed, rewritten through the JoltWriter, and
 * re-parsed — the regenerated token stream must equal the original, and rewriting the rewrite
 * must be a fixed point (stable canonical form). Runs on both engines.
 */
@Tag("json-test-suite")
class RoundTripTest {

    @TestFactory
    Iterable<DynamicTest> yCasesRoundTripThroughTheWriter() {
        File dir = corpusDir();
        File[] corpus = dir.listFiles((d, name) -> name.startsWith("y_") && name.endsWith(".json"));
        if (corpus == null) {
            throw new IllegalStateException("Cannot list corpus directory " + dir);
        }
        Arrays.sort(corpus);
        List<DynamicTest> tests = new ArrayList<>();
        for (File f : corpus) {
            tests.add(DynamicTest.dynamicTest("bytes/" + f.getName(), () -> {
                byte[] orig = Files.readAllBytes(f.toPath());
                assertRoundTrip(orig);
            }));
            tests.add(DynamicTest.dynamicTest("string/" + f.getName(), () -> {
                String orig = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                byte[] rewritten = rewriteUtf16(orig);
                assertEquals(tokensUtf16(orig), tokensUtf16Bytes(rewritten), f.getName());
                assertArrayEquals(rewriteUtf16(new String(rewritten, StandardCharsets.UTF_8)), rewritten,
                        "canonical form must be a fixed point: " + f.getName());
            }));
        }
        return tests;
    }

    private static void assertRoundTrip(byte[] orig) throws IOException {
        byte[] rewritten = rewrite(orig);
        assertEquals(tokens(orig), tokens(rewritten));
        assertArrayEquals(rewrite(rewritten), rewritten, "canonical form must be a fixed point");
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

    private static List<Token> tokens(byte[] json) {
        JoltParser parser = Jolt.parse(json);
        List<Token> out = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out;
    }

    private static List<Token> tokensUtf16(String json) {
        JoltParser parser = Jolt.parse(json);
        List<Token> out = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out;
    }

    private static List<Token> tokensUtf16Bytes(byte[] json) {
        return tokensUtf16(new String(json, StandardCharsets.UTF_8));
    }

    private static File corpusDir() {
        File dir = new File(System.getProperty("jolt.jsontestsuite.dir", ""));
        if (!dir.isDirectory()) {
            throw new IllegalStateException("JSONTestSuite corpus not found at " + dir);
        }
        return dir;
    }
}
