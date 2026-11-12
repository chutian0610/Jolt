package io.github.chutian0610.jolt.bind;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Natural mapping's number narrowing, one home for both read paths: the decision table the
 * tree arm and the direct arm share verbatim. The laziness pins matter — integral text
 * never triggers the double parse, and the exact wide form materializes only on overflow or
 * infinity (steady-state natural reads stay allocation-light).
 */
class NaturalNarrowTest {

    /** Counts invocations — the laziness sentinel. */
    private static final class CountingWide implements java.util.function.Supplier<BigDecimal> {
        final AtomicInteger calls = new AtomicInteger();
        final BigDecimal value;

        CountingWide(BigDecimal value) {
            this.value = value;
        }

        @Override
        public BigDecimal get() {
            calls.incrementAndGet();
            return value;
        }
    }

    private static final java.util.function.DoubleSupplier UNUSED =
            () -> {
                throw new AssertionError("double parse must stay lazy for integral text");
            };

    @Test
    void integralTextReadsAsLong() {
        CountingWide wide = new CountingWide(BigDecimal.TEN);
        assertEquals(123L, BindValues.narrowNatural("123", UNUSED, wide));
        assertEquals(-45L, BindValues.narrowNatural("-45", UNUSED, wide));
        assertEquals(0L, BindValues.narrowNatural("0", UNUSED, wide));
        assertEquals(0, wide.calls.get());
    }

    @Test
    void integralOverflowTakesTheExactWideForm() {
        CountingWide wide = new CountingWide(new BigDecimal("99999999999999999999"));
        Object out = BindValues.narrowNatural("99999999999999999999", UNUSED, wide);
        assertEquals(new BigDecimal("99999999999999999999"), out);
        assertEquals(1, wide.calls.get());
    }

    @Test
    void decimalTextReadsAsDouble() {
        CountingWide wide = new CountingWide(BigDecimal.TEN);
        assertEquals(1.5d, BindValues.narrowNatural("1.5", () -> 1.5d, wide));
        assertEquals(-0.25d, BindValues.narrowNatural("-0.25", () -> -0.25d, wide));
        assertEquals(1.0e10d, BindValues.narrowNatural("1e10", () -> 1.0e10d, wide));
        assertEquals(0, wide.calls.get());
    }

    @Test
    void infiniteDoubleTakesTheExactWideForm() {
        CountingWide wide = new CountingWide(new BigDecimal("1e999"));
        Object out = BindValues.narrowNatural("1e999", () -> Double.POSITIVE_INFINITY, wide);
        assertEquals(new BigDecimal("1e999"), out);
        assertEquals(1, wide.calls.get());
    }

    @Test
    void narrowDoubleIsTheNonIntegralArm() {
        CountingWide finite = new CountingWide(BigDecimal.ONE);
        assertEquals(2.5d, BindValues.narrowDouble(2.5d, finite));
        assertEquals(0, finite.calls.get());

        CountingWide infinite = new CountingWide(BigDecimal.TEN);
        assertEquals(BigDecimal.TEN, BindValues.narrowDouble(Double.NEGATIVE_INFINITY, infinite));
        assertEquals(1, infinite.calls.get());
    }

    @Test
    void integralFormIsSignThenDigitsOnly() {
        assertTrue(BindValues.isIntegralForm("123"));
        assertTrue(BindValues.isIntegralForm("-123"));
        assertTrue(BindValues.isIntegralForm("0"));
        assertFalse(BindValues.isIntegralForm(""));
        assertFalse(BindValues.isIntegralForm("-"));
        assertFalse(BindValues.isIntegralForm("+1"));
        assertFalse(BindValues.isIntegralForm("1.0"));
        assertFalse(BindValues.isIntegralForm("1e5"));
        assertFalse(BindValues.isIntegralForm("12a"));
        assertFalse(BindValues.isIntegralForm("1 2"));
    }
}
