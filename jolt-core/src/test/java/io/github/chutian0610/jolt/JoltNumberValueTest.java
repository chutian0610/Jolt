package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltNumberValueTest {

    private static JoltParser atValue(String json) {
        JoltParser parser = Jolt.parse(utf8(json));
        parser.nextToken();
        return parser;
    }

    @Test
    void longValueParsesInPlaceNeverThroughDouble() {
        // 2^53 + 1: not representable as a double; must survive exactly on the long path.
        assertEquals(9007199254740993L, atValue("9007199254740993").longValue());
    }

    @Test
    void longValueHandlesRangeExtremes() {
        assertEquals(Long.MAX_VALUE, atValue("9223372036854775807").longValue());
        assertEquals(Long.MIN_VALUE, atValue("-9223372036854775808").longValue());
        assertEquals(0L, atValue("0").longValue());
        assertEquals(0L, atValue("-0").longValue());
        assertEquals(-42L, atValue("-42").longValue());
    }

    @Test
    void longValueOverflowThrows() {
        assertThrows(ArithmeticException.class, () -> atValue("9223372036854775808").longValue());
        assertThrows(ArithmeticException.class, () -> atValue("-9223372036854775809").longValue());
    }

    @Test
    void longValueInsideDocuments() {
        JoltParser parser = Jolt.parse(utf8("{\"a\":123,\"b\":[456]}"));
        assertEquals(Token.START_OBJECT, parser.nextToken());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(123L, parser.longValue());
        assertEquals(Token.FIELD_NAME, parser.nextToken());
        assertEquals(Token.START_ARRAY, parser.nextToken());
        assertEquals(Token.VALUE_NUMBER_INT, parser.nextToken());
        assertEquals(456L, parser.longValue());
    }

    @Test
    void numberTextReturnsUntouchedSource() {
        assertEquals("1.50e2", atValue("1.50e2").numberText());
        assertEquals("-0.0", atValue("-0.0").numberText());
        assertEquals("42", atValue("42").numberText());
    }

    @Test
    void integerWithinLongRangeStaysLong() {
        assertEquals(Long.class, atValue("-42").numberValue().getClass());
        assertEquals(-42L, atValue("-42").numberValue());
        assertEquals(9007199254740993L, atValue("9007199254740993").numberValue());
    }

    @Test
    void integerOverflowPromotesToBigDecimalExactly() {
        Number n = atValue("9223372036854775808").numberValue();
        assertEquals(new BigDecimal("9223372036854775808"), n);
        Number neg = atValue("-9223372036854775809").numberValue();
        assertEquals(new BigDecimal("-9223372036854775809"), neg);
    }

    @Test
    void ordinaryFloatStaysDouble() {
        assertEquals(Double.class, atValue("1.5").numberValue().getClass());
        assertEquals(1.5, atValue("1.5").numberValue().doubleValue());
        assertEquals(0.1, atValue("0.1").numberValue().doubleValue());
        // 20 characters but 1 significant digit: stays on the double fast path, type pinned.
        assertEquals(Double.class, atValue("0.00000000000000000001").numberValue().getClass());
        assertEquals(1e-20, atValue("0.00000000000000000001").numberValue().doubleValue(), 0.0);
    }

    @Test
    void doubleOverflowPromotesToBigDecimal() {
        Number n = atValue("1e309").numberValue();
        assertEquals(new BigDecimal("1e309"), n);
        Number neg = atValue("-1e309").numberValue();
        assertEquals(new BigDecimal("-1e309"), neg);
        Number under = atValue("1e-400").numberValue();
        assertEquals(new BigDecimal("1e-400"), under);
    }

    @Test
    void doubleValueReadsFloatsAndInts() {
        assertEquals(1.5, atValue("1.5").doubleValue());
        assertEquals(100.0, atValue("1e2").doubleValue());
        assertEquals(42.0, atValue("42").doubleValue());
        assertEquals(-2.5e3, atValue("-2.5e3").doubleValue());
    }

    @Test
    void bigDecimalValueIsExactForAnyForm() {
        assertEquals(new BigDecimal("0.1"), atValue("0.1").bigDecimalValue());
        assertEquals(new BigDecimal("-123.45e2"), atValue("-123.45e2").bigDecimalValue());
        assertEquals(new BigDecimal("42"), atValue("42").bigDecimalValue());
    }
}
