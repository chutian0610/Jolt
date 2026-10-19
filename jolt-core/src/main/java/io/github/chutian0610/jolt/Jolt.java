package io.github.chutian0610.jolt;

/**
 * Stateless entry points into the Jolt parser.
 */
public final class Jolt {

    private Jolt() {}

    /** Begins parsing a UTF-8 JSON document held in {@code json} with the default depth limit. */
    public static JoltParser parse(byte[] json) {
        return new JoltParser(json);
    }

    /** Begins parsing with an explicit nesting depth limit. */
    public static JoltParser parse(byte[] json, int maxDepth) {
        return new JoltParser(json, maxDepth);
    }
}
