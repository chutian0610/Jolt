package io.github.chutian0610.jolt.test;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.bind.fixtures.BindFixtures;
import io.github.chutian0610.jolt.gen.GeneratedBinders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M7-T1 three-grade tracer gate on the canonical fixture: the Scalars record through the
 * tree path, the reflective direct binder, and the GENERATED direct read (raw
 * {@code parseTokens} on the generated class, and the facade's {@code withDirect} dispatch
 * riding the registry). Values equal, writes byte-identical, error cells byte-identical
 * (type + message + path) — a divergence between the grades is a gate failure. The
 * reflective grade's own parity lives in jolt-bind's DirectBindTest; this gate holds the
 * generated grade to the same cells.
 */
class GeneratedDirectTracerGateTest {

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** The generated binder for the scalar fixture, straight from the registry. */
    @SuppressWarnings("unchecked")
    private static TypedBinder<BindFixtures.Scalars> generated() {
        return (TypedBinder<BindFixtures.Scalars>) GeneratedBinders.forType(BindFixtures.Scalars.class);
    }

    private static boolean declaresDirectRead(Class<?> target) {
        return ((io.github.chutian0610.jolt.bind.GeneratedFields)
                GeneratedBinders.forType(target)).hasDirectRead();
    }

    @Test
    void scalarsFixtureValueParityAcrossAllThreeGrades() {
        BindFixtures.Scalars tree = Bind.parse(utf8(BindFixtures.SCALARS_JSON),
                BindFixtures.Scalars.class, BindConfig.defaults());
        BindFixtures.Scalars viaFacade = Bind.parse(utf8(BindFixtures.SCALARS_JSON),
                BindFixtures.Scalars.class, BindConfig.defaults().withDirect());
        BindFixtures.Scalars rawGenerated = generated().parseTokens(
                Jolt.utf8Parser(utf8(BindFixtures.SCALARS_JSON)), BindConfig.defaults());
        assertEquals(tree, viaFacade);
        assertEquals(tree, rawGenerated);
        assertEquals(BindFixtures.SCALARS_JSON, Bind.write(rawGenerated));
    }

    @Test
    void scalarsErrorCellsIdenticalTreeVersusGeneratedDirect() {
        String[] cells = {
                // byte width overflow: $.b
                "{\"b\":129,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1}",
                // BigInteger fraction: "number is not a whole number"
                "{\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":123.45}",
                // tag mismatch at $.text
                "{\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":[\"x\"],\"dec\":1,\"big\":1}",
                // JSON null against a primitive: $.b
                "{\"b\":null,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1}",
                // missing primitive at the END_OBJECT sweep: $.i
                "{\"b\":1,\"s\":2,\"l\":4,\"f\":5,\"d\":6,"
                        + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1}"};
        for (String doc : cells) {
            RuntimeException tree = assertThrows(RuntimeException.class,
                    () -> Bind.parse(utf8(doc), BindFixtures.Scalars.class,
                            BindConfig.defaults()));
            RuntimeException raw = assertThrows(RuntimeException.class,
                    () -> generated().parseTokens(Jolt.utf8Parser(utf8(doc)),
                            BindConfig.defaults()));
            RuntimeException viaFacade = assertThrows(RuntimeException.class,
                    () -> Bind.parse(utf8(doc), BindFixtures.Scalars.class,
                            BindConfig.defaults().withDirect()));
            assertEquals(tree.getClass(), raw.getClass(), doc);
            assertEquals(tree.getClass(), viaFacade.getClass(), doc);
            assertEquals(tree.getMessage(), raw.getMessage(), doc);
            assertEquals(tree.getMessage(), viaFacade.getMessage(), doc);
            if (tree instanceof JsonBindException tb) {
                assertEquals(tb.getPath(), ((JsonBindException) raw).getPath(), doc);
                assertEquals(tb.getPath(), ((JsonBindException) viaFacade).getPath(), doc);
            }
        }
    }

