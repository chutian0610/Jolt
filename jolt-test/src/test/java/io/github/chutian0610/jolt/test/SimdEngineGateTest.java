package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltConfig;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.SimdUtf8Parser;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.Utf8Parser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third engine's parity matrices against the scalar engine (M9-T2, spec #62): value
 * materialization, error semantics, depth and lenient knobs, the exhaustive BMP escape
 * round-trip, and the reset-reuse contract — everything the dual-engine gates pin, now across
 * three engines. Plus the factory hook's dispatch contract: install routes the
 * {@code JoltParser}-typed UTF-8 entries to the SIMD engine, uninstall restores scalar, and
 * the concrete reuse entries stay scalar by contract.
 */
class SimdEngineGateTest {

    // --- value materialization parity ---

    /** One document carrying every value shape: escapes, BMP/astral, all number widths. */
    private static final String VALUES_DOC = """
            {"s":"plain","esc":"a\\"b\\\\c\\u00e9\\uD83D\\uDE00","i":42,"neg":-9007199254740993,
             "big":123456789012345678901234567890,"f":3.25,"exp":1.5e300,"t":true,"x":null,
             "name\\u00e9":"value with \\t tab and \\u0000 nul","arr":[1,[2,[3]]]}""";

    @Test
    void valueMaterializationMatchesScalar() {
        byte[] doc = VALUES_DOC.getBytes(StandardCharsets.UTF_8);
        Utf8Parser scalar = Jolt.utf8Parser(doc);
        SimdUtf8Parser simd = new SimdUtf8Parser(doc);
        int tokens = 0;
        while (true) {
            Token t = simd.nextToken();
            Token expected = scalar.nextToken();
            assertEquals(expected, t, "token #" + (tokens + 1));
            if (t == Token.EOF) {
                break;
            }
            tokens++;
            if (t == Token.VALUE_STRING || t == Token.FIELD_NAME) {
                assertEquals(scalar.textValue(), simd.textValue(), "textValue #" + tokens);
                assertEquals(scalar.rawText(), simd.rawText(), "rawText #" + tokens);
            } else if (t == Token.VALUE_NUMBER_INT || t == Token.VALUE_NUMBER_FLOAT) {
                assertEquals(scalar.rawText(), simd.rawText(), "rawText #" + tokens);
                assertEquals(scalar.numberText(), simd.numberText(), "numberText #" + tokens);
                assertEquals(scalar.bigDecimalValue(), simd.bigDecimalValue(), "bigDecimalValue #" + tokens);
                Number scalarNumber = scalar.numberValue();
                assertEquals(scalarNumber, simd.numberValue(), "numberValue #" + tokens);
                // longValue is the fits-in-long contract: only exercised when numberValue held a Long.
                if (scalarNumber instanceof Long) {
                    assertEquals(scalarNumber.longValue(), simd.longValue(), "longValue #" + tokens);
                }
            }
        }
        assertTrue(tokens > 10, "the fixture doc should carry a real token stream");
    }

    // --- error / depth / lenient parity ---

    /** Scan-level rejections (nothing here relies on materialization-time failures). */
    private static final String[] REJECTIONS = {
            "{\"a\":1,}", "[1,]", "{\"a\" 1}", "{\"a\":}", "[", "{\"a\":1", "tru", "nul", "fals",
            "01", "1.", ".5", "1e", "-", "--1", "\"unterminated", "\"bad\\qescape\"", "\"\\u12\"",
            "{\"a\"::1}", "[1 2]", "{\"a\":1}tail", "{}{}", "\uFEFF{}", "{\"a\":01}",
    };

    @Test
    void rejectionsMatchScalarDiagnostics() {
        for (String doc : REJECTIONS) {
            byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
            JsonParseException scalar = captureFailure(Jolt.utf8Parser(bytes));
            JsonParseException simd = captureFailure(new SimdUtf8Parser(bytes));
            assertNotNull(scalar, "scalar should reject: " + doc);
            assertNotNull(simd, "simd should reject: " + doc);
            assertEquals(scalar.getMessage(), simd.getMessage(), doc);
        }
    }

    private static JsonParseException captureFailure(JoltParser parser) {
        try {
            CorpusSupport.drain(parser);
            return null;
        } catch (JsonParseException e) {
            return e;
        }
    }

