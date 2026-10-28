package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltParser;
import io.github.chutian0610.jolt.Token;
import io.github.chutian0610.jolt.Utf16Parser;
import io.github.chutian0610.jolt.Utf8Parser;
import io.github.chutian0610.jolt.tree.Node;


import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The direct (token) read adapter: parser tokens feed one consumer straight to the
 * constructed record — no Node graph. Cached per class like the reflective binder; each
 * thread reuses its own parser pair and {@link BindPath} (steady-state allocation is the
 * target object graph only). Not reachable through the tree seam: {@link #parse} throws.
 *
 * <p>Semantics mirror the tree path instruction for instruction — strict tag checks with the
 * same messages in the same component-declaration priority order, the three-null contract,
 * unknown fields per config, and the same `$`-rooted paths (assembled lazily from the path
 * carrier). Field-name matching is zero-allocation ({@link JoltParser#fieldNameEquals});
 * per-thread parsers are reused via {@code reset} (the M1 reuse discipline).
 */
final class DirectBinder<T> implements TypedBinder<T> {

    private static final Map<Class<?>, DirectBinder<?>> CACHE = new ConcurrentHashMap<>();

    // component kinds (strict scalars; the M3 spectrum widens in later tickets)
    private static final int K_STRING = 0;
    private static final int K_BOOLEAN = 1;
    private static final int K_BYTE = 2;
    private static final int K_SHORT = 3;
    private static final int K_INT = 4;
    private static final int K_LONG = 5;
    private static final int K_FLOAT = 6;
    private static final int K_DOUBLE = 7;

    private final Class<T> type;
    private final String[] names;
    private final int[] kinds;
    private final boolean[] primitives;
    private final Constructor<T> constructor;
    private final ThreadLocal<DirectState> state = ThreadLocal.withInitial(DirectState::new);

    @SuppressWarnings("unchecked")
    private DirectBinder(Class<?> type) {
        if (!type.isRecord()) {
            throw new UnsupportedOperationException(
                    "direct binding covers records first; " + type.getName()
                            + " lands with the M6 spectrum tickets");
        }
        this.type = (Class<T>) type;
        RecordComponent[] components = type.getRecordComponents();
        this.names = new String[components.length];
        this.kinds = new int[components.length];
        this.primitives = new boolean[components.length];
        Class<?>[] argTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            names[i] = components[i].getName();
            Class<?> raw = components[i].getType();
            argTypes[i] = raw;
            primitives[i] = raw.isPrimitive();
            kinds[i] = kindOf(raw);
        }
        try {
            this.constructor = (Constructor<T>) type.getDeclaredConstructor(argTypes);
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "record without canonical constructor: " + type.getName(), e);
        }
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
                cached = new DirectBinder<>(type);
                CACHE.put(type, cached);
            }
            return (DirectBinder<T>) cached;
        }
    }

    private static int kindOf(Class<?> raw) {
        if (raw == String.class) {
            return K_STRING;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return K_BOOLEAN;
        }
        if (raw == byte.class || raw == Byte.class) {
            return K_BYTE;
        }
        if (raw == short.class || raw == Short.class) {
            return K_SHORT;
        }
        if (raw == int.class || raw == Integer.class) {
            return K_INT;
        }
        if (raw == long.class || raw == Long.class) {
            return K_LONG;
        }
        if (raw == float.class || raw == Float.class) {
            return K_FLOAT;
        }
        if (raw == double.class || raw == Double.class) {
            return K_DOUBLE;
        }
        throw new UnsupportedOperationException(
                "component type " + raw.getName() + " lands with the M6 spectrum tickets");
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
        return readRecord(parser, config, path);
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

    private T readRecord(JoltParser p, BindConfig config, BindPath path) {
        Object[] args = new Object[names.length]; // null = missing (reference nulls and
        // missing components are indistinguishable by design — both bind as null)
        // The tree path raises errors in COMPONENT-DECLARATION order (it loops the component
        // table); the token stream arrives in DOCUMENT order. Errors therefore land in
        // lazily-allocated slots (the failure path may allocate — ADR-0005) and the
        // highest-priority one is raised after the object closes. The unknown-field check
        // runs after that, exactly like the tree path's keys scan.
        Object[] errors = null;
        String unknownKey = null;
        Token t = p.nextToken();
        if (t != Token.START_OBJECT) {
            // a non-object document: the tree path reads every component as missing
            // (get(name) is null on a scalar node) — the first primitive component throws
            throwFirstMissingPrimitive(path);
        }
        while ((t = p.nextToken()) != Token.END_OBJECT) {
            // syntax errors (unterminated containers, junk) surface from nextToken unchanged
            // t == FIELD_NAME — match by name against the component table, zero allocation
            int comp = -1;
            for (int i = 0; i < names.length; i++) {
                if (p.fieldNameEquals(names[i])) {
                    comp = i;
                    break;
                }
            }
            if (comp < 0) {
                // unknown field: the name materializes only when strict mode demands it,
                // and only the FIRST unknown is recorded (the tree scan reports the first)
                if (config.failOnUnknown()) {
                    if (unknownKey == null) {
                        unknownKey = p.textValue();
                    }
                }
                skipValue(p);
                continue;
            }
            path.pushName(names[comp]);
            try {
                args[comp] = readScalar(p, comp, path);
            } catch (JsonBindException e) {
                // the error is recorded in the component's slot; a container value has
                // already been skipped by the throw site, scalar tokens were consumed by
                // readScalar's nextToken — the field loop stays aligned either way
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
                        + kindSimpleName(kinds[i]), path.resolve() + "." + names[i]);
            }
        }
        if (unknownKey != null) {
            throw new JsonBindException("unknown field '" + unknownKey + "'",
                    path.resolve() + "." + unknownKey);
        }
        // drain the tail exactly like the tree loader: parser syntax errors surface unchanged
        while ((t = p.nextToken()) != Token.EOF) {
            // trailing content
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

    /** {@code comp} indexes the component table (primitives[]); the kind drives the read. */
    private Object readScalar(JoltParser p, int comp, BindPath path) {
        int kind = kinds[comp];
        Token t = p.nextToken();
        if (t == Token.VALUE_NULL) {
            if (primitives[comp]) {
                throw new JsonBindException("JSON null for primitive component "
                        + kindSimpleName(kind), path.resolve());
            }
            return null;
        }
        switch (kind) {
            case K_STRING -> {
                if (t != Token.VALUE_STRING) {
                    throw tagError(p, t, "a string", path);
                }
                return p.textValue();
            }
            case K_BOOLEAN -> {
                if (t == Token.VALUE_TRUE) {
                    return Boolean.TRUE;
                }
                if (t == Token.VALUE_FALSE) {
                    return Boolean.FALSE;
                }
                throw tagError(p, t, "a boolean", path);
            }
            case K_INT -> {
                long v = readLong(p, t, path);
                if (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) {
                    throw new JsonBindException("number does not fit in int", path.resolve());
                }
                return Integer.valueOf((int) v);
            }
            case K_LONG -> {
                return Long.valueOf(readLong(p, t, path));
            }
            case K_BYTE -> {
                long v = readLong(p, t, path);
                if (v > Byte.MAX_VALUE || v < Byte.MIN_VALUE) {
                    throw new JsonBindException("number does not fit in byte", path.resolve());
                }
                return Byte.valueOf((byte) v);
            }
            case K_SHORT -> {
                long v = readLong(p, t, path);
                if (v > Short.MAX_VALUE || v < Short.MIN_VALUE) {
                    throw new JsonBindException("number does not fit in short", path.resolve());
                }
                return Short.valueOf((short) v);
            }
            case K_FLOAT -> {
                if (t != Token.VALUE_NUMBER_INT && t != Token.VALUE_NUMBER_FLOAT) {
                    throw tagError(p, t, "a number", path);
                }
                return Float.valueOf(p.bigDecimalValue().floatValue());
            }
            case K_DOUBLE -> {
                if (t != Token.VALUE_NUMBER_INT && t != Token.VALUE_NUMBER_FLOAT) {
                    throw tagError(p, t, "a number", path);
                }
                return Double.valueOf(p.bigDecimalValue().doubleValue());
            }
            default -> throw new IllegalStateException("unreachable kind " + kind);
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
                        + kindSimpleName(kinds[i]), path.resolve() + "." + names[i]);
            }
        }
        throw new UnsupportedOperationException(
                "all-reference records bind from non-object documents with the M6 spectrum tickets");
    }

    private static String kindSimpleName(int kind) {
        return switch (kind) {
            case K_STRING -> "String";
            case K_BOOLEAN -> "boolean";
            case K_BYTE -> "byte";
            case K_SHORT -> "short";
            case K_INT -> "int";
            case K_LONG -> "long";
            case K_FLOAT -> "float";
            case K_DOUBLE -> "double";
            default -> throw new IllegalStateException("unreachable kind " + kind);
        };
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
