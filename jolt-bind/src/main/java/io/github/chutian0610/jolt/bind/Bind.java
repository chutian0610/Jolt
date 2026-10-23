package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.TypeTag;
import io.github.chutian0610.jolt.tree.Tree;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The binding facade: JSON ↔ records via the tree layer. Parsing loads through the verified
 * core parser into a {@link Node} tree, then constructs the target record from its components
 * by name; writing mirrors the path (value → Node → core writer). Strict by design: the JSON
 * type must match the target — no wide conversions, no silent defaults.
 *
 * <p>Supported targets: records (nested, by component name), POJOs (public no-arg constructor
 * + public setters), collections ({@code List<X>}, {@code X[]}, {@code Map<String,X>} with
 * generic signatures), enums (by {@code name()}), all strict scalars, and untyped
 * {@code Object} elements via natural mapping. Polymorphic (abstract/interface) targets are
 * rejected.
 */
public final class Bind {

    private Bind() {}

    /** Loads a UTF-8 JSON document into an instance of {@code type} (unknown fields ignored). */
    public static <T> T parse(byte[] json, Class<T> type) {
        return parse(json, type, BindConfig.strict());
    }

    /** Loads a UTF-8 JSON document under an explicit binding configuration. */
    public static <T> T parse(byte[] json, Class<T> type, BindConfig config) {
        return fromNode(Tree.parse(json), type, config, "$");
    }

    /** Loads a String document into an instance of {@code type} (unknown fields ignored). */
    public static <T> T parse(String json, Class<T> type) {
        return parse(json, type, BindConfig.strict());
    }

    /** Loads a String document under an explicit binding configuration. */
    public static <T> T parse(String json, Class<T> type, BindConfig config) {
        return fromNode(Tree.parse(json), type, config, "$");
    }

    /** Serializes a bound value as compact JSON. */
    public static String write(Object value) {
        return toNode(value, "$").toJson();
    }

    // --- record/POJO → value (parse direction) ---

    @SuppressWarnings("unchecked")
    private static <T> T fromNode(Node node, Class<T> type, BindConfig config, String path) {
        if (type.isRecord()) {
            return (T) recordFrom(node, (Class<? extends java.lang.Record>) type, config, path);
        }
        return (T) pojoFrom(node, type, config, path);
    }

