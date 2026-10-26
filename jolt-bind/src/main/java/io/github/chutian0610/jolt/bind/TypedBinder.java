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

    /**
     * Constructs an instance of {@code T} from a loaded node tree. Implementations must apply
     * the strict-binding contract (type matches target, no wide conversions), honor
     * {@code config} (including fail-on-unknown), report failures as {@link JsonBindException}
     * with the {@code $}-rooted path, and let core parse errors surface unchanged.
     */
    T parse(Node node, String path, BindConfig config);

    /**
     * Writes {@code value} as a COMPLETE compact JSON document through the core writer — the
     * implementation owns the enclosing object braces. Nested values go through
     * {@code Bind.writeTo} so both adapters share the escaping and verbatim-number semantics.
     */
    void write(T value, JoltWriter writer);
}
