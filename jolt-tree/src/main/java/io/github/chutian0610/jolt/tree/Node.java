package io.github.chutian0610.jolt.tree;

import io.github.chutian0610.jolt.JoltWriter;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The tree layer's single final class: one instance for every JSON value, discriminated by
 * {@link TypeTag}. Mutable after construction — {@code put}/{@code add} return {@code this} for
 * fluent document chains. Objects store keys and values in parallel arrays with linear-scan
 * lookup (small-object workload); insertion order is the document order and the serialization
 * order. NUMBER nodes retain the original source text; serialization writes it verbatim.
 * Missing keys and indexes read as Java {@code null}; JSON null is a {@link TypeTag#NULL} node.
 *
 * <p>Null policy: {@code Node}-typed parameters reject Java null (NPE — a typed null is a
 * bug); raw-value overloads convert Java null into the JSON {@code null} literal (Jackson
 * parity); {@code putNull(key)} and {@code nullValue()} are the explicit forms. A bare
 * {@code put("k", null)} is deliberately a compile error — the five raw overloads force the
 * intent to be written down.
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
    private Object numericCache; // BigDecimal, computed on first numeric view

    private Node(TypeTag tag) {
        this.tag = tag;
    }

    /** An empty OBJECT node. */
    public static Node object() {
        return new Node(TypeTag.OBJECT);
    }

    /** An empty ARRAY node. */
    public static Node array() {
        return new Node(TypeTag.ARRAY);
    }

    public static Node of(String value) {
        Node n = new Node(TypeTag.STRING);
        n.text = Objects.requireNonNull(value);
        return n;
    }

    public static Node of(boolean value) {
        Node n = new Node(TypeTag.BOOLEAN);
        n.bool = value;
        return n;
    }

    public static Node of(long value) {
        return ofNumberText(Long.toString(value));
    }

    public static Node of(double value) {
        return ofNumberText(Double.toString(value));
    }

    public static Node of(BigDecimal value) {
        return ofNumberText(Objects.requireNonNull(value).toString());
    }

    /** The JSON {@code null} node. */
    public static Node nullValue() {
        return new Node(TypeTag.NULL);
    }

    static Node ofNumberText(String numberText) {
        Node n = new Node(TypeTag.NUMBER);
        n.text = Objects.requireNonNull(numberText);
        return n;
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

    /** The object's keys as an insertion-ordered defensive snapshot; empty for non-objects. */
    public java.util.List<String> keys() {
        return List.of(keys).subList(0, count);
    }

    /** The container's values as an insertion-ordered defensive snapshot. */
    public java.util.List<Node> elements() {
        return List.of(values).subList(0, count);
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

    /** Raw overload: a Java-null value becomes a JSON {@code null} node (Jackson parity). */
    public Node put(String key, String value) {
        return put(key, value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} node. */
    public Node put(String key, long value) {
        return put(key, of(value));
    }

    /** Boxed overload: a Java-null {@code Long} becomes a JSON {@code null} node instead of
     * unboxing into an unexpected NPE. */
    public Node put(String key, Long value) {
        return put(key, value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} node. */
    public Node put(String key, Double value) {
        return put(key, value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} node. */
    public Node put(String key, BigDecimal value) {
        return put(key, value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} node. */
    public Node put(String key, Boolean value) {
        return put(key, value == null ? nullValue() : of(value));
    }

    /** Explicitly writes the JSON {@code null} literal under {@code key}. */
    public Node putNull(String key) {
        return put(key, nullValue());
    }

    public Node add(Node value) {
        requireContainer(TypeTag.ARRAY);
        Objects.requireNonNull(value);
        values = append(values, value);
        count++;
        return this;
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} element. */
    public Node add(String value) {
        return add(value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} element. */
    public Node add(long value) {
        return add(of(value));
    }

    /** Boxed overload: a Java-null {@code Long} becomes a JSON {@code null} element. */
    public Node add(Long value) {
        return add(value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} element. */
    public Node add(Double value) {
        return add(value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} element. */
    public Node add(Boolean value) {
        return add(value == null ? nullValue() : of(value));
    }

    /** Raw overload: a Java-null value becomes a JSON {@code null} element. */
    public Node add(BigDecimal value) {
        return add(value == null ? nullValue() : of(value));
    }

    /** The retained source text of a NUMBER node, exactly as it was parsed or built. */
    public String numberText() {
        requireType(TypeTag.NUMBER);
        return text;
    }

    /** The STRING content; a type mismatch is a programming error and throws. */
    public String asText() {
        requireType(TypeTag.STRING);
        return text;
    }

    /** The BOOLEAN value; a type mismatch is a programming error and throws. */
    public boolean asBoolean() {
        requireType(TypeTag.BOOLEAN);
        return bool;
    }

    /**
     * The exact {@link BigDecimal} value of a NUMBER node, parsed from the retained text on
     * first access and cached. Text beyond BigDecimal's grammar limits (absurd exponents)
     * raises {@link NumberFormatException}.
     */
    public BigDecimal asDecimal() {
        requireType(TypeTag.NUMBER);
        if (numericCache == null) {
            numericCache = new BigDecimal(text);
        }
        return (BigDecimal) numericCache;
    }

    /**
     * The integer value when the conversion is exact; lossy or overflowing conversions raise
     * {@link ArithmeticException} — use {@link #asDecimal()} for the exact wide form.
     */
    public long asLong() {
        return asDecimal().longValueExact();
    }

    /** The double value, best effort: overflow reads as infinity, underflow as zero. */
    public double asDouble() {
        return asDecimal().doubleValue();
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
            case NUMBER -> writer.valueRaw(text); // the retained source text, verbatim
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

    /** JSON value semantics: objects key-order-independent, numbers numerically compared. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Node other) || tag != other.tag) {
            return false;
        }
        return switch (tag) {
            case OBJECT -> equalsObject(other);
            case ARRAY -> equalsArray(other);
            case STRING -> text.equals(other.text);
            case NUMBER -> compareNumeric(other);
            case BOOLEAN -> bool == other.bool;
            case NULL -> true;
        };
    }

    /**
     * Numeric comparison when both texts parse (BigDecimal semantics); text equality as the
     * fallback for texts beyond BigDecimal's grammar — equals never throws.
     */
    private boolean compareNumeric(Node other) {
        try {
            return asDecimal().compareTo(other.asDecimal()) == 0;
        } catch (NumberFormatException beyondGrammar) {
            return text.equals(other.text);
        }
    }

    private boolean equalsObject(Node other) {
        if (count != other.count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            Node theirs = other.get(keys[i]);
            if (theirs == null || !values[i].equals(theirs)) {
                return false;
            }
        }
        return true;
    }

    private boolean equalsArray(Node other) {
        if (count != other.count) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            if (!values[i].equals(other.values[i])) {
                return false;
            }
        }
        return true;
    }

    /** Consistent with {@link #equals(Object)}: numbers hash via their stripped form, falling
     * back to their text when that form is beyond BigDecimal's grammar. */
    @Override
    public int hashCode() {
        int content = switch (tag) {
            case OBJECT -> {
                int sum = 0;
                for (int i = 0; i < count; i++) {
                    sum += keys[i].hashCode() ^ values[i].hashCode();
                }
                yield sum;
            }
            case ARRAY -> {
                int result = 1;
                for (int i = 0; i < count; i++) {
                    result = result * 31 + values[i].hashCode();
                }
                yield result;
            }
            case STRING -> text.hashCode();
            case NUMBER -> {
                try {
                    yield asDecimal().stripTrailingZeros().hashCode();
                } catch (NumberFormatException beyondGrammar) {
                    yield text.hashCode();
                }
            }
            case BOOLEAN -> Boolean.hashCode(bool);
            case NULL -> 0;
        };
        return 31 * tag.hashCode() + content;
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
