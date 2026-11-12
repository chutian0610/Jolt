package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltParser;

/**
 * The lazy path carrier for the direct read, one type for every consumer: the reflective
 * binder, and both generated forms through INVOKEVIRTUAL (the Object-handle escape hatch
 * existed only because this class was package-private while the generated class loads in
 * another runtime package — that pretense is gone). Internal carrier, not user API: the
 * public surfaces that hand it out ({@code DirectSupport.resetCarrier}/{@code takeCarrier})
 * document it as opaque to generated code.
 *
 * <p>Segments are pushed and popped on the hot path
 * with no string construction — the `$`-rooted path is assembled only when a failure raises,
 * byte-identical to the tree path's strings (`$.user.tags[2]`). Capacity bounds the parser's
 * depth limit (each nesting level contributes at most one segment). One instance per thread,
 * reused across calls.
 */
public final class BindPath {

    private final String[] names = new String[JoltParser.DEFAULT_MAX_DEPTH + 16];
    private final int[] indexes = new int[names.length];
    private final boolean[] indexed = new boolean[names.length];
    private int depth;

    public void reset() {
        for (int i = 0; i < depth; i++) {
            names[i] = null;
        }
        depth = 0;
    }

    public void pushName(String name) {
        names[depth] = name;
        indexed[depth] = false;
        depth++;
    }

    public void pushIndex(int index) {
        indexes[depth] = index;
        indexed[depth] = true;
        depth++;
    }

    public void pop() {
        depth--;
        names[depth] = null;
    }

    /** The segments pushed so far — an inline collection loop saves it at entry. */
    public int depth() {
        return depth;
    }

    /** Pops back to {@code target}: the inline loops' error edge restores the position they
     * entered with, whatever nested loops pushed in between (one handler, any nesting). */
    public void truncate(int target) {
        while (depth > target) {
            pop();
        }
    }

    /** The `$`-rooted path of the current position, assembled on demand (failure only). */
    public String resolve() {
        StringBuilder sb = new StringBuilder("$");
        for (int i = 0; i < depth; i++) {
            if (indexed[i]) {
                sb.append('[').append(indexes[i]).append(']');
            } else {
                sb.append('.').append(names[i]);
            }
        }
        return sb.toString();
    }
}
