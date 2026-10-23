package io.github.chutian0610.jolt.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chutian0610.jolt.bind.Bind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The natural-mapping contract (architecture review C1): what an untyped JSON value becomes is
 * an EXECUTABLE policy, not prose. Jackson is compared per shape — the integral divergence
 * (Long here, Integer there) is asserted BY NAME so M4's streaming bind inherits a gate, not a
 * javadoc note.
 */
class NaturalMappingGateTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    public record Box(List<Object> any) {}

    private static Object natural(String json) {
        return Bind.parse("{\"any\":[" + json + "]}", Box.class).any().get(0);
    }

    private static Object jackson(String json) throws Exception {
        Box b = JACKSON.readValue("{\"any\":[" + json + "]}", Box.class);
        return b.any().get(0);
    }

    @Test
    void integralMapsToLongDivergingFromJacksonByName() throws Exception {
        assertEquals(7L, natural("7"));
        assertEquals(Long.class, natural("7").getClass());
        assertEquals(Integer.valueOf(7), jackson("7")); // the named divergence: Jackson boxes Integer
        assertEquals(-42L, natural("-42"));
    }

    @Test
    void integralBeyondLongMapsToBigDecimal() {
        BigDecimal big = assertInstanceOf(BigDecimal.class, natural("123456789012345678901234567890"));
        assertEquals(new BigDecimal("123456789012345678901234567890"), big);
    }

    @Test
    void floatFormMapsToDoubleBestEffort() {
        assertEquals(2.5, natural("2.5"));
        assertEquals(Double.class, natural("2.5").getClass());
        assertEquals(1.5, natural("1.50")); // text differs; value identical — Double either way
    }

    @Test
    void infiniteDoubleOverflowsToBigDecimal() {
        assertEquals(new BigDecimal("1e400"), natural("1e400"));
    }

    @Test
    void remainingShapesMatchJacksonExactly() throws Exception {
        assertEquals(jackson("\"s\""), natural("\"s\""));
        assertEquals("s", natural("\"s\""));
        assertEquals(jackson("true"), natural("true"));
        assertEquals(Boolean.TRUE, natural("true"));
        assertNull(natural("null"));
        assertEquals(List.of(1L, 2L), natural("[1,2]"));
        assertEquals(Map.of("k", "v"), natural("{\"k\":\"v\"}"));
    }
}
