package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.tree.Tree;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The direct-bind tracer gate: the SAME document through BOTH config arms (tree default /
 * withDirect) — values equal, exceptions byte-identical (type, path, message). Any divergence
 * between the read paths is a gate failure, not a footnote.
 */
class DirectBindTest {

    /** The tracer record (public nested: cross-package reflective construction requires it). */
    public record Tracer(String name, int quantity, boolean active) {}

    public enum Status { ACTIVE, ARCHIVED }

    public record Inner(int x, java.math.BigDecimal price, Status status) {}

    public record Deep(String label, Inner inner, long total) {}

    public record Decimals(java.math.BigDecimal amount, java.math.BigInteger seq) {}

    public record Boxed(Integer i, Long l, Boolean b) {}

    public record Widths(byte b, short s, long l, float f, double d) {}

    private static byte[] utf8(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** Both arms on one document: values must be equal. */
    private static <T> void assertTwoArmsEqual(String json, Class<T> type, BindConfig base) {
        T tree = Bind.parse(utf8(json), type, base);
        T direct = Bind.parse(utf8(json), type, base.withDirect());
        assertEquals(tree, direct);
    }

    /** Both arms on one failing document: same runtime type, message, and (bind errors) path. */
    private static <T> void assertTwoArmsFailIdentically(String json, Class<T> type,
                                                         BindConfig base) {
        RuntimeException tree = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(json), type, base));
        RuntimeException direct = assertThrows(RuntimeException.class,
                () -> Bind.parse(utf8(json), type, base.withDirect()));
        assertEquals(tree.getClass(), direct.getClass(), "exception type");
        assertEquals(tree.getMessage(), direct.getMessage(), "message");
        if (tree instanceof JsonBindException tb && direct instanceof JsonBindException db) {
            assertEquals(tb.getPath(), db.getPath(), "path");
        }
    }

    @Test
    void tracerTwoArmValueParity() {
        assertTwoArmsEqual("{\"name\":\"ann\",\"quantity\":2,\"active\":true}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmUnknownIgnoredByDefault() {
        assertTwoArmsEqual(
                "{\"name\":\"ann\",\"quantity\":2,\"active\":false,\"sneaky\":{\"deep\":[1,2]}}",
                Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmFailOnUnknownSamePathAndMessage() {
        assertTwoArmsFailIdentically("{\"name\":\"ann\",\"sneaky\":1}", Tracer.class, BindConfig.defaults().withFailOnUnknown());
    }

    @Test
    void tracerTwoArmTypeMismatchSamePathAndMessage() {
        assertTwoArmsFailIdentically("{\"name\":1,\"quantity\":2,\"active\":true}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmContainerMismatchSamePathAndMessage() {
        // a container where a scalar is expected — the direct loop must consume it and
        // stay aligned before raising
        assertTwoArmsFailIdentically("{\"name\":{\"x\":1},\"quantity\":2,\"active\":true}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmMissingPrimitiveSamePathAndMessage() {
        assertTwoArmsFailIdentically("{\"name\":\"ann\",\"active\":true}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmJsonNullForPrimitiveSamePathAndMessage() {
        assertTwoArmsFailIdentically("{\"name\":\"ann\",\"quantity\":null,\"active\":true}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmMissingReferenceReadsNull() {
        assertTwoArmsEqual("{\"quantity\":3,\"active\":true}", Tracer.class, BindConfig.defaults());
        Tracer direct = Bind.parse(utf8("{\"quantity\":3,\"active\":true}"), Tracer.class,
                BindConfig.defaults().withDirect());
        assertNull(direct.name());
    }

    @Test
    void tracerTwoArmJsonNullReferenceReadsNull() {
        assertTwoArmsEqual("{\"name\":null,\"quantity\":3,\"active\":true}", Tracer.class, BindConfig.defaults());
        Tracer direct = Bind.parse(utf8("{\"name\":null,\"quantity\":3,\"active\":true}"),
                Tracer.class, BindConfig.defaults().withDirect());
        assertNull(direct.name());
    }

    @Test
    void tracerTwoArmEmptyDocumentSameShape() {
        // an empty document dies in the parser itself (JsonParseException) on both arms —
        // the direct path consumes the same parser
        assertTwoArmsFailIdentically("", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmNonObjectRootThrowsMissingAtTheSamePath() {
        // a scalar root: the tree path reads every component as missing — the first
        // primitive component throws at its own path
        assertTwoArmsFailIdentically("5", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmIntBoundariesAndOverflow() {
        assertTwoArmsEqual("{\"name\":\"a\",\"quantity\":2147483647,\"active\":true}", Tracer.class, BindConfig.defaults());
        assertTwoArmsEqual("{\"name\":\"a\",\"quantity\":-2147483648,\"active\":true}", Tracer.class, BindConfig.defaults());
        assertTwoArmsFailIdentically(
                "{\"name\":\"a\",\"quantity\":2147483648,\"active\":true}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerTwoArmTailSyntaxErrorSameShape() {
        assertTwoArmsFailIdentically("{\"name\":\"a\",\"quantity\":1,\"active\":true} oops", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerDirectParsesRepeatedlyThroughTheReusedThreadParser() {
        BindConfig direct = BindConfig.defaults().withDirect();
        Tracer first = Bind.parse(utf8("{\"name\":\"a\",\"quantity\":1,\"active\":true}"),
                Tracer.class, direct);
        Tracer second = Bind.parse(utf8("{\"name\":\"b\",\"quantity\":2,\"active\":false}"),
                Tracer.class, direct);
        assertEquals(new Tracer("a", 1, true), first);
        assertEquals(new Tracer("b", 2, false), second);
    }

    @Test
    void tracerDirectWorksOnTheUtf16Engine() {
        String json = "{\"name\":\"ä\",\"quantity\":7,\"active\":true}";
        Tracer tree = Bind.parse(json, Tracer.class, BindConfig.defaults());
        Tracer direct = Bind.parse(json, Tracer.class, BindConfig.defaults().withDirect());
        assertEquals(tree, direct);
    }

    @Test
    void tracerDirectWriteSharesTheTreeArm() {
        // the write direction is shared — parse through the direct arm, write, and compare
        // against the tree arm's parse of the same document
        String json = "{\"name\":\"ann\",\"quantity\":2,\"active\":true}";
        Tracer direct = Bind.parse(utf8(json), Tracer.class, BindConfig.defaults().withDirect());
        Tracer tree = Bind.parse(utf8(json), Tracer.class, BindConfig.defaults());
        assertEquals(Bind.write(tree), Bind.write(direct));
    }

    @Test
    void tracerEscapedFieldNameMatchesOnTheDirectPath() {
        // "\\u0071uantity" decodes to "quantity" — the escaped fallback of the
        // zero-allocation field-name comparison
        assertTwoArmsEqual("{\"name\":\"ann\",\"\\u0071uantity\":2,\"active\":true}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void tracerFieldNameComparisonIsZeroAllocationInTheSteadyState() {
        // behavioral pin for the core increment: repeated comparisons against the same
        // parser hit the cache (the allocation proof itself is M6-T4's -prof gc run)
        var parser = io.github.chutian0610.jolt.Jolt.utf8Parser(
                utf8("{\"name\":\"ann\",\"quantity\":2,\"active\":true}"));
        assertEquals(io.github.chutian0610.jolt.Token.START_OBJECT, parser.nextToken());
        assertEquals(io.github.chutian0610.jolt.Token.FIELD_NAME, parser.nextToken());
        boolean name = parser.fieldNameEquals("name");
        boolean quantity = parser.fieldNameEquals("quantity");
        assertEquals(true, name);
        assertEquals(false, quantity);
        // non-FIELD_NAME state reports false rather than lying
        parser.nextToken(); // VALUE_STRING
        assertEquals(false, parser.fieldNameEquals("ann"));
    }

    @Test
    void nestedRecordTwoArmValueParity() {
        assertTwoArmsEqual("{\"label\":\"a\",\"inner\":{\"x\":5,\"price\":1.50,"
                + "\"status\":\"ACTIVE\"},\"total\":9007199254740993}", Deep.class, BindConfig.defaults());
        Deep direct = Bind.parse(utf8("{\"label\":\"a\",\"inner\":{\"x\":5,\"price\":1.50,"
                + "\"status\":\"ACTIVE\"},\"total\":9007199254740993}"), Deep.class,
                BindConfig.defaults().withDirect());
        assertEquals(5, direct.inner().x());
        assertEquals(new java.math.BigDecimal("1.50"), direct.inner().price());
        assertEquals(Status.ACTIVE, direct.inner().status());
        assertEquals(9007199254740993L, direct.total());
    }

    @Test
    void nestedRecordInnerErrorCarriesTheNestedPath() {
        // unknown enum name inside the nested record — same message and path on both arms
        assertTwoArmsFailIdentically("{\"label\":\"a\",\"inner\":{\"x\":5,\"price\":1.50,"
                + "\"status\":\"WHO\"},\"total\":1}", Deep.class, BindConfig.defaults());
    }

    @Test
    void nestedRecordMissingPrimitiveInsideCarriesTheNestedPath() {
        assertTwoArmsFailIdentically("{\"label\":\"a\",\"inner\":{\"price\":1.50,"
                + "\"status\":\"ACTIVE\"},\"total\":1}", Deep.class, BindConfig.defaults());
    }

    @Test
    void nestedRecordTypeMismatchInsideCarriesTheNestedPath() {
        assertTwoArmsFailIdentically("{\"label\":\"a\",\"inner\":{\"x\":true,\"price\":1.50,"
                + "\"status\":\"ACTIVE\"},\"total\":1}", Deep.class, BindConfig.defaults());
    }

    @Test
    void nestedRecordContainerValueConsumedBeforeTheError() {
        // an object where the nested scalar is expected — the loop must consume and align
        assertTwoArmsFailIdentically("{\"label\":\"a\",\"inner\":{\"x\":{\"z\":9},"
                + "\"price\":1.50,\"status\":\"ACTIVE\"},\"total\":1}", Tracer.class, BindConfig.defaults());
    }

    @Test
    void decimalsTwoArmParity() {
        assertTwoArmsEqual("{\"amount\":0.1000000000000000000001,"
                + "\"seq\":123456789012345678901234567890}", Decimals.class, BindConfig.defaults());
        Decimals direct = Bind.parse(utf8("{\"amount\":0.1000000000000000000001,"
                + "\"seq\":123456789012345678901234567890}"), Decimals.class,
                BindConfig.defaults().withDirect());
        assertEquals(new java.math.BigDecimal("0.1000000000000000000001"), direct.amount());
        assertEquals(new java.math.BigInteger("123456789012345678901234567890"), direct.seq());
    }

    @Test
    void decimalsBigIntegerFractionalThrowsSamePath() {
        assertTwoArmsFailIdentically("{\"amount\":1.5,\"seq\":123.45}", Decimals.class, BindConfig.defaults());
    }

    @Test
    void boxedNullablesTwoArmParity() {
        assertTwoArmsEqual("{\"i\":3,\"l\":9007199254740993,\"b\":true}", Boxed.class, BindConfig.defaults());
        assertTwoArmsEqual("{\"i\":null,\"l\":null,\"b\":null}", Boxed.class, BindConfig.defaults());
        assertTwoArmsEqual("{}", Boxed.class, BindConfig.defaults());
    }

    @Test
    void widthBoundsTwoArmParity() {
        assertTwoArmsEqual("{\"b\":-128,\"s\":-32768,\"l\":-9223372036854775808,"
                + "\"f\":1.5,\"d\":2.5}", Widths.class, BindConfig.defaults());
        assertTwoArmsFailIdentically("{\"b\":128,\"s\":0,\"l\":0,\"f\":0,\"d\":0}", Widths.class, BindConfig.defaults());
        assertTwoArmsFailIdentically("{\"b\":0,\"s\":32768,\"l\":0,\"f\":0,\"d\":0}", Widths.class, BindConfig.defaults());
    }

    @Test
    void boxedWidthOverflowMessageMatchesTheTreeArm() {
        // boxed Integer's bound message names "Integer", not "int" — the getSimpleName rule
        assertTwoArmsFailIdentically("{\"i\":2147483648,\"l\":0,\"b\":true}", Boxed.class, BindConfig.defaults());
    }
}
