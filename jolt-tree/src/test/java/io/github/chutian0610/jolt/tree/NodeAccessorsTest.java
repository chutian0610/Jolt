package io.github.chutian0610.jolt.tree;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The strict scalar accessor contract: exact-or-throw, no silent defaults, no coercion. */
class NodeAccessorsTest {

    private static Node number(String text) {
        return Tree.parse(("{\"n\":" + text + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("n");
    }

    @Test
    void numberTextIsVerbatim() {
        assertEquals("1.50e2", number("1.50e2").numberText());
        assertEquals("9007199254740993", number("9007199254740993").numberText());
        assertEquals("-0.0", number("-0.0").numberText());
    }

    @Test
    void asDecimalIsExactAndCached() {
        Node n = number("0.1000000000000000000001");
        BigDecimal first = n.asDecimal();
        assertEquals(new BigDecimal("0.1000000000000000000001"), first);
        assertSame(first, n.asDecimal(), "the numeric view is computed once and cached");
    }

    @Test
    void asLongIsExactOrThrows() {
        assertEquals(42L, number("42").asLong());
        assertEquals(100L, number("1e2").asLong());
        assertEquals(9007199254740993L, number("9007199254740993").asLong());
        assertEquals(-9223372036854775808L, number("-9223372036854775808").asLong());
        assertThrows(ArithmeticException.class, () -> number("1.5").asLong());
        assertThrows(ArithmeticException.class, () -> number("9223372036854775808").asLong());
    }

    @Test
    void asDoubleIsBestEffort() {
        assertEquals(1.5, number("1.5").asDouble());
        assertEquals(100.0, number("1e2").asDouble());
        assertEquals(Double.POSITIVE_INFINITY, number("1e400").asDouble());
    }

    @Test
    void typeMismatchesThrow() {
        Node n = number("42");
        assertThrows(IllegalStateException.class, n::asText);
        assertThrows(IllegalStateException.class, n::asBoolean);
        Node s = Tree.parse("\"text\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, s::asLong);
        assertThrows(IllegalStateException.class, s::asDecimal);
        Node absent = Tree.parse("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("missing");
        assertNullAbsentHasNoType(absent);
    }

    private static void assertNullAbsentHasNoType(Node absent) {
        org.junit.jupiter.api.Assertions.assertNull(absent);
    }

    @Test
    void absurdExponentFailsLoudlyNotSilently() {
        // Beyond BigDecimal's grammar: the failure is the contract (never a silent default).
        assertThrows(NumberFormatException.class, () -> number("0.4e00669999999999").asDecimal());
    }
}
