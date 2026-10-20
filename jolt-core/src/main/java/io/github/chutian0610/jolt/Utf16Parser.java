package io.github.chutian0610.jolt;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * UTF-16 lexical engine: the {@link String} implementation of {@link JoltParser}, mirroring
 * {@link Utf8Parser}'s structural state machine while reading chars directly — no transcoding
 * bridge (no UTF-8 round-trip). The input String is expanded to a char[] once at construction
 * (Java 9+ compact strings offer no public zero-copy path, and reflection into String internals
 * is forbidden by ADR-0003); after that, scanning and clean-string materialization never
 * transcode or re-buffer. Instances are mutable, reusable for one document at a time, and not
 * thread-safe.
 */
public final class Utf16Parser implements JoltParser {

    private static final char OBJECT = '{';
    private static final char ARRAY = '[';

    // Parser states: where in the grammar the cursor currently is (mirrors Utf8Parser).
    private static final int ST_TOP_VALUE = 0;
    private static final int ST_TOP_AFTER = 1;
    private static final int ST_TOP_DONE = 2;
    private static final int ST_OBJ_KEY_OR_END = 3;
    private static final int ST_OBJ_KEY = 4;
    private static final int ST_COLON = 5;
    private static final int ST_VALUE = 6;
    private static final int ST_ARR_VALUE_OR_END = 7;
    private static final int ST_AFTER = 8;

    private final char[] json;
    private final int maxDepth;
    // Grown on demand (doubling, capped at maxDepth) so a huge configured limit never
    // pre-allocates a huge array up front.
    private byte[] containerStack;

    private int pos;
    private int depth;
    private int state = ST_TOP_VALUE;

    // Span of the most recent value-bearing token; decoding happens in the readers.
    private int valueStart;
    private int valueEnd;
    private Token lastToken = Token.EOF;
    // Start offset of the most recent token (value or structural); anchors container spans.
    private int lastTokenStart;
    private int tokenStart;

    // True when the most recent string span contains at least one escape (two-phase unescape).
    private boolean stringHasEscape;
    // Reused decode buffer for escaped strings; grows on demand, never per parse.
    private char[] scratch;

    Utf16Parser(String json) {
        this(json, JoltParser.DEFAULT_MAX_DEPTH);
    }

    Utf16Parser(String json, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
        this.json = json.toCharArray();
        this.maxDepth = maxDepth;
        this.containerStack = new byte[Math.min(maxDepth, 16)];
    }

    /** Advances to and returns the next token; {@link Token#EOF} once the document is exhausted. */
    public Token nextToken() {
        Token t = nextTokenInternal();
        lastToken = t;
        lastTokenStart = tokenStart;
        return t;
    }

    private Token nextTokenInternal() {
        while (true) {
            skipWhitespace();
            tokenStart = pos;
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
            char c = json[pos];
            pos++;
            switch (c) {
                case '{' -> {
                    requireValuePosition(c);
                    push(OBJECT);
                    state = ST_OBJ_KEY_OR_END;
                    return Token.START_OBJECT;
                }
                case '[' -> {
                    requireValuePosition(c);
                    push(ARRAY);
                    state = ST_ARR_VALUE_OR_END;
                    return Token.START_ARRAY;
                }
                case '}' -> {
                    return closeContainer(OBJECT, Token.END_OBJECT, "'}'");
                }
                case ']' -> {
                    return closeContainer(ARRAY, Token.END_ARRAY, "']'");
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
                    requireValuePosition(c);
                    scanLiteral("true");
                    state = afterValue();
                    return Token.VALUE_TRUE;
                }
                case 'f' -> {
                    requireValuePosition(c);
                    scanLiteral("false");
                    state = afterValue();
                    return Token.VALUE_FALSE;
                }
                case 'n' -> {
                    requireValuePosition(c);
                    scanLiteral("null");
                    state = afterValue();
                    return Token.VALUE_NULL;
                }
                default -> {
                    if ((c >= '0' && c <= '9') || c == '-') {
                        requireValuePosition(c);
                        return scanNumber() ? Token.VALUE_NUMBER_FLOAT : Token.VALUE_NUMBER_INT;
                    }
                    throw error("Unexpected character");
                }
            }
        }
    }

    /**
     * The decoded string content of the most recent string-bearing token. Two phases: a clean
     * span constructs directly from the input (no intermediate buffers); an escaped span decodes
     * in a single pass into the reused scratch buffer. Escaped lone surrogates fail here, at
     * materialization — never during scanning (lazy materialization).
     */
    public String textValue() {
        if (!stringHasEscape) {
            return new String(json, valueStart, valueEnd - valueStart);
        }
        return unescapeIntoScratch();
    }

