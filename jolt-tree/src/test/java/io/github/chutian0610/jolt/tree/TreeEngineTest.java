package io.github.chutian0610.jolt.tree;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Engine parity and config/depth propagation through the tree entry points. */
class TreeEngineTest {

    private static final String DOC = "{\"k\":\"héllo 😀\",\"n\":9007199254740993,\"a\":[1,2]}";

    @Test
    void bothEnginesProduceEqualTrees() {
        Node fromBytes = Tree.parse(DOC.getBytes(StandardCharsets.UTF_8));
        Node fromString = Tree.parse(DOC);
        assertEquals(fromBytes, fromString);
        assertEquals(fromBytes.hashCode(), fromString.hashCode());
    }

    @Test
    void valueEqualityIsOrderIndependentForObjects() {
        Node a = Tree.parse("{\"x\":1,\"y\":[1,2]}".getBytes(StandardCharsets.UTF_8));
        Node b = Tree.parse("{\"y\":[1,2],\"x\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        // numbers compare numerically: 1.0 equals 1
        Node c = Tree.parse("{\"x\":1.0,\"y\":[1,2]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(a, c);
    }

    @Test
    void lenientConfigComposesThroughTreeParse() {
        String doc = "// note\n[1,]";
        org.junit.jupiter.api.Assertions.assertThrows(io.github.chutian0610.jolt.JsonParseException.class,
                () -> Tree.parse(doc.getBytes(StandardCharsets.UTF_8)));
        Node lenient = Tree.parse(doc.getBytes(StandardCharsets.UTF_8),
                io.github.chutian0610.jolt.JoltConfig.strict().withComments().withTrailingCommas());
        assertEquals(1, lenient.size());
    }

    @Test
    void depthLimitInheritedUnchanged() {
        var shallow = io.github.chutian0610.jolt.JoltConfig.strict().withMaxDepth(2);
        assertEquals(TypeTag.ARRAY, Tree.parse("[[1]]".getBytes(StandardCharsets.UTF_8), shallow).type());
        assertThrows(io.github.chutian0610.jolt.JsonParseException.class,
                () -> Tree.parse("[[[1]]]".getBytes(StandardCharsets.UTF_8), shallow));
    }
}
