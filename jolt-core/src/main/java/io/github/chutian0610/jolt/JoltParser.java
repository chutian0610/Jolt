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
}
