package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class ModTimingProfiler {

    private static final ModTimingProfiler INSTANCE = new ModTimingProfiler();
    public static ModTimingProfiler getInstance() { return INSTANCE; }

    private static class Counter {
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public void record(String modId, String methodName, long nanoseconds) {
        String key = modId == null ? "unknown" : modId;
        counters.computeIfAbsent(key, ignored -> new Counter()).nanos.add(nanoseconds);
        counters.get(key).calls.increment();
    }

    public Map<String, ModTimingSnapshot> getSnapshot() {
        Map<String, ModTimingSnapshot> result = new LinkedHashMap<>();
        counters.forEach((mod, counter) -> result.put(mod, new ModTimingSnapshot(counter.nanos.sum(), counter.calls.sum())));
        return result;
    }

    public Map<String, ModTimingSnapshot> drainSnapshot() {
        Map<String, ModTimingSnapshot> result = getSnapshot();
        reset();
        return result;
    }

    public void reset() {
        counters.clear();
    }
}
