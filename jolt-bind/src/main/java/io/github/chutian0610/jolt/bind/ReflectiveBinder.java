package io.github.chutian0610.jolt.bind;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.tree.Node;
import io.github.chutian0610.jolt.tree.TypeTag;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The reflective adapter: the default {@link TypedBinder}, with per-class metadata (canonical
 * constructors, record components, setter/accessor tables) built once and cached — the parse
 * and write loops never re-derive reflection per call.
 *
 * <p>Instances are obtained through {@code Bind.binderFor} (cached per class for the JVM's
 * life). Parse state lives on the caller's stack — binders carry no mutable per-document state,
 * so a binder may be shared across threads that each run their own parse.
 */
final class ReflectiveBinder<T> implements TypedBinder<T> {

    private static final Map<Class<?>, ReflectiveBinder<?>> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static <T> ReflectiveBinder<T> forClass(Class<T> type) {
        return (ReflectiveBinder<T>) CACHE.computeIfAbsent(type, ReflectiveBinder::new);
    }

    private final Class<T> type;
    private final java.lang.reflect.Constructor<T> constructor;
    private final RecordComponent[] components;        // records only; null for POJOs
    private final java.lang.reflect.Type[] componentTypes;
    private final List<java.lang.reflect.Method> setters;   // POJOs only; null for records
    private final List<java.lang.reflect.Method> accessors; // POJO write side; null for records

    private ReflectiveBinder(Class<T> type) {
        this.type = type;
        if (type.isRecord()) {
            this.constructor = canonicalConstructor(type);
            this.constructor.setAccessible(true);
            this.components = type.getRecordComponents();
            this.componentTypes = componentTypes(components);
            this.setters = null;
            this.accessors = null;
            return;
        }
        this.constructor = genericNoArgConstructor(type);
        if (this.constructor != null) {
            this.constructor.setAccessible(true);
        }
        if (constructor == null) {
            throw new JsonBindException(type.getName()
                    + " has no public no-arg constructor and is not a record", "$");
        }
        this.components = null;
        this.componentTypes = null;
        this.setters = setterTable(type);
        this.accessors = accessorTable(type);
        for (Method setter : setters) {
            setter.setAccessible(true); // package-private declaring classes (T31 fallback)
        }
        for (Method accessor : accessors) {
            accessor.setAccessible(true);
        }
        if (components != null) {
            for (RecordComponent component : components) {
                component.getAccessor().setAccessible(true);
            }
        }
    }

    @Override
    public T parse(Node node, String path, BindConfig config) {
        if (components != null) {
            return recordParse(node, path, config);
        }
        return pojoParse(node, path, config);
    }

