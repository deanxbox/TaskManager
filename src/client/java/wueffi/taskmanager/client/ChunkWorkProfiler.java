package wueffi.taskmanager.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class ChunkWorkProfiler {

    private static final ChunkWorkProfiler INSTANCE = new ChunkWorkProfiler();
    private static final int MAX_ROWS = 8;

    private final Map<String, LongAdder> durationNanosByLabel = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> callsByLabel = new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<Context>> contexts = ThreadLocal.withInitial(ArrayDeque::new);
    private final AtomicLong lastUpdatedAtMillis = new AtomicLong(0L);

    public static ChunkWorkProfiler getInstance() {
        return INSTANCE;
    }

    private ChunkWorkProfiler() {
    }

    public void beginPhase(String label) {
        Deque<Context> stack = contexts.get();
        if (stack.size() > 16) {
            stack.clear();
        }
        stack.push(new Context(System.nanoTime(), sanitizeLabel(label)));
    }

    public void endPhase() {
        Deque<Context> stack = contexts.get();
        if (stack.isEmpty()) {
            return;
        }
        Context context = stack.pop();
        long durationNs = Math.max(0L, System.nanoTime() - context.startedAtNs());
        durationNanosByLabel.computeIfAbsent(context.label(), ignored -> new LongAdder()).add(durationNs);
        callsByLabel.computeIfAbsent(context.label(), ignored -> new LongAdder()).increment();
        lastUpdatedAtMillis.set(System.currentTimeMillis());
        if (stack.isEmpty()) {
            contexts.remove();
        }
    }

    public void cleanupThread() {
        contexts.remove();
    }

    public Snapshot getSnapshot() {
        return new Snapshot(topEntries(durationNanosByLabel), topEntries(callsByLabel), getLastSampleAgeMillis());
    }

    public List<String> buildTopLines() {
        Snapshot snapshot = getSnapshot();
        if (snapshot.durationNanosByLabel().isEmpty()) {
            return List.of("No chunk generation/load timings captured in the current window.");
        }
        return snapshot.durationNanosByLabel().entrySet().stream()
                .map(entry -> String.format(
                        java.util.Locale.ROOT,
                        "%s | %.2f ms | %d calls",
                        entry.getKey(),
                        entry.getValue() / 1_000_000.0,
                        snapshot.callsByLabel().getOrDefault(entry.getKey(), 0L)
                ))
                .toList();
    }

    public void reset() {
        durationNanosByLabel.clear();
        callsByLabel.clear();
        lastUpdatedAtMillis.set(0L);
        contexts.remove();
    }

    private long getLastSampleAgeMillis() {
        long updatedAt = lastUpdatedAtMillis.get();
        if (updatedAt == 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, System.currentTimeMillis() - updatedAt);
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "chunk-work";
        }
        return label;
    }

    private static Map<String, Long> topEntries(Map<String, LongAdder> source) {
        LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
        source.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().sum()))
                .filter(entry -> entry.getValue() > 0L)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(MAX_ROWS)
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue()));
        return snapshot;
    }

    private record Context(long startedAtNs, String label) {
    }

    public record Snapshot(Map<String, Long> durationNanosByLabel, Map<String, Long> callsByLabel, long sampleAgeMillis) {
    }
}
