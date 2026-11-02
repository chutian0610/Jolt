package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.bind.fixtures.BindFixtures;
import io.github.chutian0610.jolt.tree.Tree;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated-binding contract: fixture parity, cache identity, fallback, nested routing. */
class GeneratedBindersTest {

    @Test
    void generatedBinderParsesAndWritesTheOrderFixture() {
        TypedBinder<BindFixtures.Order> binder = GeneratedBinders.forType(BindFixtures.Order.class);
        BindFixtures.Order order = binder.parse(
                Tree.parse(BindFixtures.ORDER_JSON.getBytes(StandardCharsets.UTF_8)), "$",
                BindConfig.defaults());
        JoltWriter writer = Jolt.write();
        binder.write(order, writer);
        assertEquals(BindFixtures.ORDER_JSON, writer.toString());
    }

    @Test
    void generatedBinderParsesAndWritesTheScalarFixture() {
        TypedBinder<BindFixtures.Scalars> binder = GeneratedBinders.forType(BindFixtures.Scalars.class);
        BindFixtures.Scalars scalars = binder.parse(
                Tree.parse(BindFixtures.SCALARS_JSON.getBytes(StandardCharsets.UTF_8)), "$",
                BindConfig.defaults());
        JoltWriter writer = Jolt.write();
        binder.write(scalars, writer);
        assertEquals(BindFixtures.SCALARS_JSON, writer.toString());
    }

    @Test
    void cacheIdentityPerType() {
        assertSame(GeneratedBinders.forType(Point.class), GeneratedBinders.forType(Point.class));
    }

    @Test
    void generatedBinderRegistersForNestedRouting() {
        GeneratedBinders.forType(Point.class);
        assertSame(GeneratedBinders.forType(Point.class), Bind.generatedLookupForTest(Point.class));
    }

    @Test
    void facadeDelegationIsValueIdentical() {
        BindFixtures.Order viaGen = GeneratedBinders.forType(BindFixtures.Order.class).parse(
                Tree.parse(BindFixtures.ORDER_JSON.getBytes(StandardCharsets.UTF_8)), "$",
                BindConfig.defaults());
        BindFixtures.Order viaReflective = Bind.parse(
                BindFixtures.ORDER_JSON.getBytes(StandardCharsets.UTF_8), BindFixtures.Order.class);
        assertEquals(viaReflective, viaGen);
        // both binders construct the TARGET type — the binder class differs, the value doesn't
        assertTrue(GeneratedBinders.forType(BindFixtures.Order.class).getClass()
                .getName().startsWith("io.github.chutian0610.jolt.gen.Gen$"));
    }

    @Test
    void nonPublicTargetsFallBackToReflective() throws Exception {
        Class<?> hidden = Class.forName("io.github.chutian0610.jolt.bind.fixtures.HiddenPojo");
        assertFalse(java.lang.reflect.Modifier.isPublic(hidden.getModifiers()));
        io.github.chutian0610.jolt.bind.TypedBinder<?> binder = GeneratedBinders.forType(hidden);
        // fallback: the reflective adapter, NOT a jolt-gen generated class
        assertFalse(binder.getClass().getName().startsWith("io.github.chutian0610.jolt.gen.Gen$"));
        // functional parity: bind + write through the fallback works
        @SuppressWarnings("unchecked")
        io.github.chutian0610.jolt.bind.TypedBinder<Object> typed = (TypedBinder<Object>) binder;
        Object instance = typed.parse(Tree.parse("{\"v\":1}".getBytes(StandardCharsets.UTF_8)),
                "$", BindConfig.defaults());
        JoltWriter writer = Jolt.write();
        typed.write(instance, writer);
        assertEquals("{\"v\":1}", writer.toString());
    }

    record Point(int x, int y) {}

    @Test
    void specializedTargetsGetStraightLineOverrides() throws Exception {
        // record AND POJO targets: the overrides live ON the generated class — inherited plan
        // execution fails this gate
        for (Class<?> target : new Class<?>[]{BindFixtures.Order.class, BindFixtures.LabelPojo.class}) {
            Class<?> generated = GeneratedBinders.forType(target).getClass();
            assertNotNull(generated.getDeclaredMethod("parse",
                    io.github.chutian0610.jolt.tree.Node.class, String.class, BindConfig.class),
                    target.getName());
            assertNotNull(generated.getDeclaredMethod("write", Object.class, JoltWriter.class),
                    target.getName());
            assertNotNull(generated.getDeclaredMethod("writeFields",
                    Object.class, JoltWriter.class, String.class), target.getName());
        }
    }

