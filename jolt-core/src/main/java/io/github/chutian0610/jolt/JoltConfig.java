package io.github.chutian0610.jolt;

/**
 * Explicit configuration for lenient features (ADR-0001): each feature is individually opt-in
 * and a disabled feature costs zero — its only footprint is a never-taken branch on a final
 * per-instance flag (predicted away by the JIT), never an interleaved check in strict
 * scanning. The default is strict RFC 8259.
 *
 * <p>Immutable; start from {@link #strict()} and chain {@code withXxx()} calls.
 */
public final class JoltConfig {

    static final int F_COMMENTS = 1;
    static final int F_TRAILING_COMMAS = 2;

    final int features;
    final int maxDepth;

    private JoltConfig(int features, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1, got " + maxDepth);
        }
        this.features = features;
        this.maxDepth = maxDepth;
    }

    /** Strict RFC 8259, default depth limit: the base configuration. */
    public static JoltConfig strict() {
        return new JoltConfig(0, JoltParser.DEFAULT_MAX_DEPTH);
    }

    /** Enables {@code // line} and {@code /* block * @} comments between tokens. Line comments
     * end at the next {@code \n} (a preceding {@code \r} is part of the comment). */
    public JoltConfig withComments() {
        return new JoltConfig(features | F_COMMENTS, maxDepth);
    }

    /** Enables one trailing comma before {@code ]} / {@code }} after the last element. */
    public JoltConfig withTrailingCommas() {
        return new JoltConfig(features | F_TRAILING_COMMAS, maxDepth);
    }

    /** Sets the nesting depth limit (default {@link JoltParser#DEFAULT_MAX_DEPTH}). */
    public JoltConfig withMaxDepth(int maxDepth) {
        return new JoltConfig(features, maxDepth);
    }
}
