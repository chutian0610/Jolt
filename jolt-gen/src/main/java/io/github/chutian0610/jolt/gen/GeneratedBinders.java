package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.GeneratedFields;
import io.github.chutian0610.jolt.bind.TypedBinder;
import net.bytebuddy.ByteBuddy;
import io.github.chutian0610.jolt.tree.Node;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.FieldAccessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The generated-binding entry point: {@code forType} returns a cached {@link TypedBinder}
 * backed by a runtime-generated public class (one per target type) whose parse/write delegate
 * to the type's baked {@link GenPlan}. Non-public or otherwise inaccessible
 * targets fall back to the reflective binder from {@code jolt-bind} — the fallback is visible
 * via the returned implementation's class, documented here.
 */
public final class GeneratedBinders {

    private static final Map<Class<?>, TypedBinder<?>> CACHE = new ConcurrentHashMap<>();
    private static final String GEN_PACKAGE = "io.github.chutian0610.jolt.gen";

    private GeneratedBinders() {}

    /** The generated binder for {@code type}, cached per class (JVM-lifetime, strong refs). */
    public static <T> TypedBinder<T> forType(Class<T> type) {
        TypedBinder<T> cached = cast(CACHE.get(type));
        if (cached != null) {
            return cached;
        }
        synchronized (CACHE) {
            cached = cast(CACHE.get(type));
            if (cached != null) {
                return cached;
            }
            cached = create(type);
            CACHE.put(type, cached);
            io.github.chutian0610.jolt.bind.Bind.registerGenerated(type, cached);
            return cached;
        }
    }

    /** The generated binder for {@code type} if one exists, else null (nested routing). */
    public static TypedBinder<?> lookup(Class<?> type) {
        return CACHE.get(type);
    }

    /** Value-position fields-only write through the generated binder, if present. */
    public static boolean tryWriteFields(Class<?> raw, Object value, JoltWriter writer, String path) {
        TypedBinder<?> binder = CACHE.get(raw);
        if (binder instanceof GeneratedFields generatedFields) {
            generatedFields.writeFields(value, writer, path);
            return true;
        }
        return false;
    }

    private static <T> TypedBinder<T> create(Class<T> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            // Fallback (documented): non-public targets keep the reflective binder.
            return io.github.chutian0610.jolt.bind.Bind.reflectiveBinder(type);
        }
        GenPlan<T> plan = new GenPlan<>(type);
        Class<?> rawGenerated = new ByteBuddy()
                .subclass(GenBinderBase.class)
                .name(GEN_PACKAGE + ".Gen$" + Integer.toHexString(type.getName().hashCode()))
                .make()
                .load(GeneratedBinders.class.getClassLoader())
                .getLoaded();
        GenBinderBase binder;
        try {
            binder = (GenBinderBase) rawGenerated.getDeclaredConstructor().newInstance();
            binder.assignPlan(plan);
            // register BEFORE cascading: self-referential records terminate via the cache
            CACHE.put(type, binder);
            io.github.chutian0610.jolt.bind.Bind.registerGenerated(type, (TypedBinder<T>) binder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("generated binder could not be instantiated", e);
        }
        cascade(plan);
        return (TypedBinder<T>) binder;
    }

    /** Generates binders for nested record/POJO component types (recursion-safe via cache). */
    private static void cascade(GenPlan<?> plan) {
        for (java.lang.reflect.Type genericType : plan.genericTypes) {
            cascadeType(genericType);
        }
        for (Method accessor : plan.accessors) {
            cascadeType(accessor.getGenericReturnType());
        }
    }

    private static void cascadeType(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) {
            if (isBindable(c)) {
                forType(c);
            }
            return;
        }
        if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            // List<Item> / Map<String, Item>: the ITEM type is the bindable one
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                cascadeType(argument);
            }
        }
        // GenericArrayType (X[][]) falls through: component bindability resolves on the inner level
    }

    private static boolean isBindable(Class<?> raw) {
        // enums are leaf scalars bound by name — no binder generated for them
        return raw != null && !raw.isArray() && raw != Object.class && !raw.isEnum()
                && !raw.getName().startsWith("java.")
                && (raw.isRecord()
                    || (!raw.isInterface() && !Modifier.isAbstract(raw.getModifiers())));
    }

    @SuppressWarnings("unchecked")
    private static <T> TypedBinder<T> cast(Object binder) {
        return (TypedBinder<T>) binder;
    }
}
