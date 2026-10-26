package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.JoltWriter;

/** Value-position fields-only write, callable from Bind's nested routing without a cast. */
public interface GeneratedFields {
    void writeFields(Object value, JoltWriter writer, String path);
}
