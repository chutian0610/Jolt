package io.github.chutian0610.jolt;

import java.nio.charset.StandardCharsets;

/**
 * Streaming token-pull parser over a UTF-8 byte[]. Tokens record spans of the input; values are
 * materialized lazily by the value readers (lazy materialization). The parse loop is an iterative
 * flat state machine — no recursion — with an explicit container stack bounded by the configurable
 * nesting depth limit (default 512). Instances are mutable, reusable for one document at a time,
 * and not thread-safe.
 */
public final class JoltParser {

    /** Default nesting depth limit. */
    public static final int DEFAULT_MAX_DEPTH = 512;

    private static final byte OBJECT = 0x7B; // '{'
    private static final byte ARRAY = 0x5B;  // '['

    // Parser states: where in the grammar the cursor currently is.
    private static final int ST_TOP_VALUE = 0;     // expecting the single top-level value
    private static final int ST_TOP_AFTER = 1;     // top-level value consumed; only EOF (or ws) is legal
    private static final int ST_TOP_DONE = 2;      // EOF returned; nextToken() keeps returning EOF
    private static final int ST_OBJ_KEY_OR_END = 3; // '{' just opened: '"' key or '}'
    private static final int ST_OBJ_KEY = 4;       // after ',': a '"' key is mandatory
    private static final int ST_COLON = 5;         // after a key: ':' is mandatory
    private static final int ST_VALUE = 6;         // a value is mandatory (after ':' or after ',' in an array)
    private static final int ST_ARR_VALUE_OR_END = 7; // '[' just opened: value or ']'
    private static final int ST_AFTER = 8;         // value consumed inside a container: ',', or the closing bracket

    private final byte[] json;
    private final int maxDepth;
    private final byte[] containerStack;

    private int pos;
    private int depth;
    private int state = ST_TOP_VALUE;

    // Span of the most recent value-bearing token; decoding happens in the readers.
    private int valueStart;
    private int valueEnd;

    JoltParser(byte[] json) {
        this(json, DEFAULT_MAX_DEPTH);
    }

    JoltParser(byte[] json, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
        this.json = json;
        this.maxDepth = maxDepth;
        this.containerStack = new byte[maxDepth];
    }

    /** Advances to and returns the next token; {@link Token#EOF} once the document is exhausted. */
    public Token nextToken() {
        while (true) {
            skipWhitespace();
            if (pos >= json.length) {
                if (state == ST_TOP_AFTER) {
                    state = ST_TOP_DONE;
                    return Token.EOF;
                }
                if (state == ST_TOP_DONE) {
                    return Token.EOF;
                }
                throw error("Unexpected end of input");
            }
            byte b = json[pos];
            pos++;
            switch (b) {
                case '{' -> {
                    requireValuePosition(b);
                    push(OBJECT);
                    state = ST_OBJ_KEY_OR_END;
                    return Token.START_OBJECT;
                }
                case '[' -> {
                    requireValuePosition(b);
                    push(ARRAY);
                    state = ST_ARR_VALUE_OR_END;
                    return Token.START_ARRAY;
                }
                case '}' -> {
                    if (state == ST_OBJ_KEY_OR_END) {
                        pop();
                        return Token.END_OBJECT;
                    }
                    if (state == ST_AFTER && top() == OBJECT) {
                        pop();
                        return Token.END_OBJECT;
                    }
                    throw error("'}' not allowed here");
                }
                case ']' -> {
                    if (state == ST_ARR_VALUE_OR_END) {
                        pop();
                        return Token.END_ARRAY;
                    }
                    if (state == ST_AFTER && top() == ARRAY) {
                        pop();
                        return Token.END_ARRAY;
                    }
                    throw error("']' not allowed here");
                }
                case ',' -> {
                    if (state != ST_AFTER) {
                        throw error("',' not allowed here");
                    }
                    state = (top() == OBJECT) ? ST_OBJ_KEY : ST_VALUE;
                    continue;
                }
                case ':' -> {
                    if (state != ST_COLON) {
                        throw error("':' not allowed here");
                    }
                    state = ST_VALUE;
                    continue;
                }
                case '"' -> {
                    if (state == ST_OBJ_KEY_OR_END || state == ST_OBJ_KEY) {
                        scanStringSpan();
                        state = ST_COLON;
                        return Token.FIELD_NAME;
                    }
                    if (isValuePosition()) {
                        scanStringSpan();
                        state = afterValue();
                        return Token.VALUE_STRING;
                    }
                    throw error("'\"' not allowed here");
                }
                case 't' -> {
                    requireValuePosition(b);
                    scanLiteral("true");
                    state = afterValue();
                    return Token.VALUE_TRUE;
                }
                case 'f' -> {
                    requireValuePosition(b);
                    scanLiteral("false");
                    state = afterValue();
                    return Token.VALUE_FALSE;
                }
                case 'n' -> {
                    requireValuePosition(b);
                    scanLiteral("null");
                    state = afterValue();
                    return Token.VALUE_NULL;
                }
                default -> {
                    if ((b >= '0' && b <= '9') || b == '-') {
                        requireValuePosition(b);
                        return scanNumber() ? Token.VALUE_NUMBER_FLOAT : Token.VALUE_NUMBER_INT;
                    }
                    throw error("Unexpected character");
                }
            }
        }
    }

    /** The string content of the most recent string-bearing token. */
    public String textValue() {
        return new String(json, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8);
    }

    /** The integer value of the most recent {@link Token#VALUE_NUMBER_INT}. */
    public long longValue() {
        return Long.parseLong(textValue());
    }

    // --- state helpers ---

    private boolean isValuePosition() {
        return state == ST_TOP_VALUE || state == ST_VALUE || state == ST_ARR_VALUE_OR_END;
    }

