package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.gen.GeneratedBinders;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The standing THREE-GRADE pairwise matrix on the record spectrum (M7-T2): tree reflective,
 * reflective direct, generated direct — pairwise-equal values, pairwise-identical failures
 * (type, message, path), byte-identical writes.
 *
 * <p>Both direct grades ride the facade's {@code withDirect} dispatch, whose registry hit is
 * LAZY: a type nobody has generated yet routes to the reflective direct binder, and
 * {@code GeneratedBinders.forType} flips the same call to the generated read. Each local
 * fixture gets ONE three-grade cell — the failure cell or the value cell, whichever runs
 * first, while the fixture is still ungenerated — and the rest of its cells run tree vs the
 * (now generated) direct arm; those shapes keep their reflective-grade parity in jolt-bind's
 * DirectBindTest (the same mismatch/missing/unknown/nested-path cells, in-package), closing
 * the pairwise triangle per shape. Non-public component types make a record non-capable by
 * design (the emitted bytecode names every component type from the generated runtime
 * package) — their cells pin the reflective fallback.
 */
class ThreeGradeMatrixGateTest {

    public enum Tier { BASIC, PRO }

    public record Inner(int x, BigDecimal price, Tier tier) {}

    /** Capable: scalars + enum + a nested RECORD child. */
    public record Outer(String label, Inner inner, long total) {}

    /** A NON-capable record child (a collection component) — the reflective door's arm. */
    public record Leafy(String name, List<Integer> scores) {}

    /** Capable parent whose child has no direct body — the door serves it. */
    public record Shell(String id, Leafy leaf) {}

    /** Self-referential: capable, its child read is its OWN body (finite documents). */
    public record Node2(int value, Node2 next) {}

    /** A package-private enum component: the record must stay on the reflective arm. */
    enum SecretCode { ONE, TWO }

    public record Badged(String name, SecretCode code) {}

    /** A package-private record component: same visibility rule, same fallback. */
    record Hidden(int v) {}

    public record Cloaked(String tag, Hidden hid) {}

    /** The full M3 graph, local: nested record + list of records + map + enum + boxed
     * null + exact decimals + untyped elements — the ORDER shape with deterministic
     * generation. */
    public record FullGraph(String id, Inner inner, List<Inner> items,
                            java.util.Map<String, Integer> counts, Tier tier,
                            Integer boxed, long total, java.math.BigInteger sequence,
                            double ratio, boolean flag, List<Object> any) {}

    /** A local POJO: setters with a primitive, a reference, and a BigInteger parameter. */
    public static class Crate {
        private String label;
        private int weight;
        private java.math.BigInteger seq;

        public Crate() {}

        public void setLabel(String label) {
            if (label != null && label.equals("boom")) {
                throw new IllegalStateException("nope");
            }
            this.label = label;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public void setSeq(java.math.BigInteger seq) {
            this.seq = seq;
        }

        public String getLabel() {
            return label;
        }

        public int getWeight() {
            return weight;
        }

        public java.math.BigInteger getSeq() {
            return seq;
        }
    }

    /** A record with a POJO child: the nested dispatch's POJO arm. */
    public record Shipment(String id, Crate crate) {}

    /** A polymorphic (abstract) component: the shared rejection through the door. */
    public abstract static class Base {
        public Base() {}
    }

    public record WithBase(Base b) {}

    /** A POJO with an abstract setter parameter: the same rejection, POJO arm. */
    public static class Flexy {
        private Base b;

        public void setB(Base b) {
            this.b = b;
        }

        public Base getB() {
            return b;
        }
    }

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** The FIRST cell of a type: reflective-first (registry miss), then generate, then
     * generated — the full pairwise proof for this cell. */
    private static <T> void assertThreeGradesBind(String doc, Class<T> type) {
        assertNull(Bind.generatedLookupForTest(type), type.getSimpleName()
                + " must be ungenerated for the reflective arm to be the facade's first hit");
        T tree = Bind.parse(utf8(doc), type, BindConfig.defaults());
        T reflective = Bind.parse(utf8(doc), type, BindConfig.defaults().withDirect());
        GeneratedBinders.forType(type);
        T generated = Bind.parse(utf8(doc), type, BindConfig.defaults().withDirect());
        assertEquals(tree, reflective, type.getSimpleName() + " reflective value");
        assertEquals(tree, generated, type.getSimpleName() + " generated value");
        assertEquals(Bind.write(tree), Bind.write(reflective));
        assertEquals(Bind.write(tree), Bind.write(generated));
    }

