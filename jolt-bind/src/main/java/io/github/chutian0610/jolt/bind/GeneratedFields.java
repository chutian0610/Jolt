package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltWriter;

/**
 * Value-position fields-only write, implemented by jolt-gen's generated binders so nested
 * record/POJO values can route to generated code without braces (the caller owns them).
 * jolt-bind never depends on jolt-gen — the interface travels in the forward direction.
 */
public interface GeneratedFields {
    void writeFields(Object value, JoltWriter writer, String path);
}
