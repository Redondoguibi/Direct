package com.redondoguibi.direct.optimization;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MultipartSharingTest {
    @Test
    void deduplicatesEqualSelectorListsOnlyDuringABake() {
        Object predicate = new Object(), part = new Object();
        assertNotSame(MultipartDeduplication.intern(List.of(predicate, part), Object::new),
                MultipartDeduplication.intern(List.of(predicate, part), Object::new), "no sharing outside a bake");
        MultipartDeduplication.begin();
        Object first = MultipartDeduplication.intern(List.of(predicate, part), Object::new);
        assertSame(first, MultipartDeduplication.intern(new ArrayList<>(List.of(predicate, part)), Object::new));
        assertNotSame(first, MultipartDeduplication.intern(List.of(part, predicate), Object::new), "order is part of the model");
        assertNotSame(first, MultipartDeduplication.intern(List.of(predicate, new Object()), Object::new));
        assertArrayEquals(new long[] {4, 1}, MultipartDeduplication.end());
        assertNotSame(first, MultipartDeduplication.intern(List.of(predicate, part), Object::new), "table released after the bake");
    }

    @Test
    void copyOnWriteCacheComputesOncePerKeyIncludingNull() {
        var cache = new CopyOnWriteCache<Object, Object>();
        AtomicInteger computations = new AtomicInteger();
        Object value = cache.computeIfAbsent(null, key -> { computations.incrementAndGet(); return "null-state"; });
        assertEquals("null-state", value);
        assertEquals("null-state", cache.get(null));
        assertSame(value, cache.computeIfAbsent(null, key -> { computations.incrementAndGet(); return "again"; }));
        assertEquals(1, computations.get());
        assertThrows(IllegalStateException.class, () -> cache.computeIfAbsent("bad", key -> { throw new IllegalStateException(); }));
        assertNull(cache.get("bad"), "a failed computation is not cached");
    }

    /** Many chunk workers hitting one shared model: every key is computed once and readers stay consistent. */
    @Test
    void copyOnWriteCacheIsSafeUnderConcurrentReadersAndWriters() throws Exception {
        var cache = new CopyOnWriteCache<Integer, Integer>();
        Integer[] keys = new Integer[2000];
        for (int i = 0; i < keys.length; i++) keys[i] = Integer.valueOf(i + 100_000); // distinct identities
        AtomicInteger[] computed = new AtomicInteger[keys.length];
        for (int i = 0; i < keys.length; i++) computed[i] = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int seed = t;
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < 5; round++) {
                        for (int i = 0; i < keys.length; i++) {
                            int index = (i * 7 + seed * 131 + round) % keys.length;
                            Integer key = keys[index];
                            Integer value = cache.get(key);
                            if (value == null) value = cache.computeIfAbsent(key, k -> { computed[index].incrementAndGet(); return k * 2; });
                            assertEquals(key * 2, value);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(keys.length, cache.size());
        for (AtomicInteger count : computed) assertEquals(1, count.get());
    }
}
