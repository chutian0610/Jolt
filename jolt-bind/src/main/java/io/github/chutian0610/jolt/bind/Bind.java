package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.Jolt;
import io.github.chutian0610.jolt.JoltWriter;
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
        return parse(json, type, BindConfig.defaults());
    }

    /** Loads a UTF-8 JSON document under an explicit binding configuration. */
    public static <T> T parse(byte[] json, Class<T> type, BindConfig config) {
        if (config.direct() && java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
            // non-public targets keep the M4 fallback contract: the reflective (tree) binder
            return DirectBinder.forClass(type).parseDirect(json, config);
        }
        return fromNode(Tree.parse(json), type, config, "$");
    }

    /** Loads a String document into an instance of {@code type} (unknown fields ignored). */
    public static <T> T parse(String json, Class<T> type) {
        return parse(json, type, BindConfig.defaults());
    }

    /** Loads a String document under an explicit binding configuration (the UTF-16 engine). */
    public static <T> T parse(String json, Class<T> type, BindConfig config) {
        if (config.direct() && java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
            return DirectBinder.forClass(type).parseDirect(json, config);
        }
        return fromNode(Tree.parse(json), type, config, "$");
    }

    /** Serializes a bound value as compact JSON. */
    public static String write(Object value) {
        JoltWriter writer = Jolt.write();
        writeTo(value, writer, "$");
        return writer.toString();
    }

    /** Serializes {@code value} through an explicit binder. */
    public static <T> String write(T value, TypedBinder<T> binder) {
        JoltWriter writer = Jolt.write();
        binder.write(value, writer);
        return writer.toString();
    }

    /** Loads a UTF-8 JSON document through an explicit binder. */
    public static <T> T parse(byte[] json, TypedBinder<T> binder) {
        return binder.parse(Tree.parse(json), "$", BindConfig.defaults());
    }

    /** Loads a String document through an explicit binder. */
    public static <T> T parse(String json, TypedBinder<T> binder) {
        return binder.parse(Tree.parse(json), "$", BindConfig.defaults());
    }

    /** The binder cache: one reflective binder per target class, JVM-lifetime. */
    static <T> ReflectiveBinder<T> binderFor(Class<T> type) {
        return ReflectiveBinder.forClass(type);
    }

    /**
     * A reflective {@link TypedBinder} for {@code type} — the public entry point other modules
     * (jolt-gen's fallback, embedders) use to obtain the default adapter.
     */
    public static <T> TypedBinder<T> reflectiveBinder(Class<T> type) {
        return ReflectiveBinder.forClass(type);
    }

    // Generated binders registered by jolt-gen (M4): consulted BEFORE the reflective cache so
    // nested record/POJO values route to generated code when present. Registration is the
    // forward direction — jolt-bind never depends on jolt-gen (ADR-0004).
    private static final Map<Class<?>, TypedBinder<?>> GENERATED = new java.util.concurrent.ConcurrentHashMap<>();

    /** Registers a generated binder for {@code type} (called by jolt-gen only). */
    public static <T> void registerGenerated(Class<T> type, TypedBinder<T> binder) {
        GENERATED.put(type, binder);
    }

    @SuppressWarnings("unchecked")
    private static <T> TypedBinder<T> generatedFor(Class<T> type) {
        return (TypedBinder<T>) GENERATED.get(type);
    }

    /** Test-visible registered lookup (same map the nested routing consults). */
    public static TypedBinder<?> generatedLookupForTest(Class<?> type) {
        return GENERATED.get(type);
    }


    // --- record/POJO → value (parse direction) ---

    @SuppressWarnings("unchecked")
    private static <T> T fromNode(Node node, Class<T> type, BindConfig config, String path) {
        return (T) binderFor(type).parse(node, path, config);
    }

    /**
     * The strict component-value conversion shared by every bind adapter (reflective and
     * generated): the JSON type must match the target, failures carry the path.
     */
    public static Object toValue(Node field, java.lang.reflect.Type target, String path, BindConfig config) {
        // Missing and JSON null are different states with one shared rule: reference kinds read
        // as Java null, primitive targets reject (never a silent zero).
        if (field == null || field.type() == TypeTag.NULL) {
            boolean missing = field == null;
            if (target instanceof Class<?> raw && raw.isPrimitive()) {
                throw new JsonBindException((missing ? "missing value for" : "JSON null for")
                        + " primitive component " + raw.getSimpleName(), path);
            }
            return null;
        }
        if (target instanceof java.lang.reflect.ParameterizedType parameterized) {
            return toParameterized(field, parameterized, path, config);
        }
        if (target instanceof java.lang.reflect.GenericArrayType genericArray) {
            return toGenericArray(field, genericArray.getGenericComponentType(), path, config);
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
        return toGenericArray(field, element, path, config);
    }

    /** The array binding for any component type: the component keeps its full generic form. */
    private static Object toGenericArray(Node field, java.lang.reflect.Type component,
                                         String path, BindConfig config) {
        requireTag(field, TypeTag.ARRAY, "an array", path);
        List<Node> elements = field.elements();
        Class<?> raw = (component instanceof Class<?> c)
                ? c
                : (Class<?>) ((java.lang.reflect.ParameterizedType) component).getRawType();
        Object out = java.lang.reflect.Array.newInstance(raw, elements.size());
        for (int i = 0; i < elements.size(); i++) {
            java.lang.reflect.Array.set(out, i, toValue(elements.get(i), component, path + "[" + i + "]", config));
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
        TypedBinder<?> generated = generatedFor(raw);
        if (raw.isRecord()) {
            requireTag(field, TypeTag.OBJECT, "an object", path);
            if (generated != null) {
                return generated.parse(field, path, config);
            }
            return binderFor(raw.asSubclass(java.lang.Record.class)).parse(field, path, config);
        }
        if (raw == Object.class) {
            return naturalValue(field, path);
        }
        if (!raw.isInterface() && !java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
            if (generated != null) {
                return generated.parse(field, path, config);
            }
            return binderFor(raw).parse(field, path, config);
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
        if (isIntegralForm(text)) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException beyondLong) {
                return field.asDecimal(); // exact wide form (cached parse)
            }
        }
        double d = field.asDouble();
        if (Double.isInfinite(d)) {
            return field.asDecimal();
        }
        return d;
    }

    /** Optional sign followed by digits only — no regex compilation per untyped number. */
    private static boolean isIntegralForm(String text) {
        int start = text.startsWith("-") ? 1 : 0;
        if (start >= text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
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


    // --- value → writer (write direction, streaming mirror of the read families) ---





    /** Structural kinds dispatch through their cached binders; the rest are scalars. */
    private static boolean isStructure(Object value) {
        return value instanceof List<?> || value instanceof Map<?, ?> || value.getClass().isArray()
                || value instanceof java.lang.Record || value instanceof Enum<?>
                || !(value instanceof String || value instanceof Number
                     || value instanceof Boolean);
    }

    /**
     * The shared write emission for any value (scalars, collections, record/POJO via its
     * binder) — the contract every bind adapter writes through.
     */
    public static void writeTo(Object value, JoltWriter writer, String path) {
        if (value == null) {
            writer.nullValue();
            return;
        }
        if (value instanceof Node n) {
            emitNode(n, writer);
            return;
        }
        if (isStructure(value)) {
            Class<?> raw = value.getClass();
            if (raw.isRecord() || (!raw.isArray() && !(value instanceof List<?>)
                    && !(value instanceof Map<?, ?>) && !(value instanceof Enum<?>))) {
                // records and POJOs dispatch through their cached binders (fields only —
                // the value position owns its braces)
                writer.startObject();
                TypedBinder<?> generated = generatedFor(raw);
                if (generated instanceof GeneratedFields generatedFields) {
                    generatedFields.writeFields(value, writer, path);
                } else {
                    ReflectiveBinder.writeFieldsVia(value, raw, writer, path);
                }
                writer.endObject();
                return;
            }
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
        writeScalar(value, writer);
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
