package wueffi.taskmanager.client;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class StartupTimingProfiler {

    private static final StartupTimingProfiler INSTANCE = new StartupTimingProfiler();
    public static StartupTimingProfiler getInstance() { return INSTANCE; }

    private final Map<String, Long> firstSeen = new LinkedHashMap<>();
    private final Map<String, Long> lastSeen = new LinkedHashMap<>();
    private final Map<String, Long> activeNanos = new LinkedHashMap<>();
    private final Map<String, Integer> registrationCount = new LinkedHashMap<>();
    private final Map<String, Integer> entrypointCount = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> stagesByMod = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> definitionsByMod = new LinkedHashMap<>();
    public static boolean closed = false;

    public synchronized void reset() {
        firstSeen.clear();
        lastSeen.clear();
        activeNanos.clear();
        registrationCount.clear();
        entrypointCount.clear();
        stagesByMod.clear();
        definitionsByMod.clear();
        closed = false;
    }

    public void close() {
        closed = true;
    }

    public synchronized void recordRegistration(String modId) {
        if (closed || modId == null || modId.isBlank()) return;
        long now = System.nanoTime();
        firstSeen.putIfAbsent(modId, now);
        lastSeen.put(modId, now);
        registrationCount.merge(modId, 1, Integer::sum);
    }

    public synchronized void recordEntrypoint(String modId, String stage, String definition, long startedNs, long finishedNs) {
        if (closed || modId == null || modId.isBlank()) return;

        long start = Math.min(startedNs, finishedNs);
        long finish = Math.max(startedNs, finishedNs);
        long duration = Math.max(0L, finish - start);

        firstSeen.merge(modId, start, Math::min);
        lastSeen.merge(modId, finish, Math::max);
        activeNanos.merge(modId, duration, Long::sum);
        entrypointCount.merge(modId, 1, Integer::sum);

        if (stage != null && !stage.isBlank()) {
            stagesByMod.computeIfAbsent(modId, ignored -> new LinkedHashMap<>()).merge(stage, 1, Integer::sum);
        }
        if (definition != null && !definition.isBlank()) {
            definitionsByMod.computeIfAbsent(modId, ignored -> new LinkedHashMap<>()).merge(definition, 1, Integer::sum);
        }
    }

    public synchronized boolean hasData() {
        return !firstSeen.isEmpty();
    }

    public synchronized boolean hasEntrypointData() {
        return !entrypointCount.isEmpty();
    }

    public synchronized long getGlobalFirst() {
        return firstSeen.values().stream().mapToLong(Long::longValue).min().orElse(0);
    }

    public synchronized long getGlobalLast() {
        return lastSeen.values().stream().mapToLong(Long::longValue).max().orElse(0);
    }

    public record StartupRow(
            String modId,
            long first,
            long last,
            long activeNanos,
            int entrypoints,
            int registrations,
            String stageSummary,
            String definitionSummary,
            boolean measuredEntrypoints
    ) {}

    public synchronized java.util.List<StartupRow> getSortedRows() {
        return lastSeen.keySet().stream()
                .map(modId -> new StartupRow(
                        modId,
                        firstSeen.get(modId),
                        lastSeen.get(modId),
                        activeNanos.getOrDefault(modId, Math.max(0L, lastSeen.get(modId) - firstSeen.get(modId))),
                        entrypointCount.getOrDefault(modId, 0),
                        registrationCount.getOrDefault(modId, 0),
                        summarizeStages(stagesByMod.get(modId)),
                        summarizeDefinitions(definitionsByMod.get(modId)),
                        entrypointCount.containsKey(modId)
                ))
                .sorted(Comparator.comparingLong(StartupRow::activeNanos).reversed())
                .toList();
    }

    private String summarizeStages(Map<String, Integer> stages) {
        if (stages == null || stages.isEmpty()) {
            return "listener-registration";
        }
        return stages.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String summarizeDefinitions(Map<String, Integer> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return "";
        }
        return definitions.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(2)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(" | "));
    }
}
