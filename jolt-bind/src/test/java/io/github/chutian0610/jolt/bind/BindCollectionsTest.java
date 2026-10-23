package io.github.chutian0610.jolt.bind;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Collections, enums, POJOs: generic resolution, natural mapping, and rejection contracts. */
class BindCollectionsTest {

    enum Status { ACTIVE, ARCHIVED }

    record Tagged(String id, List<String> tags, Status status) {}

    record NestedCollections(Map<String, List<Integer>> index, List<List<String>> grid) {}

    record Untyped(List<Object> any, Map<String, Object> dynamic) {}

    record WithNumbers(List<Integer> ints, double[] doubles) {}

    interface Shape {}

    record Shapes(List<Shape> shapes) {}

    public static class PojoFixture {
        private String name;
        private int count;

        public PojoFixture() {}

        public void setName(String name) {
            this.name = name;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }
    }

    public static class NoDefault {
        public NoDefault(String required) {}
    }

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void listAndEnumBind() {
        Tagged t = Bind.parse(utf8("{\"id\":\"a\",\"tags\":[\"x\",\"y\"],\"status\":\"ACTIVE\"}"), Tagged.class);
        assertEquals("a", t.id());
        assertEquals(List.of("x", "y"), t.tags());
        assertEquals(Status.ACTIVE, t.status());
    }

    @Test
    void unknownEnumNameThrowsWithPath() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"id\":\"a\",\"tags\":[],\"status\":\"GONE\"}"), Tagged.class));
        assertEquals("$.status", e.getPath());
        assertTrue(e.getMessage().contains("GONE"), e.getMessage());
    }

    @Test
    void mapsAndNestedGenericsBind() {
        NestedCollections n = Bind.parse(utf8(
                "{\"index\":{\"a\":[1,2],\"b\":[]},\"grid\":[[\"x\"],[\"y\",\"z\"]]}"), NestedCollections.class);
        assertEquals(List.of(1, 2), n.index().get("a"));
        assertEquals(List.of(), n.index().get("b"));
        assertEquals(List.of("y", "z"), n.grid().get(1));
    }

    @Test
    void primitiveArraysBind() {
        WithNumbers w = Bind.parse(utf8("{\"ints\":[1,-2],\"doubles\":[0.5,1.5]}"), WithNumbers.class);
        assertEquals(1, w.ints().get(0));
        assertEquals(-2, w.ints().get(1));
        assertEquals(2, w.doubles().length);
        assertEquals(1.5, w.doubles()[1]);
    }

    @Test
    void untypedElementsMapNaturally() {
        Untyped u = Bind.parse(utf8("{\"any\":[\"s\",7,1.5,true,null,[1],{\"k\":2}],"
                + "\"dynamic\":{\"deep\":{\"x\":1}}}"), Untyped.class);
        List<Object> any = u.any();
        assertEquals("s", any.get(0));
        assertEquals(7L, any.get(1));
        assertEquals(1.5, any.get(2));
        assertEquals(true, any.get(3));
        assertNull(any.get(4));
        assertEquals(List.of(1L), any.get(5));
        assertEquals(Map.of("k", 2L), any.get(6));
        assertEquals(Map.of("deep", Map.of("x", 1L)), u.dynamic());
    }

    @Test
    void abstractElementTypesAreRejected() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"shapes\":[{\"kind\":\"circle\"}]}"), Shapes.class));
        assertEquals("$.shapes[0]", e.getPath());
        assertTrue(e.getMessage().contains("polymorphic"), e.getMessage());
    }

    @Test
    void pojoBindsViaSetters() {
        PojoFixture p = Bind.parse(utf8("{\"name\":\"jolt\",\"count\":7}"), PojoFixture.class);
        assertEquals("jolt", p.getName());
        assertEquals(7, p.getCount());
    }

    @Test
    void pojoWithoutUsableConstructorFailsNamingTypeAndReason() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"required\":\"x\"}"), NoDefault.class));
        assertTrue(e.getMessage().contains("NoDefault"), e.getMessage());
        assertTrue(e.getMessage().contains("no public no-arg constructor"), e.getMessage());
    }

    @Test
    void pojoKeysWithoutSettersAreIgnored() {
        PojoFixture p = Bind.parse(utf8("{\"name\":\"x\",\"mystery\":[1,2]}"), PojoFixture.class);
        assertEquals("x", p.getName());
    }

    @Test
    void writeDirectionMirrorsCollectionsEnumsAndPojos() {
        Tagged t = new Tagged("a", List.of("x", "y"), Status.ACTIVE);
        String json = Bind.write(t);
        Tagged reloaded = Bind.parse(json, Tagged.class);
        assertEquals(t, reloaded);

        PojoFixture p = new PojoFixture();
        p.setName("n");
        p.setCount(3);
        NodeStyleProbe probe = new NodeStyleProbe();
        probe.name = "n";
        probe.count = 3;
        String pojoJson = Bind.write(p);
        assertTrue(pojoJson.contains("\"name\":\"n\""), pojoJson);
        assertTrue(pojoJson.contains("\"count\":3"), pojoJson);
        PojoFixture reloadedPojo = Bind.parse(pojoJson, PojoFixture.class);
        assertEquals(probe.name, reloadedPojo.getName());
        assertEquals(probe.count, reloadedPojo.getCount());
    }

    /** Write-shape reference without depending on getter ordering. */
    static class NodeStyleProbe {
        String name;
        int count;
    }

    @Test
    void isXIsNeverASetterAndMapKeysMustBeString() {
        // isX is a getter, never a setter: the "active" key is ignored and the flag stays false
        OnlyIsGetter pojo = Bind.parse(utf8("{\"active\":true}"), OnlyIsGetter.class);
        assertEquals(false, pojo.isActive());
        // Map keys must be String: Map<Integer, String> is rejected with the path
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"m\":{\"1\":\"x\"}}"), BadMapKey.class));
        assertEquals("$.m", e.getPath());
        assertTrue(e.getMessage().contains("map keys must be String"), e.getMessage());
    }

    public static class OnlyIsGetter {
        public OnlyIsGetter() {}
        private boolean active;

        public boolean isActive() {
            return active;
        }
    }

    record BadMapKey(Map<Integer, String> m) {}

    @Test
    void missingAndNullCollectionsReadAsNull() {
        record WithList(List<String> items) {}
        WithList missing = Bind.parse(utf8("{}"), WithList.class);
        assertNull(missing.items());
        WithList jsonNull = Bind.parse(utf8("{\"items\":null}"), WithList.class);
        assertNull(jsonNull.items());
    }

    @Test
    void naturalBigDecimalOverflowAndDeterministicPojoOrder() {
        Untyped u = Bind.parse(utf8("{\"any\":[1e400]}"), Untyped.class);
        assertInstanceOf(BigDecimal.class, u.any().get(0));
        assertEquals(new BigDecimal("1e400"), u.any().get(0));

        // POJO property order is accessor-sorted and deterministic across runs (T25 gate needs this)
        PojoFixture p = new PojoFixture();
        p.setName("n");
        p.setCount(1);
        String once = Bind.write(p);
        for (int i = 0; i < 20; i++) {
            PojoFixture again = new PojoFixture();
            again.setName("n");
            again.setCount(1);
            assertEquals(once, Bind.write(again), "POJO property order must be deterministic");
        }
    }
}
