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

    private final String detail;
    private final transient byte[] jsonBytes; // exactly one of the three input forms is set
    private final transient String jsonText;
    private final transient char[] jsonChars;
    private final int offset;

    // Lazy, cached diagnostics (offset >= 0 only).
    private transient int line = -1;
    private transient int column = -1;
    private transient int lineStart = -1;
    private transient int lineEnd = -1;
    private transient String message;

    /** Detail-only form (no position). */
    public JsonParseException(String detail) {
        super(detail);
        this.detail = detail;
        this.jsonBytes = null;
        this.jsonText = null;
        this.jsonChars = null;
        this.offset = -1;
    }

    /** Positioned form for the UTF-8 engine. */
    public JsonParseException(String detail, byte[] json, int offset) {
        super((String) null);
        this.detail = detail;
        this.jsonBytes = json;
        this.jsonText = null;
        this.jsonChars = null;
        this.offset = offset;
    }

    /** Positioned form for the UTF-16 engine (char[] domain, no String re-materialization). */
    public JsonParseException(String detail, char[] json, int offset) {
        super((String) null);
        this.detail = detail;
        this.jsonBytes = null;
        this.jsonText = null;
        this.jsonChars = json;
        this.offset = offset;
    }

    /** Positioned form for String input. */
    public JsonParseException(String detail, String json, int offset) {
        super((String) null);
        this.detail = detail;
        this.jsonBytes = null;
        this.jsonText = json;
        this.jsonChars = null;
        this.offset = offset;
    }

    /** The error position in engine-local units (bytes for UTF-8, chars for UTF-16); -1 if none. */
    public int getOffset() {
        return offset;
    }

    /** 1-based line number of the error; -1 if unavailable. */
    public int getLine() {
        if (line == -1) {
            computePosition();
        }
        return line;
    }

    /** 1-based column number of the error; -1 if unavailable. */
    public int getColumn() {
        if (column == -1) {
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

    /** The error line with a caret marking the position; empty when no position. */
    public String getExcerpt() {
        if (offset < 0 || getLine() < 0) {
            return "";
        }
        return errorLine() + "\n" + " ".repeat(column - 1) + "^";
    }

    private void computePosition() {
        if (!hasInput()) {
            return; // post-deserialization or detail-only: diagnostics stay unavailable
        }
        int start = 0;
        int l = 1;
        for (int i = 0; i < offset; i++) {
            if (charAt(i) == '\n') {
                l++;
                start = i + 1;
            }
        }
        int end = offset;
        int len = inputLength();
        while (end < len && charAt(end) != '\n') {
            end++;
        }
        this.line = l;
        this.column = offset - start + 1;
        this.lineStart = start;
        this.lineEnd = end;
    }

    private boolean hasInput() {
        return jsonBytes != null || jsonText != null || jsonChars != null;
    }

    private int inputLength() {
        if (jsonBytes != null) {
            return jsonBytes.length;
        }
        return jsonChars != null ? jsonChars.length : jsonText.length();
    }

    private char charAt(int i) {
        if (jsonBytes != null) {
            return (char) (jsonBytes[i] & 0xFF);
        }
        return jsonChars != null ? jsonChars[i] : jsonText.charAt(i);
    }

    private String errorLine() {
        if (jsonBytes != null) {
            return new String(jsonBytes, lineStart, lineEnd - lineStart, StandardCharsets.UTF_8);
        }
        if (jsonChars != null) {
            return new String(jsonChars, lineStart, lineEnd - lineStart);
        }
        return jsonText.substring(lineStart, lineEnd);
    }
}
