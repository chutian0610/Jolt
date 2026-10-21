package io.github.chutian0610.jolt.tree;

import io.github.chutian0610.jolt.JoltWriter;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

/**
 * The tree layer's single final class: one instance for every JSON value, discriminated by
 * {@link TypeTag}. Mutable after construction — {@code put}/{@code add} return {@code this} for
 * fluent document chains. Objects store keys and values in parallel arrays with linear-scan
 * lookup (small-object workload); insertion order is the document order and the serialization
 * order. NUMBER nodes retain the original source text; serialization writes it verbatim.
 * Missing keys and indexes read as Java {@code null}; JSON null is a {@link TypeTag#NULL} node.
 */
public final class Node {

    private static final String[] NO_KEYS = {};
    private static final Node[] NO_NODES = {};

    private final TypeTag tag;
    private String[] keys = NO_KEYS;
    private Node[] values = NO_NODES;
    private int count;
    private String text;      // STRING content, or the NUMBER node's retained source text
    private boolean bool;

    private Node(TypeTag tag) {
        this.tag = tag;
    }

    static Node newObject() {
        return new Node(TypeTag.OBJECT);
    }

    static Node newArray() {
        return new Node(TypeTag.ARRAY);
    }

    static Node ofString(String value) {
        Node n = new Node(TypeTag.STRING);
        n.text = Objects.requireNonNull(value);
        return n;
    }

    static Node ofNumber(String numberText) {
        Node n = new Node(TypeTag.NUMBER);
        n.text = Objects.requireNonNull(numberText);
        return n;
    }

    static Node ofBoolean(boolean value) {
        Node n = new Node(TypeTag.BOOLEAN);
        n.bool = value;
        return n;
    }

    static Node ofNull() {
        return new Node(TypeTag.NULL);
    }

    public TypeTag type() {
        return tag;
    }

    public boolean is(TypeTag tag) {
        return this.tag == tag;
    }

    /** The value of {@code key}, linearly scanned; Java null when the key is absent. */
    public Node get(String key) {
        for (int i = 0; i < count; i++) {
            if (keys[i].equals(key)) {
                return values[i];
            }
        }
        return null;
    }

    /** The value at {@code index}; Java null when out of bounds. */
    public Node get(int index) {
        if (index < 0 || index >= count) {
            return null;
        }
        return values[index];
    }

    public int size() {
        return count;
    }

    public Node put(String key, Node value) {
        requireContainer(TypeTag.OBJECT);
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        for (int i = 0; i < count; i++) {
            if (keys[i].equals(key)) {
                values[i] = value;
                return this;
            }
        }
        keys = append(keys, key);
        values = append(values, value);
        count++;
        return this;
    }

    public Node add(Node value) {
        requireContainer(TypeTag.ARRAY);
        Objects.requireNonNull(value);
        values = append(values, value);
        count++;
        return this;
    }

    /** The retained source text of a NUMBER node, exactly as it was parsed or built. */
    public String numberText() {
        requireType(TypeTag.NUMBER);
        return text;
    }

    /** Writes this subtree as compact JSON through the core writer. */
    public void writeTo(JoltWriter writer) {
        switch (tag) {
            case OBJECT -> {
                writer.startObject();
                for (int i = 0; i < count; i++) {
                    writer.fieldName(keys[i]);
                    values[i].writeTo(writer);
                }
                writer.endObject();
            }
            case ARRAY -> {
                writer.startArray();
                for (int i = 0; i < count; i++) {
                    values[i].writeTo(writer);
                }
                writer.endArray();
            }
            case STRING -> writer.value(text);
            case NUMBER -> {
                // Tracer form: integral text takes the long path, everything else BigDecimal.
                // The verbatim-text write lands with the byte-exact round-trip gate.
                String t = text;
                if (t.matches("-?\\d+")) {
                    try {
                        writer.value(Long.parseLong(t));
                        return;
                    } catch (NumberFormatException beyondLong) {
                        // fall through: a legal JSON integer may exceed the long range
                    }
                }
                writer.value(new BigDecimal(t));
            }
            case BOOLEAN -> writer.value(bool);
            case NULL -> writer.nullValue();
        }
    }

    /** This subtree as compact JSON. */
    public String toJson() {
        JoltWriter writer = io.github.chutian0610.jolt.Jolt.write();
        writeTo(writer);
        return writer.toString();
    }

    @Override
    public String toString() {
        return toJson();
    }

    private void requireContainer(TypeTag expected) {
        if (tag != expected) {
            throw new IllegalStateException(expected + " operation on a " + tag + " node");
        }
    }

    private void requireType(TypeTag expected) {
        if (tag != expected) {
            throw new IllegalStateException(expected + " value requested from a " + tag + " node");
        }
    }

    private static String[] append(String[] array, String value) {
        String[] grown = Arrays.copyOf(array, array.length + 1);
        grown[array.length] = value;
        return grown;
    }

    private static Node[] append(Node[] array, Node value) {
        Node[] grown = Arrays.copyOf(array, array.length + 1);
        grown[array.length] = value;
        return grown;
    }
}
