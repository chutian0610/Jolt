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

    private static final Plan NATURAL_PLAN = new Plan.PNatural();

    /**
     * The per-component read plan, resolved ONCE at construction: generic-type reflection
     * (getActualTypeArguments and friends) allocates per call, so the hot path sees only
     * the resolved plan. Unsupported shapes (polymorphic targets, exotic generics) carry
     * their rejection message here and raise it at the value's path, runtime-time, exactly
     * when the tree path would.
     */
    private sealed interface Plan {
        record PScalar(Class<?> raw, boolean primitive, String simpleName) implements Plan {}
        record PEnum(Class<?> raw) implements Plan {}
        record PList(Plan element) implements Plan {}
        record PMap(Plan value, String keyTypeName, boolean stringKeys) implements Plan {}
        record PArray(Plan component, Class<?> raw) implements Plan {}
        record PRecord(java.lang.Class<?> raw) implements Plan {}
        record PPojo(java.lang.Class<?> raw) implements Plan {}
        record PNatural() implements Plan {}
        record PUnsupported(String message) implements Plan {}
    }

    private static Plan planOf(java.lang.reflect.Type target) {
        if (target instanceof java.lang.reflect.ParameterizedType parameterized) {
            Class<?> raw = (Class<?>) parameterized.getRawType();
            java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
            if (raw == List.class) {
                return new Plan.PList(planOf(args[0]));
            }
            if (raw == Map.class) {
                return new Plan.PMap(planOf(args[1]), args[0].getTypeName(),
                        args[0] == String.class);
            }
            return new Plan.PUnsupported("unsupported generic type "
                    + parameterized.getTypeName());
        }
        if (target instanceof java.lang.reflect.GenericArrayType genericArray) {
            return planArray(genericArray.getGenericComponentType());
        }
        if (!(target instanceof Class<?> raw)) {
            return new Plan.PUnsupported("unsupported component type " + target);
        }
        return planClass(raw, raw.isPrimitive(), raw.getSimpleName());
    }

    private static Plan planArray(java.lang.reflect.Type component) {
        Class<?> raw = component instanceof Class<?> c
                ? c
                : (Class<?>) ((java.lang.reflect.ParameterizedType) component).getRawType();
        return new Plan.PArray(planOf(component), raw);
    }

    private static Plan planClass(Class<?> raw, boolean primitive, String simpleName) {
        if (raw == String.class || raw == boolean.class || raw == Boolean.class
                || raw == byte.class || raw == Byte.class || raw == short.class || raw == Short.class
                || raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class
                || raw == float.class || raw == Float.class || raw == double.class || raw == Double.class
                || raw == java.math.BigDecimal.class || raw == java.math.BigInteger.class) {
            return new Plan.PScalar(raw, primitive, simpleName);
        }
        if (raw.isEnum()) {
            return new Plan.PEnum(raw);
        }
        if (raw == Object.class) {
            return new Plan.PNatural();
        }
        if (raw.isRecord()) {
            return new Plan.PRecord(raw);
        }
        if (raw.isArray()) {
            return planArray(raw.getComponentType());
        }
        if (!raw.isInterface() && !java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
            return new Plan.PPojo(raw);
        }
        return new Plan.PUnsupported("polymorphic binding is not supported for "
                + raw.getSimpleName());
    }

    private final Shape shape;
    private final Class<T> type;
    private final String[] names;        // record component names | sorted setter keys
    private final String[] simpleNames;  // the component type's getSimpleName() — the tree
    // path builds bound/missing messages from it (boxed "Integer" vs primitive "int")
    private final Class<?>[] rawTypes;
    private final Plan[] plans;
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
            this.plans = new Plan[components.length];
            this.primitives = new boolean[components.length];
            this.setters = new Method[0];
            Class<?>[] argTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                names[i] = components[i].getName();
                rawTypes[i] = components[i].getType();
                argTypes[i] = rawTypes[i];
                simpleNames[i] = rawTypes[i].getSimpleName();
                primitives[i] = rawTypes[i].isPrimitive();
                plans[i] = planOf(components[i].getGenericType());
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
        this.plans = new Plan[names.length];
        this.primitives = new boolean[names.length];
        this.setters = sortedSetters(type);
        for (int i = 0; i < setters.length; i++) {
            Class<?> raw = setters[i].getParameterTypes()[0];
            rawTypes[i] = raw;
            simpleNames[i] = raw.getSimpleName();
            primitives[i] = raw.isPrimitive();
            plans[i] = planOf(setters[i].getGenericParameterTypes()[0]);
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
                args[comp] = readValue(p, valueToken, plans[comp], config, path);
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
                Object bound = readValue(p, valueToken, plans[comp], config, path);
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
     * never double-consume. {@code plan} carries the construction-resolved type shape. */
    private Object readValue(JoltParser p, Token first, Plan plan,
                             BindConfig config, BindPath path) {
        Token t = first;
        if (t == Token.VALUE_NULL) {
            if (plan instanceof Plan.PScalar scalar && scalar.primitive()) {
                throw new JsonBindException("JSON null for primitive component "
                        + scalar.simpleName(), path.resolve());
            }
            return null;
        }
        switch (plan) {
            case Plan.PScalar scalar -> {
                Class<?> raw = scalar.raw();
                switch (raw.getSimpleName()) {
                    case "String" -> {
                        if (t != Token.VALUE_STRING) {
                            throw tagError(p, t, "a string", path);
                        }
                        return p.textValue();
                    }
                    case "boolean", "Boolean" -> {
                        if (t == Token.VALUE_TRUE) {
                            return Boolean.TRUE;
                        }
                        if (t == Token.VALUE_FALSE) {
                            return Boolean.FALSE;
                        }
                        throw tagError(p, t, "a boolean", path);
                    }
                    case "byte", "Byte" -> {
                        long v = readLong(p, t, path);
                        if (v > Byte.MAX_VALUE || v < Byte.MIN_VALUE) {
                            throw new JsonBindException("number does not fit in "
                                    + scalar.simpleName(), path.resolve());
                        }
                        return Byte.valueOf((byte) v);
                    }
                    case "short", "Short" -> {
                        long v = readLong(p, t, path);
                        if (v > Short.MAX_VALUE || v < Short.MIN_VALUE) {
                            throw new JsonBindException("number does not fit in "
                                    + scalar.simpleName(), path.resolve());
                        }
                        return Short.valueOf((short) v);
                    }
                    case "int", "Integer" -> {
                        long v = readLong(p, t, path);
                        if (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) {
                            throw new JsonBindException("number does not fit in "
                                    + scalar.simpleName(), path.resolve());
                        }
                        return Integer.valueOf((int) v);
                    }
                    case "long", "Long" -> {
                        return Long.valueOf(readLong(p, t, path));
                    }
                    case "float", "Float" -> {
                        requireNumber(p, t, path);
                        return Float.valueOf(p.bigDecimalValue().floatValue());
                    }
                    case "double", "Double" -> {
                        requireNumber(p, t, path);
                        return Double.valueOf(p.bigDecimalValue().doubleValue());
                    }
                    case "BigDecimal" -> {
                        requireNumber(p, t, path);
                        return p.bigDecimalValue();
                    }
                    default -> { // BigInteger
                        requireNumber(p, t, path);
                        try {
                            return p.bigDecimalValue().toBigIntegerExact();
                        } catch (ArithmeticException lossy) {
                            throw new JsonBindException("number is not a whole number",
                                    path.resolve());
                        }
                    }
                }
            }
            case Plan.PEnum enumPlan -> {
                if (t != Token.VALUE_STRING) {
                    throw tagError(p, t, "a string", path);
                }
                String text = p.textValue();
                try {
                    return java.lang.Enum.valueOf((Class<? extends java.lang.Enum>) enumPlan.raw(), text);
                } catch (IllegalArgumentException unknownName) {
                    throw new JsonBindException("unknown enum name '" + text
                            + "' for " + enumPlan.raw().getSimpleName(), path.resolve());
                }
            }
            case Plan.PList listPlan -> {
                return readList(p, t, listPlan, config, path);
            }
            case Plan.PMap mapPlan -> {
                if (!mapPlan.stringKeys()) {
                    throw new JsonBindException("map keys must be String, got "
                            + mapPlan.keyTypeName(), path.resolve());
                }
                return readMap(p, t, mapPlan, config, path);
            }
            case Plan.PArray arrayPlan -> {
                return readArray(p, t, arrayPlan, config, path);
            }

            case Plan.PRecord recordPlan -> {
                if (t != Token.START_OBJECT) {
                    throw tagError(p, t, "an object", path);
                }
                // the child's OWN binder carries the child's component table — recursion
                // happens at runtime through the cache (self-referential records converge;
                // construction itself never recurses)
                return DirectBinder.forClass(recordPlan.raw())
                        .readRecordBody(p, config, path, t);
            }
            case Plan.PPojo pojoPlan -> {
                if (t != Token.START_OBJECT) {
                    // the tree path hands a scalar node to the POJO binder, whose setter loop
                    // skips everything — the result is the default instance
                    return constructEmptyOf(pojoPlan.raw(), path);
                }
                return DirectBinder.forClass(pojoPlan.raw()).readPojoBody(p, config, path, t);
            }
            case Plan.PNatural naturalPlan -> {
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
            case Plan.PUnsupported unsupported -> throw new JsonBindException(
                    unsupported.message(), path.resolve());
        }
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
                out.put(key, readValue(p, valueToken, NATURAL_PLAN, config, path));
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
                out.add(readValue(p, look, NATURAL_PLAN, config, path));
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
    private List<Object> readList(JoltParser p, Token first, Plan.PList listPlan,
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
                out.add(readValue(p, look, listPlan.element(), config, path));
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
    private Map<Object, Object> readMap(JoltParser p, Token first, Plan.PMap mapPlan,
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
                out.put(key, readValue(p, valueToken, mapPlan.value(), config, path));
            } finally {
                path.pop();
            }
            look = p.nextToken();
        }
        return out;
    }

    /**
     * X[]: the length is unknown in a stream, so elements buffer before the array is built —
     * in a POOLED scratch buffer (per-thread, grown to the fixture's shape in warmup, reused
     * across calls and across nesting via the pool), so the steady-state allocation is the
     * target array itself. The buffer is implementation scratch; the pool bounds its lifecycle.
     */
    private Object readArray(JoltParser p, Token first, Plan.PArray arrayPlan,
                             BindConfig config, BindPath path) {
        Class<?> raw = arrayPlan.raw();
        if (first != Token.START_ARRAY) {
            throw tagError(p, first, "an array", path);
        }
        DirectState st = state.get();
        Object[] buffer = st.takeBuffer();
        int len = 0;
        try {
            int i = 0;
            Token look = p.nextToken();
            while (look != Token.END_ARRAY) {
                path.pushIndex(i);
                try {
                    if (len == buffer.length) {
                        Object[] grown = new Object[len * 2];
                        System.arraycopy(buffer, 0, grown, 0, len);
                        buffer = grown;
                    }
                    buffer[len++] = readValue(p, look, arrayPlan.component(), config, path);
                } finally {
                    path.pop();
                }
                i++;
                look = p.nextToken();
            }
            Object out = java.lang.reflect.Array.newInstance(raw, len);
            System.arraycopy(buffer, 0, out, 0, len);
            return out;
        } finally {
            for (int j = 0; j < len; j++) {
                buffer[j] = null; // release the element references into the pooled scratch
            }
            st.returnBuffer(buffer);
        }
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

    /** Per-thread parser pair, path stack, and array scratch pool (the M1 reuse discipline,
     * applied to binding). */
    private static final class DirectState {
        final BindPath path = new BindPath();
        private final java.util.ArrayDeque<Object[]> bufferPool = new java.util.ArrayDeque<>();
        private Utf8Parser utf8;
        private Utf16Parser utf16;

        Object[] takeBuffer() {
            Object[] buffered = bufferPool.pollFirst();
            return buffered != null ? buffered : new Object[8];
        }

        void returnBuffer(Object[] buffer) {
            if (bufferPool.size() < 8) {
                bufferPool.addFirst(buffer);
            }
        }

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
