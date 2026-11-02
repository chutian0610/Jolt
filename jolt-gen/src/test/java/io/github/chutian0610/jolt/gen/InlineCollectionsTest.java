package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.bind.TypedBinder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The M8 collection-loop inlining (#57, lever #2): the element loop and element reads live
 * in the parent binder body — no door call, record/POJO elements through the monomorphic
 * child body. Every cell below binds through THREE arms and asserts they agree: the M8 form
 * (inlined), the M8-off reference form (the M7 body: String chain + door calls), and the
 * tree path — the error cells byte-for-byte on message AND path (the #57 acceptance).
 * Untyped elements keep the door by design; they bind identically through both forms.
 */
class InlineCollectionsTest {

    public enum Color { RED, GREEN }

    public record Item(String sku, int quantity, BigDecimal price) {}

    public record Scalars(
            List<String> tags,
            List<Integer> ints,
            List<Long> longs,
            List<Boolean> flags,
            List<BigDecimal> decimals,
            List<java.math.BigInteger> bigs,
            List<Color> colors) {}

    /** Array components: record equals compares arrays by identity, so this one asserts
     * element-wise against the tree path. The primitive array keeps the door (#60's build). */
    public record ArraysRec(String[] names, Item[] items, int[] intsArray) {}

    public record Nested(List<List<String>> grid, List<List<Item>> batches) {}

    public record Children(List<Item> items, Map<String, Item> bySku, List<Color> colors) {}

    public record Untyped(List<Object> any, Map<String, Object> dynamic) {}

    public static class PojoCart {
        private List<String> tags;
        private List<Item> items;

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public List<Item> getItems() { return items; }
        public void setItems(List<Item> items) { this.items = items; }
    }

    private static final String SCALARS_JSON = "{\"tags\":[\"a\",\"b\"],"
            + "\"ints\":[1,-2],\"longs\":[9007199254740993,1],"
            + "\"flags\":[true,false],\"decimals\":[1.50,92233720368547758079],"
            + "\"bigs\":[123456789012345678901234567890,7],"
            + "\"colors\":[\"RED\",\"GREEN\"]}";

    private static final String ARRAYS_JSON = "{\"names\":[\"x\",\"y\"],"
            + "\"items\":[{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50}],"
            + "\"intsArray\":[7,8]}";

    private static final String NESTED_JSON = "{\"grid\":[[\"a\"],[\"b\",\"c\"]],"
            + "\"batches\":[[{\"sku\":\"s1\",\"quantity\":1,\"price\":2.5}],"
            + "[{\"sku\":\"s2\",\"quantity\":2,\"price\":3.5}]]}";

    private static final String CHILDREN_JSON = "{\"items\":[{\"sku\":\"s1\",\"quantity\":2,"
            + "\"price\":1.50},{\"sku\":\"s2\",\"quantity\":1,\"price\":92233720368547758079}],"
            + "\"bySku\":{\"s1\":{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50}},"
            + "\"colors\":[\"GREEN\"]}";

    private static final String UNTYPED_JSON = "{\"any\":[1,\"a\",true,null,[2],{\"k\":\"v\"}],"
            + "\"dynamic\":{\"a\":1,\"b\":[true]}}";

    private static final String POJO_JSON = "{\"tags\":[\"a\",\"b\"],"
            + "\"items\":[{\"sku\":\"s1\",\"quantity\":2,\"price\":1.50}]}";

    @Test
    void scalarElementCollectionsInlineThroughAllThreeArms() {
        Scalars m8 = (Scalars) GeneratedBinders.forType(Scalars.class).parseTokens(
                Jolt.utf8Parser(SCALARS_JSON.getBytes(StandardCharsets.UTF_8)),
                BindConfig.defaults());
        Scalars reference = (Scalars) GeneratedBinders.referenceForType(Scalars.class)
                .parseTokens(Jolt.utf8Parser(SCALARS_JSON.getBytes(StandardCharsets.UTF_8)),
                        BindConfig.defaults());
        Scalars tree = Bind.parse(SCALARS_JSON, Scalars.class);
        assertEquals(tree, m8);
        assertEquals(tree, reference);
    }

    @Test
    void arraysInlineElementWiseAgainstTheTreePath() {
        byte[] json = ARRAYS_JSON.getBytes(StandardCharsets.UTF_8);
        ArraysRec m8 = (ArraysRec) GeneratedBinders.forType(ArraysRec.class).parseTokens(
                Jolt.utf8Parser(json), BindConfig.defaults());
        ArraysRec reference = (ArraysRec) GeneratedBinders.referenceForType(ArraysRec.class)
                .parseTokens(Jolt.utf8Parser(json), BindConfig.defaults());
        ArraysRec tree = Bind.parse(ARRAYS_JSON, ArraysRec.class);
        org.junit.jupiter.api.Assertions.assertArrayEquals(tree.names(), m8.names());
        org.junit.jupiter.api.Assertions.assertArrayEquals(tree.items(), m8.items());
        org.junit.jupiter.api.Assertions.assertArrayEquals(tree.intsArray(), m8.intsArray());
        org.junit.jupiter.api.Assertions.assertArrayEquals(tree.names(), reference.names());
        org.junit.jupiter.api.Assertions.assertArrayEquals(tree.items(), reference.items());
        org.junit.jupiter.api.Assertions.assertArrayEquals(tree.intsArray(), reference.intsArray());
    }

    @Test
    void nestedCollectionsInlineThroughAllThreeArms() {
        Nested m8 = (Nested) GeneratedBinders.forType(Nested.class).parseTokens(
                Jolt.utf8Parser(NESTED_JSON.getBytes(StandardCharsets.UTF_8)),
                BindConfig.defaults());
        Nested reference = (Nested) GeneratedBinders.referenceForType(Nested.class)
                .parseTokens(Jolt.utf8Parser(NESTED_JSON.getBytes(StandardCharsets.UTF_8)),
                        BindConfig.defaults());
        Nested tree = Bind.parse(NESTED_JSON, Nested.class);
        assertEquals(tree, m8);
        assertEquals(tree, reference);
    }

    @Test
    void childElementCollectionsInlineThroughTheMonomorphicChildBody() {
        Children m8 = (Children) GeneratedBinders.forType(Children.class).parseTokens(
                Jolt.utf8Parser(CHILDREN_JSON.getBytes(StandardCharsets.UTF_8)),
                BindConfig.defaults());
        Children reference = (Children) GeneratedBinders.referenceForType(Children.class)
                .parseTokens(Jolt.utf8Parser(CHILDREN_JSON.getBytes(StandardCharsets.UTF_8)),
                        BindConfig.defaults());
        Children tree = Bind.parse(CHILDREN_JSON, Children.class);
        assertEquals(tree, m8);
        assertEquals(tree, reference);
        // the byte-identical round trip, M8 form
        JoltWriter writer = Jolt.write();
        GeneratedBinders.forType(Children.class).write(m8, writer);
        assertEquals(CHILDREN_JSON, writer.toString());
    }

    @Test
    void untypedElementsKeepTheDoorAndBindIdentically() {
        Untyped m8 = (Untyped) GeneratedBinders.forType(Untyped.class).parseTokens(
                Jolt.utf8Parser(UNTYPED_JSON.getBytes(StandardCharsets.UTF_8)),
                BindConfig.defaults());
        Untyped reference = (Untyped) GeneratedBinders.referenceForType(Untyped.class)
                .parseTokens(Jolt.utf8Parser(UNTYPED_JSON.getBytes(StandardCharsets.UTF_8)),
                        BindConfig.defaults());
        Untyped tree = Bind.parse(UNTYPED_JSON, Untyped.class);
        assertEquals(tree, m8);
        assertEquals(tree, reference);
    }

    @Test
    void pojoParentsInlineLikeRecords() {
        byte[] json = POJO_JSON.getBytes(StandardCharsets.UTF_8);
        PojoCart m8 = (PojoCart) GeneratedBinders.forType(PojoCart.class).parseTokens(
                Jolt.utf8Parser(json), BindConfig.defaults());
        PojoCart reference = (PojoCart) GeneratedBinders.referenceForType(PojoCart.class)
                .parseTokens(Jolt.utf8Parser(json), BindConfig.defaults());
        PojoCart tree = Bind.parse(json, PojoCart.class);
        assertEquals(List.of("a", "b"), m8.getTags());
        assertEquals(tree.getTags(), m8.getTags());
        assertEquals(tree.getItems(), m8.getItems());
        assertEquals(tree.getTags(), reference.getTags());
        assertEquals(tree.getItems(), reference.getItems());
    }

    @Test
    void elementErrorsAreByteIdenticalAcrossAllThreeArms() {
        // (type, document, expected path): scalar tag, integral overflow, child field,
        // nested element, map value, array element — and the alignment proof: a
        // later-declared field after the failing collection raises ITS OWN error
        record Cell(Class<?> type, String json, String path) {}
        List<Cell> cells = List.of(
                new Cell(Scalars.class,
                        "{\"tags\":[\"a\",1],\"ints\":[]}", "$.tags[1]"),
                new Cell(Scalars.class,
                        "{\"ints\":[1,99999999999]}", "$.ints[1]"),
                new Cell(Scalars.class,
                        "{\"colors\":[\"RED\",\"BLUE\"]}", "$.colors[1]"),
                // the BigInteger element's exactness handler (the emitter's own try region
                // inside the loop — the review's catch: a self-looping join label)
                new Cell(Scalars.class,
                        "{\"bigs\":[1,2.5]}", "$.bigs[1]"),
                new Cell(ArraysRec.class,
                        "{\"names\":[1]}", "$.names[0]"),
                new Cell(Children.class,
                        "{\"items\":[{\"sku\":\"s\",\"quantity\":\"x\"}]}", "$.items[0].quantity"),
                new Cell(Children.class,
                        "{\"bySku\":{\"k\":{\"sku\":\"s\",\"quantity\":0,\"price\":\"p\"}}}",
                        "$.bySku.k.price"),
                new Cell(Nested.class,
                        "{\"grid\":[[\"a\"],[1]]}", "$.grid[1][0]"),
                new Cell(Nested.class,
                        "{\"batches\":[[{\"sku\":\"s\",\"quantity\":1,\"price\":1}],[{\"sku\":\"t\"}]]}",
                        "$.batches[1][0].quantity"),
                new Cell(PojoCart.class,
                        "{\"tags\":[\"a\",1],\"items\":[]}", "$.tags[1]"),
                // alignment: the collection fails in the document FIRST but "a" is declared
                // FIRST — its error wins the declaration-order sweep only if the loop
                // re-aligned after the collection
                new Cell(Tagged.class, "{\"tags\":[\"x\",1],\"a\":\"bad\"}", "$.a"));
        for (Cell cell : cells) {
            byte[] json = cell.json.getBytes(StandardCharsets.UTF_8);
            JsonBindException viaTree = assertThrows(JsonBindException.class,
                    () -> Bind.parse(json, cell.type, BindConfig.defaults()), cell.json);
            JsonBindException viaM8 = assertThrows(JsonBindException.class,
                    () -> GeneratedBinders.forType(cell.type).parseTokens(
                            Jolt.utf8Parser(json), BindConfig.defaults()), cell.json);
            JsonBindException viaReference = assertThrows(JsonBindException.class,
                    () -> GeneratedBinders.referenceForType(cell.type).parseTokens(
                            Jolt.utf8Parser(json), BindConfig.defaults()), cell.json);
            assertEquals(viaTree.getMessage(), viaM8.getMessage(), cell.json);
            assertEquals(viaTree.getPath(), viaM8.getPath(), cell.json);
            assertEquals(cell.path, viaM8.getPath(), cell.json);
            assertEquals(viaTree.getMessage(), viaReference.getMessage(), cell.json);
            assertEquals(viaTree.getPath(), viaReference.getPath(), cell.json);
        }
    }

    public record Tagged(int a, List<String> tags) {}

    @Test
    void theInlineDecisionIsVisibleAtGenerationTime() {
        // decompose: inlinable shapes vs the door — the capability predicate is untouched
        assertNotNull(InlineCollections.decompose(
                GenericsTestSupport.typeOf(Children.class, "items")));
        assertNotNull(InlineCollections.decompose(
                GenericsTestSupport.typeOf(Children.class, "bySku")));
        assertNotNull(InlineCollections.decompose(
                GenericsTestSupport.typeOf(Nested.class, "grid")));
        // untyped elements keep the door
        org.junit.jupiter.api.Assertions.assertNull(InlineCollections.decompose(
                GenericsTestSupport.typeOf(Untyped.class, "any")));
        org.junit.jupiter.api.Assertions.assertNull(InlineCollections.decompose(
                GenericsTestSupport.typeOf(Untyped.class, "dynamic")));
    }

    /** Reads a component's generic Type through reflection (the decompose entry point). */
    static final class GenericsTestSupport {
        static java.lang.reflect.Type typeOf(Class<?> type, String component) {
            try {
                return type.getDeclaredField(component).getGenericType();
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void binderArmsCarryTheirForms() {
        TypedBinder<?> m8 = GeneratedBinders.forType(Children.class);
        TypedBinder<?> reference = GeneratedBinders.referenceForType(Children.class);
        org.junit.jupiter.api.Assertions.assertTrue(
                reference.getClass().getName().contains("Gen$Ref$"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                m8.getClass(), reference.getClass());
    }
}
