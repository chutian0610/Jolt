package io.github.chutian0610.jolt.bind;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full record binding: nesting, the null contract, strict scalars, and the two-layer exceptions. */
class BindRecordTest {

    record Customer(String name, Integer tier, long total) {}

    record Order(String id, Customer customer) {}

    record AllScalars(byte b, short s, int i, long l, float f, double d,
                      boolean flag, String text, BigDecimal dec, BigInteger big,
                      Integer boxedNullable) {}

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void nestedRecordsBindRecursively() {
        Order order = Bind.parse(utf8("{\"id\":\"o1\",\"customer\":{\"name\":\"ann\",\"tier\":2,\"total\":150}}"),
                Order.class);
        assertEquals("o1", order.id());
        assertEquals("ann", order.customer().name());
        assertEquals(2, order.customer().tier());
        assertEquals(150L, order.customer().total());
    }

    @Test
    void missingReferenceComponentReadsAsNull() {
        Order order = Bind.parse(utf8("{\"id\":\"o2\"}"), Order.class);
        assertEquals("o2", order.id());
        assertNull(order.customer());
    }

    @Test
    void nestedPathCarriesInErrors() {
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"id\":\"o3\",\"customer\":{\"name\":true,\"tier\":1,\"total\":1}}"),
                        Order.class));
        assertEquals("$.customer.name", e.getPath());
        assertTrue(e.getMessage().contains("expected a string"), e.getMessage());
    }

    @Test
    void primitiveMissingAndNullThrow() {
        JsonBindException missing = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"name\":\"a\",\"ok\":true}"), BindTracerTest.ScalarFixture.class));
        assertEquals("$.count", missing.getPath());
        JsonBindException jsonNull = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"name\":\"a\",\"count\":null,\"ok\":true}"), BindTracerTest.ScalarFixture.class));
        assertEquals("$.count", jsonNull.getPath());
        assertTrue(jsonNull.getMessage().contains("JSON null"), jsonNull.getMessage());
    }

    @Test
    void everyScalarBindsExactly() {
        AllScalars v = Bind.parse(utf8("{\"b\":127,\"s\":32767,\"i\":2147483647,"
                        + "\"l\":9007199254740993,\"f\":1.5,\"d\":2.5,\"flag\":true,"
                        + "\"text\":\"x\",\"dec\":0.1000000000000000000001,"
                        + "\"big\":12345678901234567890123456789}"),
                AllScalars.class);
        assertEquals((byte) 127, v.b());
        assertEquals((short) 32767, v.s());
        assertEquals(2147483647, v.i());
        // lower bounds bind exactly (the review caught Long.MIN_VALUE being rejected);
        // each document fills every primitive so binding reaches the component under test
        assertEquals((byte) -128, Bind.parse(utf8(fullScalars("\"b\":-128")), AllScalars.class).b());
        assertEquals((short) -32768, Bind.parse(utf8(fullScalars("\"s\":-32768")), AllScalars.class).s());
        assertEquals(-2147483648, Bind.parse(utf8(fullScalars("\"i\":-2147483648")), AllScalars.class).i());
        assertEquals(-9223372036854775808L, Bind.parse(utf8(fullScalars("\"l\":-9223372036854775808")), AllScalars.class).l());
        assertEquals(9007199254740993L, v.l()); // never through double
        assertEquals(1.5f, v.f());
        assertEquals(2.5, v.d());
        assertTrue(v.flag());
        assertEquals("x", v.text());
        assertEquals(new BigDecimal("0.1000000000000000000001"), v.dec());
        assertEquals(new BigInteger("12345678901234567890123456789"), v.big());
        assertNull(v.boxedNullable());
    }

    @Test
    void scalarRangeAndLossinessThrowWithPath() {
        assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"b\":128}"), AllScalars.class).b());
        JsonBindException e = assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"b\":1,\"s\":1,\"i\":1,\"l\":1,\"f\":1.0,\"d\":1.0,\"flag\":true,\"text\":\"x\",\"dec\":1,\"big\":1.5}"), AllScalars.class).big());
        assertEquals("$.big", e.getPath());
        assertThrows(JsonBindException.class,
                () -> Bind.parse(utf8("{\"l\":9223372036854775808}"), AllScalars.class).l());
    }

    @Test
    void unknownFieldsAreIgnoredByDefault() {
        Order order = Bind.parse(
                utf8("{\"id\":\"o4\",\"customer\":null,\"sneaky\":{\"deep\":1},\"also\":true}"),
                Order.class);
        assertEquals("o4", order.id());
        assertNull(order.customer());
    }

    @Test
    void parseErrorsStayParseExceptions() {
        // syntax: the two-exception layering holds through the bind facade
        assertThrows(io.github.chutian0610.jolt.JsonParseException.class,
                () -> Bind.parse(utf8("{\"id\": oops}"), Order.class));
        // depth: unchanged core contract
        String deep = "[".repeat(513) + "1" + "]".repeat(513);
        assertThrows(io.github.chutian0610.jolt.JsonParseException.class,
                () -> Bind.parse(utf8(deep), Order.class));
    }

    /** A complete AllScalars document with one component overridden. */
    private static String fullScalars(String override) {
        return "{\"b\":1,\"s\":1,\"i\":1,\"l\":1,\"f\":1.0,\"d\":1.0,\"flag\":true,"
                + "\"text\":\"x\"," + override + "}";
    }
}
