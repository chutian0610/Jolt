package io.github.chutian0610.jolt;

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
}
