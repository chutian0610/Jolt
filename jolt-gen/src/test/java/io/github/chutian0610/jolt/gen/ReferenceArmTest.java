package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.TypedBinder;
import io.github.chutian0610.jolt.bind.fixtures.BindFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M8-OFF reference arm (#56, the T1 recalibration's comparator): the M7-form specialist
 * — String-name chain through the frozen {@code fieldNameEquals(String)} overload + the
 * per-value door calls — under the {@code Gen$Ref$} namespace, cached separately, never
 * registered with the {@code Bind} facade. The gate's self-referential bar compares the M8
 * form against THIS form in the same build; the arm must therefore fail loudly (never
 * silently degrade) and resolve its nested children within its own namespace.
 */
class ReferenceArmTest {

    /** A fresh type per test so the facade-isolation assertion sees a clean registry. */
    public record Isolated(String id, int quantity) {}

    public record Shelved(String id, BindFixtures.Item item) {}

    /** Self-referential: termination must hold in the reference namespace too. */
    public record SelfRef(String name, SelfRef next) {}

    record Hidden(int v) {}

    @Test
    void referenceArmLivesInItsOwnNamespaceAndKeepsTheFacadeClean() {
        TypedBinder<?> ref = GeneratedBinders.referenceForType(Isolated.class);
        assertTrue(ref.getClass().getName().startsWith("io.github.chutian0610.jolt.gen.Gen$Ref$"),
                ref.getClass().getName());
        // the facade registry never sees the reference arm
        assertNull(Bind.generatedLookupForTest(Isolated.class),
                "the reference arm must not register with the Bind facade");
        // the main form is a distinct class of the same type
        TypedBinder<?> main = GeneratedBinders.forType(Isolated.class);
        assertTrue(main.getClass().getName().startsWith("io.github.chutian0610.jolt.gen.Gen$")
                        && !main.getClass().getName().startsWith(
                                "io.github.chutian0610.jolt.gen.Gen$Ref$"),
                main.getClass().getName());
        assertNotEquals(ref.getClass(), main.getClass());
    }

    @Test
    void referenceArmBindsIdenticallyToTheMainForm() {
        record Cell(Class<?> type, String json) {}
        for (Cell cell : new Cell[]{
                new Cell(BindFixtures.Scalars.class, BindFixtures.SCALARS_JSON),
                new Cell(BindFixtures.Order.class, BindFixtures.ORDER_JSON)}) {
            @SuppressWarnings("unchecked")
            TypedBinder<Object> reference =
                    (TypedBinder<Object>) (TypedBinder<?>) GeneratedBinders.referenceForType(cell.type);
            @SuppressWarnings("unchecked")
            TypedBinder<Object> main =
                    (TypedBinder<Object>) (TypedBinder<?>) GeneratedBinders.forType(cell.type);
            Object viaReference = reference.parseTokens(
                    Jolt.utf8Parser(cell.json.getBytes(StandardCharsets.UTF_8)),
                    BindConfig.defaults());
            Object viaMain = main.parseTokens(
                    Jolt.utf8Parser(cell.json.getBytes(StandardCharsets.UTF_8)),
                    BindConfig.defaults());
            assertEquals(Bind.parse(cell.json, cell.type), viaReference, cell.type.getName());
            assertEquals(viaMain, viaReference, cell.type.getName());
            // byte-identical writes: the two arms write the same bytes
            JoltWriter referenceWriter = Jolt.write();
            reference.write(viaReference, referenceWriter);
            JoltWriter mainWriter = Jolt.write();
            main.write(viaMain, mainWriter);
            assertEquals(mainWriter.toString(), referenceWriter.toString(), cell.type.getName());
        }
    }

    @Test
    void referenceArmResolvesItsChildrenWithinTheReferenceNamespace() {
        // an M8-off parent must not pick up an M8-form child through the main cache — the
        // gate's graph arm would otherwise measure a mixed stack
        TypedBinder<?> parent = GeneratedBinders.referenceForType(Shelved.class);
        Object childBinder = null;
        for (java.lang.reflect.Field field : parent.getClass().getDeclaredFields()) {
            if (field.getName().startsWith("jolt$child$")) {
                field.setAccessible(true);
                try {
                    childBinder = field.get(parent);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        assertEquals(GeneratedBinders.referenceForType(BindFixtures.Item.class).getClass(),
                childBinder.getClass(),
                "the reference parent's child must be a Gen$Ref$ child");
        String doc = "{\"id\":\"s1\",\"item\":{\"sku\":\"a\",\"quantity\":2,\"price\":1.50}}";
        Object parsed = parent.parseTokens(
                Jolt.utf8Parser(doc.getBytes(StandardCharsets.UTF_8)), BindConfig.defaults());
        assertEquals(Bind.parse(doc, Shelved.class), parsed);
    }

    @Test
    void selfReferentialRecordsTerminateInTheReferenceNamespace() {
        TypedBinder<?> binder = GeneratedBinders.referenceForType(SelfRef.class);
        String doc = "{\"name\":\"a\",\"next\":{\"name\":\"b\",\"next\":null}}";
        Object parsed = binder.parseTokens(
                Jolt.utf8Parser(doc.getBytes(StandardCharsets.UTF_8)), BindConfig.defaults());
        assertEquals(Bind.parse(doc, SelfRef.class), parsed);
    }

    @Test
    void referenceArmThrowsRatherThanSilentlyDegrading() {
        // a benchmark arm measuring a fallback unawares would lie — refused shapes throw
        assertThrows(IllegalStateException.class,
                () -> GeneratedBinders.referenceForType(WithPkg.class));
        assertThrows(IllegalStateException.class,
                () -> GeneratedBinders.referenceForType(Hidden.class));
    }

    public record WithPkg(Pkg p) {}

    enum Pkg { A }
}
