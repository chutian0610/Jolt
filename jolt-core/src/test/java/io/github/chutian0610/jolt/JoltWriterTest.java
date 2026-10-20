package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltWriterTest {

    @Test
    void writesFlatObjectWithAutoCommasAndColons() {
        JoltWriter w = Jolt.write()
                .startObject()
                .fieldName("name").value("jolt")
                .fieldName("count").value(42)
                .fieldName("ratio").value(2.5)
                .fieldName("ok").value(true)
                .fieldName("nope").nullValue()
                .endObject();
        assertEquals("{\"name\":\"jolt\",\"count\":42,\"ratio\":2.5,\"ok\":true,\"nope\":null}",
                w.toString());
    }

    @Test
    void writesArraysScalarsAndEmptyContainers() {
        assertEquals("[1,-2,3]", Jolt.write().startArray().value(1).value(-2).value(3).endArray().toString());
        assertEquals("{}", Jolt.write().startObject().endObject().toString());
        assertEquals("[]", Jolt.write().startArray().endArray().toString());
        assertEquals("{\"a\":[[1],{\"b\":null}]}",
                Jolt.write().startObject().fieldName("a").startArray()
                        .startArray().value(1).endArray()
                        .startObject().fieldName("b").nullValue().endObject()
                        .endArray().endObject().toString());
    }

    @Test
    void writesTopLevelScalars() {
        assertEquals("\"s\"", Jolt.write().value("s").toString());
        assertEquals("42", Jolt.write().value(42).toString());
        assertEquals("true", Jolt.write().value(true).toString());
        assertEquals("null", Jolt.write().nullValue().toString());
        assertEquals("1.5", Jolt.write().value(1.5).toString());
    }

    @Test
    void writeBigDecimalAndNegativeForms() {
        assertEquals("1.234567890123456789", Jolt.write().value(new java.math.BigDecimal("1.234567890123456789")).toString());
        assertEquals("-42", Jolt.write().value(-42L).toString());
        assertEquals("9223372036854775807", Jolt.write().value(Long.MAX_VALUE).toString());
        assertEquals("-9223372036854775808", Jolt.write().value(Long.MIN_VALUE).toString());
    }

    @Test
    void enforcesWriteOrder() {
        assertThrows(IllegalStateException.class, () -> Jolt.write().startObject().endArray());
        assertThrows(IllegalStateException.class, () -> Jolt.write().startArray().fieldName("x"));
        assertThrows(IllegalStateException.class, () -> Jolt.write().startObject().value("v"));
        // a field without its value cannot be flushed
        assertThrows(IllegalStateException.class, () -> Jolt.write().startObject().fieldName("k").toString());
        // unclosed document cannot be flushed
        assertThrows(IllegalStateException.class, () -> Jolt.write().startObject().toString());
        assertThrows(IllegalStateException.class, () -> Jolt.write().startArray().value(1).toString());
        // top level accepts exactly one value
        assertThrows(IllegalStateException.class, () -> Jolt.write().value(1).value(2));
    }

    @Test
    void resetAllowsReuseForAnotherDocument() {
        JoltWriter w = Jolt.write().startArray().value(1).endArray();
        assertEquals("[1]", w.toString());
        w.reset().startObject().fieldName("k").value("v").endObject();
        assertEquals("{\"k\":\"v\"}", w.toString());
    }

    @Test
    void escapesMandatoryCharacters() {
        assertEquals("\"a\\\"b\"", Jolt.write().value("a\"b").toString());
        assertEquals("\"a\\\\b\"", Jolt.write().value("a\\b").toString());
        assertEquals("\"\\b\\t\\n\\f\\r\"", Jolt.write().value("\b\t\n\f\r").toString());
        assertEquals("\"\\u0000\"", Jolt.write().value("\u0000").toString());
        assertEquals("\"\\u001f\"", Jolt.write().value("\u001f").toString());
        assertEquals("\"\\u000b\"", Jolt.write().value("\u000b").toString());
        // slash is NOT escaped
        assertEquals("\"a/b\"", Jolt.write().value("a/b").toString());
    }

    @Test
    void escapesFieldNamesToo() {
        assertEquals("{\"k\\t1\":1}",
                Jolt.write().startObject().fieldName("k\t1").value(1).endObject().toString());
    }

    @Test
    void passesNonAsciiThroughAsUtf8() {
        assertEquals("\"héllo € 😀\"", Jolt.write().value("héllo € 😀").toString());
        assertEquals("\"中文\"", Jolt.write().value("中文").toString());
    }

    @Test
    void surrogatePairsEncodeAsUtf8() {
        assertEquals("\"😀\"", Jolt.write().value("😀").toString());
    }

    @Test
    void loneSurrogatesFailAtWrite() {
        assertThrows(JsonParseException.class, () -> Jolt.write().value("\uD800").toString());
        assertThrows(JsonParseException.class, () -> Jolt.write().value("a\uDC00b").toString());
        assertThrows(JsonParseException.class, () -> Jolt.write().startObject().fieldName("\uD800").value(1).endObject().toString());
    }

    @Test
    void longDigitRenderingMatchesToString() {
        String doc = "{\"a\":0,\"b\":7,\"c\":-13,\"d\":1000000007,\"e\":-1000000007,\"f\":9007199254740993}";
        assertEquals(doc, Jolt.write().startObject()
                .fieldName("a").value(0L)
                .fieldName("b").value(7L)
                .fieldName("c").value(-13L)
                .fieldName("d").value(1000000007L)
                .fieldName("e").value(-1000000007L)
                .fieldName("f").value(9007199254740993L)
                .endObject().toString());
    }

    @Test
    void repeatedReuseStaysCorrect() {
        JoltWriter w = Jolt.write();
        for (int i = 0; i < 500; i++) {
            w.reset().startObject().fieldName("n").value(42).fieldName("s").value("x\ty").endObject();
            assertEquals("{\"n\":42,\"s\":\"x\\ty\"}", w.toString());
        }
    }

    @Test
    void fieldNameAfterArrayValueIsRejectedNotSilentlyAccepted() {
        // Found by review: this used to emit the invalid [1,"k":5].
        assertThrows(IllegalStateException.class,
                () -> Jolt.write().startArray().value(1).fieldName("k").value(5).endArray().toString());
    }

    @Test
    void toByteArrayEmitsExactEscapedBytes() {
        byte[] bytes = Jolt.write().value("a\"b\\c\u001fd😀").toByteArray();
        assertArrayEquals("\"a\\\"b\\\\c\\u001fd😀\"".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes);
    }

    @Test
    void writerDepthLimitMatchesTheParser() {
        JoltWriter w = Jolt.write();
        for (int i = 0; i < JoltParser.DEFAULT_MAX_DEPTH; i++) {
            w.startArray();
        }
        assertThrows(IllegalStateException.class, w::startArray);
    }
}
