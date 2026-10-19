package io.github.chutian0610.jolt;

/**
 * Stateless entry points into the Jolt parser.
 */
public final class Jolt {

    private Jolt() {}

    /** Begins parsing a UTF-8 JSON document held in {@code json}. */
    public static JoltParser parse(byte[] json) {
        return new JoltParser(json);
    }
}
