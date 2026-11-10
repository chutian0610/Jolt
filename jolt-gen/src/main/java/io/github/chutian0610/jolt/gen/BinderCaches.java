package io.github.chutian0610.jolt.gen;

import io.github.chutian0610.jolt.bind.TypedBinder;

import java.util.Map;
import java.util.function.Function;

/**
 * The binder caches' shared double-checked arm: three namespaces (main, plan-cached,
 * reference), one memoization shell. The callers keep their own maps, registration side
 * effects, and namespace flags — this only owns the get-or-create discipline.
 */
final class BinderCaches {

    private BinderCaches() {}

    private static <T> TypedBinder<T> cast(Object binder) {
        return (TypedBinder<T>) binder;
    }

    /** The cached binder for {@code type}, creating and storing it once under the lock. */
    static <T> TypedBinder<T> getOrCreate(Map<Class<?>, TypedBinder<?>> cache, Class<T> type,
                                          Function<Class<T>, TypedBinder<T>> create) {
        TypedBinder<T> cached = cast(cache.get(type));
        if (cached != null) {
            return cached;
        }
        synchronized (cache) {
            cached = cast(cache.get(type));
            if (cached != null) {
                return cached;
            }
            cached = create.apply(type);
            cache.put(type, cached);
            return cached;
        }
    }
}