    @Test
    void pojoFixtureBindsAndWritesByteIdenticallyThroughTheSpecializedBinder() {
        TypedBinder<BindFixtures.LabelPojo> binder =
                GeneratedBinders.forType(BindFixtures.LabelPojo.class);
        BindFixtures.LabelPojo pojo = binder.parse(
                Tree.parse("{\"label\":\"a\\\"b\"}".getBytes(StandardCharsets.UTF_8)), "$",
                BindConfig.defaults());
        assertEquals("a\"b", pojo.getLabel());
        JoltWriter writer = Jolt.write();
        binder.write(pojo, writer);
        assertEquals("{\"label\":\"a\\\"b\"}", writer.toString());
    }

    @Test
    void pojoFailOnUnknownIsAnOOneContainsWithTheUnknownKeysPath() {
        TypedBinder<BindFixtures.LabelPojo> binder =
                GeneratedBinders.forType(BindFixtures.LabelPojo.class);
        // unknown fields ignored by default
        BindFixtures.LabelPojo ignored = binder.parse(
                Tree.parse("{\"label\":\"v\",\"sneaky\":1}".getBytes(StandardCharsets.UTF_8)),
                "$", BindConfig.defaults());
        assertEquals("v", ignored.getLabel());
        // fail-on-unknown raises at the unknown key's own path
        io.github.chutian0610.jolt.bind.JsonBindException e =
                assertThrows(io.github.chutian0610.jolt.bind.JsonBindException.class,
                        () -> binder.parse(
                                Tree.parse("{\"label\":\"v\",\"sneaky\":1}".getBytes(StandardCharsets.UTF_8)),
                                "$", BindConfig.defaults().withFailOnUnknown()));
        assertEquals("$.sneaky", e.getPath());
    }

    @Test
    void pojoJsonNullBindsNullIntoTheReferenceSetter() {
        TypedBinder<BindFixtures.LabelPojo> binder =
                GeneratedBinders.forType(BindFixtures.LabelPojo.class);
        // JSON null (explicit in the document) binds as Java null — the reflective path
        // invokes the setter with null; write mirrors it back as JSON null
        BindFixtures.LabelPojo pojo = binder.parse(
                Tree.parse("{\"label\":null}".getBytes(StandardCharsets.UTF_8)), "$",
                BindConfig.defaults());
        assertNull(pojo.getLabel());
        JoltWriter writer = Jolt.write();
        binder.write(pojo, writer);
        assertEquals("{\"label\":null}", writer.toString());
    }

    @Test
    void planCachedReferenceArmExecutesThePlanWithoutOverridesAndNeverFeedsTheFacade()
            throws Exception {
        // a PUBLIC target — package-private ones legitimately fall back to reflective
        Class<?> planCached = GeneratedBinders.planCachedForType(BindFixtures.Customer.class)
                .getClass();
        // the reference arm is a plain GenBinderBase subclass: no straight-line overrides
        assertThrows(NoSuchMethodException.class, () -> planCached.getDeclaredMethod("parse",
                io.github.chutian0610.jolt.tree.Node.class, String.class, BindConfig.class));
        // the facade registry holds the specialized binder, never the reference arm
        assertNotSame(planCached, Bind.generatedLookupForTest(BindFixtures.Customer.class));
        // but it binds identically (functional sanity)
        io.github.chutian0610.jolt.bind.fixtures.BindFixtures.Customer parsed =
                (io.github.chutian0610.jolt.bind.fixtures.BindFixtures.Customer)
                        GeneratedBinders.planCachedForType(BindFixtures.Customer.class).parse(
                                Tree.parse(("{\"name\":\"ann\",\"emails\":[\"a@x\"],"
                                        + "\"tier\":2}").getBytes(StandardCharsets.UTF_8)),
                                "$", BindConfig.defaults());
        assertEquals("ann", parsed.name());
        assertEquals(java.util.List.of("a@x"), parsed.emails());
        assertEquals(2, parsed.tier());
    }