    private String unescapeIntoScratch() {
        int len = valueEnd - valueStart;
        if (scratch == null || scratch.length < len) {
            scratch = new char[Math.max(len, 64)];
        }
        int out = 0;
        int p = valueStart;
        while (p < valueEnd) {
            char c = json[p++];
            if (c != '\\') {
                scratch[out++] = c;
                continue;
            }
            char e = json[p++];
            switch (e) {
                case '"' -> scratch[out++] = '"';
                case '\\' -> scratch[out++] = '\\';
                case '/' -> scratch[out++] = '/';
                case 'b' -> scratch[out++] = '\b';
                case 'f' -> scratch[out++] = '\f';
                case 'n' -> scratch[out++] = '\n';
                case 'r' -> scratch[out++] = '\r';
                case 't' -> scratch[out++] = '\t';
                case 'u' -> {
                    int cp = hex4(p);
                    p += 4;
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        if (p + 6 <= valueEnd && json[p] == '\\' && json[p + 1] == 'u') {
                            int low = hex4(p + 2);
                            if (low < 0xDC00 || low > 0xDFFF) {
                                throw error("unpaired surrogate escape in string");
                            }
                            p += 6;
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                        } else {
                            throw error("unpaired surrogate escape in string");
                        }
                    } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                        throw error("lone low surrogate escape in string");
                    }
                    out = encodeChar(cp, out);
                }
                default -> throw error("invalid escape in string"); // unreachable: scan-time validation
            }
        }
        return new String(scratch, 0, out);
    }

    /** Reads 4 already-validated hex digits at {@code p} as a code unit. */
    private int hex4(int p) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int d = json[p + i];
            v = (v << 4) | (d <= '9' ? d - '0' : (d | 0x20) - 'a' + 10);
        }
        return v;
    }

    private int encodeChar(int cp, int out) {
        if (cp < 0x10000) {
            scratch[out++] = (char) cp;
        } else {
            cp -= 0x10000;
            scratch[out++] = (char) (0xD800 | (cp >> 10));
            scratch[out++] = (char) (0xDC00 | (cp & 0x3FF));
        }
        return out;
    }

    /**
     * The integer value of the most recent {@link Token#VALUE_NUMBER_INT}, parsed in place from
     * the recorded span (no intermediate String). Domain overflow raises {@link ArithmeticException}
     * (JDK convention for value-range failures — distinct from parse errors, which are always
     * {@link JsonParseException}); use {@link #numberValue()} for promotion instead.
     */
    public long longValue() {
        int p = valueStart;
        int end = valueEnd;
        boolean negative = false;
        if (json[p] == '-') {
            negative = true;
            p++;
        }
        // Accumulate negatively: Long.MIN_VALUE has no positive counterpart.
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long acc = 0;
        while (p < end) {
            int digit = json[p] - '0';
            if (acc < (limit + digit) / 10) {
                throw new ArithmeticException("long overflow in number at offset " + valueStart);
            }
            acc = acc * 10 - digit;
            p++;
        }
        return negative ? acc : -acc;
    }

    /**
     * Skips the children of the most recent container token in one call (a no-op returning the
     * current token for scalars and closers). Skipping decodes nothing — lazy materialization.
     * After skipping, the span readers address the whole container's raw source text.
     */
    public Token skipChildren() {
        if (lastToken != Token.START_OBJECT && lastToken != Token.START_ARRAY) {
            return lastToken;
        }
        int containerStart = lastTokenStart;
        int targetDepth = depth - 1;
        while (depth > targetDepth) {
            if (nextToken() == Token.EOF) {
                return Token.EOF;
            }
        }
        valueStart = containerStart;
        valueEnd = pos;
        stringHasEscape = false;
        return lastToken;
    }

    /** The raw, undecoded source text of the most recent value-bearing token. */
    public String rawText() {
        return new String(json, valueStart, valueEnd - valueStart);
    }

    /** The untouched source text of the most recent number token, exactly as it appeared. */
    public String numberText() {
        return rawText();
    }

    /**
     * The most recent number under the number-promotion policy (ADR-0002): integers within long
     * range as {@link Long}, integers that overflow as {@link BigDecimal}; floats up to 17
     * significant digits and within double range as {@link Double}, precision-hungry or
     * overflowing/underflowing floats as {@link BigDecimal}.
     */
    public Number numberValue() {
        if (lastToken == Token.VALUE_NUMBER_INT) {
            return fitsInLong() ? Long.valueOf(longValue()) : bigDecimalValue();
        }
        if (significantDigits() > 17) {
            return bigDecimalValue();
        }
        double d = Double.parseDouble(numberText());
        if (d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY
                || (d == 0.0 && spanHasNonZeroDigit())) {
            // Overflow, or underflow that would silently read as 0.
            return bigDecimalValue();
        }
        return d;
    }

    /**
     * The most recent number as a double. Correctness-first: delegates to
     * {@link Double#parseDouble}; a hand-written conversion lands with the T8 benchmark push.
     */
    public double doubleValue() {
        return Double.parseDouble(numberText());
    }

    /** The most recent number as an exact {@link BigDecimal}. */
    public BigDecimal bigDecimalValue() {
        return new BigDecimal(numberText());
    }

    /** Dry-runs the in-place accumulation to test long range without exception control flow. */
    private boolean fitsInLong() {
        int p = valueStart;
        int end = valueEnd;
        boolean negative = json[p] == '-';
        if (negative) {
            p++;
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long acc = 0;
        while (p < end) {
            int digit = json[p] - '0';
            if (acc < (limit + digit) / 10) {
                return false;
            }
            acc = acc * 10 - digit;
            p++;
        }
        return true;
    }

    /** Count of significant decimal digits in the span (leading zeros skipped), exponent ignored. */
    private int significantDigits() {
        int p = valueStart;
        int end = valueEnd;
        if (json[p] == '-') {
            p++;
        }
        boolean seenNonZero = false;
        int count = 0;
        while (p < end) {
            char c = json[p];
            if (c >= '0' && c <= '9') {
                if (seenNonZero || c != '0') {
                    seenNonZero = true;
                    count++;
                }
            } else if (c == 'e' || c == 'E') {
                break;
            }
            // '.' just passes through
            p++;
        }
        return count;
    }

    private boolean spanHasNonZeroDigit() {
        for (int p = valueStart; p < valueEnd; p++) {
            char c = json[p];
            if (c >= '1' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    // --- state helpers ---

    private boolean isValuePosition() {
        return state == ST_TOP_VALUE || state == ST_VALUE || state == ST_ARR_VALUE_OR_END;
    }

    private void requireValuePosition(char c) {
        if (!isValuePosition()) {
            throw error("value not allowed here (char '" + c + "')");
        }
    }

    private int afterValue() {
        return (depth == 0) ? ST_TOP_AFTER : ST_AFTER;
    }

    /** Closes the current container if the grammar allows it here; otherwise fails. */
    private Token closeContainer(char container, Token endToken, String what) {
        boolean atOpen = (container == OBJECT)
                ? state == ST_OBJ_KEY_OR_END
                : state == ST_ARR_VALUE_OR_END;
        if (atOpen || (state == ST_AFTER && top() == container)) {
            pop();
            return endToken;
        }
        throw error(what + " not allowed here");
    }

    private void push(char container) {
        if (depth == maxDepth) {
            throw error("nesting depth exceeds limit " + maxDepth);
        }
        if (depth == containerStack.length) {
            containerStack = Arrays.copyOf(containerStack,
                    Math.min(containerStack.length * 2, maxDepth));
        }
        containerStack[depth] = (byte) container;
        depth++;
    }

    private void pop() {
        depth--;
        state = afterValue();
    }

    private byte top() {
        return containerStack[depth - 1];
    }

    // --- scanners ---

    private void scanStringSpan() {
        stringHasEscape = false;
        valueStart = pos;
        while (pos < json.length) {
            char c = json[pos];
            if (c == '"') {
                valueEnd = pos;
                pos++; // closing quote
                return;
            }
            if (c == '\\') {
                stringHasEscape = true;
                validateEscape();
                continue;
            }
            if (c < 0x20) {
                throw error("raw control character in string");
            }
            pos++;
            // Chars >= 0x80 pass through unvalidated: UTF-16 has no byte-level encoding to check.
        }
        throw error("unterminated string");
    }

    /** Validates an escape sequence; the cursor sits on the backslash. */
    private void validateEscape() {
        pos++; // past backslash
        if (pos >= json.length) {
            throw error("unterminated escape");
        }
        char e = json[pos++];
        switch (e) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                return;
            }
            case 'u' -> {
                for (int i = 0; i < 4; i++) {
                    if (pos >= json.length) {
                        throw error("truncated \\u escape");
                    }
                    char h = json[pos++];
                    boolean isHexDigit = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
                    if (!isHexDigit) {
                        throw error("invalid hex digit in \\u escape");
                    }
                }
                return;
            }
            default -> throw error("invalid escape '\\" + e + "'");
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
     * Scans an RFC 8259 number starting at the already-consumed first char; returns true when the
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

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipWhitespace() {
        while (pos < json.length) {
            char c = json[pos];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
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
