package io.github.chutian0610.jolt;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.function.BiFunction;

import static io.github.chutian0610.jolt.JoltTestSupport.engines;
import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The number-value matrix, replayed on BOTH engine adapters (architecture-review tripwire):
 * promotion boundaries, overflow, and number-text fidelity must hold engine-for-engine — a
 * drift in one engine's copy of the number arithmetic fails here, loudly.
 */
class JoltNumberValueTest {

    private static JoltParser atValue(BiFunction<String, Integer, JoltParser> parse, String json) {
        JoltParser parser = parse.apply(json, 512);
        parser.nextToken();
        return parser;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void longValueParsesInPlaceNeverThroughDouble(BiFunction<String, Integer, JoltParser> parse) {
        // 2^53 + 1: not representable as a double; must survive exactly on the long path.
        assertEquals(9007199254740993L, atValue(parse, "9007199254740993").longValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void longValueHandlesRangeExtremes(BiFunction<String, Integer, JoltParser> parse) {
        assertEquals(Long.MAX_VALUE, atValue(parse, "9223372036854775807").longValue());
        assertEquals(Long.MIN_VALUE, atValue(parse, "-9223372036854775808").longValue());
        assertEquals(0L, atValue(parse, "0").longValue());
        assertEquals(0L, atValue(parse, "-0").longValue());
        assertEquals(-42L, atValue(parse, "-42").longValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void longValueOverflowThrows(BiFunction<String, Integer, JoltParser> parse) {
        assertThrows(ArithmeticException.class, () -> atValue(parse, "9223372036854775808").longValue());
        assertThrows(ArithmeticException.class, () -> atValue(parse, "-9223372036854775809").longValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void longValueInsideDocuments(BiFunction<String, Integer, JoltParser> parse) {
        JoltParser parser = parse.apply("{\"a\":123,\"b\":[456]}", 512);
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(123L, parser.longValue());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals(Token.START_ARRAY, parser.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(456L, parser.longValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void numberTextReturnsUntouchedSource(BiFunction<String, Integer, JoltParser> parse) {
        assertEquals("1.50e2", atValue(parse, "1.50e2").numberText());
        assertEquals("-0.0", atValue(parse, "-0.0").numberText());
        assertEquals("42", atValue(parse, "42").numberText());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void integerWithinLongRangeStaysLong(BiFunction<String, Integer, JoltParser> parse) {
        assertEquals(Long.class, atValue(parse, "-42").numberValue().getClass());
        assertEquals(-42L, atValue(parse, "-42").numberValue());
        assertEquals(9007199254740993L, atValue(parse, "9007199254740993").numberValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void integerOverflowPromotesToBigDecimalExactly(BiFunction<String, Integer, JoltParser> parse) {
        Number n = atValue(parse, "9223372036854775808").numberValue();
        assertEquals(new BigDecimal("9223372036854775808"), n);
        Number neg = atValue(parse, "-9223372036854775809").numberValue();
        assertEquals(new BigDecimal("-9223372036854775809"), neg);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void ordinaryFloatStaysDouble(BiFunction<String, Integer, JoltParser> parse) {
        assertEquals(Double.class, atValue(parse, "1.5").numberValue().getClass());
        assertEquals(1.5, atValue(parse, "1.5").numberValue().doubleValue());
        assertEquals(0.1, atValue(parse, "0.1").numberValue().doubleValue());
        // 20 characters but 1 significant digit: stays on the double fast path, type pinned.
        assertEquals(Double.class, atValue(parse, "0.00000000000000000001").numberValue().getClass());
        assertEquals(1e-20, atValue(parse, "0.00000000000000000001").numberValue().doubleValue(), 0.0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void precisionHungryFloatPromotesToBigDecimal(BiFunction<String, Integer, JoltParser> parse) {
        Number n = atValue(parse, "0.1000000000000000000001").numberValue();
        assertEquals(new BigDecimal("0.1000000000000000000001"), n);
        Number long18 = atValue(parse, "1.23456789012345678").numberValue();
        assertEquals(new BigDecimal("1.23456789012345678"), long18);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void doubleOverflowPromotesToBigDecimal(BiFunction<String, Integer, JoltParser> parse) {
        Number n = atValue(parse, "1e309").numberValue();
        assertEquals(new BigDecimal("1e309"), n);
        Number neg = atValue(parse, "-1e309").numberValue();
        assertEquals(new BigDecimal("-1e309"), neg);
        Number under = atValue(parse, "1e-400").numberValue();
        assertEquals(new BigDecimal("1e-400"), under);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void doubleValueReadsFloatsAndInts(BiFunction<String, Integer, JoltParser> parse) {
        assertEquals(1.5, atValue(parse, "1.5").doubleValue());
        assertEquals(100.0, atValue(parse, "1e2").doubleValue());
        assertEquals(42.0, atValue(parse, "42").doubleValue());
        assertEquals(-2.5e3, atValue(parse, "-2.5e3").doubleValue());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void bigDecimalValueIsExactForAnyForm(BiFunction<String, Integer, JoltParser> parse) {
        assertEquals(new BigDecimal("0.1"), atValue(parse, "0.1").bigDecimalValue());
        assertEquals(new BigDecimal("-123.45e2"), atValue(parse, "-123.45e2").bigDecimalValue());
        assertEquals(new BigDecimal("42"), atValue(parse, "42").bigDecimalValue());
    }
}