    @SuppressWarnings("unchecked")
    private T recordParse(Node node, String path, BindConfig config) {
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            String name = components[i].getName();
            args[i] = Bind.toValue(node.get(name), componentTypes[i], path + "." + name, config);
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
            return (T) constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new JsonBindException("record constructor rejected the bound values: "
                    + e.getCause(), path);
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot construct " + type.getName() + ": " + e, path);
        }
    }

    @SuppressWarnings("unchecked")
    private T pojoParse(Node node, String path, BindConfig config) {
        Object instance;
        try {
            instance = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot instantiate " + type.getName(), path);
        }
        for (Method setter : setters) {
            String key = propertyName(setter.getName(), 3);
            Node value = node.get(key);
            if (value == null) {
                continue; // unknown fields are ignored; absent properties stay default
            }
            Object bound = Bind.toValue(value, setter.getGenericParameterTypes()[0],
                    path + "." + key, config);
            try {
                setter.invoke(instance, bound);
            } catch (InvocationTargetException e) {
                throw new JsonBindException("setter rejected the bound value: " + e.getCause(),
                        path + "." + key);
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot invoke setter for " + key, path + "." + key);
            }
        }
        if (config.failOnUnknown()) {
            for (String key : node.keys()) {
                if (setterFor(type, key) == null) {
                    throw new JsonBindException("unknown field '" + key + "'", path + "." + key);
                }
            }
        }
        return (T) instance;
    }

    @Override
    public void write(T value, JoltWriter writer) {
        // Full-document contract: the binder owns the enclosing object braces.
        writer.startObject();
        writeFields(value, writer, "$");
        writer.endObject();
    }

    /** Value-position variant: fields only, no enclosing braces (nested records/POJOs). */
    void writeFields(T value, JoltWriter writer, String path) {
        writeFieldsInternal(value, writer, path);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void writeFieldsVia(Object value, Class<?> raw, JoltWriter writer, String path) {
        ((ReflectiveBinder) forClass(raw)).writeFields(value, writer, path);
    }

    private void writeFieldsInternal(T value, JoltWriter writer, String path) {
        if (components != null) {
            for (RecordComponent component : components) {
                try {
                    Object fieldValue = component.getAccessor().invoke(value);
                    writer.fieldName(component.getName());
                    Bind.writeTo(fieldValue, writer, path + "." + component.getName());
                } catch (ReflectiveOperationException e) {
                    throw new JsonBindException("cannot read component " + component.getName(), path);
                }
            }
            return;
        }
        for (Method accessor : accessors) {
            String property = propertyName(accessor.getName(), accessor.getName().startsWith("is") ? 2 : 3);
            try {
                Object fieldValue = accessor.invoke(value);
                writer.fieldName(property);
                Bind.writeTo(fieldValue, writer, path + "." + property);
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot read property " + property, path);
            }
        }
    }

    // --- metadata construction ---

    private static <T> java.lang.reflect.Constructor<T> canonicalConstructor(Class<T> type) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] argTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            argTypes[i] = components[i].getType();
        }
        try {
            return type.getDeclaredConstructor(argTypes);
        } catch (NoSuchMethodException e) {
            throw new JsonBindException("cannot construct " + type.getName() + ": " + e, "$");
        }
    }

    private static java.lang.reflect.Type[] componentTypes(RecordComponent[] components) {
        java.lang.reflect.Type[] types = new java.lang.reflect.Type[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getGenericType();
        }
        return types;
    }

    private static <T> java.lang.reflect.Constructor<T> genericNoArgConstructor(Class<T> type) {
        try {
            return type.getConstructor();
        } catch (NoSuchMethodException none) {
            return null;
        }
    }

    /** Setters sorted by name: deterministic iteration for fail-on-unknown diagnostics. */
    private static List<Method> setterTable(Class<?> type) {
        List<Method> out = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getName().startsWith("set")
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                out.add(method);
            }
        }
        out.sort(Comparator.comparing(Method::getName));
        return out;
    }

    /** Getters (getX and isX) sorted by name: deterministic POJO property order for writes. */
    private static List<Method> accessorTable(Class<?> type) {
        List<Method> out = new ArrayList<>();
        for (Method method : type.getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getDeclaringClass() == Object.class) {
                continue;
            }
            boolean isGet = name.startsWith("get") && name.length() > 3;
            boolean isBoolIs = name.startsWith("is") && name.length() > 2
                    && method.getReturnType() == boolean.class;
            if (isGet || isBoolIs) {
                out.add(method);
            }
        }
        out.sort(Comparator.comparing(Method::getName));
        return out;
    }

    private static String propertyName(String accessorName, int prefixLength) {
        return Character.toLowerCase(accessorName.charAt(prefixLength))
                + accessorName.substring(prefixLength + 1);
    }

    /** Fallback lookup used by fail-on-unknown diagnostics on the POJO side. */
    private static Method setterFor(Class<?> type, String jsonKey) {
        String setterName = "set" + Character.toUpperCase(jsonKey.charAt(0)) + jsonKey.substring(1);
        for (Method method : type.getMethods()) {
            if (method.getName().equals(setterName)
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }
}
