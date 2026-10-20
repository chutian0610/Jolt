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
 * points). Failures propagate internally as an error code (fast return, no throws); the
 * {@link JsonParseException} materializes only at the public API boundary (ADR-0005).
 * Instances are mutable, reusable for one document at a time, and not thread-safe.
 */
public final class Utf8Parser implements JoltParser {

    private static final byte OBJECT = 0x7B; // '{'
    private static final byte ARRAY = 0x5B;  // '['

    // Parser states: where in the grammar the cursor currently is (mirrors Utf16Parser).
    private static final int ST_TOP_VALUE = 0;
    private static final int ST_TOP_AFTER = 1;
    private static final int ST_TOP_DONE = 2;
    private static final int ST_OBJ_KEY_OR_END = 3;
    private static final int ST_OBJ_KEY = 4;
    private static final int ST_COLON = 5;
    private static final int ST_VALUE = 6;
    private static final int ST_ARR_VALUE_OR_END = 7;
    private static final int ST_AFTER = 8;

    private final byte[] json;
    private final int maxDepth;
    // Lenient features (ADR-0001): final per-instance flags, individually opt-in; a disabled
    // feature's branch is never taken.
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

    // Error code (ADR-0005): the ONLY internal failure representation — a detail string plus
    // offset, written solely on the failure path; the exception materializes at the boundary.
    private String errorDetail;
    private int errorOffset;

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

