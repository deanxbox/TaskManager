package wueffi.taskmanager.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class RenderPhaseProfiler {

    private static final RenderPhaseProfiler INSTANCE = new RenderPhaseProfiler();
    public static RenderPhaseProfiler getInstance() { return INSTANCE; }

    private static class Counter {
        final LongAdder cpuNanos = new LongAdder();
        final LongAdder cpuCalls = new LongAdder();
        final LongAdder gpuNanos = new LongAdder();
        final LongAdder gpuCalls = new LongAdder();
    }

    public record PhaseSnapshot(long cpuNanos, long cpuCalls, long gpuNanos, long gpuCalls) {}

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> cpuStart = new ConcurrentHashMap<>();

    public void beginCpuPhase(String phase) {
        cpuStart.put(phase, System.nanoTime());
    }

    public void endCpuPhase(String phase) {
        Long start = cpuStart.remove(phase);
        if (start == null) return;

        long duration = System.nanoTime() - start;
        Counter counter = counters.computeIfAbsent(phase, ignored -> new Counter());
        counter.cpuNanos.add(duration);
        counter.cpuCalls.increment();
    }

    public void recordGpuResult(String phase, long nanoseconds) {
        Counter counter = counters.computeIfAbsent(phase, ignored -> new Counter());
        counter.gpuNanos.add(nanoseconds);
        counter.gpuCalls.increment();
    }

    public Map<String, PhaseSnapshot> getSnapshot() {
        Map<String, PhaseSnapshot> result = new LinkedHashMap<>();
        counters.forEach((phase, counter) -> result.put(phase, new PhaseSnapshot(
                counter.cpuNanos.sum(),
                counter.cpuCalls.sum(),
                counter.gpuNanos.sum(),
                counter.gpuCalls.sum()
        )));
        return result;
    }

    public Map<String, PhaseSnapshot> drainSnapshot() {
        Map<String, PhaseSnapshot> result = getSnapshot();
        reset();
        return result;
    }

    public Map<String, Long> getCpuNanos() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.cpuNanos()));
        return result;
    }

    public Map<String, Long> getCpuCalls() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.cpuCalls()));
        return result;
    }

    public Map<String, Long> getGpuNanos() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.gpuNanos()));
        return result;
    }

    public Map<String, Long> getGpuCalls() {
        Map<String, Long> result = new LinkedHashMap<>();
        getSnapshot().forEach((phase, snapshot) -> result.put(phase, snapshot.gpuCalls()));
        return result;
    }

    public void reset() {
        counters.clear();
        cpuStart.clear();
    }
}