    @Test
    void depthLimitParity() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            deep.append('[');
        }
        byte[] doc = deep.toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(captureFailure(Jolt.utf8Parser(doc)).getMessage(),
                captureFailure(new SimdUtf8Parser(doc)).getMessage());
        JoltConfig limit8 = JoltConfig.strict().withMaxDepth(8);
        assertEquals(captureFailure(Jolt.utf8Parser(doc, limit8)).getMessage(),
                captureFailure(new SimdUtf8Parser(doc, limit8)).getMessage());
    }

    @Test
    void lenientKnobsParity() {
        byte[] doc = "[1, /* c */ 2,]".getBytes(StandardCharsets.UTF_8);
        JoltConfig lenient = JoltConfig.strict().withComments().withTrailingCommas();
        assertEquals(CorpusSupport.tokens(Jolt.utf8Parser(doc, lenient)).length,
                CorpusSupport.tokens(new SimdUtf8Parser(doc, lenient)).length);
        // Strict default rejects both knobs' syntax on both engines.
        assertThrows(JsonParseException.class, () -> CorpusSupport.drain(Jolt.utf8Parser(doc)));
        assertThrows(JsonParseException.class, () -> CorpusSupport.drain(new SimdUtf8Parser(doc)));
    }

    // --- exhaustive escape round-trip (scalar vs simd) ---

    @Test
    void everyBmpScalarCharRoundTripsThroughBothEngines() {
        for (int c = 0; c <= 0xFFFF; c++) {
            if (c >= 0xD800 && c <= 0xDFFF) {
                continue; // not scalar characters
            }
            assertRoundTrip(String.valueOf((char) c));
        }
    }

    @Test
    void astralBoundaryCodePointsRoundTrip() {
        for (int cp : new int[]{0x10000, 0x10001, 0x1D11E, 0x1F600, 0x10FFFC, 0x10FFFD, 0x10FFFE, 0x10FFFF}) {
            assertRoundTrip(new String(Character.toChars(cp)));
        }
    }

    private static void assertRoundTrip(String original) {
        byte[] written = Jolt.write().value(original).toByteArray();

        JoltParser utf8 = Jolt.parse(written);
        assertEquals(Token.VALUE_STRING, utf8.nextToken(), "char U+" + hex(original) + " (utf8)");
        assertEquals(original, utf8.textValue(), "char U+" + hex(original) + " (utf8)");

        JoltParser simd = new SimdUtf8Parser(written);
        assertEquals(Token.VALUE_STRING, simd.nextToken(), "char U+" + hex(original) + " (simd)");
        assertEquals(original, simd.textValue(), "char U+" + hex(original) + " (simd)");
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append(String.format("%04X ", (int) s.charAt(i)));
        }
        return sb.toString().trim();
    }

    // --- the vector paths at every word alignment ---

    /**
     * The clean run's only risk surface is the word arithmetic ({@code pos += firstTrue()}):
     * every special byte class placed at every offset across several vector words, inside
     * documents long enough for the run to engage, must produce the scalar engine's exact
     * outcome — tokens, decoded text, or the rejection message.
     */
    @Test
    void stringCleanRunMatchesScalarAtEveryAlignment() {
        String[] inserts = {
                "", "\\\"", "\\\\", "\\/", "\\n", "\\u00e9", "\\uD83D\\uDE00",  // escapes
                "\u00e9", "\u20ac", "\uD83D\uDE00",                              // raw 2/3/4-byte UTF-8
                "\u0001", "\u001f", "\t",                                        // raw controls
        };
        for (int prefix = 0; prefix <= 72; prefix++) {
            for (int suffix : new int[]{0, 1, 7, 15, 16, 17, 33}) {
                for (String insert : inserts) {
                    assertSameOutcome(("[\"" + "a".repeat(prefix) + insert + "b".repeat(suffix)
                            + "\",1]").getBytes(StandardCharsets.UTF_8));
                }
            }
            // unterminated at every offset (with and without a trailing escape start)
            assertSameOutcome(("[\"" + "a".repeat(prefix)).getBytes(StandardCharsets.UTF_8));
            assertSameOutcome(("[\"" + "a".repeat(prefix) + "\\").getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Invalid UTF-8 inside the run's reach: overlong, encoded surrogate, beyond U+10FFFF, lone continuation, truncation. */
    @Test
    void invalidUtf8InsideTheRunMatchesScalar() {
        byte[][] bad = {
                {(byte) 0xC0, (byte) 0x80}, {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
                {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0x80},
                {(byte) 0xE2, (byte) 0x82}, {(byte) 0xF5}, {(byte) 0xFF},
        };
        for (int prefix = 0; prefix <= 40; prefix++) {
            for (byte[] seq : bad) {
                byte[] head = ("[\"" + "a".repeat(prefix)).getBytes(StandardCharsets.UTF_8);
                byte[] tail = "zzzzzzzzzzzzzzzzzzzz\",1]".getBytes(StandardCharsets.UTF_8);
                byte[] doc = new byte[head.length + seq.length + tail.length];
                System.arraycopy(head, 0, doc, 0, head.length);
                System.arraycopy(seq, 0, doc, head.length, seq.length);
                System.arraycopy(tail, 0, doc, head.length + seq.length, tail.length);
                assertSameOutcome(doc);
            }
        }
    }

    /** The whitespace run at every alignment, every whitespace byte, and run-to-end. */
    @Test
    void whitespaceRunMatchesScalarAtEveryAlignment() {
        String[] pads = {" ", "\t", "\n", "\r", " \t\n\r"};
        for (int n = 0; n <= 70; n++) {
            for (String pad : pads) {
                String ws = pad.repeat(n);
                assertSameOutcome(("[" + ws + "1" + ws + "," + ws + "\"s\"" + ws + "]" + ws)
                        .getBytes(StandardCharsets.UTF_8));
                assertSameOutcome(("{" + ws + "\"k\"" + ws + ":" + ws + "x" + ws + "}")
                        .getBytes(StandardCharsets.UTF_8)); // rejection after a run
            }
        }
    }

    /** Same tokens and decoded text to EOF, or the same rejection message, on both engines. */
    private static void assertSameOutcome(byte[] doc) {
        assertEquals(outcome(Jolt.utf8Parser(doc)), outcome(new SimdUtf8Parser(doc)),
                () -> new String(doc, StandardCharsets.UTF_8));
    }

    private static String outcome(JoltParser parser) {
        StringBuilder sb = new StringBuilder();
        try {
            Token t;
            while ((t = parser.nextToken()) != Token.EOF) {
                sb.append(t);
                if (t == Token.VALUE_STRING || t == Token.FIELD_NAME) {
                    sb.append('=').append(parser.rawText());
                    try {
                        sb.append('/').append(parser.textValue());
                    } catch (JsonParseException materialization) {
                        sb.append("/!").append(materialization.getMessage());
                    }
                }
                sb.append(' ');
            }
            return sb.append("EOF").toString();
        } catch (JsonParseException e) {
            return sb.append("rejected: ").append(e.getMessage()).toString();
        }
    }

    // --- reset reuse parity ---

    @Test
    void resetReuseMatchesFreshInstances() {
        // Inline documents (the corpus-dependent reuse matrix runs under the tagged task).
        String[] docs = {
                "{\"a\":1}", "[1,2,[3]]", "{\"k\":\"v\",\"n\":-7,\"f\":2.5,\"b\":false,\"z\":null}",
                "{\"nested\":{\"deep\":{\"deeper\":[true,false,null]}},\"tail\":1}",
                "[\"s\\u00e9\",\"\\uD83D\\uDE00\",123456789012345678901234567890,0.5e-3]",
                "{}", "[]", "\"top-level string\"", "42",
        };
        for (String doc : docs) {
            byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
            SimdUtf8Parser reused = new SimdUtf8Parser(bytes);
            int first = CorpusSupport.tokens(reused).length;
            for (String other : docs) {
                byte[] otherBytes = other.getBytes(StandardCharsets.UTF_8);
                int fresh = CorpusSupport.tokens(new SimdUtf8Parser(otherBytes)).length;
                int again = CorpusSupport.tokens(reused.reset(otherBytes)).length;
                assertEquals(fresh, again, doc + " -> " + other);
            }
            assertEquals(first, CorpusSupport.tokens(reused.reset(bytes)).length, doc);
        }
    }

    // --- the factory hook ---

    @Test
    void hookDispatchesToSimdAndBack() {
        byte[] doc = "{\"a\":[1,true,null]}".getBytes(StandardCharsets.UTF_8);
        int scalarTokens = CorpusSupport.tokens(Jolt.utf8Parser(doc)).length;
        try {
            Jolt.installUtf8ParserFactory(SimdUtf8Parser.factory());
            assertSame(SimdUtf8Parser.class, Jolt.parse(doc).getClass());
            assertSame(SimdUtf8Parser.class, Jolt.parse(doc, 64).getClass());
            assertSame(SimdUtf8Parser.class,
                    Jolt.parse(doc, JoltConfig.strict().withTrailingCommas()).getClass());
            assertTrue(Jolt.tryParse(doc).isSuccess());
            // The simd engine answers through the hook with the same token stream.
            assertEquals(scalarTokens, CorpusSupport.tokens(Jolt.parse(doc)).length);
        } finally {
            Jolt.uninstallUtf8ParserFactory();
        }
        assertSame(Utf8Parser.class, Jolt.parse(doc).getClass());
        assertTrue(Jolt.tryParse(doc).isSuccess());
    }

    @Test
    void concreteReuseEntriesStayScalar() {
        // By contract utf8Parser stays concrete-scalar (its reset-reuse contract cannot honor a
        // factory-produced engine through the concrete type).
        byte[] doc = "[1]".getBytes(StandardCharsets.UTF_8);
        assertSame(Utf8Parser.class, Jolt.utf8Parser(doc).reset(doc).getClass());
    }
}
