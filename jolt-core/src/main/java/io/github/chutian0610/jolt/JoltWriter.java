package io.github.chutian0610.jolt;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Streaming JSON writer producing compact UTF-8 output into a reusable, grow-on-demand buffer.
 * Structural write order is enforced by a state machine — the write-direction mirror of the
 * parser's — and separators (',' and ':') are inserted automatically, so a document completed
 * through this API is always valid strict JSON. Write-order misuse raises
 * {@link IllegalStateException} (a programming error); data failures such as lone surrogates
 * raise {@link JsonParseException} (the boundary exception). Nesting depth is bounded like the
 * parser (default {@link JoltParser#DEFAULT_MAX_DEPTH}).
 *
 * <p>Allocation contract: after buffer warm-up, the long/boolean/null and string paths allocate
 * zero bytes per call; {@code value(double)} and {@code value(BigDecimal)} still allocate via
 * {@code toString} (the same debt as the parser's parseDouble, deferred to T8).
 * Instances are reusable via {@link #reset()} (buffer retained) and not thread-safe.
 */
public final class JoltWriter {

    // Write states: the mirror of the parser's structural states.
    private static final int ST_TOP_VALUE = 0;
    private static final int ST_TOP_DONE = 1;
    private static final int ST_OBJ_KEY_OR_END = 2;
    private static final int ST_OBJ_KEY = 3;   // after ',' in an object: a field name is mandatory
    private static final int ST_VALUE = 4;     // a value is mandatory (after ':' or ',' in an array)
    private static final int ST_ARR_VALUE_OR_END = 5;
    private static final int ST_AFTER = 6;     // value done inside a container

    private static final byte OBJ = '{';
    private static final byte ARR = '[';

    private byte[] buf = new byte[256];
    private int len;
    private int depth;
    private byte[] stack = new byte[16];
    private int state = ST_TOP_VALUE;

    JoltWriter() {}

    public JoltWriter startObject() {
        beforeValue();
        writeByte('{');
        push(OBJ);
        state = ST_OBJ_KEY_OR_END;
        return this;
    }

    public JoltWriter endObject() {
        if (state != ST_OBJ_KEY_OR_END && !(state == ST_AFTER && top() == OBJ)) {
            throw new IllegalStateException("'}' not allowed in current state");
        }
        writeByte('}');
        pop();
        return this;
    }

    public JoltWriter startArray() {
        beforeValue();
        writeByte('[');
        push(ARR);
        state = ST_ARR_VALUE_OR_END;
        return this;
    }

    public JoltWriter endArray() {
        if (state != ST_ARR_VALUE_OR_END && !(state == ST_AFTER && top() == ARR)) {
            throw new IllegalStateException("']' not allowed in current state");
        }
        writeByte(']');
        pop();
        return this;
    }

    public JoltWriter fieldName(String name) {
        if (state == ST_AFTER) {
            if (top() != OBJ) {
                throw new IllegalStateException("field name not allowed inside an array");
            }
            writeByte(',');
            state = ST_OBJ_KEY;
        }
        if (state != ST_OBJ_KEY_OR_END && state != ST_OBJ_KEY) {
            throw new IllegalStateException("field name not allowed in current state");
        }
        writeByte('"');
        writeStringEscaped(name);
        writeByte('"');
        writeByte(':');
        state = ST_VALUE;
        return this;
    }

    public JoltWriter value(String value) {
        beforeValue();
        writeByte('"');
        writeStringEscaped(value);
        writeByte('"');
        afterValue();
        return this;
    }

    public JoltWriter value(long value) {
        beforeValue();
        ensure(20);
        if (value < 0) {
            buf[len++] = '-';
            if (value == Long.MIN_VALUE) {
                // No positive counterpart; emit the digit literal directly.
                literal(LONG_MIN_DIGITS);
                afterValue();
                return this;
            }
            value = -value;
        }
        long v = value;
        int digits = (v == 0) ? 1 : 0;
        while (v > 0) {
            digits++;
            v /= 10;
        }
        int end = len + digits;
        len = end;
        do {
            buf[--end] = (byte) ('0' + (value % 10));
            value /= 10;
        } while (value > 0);
        afterValue();
        return this;
    }

    public JoltWriter value(double value) {
        beforeValue();
        ascii(Double.toString(value));
        afterValue();
        return this;
    }

    public JoltWriter value(BigDecimal value) {
        beforeValue();
        ascii(value.toString());
        afterValue();
        return this;
    }

    public JoltWriter value(boolean value) {
        beforeValue();
        literal(value ? TRUE_BYTES : FALSE_BYTES);
        afterValue();
        return this;
    }

    public JoltWriter nullValue() {
        beforeValue();
        literal(NULL_BYTES);
        afterValue();
        return this;
    }

    /** The completed document as UTF-8 text; only legal on a closed document. */
    @Override
    public String toString() {
        requireDone();
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    /** The completed document as a fresh UTF-8 byte array; only legal on a closed document. */
    public byte[] toByteArray() {
        requireDone();
        return Arrays.copyOfRange(buf, 0, len);
    }

    /** Current output length in bytes. */
    public int length() {
        return len;
    }

    /** Clears the document but retains the buffer (and its capacity) for reuse. */
    public JoltWriter reset() {
        len = 0;
        depth = 0;
        state = ST_TOP_VALUE;
        return this;
    }

    // --- plumbing ---

    private void beforeValue() {
        if (state == ST_AFTER) {
            writeByte(',');
            state = (top() == OBJ) ? ST_OBJ_KEY : ST_VALUE;
        }
        if (state != ST_TOP_VALUE && state != ST_VALUE && state != ST_ARR_VALUE_OR_END) {
            throw new IllegalStateException("value not allowed in current state");
        }
    }

    private void afterValue() {
        state = (depth == 0) ? ST_TOP_DONE : ST_AFTER;
    }

    private void push(byte container) {
        if (depth == JoltParser.DEFAULT_MAX_DEPTH) {
            throw new IllegalStateException(
                    "nesting depth exceeds limit " + JoltParser.DEFAULT_MAX_DEPTH);
        }
        if (depth == stack.length) {
            stack = Arrays.copyOf(stack, stack.length * 2);
        }
        stack[depth] = container;
        depth++;
    }

    private void pop() {
        depth--;
        afterValue();
    }

    private byte top() {
        return stack[depth - 1];
    }

    private void requireDone() {
        if (state != ST_TOP_DONE) {
            throw new IllegalStateException("document is not complete");
        }
    }

    private void writeByte(int b) {
        ensure(1);
        buf[len++] = (byte) b;
    }

    private void ensure(int extra) {
        if (len + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
        }
    }

    private void ascii(String s) {
        literal(s.getBytes(StandardCharsets.US_ASCII));
    }

    private void literal(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, len, bytes.length);
        len += bytes.length;
    }

    private static final byte[] TRUE_BYTES = "true".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FALSE_BYTES = "false".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL_BYTES = "null".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LONG_MIN_DIGITS = "9223372036854775808".getBytes(StandardCharsets.US_ASCII);

    /**
     * Encodes one string into the buffer as escaped, UTF-8 JSON text — char by char, no
     * intermediate allocation. Mandatory escapes only (" \ and control characters); non-ASCII
     * passes through as UTF-8; surrogate pairs combine into their 4-byte form; lone surrogates
     * fail here, at the write boundary.
     */
    private void writeStringEscaped(String s) {
        int n = s.length();
        // long math then clamp: a plain n*6 overflows int for pathological (~3.6B-char) inputs.
        ensure((int) Math.min(2L + n * 6L, Integer.MAX_VALUE));
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                buf[len++] = '\\';
                buf[len++] = (byte) c;
            } else if (c < 0x20) {
                switch (c) {
                    case '\b' -> { buf[len++] = '\\'; buf[len++] = 'b'; }
                    case '\t' -> { buf[len++] = '\\'; buf[len++] = 't'; }
                    case '\n' -> { buf[len++] = '\\'; buf[len++] = 'n'; }
                    case '\f' -> { buf[len++] = '\\'; buf[len++] = 'f'; }
                    case '\r' -> { buf[len++] = '\\'; buf[len++] = 'r'; }
                    default -> {
                        buf[len++] = '\\';
                        buf[len++] = 'u';
                        buf[len++] = '0';
                        buf[len++] = '0';
                        buf[len++] = (byte) HEX[(c >>> 4) & 0xF];
                        buf[len++] = (byte) HEX[c & 0xF];
                    }
                }
            } else if (c < 0x80) {
                buf[len++] = (byte) c;
            } else if (c >= 0xD800 && c <= 0xDBFF) {
                i++;
                if (i >= n) {
                    throw new JsonParseException("unpaired surrogate in string");
                }
                char low = s.charAt(i);
                if (low < 0xDC00 || low > 0xDFFF) {
                    throw new JsonParseException("unpaired surrogate in string");
                }
                int cp = 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00);
                buf[len++] = (byte) (0xF0 | (cp >> 18));
                buf[len++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                buf[len++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                buf[len++] = (byte) (0x80 | (cp & 0x3F));
            } else if (c >= 0xDC00 && c <= 0xDFFF) {
                throw new JsonParseException("lone low surrogate in string");
            } else if (c < 0x800) {
                buf[len++] = (byte) (0xC0 | (c >> 6));
                buf[len++] = (byte) (0x80 | (c & 0x3F));
            } else {
                buf[len++] = (byte) (0xE0 | (c >> 12));
                buf[len++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                buf[len++] = (byte) (0x80 | (c & 0x3F));
            }
        }
    }

    private static final char[] HEX = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
}
