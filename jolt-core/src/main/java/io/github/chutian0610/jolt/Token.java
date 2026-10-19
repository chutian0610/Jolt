package io.github.chutian0610.jolt;

/**
 * Singleton tokens produced by the streaming parser. Values are materialized lazily by the value
 * readers on {@link JoltParser} (lazy materialization), never inside the token itself.
 */
public enum Token {
    START_OBJECT,
    END_OBJECT,
    START_ARRAY,
    END_ARRAY,
    FIELD_NAME,
    VALUE_STRING,
    VALUE_NUMBER_INT,
    VALUE_NUMBER_FLOAT,
    VALUE_TRUE,
    VALUE_FALSE,
    VALUE_NULL,
    EOF
}
