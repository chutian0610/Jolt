package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.Tree;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;

import static io.github.chutian0610.jolt.test.CorpusSupport.GateCase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Engine parity at the tree layer (T17): every corpus case loads through BOTH engines and the
 * byte path and the strict-decoded String path produce EQUAL trees — or both reject. The tree
 * must not see a difference between the engines.
 *
 * <p>Recorded tree-level divergence from token-level verdicts: escaped lone surrogates parse
 * as tokens but CANNOT become a tree (strings materialize during loading), so these
 * accept-recorded cases are tree-level rejections — required to throw on BOTH engines, which
 * is the parity this gate asserts for them.
 */
@Tag("json-test-suite")
class TreeParityTest {

    /** Escaped-lone-surrogate i_ cases: token-level accept, tree-level reject (both engines). */
    private static final List<String> TREE_MATERIALIZES_NOT = List.of(
            "i_object_key_lone_2nd_surrogate.json",
            "i_string_1st_surrogate_but_2nd_missing.json",
            "i_string_1st_valid_surrogate_2nd_invalid.json",
            "i_string_incomplete_surrogate_and_escape_valid.json",
            "i_string_incomplete_surrogate_pair.json",
            "i_string_incomplete_surrogates_escape_valid.json",
            "i_string_invalid_lonely_surrogate.json",
            "i_string_invalid_surrogate.json",
            "i_string_inverted_surrogates_U+1D11E.json",
            "i_string_lone_second_surrogate.json");

    @TestFactory
    Iterable<DynamicTest> treesMatchAcrossEngines() {
        List<DynamicTest> tests = new ArrayList<>();
        for (GateCase c : CorpusSupport.verdictCases().toList()) {
            tests.add(DynamicTest.dynamicTest(c.name(), () -> {
                if (!c.accept()) {
                    assertThrows(JsonParseException.class, () -> Tree.parse(c.bytes()), c.name());
                    assertStringPathRejects(c);
                    return;
                }
                if (TREE_MATERIALIZES_NOT.contains(c.name())) {
                    // Tree-level rejection policy: strings must materialize to enter a tree.
                    assertThrows(JsonParseException.class, () -> Tree.parse(c.bytes()), c.name());
                    assertStringPathRejects(c);
                    return;
                }
                Node fromBytes = Tree.parse(c.bytes());
                Node fromString = Tree.parse(CorpusSupport.decodeStrict(c.bytes()));
                assertEquals(fromBytes, fromString, c.name());
            }));
        }
        return tests;
    }

    /** The String path rejects a case when it decodes strictly AND the tree rejects the text;
     * bytes that cannot decode at all are already a String-path rejection. */
    private static void assertStringPathRejects(GateCase c) {
        String text;
        try {
            text = CorpusSupport.decodeStrict(c.bytes());
        } catch (CharacterCodingException undecodable) {
            return; // rejection by undecodability — matches the dual-engine replay semantics
        }
        assertThrows(JsonParseException.class, () -> Tree.parse(text), c.name());
    }
}
