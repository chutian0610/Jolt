package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoltTryParseTest {

    @Test
    void successReturnsTheSharedSentinelAcrossCallsAndEngines() {
        ParseResult a = Jolt.tryParse(utf8("{\"a\":1}"));
        ParseResult b = Jolt.tryParse("[1,2,3]");
        ParseResult c = Jolt.tryParse("\"plain string\"");
        assertTrue(a.isSuccess());
        assertNull(a.getFailure());
        assertSame(a, b, "success must be the shared sentinel, not a per-call allocation");
        assertSame(b, c);
    }

    @Test
    void failureCarriesFullDiagnosticsOnBothEngines() {
        ParseResult bytes = Jolt.tryParse(utf8("{\n  \"a\": 1,\n  \"b\": ??\n}"));
        assertFalse(bytes.isSuccess());
        JsonParseException e = bytes.getFailure();
        assertNotNull(e);
        int expectedOffset = "{\n  \"a\": 1,\n  \"b\": ??\n}".indexOf('?');
        assertEquals(expectedOffset, e.getOffset());
        assertEquals(3, e.getLine());
        assertEquals(8, e.getColumn());
        assertTrue(e.getMessage().contains("Unexpected character"), e.getMessage());
        assertTrue(e.getExcerpt().contains("^"));

        ParseResult text = Jolt.tryParse("[1,]");
        assertFalse(text.isSuccess());
        assertEquals(3, text.getFailure().getOffset());
    }

    @Test
    void failureDiagnosticsMatchTheThrowingPathExactly() {
        String bad = "{\"a\":1,,}";
        JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> JoltTestSupport.drain(utf8(bad)));
        ParseResult result = Jolt.tryParse(utf8(bad));
        assertEquals(thrown.getOffset(), result.getFailure().getOffset());
        assertEquals(thrown.getLine(), result.getFailure().getLine());
        assertEquals(thrown.getColumn(), result.getFailure().getColumn());
        assertEquals(thrown.getMessage(), result.getFailure().getMessage());
    }

    @Test
    void lenientConfigIsHonored() {
        JoltConfig lenient = JoltConfig.strict().withComments();
        assertTrue(Jolt.tryParse(utf8("// c\n[1]"), lenient).isSuccess());
        assertFalse(Jolt.tryParse(utf8("// c\n[1]")).isSuccess());
        assertTrue(Jolt.tryParse("// c\n[1]", lenient).isSuccess());
    }

    @Test
    void tryParseDrainsTheWholeDocument() {
        // The failure is only visible at the very end; a half-parse would miss it.
        assertFalse(Jolt.tryParse(utf8("[1,2,3")).isSuccess());
        assertFalse(Jolt.tryParse(utf8("{\"a\":1} trailing")).isSuccess());
        assertTrue(Jolt.tryParse(utf8("{\"nested\":[{\"deep\":[true]}]}")).isSuccess());
    }
}
