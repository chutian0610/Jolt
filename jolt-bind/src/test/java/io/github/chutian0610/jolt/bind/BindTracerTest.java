package io.github.chutian0610.jolt.bind;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The M3 tracer: JSON → record → JSON through the tree, content-identical. */
class BindTracerTest {

    record ScalarFixture(String name, long count, boolean ok) {}

    private static final String DOC = "{\"name\":\"jolt\",\"count\":42,\"ok\":true}";

    @Test
    void parsePopulatesByComponentName() {
        ScalarFixture fixture = Bind.parse(DOC.getBytes(StandardCharsets.UTF_8), ScalarFixture.class);
        assertEquals("jolt", fixture.name());
        assertEquals(42L, fixture.count());
        assertEquals(true, fixture.ok());
    }

    @Test
    void writeIsContentIdentical() {
        ScalarFixture fixture = Bind.parse(DOC.getBytes(StandardCharsets.UTF_8), ScalarFixture.class);
        assertEquals(DOC, Bind.write(fixture));
    }

    @Test
    void stringEngineRoundTripsEqually() {
        ScalarFixture fromBytes = Bind.parse(DOC.getBytes(StandardCharsets.UTF_8), ScalarFixture.class);
        ScalarFixture fromString = Bind.parse(DOC, ScalarFixture.class);
        assertEquals(fromBytes, fromString); // records: value equality for free
        assertEquals(DOC, Bind.write(fromString));
    }
}
