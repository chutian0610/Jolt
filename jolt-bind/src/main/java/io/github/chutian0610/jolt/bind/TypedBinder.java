package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.tree.Node;

/**
 * The binding seam: parse and write for ONE target type. Two adapters implement it —
 * {@code ReflectiveBinder} (the jolt-bind default, with per-class metadata cached) and the
 * runtime-generated binders of {@code jolt-gen} (M4). Both adapters face the identical
 * contract; a semantics difference between them is a gate failure, not a footnote.
 */
public interface TypedBinder<T> {

    /** Constructs an instance of {@code T} from a loaded node tree. */
    T parse(Node node, String path, BindConfig config);

    /** Writes {@code value} as compact JSON through the core writer. */
    void write(T value, JoltWriter writer);
}
