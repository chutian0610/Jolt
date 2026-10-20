package io.github.chutian0610.jolt;

import java.io.IOException;
import java.io.InputStream;

/**
 * Stateless entry points into the Jolt parser.
 */
public final class Jolt {

    private Jolt() {}

    /** Begins parsing a UTF-8 JSON document held in {@code json} with the default depth limit. */
    public static JoltParser parse(byte[] json) {
        return new Utf8Parser(json);
    }

    /** Begins parsing a UTF-8 document with an explicit nesting depth limit. */
    public static JoltParser parse(byte[] json, int maxDepth) {
        return new Utf8Parser(json, maxDepth);
    }

    /** Begins parsing a JSON document held in a {@link String} (UTF-16 engine, no transcoding). */
    public static JoltParser parse(String json) {
        return new Utf16Parser(json);
    }

    /** Begins parsing a {@link String} document with an explicit nesting depth limit. */
    public static JoltParser parse(String json, int maxDepth) {
        return new Utf16Parser(json, maxDepth);
    }

    /** Begins parsing a UTF-8 document under an explicit configuration. */
    public static JoltParser parse(byte[] json, JoltConfig config) {
        return new Utf8Parser(json, config);
    }

    /** Begins parsing a {@link String} document under an explicit configuration. */
    public static JoltParser parse(String json, JoltConfig config) {
        return new Utf16Parser(json, config);
    }

    /** Begins writing a JSON document into a reusable UTF-8 buffer. */
    public static JoltWriter write() {
        return new JoltWriter();
    }

    /** A concrete UTF-8 engine instance for zero-allocation reuse across messages via
     * {@link Utf8Parser#reset(byte[])}. */
    public static Utf8Parser utf8Parser(byte[] json) {
        return new Utf8Parser(json);
    }

    /** A concrete UTF-8 engine instance under an explicit configuration. */
    public static Utf8Parser utf8Parser(byte[] json, JoltConfig config) {
        return new Utf8Parser(json, config);
    }

    /** A concrete UTF-16 engine instance for reuse across messages via
     * {@link Utf16Parser#reset(String)}. */
    public static Utf16Parser utf16Parser(String json) {
        return new Utf16Parser(json);
    }

    /** A concrete UTF-16 engine instance under an explicit configuration. */
    public static Utf16Parser utf16Parser(String json, JoltConfig config) {
        return new Utf16Parser(json, config);
    }

    /** Parses from an InputStream by buffering it internally (the adapter path — the byte[]
     * fast path stays zero-copy). */
    public static JoltParser parse(InputStream in) throws IOException {
        return new Utf8Parser(in.readAllBytes());
    }

    /** Parses from an InputStream under an explicit configuration. */
    public static JoltParser parse(InputStream in, JoltConfig config) throws IOException {
        return new Utf8Parser(in.readAllBytes(), config);
    }

    /** Validates a UTF-8 document without exceptions: shared success sentinel, or diagnostics. */
    public static ParseResult tryParse(byte[] json) {
        return new Utf8Parser(json).drainOrFailure();
    }

    /** Validates a UTF-8 document under a configuration, without exceptions. */
    public static ParseResult tryParse(byte[] json, JoltConfig config) {
        return new Utf8Parser(json, config).drainOrFailure();
    }

    /** Validates a String document without exceptions: shared success sentinel, or diagnostics. */
    public static ParseResult tryParse(String json) {
        return new Utf16Parser(json).drainOrFailure();
    }

    /** Validates a String document under a configuration, without exceptions. */
    public static ParseResult tryParse(String json, JoltConfig config) {
        return new Utf16Parser(json, config).drainOrFailure();
    }
}
