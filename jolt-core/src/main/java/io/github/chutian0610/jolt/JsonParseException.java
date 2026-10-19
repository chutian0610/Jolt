package io.github.chutian0610.jolt;

/**
 * The boundary exception (ADR-0005): raised only at the public API boundary when a document
 * violates strict RFC 8259 semantics. Position and excerpt reporting is completed in T4.
 */
public class JsonParseException extends RuntimeException {

    public JsonParseException(String message) {
        super(message);
    }
}
