package io.github.chutian0610.jolt.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.test.BindFixtures.Order;
import io.github.chutian0610.jolt.test.BindFixtures.Scalars;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The M3 differential + byte gates (T25), on the fixture graph.
 *
 * <p>Differential: Jackson databind and Bind load the SAME fixture documents into the SAME
 * record types — records' value equality is the comparison. Recorded divergences (documented
 * in the M3 spec and #25): natural-mapping integers bind to Long here vs Jackson's Integer,
 * and strict typing rejects the string coercions Jackson performs — so the differential
 * fixtures use declared types only, and the coercion/NFE divergences are pinned by the
 * error-matrix unit tests instead.
 *
 * <p>Byte gate: JSON → record → JSON is byte-identical for the canonical fixture documents
 * (the tree's verbatim-number serialization inherited through Bind.write).
 */
class BindFixtureGatesTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    @Test
    void jacksonAndBindAgreeOnTheOrderFixture() throws Exception {
        byte[] json = BindFixtures.ORDER_JSON.getBytes(StandardCharsets.UTF_8);
        Order viaJackson = JACKSON.readValue(json, Order.class);
        Order viaBind = Bind.parse(json, Order.class);
        assertEquals(viaJackson, viaBind);
    }

    @Test
    void jacksonAndBindAgreeOnTheScalarFixture() throws Exception {
        byte[] json = BindFixtures.SCALARS_JSON.getBytes(StandardCharsets.UTF_8);
        Scalars viaJackson = JACKSON.readValue(json, Scalars.class);
        Scalars viaBind = Bind.parse(json, Scalars.class);
        assertEquals(viaJackson, viaBind);
    }

    @Test
    void bindWriteIsByteIdenticalToCanonicalFixtures() {
        byte[] order = Bind.write(Bind.parse(BindFixtures.ORDER_JSON, Order.class))
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(BindFixtures.ORDER_JSON, new String(order, StandardCharsets.UTF_8));
        byte[] scalars = Bind.write(Bind.parse(BindFixtures.SCALARS_JSON, Scalars.class))
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(BindFixtures.SCALARS_JSON, new String(scalars, StandardCharsets.UTF_8));
    }

    @Test
    void jacksonWriteMatchesBindWriteOnTheOrderFixture() throws Exception {
        Order viaBind = Bind.parse(BindFixtures.ORDER_JSON, Order.class);
        String jacksonJson = JACKSON.writeValueAsString(viaBind);
        assertEquals(jacksonJson, Bind.write(viaBind));
    }

    @Test
    void byteFormInputRoundTripsIdentically() {
        byte[] json = BindFixtures.ORDER_JSON.getBytes(StandardCharsets.UTF_8);
        byte[] out = Bind.write(Bind.parse(json, Order.class)).getBytes(StandardCharsets.UTF_8);
        assertEquals(BindFixtures.ORDER_JSON, new String(out, StandardCharsets.UTF_8));
    }
}
