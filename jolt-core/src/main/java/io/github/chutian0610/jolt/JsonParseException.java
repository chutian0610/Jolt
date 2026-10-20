package io.github.chutian0610.jolt;

import java.nio.charset.StandardCharsets;

/**
 * The boundary exception (ADR-0005): raised only at the public API boundary when a document
 * violates strict RFC 8259 semantics. Line/column and excerpt diagnostics are computed lazily
 * and cached; the message is assembled on demand — a successful parse path never constructs
 * or pays for any of this.
 */
public class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * One document form as a diagnostic source. Adding a value source (M2: tree nodes,
     * streaming buffers) means one implementation and one constructor — never another branch.
     */
    private interface Input {
        int length();

        char charAt(int i);

        /** The document text in [start, end), decoded per the form's own units. */
        String text(int start, int end);
    }

    private static final class BytesInput implements Input {
        private final byte[] json;

        BytesInput(byte[] json) {
            this.json = json;
        }

        @Override
        public int length() {
            return json.length;
        }

        @Override
        public char charAt(int i) {
            return (char) (json[i] & 0xFF);
        }

        @Override
        public String text(int start, int end) {
            return new String(json, start, end - start, StandardCharsets.UTF_8);
        }
    }

    private static final class CharsInput implements Input {
        private final char[] json;

        CharsInput(char[] json) {
            this.json = json;
        }

        @Override
        public int length() {
            return json.length;
        }

        @Override
        public char charAt(int i) {
            return json[i];
        }

        @Override
        public String text(int start, int end) {
            return new String(json, start, end - start);
        }
    }

    private static final class TextInput implements Input {
        private final String json;

        TextInput(String json) {
            this.json = json;
        }

        @Override
        public int length() {
            return json.length();
        }

        @Override
        public char charAt(int i) {
            return json.charAt(i);
        }

        @Override
        public String text(int start, int end) {
            return json.substring(start, end);
        }
    }

    private final String detail;
    private final transient Input input; // null in the detail-only / deserialized form
    private final int offset;

    // Lazy, cached diagnostics (offset >= 0 only). A boolean gate — not a -1 sentinel —
    // because transient ints deserialize to 0, which must not fake a computed position.
    private transient boolean positionComputed;
    private transient int line = -1;
    private transient int column = -1;
    private transient int lineStart = -1;
    private transient int lineEnd = -1;
    private transient String message;

    /** Detail-only form (no position). */
    public JsonParseException(String detail) {
        super(detail);
        this.detail = detail;
        this.input = null;
        this.offset = -1;
    }

    /** Positioned form for the UTF-8 engine. */
    public JsonParseException(String detail, byte[] json, int offset) {
        super((String) null);
        this.detail = detail;
        this.input = new BytesInput(json);
        this.offset = offset;
    }

    /** Positioned form for the UTF-16 engine (char[] domain, no String re-materialization). */
    public JsonParseException(String detail, char[] json, int offset) {
        super((String) null);
        this.detail = detail;
        this.input = new CharsInput(json);
        this.offset = offset;
    }

    /** Positioned form for String input. */
    public JsonParseException(String detail, String json, int offset) {
        super((String) null);
        this.detail = detail;
        this.input = new TextInput(json);
        this.offset = offset;
    }

    /** The error position in engine-local units (bytes for UTF-8, chars for UTF-16); -1 if none. */
    public int getOffset() {
        return offset;
    }

    /** 1-based line number ('\n'-delimited) of the error; -1 if unavailable. */
    public int getLine() {
        if (!positionComputed) {
            computePosition();
        }
        return line;
    }

    /** 1-based column number of the error; -1 if unavailable. */
    public int getColumn() {
        if (!positionComputed) {
            computePosition();
        }
        return column;
    }

    @Override
    public String getMessage() {
        if (message == null) {
            message = offset >= 0 && getLine() > 0
                    ? detail + " at offset " + offset
                            + " (line " + getLine() + ", column " + getColumn() + ")"
                    : detail;
        }
        return message;
    }

    /** The error line with a caret marking the position (aligned to display columns); empty
     * when no position. */
    public String getExcerpt() {
        if (offset < 0 || getLine() < 0) {
            return "";
        }
        return input.text(lineStart, lineEnd) + "\n" + " ".repeat(displayPrefixLength()) + "^";
    }

    /** Display (character) width of the error line's prefix up to the error position. */
    private int displayPrefixLength() {
        return input.text(lineStart, offset).length();
    }

    private void computePosition() {
        if (input == null) {
            return; // post-deserialization or detail-only: diagnostics stay unavailable
        }
        int start = 0;
        int l = 1;
        for (int i = 0; i < offset; i++) {
            if (input.charAt(i) == '\n') {
                l++;
                start = i + 1;
            }
        }
        int end = offset;
        while (end < input.length() && input.charAt(end) != '\n') {
            end++;
        }
        this.line = l;
        this.column = offset - start + 1;
        this.lineStart = start;
        this.lineEnd = end;
        this.positionComputed = true;
    }
}