    /** Self-referential record: cascade registration must terminate (T32-review bug pinned). */
    record Linked(String name, Linked next) {}

    @Test
    void selfReferentialRecordsDoNotStackOverflow() {
        Linked head = GeneratedBinders.forType(Linked.class).parse(
                Tree.parse("{\"name\":\"a\",\"next\":{\"name\":\"b\",\"next\":null}}".getBytes(StandardCharsets.UTF_8)),
                "$", BindConfig.defaults());
        assertEquals("a", head.name());
        assertEquals("b", head.next().name());
        assertNull(head.next().next());
    }

    @Test
    void directCapableRecordsDeclareTheTokenReadAndTheFlag() throws Exception {
        // the third form rides the SAME instance: the capability flag plus the declared
        // parseTokens — and the generated read binds the canonical document exactly like
        // the tree path
        TypedBinder<BindFixtures.Scalars> binder =
                GeneratedBinders.forType(BindFixtures.Scalars.class);
        assertTrue(binder instanceof io.github.chutian0610.jolt.bind.GeneratedFields fields
                && fields.hasDirectRead());
        assertNotNull(binder.getClass().getDeclaredMethod("parseTokens",
                io.github.chutian0610.jolt.JoltParser.class, BindConfig.class));

        BindFixtures.Scalars direct = binder.parseTokens(
                Jolt.utf8Parser(BindFixtures.SCALARS_JSON.getBytes(StandardCharsets.UTF_8)),
                BindConfig.defaults());
        BindFixtures.Scalars tree = binder.parse(
                Tree.parse(BindFixtures.SCALARS_JSON.getBytes(StandardCharsets.UTF_8)), "$",
                BindConfig.defaults());
        assertEquals(tree, direct);
    }

    @Test
    void theWholeM3GraphIsDirectCapableNow() throws Exception {
        // T3 completed the spectrum: nested records, collections, enums, and POJOs are all
        // emittable — the Order graph (record child, list of records, map, enum, boxed
        // null, exact decimals) and the POJO declare BOTH direct forms and the flag
        for (Class<?> capable : new Class<?>[]{BindFixtures.Order.class,
                BindFixtures.LabelPojo.class, BindFixtures.Item.class}) {
            TypedBinder<?> binder = GeneratedBinders.forType(capable);
            assertTrue(binder instanceof io.github.chutian0610.jolt.bind.GeneratedFields fields
                    && fields.hasDirectRead(), capable.getName());
            assertNotNull(binder.getClass().getDeclaredMethod("parseTokens",
                    io.github.chutian0610.jolt.JoltParser.class, BindConfig.class),
                    capable.getName());
            assertNotNull(binder.getClass().getDeclaredMethod("parseTokensBody",
                    io.github.chutian0610.jolt.JoltParser.class, BindConfig.class),
                    capable.getName());
        }
    }

    // The visibility-refused fixtures below generate safely since #53: the capability
    // predicate gates BOTH grades before any emission (the whole type keeps the reflective
    // arm) — asserted in capabilityRefusedTypesKeepTheWholeTypeOnTheReflectiveArm and
    // capabilityRefusedTypesBindCorrectlyThroughTheReflectiveArm.

    public record Graded(String course, BindFixtures.Status status) {}

    public record Shelved(String id, BindFixtures.Item item) {}

    @Test
    void enumAndNestedRecordComponentsJoinTheDirectRead() throws Exception {
        // T2 widens the capability: enum components and nested RECORD children are
        // emittable — both forms declared, the flag raised, and the nested value binds
        // through the generated arm exactly like the tree path
        for (Class<?> capable : new Class<?>[]{Graded.class, Shelved.class}) {
            TypedBinder<?> binder = GeneratedBinders.forType(capable);
            assertTrue(binder instanceof io.github.chutian0610.jolt.bind.GeneratedFields fields
                    && fields.hasDirectRead(), capable.getName());
            assertNotNull(binder.getClass().getDeclaredMethod("parseTokensBody",
                    io.github.chutian0610.jolt.JoltParser.class, BindConfig.class),
                    capable.getName());
            assertNotNull(binder.getClass().getDeclaredMethod("parseTokens",
                    io.github.chutian0610.jolt.JoltParser.class, BindConfig.class),
                    capable.getName());
        }
        String doc = "{\"id\":\"s1\",\"item\":{\"sku\":\"a\",\"quantity\":2,\"price\":1.50}}";
        Object shelved = GeneratedBinders.forType(Shelved.class).parseTokens(
                Jolt.utf8Parser(doc.getBytes(StandardCharsets.UTF_8)), BindConfig.defaults());
        assertEquals(Bind.parse(doc, Shelved.class), shelved);
    }

