package io.github.chutian0610.jolt.bind;

/**
 * Immutable binding configuration, mirroring the {@code JoltConfig} pattern: start from
 * {@link #strict()} and chain {@code withXxx()} calls. The default behavior ignores unknown
 * JSON fields; later knobs land with their features.
 */
public final class BindConfig {

    private static final BindConfig DEFAULT = new BindConfig(false);
    private static final BindConfig FAIL_ON_UNKNOWN = new BindConfig(true);

    private final boolean failOnUnknown;

    private BindConfig(boolean failOnUnknown) {
        this.failOnUnknown = failOnUnknown;
    }

    /** The default configuration: unknown fields ignored. (Named {@code defaults}, not
     * {@code strict}: the strict-unknowns posture is {@link #withFailOnUnknown()}.) */
    public static BindConfig defaults() {
        return DEFAULT;
    }

    /** Unknown JSON fields raise {@code JsonBindException} instead of being ignored. */
    public BindConfig withFailOnUnknown() {
        return FAIL_ON_UNKNOWN;
    }

    boolean failOnUnknown() {
        return failOnUnknown;
    }
}
