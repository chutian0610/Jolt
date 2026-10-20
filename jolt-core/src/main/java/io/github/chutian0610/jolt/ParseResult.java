package io.github.chutian0610.jolt;

/**
 * The outcome of {@link Jolt#tryParse}: a shared success sentinel (zero allocation on success —
 * every successful validation returns the same instance) or a failure carrying the exact same
 * lazily-computed diagnostics (offset, line/column, excerpt) as the boundary exception
 * (ADR-0005).
 */
public final class ParseResult {

    private static final ParseResult SUCCESS = new ParseResult(null);

    private final JsonParseException failure;

    private ParseResult(JsonParseException failure) {
        this.failure = failure;
    }

    static ParseResult success() {
        return SUCCESS;
    }

    static ParseResult failure(JsonParseException failure) {
        return new ParseResult(failure);
    }

    public boolean isSuccess() {
        return failure == null;
    }

    /** The failure diagnostics; null on success. */
    public JsonParseException getFailure() {
        return failure;
    }
}
