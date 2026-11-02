package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.gen.GeneratedBinders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The fast-lane pair #60/#61 — the direct read's collection edges, both grades (the generated
 * binder reads collections through the same door as the reflective one; the M8 inline form
 * joins the matrix in #57):
 *
 * <ul>
 *   <li>#60: primitive-component arrays — the door built every array with {@code arraycopy}
 *       from the Object[] scratch, an {@code ArrayStoreException} for {@code double[]}/…;</li>
 *   <li>#61: an element failure left the cursor inside the container, the enclosing field
 *       loop drifted past the root's closer and {@code nextToken()} returned EOF forever —
 *       every element-error cell below runs under a preemptive timeout.</li>
 * </ul>
 *
 * Each cell asserts the tree path's message and path (the direct read's contract) and, where
 * a later-declared field follows the failing collection in the document, that the field was
 * still reached (its own error wins the declaration-order sweep — alignment proven).
 */
class DirectCollectionEdgesTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);

    public record Prims(int[] ints, long[] longs, double[] doubles, boolean[] flags,
                        String[] names) {}

    public record Tagged(int a, List<String> tags) {}

    public record Nested(int a, List<List<String>> grid) {}

    public record Item(String sku, int quantity) {}

    public record Cart(int a, List<Item> items) {}

    public record Labelled(int a, java.util.Map<String, Integer> labels) {}

    private static final String PRIMS = "{\"ints\":[1,-2,3],\"longs\":[9007199254740993],"
            + "\"doubles\":[0.5,1.5],\"flags\":[true,false],\"names\":[\"a\",\"b\"]}";

    @Test
    void primitiveArraysBindThroughBothDirectGrades() {
        byte[] json = PRIMS.getBytes(StandardCharsets.UTF_8);
        Prims tree = Bind.parse(json, Prims.class, BindConfig.defaults());
        Prims reflective = DirectBinder.forClass(Prims.class)
                .parseDirect(json, BindConfig.defaults().withDirect());
        assertSame(tree, reflective);
        GeneratedBinders.forType(Prims.class);
        Prims generated = Bind.parse(json, Prims.class, BindConfig.defaults().withDirect());
        assertSame(tree, generated);
    }

    @Test
    void elementFailuresRaiseTheTreePathsErrorAndKeepTheFieldLoopAligned() {
        // (type, document, the expected FIRST error's path — a later-declared field's own
        // error where the document places it after the failing collection)
        record Cell(Class<?> type, String json, String path) {}
        Cell[] cells = {
                // the null against a primitive array component: the element's own path
                new Cell(Prims.class, "{\"ints\":[1,null]}", "$.ints[1]"),
                // a scalar element tag error; "a" follows and is bad itself — declared first,
                // so ITS error wins only if the loop re-aligned after the array
                new Cell(Tagged.class, "{\"tags\":[\"x\",1],\"a\":\"bad\"}", "$.a"),
                new Cell(Tagged.class, "{\"a\":1,\"tags\":[\"x\",1]}", "$.tags[1]"),
                // nested collection element error, then the aligned later field
                new Cell(Nested.class, "{\"grid\":[[\"a\"],[1]],\"a\":\"bad\"}", "$.a"),
                new Cell(Nested.class, "{\"a\":1,\"grid\":[[\"a\"],[1,\"b\"]]}", "$.grid[1][0]"),
                // a record element's field error, then the aligned later field
                new Cell(Cart.class, "{\"items\":[{\"sku\":\"s\",\"quantity\":\"x\"}],\"a\":\"bad\"}",
                        "$.a"),
                new Cell(Cart.class, "{\"a\":1,\"items\":[{\"sku\":\"s\"},{\"sku\":\"t\",\"quantity\":\"x\"}]}",
                        "$.items[0].quantity"),
                // a map value error, then the aligned later field
                new Cell(Labelled.class, "{\"labels\":{\"k\":\"v\"},\"a\":\"bad\"}", "$.a"),
                new Cell(Labelled.class, "{\"a\":1,\"labels\":{\"k\":1,\"j\":\"v\"}}", "$.labels.j"),
        };
        for (Cell cell : cells) {
            byte[] json = cell.json.getBytes(StandardCharsets.UTF_8);
            JsonBindException viaTree = assertThrows(JsonBindException.class,
                    () -> Bind.parse(json, cell.type, BindConfig.defaults()), cell.json);
            assertEquals(cell.path, viaTree.getPath(), "tree: " + cell.json);
            JsonBindException viaReflective = assertTimeoutPreemptively(BUDGET,
                    () -> assertThrows(JsonBindException.class,
                            () -> DirectBinder.forClass(cell.type)
                                    .parseDirect(json, BindConfig.defaults().withDirect())),
                    "reflective direct hung: " + cell.json);
            assertEquals(viaTree.getMessage(), viaReflective.getMessage(), cell.json);
            assertEquals(viaTree.getPath(), viaReflective.getPath(), cell.json);
            GeneratedBinders.forType(cell.type);
            JsonBindException viaGenerated = assertTimeoutPreemptively(BUDGET,
                    () -> assertThrows(JsonBindException.class,
                            () -> Bind.parse(json, cell.type, BindConfig.defaults().withDirect())),
                    "generated direct hung: " + cell.json);
            assertEquals(viaTree.getMessage(), viaGenerated.getMessage(), cell.json);
            assertEquals(viaTree.getPath(), viaGenerated.getPath(), cell.json);
        }
    }

    private static void assertSame(Prims expected, Prims actual) {
        assertArrayEquals(expected.ints(), actual.ints());
        assertArrayEquals(expected.longs(), actual.longs());
        assertArrayEquals(expected.doubles(), actual.doubles());
        assertArrayEquals(expected.flags(), actual.flags());
        assertArrayEquals(expected.names(), actual.names());
    }
}