    private static void assertThreeGradesFail(String doc, Class<?> type) {
        RuntimeException tree = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), type, BindConfig.defaults()));
        RuntimeException reflective = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), type, BindConfig.defaults().withDirect()));
        GeneratedBinders.forType(type);
        RuntimeException generated = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), type, BindConfig.defaults().withDirect()));
        assertEquals(tree.getClass(), reflective.getClass(), doc);
        assertEquals(tree.getClass(), generated.getClass(), doc);
        assertEquals(tree.getMessage(), reflective.getMessage(), doc);
        assertEquals(tree.getMessage(), generated.getMessage(), doc);
        if (tree instanceof JsonBindException tb) {
            assertEquals(tb.getPath(), ((JsonBindException) reflective).getPath(), doc);
            assertEquals(tb.getPath(), ((JsonBindException) generated).getPath(), doc);
        }
    }

    /** A cell on an ALREADY-generated type: tree vs the facade's direct arm. */
    private static <T> void assertDirectArmBind(String doc, Class<T> type) {
        T tree = Bind.parse(utf8(doc), type, BindConfig.defaults());
        T direct = Bind.parse(utf8(doc), type, BindConfig.defaults().withDirect());
        assertEquals(tree, direct, type.getSimpleName() + " direct value");
        assertEquals(Bind.write(tree), Bind.write(direct));
    }

    private static void assertDirectArmFail(String doc, Class<?> type) {
        assertDirectArmFail(doc, type, BindConfig.defaults());
    }

    private static void assertDirectArmFail(String doc, Class<?> type, BindConfig base) {
        RuntimeException tree = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), type, base));
        RuntimeException direct = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), type, base.withDirect()));
        assertEquals(tree.getClass(), direct.getClass(), doc);
        assertEquals(tree.getMessage(), direct.getMessage(), doc);
        if (tree instanceof JsonBindException tb) {
            assertEquals(tb.getPath(), ((JsonBindException) direct).getPath(), doc);
        }
    }

    /** ONE ordered method by design: the three-grade cells require their fixture to be
     * UNGENERATED, so the sequence matters (each fixture's three-grade cell first). */
    @Test
    void recordSpectrumPairwiseMatrix() {
        // one three-grade cell per fixture, while it is still ungenerated
        assertThreeGradesFail("{\"label\":\"a\",\"inner\":{\"x\":true,\"price\":1.5,"
                + "\"tier\":\"BASIC\"},\"total\":1}", Outer.class); // $.inner.x, three grades
        assertThreeGradesBind("{\"id\":\"i\",\"leaf\":{\"name\":\"n\",\"scores\":[1,2,3]}}",
                Shell.class);
        assertThreeGradesFail("{\"value\":1,\"next\":{\"value\":true}}", Node2.class);
        // $.next.value through the self virtual recursion, three grades

        // Outer (generated): values at the bounds, then the failure family
        assertDirectArmBind("{\"label\":\"a\",\"inner\":{\"x\":5,\"price\":1.50,"
                + "\"tier\":\"PRO\"},\"total\":9007199254740993}", Outer.class);
        assertDirectArmBind("{\"label\":null,\"inner\":null,"
                + "\"total\":-9223372036854775808}", Outer.class);
        assertDirectArmFail("{\"label\":\"a\",\"inner\":{\"x\":5,\"price\":1.5,"
                + "\"tier\":\"GOLD\"},\"total\":1}", Outer.class); // $.inner.tier unknown enum
        assertDirectArmFail("{\"label\":\"a\",\"inner\":{\"price\":1.5,\"tier\":\"BASIC\"},"
                + "\"total\":1}", Outer.class); // $.inner.x missing (the child's sweep)
        assertDirectArmFail("{\"label\":\"a\",\"inner\":5,\"total\":1}", Outer.class);
        // $.inner: expected an object but found NUMBER
        assertDirectArmFail("{\"label\":\"a\",\"inner\":{\"x\":5,\"price\":\"1.5\"},"
                + "\"total\":1}", Outer.class); // $.inner.price
        assertDirectArmFail("{\"label\":\"a\",\"inner\":null,\"total\":null}", Outer.class);
        // JSON null for the primitive $.total

        // Shell: the door child's own cells
        assertDirectArmBind("{\"id\":\"i\"}", Shell.class); // leaf missing → null
        assertDirectArmFail("{\"id\":\"i\",\"leaf\":{\"name\":\"n\",\"scores\":\"x\"}}",
                Shell.class); // $.leaf.scores

        // Node2 (generated): self-reference values incl. the null tail
        assertDirectArmBind("{\"value\":1,\"next\":{\"value\":2,\"next\":{\"value\":3}}}",
                Node2.class);
        assertDirectArmBind("{\"value\":1,\"next\":null}", Node2.class);

        // the drain family: empty document, tail garbage, non-object root
        assertDirectArmFail("", Outer.class); // the parser throws on all arms
        assertDirectArmFail("{\"label\":\"a\",\"inner\":{\"x\":1,\"price\":1,"
                + "\"tier\":\"BASIC\"},\"total\":1} oops", Outer.class); // the drain cell
        assertDirectArmFail("5", Outer.class); // non-object root: $.total missing

        // non-public component types: the records stay non-capable — the facade's direct
        // arm is the reflective binder, and the cells hold anyway
        assertDirectArmBind("{\"name\":\"n\",\"code\":\"ONE\"}", Badged.class);
        assertDirectArmBind("{\"tag\":\"t\",\"hid\":{\"v\":1}}", Cloaked.class);
        assertDirectArmFail("{\"tag\":\"t\",\"hid\":{\"v\":true}}", Cloaked.class);
        // $.hid.v through the reflective arm

        // T3: the full local graph (nested record + list of records + map + enum + boxed
        // null + exact decimals + untyped elements) — the three-grade value cell, then the
        // generated-arm failure family (element paths, the map, the untyped elements)
        assertThreeGradesBind("{\"id\":\"o\",\"inner\":{\"x\":5,\"price\":1.50,"
                + "\"tier\":\"PRO\"},\"items\":[{\"x\":1,\"price\":2.5,\"tier\":\"BASIC\"},"
                + "{\"x\":2,\"price\":92233720368547758079,\"tier\":\"PRO\"}],"
                + "\"counts\":{\"a\":1,\"b\":2},\"tier\":\"BASIC\",\"boxed\":null,"
                + "\"total\":9007199254740993,\"sequence\":123456789012345678901234567890,"
                + "\"ratio\":2.5,\"flag\":true,\"any\":[1,\"s\",true,null,2.5]}", FullGraph.class);
        assertDirectArmFail("{\"id\":\"o\",\"inner\":{\"x\":1,\"price\":1,\"tier\":\"BASIC\"},"
                + "\"items\":[{\"x\":1,\"price\":1,\"tier\":\"BASIC\"},"
                + "{\"x\":\"nine\",\"price\":1,\"tier\":\"BASIC\"}],\"counts\":{},"
                + "\"tier\":\"BASIC\",\"boxed\":1,\"total\":1,\"sequence\":1,\"ratio\":1.0,"
                + "\"flag\":false}", FullGraph.class); // $.items[1].x — the element path
        assertDirectArmFail("{\"id\":\"o\",\"inner\":{\"x\":1,\"price\":1,\"tier\":\"BASIC\"},"
                + "\"items\":[],\"counts\":{},\"tier\":\"WHO\",\"boxed\":1,\"total\":1,"
                + "\"sequence\":1,\"ratio\":1.0,\"flag\":false}", FullGraph.class);
        // $.tier: the unknown enum through the generated read
        assertDirectArmFail("{\"id\":\"o\",\"inner\":{\"x\":1,\"price\":1,\"tier\":\"BASIC\"},"
                + "\"items\":[],\"counts\":{},\"tier\":\"BASIC\",\"boxed\":1,\"total\":1,"
                + "\"sequence\":1.5,\"ratio\":1.0,\"flag\":false}", FullGraph.class);
        // $.sequence: the BigInteger whole-number rejection
        assertDirectArmFail("{\"id\":\"o\",\"inner\":{\"x\":1,\"price\":1,\"tier\":\"BASIC\"},"
                + "\"items\":[],\"counts\":{},\"tier\":\"BASIC\",\"boxed\":1,\"total\":1,"
                + "\"sequence\":1,\"ratio\":1.0,\"flag\":false,\"any\":{\"sneaky\":1},"
                + "\"sneaky\":2}", FullGraph.class,
                BindConfig.defaults().withFailOnUnknown()); // the unknown family
        assertDirectArmBind("{\"id\":\"o\",\"inner\":{\"x\":1,\"price\":1,\"tier\":\"BASIC\"},"
                + "\"items\":[],\"counts\":{},\"tier\":\"BASIC\",\"boxed\":1,\"total\":1,"
                + "\"sequence\":1,\"ratio\":1.0,\"flag\":false,"
                + "\"any\":[1,\"s\",true,null,2.5,{\"k\":{\"n\":[9.5]}}]}",
                FullGraph.class); // the untyped natural mapping (Long/Double/BigDecimal/map)

        // the local POJO: the three-grade cell is its SETTER-REJECTION failure (the first
        // cell, while ungenerated), then the value family on the generated arm
        assertThreeGradesFail("{\"label\":\"boom\",\"weight\":1}", Crate.class);
        // $.label: "setter rejected the bound value: java.lang.IllegalStateException: nope"
        // Crate has identity equals — the value cells compare the write bytes + getters
        Crate okTree = Bind.parse(utf8("{\"label\":\"ok\",\"weight\":7}"), Crate.class,
                BindConfig.defaults());
        Crate okDirect = Bind.parse(utf8("{\"label\":\"ok\",\"weight\":7}"), Crate.class,
                BindConfig.defaults().withDirect());
        assertEquals("ok", okDirect.getLabel());
        assertEquals(7, okDirect.getWeight());
        assertEquals(Bind.write(okTree), Bind.write(okDirect));
        Crate sparse = Bind.parse(utf8("{\"weight\":7}"), Crate.class,
                BindConfig.defaults().withDirect());
        assertNull(sparse.getLabel()); // absent label stays default
        assertEquals(7, sparse.getWeight());
        Crate scalarRoot = Bind.parse(utf8("5"), Crate.class,
                BindConfig.defaults().withDirect());
        assertNull(scalarRoot.getLabel()); // the scalar root: the default instance
        assertEquals(0, scalarRoot.getWeight());
        assertDirectArmFail("{\"label\":\"x\",\"weight\":null}", Crate.class);
        // JSON null for the primitive $.weight

        // the DECLARATION-ORDER pin: two errors in one document — the setter table sorts
        // label < weight, so the sweep raises the $.label REJECTION (declared first), not
        // the document-first $.weight type mismatch
        assertDirectArmFail("{\"weight\":\"nine\",\"label\":\"boom\"}", Crate.class);

        // the POJO BigInteger cell: the exact read AND the fraction rejection (the lossy
        // handler) through the generated arm
        Crate seqTree = Bind.parse(utf8("{\"label\":\"x\",\"weight\":1,"
                + "\"seq\":123456789012345678901234567890}"), Crate.class,
                BindConfig.defaults());
        Crate seqDirect = Bind.parse(utf8("{\"label\":\"x\",\"weight\":1,"
                + "\"seq\":123456789012345678901234567890}"), Crate.class,
                BindConfig.defaults().withDirect());
        assertEquals(new java.math.BigInteger("123456789012345678901234567890"),
                seqDirect.getSeq());
        assertEquals(Bind.write(seqTree), Bind.write(seqDirect));
        assertDirectArmFail("{\"label\":\"x\",\"weight\":1,\"seq\":12.5}", Crate.class);
        // $.seq: number is not a whole number

        // a record whose child is a POJO: the nested dispatch's POJO arm — the three-grade
        // proof compares WRITES (Crate has identity equals), then the child's own setter
        // rejection at the nested path
        assertNull(Bind.generatedLookupForTest(Shipment.class));
        String shipDoc = "{\"id\":\"s\",\"crate\":{\"label\":\"ok\",\"weight\":3,\"seq\":42}}";
        var shipTree = Bind.parse(utf8(shipDoc), Shipment.class, BindConfig.defaults());
        var shipRefl = Bind.parse(utf8(shipDoc), Shipment.class,
                BindConfig.defaults().withDirect());
        GeneratedBinders.forType(Shipment.class);
        var shipGen = Bind.parse(utf8(shipDoc), Shipment.class,
                BindConfig.defaults().withDirect());
        assertEquals(Bind.write(shipTree), Bind.write(shipRefl));
        assertEquals(Bind.write(shipTree), Bind.write(shipGen));
        assertDirectArmFail("{\"id\":\"s\",\"crate\":{\"label\":\"boom\",\"weight\":3,"
                + "\"seq\":42}}", Shipment.class); // $.crate.label: the child's rejection

        // the POJO polymorphic rejection through the door (the abstract setter parameter)
        assertDirectArmFail("{\"b\":{}}", Flexy.class);
        // the POJO twin

        // the polymorphic rejection through the door: the same message, three grades
        assertThreeGradesFail("{\"b\":{}}", WithBase.class);
    }
}
