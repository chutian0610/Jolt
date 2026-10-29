package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltWriter;

/**
 * Value-position fields-only write, implemented by jolt-gen's generated binders so nested
 * record/POJO values can route to generated code without braces (the caller owns them).
 * jolt-bind never depends on jolt-gen — the interface travels in the forward direction.
 */
public interface GeneratedFields {
    void writeFields(Object value, JoltWriter writer, String path);

    /**
     * Whether the implementing generated binder declares the direct (token) read — the
     * facade's {@code withDirect} dispatch consults it before falling back to the reflective
     * direct binder. The declaration IS the capability flag: a generated class emits
     * {@code parseTokens} exactly when its target shape is supported (the M7-T1 tracer:
     * all-scalar records), so a registry hit without a direct read is the documented miss
     * arm. The M4-era plan executor and non-capable shapes default to false.
     */
    default boolean hasDirectRead() {
        return false;
    }
}
