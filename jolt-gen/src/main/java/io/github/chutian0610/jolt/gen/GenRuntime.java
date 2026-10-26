package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.bind.JsonBindException;
import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.tree.Node;
import net.bytebuddy.implementation.bind.annotation.FieldValue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The plan executor the generated binder classes delegate to. The generated class's
 * {@code plan} field is bound via {@code @FieldValue}; strict conversion, nested dispatch, and
 * path-carrying failures go through {@code Bind}'s shared contracts — both binders face
 * identical semantics.
 */
final class GenRuntime {

    private GenRuntime() {}

    /** Generated parse: routes record vs POJO plans. */
    static Object parse(@FieldValue(value = "plan") GenPlan<?> plan, Node node, String path, BindConfig config) {
        return plan.isRecord ? parseRecord(plan, node, path, config) : parsePojo(plan, node, path, config);
    }

    /** Generated write (full document): owns the enclosing object braces. */
    static void write(@FieldValue(value = "plan") GenPlan<?> plan, Object value, JoltWriter writer, String path) {
        writer.startObject();
        writeFields(plan, value, writer, path);
        writer.endObject();
    }

    /** Generated write (value position): fields only. */
    static void writeFields(@FieldValue(value = "plan") GenPlan<?> plan, Object value, JoltWriter writer, String path) {
        for (int i = 0; i < plan.accessors.length; i++) {
            Method accessor = plan.accessors[i];
            String name = plan.names[i]; // record accessor names ARE the component names
            Object fieldValue;
            try {
                fieldValue = accessor.invoke(value);
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot read property " + name, path);
            }
            writer.fieldName(name);
            Bind.writeTo(fieldValue, writer, path + "." + name);
        }
    }

    private static Object parseRecord(GenPlan<?> plan, Node node, String path, BindConfig config) {
        Object[] args = new Object[plan.names.length];
        String[] names = plan.names;
        java.lang.reflect.Type[] types = plan.genericTypes;
        for (int i = 0; i < names.length; i++) {
            args[i] = Bind.toValue(node.get(names[i]), types[i], path + "." + names[i], config);
        }
        if (config.failOnUnknown()) {
            for (String key : node.keys()) {
                boolean known = false;
                for (String name : names) {
                    known |= name.equals(key);
                }
                if (!known) {
                    throw new JsonBindException("unknown field '" + key + "'", path + "." + key);
                }
            }
        }
        try {
            return plan.constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new JsonBindException("record constructor rejected the bound values: "
                    + e.getCause(), path);
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot construct " + plan.type.getName() + ": " + e, path);
        }
    }

    private static Object parsePojo(GenPlan<?> plan, Node node, String path, BindConfig config) {
        Object instance;
        try {
            instance = plan.constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new JsonBindException("cannot instantiate " + plan.type.getName(), path);
        }
        for (String name : plan.names) {
            Node value = node.get(name);
            if (value == null) {
                continue;
            }
            Method setter = findSetter(plan.type, name);
            if (setter == null) {
                if (config.failOnUnknown()) {
                    throw new JsonBindException("unknown field '" + name + "'", path + "." + name);
                }
                continue;
            }
            Object bound = Bind.toValue(value, setter.getGenericParameterTypes()[0],
                    path + "." + name, config);
            try {
                setter.invoke(instance, bound);
            } catch (InvocationTargetException e) {
                throw new JsonBindException("setter rejected the bound value: " + e.getCause(),
                        path + "." + name);
            } catch (ReflectiveOperationException e) {
                throw new JsonBindException("cannot invoke setter for " + name, path + "." + name);
            }
        }
        if (config.failOnUnknown()) {
            for (String key : node.keys()) {
                if (findSetter(plan.type, key) == null && !hasGetter(plan.type, key)) {
                    throw new JsonBindException("unknown field '" + key + "'", path + "." + key);
                }
            }
        }
        return instance;
    }

    private static String propertyName(String accessorName) {
        int prefix = accessorName.startsWith("is") ? 2 : 3;
        return Character.toLowerCase(accessorName.charAt(prefix))
                + accessorName.substring(prefix + 1);
    }

    private static Method findSetter(Class<?> type, String jsonKey) {
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

    private static boolean hasGetter(Class<?> type, String jsonKey) {
        String get = "get" + Character.toUpperCase(jsonKey.charAt(0)) + jsonKey.substring(1);
        String is = "is" + Character.toUpperCase(jsonKey.charAt(0)) + jsonKey.substring(1);
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())
                    && (method.getName().equals(get) || method.getName().equals(is))) {
                return true;
            }
        }
        return false;
    }
}
