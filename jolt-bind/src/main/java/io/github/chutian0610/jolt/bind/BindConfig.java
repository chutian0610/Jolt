package io.github.chutian0610.jolt.bind;

/**
 * Immutable binding configuration, mirroring the {@code JoltConfig} pattern: start from
 * {@link #strict()} and chain {@code withXxx()} calls. The default behavior ignores unknown
 * JSON fields; later knobs land with their features.
 */
public final class BindConfig {

    private static final BindConfig DEFAULT = new BindConfig(false, false);
    private static final BindConfig FAIL_ON_UNKNOWN = new BindConfig(true, false);

    private final boolean failOnUnknown;
    private final boolean direct;

    private BindConfig(boolean failOnUnknown, boolean direct) {
        this.failOnUnknown = failOnUnknown;
        this.direct = direct;
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

    public boolean failOnUnknown() {
        return failOnUnknown;
    }

    /**
     * The direct read path: parser tokens feed the binder straight to the constructed
     * target, skipping the tree layer. Semantically equivalent to the default tree path
     * (same values, same error matrix, same paths — divergence is a gate failure); the
     * steady-state allocation is the target object graph only. See the {@code Direct bind}
     * entry in CONTEXT.md.
     */
    public BindConfig withDirect() {
        return new BindConfig(failOnUnknown, true);
    }

    public boolean direct() {
        return direct;
    }
}
