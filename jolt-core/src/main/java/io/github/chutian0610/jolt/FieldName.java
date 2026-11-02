package io.github.chutian0610.jolt;

import java.nio.charset.StandardCharsets;

/**
 * A pre-encoded JSON field name: the matching key the binders bake as per-type constants so
 * the engines compare raw bytes (UTF-8) or chars (UTF-16) instead of re-encoding the expected
 * name on every comparison. The M8-T0 dissection measured the alternative — the
 * {@link JoltParser#fieldNameEquals(String)} single-entry expected-name cache misses on every
 * new name in a binder's match chain and re-encodes it ({@code getBytes → byte[]}) — at
 * 47–53% of the generated direct read's time. A {@code FieldName} is immutable and created
 * once (bake time, per target type); the comparison itself allocates nothing.
 *
 * <p>Semantics are the String overload's, token for token: non-{@code FIELD_NAME} state
 * returns false; a clean span compares against the pre-encoded form; an escaped field name
 * (rare) falls back to the decoded comparison against {@link #name()} — the same
 * never-taken fast/slow contract.
 */
public final class FieldName {

    private final String name;
    final byte[] utf8;  // the UTF-8 engine's clean-span form
    final char[] chars; // the UTF-16 engine's clean-span form

    private FieldName(String name) {
        this.name = name;
        this.utf8 = name.getBytes(StandardCharsets.UTF_8);
        this.chars = name.toCharArray();
    }

    /** Bakes the matching key for {@code name} (create once per binding target, never per call). */
    public static FieldName of(String name) {
        return new FieldName(name);
    }

    /** The exact JSON name — the escaped-span fallback compares the decoded text against it. */
    public String name() {
        return name;
    }
}
