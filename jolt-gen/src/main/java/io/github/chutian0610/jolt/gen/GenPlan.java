package io.github.chutian0610.jolt.gen;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

/**
 * The per-type execution plan for a generated binder: everything the generated class needs to
 * parse and write one target type without touching reflection at plan-execution time — the
 * canonical constructor, component names/types, and (for the write direction) the component
 * accessors. Built once per target when the binder is generated.
 */
public final class GenPlan<T> {

    final Class<T> type;
    final Constructor<T> constructor;
    final boolean isRecord;
    final String[] names;
    final java.util.Set<String> nameSet;
    final Type[] genericTypes;
    final Method[] accessors; // write side: component accessors (records); getters (POJOs)
    final List<Method> setters;    // POJO parse side: setters sorted by name

    @SuppressWarnings("unchecked")
    GenPlan(Class<T> type) {
        this.type = type;
        if (type.isRecord()) {
            this.isRecord = true;
            RecordComponent[] components = type.getRecordComponents();
            this.names = new String[components.length];
            this.genericTypes = new Type[components.length];
            this.accessors = new Method[components.length];
            Class<?>[] argTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                names[i] = components[i].getName();
                genericTypes[i] = components[i].getGenericType();
                argTypes[i] = components[i].getType();
                accessors[i] = components[i].getAccessor();
            }
            try {
                this.constructor = type.getDeclaredConstructor(argTypes);
                this.constructor.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("record without canonical constructor: " + type.getName(), e);
            }
            this.nameSet = java.util.Set.of(names);
            this.setters = List.of();
            return;
        }
        this.isRecord = false;
        this.accessors = getters(type);
        this.names = setterNames(type);
        this.nameSet = java.util.Set.of(this.names);
        this.setters = setterMethods(type);
        this.genericTypes = new Type[0];
        try {
            this.constructor = type.getConstructor();
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type.getName()
                    + " has no public no-arg constructor and is not a record", e);
        }
    }

    /** Setters sorted by name — deterministic fail-on-unknown diagnostics and writes. */
    private static String[] setterNames(Class<?> type) {
        List<String> out = new java.util.ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getName().startsWith("set")
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                String key = method.getName().substring(3);
                out.add(Character.toLowerCase(key.charAt(0)) + key.substring(1));
            }
        }
        return out.toArray(new String[0]);
    }

    /** Getters (getX and isX) sorted by name — deterministic POJO property order. */
    private static Method[] getters(Class<?> type) {
        List<Method> out = new java.util.ArrayList<>();
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
        out.sort(java.util.Comparator.comparing(Method::getName));
        return out.toArray(new Method[0]);
    }

    /** Setters sorted by name: the POJO parse loop's metadata. */
    private static List<Method> setterMethods(Class<?> type) {
        List<Method> out = new java.util.ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getName().startsWith("set")
                    && method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                out.add(method);
            }
        }
        out.sort(java.util.Comparator.comparing(Method::getName));
        return out;
    }
}
