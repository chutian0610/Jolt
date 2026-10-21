package io.github.chutian0610.jolt.tree;

/**
 * The node discriminator: one tag per JSON value kind. A single {@code NUMBER} tag — the
 * parser's INT/FLOAT token split does not survive into the tree; the numeric views answer per
 * value.
 */
public enum TypeTag {
    OBJECT,
    ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL
}
