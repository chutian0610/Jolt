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
    void recordTargetsGetStraightLineOverrides() throws Exception {
        Class<?> generated = GeneratedBinders.forType(BindFixtures.Order.class).getClass();
        // the overrides live ON the generated class — inherited plan execution fails this gate
        assertNotNull(generated.getDeclaredMethod("parse",
                io.github.chutian0610.jolt.tree.Node.class, String.class, BindConfig.class));
        assertNotNull(generated.getDeclaredMethod("write", Object.class, JoltWriter.class));
        assertNotNull(generated.getDeclaredMethod("writeFields",
                Object.class, JoltWriter.class, String.class));
        // POJO targets keep the M4 plan-executing binder: no parse override of their own
        Class<?> pojoGenerated = GeneratedBinders.forType(BindFixtures.LabelPojo.class).getClass();
        assertThrows(NoSuchMethodException.class, () -> pojoGenerated.getDeclaredMethod("parse",
                io.github.chutian0610.jolt.tree.Node.class, String.class, BindConfig.class));
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
}
