package wueffi.taskmanager.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

public final class TextureUploadProfiler {

    public record UploadEvent(long timestampMs, String modId, long bytes, String texturePath, boolean atlasReload) {}

    public record Snapshot(
            Map<String, Long> bytesByMod,
            Map<String, Long> countsByMod,
            long totalBytes,
            List<UploadEvent> recentUploads
    ) {}

    private static final int MAX_RECENT_UPLOADS = 24;
    private static final TextureUploadProfiler INSTANCE = new TextureUploadProfiler();

    public static TextureUploadProfiler getInstance() {
        return INSTANCE;
    }

    private final Map<String, LongAdder> uploadBytesByMod = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> uploadCountByMod = new ConcurrentHashMap<>();
    private final Deque<UploadEvent> recentUploads = new ConcurrentLinkedDeque<>();
    private volatile boolean atlasReloadActive;

    private TextureUploadProfiler() {
    }

    public void markAtlasReloadStart() {
        atlasReloadActive = true;
    }

    public void markAtlasReloadEnd() {
        atlasReloadActive = false;
    }

    public void recordUpload(String modId, long bytes, String texturePath) {
        String normalizedModId = normalizeModId(modId);
        long safeBytes = Math.max(0L, bytes);
        String normalizedTexturePath = texturePath == null ? "" : texturePath;
        uploadBytesByMod.computeIfAbsent(normalizedModId, ignored -> new LongAdder()).add(safeBytes);
        uploadCountByMod.computeIfAbsent(normalizedModId, ignored -> new LongAdder()).increment();
        boolean atlasReload = atlasReloadActive || normalizedTexturePath.toLowerCase(java.util.Locale.ROOT).contains("atlas");
        recentUploads.addFirst(new UploadEvent(System.currentTimeMillis(), normalizedModId, safeBytes, normalizedTexturePath, atlasReload));
        while (recentUploads.size() > MAX_RECENT_UPLOADS) {
            recentUploads.removeLast();
        }
    }

    public Snapshot getSnapshot() {
        Map<String, Long> bytesByMod = toSortedLongMap(uploadBytesByMod);
        Map<String, Long> countsByMod = toSortedLongMap(uploadCountByMod);
        long totalBytes = bytesByMod.values().stream().mapToLong(Long::longValue).sum();
        return new Snapshot(bytesByMod, countsByMod, totalBytes, List.copyOf(recentUploads));
    }

    public void reset() {
        uploadBytesByMod.clear();
        uploadCountByMod.clear();
        recentUploads.clear();
        atlasReloadActive = false;
    }

    private static String normalizeModId(String modId) {
        if (modId == null || modId.isBlank()) {
            return "minecraft";
        }
        return modId;
    }

    private static Map<String, Long> toSortedLongMap(Map<String, LongAdder> source) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        source.forEach((key, value) -> entries.add(Map.entry(key, value.sum())));
        entries.sort(Comparator.comparingLong((Map.Entry<String, Long> entry) -> entry.getValue()).reversed());
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : entries) {
            ordered.put(entry.getKey(), entry.getValue());
        }
        return ordered;
    }
}
