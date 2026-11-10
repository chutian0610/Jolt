package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.tree.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * The shared write emission, one home: value → {@link JoltWriter} for every bind adapter —
 * scalars write themselves, collections recurse by index/key, record/POJO values dispatch
 * through their binder's fields-only write (the value position owns its braces), and an
 * already-loaded {@link Node} re-emits verbatim. Nothing here is named by generated
 * bytecode — the frozen ABI door is {@code Bind.writeTo}, whose descriptor the jolt-gen
 * emitters bake into INVOKESTATIC.
 */
final class BindWrites {

    private BindWrites() {}

    /**
     * The shared write emission for any value (scalars, collections, record/POJO via its
     * binder) — the contract every bind adapter writes through. One {@code instanceof} per
     * kind: containers first, then the scalar fast path, then the object arm that owns
     * its braces.
     */
    static void writeTo(Object value, JoltWriter writer, String path) {
        if (value == null) {
            writer.nullValue();
            return;
        }
        if (value instanceof Node n) {
            emitNode(n, writer);
            return;
        }
        if (value instanceof List<?> list) {
            writer.startArray();
            for (int i = 0; i < list.size(); i++) {
                writeTo(list.get(i), writer, path + "[" + i + "]");
            }
            writer.endArray();
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writer.startObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                writer.fieldName(String.valueOf(entry.getKey()));
                writeTo(entry.getValue(), writer, path + "." + entry.getKey());
            }
            writer.endObject();
            return;
        }
        if (value.getClass().isArray()) {
            writer.startArray();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                writeTo(java.lang.reflect.Array.get(value, i), writer, path + "[" + i + "]");
            }
            writer.endArray();
            return;
        }
        if (value instanceof Enum<?> e) {
            writer.value(e.name());
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            writeScalar(value, writer);
            return;
        }
        // records and POJOs (and any other object shape) dispatch through their cached
        // binders (fields only — the value position owns its braces)
        Class<?> raw = value.getClass();
        writer.startObject();
        TypedBinder<?> generated = GeneratedRegistry.lookup(raw);
        if (generated instanceof GeneratedFields generatedFields) {
            generatedFields.writeFields(value, writer, path);
        } else {
            ReflectiveBinder.writeFieldsVia(value, raw, writer, path);
        }
        writer.endObject();
    }

    /** Emits an already-loaded {@link Node} as JSON (Bind.write of a tree value). */
    private static void emitNode(Node node, JoltWriter writer) {
        switch (node.type()) {
            case OBJECT -> {
                writer.startObject();
                for (int i = 0; i < node.size(); i++) {
                    writer.fieldName(node.keys().get(i));
                    emitNode(node.elements().get(i), writer);
                }
                writer.endObject();
            }
            case ARRAY -> {
                writer.startArray();
                for (Node child : node.elements()) {
                    emitNode(child, writer);
                }
                writer.endArray();
            }
            case STRING -> writer.value(node.asText());
            case NUMBER -> writer.valueRaw(node.numberText());
            case BOOLEAN -> writer.value(node.asBoolean());
            case NULL -> writer.nullValue();
        }
    }

    /** Write mirror of the scalar families: numeric widths, text, boolean. */
    private static void writeScalar(Object value, JoltWriter writer) {
        if (value instanceof String s) {
            writer.value(s);
        } else if (value instanceof Boolean b) {
            writer.value(b);
        } else if (value instanceof BigDecimal d) {
            writer.value(d);
        } else if (value instanceof BigInteger big) {
            writer.value(new BigDecimal(big));
        } else if (value instanceof Integer i) {
            writer.value((long) i);
        } else if (value instanceof Byte b) {
            writer.value((long) b);
        } else if (value instanceof Short sh) {
            writer.value((long) sh);
        } else if (value instanceof Long l) {
            writer.value(l);
        } else if (value instanceof Float f) {
            writer.value((double) f);
        } else if (value instanceof Double d) {
            writer.value(d);
        } else {
            throw new JsonBindException("unsupported value type " + value.getClass().getName());
        }
    }
}
