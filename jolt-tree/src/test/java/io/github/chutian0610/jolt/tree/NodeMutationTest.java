package io.github.chutian0610.jolt.tree;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Factories, fluent mutation, the three-null contract, and value equality. */
class NodeMutationTest {

    @Test
    void fluentChainReadsAsADocument() {
        Node doc = Node.object()
                .put("name", "jolt")
                .put("count", 42)
                .put("ratio", 2.5)
                .put("ok", true)
                .put("exact", new BigDecimal("0.1000000000000000000001"))
                .put("tags", Node.array().add("a").add(7).add(false))
                .putNull("nope");
        assertEquals("{\"name\":\"jolt\",\"count\":42,\"ratio\":2.5,\"ok\":true,"
                        + "\"exact\":0.1000000000000000000001,\"tags\":[\"a\",7,false],\"nope\":null}",
                doc.toJson());
    }

    @Test
    void factoriesCarryTheRightTagsAndText() {
        assertEquals(TypeTag.STRING, Node.of("s").type());
        assertEquals(TypeTag.NUMBER, Node.of(42L).type());
        assertEquals("42", Node.of(42L).numberText());
        assertEquals("2.5", Node.of(2.5).numberText());
        assertEquals("0.1", Node.of(new BigDecimal("0.1")).numberText());
        assertEquals(TypeTag.BOOLEAN, Node.of(true).type());
        assertEquals(TypeTag.NULL, Node.nullValue().type());
        assertEquals(TypeTag.OBJECT, Node.object().type());
        assertEquals(TypeTag.ARRAY, Node.array().type());
    }

    @Test
    void threeNullContractIsPinned() {
        Node doc = Node.object();
        // typed argument: Java null is the caller's bug
        assertThrows(NullPointerException.class, () -> doc.put("a", (Node) null));
        // raw overload: Java null becomes the JSON null literal
        doc.put("b", (String) null);
        assertEquals(TypeTag.NULL, doc.get("b").type());
        // explicit form
        doc.putNull("c");
        assertEquals(TypeTag.NULL, doc.get("c").type());
        // arrays behave the same
        Node arr = Node.array();
        assertThrows(NullPointerException.class, () -> arr.add((Node) null));
        arr.add((String) null);
        assertEquals(TypeTag.NULL, arr.get(0).type());
        // missing stays distinct from all of them
        assertNull(doc.get("absent"));
    }

    @Test
    void putReplacesValueKeepsFirstInsertionOrder() {
        Node doc = Node.object().put("a", 1).put("b", 2).put("a", 99);
        assertEquals("{\"a\":99,\"b\":2}", doc.toJson());
        assertSame(doc.get("a"), doc.get("a"));
        assertEquals(99L, doc.get("a").asLong());
    }

    @Test
    void equalityIsValueSemantics() {
        Node a = Tree.parse("{\"x\":1,\"y\":[1,2]}"
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(a, Tree.parse("{ \"y\" : [1, 2] , \"x\" : 1 }"
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(a.hashCode(), Tree.parse("{\"y\":[1,2],\"x\":1}"
                .getBytes(StandardCharsets.UTF_8)).hashCode());
        // numbers compare numerically: 1.0 equals 1, same hash via the stripped form
        Node numeric = Tree.parse("{\"x\":1.0,\"y\":[1,2.0]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(a, numeric);
        assertEquals(a.hashCode(), numeric.hashCode());
        // and unequal cases really are unequal
        assertNotEquals(a, Tree.parse("{\"x\":2,\"y\":[1,2]}".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(a, Tree.parse("{\"x\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void toStringRoundTripsToAnEqualTree() {
        Node built = Node.object()
                .put("k", "a\"b\\c\u001fd\u6f22")
                .put("n", -9007199254740993L)
                .put("arr", Node.array().add(Node.nullValue()).add(new BigDecimal("-123.45e2")));
        Node reloaded = Tree.parse(built.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(built, reloaded);
        assertEquals(built.hashCode(), reloaded.hashCode());
    }
}