    enum Pkg { A }

    public record WithPkgEnum(Pkg p) {}

    record HidRec(int v) {}

    public record WithHidRecord(HidRec r) {}

    /** The POJO twins of the defect shape: public POJOs, package-private property types. */
    public static class PkgChildPojo {
        private HidRec r;
        public HidRec getR() { return r; }
        public void setR(HidRec r) { this.r = r; }
    }

    public static class PkgEnumPojo {
        private Pkg p;
        public Pkg getP() { return p; }
        public void setP(Pkg p) { this.p = p; }
    }

    @Test
    void nonPublicComponentTypesKeepTheRecordOffTheDirectRead() {
        // the emitted bytecode NAMES every reference component type — LDC class constants
        // and CHECKCASTs resolve from the generated class's runtime package — so a
        // non-public enum or record component keeps the whole record on the reflective
        // binder (the capability predicate refuses, the facade falls back)
        assertFalse(RecordSpecializer.isDirectCapable(WithPkgEnum.class));
        assertFalse(RecordSpecializer.isDirectCapable(WithHidRecord.class));
        assertTrue(RecordSpecializer.isDirectCapable(Graded.class));
        assertTrue(RecordSpecializer.isDirectCapable(Shelved.class));
    }

    @Test
    void capabilityRefusedTypesKeepTheWholeTypeOnTheReflectiveArm() {
        // #53: the capability predicate gates BOTH grades, once per type, before any
        // emission — generation of these shapes used to crash (the LDC of the non-public
        // component class in jolt$initChildren, the pre-existing M5 defect recorded in
        // #49). The whole type keeps the reflective binder: no half-generated class, no
        // per-component fallback.
        for (Class<?> refused : new Class<?>[]{WithPkgEnum.class, WithHidRecord.class,
                PkgChildPojo.class, PkgEnumPojo.class}) {
            TypedBinder<?> binder = GeneratedBinders.forType(refused);
            assertFalse(binder.getClass().getName()
                            .startsWith("io.github.chutian0610.jolt.gen.Gen$"),
                    refused.getName());
        }
        assertTrue(RecordSpecializer.isDirectCapable(Graded.class));
        assertTrue(RecordSpecializer.isDirectCapable(Shelved.class));
        assertFalse(PojoSpecializer.isDirectCapable(PkgChildPojo.class));
        assertFalse(PojoSpecializer.isDirectCapable(PkgEnumPojo.class));
    }

    @Test
    void capabilityRefusedTypesBindCorrectlyThroughTheReflectiveArm() {
        // the regression cell: functional parity through the fallback, both directions,
        // for the record and POJO twins of the defect shape. Records assert value
        // equality against the facade's tree path; the POJO twins have no value equals,
        // so their parity is the byte-identical round trip itself.
        record Cell(Class<?> type, String json, boolean valueEquals) {}
        for (Cell cell : new Cell[]{
                new Cell(WithPkgEnum.class, "{\"p\":\"A\"}", true),
                new Cell(WithHidRecord.class, "{\"r\":{\"v\":7}}", true),
                new Cell(PkgEnumPojo.class, "{\"p\":\"A\"}", false),
                new Cell(PkgChildPojo.class, "{\"r\":{\"v\":7}}", false)}) {
            @SuppressWarnings("unchecked")
            TypedBinder<Object> binder =
                    (TypedBinder<Object>) (TypedBinder<?>) GeneratedBinders.forType(cell.type);
            Object parsed = binder.parse(
                    Tree.parse(cell.json.getBytes(StandardCharsets.UTF_8)), "$",
                    BindConfig.defaults());
            JoltWriter writer = Jolt.write();
            binder.write(parsed, writer);
            assertEquals(cell.json, writer.toString(), cell.type.getName());
            if (cell.valueEquals) {
                assertEquals(Bind.parse(cell.json, cell.type), parsed, cell.type.getName());
            }
        }
    }
}