    private void requireValuePosition(byte b) {
        if (!isValuePosition()) {
            throw error("value not allowed here (byte '" + (char) (b & 0xFF) + "')");
        }
    }

    private int afterValue() {
        return (depth == 0) ? ST_TOP_AFTER : ST_AFTER;
    }

    private void push(byte container) {
        if (depth == maxDepth) {
            throw error("nesting depth exceeds limit " + maxDepth);
        }
        containerStack[depth] = container;
        depth++;
    }

    private void pop() {
        depth--;
        state = afterValue();
    }

    private byte top() {
        return containerStack[depth - 1];
    }

    // --- scanners (grammar tightened in later slices) ---

    private void scanStringSpan() {
        valueStart = pos;
        while (pos < json.length) {
            byte b = json[pos];
            if (b == '"') {
                valueEnd = pos;
                pos++; // closing quote
                return;
            }
            if (b == '\\') {
                validateEscape();
                continue;
            }
            if (b < 0) { // bytes >= 0x80 are signed-negative: multi-byte UTF-8 territory
                validateUtf8Sequence();
                continue;
            }
            if (b < 0x20) {
                throw error("raw control character in string");
            }
            pos++;
        }
        throw error("unterminated string");
    }

    /** Validates an escape sequence; the cursor sits on the backslash. */
    private void validateEscape() {
        pos++; // past backslash
        if (pos >= json.length) {
            throw error("unterminated escape");
        }
        byte e = json[pos++];
        switch (e) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                return;
            }
            case 'u' -> {
                for (int i = 0; i < 4; i++) {
                    if (pos >= json.length) {
                        throw error("truncated \\u escape");
                    }
                    byte h = json[pos++];
                    boolean hex = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
                    if (!hex) {
                        throw error("invalid hex digit in \\u escape");
                    }
                }
                return;
            }
            default -> throw error("invalid escape '\\" + (char) (e & 0xFF) + "'");
        }
    }

    /**
     * Validates one strictly-encoded UTF-8 sequence (rejects overlong forms, UTF-8-encoded
     * surrogates, and code points beyond U+10FFFF); the cursor sits on the leading byte.
     */
    private void validateUtf8Sequence() {
        int b0 = json[pos] & 0xFF;
        pos++;
        int continuations;
        int firstLow = 0x80;
        int firstHigh = 0xBF;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            continuations = 1;
        } else if (b0 == 0xE0) {
            continuations = 2;
            firstLow = 0xA0; // reject overlong
        } else if ((b0 >= 0xE1 && b0 <= 0xEC) || b0 == 0xEE || b0 == 0xEF) {
            continuations = 2;
        } else if (b0 == 0xED) {
            continuations = 2;
            firstHigh = 0x9F; // reject surrogates D800–DFFF
        } else if (b0 == 0xF0) {
            continuations = 3;
            firstLow = 0x90; // reject overlong
        } else if (b0 >= 0xF1 && b0 <= 0xF3) {
            continuations = 3;
        } else if (b0 == 0xF4) {
            continuations = 3;
            firstHigh = 0x8F; // reject beyond U+10FFFF
        } else {
            throw error("invalid UTF-8 leading byte");
        }
        if (pos >= json.length) {
            throw error("truncated UTF-8 sequence");
        }
        int c = json[pos++] & 0xFF;
        if (c < firstLow || c > firstHigh) {
            throw error("invalid UTF-8 continuation");
        }
        for (int i = 1; i < continuations; i++) {
            if (pos >= json.length) {
                throw error("truncated UTF-8 sequence");
            }
            c = json[pos++] & 0xFF;
            if (c < 0x80 || c > 0xBF) {
                throw error("invalid UTF-8 continuation");
            }
        }
    }

    private void scanLiteral(String literal) {
        int len = literal.length();
        for (int i = 1; i < len; i++) {
            if (pos >= json.length || json[pos] != literal.charAt(i)) {
                throw error("invalid literal, expected '" + literal + "'");
            }
            pos++;
        }
        valueStart = pos - len;
        valueEnd = pos;
    }

    /**
     * Scans an RFC 8259 number starting at the already-consumed first byte; returns true when the
     * number has a fraction or exponent (a float form). Rejects leading zeros, missing digits
     * around '.'/'e', and everything else the grammar forbids.
     */
    private boolean scanNumber() {
        int start = pos - 1;
        int p = start;
        if (json[p] == '-') {
            p++;
        }
        if (p >= json.length) {
            throw error("digit required after '-'");
        }
        if (json[p] == '0') {
            p++; // a leading zero is never followed by more integer digits
        } else if (json[p] >= '1' && json[p] <= '9') {
            while (p < json.length && isDigit(json[p])) {
                p++;
            }
        } else {
            throw error("digit required in number");
        }
        boolean isFloat = false;
        if (p < json.length && json[p] == '.') {
            isFloat = true;
            p++;
            if (p >= json.length || !isDigit(json[p])) {
                throw error("digit required after '.'");
            }
            while (p < json.length && isDigit(json[p])) {
                p++;
            }
        }
        if (p < json.length && (json[p] == 'e' || json[p] == 'E')) {
            isFloat = true;
            p++;
            if (p < json.length && (json[p] == '+' || json[p] == '-')) {
                p++;
            }
            if (p >= json.length || !isDigit(json[p])) {
                throw error("digit required in exponent");
            }
            while (p < json.length && isDigit(json[p])) {
                p++;
            }
        }
        valueStart = start;
        valueEnd = p;
        pos = p;
        state = afterValue();
        return isFloat;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    private void skipWhitespace() {
        while (pos < json.length) {
            byte b = json[pos];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private JsonParseException error(String detail) {
        return new JsonParseException(detail + " at offset " + Math.max(0, pos - 1));
    }
}
