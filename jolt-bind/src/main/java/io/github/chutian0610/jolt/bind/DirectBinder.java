package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.Utf16Parser;
import io.github.chutian0610.jolt.Utf8Parser;
import io.github.chutian0610.jolt.tree.Node;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The direct (token) read adapter: parser tokens feed one consumer straight to the
 * constructed record/POJO — no Node graph. Cached per class like the reflective binder; each
 * thread reuses its own parser pair and {@link BindPath} (steady-state allocation is the
 * target object graph only). Not reachable through the tree seam: {@link #parse} throws.
 *
 * <p>Semantics mirror the tree path instruction for instruction — strict tag checks with the
 * same messages in the same component-declaration priority order, the three-null contract,
 * unknown fields per config (the first key reported), polymorphic rejection, and the same
 * `$`-rooted paths (assembled lazily from the path carrier). Values are read type-driven
 * through {@link #readValue} — the same code path for components, collection elements, and
 * natural mapping. Field-name matching is zero-allocation
 * ({@link JoltParser#fieldNameEquals}); per-thread parsers are reused via {@code reset} (the
 * M1 reuse discipline).
 */
final class DirectBinder<T> implements TypedBinder<T> {

    private static final Map<Class<?>, DirectBinder<?>> CACHE = new ConcurrentHashMap<>();

    private enum Shape { RECORD, POJO }

    private final Shape shape;
    private final Class<T> type;
    private final String[] names;        // record component names | sorted setter keys
    private final String[] simpleNames;  // the component type's getSimpleName() — the tree
    // path builds bound/missing messages from it (boxed "Integer" vs primitive "int")
    private final Class<?>[] rawTypes;
    private final java.lang.reflect.Type[] genericTypes;
    private final boolean[] primitives;
    private final Constructor<T> constructor; // record canonical | POJO public no-arg
    private final Method[] setters;           // POJO only, sorted by name
    private final ThreadLocal<DirectState> state = ThreadLocal.withInitial(DirectState::new);

    private DirectBinder(Class<T> type, Shape shape) {
        this.type = type;
        this.shape = shape;
        if (shape == Shape.RECORD) {
            RecordComponent[] components = type.getRecordComponents();
            this.names = new String[components.length];
            this.simpleNames = new String[components.length];
            this.rawTypes = new Class<?>[components.length];
            this.genericTypes = new java.lang.reflect.Type[components.length];
            this.primitives = new boolean[components.length];
            this.setters = new Method[0];
            Class<?>[] argTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                names[i] = components[i].getName();
                rawTypes[i] = components[i].getType();
                genericTypes[i] = components[i].getGenericType();
                argTypes[i] = rawTypes[i];
                simpleNames[i] = rawTypes[i].getSimpleName();
                primitives[i] = rawTypes[i].isPrimitive();
            }
            try {
                this.constructor = type.getDeclaredConstructor(argTypes);
                this.constructor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(
                        "record without canonical constructor: " + type.getName(), e);
            }
            return;
        }
        // POJO: public no-arg constructor + sorted public setters (the M3 contract)
        this.names = setterKeys(type);
        this.simpleNames = new String[names.length];
        this.rawTypes = new Class<?>[names.length];
        this.genericTypes = new java.lang.reflect.Type[names.length];
        this.primitives = new boolean[names.length];
        this.setters = sortedSetters(type);
        for (int i = 0; i < setters.length; i++) {
            Class<?> raw = setters[i].getParameterTypes()[0];
            rawTypes[i] = raw;
            genericTypes[i] = setters[i].getGenericParameterTypes()[0];
            simpleNames[i] = raw.getSimpleName();
            primitives[i] = raw.isPrimitive();
        }
        try {
            this.constructor = type.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type.getName()
                    + " has no public no-arg constructor and is not a record", e);
        }
    }

    private static String[] setterKeys(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (isSetter(method)) {
                String key = method.getName().substring(3);
                out.add(Character.toLowerCase(key.charAt(0)) + key.substring(1));
            }
        }
        out.sort(java.util.Comparator.naturalOrder());
        return out.toArray(new String[0]);
    }

    private static Method[] sortedSetters(Class<?> type) {
        List<Method> out = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (isSetter(method)) {
                out.add(method);
            }
        }
        out.sort(java.util.Comparator.comparing(Method::getName));
        return out.toArray(new Method[0]);
    }

    private static boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getReturnType() == void.class
                && method.getParameterCount() == 1
                && java.lang.reflect.Modifier.isPublic(method.getModifiers());
    }

    @SuppressWarnings("unchecked")
    static <T> DirectBinder<T> forClass(Class<T> type) {
        DirectBinder<?> cached = CACHE.get(type);
        if (cached != null) {
            return (DirectBinder<T>) cached;
        }
        synchronized (CACHE) {
            cached = CACHE.get(type);
            if (cached == null) {
                cached = new DirectBinder<>(type,
                        type.isRecord() ? Shape.RECORD : Shape.POJO);
                CACHE.put(type, cached);
            }
            return (DirectBinder<T>) cached;
        }
    }

    /** The tree seam is not supported by the direct adapter — tokens in, values out. */
    @Override
    public T parse(Node node, String path, BindConfig config) {
        throw new UnsupportedOperationException(
                "the direct binder reads tokens, not nodes");
    }

    /**
     * The write direction is shared — both config arms write through the same reflective
     * binder (streaming since M4); there is no direct-write variant to fork.
     */
    @Override
    public void write(T value, io.github.chutian0610.jolt.JoltWriter writer) {
        Bind.reflectiveBinder(type).write(value, writer);
    }

    @Override
    public T parseTokens(JoltParser parser, BindConfig config) {
        BindPath path = state.get().path;
        path.reset();
        Token first = parser.nextToken();
        if (first != Token.START_OBJECT) {
            if (shape == Shape.RECORD) {
                // a non-object document: the tree path reads every component as missing
                // (get(name) is null on a scalar node) — the first primitive component throws
                throwFirstMissingPrimitive(path);
            }
            // a POJO reads every property as absent and constructs the default instance —
            // the tree path's setter loop skips everything for a scalar root
            return constructEmpty(path);
        }
        T value = shape == Shape.RECORD
                ? readRecordBody(parser, config, path, first)
                : readPojoBody(parser, config, path, first);
        // drain the tail exactly like the tree loader: parser syntax errors surface unchanged
        while (parser.nextToken() != Token.EOF) {
            // trailing content
        }
        return value;
    }

    /** Facade convenience: reuses the thread's parser (steady-state allocation = target graph). */
    T parseDirect(byte[] json, BindConfig config) {
        DirectState st = state.get();
        return parseTokens(st.parserFor(json), config);
    }

    /** Facade convenience over the UTF-16 engine. */
    T parseDirect(String json, BindConfig config) {
        DirectState st = state.get();
        return parseTokens(st.parserFor(json), config);
    }

    private T constructEmpty(BindPath path) {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot instantiate " + type.getName(), path.resolve());
        }
    }

    // --- record body ---

    private T readRecordBody(JoltParser p, BindConfig config, BindPath path, Token first) {
        Object[] args = new Object[names.length]; // null = missing (reference nulls and
        // missing components are indistinguishable by design — both bind as null)
        // The tree path raises errors in COMPONENT-DECLARATION order (it loops the component
        // table); the token stream arrives in DOCUMENT order. Errors therefore land in
        // lazily-allocated slots (the failure path may allocate — ADR-0005) and the
        // highest-priority one is raised after the object closes. The unknown-field check
        // runs after that, exactly like the tree path's keys scan.
        Object[] errors = null;
        String unknownKey = null;
        Token t = first;
        while ((t = p.nextToken()) != Token.END_OBJECT) {
            // t == FIELD_NAME — match by name against the component table, zero allocation
            int comp = -1;
            for (int i = 0; i < names.length; i++) {
                if (p.fieldNameEquals(names[i])) {
                    comp = i;
                    break;
                }
            }
            if (comp < 0) {
                unknownKey = noteUnknown(p, config, unknownKey);
                skipValue(p);
                continue;
            }
            path.pushName(names[comp]);
            Token valueToken = p.nextToken();
            try {
                args[comp] = readValue(p, valueToken, genericTypes[comp], primitives[comp],
                        simpleNames[comp], config, path);
            } catch (JsonBindException e) {
                // the error is recorded in the component's slot; a container value has
                // already been skipped by the throw site, scalar tokens were consumed by
                // the read — the field loop stays aligned either way
                if (errors == null) {
                    errors = new Object[names.length];
                }
                errors[comp] = e;
            }
            path.pop();
        }
        // declaration-order error sweep: tag/JSON-null errors and the missing check
        // interleaved by component index — one declaration-order loop, like the tree path
        for (int i = 0; i < names.length; i++) {
            if (errors != null && errors[i] instanceof JsonBindException e) {
                throw e;
            }
            if (args[i] == null && primitives[i]) {
                throw new JsonBindException("missing value for primitive component "
                        + simpleNames[i], path.resolve() + "." + names[i]);
            }
        }
        if (unknownKey != null) {
            throw new JsonBindException("unknown field '" + unknownKey + "'",
                    path.resolve() + "." + unknownKey);
        }
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new JsonBindException(
                    "record constructor rejected the bound values: " + e.getCause(),
                    path.resolve());
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException(
                    "cannot construct " + type.getName() + ": " + e, path.resolve());
        }
    }

    // --- POJO body ---

    private T readPojoBody(JoltParser p, BindConfig config, BindPath path, Token first) {
        T instance;
        try {
            instance = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot instantiate " + type.getName(), path.resolve());
        }
        // the setter table declares the error priority; the token stream arrives in document
        // order — the same lazy-slot reshaping the record body uses
        Object[] errors = null;
        String unknownKey = null;
        Token t = first;
        while ((t = p.nextToken()) != Token.END_OBJECT) {
            int comp = -1;
            for (int i = 0; i < names.length; i++) {
                if (p.fieldNameEquals(names[i])) {
                    comp = i;
                    break;
                }
            }
            if (comp < 0) {
                unknownKey = noteUnknown(p, config, unknownKey);
                skipValue(p);
                continue;
            }
            path.pushName(names[comp]);
            Token valueToken = p.nextToken();
            try {
                Object bound = readValue(p, valueToken, genericTypes[comp], primitives[comp],
                        simpleNames[comp], config, path);
                setters[comp].invoke(instance, bound);
            } catch (InvocationTargetException rejected) {
                if (errors == null) {
                    errors = new Object[names.length];
                }
                errors[comp] = new JsonBindException("setter rejected the bound value: "
                        + rejected.getCause(), path.resolve());
            } catch (JsonBindException e) {
                if (errors == null) {
                    errors = new Object[names.length];
                }
                errors[comp] = e;
            } catch (ReflectiveOperationException inaccessible) {
                if (errors == null) {
                    errors = new Object[names.length];
                }
                errors[comp] = new JsonBindException("cannot invoke setter for " + names[comp],
                        path.resolve());
            }
            path.pop();
        }
        // declaration-order sweep (missing does not exist for POJOs — absent properties
        // stay default), then the unknown-field scan
        for (int i = 0; i < names.length; i++) {
            if (errors != null && errors[i] instanceof JsonBindException e) {
                throw e;
            }
        }
        if (unknownKey != null) {
            throw new JsonBindException("unknown field '" + unknownKey + "'",
                    path.resolve() + "." + unknownKey);
        }
        return instance;
    }

    /** The first unknown key's name materializes only when strict mode demands it. */
    private static String noteUnknown(JoltParser p, BindConfig config, String recorded) {
        if (config.failOnUnknown() && recorded == null) {
            return p.textValue();
        }
        return recorded;
    }

    // --- the type-driven value read (components, elements, and natural mapping share it) ---

    @SuppressWarnings("unchecked")
    /** {@code first} is the ALREADY-CONSUMED value token — every reader in the direct
     * path follows the "who read the token passes it down" contract, so nested reads
     * never double-consume. */
    private Object readValue(JoltParser p, Token first, java.lang.reflect.Type target,
                             boolean primitive, String simpleName, BindConfig config,
                             BindPath path) {
        Token t = first;
        if (t == Token.VALUE_NULL) {
            if (primitive) {
                throw new JsonBindException("JSON null for primitive component " + simpleName,
                        path.resolve());
            }
            return null;
        }
        if (target instanceof java.lang.reflect.ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
            if (raw == List.class) {
                return readList(p, t, args[0], config, path);
            }
            if (raw == Map.class) {
                if (args[0] != String.class) {
                    throw new JsonBindException("map keys must be String, got " + args[0],
                            path.resolve());
                }
                return readMap(p, t, args[1], config, path);
            }
            throw new JsonBindException(
                    "unsupported generic type " + parameterized.getTypeName(), path.resolve());
        }
        if (target instanceof java.lang.reflect.GenericArrayType genericArray) {
            return readArray(p, t, genericArray.getGenericComponentType(), config, path);
        }
        if (!(target instanceof Class<?> raw)) {
            throw new JsonBindException("unsupported component type " + target, path.resolve());
        }
        if (raw.isArray()) {
            return readArray(p, t, raw.getComponentType(), config, path);
        }
        // strict scalars
        if (raw == String.class) {
            if (t != Token.VALUE_STRING) {
                throw tagError(p, t, "a string", path);
            }
            return p.textValue();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            if (t == Token.VALUE_TRUE) {
                return Boolean.TRUE;
            }
            if (t == Token.VALUE_FALSE) {
                return Boolean.FALSE;
            }
            throw tagError(p, t, "a boolean", path);
        }
        if (raw == byte.class || raw == Byte.class) {
            long v = readLong(p, t, path);
            if (v > Byte.MAX_VALUE || v < Byte.MIN_VALUE) {
                throw new JsonBindException("number does not fit in " + simpleName,
                        path.resolve());
            }
            return Byte.valueOf((byte) v);
        }
        if (raw == short.class || raw == Short.class) {
            long v = readLong(p, t, path);
            if (v > Short.MAX_VALUE || v < Short.MIN_VALUE) {
                throw new JsonBindException("number does not fit in " + simpleName,
                        path.resolve());
            }
            return Short.valueOf((short) v);
        }
        if (raw == int.class || raw == Integer.class) {
            long v = readLong(p, t, path);
            if (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) {
                throw new JsonBindException("number does not fit in " + simpleName,
                        path.resolve());
            }
            return Integer.valueOf((int) v);
        }
        if (raw == long.class || raw == Long.class) {
            return Long.valueOf(readLong(p, t, path));
        }
        if (raw == float.class || raw == Float.class) {
            requireNumber(p, t, path);
            return Float.valueOf(p.bigDecimalValue().floatValue());
        }
        if (raw == double.class || raw == Double.class) {
            requireNumber(p, t, path);
            return Double.valueOf(p.bigDecimalValue().doubleValue());
        }
        if (raw == java.math.BigDecimal.class) {
            requireNumber(p, t, path);
            return p.bigDecimalValue();
        }
        if (raw == java.math.BigInteger.class) {
            requireNumber(p, t, path);
            try {
                return p.bigDecimalValue().toBigIntegerExact();
            } catch (ArithmeticException lossy) {
                throw new JsonBindException("number is not a whole number", path.resolve());
            }
        }
        if (raw.isEnum()) {
            if (t != Token.VALUE_STRING) {
                throw tagError(p, t, "a string", path);
            }
            String text = p.textValue();
            try {
                return Enum.valueOf((Class<? extends Enum>) raw, text);
            } catch (IllegalArgumentException unknownName) {
                throw new JsonBindException("unknown enum name '" + text
                        + "' for " + raw.getSimpleName(), path.resolve());
            }
        }
        if (raw == Object.class) {
            return switch (t) {
                case VALUE_STRING -> p.textValue();
                case VALUE_TRUE -> Boolean.TRUE;
                case VALUE_FALSE -> Boolean.FALSE;
                case VALUE_NUMBER_INT -> naturalInt(p);
                case VALUE_NUMBER_FLOAT -> naturalFloat(p);
                case START_OBJECT -> readNaturalObject(p, config, path);
                case START_ARRAY -> readNaturalArray(p, config, path);
                default -> throw new IllegalStateException("unexpected token " + t);
            };
        }
        if (raw.isRecord()) {
            if (t != Token.START_OBJECT) {
                throw tagError(p, t, "an object", path);
            }
            // the child's OWN binder carries the child's component table — recursion
            // happens at runtime through the cache (self-referential records converge;
            // construction itself never recurses)
            return DirectBinder.forClass(raw).readRecordBody(p, config, path, t);
        }
        if (!raw.isInterface() && !java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
            if (t != Token.START_OBJECT) {
                // the tree path hands a scalar node to the POJO binder, whose setter loop
                // skips everything — the result is the default instance
                return constructEmptyOf(raw, path);
            }
            return DirectBinder.forClass(raw).readPojoBody(p, config, path, t);
        }
        throw new JsonBindException(
                "polymorphic binding is not supported for " + raw.getSimpleName(),
                path.resolve());
    }

    /** Natural mapping numbers: integral text → Long (overflow → BigDecimal), else Double
     * (infinite → BigDecimal) — the tree path's naturalNumber, token-for-token. */
    private Object naturalInt(JoltParser p) {
        String text = p.numberText();
        if (isIntegralForm(text)) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException beyondLong) {
                return p.bigDecimalValue(); // the exact wide form
            }
        }
        double d = p.doubleValue();
        return Double.isInfinite(d) ? p.bigDecimalValue() : d;
    }

    private Object naturalFloat(JoltParser p) {
        double d = p.doubleValue();
        return Double.isInfinite(d) ? p.bigDecimalValue() : d;
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

    private Map<Object, Object> readNaturalObject(JoltParser p, BindConfig config,
                                                  BindPath path) {
        Map<Object, Object> out = new LinkedHashMap<>();
        Token look = p.nextToken();
        while (look != Token.END_OBJECT) {
            String key = p.textValue();
            Token valueToken = p.nextToken();
            path.pushName(key);
            try {
                out.put(key, readValue(p, valueToken, Object.class, false, null, config, path));
            } finally {
                path.pop();
            }
            look = p.nextToken();
        }
        return out;
    }

    private List<Object> readNaturalArray(JoltParser p, BindConfig config, BindPath path) {
        List<Object> out = new ArrayList<>();
        int i = 0;
        Token look = p.nextToken();
        while (look != Token.END_ARRAY) {
            path.pushIndex(i);
            try {
                out.add(readValue(p, look, Object.class, false, null, config, path));
            } finally {
                path.pop();
            }
            i++;
            look = p.nextToken();
        }
        return out;
    }

    private Object constructEmptyOf(Class<?> raw, BindPath path) {
        try {
            return DirectBinder.forClass(raw).constructEmpty(path);
        } catch (ClassCastException e) {
            throw new IllegalStateException(raw.getName() + " is not a supported target", e);
        }
    }

    /** List&lt;X&gt;: the caller-read token must be START_ARRAY, then each element through
     * the element type's read. */
    private List<Object> readList(JoltParser p, Token first,
                                  java.lang.reflect.Type elementType,
                                  BindConfig config, BindPath path) {
        if (first != Token.START_ARRAY) {
            throw tagError(p, first, "an array", path);
        }
        List<Object> out = new ArrayList<>();
        int i = 0;
        Token look = p.nextToken();
        while (look != Token.END_ARRAY) {
            path.pushIndex(i);
            try {
                out.add(readValue(p, look, elementType, false, null, config, path));
            } finally {
                path.pop();
            }
            i++;
            look = p.nextToken();
        }
        return out;
    }

    /** Map&lt;String,X&gt;: the caller-read token must be START_OBJECT, each key materialized
     * (map keys are data). */
    private Map<Object, Object> readMap(JoltParser p, Token first,
                                        java.lang.reflect.Type valueType,
                                        BindConfig config, BindPath path) {
        if (first != Token.START_OBJECT) {
            throw tagError(p, first, "an object", path);
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        Token look = p.nextToken();
        while (look != Token.END_OBJECT) {
            String key = p.textValue();
            Token valueToken = p.nextToken();
            path.pushName(key);
            try {
                out.put(key, readValue(p, valueToken, valueType, false, null, config, path));
            } finally {
                path.pop();
            }
            look = p.nextToken();
        }
        return out;
    }

    /** X[]: the length is unknown in a stream, so elements buffer before the array is built
     * (the buffer is implementation scratch — the allocation contract reviews it in M6-T4). */
    private Object readArray(JoltParser p, Token first,
                             java.lang.reflect.Type componentType,
                             BindConfig config, BindPath path) {
        Class<?> raw = componentType instanceof Class<?> c
                ? c
                : (Class<?>) ((java.lang.reflect.ParameterizedType) componentType).getRawType();
        if (first != Token.START_ARRAY) {
            throw tagError(p, first, "an array", path);
        }
        List<Object> buffer = new ArrayList<>();
        int i = 0;
        Token look = p.nextToken();
        while (look != Token.END_ARRAY) {
            path.pushIndex(i);
            try {
                buffer.add(readValue(p, look, componentType, raw.isPrimitive(),
                        raw.getSimpleName(), config, path));
            } finally {
                path.pop();
            }
            i++;
            look = p.nextToken();
        }
        Object out = java.lang.reflect.Array.newInstance(raw, buffer.size());
        for (int j = 0; j < buffer.size(); j++) {
            java.lang.reflect.Array.set(out, j, buffer.get(j));
        }
        return out;
    }

    /** VALUE_NUMBER_INT with the promotion-strategy overflow message; anything else is a tag error. */
    private long readLong(JoltParser p, Token t, BindPath path) {
        if (t != Token.VALUE_NUMBER_INT) {
            throw tagError(p, t, "a number", path);
        }
        try {
            return p.longValue();
        } catch (ArithmeticException beyondLong) {
            throw new JsonBindException("number does not fit in a long", path.resolve());
        }
    }

    private void requireNumber(JoltParser p, Token t, BindPath path) {
        if (t != Token.VALUE_NUMBER_INT && t != Token.VALUE_NUMBER_FLOAT) {
            throw tagError(p, t, "a number", path);
        }
    }

    /**
     * The tree layer's tag-rejection message at the value's path. A container value is
     * skipped before the throw so the field loop stays aligned.
     */
    private RuntimeException tagError(JoltParser p, Token t, String wanted, BindPath path) {
        if (t == Token.START_OBJECT || t == Token.START_ARRAY) {
            p.skipChildren();
        }
        throw new JsonBindException(
                "expected " + wanted + " but found " + tagName(t), path.resolve());
    }

    /** The tree layer's TypeTag names, from the streaming token. */
    private static String tagName(Token t) {
        return switch (t) {
            case VALUE_STRING -> "STRING";
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "NUMBER";
            case VALUE_TRUE, VALUE_FALSE -> "BOOLEAN";
            case VALUE_NULL -> "NULL";
            case START_OBJECT, FIELD_NAME -> "OBJECT";
            case START_ARRAY -> "ARRAY";
            default -> throw new IllegalStateException("unexpected token " + t);
        };
    }

    /**
     * A non-object document root: the tree path reads every component as missing and the
     * first primitive component throws at its own path. An all-reference record would bind
     * as all-null there (the tree path constructs it) — that lands with the spectrum tickets.
     */
    private void throwFirstMissingPrimitive(BindPath path) {
        for (int i = 0; i < names.length; i++) {
            if (primitives[i]) {
                throw new JsonBindException("missing value for primitive component "
                        + simpleNames[i], path.resolve() + "." + names[i]);
            }
        }
        throw new UnsupportedOperationException(
                "all-reference records bind from non-object documents with the M6 spectrum tickets");
    }

    /**
     * Consumes the value after an unmatched field name: containers skip whole (zero
     * decoding), scalar value tokens are already the next token and just get consumed by
     * the loop's next FIELD_NAME step.
     */
    private void skipValue(JoltParser p) {
        Token t = p.nextToken();
        if (t == Token.START_OBJECT || t == Token.START_ARRAY) {
            p.skipChildren();
        }
    }

    /** Per-thread parser pair and path stack (the M1 reuse discipline, applied to binding). */
    private static final class DirectState {
        final BindPath path = new BindPath();
        private Utf8Parser utf8;
        private Utf16Parser utf16;

        JoltParser parserFor(byte[] json) {
            if (utf8 == null) {
                utf8 = io.github.chutian0610.jolt.Jolt.utf8Parser(json);
                return utf8;
            }
            return utf8.reset(json);
        }

        JoltParser parserFor(String json) {
            if (utf16 == null) {
                utf16 = io.github.chutian0610.jolt.Jolt.utf16Parser(json);
                return utf16;
            }
            return utf16.reset(json);
        }
    }
}
