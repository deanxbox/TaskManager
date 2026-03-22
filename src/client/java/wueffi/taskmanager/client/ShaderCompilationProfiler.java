package wueffi.taskmanager.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class ShaderCompilationProfiler {

    private static final ShaderCompilationProfiler INSTANCE = new ShaderCompilationProfiler();
    private static final int MAX_RECENT_EVENTS = 32;
    private static final int MAX_TOP_LABELS = 8;

    private final Map<String, LongAdder> durationNanosByLabel = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> compileCountByLabel = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<CompileEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private final ThreadLocal<Deque<CompileContext>> contexts = ThreadLocal.withInitial(ArrayDeque::new);
    private final AtomicLong lastCompletedAtMillis = new AtomicLong(0L);

    public static ShaderCompilationProfiler getInstance() {
        return INSTANCE;
    }

    private ShaderCompilationProfiler() {
    }

    public void beginCompile(String label) {
        contexts.get().push(new CompileContext(System.nanoTime(), sanitizeLabel(label)));
    }

    public void endCompile() {
        Deque<CompileContext> stack = contexts.get();
        if (stack.isEmpty()) {
            return;
        }
        CompileContext context = stack.pop();
        long durationNs = Math.max(0L, System.nanoTime() - context.startedAtNs());
        durationNanosByLabel.computeIfAbsent(context.label(), ignored -> new LongAdder()).add(durationNs);
        compileCountByLabel.computeIfAbsent(context.label(), ignored -> new LongAdder()).increment();
        long completedAtMillis = System.currentTimeMillis();
        recentEvents.addFirst(new CompileEvent(completedAtMillis, context.label(), durationNs));
        while (recentEvents.size() > MAX_RECENT_EVENTS) {
            recentEvents.pollLast();
        }
        lastCompletedAtMillis.set(completedAtMillis);
    }

    public Snapshot getSnapshot() {
        return new Snapshot(
                topEntries(durationNanosByLabel),
                topEntries(compileCountByLabel),
                List.copyOf(recentEvents),
                getLastSampleAgeMillis()
        );
    }

    public boolean hasRecentCompilation(long withinMillis) {
        return getLastSampleAgeMillis() <= withinMillis;
    }

    public CompileEvent getLatestEvent() {
        return recentEvents.peekFirst();
    }

    public void reset() {
        durationNanosByLabel.clear();
        compileCountByLabel.clear();
        recentEvents.clear();
        lastCompletedAtMillis.set(0L);
        contexts.remove();
    }

    private long getLastSampleAgeMillis() {
        long lastCompleted = lastCompletedAtMillis.get();
        if (lastCompleted == 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, System.currentTimeMillis() - lastCompleted);
    }

    private static Map<String, Long> topEntries(Map<String, LongAdder> source) {
        LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
        source.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().sum()))
                .filter(entry -> entry.getValue() > 0L)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(MAX_TOP_LABELS)
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue()));
        return snapshot;
    }

    private static String sanitizeLabel(String label) {
        if (label == null || label.isBlank()) {
            return "unnamed-shader";
        }
        return label;
    }

    private record CompileContext(long startedAtNs, String label) {
    }

    public record CompileEvent(long completedAtEpochMillis, String label, long durationNs) {
    }

    public record Snapshot(
            Map<String, Long> durationNanosByLabel,
            Map<String, Long> compileCountByLabel,
            List<CompileEvent> recentEvents,
            long sampleAgeMillis
    ) {
    }
}
