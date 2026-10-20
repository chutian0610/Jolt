package io.github.chutian0610.jolt;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * UTF-8 lexical engine: the {@code byte[]} implementation of {@link JoltParser}. Tokens record
 * spans of the input; values are materialized lazily by the value readers (lazy materialization).
 * The parse loop is an iterative flat state machine — no recursion — with an explicit container
 * stack bounded by the configurable nesting depth limit (default 512). String bytes are validated
 * strictly at the byte level (no overlong forms, encoded surrogates, or out-of-range code
 * points). Instances are mutable, reusable for one document at a time, and not thread-safe.
 */
public final class Utf8Parser implements JoltParser {

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
    // Lenient features (ADR-0001): final per-instance flags, individually opt-in; a disabled
    // feature's branch is never taken and the JIT specializes it away.
    private final boolean comments;
    private final boolean trailingCommas;
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
    private byte[] scratch;

    Utf8Parser(byte[] json) {
        this(json, JoltParser.DEFAULT_MAX_DEPTH, 0);
    }

    Utf8Parser(byte[] json, int maxDepth) {
        this(json, maxDepth, 0);
    }

    Utf8Parser(byte[] json, JoltConfig config) {
        this(json, config.maxDepth, config.features);
    }

    Utf8Parser(byte[] json, int maxDepth, int features) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
        this.json = json;
        this.maxDepth = maxDepth;
        this.comments = (features & JoltConfig.F_COMMENTS) != 0;
        this.trailingCommas = (features & JoltConfig.F_TRAILING_COMMAS) != 0;
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
                case '/' -> {
                    // Only reachable as a comment opener; strict mode rejects here with the
                    // same branch it would otherwise never take.
                    if (!comments) {
                        throw error("Unexpected character");
                    }
                    skipComment();
                    continue;
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

    /**
     * The decoded string content of the most recent string-bearing token. Two phases: a clean
     * span constructs directly from the input (no intermediate copies); an escaped span decodes
     * in a single pass into the reused scratch buffer. Escaped lone surrogates fail here, at
     * materialization — never during scanning (lazy materialization).
     */
    public String textValue() {
        if (!stringHasEscape) {
            return rawText();
        }
        return unescapeIntoScratch();
    }

    private String unescapeIntoScratch() {
        int len = valueEnd - valueStart;
        if (scratch == null || scratch.length < len) {
            scratch = new byte[Math.max(len, 64)];
        }
        int out = 0;
        int p = valueStart;
        while (p < valueEnd) {
            byte b = json[p++];
            if (b != '\\') {
                scratch[out++] = b;
                continue;
            }
            byte e = json[p++];
            switch (e) {
                case '"' -> scratch[out++] = '"';
                case '\\' -> scratch[out++] = '\\';
                case '/' -> scratch[out++] = '/';
                case 'b' -> scratch[out++] = 8;
                case 'f' -> scratch[out++] = 12;
                case 'n' -> scratch[out++] = 10;
                case 'r' -> scratch[out++] = 13;
                case 't' -> scratch[out++] = 9;
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
                    out = encodeUtf8(cp, out);
                }
                default -> throw error("invalid escape in string"); // unreachable: scan-time validation
            }
        }
        return new String(scratch, 0, out, StandardCharsets.UTF_8);
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

    private int encodeUtf8(int cp, int out) {
        if (cp < 0x80) {
            scratch[out++] = (byte) cp;
        } else if (cp < 0x800) {
            scratch[out++] = (byte) (0xC0 | (cp >> 6));
            scratch[out++] = (byte) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            scratch[out++] = (byte) (0xE0 | (cp >> 12));
            scratch[out++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            scratch[out++] = (byte) (0x80 | (cp & 0x3F));
        } else {
            scratch[out++] = (byte) (0xF0 | (cp >> 18));
            scratch[out++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
            scratch[out++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
            scratch[out++] = (byte) (0x80 | (cp & 0x3F));
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
        return new String(json, valueStart, valueEnd - valueStart, StandardCharsets.UTF_8);
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
            byte b = json[p];
            if (b >= '0' && b <= '9') {
                if (seenNonZero || b != '0') {
                    seenNonZero = true;
                    count++;
                }
            } else if (b == 'e' || b == 'E') {
                break;
            }
            // '.' just passes through
            p++;
        }
        return count;
    }

    private boolean spanHasNonZeroDigit() {
        for (int p = valueStart; p < valueEnd; p++) {
            byte b = json[p];
            if (b >= '1' && b <= '9') {
                return true;
            }
        }
        return false;
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

    /** Closes the current container if the grammar allows it here; otherwise fails. */
    private Token closeContainer(byte container, Token endToken, String what) {
        boolean atOpen = (container == OBJECT)
                ? state == ST_OBJ_KEY_OR_END
                : state == ST_ARR_VALUE_OR_END;
        if (atOpen || (state == ST_AFTER && top() == container)) {
            pop();
            return endToken;
        }
        if (trailingCommas
                && ((container == OBJECT && state == ST_OBJ_KEY)
                        || (container == ARRAY && state == ST_VALUE))) {
            // Exactly one trailing comma: ',' already advanced the state past ST_AFTER, and a
            // second ',' would have failed there — so this is the sole trailing position.
            pop();
            return endToken;
        }
        throw error(what + " not allowed here");
    }

    private void push(byte container) {
        if (depth == maxDepth) {
            throw error("nesting depth exceeds limit " + maxDepth);
        }
        if (depth == containerStack.length) {
            containerStack = Arrays.copyOf(containerStack,
                    Math.min(containerStack.length * 2, maxDepth));
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

    // --- scanners ---

    /** Skips a comment; the cursor sits just past the consumed '/'. */
    private void skipComment() {
        if (pos >= json.length) {
            throw error("unterminated comment");
        }
        byte c = json[pos];
        if (c == '/') {
            while (pos < json.length && json[pos] != '\n') {
                pos++;
            }
            return; // the newline (or EOF) is handled by whitespace skipping on the next round
        }
        if (c == '*') {
            pos++;
            while (true) {
                if (pos + 1 >= json.length) {
                    throw error("unterminated comment");
                }
                if (json[pos] == '*' && json[pos + 1] == '/') {
                    pos += 2;
                    return;
                }
                pos++;
            }
        }
        throw error("malformed comment");
    }

    private void scanStringSpan() {
        stringHasEscape = false;
        valueStart = pos;
        while (pos < json.length) {
            byte b = json[pos];
            if (b == '"') {
                valueEnd = pos;
                pos++; // closing quote
                return;
            }
            if (b == '\\') {
                stringHasEscape = true;
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
                    boolean isHexDigit = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
                    if (!isHexDigit) {
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
