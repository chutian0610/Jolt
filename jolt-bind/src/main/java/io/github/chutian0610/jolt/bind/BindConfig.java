package io.github.chutian0610.jolt.bind;

/**
 * Immutable binding configuration, mirroring the {@code JoltConfig} pattern: start from
 * {@link #strict()} and chain {@code withXxx()} calls. The default behavior ignores unknown
 * JSON fields; later knobs land with their features.
 */
public final class BindConfig {

    private static final BindConfig STRICT = new BindConfig();

    private BindConfig() {}

    /** The base configuration: unknown fields ignored. */
    public static BindConfig strict() {
        return STRICT;
    }
}
