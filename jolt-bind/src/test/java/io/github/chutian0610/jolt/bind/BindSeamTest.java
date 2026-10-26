package io.github.chutian0610.jolt.bind;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The TypedBinder seam: cache identity, explicit-binder overloads, nested records. */
class BindSeamTest {

    private static byte[] utf8(String json) {
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    record Point(int x, int y) {}

    record Line(Point from, Point to) {}

    @Test
    void reflectiveBindersAreCachedPerClass() {
        assertSame(Bind.parse(utf8("{\"x\":1,\"y\":2}"), Point.class).getClass(),
                Bind.parse(utf8("{\"x\":3,\"y\":4}"), Point.class).getClass());
        // the seam's contract: binder identity is stable, values are not
        assertSame(ReflectiveBinder.forClass(Point.class), ReflectiveBinder.forClass(Point.class));
    }

    @Test
    void explicitBinderOverloadsMatchTheFacade() {
        Point direct = Bind.parse(utf8("{\"x\":1,\"y\":2}"), Point.class);
        TypedBinder<Point> binder = ReflectiveBinder.forClass(Point.class);
        Point explicit = Bind.parse(utf8("{\"x\":1,\"y\":2}"), binder);
        assertEquals(direct, explicit);
        assertEquals(Bind.write(direct), Bind.write(direct, binder));
    }

    @Test
    void nestedRecordsBindThroughCachedBinders() {
        Line line = Bind.parse(utf8("{\"from\":{\"x\":1,\"y\":2},\"to\":{\"x\":3,\"y\":4}}"), Line.class);
        assertEquals(new Point(1, 2), line.from());
        assertEquals(new Point(3, 4), line.to());
        // write round-trip through the same cached binders
        Line reloaded = Bind.parse(Bind.write(line).getBytes(StandardCharsets.UTF_8), Line.class);
        assertEquals(line, reloaded);
    }

    @Test
    void inaccessibleTargetsFailDiagnostically() throws Exception {
        // A package-private record in a DIFFERENT package cannot be constructed by Bind —
        // the failure must be diagnostic (with the reflection cause), never a raw
        // IllegalAccessException.
        @SuppressWarnings("unchecked")
        Class<? extends java.lang.Record> hidden =
                (Class<? extends java.lang.Record>) Class.forName(
                        "io.github.chutian0610.jolt.bind.hidden.HiddenRecord");
        var e = org.junit.jupiter.api.Assertions.assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"v\":1}"), hidden));
        assertTrue(e.getMessage().contains("cannot construct"), e.getMessage());
    }
}
