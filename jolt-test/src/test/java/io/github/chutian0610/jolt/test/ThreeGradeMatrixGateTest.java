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
        RuntimeException tree = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), type, BindConfig.defaults()));
        RuntimeException direct = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), type, BindConfig.defaults().withDirect()));
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
    }
}