    /**
     * Advances to and returns the next token; {@link Token#EOF} once the document is exhausted.
     * This is the boundary where the internal error code materializes as a
     * {@link JsonParseException} (ADR-0005).
     */
    public Token nextToken() {
        Token t = nextTokenInternal();
        if (t == null) {
            throw new JsonParseException(errorDetail, json, errorOffset);
        }
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
                return failToken("Unexpected end of input");
            }
            byte b = json[pos];
            pos++;
            switch (b) {
                case '{' -> {
                    if (!requireValuePosition(b) || !push(OBJECT)) {
                        return null;
                    }
                    state = ST_OBJ_KEY_OR_END;
                    return Token.START_OBJECT;
                }
                case '[' -> {
                    if (!requireValuePosition(b) || !push(ARRAY)) {
                        return null;
                    }
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
                        return failToken("',' not allowed here");
                    }
                    state = (top() == OBJECT) ? ST_OBJ_KEY : ST_VALUE;
                    continue;
                }
                case ':' -> {
                    if (state != ST_COLON) {
                        return failToken("':' not allowed here");
                    }
                    state = ST_VALUE;
                    continue;
                }
                case '"' -> {
                    if (state == ST_OBJ_KEY_OR_END || state == ST_OBJ_KEY) {
                        if (!scanStringSpan()) {
                            return null;
                        }
                        state = ST_COLON;
                        return Token.FIELD_NAME;
                    }
                    if (isValuePosition()) {
                        if (!scanStringSpan()) {
                            return null;
                        }
                        state = afterValue();
                        return Token.VALUE_STRING;
                    }
                    return failToken("'\"' not allowed here");
                }
                case 't' -> {
                    if (!requireValuePosition(b) || !scanLiteral("true")) {
                        return null;
                    }
                    state = afterValue();
                    return Token.VALUE_TRUE;
                }
                case 'f' -> {
                    if (!requireValuePosition(b) || !scanLiteral("false")) {
                        return null;
                    }
                    state = afterValue();
                    return Token.VALUE_FALSE;
                }
                case 'n' -> {
                    if (!requireValuePosition(b) || !scanLiteral("null")) {
                        return null;
                    }
                    state = afterValue();
                    return Token.VALUE_NULL;
                }
                case '/' -> {
                    // Only reachable as a comment opener; strict mode rejects here with the
                    // same branch it would otherwise never take.
                    if (!comments) {
                        return failToken("Unexpected character");
                    }
                    if (!skipComment()) {
                        return null;
                    }
                    continue;
                }
                default -> {
                    if ((b >= '0' && b <= '9') || b == '-') {
                        if (!requireValuePosition(b)) {
                            return null;
                        }
                        int kind = scanNumber();
                        return kind < 0 ? null
                                : kind == 1 ? Token.VALUE_NUMBER_FLOAT : Token.VALUE_NUMBER_INT;
                    }
                    return failToken("Unexpected character");
                }
            }
        }
    }

    /** Drains to EOF for the tryParse facade; never throws. */
    ParseResult drainOrFailure() {
        while (true) {
            Token t = nextTokenInternal();
            if (t == null) {
                return ParseResult.failure(new JsonParseException(errorDetail, json, errorOffset));
            }
            if (t == Token.EOF) {
                return ParseResult.success();
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
                                throw materializationError("unpaired surrogate escape in string");
                            }
                            p += 6;
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                        } else {
                            throw materializationError("unpaired surrogate escape in string");
                        }
                    } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                        throw materializationError("lone low surrogate escape in string");
                    }
                    out = encodeUtf8(cp, out);
                }
                default -> throw materializationError("invalid escape in string"); // scan validated
            }
        }
        return new String(scratch, 0, out, StandardCharsets.UTF_8);
    }

    /** A boundary exception thrown from a value reader (a materialization-time failure). */
    private JsonParseException materializationError(String detail) {
        return new JsonParseException(detail, json, valueStart);
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

    private boolean requireValuePosition(byte b) {
        return isValuePosition()
                || fail("value not allowed here (byte '" + (char) (b & 0xFF) + "')");
    }

    private int afterValue() {
        return (depth == 0) ? ST_TOP_AFTER : ST_AFTER;
    }

    /** Closes the current container if the grammar allows it here; null on failure. */
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
        return failToken(what + " not allowed here");
    }

    private boolean push(byte container) {
        if (depth == maxDepth) {
            return fail("nesting depth exceeds limit " + maxDepth);
        }
        if (depth == containerStack.length) {
            containerStack = Arrays.copyOf(containerStack,
                    Math.min(containerStack.length * 2, maxDepth));
        }
        containerStack[depth] = container;
        depth++;
        return true;
    }

    private void pop() {
        depth--;
        state = afterValue();
    }

    private byte top() {
        return containerStack[depth - 1];
    }

    // --- scanners ---

    /** Scans a string span; false (with the error code set) on any violation. */
    private boolean scanStringSpan() {
        stringHasEscape = false;
        valueStart = pos;
        while (pos < json.length) {
            byte b = json[pos];
            if (b == '"') {
                valueEnd = pos;
                pos++; // closing quote
                return true;
            }
            if (b == '\\') {
                stringHasEscape = true;
                if (!validateEscape()) {
                    return false;
                }
                continue;
            }
            if (b < 0) { // bytes >= 0x80 are signed-negative: multi-byte UTF-8 territory
                if (!validateUtf8Sequence()) {
                    return false;
                }
                continue;
            }
            if (b < 0x20) {
                return fail("raw control character in string");
            }
            pos++;
        }
        return fail("unterminated string");
    }

    /** Validates an escape sequence; the cursor sits on the backslash. */
    private boolean validateEscape() {
        pos++; // past backslash
        if (pos >= json.length) {
            return fail("unterminated escape");
        }
        byte e = json[pos++];
        switch (e) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                return true;
            }
            case 'u' -> {
                for (int i = 0; i < 4; i++) {
                    if (pos >= json.length) {
                        return fail("truncated \\u escape");
                    }
                    byte h = json[pos++];
                    boolean isHexDigit = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
                    if (!isHexDigit) {
                        return fail("invalid hex digit in \\u escape");
                    }
                }
                return true;
            }
            default -> {
                return fail("invalid escape '\\" + (char) (e & 0xFF) + "'");
            }
        }
    }

    /**
     * Validates one strictly-encoded UTF-8 sequence (rejects overlong forms, UTF-8-encoded
     * surrogates, and code points beyond U+10FFFF); the cursor sits on the leading byte.
     */
    private boolean validateUtf8Sequence() {
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
            return fail("invalid UTF-8 leading byte");
        }
        if (pos >= json.length) {
            return fail("truncated UTF-8 sequence");
        }
        int c = json[pos++] & 0xFF;
        if (c < firstLow || c > firstHigh) {
            return fail("invalid UTF-8 continuation");
        }
        for (int i = 1; i < continuations; i++) {
            if (pos >= json.length) {
                return fail("truncated UTF-8 sequence");
            }
            c = json[pos++] & 0xFF;
            if (c < 0x80 || c > 0xBF) {
                return fail("invalid UTF-8 continuation");
            }
        }
        return true;
    }

    private boolean scanLiteral(String literal) {
        int len = literal.length();
        for (int i = 1; i < len; i++) {
            if (pos >= json.length || json[pos] != literal.charAt(i)) {
                return fail("invalid literal, expected '" + literal + "'");
            }
            pos++;
        }
        valueStart = pos - len;
        valueEnd = pos;
        return true;
    }

    /**
     * Scans an RFC 8259 number starting at the already-consumed first byte: 1 for a float form,
     * 0 for an integer form, -1 (with the error code set) on any grammar violation. Rejects
     * leading zeros and missing digits around '.'/'e'.
     */
    private int scanNumber() {
        int start = pos - 1;
        int p = start;
        if (json[p] == '-') {
            p++;
        }
        if (p >= json.length) {
            return failInt("digit required after '-'");
        }
        if (json[p] == '0') {
            p++; // a leading zero is never followed by more integer digits
        } else if (json[p] >= '1' && json[p] <= '9') {
            while (p < json.length && isDigit(json[p])) {
                p++;
            }
        } else {
            return failInt("digit required in number");
        }
        boolean isFloat = false;
        if (p < json.length && json[p] == '.') {
            isFloat = true;
            p++;
            if (p >= json.length || !isDigit(json[p])) {
                return failInt("digit required after '.'");
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
                return failInt("digit required in exponent");
            }
            while (p < json.length && isDigit(json[p])) {
                p++;
            }
        }
        valueStart = start;
        valueEnd = p;
        pos = p;
        state = afterValue();
        return isFloat ? 1 : 0;
    }

    /** Skips a comment; the cursor sits just past the consumed '/'. */
    private boolean skipComment() {
        if (pos >= json.length) {
            return fail("unterminated comment");
        }
        byte c = json[pos];
        if (c == '/') {
            while (pos < json.length && json[pos] != '\n') {
                pos++;
            }
            return true; // the newline (or EOF) is handled by whitespace skipping on the next round
        }
        if (c == '*') {
            pos++;
            while (true) {
                if (pos + 1 >= json.length) {
                    return fail("unterminated comment");
                }
                if (json[pos] == '*' && json[pos + 1] == '/') {
                    pos += 2;
                    return true;
                }
                pos++;
            }
        }
        return fail("malformed comment");
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

    // --- error-code plumbing (ADR-0005) ---

    private boolean fail(String detail) {
        errorDetail = detail;
        errorOffset = Math.max(0, pos - 1);
        return false;
    }

    private Token failToken(String detail) {
        fail(detail);
        return null;
    }

    private int failInt(String detail) {
        fail(detail);
        return -1;
    }
}
