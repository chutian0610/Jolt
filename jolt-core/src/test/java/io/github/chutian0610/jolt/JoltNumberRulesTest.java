package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import static io.github.chutian0610.jolt.JoltTestSupport.tokens;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoltNumberRulesTest {

    private static void assertRejected(String json) {
        assertThrows(JsonParseException.class, () -> tokens(json), json);
    }

    @Test
    void classifiesIntegerForms() {
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_INT}, tokens("123"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_INT}, tokens("0"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_INT}, tokens("-0"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_INT}, tokens("-42"));
    }

    @Test
    void classifiesFloatForms() {
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_FLOAT}, tokens("0.5"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_FLOAT}, tokens("-1.5e10"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_FLOAT}, tokens("1E+2"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_FLOAT}, tokens("1e-2"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_FLOAT}, tokens("0e1"));
        assertArrayEquals(new Token[]{Token.VALUE_NUMBER_FLOAT}, tokens("-0.0"));
    }

    @Test
    void rejectsLeadingZeros() {
        assertRejected("01");
        assertRejected("-01");
    }

    @Test
    void rejectsPlusSignedNumbers() {
        assertRejected("+1");
    }

    @Test
    void rejectsNumberStartingWithDot() {
        assertRejected(".5");
    }

    @Test
    void rejectsTrailingDotOrExponentMarker() {
        assertRejected("1.");
        assertRejected("1e");
        assertRejected("1e+");
        assertRejected("1e-");
    }

    @Test
    void rejectsLoneMinus() {
        assertRejected("-");
    }

    @Test
    void rejectsNonFiniteLiterals() {
        assertRejected("Infinity");
        assertRejected("NaN");
        assertRejected("-Infinity");
    }

    @Test
    void rejectsHexForms() {
        assertRejected("0x1");
        assertRejected("1A");
    }

    @Test
    void numbersAsArrayItems() {
        assertArrayEquals(
                new Token[]{Token.START_ARRAY, Token.VALUE_NUMBER_INT, Token.VALUE_NUMBER_FLOAT,
                        Token.VALUE_NUMBER_INT, Token.END_ARRAY},
                tokens("[1,-2.5e3,0]"));
    }
}
