package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.TypeTag;
import io.github.chutian0610.jolt.tree.Tree;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;

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
        if (field.type() == TypeTag.NULL) {
            // JSON null binds as Java null — except primitives, which have no null.
            if (target.isPrimitive()) {
                throw new JsonBindException("JSON null for primitive component " + target.getSimpleName(), path);
            }
            return null;
        }
        if (target == String.class) {
            requireTag(field, TypeTag.STRING, "a string", path);
            return field.asText();
        }
        if (target == boolean.class || target == Boolean.class) {
            requireTag(field, TypeTag.BOOLEAN, "a boolean", path);
            return field.asBoolean();
        }
        if (isNumber(target)) {
            requireTag(field, TypeTag.NUMBER, "a number", path);
            return numberAs(field, target, path);
        }
        if (target.isRecord()) {
            requireTag(field, TypeTag.OBJECT, "an object", path);
            return fromNode(field, target, path);
        }
        throw new JsonBindException("unsupported component type " + target.getSimpleName()
                + " (collections, enums, and POJOs land with M3-T3)", path);
    }

    private static boolean isNumber(Class<?> target) {
        return target == byte.class || target == Byte.class
                || target == short.class || target == Short.class
                || target == int.class || target == Integer.class
                || target == long.class || target == Long.class
                || target == float.class || target == Float.class
                || target == double.class || target == Double.class
                || target == BigDecimal.class || target == BigInteger.class;
    }

    private static Object numberAs(Node field, Class<?> target, String path) {
        if (target == BigDecimal.class) {
            return field.asDecimal();
        }
        if (target == BigInteger.class) {
            try {
                return field.asDecimal().toBigIntegerExact();
            } catch (ArithmeticException lossy) {
                throw new JsonBindException("number is not a whole number", path);
            }
        }
        if (target == float.class || target == Float.class) {
            return field.asDecimal().floatValue();
        }
        if (target == double.class || target == Double.class) {
            return field.asDecimal().doubleValue();
        }
        long value;
        try {
            value = field.asLong();
        } catch (ArithmeticException beyondLong) {
            throw new JsonBindException("number does not fit in a long", path);
        }
        long bound = switch (target.getSimpleName()) {
            case "byte", "Byte" -> Byte.MAX_VALUE;
            case "short", "Short" -> Short.MAX_VALUE;
            case "int", "Integer" -> Integer.MAX_VALUE;
            default -> Long.MAX_VALUE;
        };
        // -bound - 1 is exact for all four widths: -(2^63-1)-1 == Long.MIN_VALUE in long math.
        if (value > bound || value < -bound - 1) {
            throw new JsonBindException("number does not fit in " + target.getSimpleName(), path);
        }
        if (target == byte.class || target == Byte.class) {
            return (byte) value;
        }
        if (target == short.class || target == Short.class) {
            return (short) value;
        }
        if (target == int.class || target == Integer.class) {
            return (int) value;
        }
        return value;
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
        if (value instanceof Byte b) {
            return Node.of((long) b);
        }
        if (value instanceof Short sh) {
            return Node.of((long) sh);
        }
        if (value instanceof Float f) {
            return Node.of((double) f);
        }
        if (value instanceof Double d) {
            return Node.of(d);
        }
        if (value instanceof BigInteger big) {
            return Node.of(new BigDecimal(big));
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
