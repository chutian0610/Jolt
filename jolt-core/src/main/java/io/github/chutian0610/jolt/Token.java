package io.github.chutian0610.jolt;

/**
 * Singleton tokens produced by the streaming parser. Values are materialized lazily by the value
 * readers on {@link JoltParser} (lazy materialization), never inside the token itself.
 */
public enum Token {
    START_OBJECT,
    END_OBJECT,
    FIELD_NAME,
    VALUE_NUMBER_INT,
    EOF
}
