package wueffi.taskmanager.client;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import wueffi.taskmanager.client.util.BoundedMaps;

public final class BoundedMapsTests {

    private BoundedMapsTests() {
    }

    public static void run() {
        synchronizedLruEvictsLeastRecentlyUsedEntry();
        getOrComputeCachesComputedValue();
    }

    private static void synchronizedLruEvictsLeastRecentlyUsedEntry() {
        Map<String, String> cache = BoundedMaps.synchronizedLru(2);
        cache.put("a", "alpha");
        cache.put("b", "beta");
        cache.get("a");
        cache.put("c", "gamma");

        if (cache.containsKey("b")) {
            throw new AssertionError("least recently used entry should be evicted first");
        }
        if (!cache.containsKey("a") || !cache.containsKey("c")) {
            throw new AssertionError("recent entries should remain present after eviction");
        }
    }

    private static void getOrComputeCachesComputedValue() {
        Map<String, String> cache = BoundedMaps.synchronizedLru(4);
        AtomicInteger invocations = new AtomicInteger();

        String first = BoundedMaps.getOrCompute(cache, "mod", key -> {
            invocations.incrementAndGet();
            return "resolved";
        });
        String second = BoundedMaps.getOrCompute(cache, "mod", key -> {
            invocations.incrementAndGet();
            return "other";
        });

        if (!"resolved".equals(first) || !"resolved".equals(second)) {
            throw new AssertionError("cached value should be returned after the first computation");
        }
        if (invocations.get() != 1) {
            throw new AssertionError("resolver should only be called once per cached key");
        }
    }
}
