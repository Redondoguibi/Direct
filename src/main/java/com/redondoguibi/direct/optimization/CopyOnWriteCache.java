package com.redondoguibi.direct.optimization;

import java.util.function.Function;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

/**
 * Identity-keyed cache read lock-free by many threads and written rarely. Each insertion publishes
 * a new immutable-after-publication map through a volatile field, so readers never observe a map
 * that is being resized. Null keys are supported (as in fastutil). Used for multipart selector
 * sets, which are now shared by every state of a block and queried by all chunk-meshing threads.
 */
public final class CopyOnWriteCache<K, V> {
    private volatile Reference2ObjectOpenHashMap<K, V> map = new Reference2ObjectOpenHashMap<>();

    public V get(K key) {
        return this.map.get(key);
    }

    /** Computes at most once per key; {@code compute} runs under this cache's lock and must not re-enter it. */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> compute) {
        V value = this.map.get(key);
        if (value != null) return value;
        synchronized (this) {
            Reference2ObjectOpenHashMap<K, V> current = this.map;
            value = current.get(key);
            if (value != null) return value;
            value = compute.apply(key);
            Reference2ObjectOpenHashMap<K, V> next = new Reference2ObjectOpenHashMap<>(current.size() + 1);
            next.putAll(current);
            next.put(key, value);
            this.map = next;
            return value;
        }
    }

    public int size() {
        return this.map.size();
    }
}
