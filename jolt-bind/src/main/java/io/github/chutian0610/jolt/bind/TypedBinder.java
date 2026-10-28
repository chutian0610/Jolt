package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltParser;
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

    /**
     * The direct read form: parser tokens feed the binder straight to the constructed
     * target, skipping the tree layer. Semantically equivalent to {@link #parse} — same
     * values, same error matrix, same `$` paths (a divergence between the two read forms is
     * a gate failure). The tree-path adapters may not support it (the default throws); the
     * direct implementation — and any future generated direct binder — implements it.
     */
    default T parseTokens(JoltParser parser, BindConfig config) {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not support direct (token) parsing");
    }
}
