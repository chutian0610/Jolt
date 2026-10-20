package io.github.chutian0610.jolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.github.chutian0610.jolt.JoltTestSupport.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoltExceptionDiagnosticsTest {

    // Line 1: {\n  Line 2: "a": 1,\n  Line 3: "b": ??\n  Line 4: }
    private static final String DOC = "{\n  \"a\": 1,\n  \"b\": ??\n}";
    private static final int ERROR_OFFSET = DOC.indexOf('?'); // first '?', line 3, col 8

    @Test
    void offsetLineAndColumnAreComputedLazilyAndOneBased() {
        JsonParseException e = new JsonParseException("bad character", utf8(DOC), ERROR_OFFSET);
        assertEquals(ERROR_OFFSET, e.getOffset());
        assertEquals(3, e.getLine());
        assertEquals(8, e.getColumn());
    }

    @Test
    void firstLineErrorsAreLineOne() {
        JsonParseException e = new JsonParseException("x", utf8("[1,]"), 4);
        assertEquals(1, e.getLine());
        assertEquals(5, e.getColumn());
    }

    @Test
    void messageComposesDetailAndLocation() {
        JsonParseException e = new JsonParseException("bad character", utf8(DOC), ERROR_OFFSET);
        assertTrue(e.getMessage().contains("bad character"), e.getMessage());
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
        assertTrue(e.getMessage().contains("column 8"), e.getMessage());
        assertTrue(e.getMessage().contains("offset " + ERROR_OFFSET), e.getMessage());
    }

    @Test
    void excerptShowsTheErrorLineWithCaret() {
        JsonParseException e = new JsonParseException("bad character", utf8(DOC), ERROR_OFFSET);
        String excerpt = e.getExcerpt();
        String[] lines = excerpt.split("\n", -1);
        assertEquals(2, lines.length);
        assertEquals("\"b\": ??", lines[0].trim()); // the full error line (leading indent trimmed)
        assertEquals('^', lines[1].charAt(e.getColumn() - 1));
    }

    @Test
    void stringInputDiagnosticsMatchByteInput() {
        JsonParseException fromBytes = new JsonParseException("d", utf8(DOC), ERROR_OFFSET);
        JsonParseException fromString = new JsonParseException("d", DOC, ERROR_OFFSET);
        assertEquals(fromBytes.getLine(), fromString.getLine());
        assertEquals(fromBytes.getColumn(), fromString.getColumn());
        assertEquals(fromBytes.getExcerpt(), fromString.getExcerpt());
    }

    @Test
    void legacyDetailOnlyConstructorStillWorks() {
        JsonParseException e = new JsonParseException("just a detail");
        assertEquals("just a detail", e.getMessage());
        assertEquals(-1, e.getOffset());
    }
}
