package io.github.chutian0610.jolt.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JsonParseException;
import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.Tree;
import io.github.chutian0610.jolt.tree.TypeTag;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.github.chutian0610.jolt.test.CorpusSupport.GateCase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The M2 differential gate: the TREE is compared against Jackson's tree on the whole corpus
 * (canonical value equality, same normalization and recorded-divergence discipline as the M1
 * differential gate). The tree layer must not drift from an independent implementation.
 */
@Tag("json-test-suite")
class TreeDifferentialTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    @TestFactory
    Iterable<DynamicTest> jacksonDifferentialOnTrees() {
        List<DynamicTest> tests = new ArrayList<>();
        for (GateCase c : CorpusSupport.verdictCases().toList()) {
            tests.add(DynamicTest.dynamicTest(c.name(), () -> {
                JsonNode jackson;
                try {
                    jackson = JACKSON.readTree(c.bytes());
                } catch (IOException jacksonRejected) {
                    assertFalse(c.accept(), c.name() + ": Jolt tree accepts, Jackson rejects");
                    return;
                }
                if (CorpusSupport.RECORDED_DIVERGENCE.contains(c.name())) {
                    return;
                }
                // Jackson's readTree ignores trailing document content and accepts; Jolt
                // enforces exactly-one-value and rejects. The corpus (n_ = reject) sides with
                // Jolt: pin the rejection instead of comparing trees.
                Node joltTree;
                try {
                    joltTree = Tree.parse(c.bytes());
                } catch (JsonParseException joltRejected) {
                    if (c.accept()) {
                        throw new AssertionError(
                                c.name() + ": corpus expects accept but the tree rejects", joltRejected);
                    }
                    return;
                }
                assertEquals(canonicalJackson(jackson), canonicalNode(joltTree),
                        c.name() + ": canonical trees diverge");
            }));
        }
        return tests;
    }

    // --- canonicalization: identical rules on both sides ---

    private static Object canonicalJackson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> out = new TreeMap<>();
            node.fields().forEachRemaining(e -> out.put(e.getKey(), canonicalJackson(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(n -> out.add(canonicalJackson(n)));
            return out;
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.textValue();
    }

    private static Object canonicalNode(Node node) {
        return switch (node.type()) {
            case OBJECT -> {
                Map<String, Object> out = new TreeMap<>();
                for (int i = 0; i < node.size(); i++) {
                    out.put(node.keys().get(i), canonicalNode(node.elements().get(i)));
                }
                yield out;
            }
            case ARRAY -> {
                List<Object> out = new ArrayList<>();
                for (Node child : node.elements()) {
                    out.add(canonicalNode(child));
                }
                yield out;
            }
            case STRING -> node.asText();
            case NUMBER -> node.asDecimal().stripTrailingZeros().toPlainString();
            case BOOLEAN -> node.asBoolean();
            case NULL -> null;
        };
    }
}