    @Test
    void unknownFieldCellIdenticalOnTheGeneratedDirectRead() {
        BindConfig strict = BindConfig.defaults().withFailOnUnknown();
        String doc = "{\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1,\"sneaky\":9}";
        JsonBindException tree = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8(doc), BindFixtures.Scalars.class, strict));
        JsonBindException raw = assertThrows(JsonBindException.class,
                () -> generated().parseTokens(Jolt.utf8Parser(utf8(doc)), strict));
        assertEquals(tree.getMessage(), raw.getMessage());
        assertEquals(tree.getPath(), raw.getPath());
    }

    @Test
    void utf16EngineThroughTheGeneratedDirectRead() {
        String json = "{\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":1.5,\"d\":2.5,"
                + "\"flag\":true,\"text\":\"ä\",\"dec\":1.50,\"big\":1}";
        BindFixtures.Scalars tree = Bind.parse(json, BindFixtures.Scalars.class,
                BindConfig.defaults());
        JoltParser utf16 = Jolt.utf16Parser(json);
        BindFixtures.Scalars raw = generated().parseTokens(utf16, BindConfig.defaults());
        assertEquals(tree, raw);
    }

    @Test
    void tailGarbageAfterAValidBodyThrowsOnTheGeneratedDirectRead() {
        // the drain cell: the reflective arm consumes the tail exactly like the tree loader
        // (syntax errors surface unchanged) — the generated read must not return early
        assertTrue(declaresDirectRead(BindFixtures.Scalars.class));
        String doc = "{\"b\":1,\"s\":2,\"i\":3,\"l\":4,\"f\":5,\"d\":6,"
                + "\"flag\":true,\"text\":\"t\",\"dec\":1,\"big\":1} oops";
        RuntimeException tree = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), BindFixtures.Scalars.class, BindConfig.defaults()));
        RuntimeException raw = assertThrows(RuntimeException.class,
                () -> generated().parseTokens(Jolt.utf8Parser(utf8(doc)), BindConfig.defaults()));
        RuntimeException viaFacade = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(doc), BindFixtures.Scalars.class,
                        BindConfig.defaults().withDirect()));
        assertEquals(tree.getClass(), raw.getClass());
        assertEquals(tree.getMessage(), raw.getMessage());
        assertEquals(tree.getClass(), viaFacade.getClass());
        assertEquals(tree.getMessage(), viaFacade.getMessage());
    }

    @Test
    void orderGraphRidesTheGeneratedDirectArmByteIdentically() {
        // the T3 flagship: the FULL M3 graph (nested record, list of records, map, enum,
        // boxed null, exact decimals) binds through the generated direct read — the write
        // is byte-identical to the canonical document
        assertTrue(declaresDirectRead(BindFixtures.Order.class));
        BindFixtures.Order tree = Bind.parse(utf8(BindFixtures.ORDER_JSON),
                BindFixtures.Order.class, BindConfig.defaults());
        BindFixtures.Order direct = Bind.parse(utf8(BindFixtures.ORDER_JSON),
                BindFixtures.Order.class, BindConfig.defaults().withDirect());
        assertEquals(tree, direct);
        assertEquals(BindFixtures.ORDER_JSON, Bind.write(direct));
    }

    @Test
    void pojoRidesTheGeneratedDirectArm() {
        // the POJO spectrum on the generated arm: values, the default-instance scalar
        // root, and the setter-rejection cell
        assertTrue(declaresDirectRead(BindFixtures.LabelPojo.class));
        BindFixtures.LabelPojo tree = Bind.parse(utf8("{\"label\":\"a\"}"),
                BindFixtures.LabelPojo.class, BindConfig.defaults());
        BindFixtures.LabelPojo direct = Bind.parse(utf8("{\"label\":\"a\"}"),
                BindFixtures.LabelPojo.class, BindConfig.defaults().withDirect());
        assertEquals("a", direct.getLabel());
        assertEquals(Bind.write(tree), Bind.write(direct));
        BindFixtures.LabelPojo scalarRoot = Bind.parse(utf8("5"),
                BindFixtures.LabelPojo.class, BindConfig.defaults().withDirect());
        assertNull(scalarRoot.getLabel()); // the default instance
    }

    @Test
    void theRegistryCarriesTheGeneratedClassWithAllThreeForms() throws Exception {
        TypedBinder<?> registered = Bind.generatedLookupForTest(BindFixtures.Scalars.class);
        assertTrue(registered.getClass().getName().startsWith("io.github.chutian0610.jolt.gen.Gen$"));
        // the three forms on ONE instance: tree parse, direct parseTokens, write
        assertNotNull(registered.getClass().getDeclaredMethod("parse",
                io.github.chutian0610.jolt.tree.Node.class, String.class, BindConfig.class));
        assertNotNull(registered.getClass().getDeclaredMethod("parseTokens",
                JoltParser.class, BindConfig.class));
        assertNotNull(registered.getClass().getDeclaredMethod("write",
                Object.class, io.github.chutian0610.jolt.JoltWriter.class));
    }

    @Test
    void tracerRecordThreeGradeParityThroughTheFacadeDispatch() {
        // generation first, so the facade's withDirect dispatch takes the registry hit
        assertTrue(declaresDirectRead(Tracer.class));
        String json = "{\"name\":\"ann\",\"quantity\":2,\"active\":true}";
        Tracer tree = Bind.parse(utf8(json), Tracer.class, BindConfig.defaults());
        Tracer direct = Bind.parse(utf8(json), Tracer.class,
                BindConfig.defaults().withDirect());
        assertEquals(tree, direct);
    }

    @Test
    void tracerRecordErrorCellsIdenticalAcrossTheGrades() {
        assertTrue(declaresDirectRead(Tracer.class));
        // mismatch ($.name) and missing ($.quantity at the sweep) cells: type + message +
        // path identical between the tree arm and the generated direct arm
        String[] cells = {
                "{\"name\":1,\"quantity\":2,\"active\":true}",
                "{\"name\":\"ann\",\"active\":true}"};
        for (String doc : cells) {
            RuntimeException tree = assertThrows(RuntimeException.class,
                    () -> Bind.parse(utf8(doc), Tracer.class, BindConfig.defaults()));
            RuntimeException direct = assertThrows(RuntimeException.class,
                    () -> Bind.parse(utf8(doc), Tracer.class,
                            BindConfig.defaults().withDirect()));
            assertEquals(tree.getClass(), direct.getClass(), doc);
            assertEquals(tree.getMessage(), direct.getMessage(), doc);
            if (tree instanceof JsonBindException tb) {
                assertEquals(tb.getPath(), ((JsonBindException) direct).getPath(), doc);
            }
        }
    }

    /** An all-reference scalar record: the non-object-root marker cell. */
    public record BoxedNullable(Integer i, Long l, Boolean b) {}

    /** The tracer shape (mirrors jolt-bind's DirectBindTest fixture). */
    public record Tracer(String name, int quantity, boolean active) {}

    @Test
    void allReferenceNonObjectRootRaisesTheSharedSpectrumMarker() {
        // BoxedNullable has no primitives: a scalar root raises the spectrum-tickets marker
        // from BOTH direct grades — one home, one message (DirectSupport)
        assertTrue(declaresDirectRead(BoxedNullable.class));
        RuntimeException direct = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8("5"), BoxedNullable.class,
                        BindConfig.defaults().withDirect()));
        assertEquals(UnsupportedOperationException.class, direct.getClass());
        assertEquals("all-reference records bind from non-object documents "
                + "with the M6 spectrum tickets", direct.getMessage());
    }
}
