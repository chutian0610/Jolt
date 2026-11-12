package io.github.chutian0610.jolt;

/**
 * The parse error text, one home for all three lexical engines (UTF-8, UTF-16, SIMD): every
 * detail string the scanners raise, assembled here so a wording change lands once and the
 * engine-parity gates check confirmation, not drift. Static methods, not string constants —
 * the literal lives ONLY in this class (a {@code static final String} would be inlined into
 * jolt-simd at compile time, baking a second copy into another artifact).
 *
 * <p>Every caller sits in a failure branch: the fail plumbing stores the detail and an
 * offset and returns its sentinel — the exception materializes only at the public API
 * boundary (ADR-0005) — so none of this is on the scanning fast path. The argument-taking
 * forms keep the concatenation in the failure branch too.
 *
 * <p>Engine ownership is explicit: the syntax family is shared by all three engines; the
 * {@code *Byte}/{@code *Char} pairs are the two lexical families' rendering of the same
 * rejection (a byte's Latin-1 form vs the real char); the UTF-8 sequence family is the
 * byte engines' own, and the raw-surrogate family is UTF-16's — the parity matrix maps
 * those two families across engines rather than comparing them.
 */
final class ParseErrors {

    private ParseErrors() {}

    // --- configuration ---

    static String maxDepthMustBeAtLeastOne(int maxDepth) {
        return "maxDepth must be >= 1, got " + maxDepth;
    }

    // --- structure: tokens and containers out of place ---

    static String commaNotAllowedHere() {
        return "',' not allowed here";
    }

    static String colonNotAllowedHere() {
        return "':' not allowed here";
    }

    static String quoteNotAllowedHere() {
        return "'\"' not allowed here";
    }

    static String notAllowedHere(String what) {
        return what + " not allowed here";
    }

    static String unexpectedCharacter() {
        return "Unexpected character";
    }

    static String unexpectedEndOfInput() {
        return "Unexpected end of input";
    }

    static String nestingDepthExceedsLimit(int maxDepth) {
        return "nesting depth exceeds limit " + maxDepth;
    }

    // --- strings: control characters, escapes, surrogates ---

    static String rawControlCharacterInString() {
        return "raw control character in string";
    }

    static String unterminatedString() {
        return "unterminated string";
    }

    static String unterminatedEscape() {
        return "unterminated escape";
    }

    static String truncatedUnicodeEscape() {
        return "truncated \\u escape";
    }

    static String invalidHexDigitInUnicodeEscape() {
        return "invalid hex digit in \\u escape";
    }

    /** The byte engines' rendering: the offending byte's Latin-1 form. */
    static String invalidEscapeByte(int b) {
        return "invalid escape '\\" + (char) (b & 0xFF) + "'";
    }

    /** The char engine's rendering: the offending character itself. */
    static String invalidEscapeChar(char e) {
        return "invalid escape '\\" + e + "'";
    }

    // materialization-time (the value read's own materialization point, not the fail plumbing)

    static String unpairedSurrogateEscape() {
        return "unpaired surrogate escape in string";
    }

    static String loneLowSurrogateEscape() {
        return "lone low surrogate escape in string";
    }

    static String invalidEscapeInString() {
        return "invalid escape in string";
    }

    /** The raw-scan rejection only the char engine can make (UTF-8 rejects bytes upstream). */
    static String unpairedSurrogateInString() {
        return "unpaired surrogate in string";
    }

    static String unpairedLowSurrogateInString() {
        return "unpaired low surrogate in string";
    }

    /** The value position outside any container — byte vs char rendering of the same check. */
    static String valueNotAllowedByte(int b) {
        return "value not allowed here (byte '" + (char) (b & 0xFF) + "')";
    }

    static String valueNotAllowedChar(char c) {
        return "value not allowed here (char '" + c + "')";
    }

    // --- UTF-8 sequence family (the byte engines' own) ---

    static String invalidUtf8LeadingByte() {
        return "invalid UTF-8 leading byte";
    }

    static String truncatedUtf8Sequence() {
        return "truncated UTF-8 sequence";
    }

    static String invalidUtf8Continuation() {
        return "invalid UTF-8 continuation";
    }

    // --- numbers ---

    static String digitRequiredAfterMinus() {
        return "digit required after '-'";
    }

    static String digitRequiredInNumber() {
        return "digit required in number";
    }

    static String digitRequiredAfterDot() {
        return "digit required after '.'";
    }

    static String digitRequiredInExponent() {
        return "digit required in exponent";
    }

    static String longOverflowAt(int valueStart) {
        return "long overflow in number at offset " + valueStart;
    }

    // --- literals ---

    static String invalidLiteral(String literal) {
        return "invalid literal, expected '" + literal + "'";
    }

    // --- comments ---

    static String unterminatedComment() {
        return "unterminated comment";
    }

    static String malformedComment() {
        return "malformed comment";
    }
}
