package io.github.chutian0610.jolt.tree;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The M2 tracer: parse → tree → JSON, plus the core navigation shape. */
class TreeTracerTest {

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static final String DOC = "{\"a\":1,\"tags\":[\"x\"],\"ok\":true,\"nope\":null}";

    @Test
    void parseTreeToJsonIsContentIdentical() {
        assertEquals(DOC, Tree.parse(utf8(DOC)).toJson());
    }

    @Test
    void navigationShapeWorks() {
        Node root = Tree.parse(utf8(DOC));
        assertEquals(TypeTag.OBJECT, root.type());
        assertEquals(TypeTag.NUMBER, root.get("a").type());
        assertEquals("1", root.get("a").numberText());
        assertEquals(TypeTag.ARRAY, root.get("tags").type());
        assertEquals(1, root.get("tags").size());
        assertEquals(TypeTag.STRING, root.get("tags").get(0).type());
        assertEquals(TypeTag.BOOLEAN, root.get("ok").type());
        assertEquals(TypeTag.NULL, root.get("nope").type());
        assertNull(root.get("absent"));
        assertNull(root.get("tags").get(5));
    }

    @Test
    void whitespaceSourceSerializesCompact() {
        Node tree = Tree.parse(utf8("{ \"a\" : 1 }"));
        assertEquals("{\"a\":1}", tree.toJson());
    }

    @Test
    void containerOperationsOnWrongNodeTypeThrow() {
        Node scalar = Tree.parse(utf8("42"));
        assertThrows(IllegalStateException.class, () -> scalar.put("k", Node.object()));
    }

    @Test
    void emptyAndSiblingContainers() {
        assertEquals("[]", Tree.parse(utf8("[]")).toJson());
        assertEquals("{}", Tree.parse(utf8("{}")).toJson());
        assertEquals("[[1],[2]]", Tree.parse(utf8("[[1],[2]] ")).toJson());
    }

    @Test
    void integerBeyondLongRangeStillWrites() {
        String doc = "{\"big\":99999999999999999999}";
        assertEquals(doc, Tree.parse(utf8(doc)).toJson());
    }
}
