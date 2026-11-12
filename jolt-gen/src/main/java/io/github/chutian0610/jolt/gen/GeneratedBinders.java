package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.JoltWriter;
import io.github.chutian0610.jolt.bind.Bind;
import io.github.chutian0610.jolt.bind.BindConfig;
import io.github.chutian0610.jolt.bind.BindingShape;
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
 * to the type's baked {@link GenPlan}. Non-public targets — and public targets the capability
 * predicate refuses (non-public reference component types, #53) — fall back to the reflective
 * binder from {@code jolt-bind}; the fallback is visible via the returned implementation's
 * class, documented here.
 */
public final class GeneratedBinders {

    private static final Map<Class<?>, TypedBinder<?>> CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, TypedBinder<?>> PLAN_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, TypedBinder<?>> REF_CACHE = new ConcurrentHashMap<>();
    private static final String GEN_PACKAGE = "io.github.chutian0610.jolt.gen";

    private GeneratedBinders() {}

    /** The generated binder for {@code type}, cached per class (JVM-lifetime, strong refs). */
    public static <T> TypedBinder<T> forType(Class<T> type) {
        return BinderCaches.getOrCreate(CACHE, type, t -> {
            TypedBinder<T> binder = create(t, true, false);
            io.github.chutian0610.jolt.bind.Bind.registerGenerated(t, binder);
            return binder;
        });
    }

    /**
     * The M4-era plan-executing binder for {@code type} — a BENCHMARK/REFERENCE arm, not a
     * supported path: it generates a {@code GenBinderBase} subclass with no straight-line
     * overrides (the pre-M5 generated binder), cached separately, never registered with the
     * {@code Bind} facade. Its nested routing resolves through the facade registry like any
     * other binder, so nested children bind through the (specialized) classes the facade
     * uses — the arm isolates the plan-executor layer, not the full M4 stack.
     */
    public static <T> TypedBinder<T> planCachedForType(Class<T> type) {
        return BinderCaches.getOrCreate(PLAN_CACHE, type, t -> create(t, false, false));
    }

    /**
     * The M8-OFF REFERENCE arm for {@code type} — a BENCHMARK/REFERENCE arm, not a supported
     * path: it generates the M7-form specialist (the String-name chain through the frozen
     * {@code fieldNameEquals(String)} overload + the per-value door calls) under the
     * {@code Gen$Ref$} namespace, cached separately, never registered with the {@code Bind}
     * facade. This is the gate's comparator arm (spec #54's PIN): the in-run self-referential
     * bar compares the M8 form against THIS form in the same build. Its nested children
     * resolve within the reference namespace too — an M8-off parent never picks up an M8-form
     * child. Public, capability-passing targets only (a refused type throws rather than
     * silently degrading the arm — a benchmark must not measure a fallback unawares).
     */
    public static <T> TypedBinder<T> referenceForType(Class<T> type) {
        return BinderCaches.getOrCreate(REF_CACHE, type, t -> create(t, true, true));
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

    /** Distinct class names per target: full-name SHA-256 prefix — the legacy
     * {@code hashCode()} hex collided (two classes of one benchmark fixture shared the same
     * eight hex digits and silently loaded as one class). The plan-cached reference arm and
     * the M8-off reference arm get their own namespaces so multiple classes of one type
     * coexist. */
    private static String generatedClassName(Class<?> type, boolean specialized,
                                             boolean referenceForm) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(type.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            String grade = referenceForm ? "Ref$" : specialized ? "" : "Plan$";
            return GEN_PACKAGE + ".Gen$" + grade + hex;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static <T> TypedBinder<T> create(Class<T> type, boolean specialized,
                                             boolean referenceForm) {
        if (!Modifier.isPublic(type.getModifiers())) {
            if (referenceForm) {
                // a benchmark arm must never measure a fallback unawares — fail loudly
                throw new IllegalStateException(
                        "the M8-off reference arm binds public targets only: " + type.getName());
            }
            // Fallback (documented): non-public targets keep the reflective binder.
            return io.github.chutian0610.jolt.bind.Bind.reflectiveBinder(type);
        }
        GenPlan<T> plan = new GenPlan<>(type);
        // #53 — the capability predicate, consulted once per type BEFORE any emission, so
        // BOTH grades obey one declaration-level rule. The emitted bytecode names every
        // reference component type from the generated class's runtime package (the child
        // LDCs in jolt$initChildren, the enum LDC+CHECKCAST in the scalar reads), so a
        // non-public component would throw IllegalAccessError where the reflective arm
        // binds fine. A refused type keeps the reflective binder ENTIRE — never a
        // half-generated class. The plan-cached reference arm emits nothing: exempt.
        if (specialized && !capable(plan)) {
            if (referenceForm) {
                // a benchmark arm must never measure a fallback unawares — fail loudly
                throw new IllegalStateException(
                        "the M8-off reference arm cannot bind a capability-refused type: "
                                + type.getName());
            }
            // Fallback (documented): capability-refused targets keep the reflective binder.
            return io.github.chutian0610.jolt.bind.Bind.reflectiveBinder(type);
        }
        String generatedName = generatedClassName(type, specialized, referenceForm);
        net.bytebuddy.dynamic.DynamicType.Builder<?> builder = new ByteBuddy()
                .subclass(GenBinderBase.class)
                .name(generatedName);
        // M5 specialization: straight-line parse/write overrides replace plan execution
        if (specialized) {
            if (type.isRecord()) {
                builder = RecordSpecializer.apply(builder, generatedName, plan, referenceForm);
            } else {
                builder = PojoSpecializer.apply(builder, generatedName, plan, referenceForm);
            }
        }
        Class<?> rawGenerated = builder
                .make()
                .load(GeneratedBinders.class.getClassLoader())
                .getLoaded();
        GenBinderBase binder;
        try {
            binder = (GenBinderBase) rawGenerated.getDeclaredConstructor().newInstance();
            binder.assignPlan(plan);
            if (specialized) {
                if (type.isRecord()) {
                    RecordSpecializer.bakeFields(binder, plan, referenceForm);
                } else {
                    PojoSpecializer.bakeFields(binder, plan, referenceForm);
                }
            }
            // register BEFORE cascading: self-referential records terminate via the cache.
            // The facade cache/registry belong to the specialized path only — the plan-cached
            // and M8-off reference arms live in their own maps and never feed the nested
            // routing (the reference arm's children resolve within its own namespace, so it
            // registers in ITS cache before its jolt$initChildren runs — same termination rule).
            if (specialized && !referenceForm) {
                CACHE.put(type, binder);
                io.github.chutian0610.jolt.bind.Bind.registerGenerated(type, (TypedBinder<T>) binder);
            } else if (referenceForm) {
                REF_CACHE.put(type, binder);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("generated binder could not be instantiated", e);
        }
        if (referenceForm) {
            // no cascade: the reference namespace grows lazily through its emitted
            // jolt$initChildren (referenceForType), the door needs no generated children
            RecordSpecializer.initGeneratedBinder(binder);
            return (TypedBinder<T>) binder;
        }
        cascade(plan);
        if (specialized) {
            // after cascade: child-binder fields resolve via the cache
            RecordSpecializer.initGeneratedBinder(binder);
        }
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

    /**
     * The capability predicate, one rule for both grades (#53): a public record whose
     * component types all pass the shared emittable check, or a public POJO whose public
     * setter parameter types do. Consulted once per type before any specialization, so the
     * M5 tree-path parse/write and the M7 direct read never diverge on which types get
     * generated bytecode — a refused type keeps the reflective binder entire.
     */
    private static boolean capable(GenPlan<?> plan) {
        return plan.type.isRecord()
                ? RecordSpecializer.isDirectCapable(plan)
                : PojoSpecializer.componentsEmittable(plan.type);
    }

    static boolean isBindable(Class<?> raw) {
        // enums are leaf scalars bound by name — no binder generated for them; arrays and
        // collections cascade to their element instead (the shape classification covers it).
        // The java.* exclusion is capability-level, not shape-level: JDK classes bind as
        // POJOs on every read path, they just never earn generated bytecode.
        return raw != null && !raw.getName().startsWith("java.")
                && switch (BindingShape.of(raw)) {
                    case RECORD, POJO -> true;
                    default -> false;
                };
    }

    @SuppressWarnings("unchecked")
    private static <T> TypedBinder<T> cast(Object binder) {
        return (TypedBinder<T>) binder;
    }
}
