package com.redondoguibi.direct.optimization;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * FerriteCore multipartDeduplication: vanilla bakes one multipart model per block state, each with
 * its own copy of an identical selector list. While a model bake is active, models built from an
 * equal selector list (same cached predicates, same cached part models) are interned. The table
 * exists only during the bake, so no model is retained across resource reloads.
 */
public final class MultipartDeduplication {
    private static final Map<List<?>, Object> MODELS = new ConcurrentHashMap<>();
    private static volatile boolean active;
    private static final AtomicLong built = new AtomicLong();
    private static final AtomicLong shared = new AtomicLong();

    private MultipartDeduplication() {}

    public static void begin() {
        MODELS.clear();
        built.set(0);
        shared.set(0);
        active = true;
    }

    /** Ends the bake and returns {built, shared} model counts. */
    public static long[] end() {
        active = false;
        MODELS.clear();
        return new long[] {built.get(), shared.get()};
    }

    @SuppressWarnings("unchecked")
    public static <M> M intern(List<?> selectors, Supplier<M> create) {
        if (!active || selectors.isEmpty()) return create.get();
        built.incrementAndGet();
        Object existing = MODELS.get(selectors);
        if (existing != null) {
            shared.incrementAndGet();
            return (M) existing;
        }
        M model = create.get();
        Object raced = MODELS.putIfAbsent(selectors, model);
        if (raced != null) {
            shared.incrementAndGet();
            return (M) raced;
        }
        return model;
    }
}
