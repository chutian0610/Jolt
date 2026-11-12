package io.github.chutian0610.jolt.bind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The error-text home's ONE-SOURCE pin: every fragment constant the emitters bake as an LDC
 * assembles, with the same parameters, to exactly what the runtime template method returns.
 * A wording change edits the fragment; this table forces the method (and every emitter that
 * inlines the fragment) to follow — drift between the two forms is a compile-time same-string
 * guarantee plus this test.
 */
class BindErrorTextTest {

    @Test
    void expectedButFound() {
        assertEquals("expected " + BindErrorText.A_STRING + " but found NUMBER",
                BindErrorText.expectedButFound(BindErrorText.A_STRING, "NUMBER"));
        assertEquals(BindErrorText.EXPECTED_OPEN + BindErrorText.A_NUMBER
                        + BindErrorText.EXPECTED_MIDDLE + "NULL",
                BindErrorText.expectedButFound(BindErrorText.A_NUMBER, "NULL"));
    }

    @Test
    void unknownField() {
        assertEquals("unknown field 'sneaky'", BindErrorText.unknownField("sneaky"));
        assertEquals(BindErrorText.UNKNOWN_FIELD_OPEN + "k" + BindErrorText.QUOTE,
                BindErrorText.unknownField("k"));
    }

    @Test
    void rejectionsCarryTheirCause() {
        RuntimeException cause = new IllegalStateException("nope");
        assertEquals("setter rejected the bound value: " + cause,
                BindErrorText.setterRejected(cause));
        assertEquals("record constructor rejected the bound values: " + cause,
                BindErrorText.constructorRejected(cause));
        assertEquals(BindErrorText.SETTER_REJECTED_OPEN + cause,
                BindErrorText.setterRejected(cause));
        assertEquals(BindErrorText.CONSTRUCTOR_REJECTED_OPEN + cause,
                BindErrorText.constructorRejected(cause));
    }

    @Test
    void numberWidthFamily() {
        assertEquals("number does not fit in byte", BindErrorText.boundError("byte"));
        assertEquals("number does not fit in Integer", BindErrorText.boundError("Integer"));
        assertEquals(BindErrorText.BOUND_OPEN + "Short", BindErrorText.boundError("Short"));
        assertEquals("number does not fit in a long", BindErrorText.LONG_OVERFLOW);
        assertEquals("number is not a whole number", BindErrorText.NOT_WHOLE_NUMBER);
    }

    @Test
    void collectionAndShapeFamily() {
        assertEquals("map keys must be String, got java.lang.Integer",
                BindErrorText.mapKeysMustBeString("java.lang.Integer"));
        assertEquals(BindErrorText.MAP_KEYS_OPEN + "X",
                BindErrorText.mapKeysMustBeString("X"));
        assertEquals("unsupported generic type java.util.List<java.lang.String>",
                BindErrorText.unsupportedGenericType("java.util.List<java.lang.String>"));
        assertEquals("polymorphic binding is not supported for Shell",
                BindErrorText.polymorphicUnsupported("Shell"));
        assertEquals(BindErrorText.POLYMORPHIC_OPEN + "Iface",
                BindErrorText.polymorphicUnsupported("Iface"));
    }

    @Test
    void pojoAndPrimitiveFamily() {
        assertEquals("cannot read property name", BindErrorText.cannotReadProperty("name"));
        assertEquals("cannot instantiate java.util.Date",
                BindErrorText.cannotInstantiate("java.util.Date"));
        assertEquals("missing value for primitive component total",
                BindErrorText.missingValueForPrimitive("total"));
        assertEquals("JSON null for primitive component total",
                BindErrorText.nullForPrimitive("total"));
        assertEquals(BindErrorText.MISSING_OPEN + "v",
                BindErrorText.missingValueForPrimitive("v"));
        assertEquals(BindErrorText.NULL_FOR_OPEN + "v",
                BindErrorText.nullForPrimitive("v"));
    }

    @Test
    void unknownEnumName() {
        assertEquals("unknown enum name 'GOLD' for Color",
                BindErrorText.unknownEnumName("GOLD", "Color"));
        assertEquals(BindErrorText.UNKNOWN_ENUM_OPEN + "X"
                        + BindErrorText.UNKNOWN_ENUM_MIDDLE + "Y",
                BindErrorText.unknownEnumName("X", "Y"));
    }
}
