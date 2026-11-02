package io.github.chutian0610.jolt;

import java.math.BigDecimal;

/**
 * The streaming token-pull parser surface over one JSON document. Tokens record spans of the
 * input; values materialize lazily in the readers (lazy materialization). Two engines implement
 * this interface: {@link Utf8Parser} for UTF-8 {@code byte[]} input and {@link Utf16Parser} for
 * {@link String} input — identical grammar (strict RFC 8259), depth limits, and error
 * semantics, with no transcoding bridge between them.
 *
 * <p>Implementations are mutable, reusable for one document at a time, and not thread-safe.
 * Offsets reported in error messages are byte offsets in the UTF-8 engine and char offsets in
 * the UTF-16 engine — engine-local units, identical semantics.
 */
public interface JoltParser {

    /** Default nesting depth limit shared by both engines. */
    int DEFAULT_MAX_DEPTH = 512;

    /** Advances to and returns the next token; {@link Token#EOF} once the document is exhausted. */
    Token nextToken();

    /**
     * Skips the children of the most recent container token in one call (a no-op returning the
     * current token for scalars and closers). Skipping decodes nothing — lazy materialization.
     * After skipping, the span readers address the whole container's raw source text.
     */
    Token skipChildren();

    /**
     * The decoded string content of the most recent string-bearing token. Escaped lone
     * surrogates fail here, at materialization — never during scanning.
     */
    String textValue();

    /** The raw, undecoded source text of the most recent value-bearing token. */
    String rawText();

    /** The untouched source text of the most recent number token, exactly as it appeared. */
    String numberText();

    /**
     * The integer value of the most recent {@link Token#VALUE_NUMBER_INT}, parsed in place.
     * Domain overflow raises {@link ArithmeticException} (JDK convention for value-range
     * failures — distinct from parse errors, which are always {@link JsonParseException});
     * use {@link #numberValue()} for promotion instead.
     */
    long longValue();

    /**
     * The most recent number under the number-promotion policy (ADR-0002).
     */
    Number numberValue();

    /** The most recent number as a double. */
    double doubleValue();

    /** The most recent number as an exact {@link BigDecimal}. */
    BigDecimal bigDecimalValue();

    /**
     * Zero-allocation comparison of the most recent {@link Token#FIELD_NAME} token's decoded
     * text with {@code expected}. Field-name sets are fixed per binding target in steady
     * state, so repeated comparisons hit a per-parser cache and allocate nothing (the first
     * comparison with a new name may allocate its encoded form). Non-{@code FIELD_NAME}
     * state returns false. Escaped field names (rare) fall back to the decoded comparison,
     * which allocates — the never-taken fast/slow contract.
     *
     * <p><b>Frozen substrate:</b> this overload's single-entry cache is intentionally
     * untouched during M8 — the {@code Gen$Ref$} reference arm (the M8-off gate comparator,
     * spec #54's PIN) is the M7-form body over exactly this method. Its multi-name chain
     * thrash is the dissection's #1 cost (#55); the fix is the {@link FieldName} overload
     * below, not a change here.
     */
    boolean fieldNameEquals(String expected);

    /**
     * Zero-allocation comparison of the most recent {@link Token#FIELD_NAME} token's decoded
     * text with the pre-encoded {@code expected} key — the multi-name-chain form: binders
     * bake one {@link FieldName} per component name, so a match chain of any length compares
     * raw bytes/chars and never re-encodes (the String overload's per-parser cache holds one
     * name; a chain defeats it — the M8-T0 dissection's #1 cost, #55). Semantics identical to
     * {@link #fieldNameEquals(String)}: non-{@code FIELD_NAME} state returns false; escaped
     * field names (rare) fall back to the decoded comparison, which allocates.
     */
    boolean fieldNameEquals(FieldName expected);
}
