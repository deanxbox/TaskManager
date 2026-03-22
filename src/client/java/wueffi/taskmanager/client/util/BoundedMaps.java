package wueffi.taskmanager.client.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

public final class BoundedMaps {

    private BoundedMaps() {
    }

    public static <K, V> Map<K, V> synchronizedLru(int maxEntries) {
        int initialCapacity = Math.max(16, Math.min(maxEntries, 256));
        return Collections.synchronizedMap(new LinkedHashMap<>(initialCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    public static <K, V> V getOrCompute(Map<K, V> map, K key, Function<? super K, ? extends V> resolver) {
        synchronized (map) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
            V value = resolver.apply(key);
            map.put(key, value);
            return value;
        }
    }

    public static <K, V> V get(Map<K, V> map, K key) {
        synchronized (map) {
            return map.get(key);
        }
    }

    public static <K, V> void put(Map<K, V> map, K key, V value) {
        synchronized (map) {
            map.put(key, value);
        }
    }

    public static <K, V> void putIfAbsent(Map<K, V> map, K key, V value) {
        synchronized (map) {
            map.putIfAbsent(key, value);
        }
    }
}
