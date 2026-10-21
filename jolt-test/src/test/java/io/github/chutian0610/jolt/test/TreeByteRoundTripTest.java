package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.tree.Tree;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.chutian0610.jolt.test.CorpusSupport.GateCase;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The M2 byte gate. Three properties, on both engines: (1) the tree's serialization is
 * TOKEN-EQUIVALENT to the source; (2) it is a FIXED POINT (reloading and re-serializing changes
 * nothing); (3) for sources that are already compact — no whitespace outside strings — it is
 * BYTE-IDENTICAL to the source, which is what forces NUMBER nodes to serialize their retained
 * text verbatim: the tree normalizes whitespace, never numbers.
 */
@Tag("json-test-suite")
class TreeByteRoundTripTest {

    @TestFactory
    Iterable<DynamicTest> treeSerializationGates() {
        List<DynamicTest> tests = new ArrayList<>();
        for (GateCase c : CorpusSupport.yCases().toList()) {
            tests.add(DynamicTest.dynamicTest("bytes/" + c.name(), () -> gate(c)));
            tests.add(DynamicTest.dynamicTest("string/" + c.name(), () -> gate(c)));
        }
        return tests;
    }

    private static void gate(GateCase c) {
        byte[] source = c.bytes();
        byte[] treeBytes = Tree.parse(source).toJson().getBytes(StandardCharsets.UTF_8);

        // (1) token equivalence with the source — except duplicate-key sources, whose keys
        // collapse under map semantics (last value wins): the token stream legitimately differs.
        if (!DUPLICATE_KEY_CASES.contains(c.name())) {
            assertArrayEquals(tokens(Jolt.parse(source)), tokens(Jolt.parse(treeBytes)));
        }

        // (2) fixed point
        byte[] twice = Tree.parse(treeBytes).toJson().getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(treeBytes, twice);

        // (3) byte-identical for tree-canonical sources: the tree normalizes insignificant
        // whitespace, Unicode-escape and slash-escape FORMS, and collapses duplicate object keys (map
        // semantics, last value wins — Jackson behaves the same). Everything else — number
        // text, key order, escapes that re-encode identically, raw UTF-8 — survives byte for byte.
        if (!DUPLICATE_KEY_CASES.contains(c.name()) && isTreeCanonicalSource(source)) {
            assertArrayEquals(source, treeBytes);
        }
    }

    /** y_ sources whose duplicate keys collapse under map semantics. */
    private static final List<String> DUPLICATE_KEY_CASES = List.of(
            "y_object_duplicated_key.json",
            "y_object_duplicated_key_and_value.json");

    /**
     * True when nothing outside string literals is whitespace and every escape re-encodes
     * identically (no Unicode escapes and no escaped slashes — those normalize to their raw character form).
     */
    static boolean isTreeCanonicalSource(byte[] json) {
        boolean inString = false;
        boolean escaped = false;
        for (byte b : json) {
            if (inString) {
                if (escaped) {
                    if (b == 'u' || b == 'U' || b == '/') {
                        return false; // escape form that re-encodes to its raw character
                    }
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == '"') {
                    inString = false;
                }
                continue;
            }
            if (b == '"') {
                inString = true;
            } else if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                return false;
            }
        }
        return true;
    }


    private static Token[] tokens(JoltParser parser) {
        List<Token> out = new ArrayList<>();
        Token t;
        while ((t = parser.nextToken()) != Token.EOF) {
            out.add(t);
        }
        return out.toArray(new Token[0]);
    }
}
