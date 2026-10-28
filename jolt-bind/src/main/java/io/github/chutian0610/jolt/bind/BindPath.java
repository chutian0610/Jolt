package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltParser;

/**
 * The lazy path carrier for the direct read: segments are pushed and popped on the hot path
 * with no string construction — the `$`-rooted path is assembled only when a failure raises,
 * byte-identical to the tree path's strings (`$.user.tags[2]`). Capacity bounds the parser's
 * depth limit (each nesting level contributes at most one segment). One instance per thread,
 * reused across calls.
 */
final class BindPath {

    private final String[] names = new String[JoltParser.DEFAULT_MAX_DEPTH + 16];
    private final int[] indexes = new int[names.length];
    private final boolean[] indexed = new boolean[names.length];
    private int depth;

    void reset() {
        for (int i = 0; i < depth; i++) {
            names[i] = null;
        }
        depth = 0;
    }

    void pushName(String name) {
        names[depth] = name;
        indexed[depth] = false;
        depth++;
    }

    void pushIndex(int index) {
        indexes[depth] = index;
        indexed[depth] = true;
        depth++;
    }

    void pop() {
        depth--;
        names[depth] = null;
    }

    /** The `$`-rooted path of the current position, assembled on demand (failure only). */
    String resolve() {
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