    @SuppressWarnings("unchecked")
    private static Object recordFrom(Node node, Class<? extends java.lang.Record> type,
                                     BindConfig config, String path) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] argTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            String name = components[i].getName();
            argTypes[i] = components[i].getType();
            args[i] = toValue(node.get(name), components[i].getGenericType(), path + "." + name, config);
        }
        if (config.failOnUnknown()) {
            for (String key : node.keys()) {
                boolean known = false;
                for (RecordComponent component : components) {
                    known |= component.getName().equals(key);
                }
                if (!known) {
                    throw new JsonBindException("unknown field '" + key + "'", path + "." + key);
                }
            }
        }
        try {
            var constructor = type.getDeclaredConstructor(argTypes);
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new JsonBindException("record constructor rejected the bound values: "
                    + e.getCause(), path);
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot construct " + type.getName(), path);
        }
    }

    /** POJO binding: public no-arg constructor + public setters; unknown keys ignored. */
    private static Object pojoFrom(Node node, Class<?> type, BindConfig config, String path) {
        var constructor = publicNoArgConstructor(type);
        if (constructor == null) {
            throw new JsonBindException(type.getName()
                    + " has no public no-arg constructor and is not a record", path);
        }
        Object instance;
        try {
            instance = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot instantiate " + type.getName(), path);
        }
        for (String key : node.keys()) {
            if (key.isEmpty()) {
                if (config.failOnUnknown()) {
                    throw new JsonBindException("unknown field ''", path);
                }
                continue; // an empty key has no setter name — ignored like any unknown field
            }
            java.lang.reflect.Method setter = setterFor(type, key);
            if (setter == null) {
                if (config.failOnUnknown()) {
                    throw new JsonBindException("unknown field '" + key + "'", path + "." + key);
                }
                continue; // unknown fields are ignored — including keys with no setter
            }
            Object value = toValue(node.get(key), setter.getGenericParameterTypes()[0],
                    path + "." + key, config);
            try {
                setter.invoke(instance, value);
            } catch (InvocationTargetException e) {
                throw new JsonBindException("setter rejected the bound value: " + e.getCause(),
                        path + "." + key);
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot invoke setter for " + key, path + "." + key);
            }
        }
        return instance;
    }

    private static java.lang.reflect.Constructor<?> publicNoArgConstructor(Class<?> type) {
        try {
            return type.getConstructor();
        } catch (NoSuchMethodException none) {
            return null;
        }
    }

    private static java.lang.reflect.Method setterFor(Class<?> type, String jsonKey) {
        String setterName = "set" + Character.toUpperCase(jsonKey.charAt(0)) + jsonKey.substring(1);
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals(setterName)
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    // --- value dispatch (parse) ---

    private static Object toValue(Node field, java.lang.reflect.Type target, String path, BindConfig config) {
        if (field == null) {
            // Missing: reference kinds (collections, enums, records, POJOs, boxes) read null.
            if (target instanceof Class<?> raw && raw.isPrimitive()) {
                throw new JsonBindException("missing value for primitive component " + raw.getSimpleName(), path);
            }
            return null;
        }
        if (field.type() == TypeTag.NULL) {
            // JSON null: collections, enums, and other reference kinds read as Java null.
            if (target instanceof Class<?> raw && raw.isPrimitive()) {
                throw new JsonBindException("JSON null for primitive component " + raw.getSimpleName(), path);
            }
            return null;
        }
        if (target instanceof java.lang.reflect.ParameterizedType parameterized) {
            return toParameterized(field, parameterized, path, config);
        }
        if (target instanceof java.lang.reflect.GenericArrayType genericArray) {
            java.lang.reflect.Type component = genericArray.getGenericComponentType();
            if (component instanceof java.lang.reflect.ParameterizedType parameterized) {
                // List<String>[]: elements bind as the raw List, values as String.
                return toArray(field, (Class<?>) parameterized.getRawType(), path, config);
            }
            if (component instanceof Class<?> componentClass) {
                return toArray(field, componentClass, path, config);
            }
            throw new JsonBindException("unsupported array component type " + component, path);
        }
        if (!(target instanceof Class<?> raw)) {
            throw new JsonBindException("unsupported component type " + target, path);
        }
        if (raw.isArray()) {
            return toArray(field, raw.getComponentType(), path, config);
        }
        return toScalarOrStructure(field, raw, path, config);
    }

    private static Object toParameterized(Node field, java.lang.reflect.ParameterizedType type, String path, BindConfig config) {
        var raw = (Class<?>) type.getRawType();
        var arguments = type.getActualTypeArguments();
        if (raw == List.class) {
            requireTag(field, TypeTag.ARRAY, "an array", path);
            List<Object> out = new java.util.ArrayList<>();
            List<Node> elements = field.elements();
            for (int i = 0; i < elements.size(); i++) {
                out.add(toValue(elements.get(i), arguments[0], path + "[" + i + "]", config));
            }
            return out;
        }
        if (raw == Map.class) {
            if (arguments[0] != String.class) {
                throw new JsonBindException("map keys must be String, got " + arguments[0], path);
            }
            requireTag(field, TypeTag.OBJECT, "an object", path);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (String key : field.keys()) {
                out.put(key, toValue(field.get(key), arguments[1], path + "." + key, config));
            }
            return out;
        }
        throw new JsonBindException("unsupported generic type " + type.getTypeName(), path);
    }

    private static Object toArray(Node field, Class<?> element, String path, BindConfig config) {
        requireTag(field, TypeTag.ARRAY, "an array", path);
        List<Node> elements = field.elements();
        Object out = java.lang.reflect.Array.newInstance(element, elements.size());
        for (int i = 0; i < elements.size(); i++) {
            java.lang.reflect.Array.set(out, i, toValue(elements.get(i), element, path + "[" + i + "]", config));
        }
        return out;
    }

    private static Object toScalarOrStructure(Node field, Class<?> raw, String path, BindConfig config) {
        if (raw == String.class) {
            requireTag(field, TypeTag.STRING, "a string", path);
            return field.asText();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            requireTag(field, TypeTag.BOOLEAN, "a boolean", path);
            return field.asBoolean();
        }
        if (isNumber(raw)) {
            requireTag(field, TypeTag.NUMBER, "a number", path);
            return numberAs(field, raw, path);
        }
        if (raw.isEnum()) {
            requireTag(field, TypeTag.STRING, "a string", path);
            return enumValue(raw, field.asText(), path);
        }
        if (raw.isRecord()) {
            requireTag(field, TypeTag.OBJECT, "an object", path);
            return recordFrom(field, raw.asSubclass(java.lang.Record.class), config, path);
        }
        if (raw == Object.class) {
            return naturalValue(field, path);
        }
        if (!raw.isInterface() && !java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
            return pojoFrom(field, raw, config, path);
        }
        throw new JsonBindException("polymorphic binding is not supported for " + raw.getSimpleName(), path);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Enum enumValue(Class<?> raw, String text, String path) {
        try {
            return Enum.valueOf((Class<? extends Enum>) raw, text);
        } catch (IllegalArgumentException unknownName) {
            throw new JsonBindException("unknown enum name '" + text
                    + "' for " + raw.getSimpleName(), path);
        }
    }

    /** Jackson-parity natural mapping for untyped Object elements. */
    private static Object naturalValue(Node field, String path) {
        return switch (field.type()) {
            case STRING -> field.asText();
            case BOOLEAN -> field.asBoolean();
            case NULL -> null;
            case NUMBER -> naturalNumber(field, path);
            case ARRAY -> {
                List<Object> out = new java.util.ArrayList<>();
                List<Node> elements = field.elements();
                for (int i = 0; i < elements.size(); i++) {
                    out.add(naturalValue(elements.get(i), path + "[" + i + "]"));
                }
                yield out;
            }
            case OBJECT -> {
                Map<String, Object> out = new java.util.LinkedHashMap<>();
                for (String key : field.keys()) {
                    out.put(key, naturalValue(field.get(key), path + "." + key));
                }
                yield out;
            }
        };
    }

    private static Object naturalNumber(Node field, String path) {
        String text = field.numberText();
        if (text.matches("-?\\d+")) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException beyondLong) {
                return new BigDecimal(text);
            }
        }
        double d = field.asDouble();
        if (Double.isInfinite(d)) {
            return field.asDecimal();
        }
        return d;
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

    // --- value → Node (write direction) ---

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
        if (value instanceof BigDecimal d) {
            return Node.of(d);
        }
        if (value instanceof Boolean b) {
            return Node.of(b);
        }
        if (value instanceof Enum<?> e) {
            return Node.of(e.name());
        }
        if (value instanceof Node n) {
            return n;
        }
        if (value instanceof List<?> list) {
            Node array = Node.array();
            for (int i = 0; i < list.size(); i++) {
                array.add(toNode(list.get(i), path + "[" + i + "]"));
            }
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            Node object = Node.object();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.put(String.valueOf(entry.getKey()), toNode(entry.getValue(), path + "." + entry.getKey()));
            }
            return object;
        }
        if (value.getClass().isArray()) {
            Node array = Node.array();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                array.add(toNode(java.lang.reflect.Array.get(value, i), path + "[" + i + "]"));
            }
            return array;
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
        return pojoToNode(value, path);
    }

    /**
     * POJO serialization via public getters (getX and isX); property name from the accessor.
     * Accessors are visited in sorted-name order: getMethods() is unordered, and the byte-exact
     * round-trip gate needs deterministic property order (records keep insertion order; POJOs
     * keep accessor order).
     */
    private static Node pojoToNode(Object value, String path) {
        Node object = Node.object();
        List<java.lang.reflect.Method> accessors = new java.util.ArrayList<>();
        for (java.lang.reflect.Method method : value.getClass().getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String property = null;
            if (name.startsWith("get") && name.length() > 3) {
                property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            } else if (name.startsWith("is") && name.length() > 2
                    && method.getReturnType() == boolean.class) {
                property = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
            if (property == null) {
                continue;
            }
            accessors.add(method);
        }
        accessors.sort(java.util.Comparator.comparing(java.lang.reflect.Method::getName));
        for (java.lang.reflect.Method method : accessors) {
            String name = method.getName();
            String property = name.startsWith("is")
                    ? Character.toLowerCase(name.charAt(2)) + name.substring(3)
                    : Character.toLowerCase(name.charAt(3)) + name.substring(4);
            try {
                object.put(property, toNode(method.invoke(value), path + "." + property));
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot read property " + property, path);
            }
        }
        return object;
    }
}
