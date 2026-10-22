package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.TypeTag;
import io.github.chutian0610.jolt.tree.Tree;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;

/**
 * The binding facade: JSON ↔ records via the tree layer. Parsing loads through the verified
 * core parser into a {@link Node} tree, then constructs the target record from its components
 * by name; writing mirrors the path (value → Node → core writer). Strict by design: the JSON
 * type must match the target — no wide conversions, no silent defaults.
 *
 * <p>Tracer scope (M3-T1): scalar components (String, long/int, boolean and their boxes).
 * Nested records, collections, enums, and POJOs land with the following tickets.
 */
public final class Bind {

    private Bind() {}

    /** Loads a UTF-8 JSON document into an instance of {@code type}. */
    public static <T> T parse(byte[] json, Class<T> type) {
        return fromNode(Tree.parse(json), type, "$");
    }

    /** Loads a String document into an instance of {@code type}. */
    public static <T> T parse(String json, Class<T> type) {
        return fromNode(Tree.parse(json), type, "$");
    }

    /** Serializes a bound value as compact JSON. */
    public static String write(Object value) {
        return toNode(value, "$").toJson();
    }

    // --- record → value (parse direction) ---

    @SuppressWarnings("unchecked")
    private static <T> T fromNode(Node node, Class<T> type, String path) {
        if (!type.isRecord()) {
            throw new JsonBindException("only records bind in the tracer scope; got " + type.getName(), path);
        }
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] argTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            String name = components[i].getName();
            argTypes[i] = components[i].getType();
            args[i] = toValue(node.get(name), argTypes[i], path + "." + name);
        }
        try {
            var constructor = type.getDeclaredConstructor(argTypes);
            return (T) constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new JsonBindException("record constructor rejected the bound values: "
                    + e.getCause(), path);
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot construct " + type.getName(), path);
        }
    }

    private static Object toValue(Node field, Class<?> target, String path) {
        if (field == null) {
            if (target.isPrimitive()) {
                throw new JsonBindException("missing value for primitive component " + target.getSimpleName(), path);
            }
            return null;
        }
        if (target == String.class) {
            requireTag(field, TypeTag.STRING, "a string", path);
            return field.asText();
        }
        if (target == long.class || target == Long.class) {
            requireTag(field, TypeTag.NUMBER, "a number", path);
            try {
                return field.asLong();
            } catch (ArithmeticException beyondLong) {
                throw new JsonBindException("number does not fit in a long", path);
            }
        }
        if (target == int.class || target == Integer.class) {
            requireTag(field, TypeTag.NUMBER, "a number", path);
            try {
                return Math.toIntExact(field.asLong());
            } catch (ArithmeticException beyondInt) {
                throw new JsonBindException("number does not fit in an int", path);
            }
        }
        if (target == boolean.class || target == Boolean.class) {
            requireTag(field, TypeTag.BOOLEAN, "a boolean", path);
            return field.asBoolean();
        }
        throw new JsonBindException("unsupported component type " + target.getSimpleName()
                + " (lands with M3-T2)", path);
    }

    /** Strict binding: the JSON type must match the target — mismatch is a mapping error,
     * reported as a bind exception at the value's path (never a raw accessor throw). */
    private static void requireTag(Node field, TypeTag expected, String wanted, String path) {
        if (field.type() != expected) {
            throw new JsonBindException("expected " + wanted + " but found " + field.type(), path);
        }
    }

    // --- value → record (write direction) ---

    private static Node toNode(Object value, String path) {
        if (value == null) {
            return Node.nullValue();
        }
        if (value instanceof String s) {
            return Node.of(s);
        }
        if (value instanceof Long l) {
            return Node.of(l);
        }
        if (value instanceof Integer i) {
            return Node.of((long) i);
        }
        if (value instanceof Boolean b) {
            return Node.of(b);
        }
        if (value instanceof BigDecimal d) {
            return Node.of(d);
        }
        if (value instanceof java.lang.Record record) {
            Node object = Node.object();
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                try {
                    Object fieldValue = component.getAccessor().invoke(record);
                    object.put(component.getName(), toNode(fieldValue, path + "." + component.getName()));
                } catch (ReflectiveOperationException e) {
                    throw new JsonBindException("cannot read component " + component.getName(), path);
                }
            }
            return object;
        }
        throw new JsonBindException("unsupported value type " + value.getClass().getName()
                + " (lands with M3-T2/M3-T3)", path);
    }
}
