package wueffi.taskmanager.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.entry.RegistryEntry;
import wueffi.taskmanager.client.util.ConfigManager;
import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfilerManager {

    public enum CaptureMode {
        OFF,
        OPEN_ONLY,
        PASSIVE_LIGHTWEIGHT,
        SPIKE_CAPTURE,
        MANUAL_DEEP;

        public CaptureMode next() {
            CaptureMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public record EntityCounts(int totalEntities, int livingEntities, int blockEntities) {
        static EntityCounts empty() {
            return new EntityCounts(0, 0, 0);
        }
    }

    public record ChunkCounts(int loadedChunks, int renderedChunks) {
        static ChunkCounts empty() {
            return new ChunkCounts(0, 0);
        }
    }

    public record HotChunkSnapshot(int chunkX, int chunkZ, int entityCount, int blockEntityCount, String topEntityClass, String topBlockEntityClass, double activityScore) {}

    public record EntityHotspot(String className, int count, String heuristic) {}

    public record BlockEntityHotspot(String className, int count, String heuristic) {}

    public record RuleFinding(String severity, String category, String message, String confidence) {}

    public record SpikeCapture(
            long capturedAtEpochMillis,
            double frameDurationMs,
            double stutterScore,
            EntityCounts entityCounts,
            ChunkCounts chunkCounts,
            List<String> topCpuMods,
            List<String> topRenderPhases,
            List<String> topThreads,
            String likelyBottleneck,
            List<RuleFinding> findings
    ) {}

    public record SessionPoint(
            long capturedAtEpochMillis,
            double currentFps,
            double averageFps,
            double onePercentLowFps,
            double pointOnePercentLowFps,
            double frameTimeMs,
            double frameTimeAvgMs,
            double frameTimeP95Ms,
            double frameTimeP99Ms,
            double frameTimeStdDevMs,
            double frameTimeVarianceMs,
            Map<String, Double> frameTimeBuckets,
            double frameBuildTimeMs,
            double gpuFrameTimeMs,
            double gpuFrameTimeP95Ms,
            double tickTimeMs,
            double millisecondsPerTick,
            double msptAvg,
            double msptP95,
            double msptP99,
            double clientTickMs,
            double serverTickMs,
            double stutterScore,
            double usedHeapMb,
            double allocatedHeapMb,
            long heapUsedBytes,
            long heapCommittedBytes,
            boolean isGcEvent,
            long gcPauseDurationMs,
            long gcPauseTotalMs,
            long gcPauseMaxMs,
            long gcCount,
            String gcType,
            long cpuSamples,
            long renderSamples,
            long drawCalls,
            long bufferUpdates,
            double mouseInputLatencyMs,
            String topThreadName,
            String cpuParallelismFlag,
            int chunksGenerating,
            int chunksMeshing,
            int chunksUploading,
            int lightsUpdatePending,
            long chunkMeshesRebuilt,
            long chunkMeshesUploaded,
            double playerSpeedBlocksPerSecond,
            int chunksEnteredLastSecond,
            double distanceTravelledBlocks,
            long vramUsedBytes,
            long vramReservedBytes,
            long textureUploadRate,
            EntityCounts entityCounts,
            ChunkCounts chunkCounts,
            SystemMetricsProfiler.Snapshot systemMetrics,
            NetworkPacketProfiler.Snapshot networkSnapshot,
            HotChunkSnapshot topHotChunk,
            List<EntityHotspot> entityHotspots,
            List<BlockEntityHotspot> blockEntityHotspots,
            List<String> lockSummaries,
            List<RuleFinding> ruleFindings,
            List<String> topCpuMods,
            List<String> topGpuMods,
            Map<String, Integer> cpuThreadCountsByMod,
            Map<String, Integer> gpuThreadCountsByMod,
            Map<String, Integer> memoryClassCountsByMod
    ) {}

    public record ProfilerSnapshot(
            long capturedAtEpochMillis,
            CaptureMode mode,
            boolean captureActive,
            boolean cpuReady,
            boolean gpuReady,
            long cpuSampleAgeMillis,
            long totalCpuSamples,
            long totalRenderSamples,
            Map<String, CpuSamplingProfiler.Snapshot> cpuMods,
            Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails,
            Map<String, ModTimingSnapshot> modInvokes,
            Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases,
            MemoryProfiler.Snapshot memory,
            Map<String, Long> memoryMods,
            Map<String, Long> sharedMemoryFamilies,
            Map<String, Map<String, Long>> sharedFamilyClasses,
            Map<String, Map<String, Long>> memoryClassesByMod,
            long memoryAgeMillis,
            EntityCounts entityCounts,
            ChunkCounts chunkCounts,
            SystemMetricsProfiler.Snapshot systemMetrics,
            double stutterScore,
            List<StartupTimingProfiler.StartupRow> startupRows,
            long startupFirst,
            long startupLast,
            Map<String, Long> flamegraphStacks,
            List<SpikeCapture> spikes,
            boolean sessionLogging,
            long sessionLoggingElapsedMillis,
            String lastExportStatus
    ) {
        static ProfilerSnapshot empty() {
            return new ProfilerSnapshot(
                    System.currentTimeMillis(),
                    CaptureMode.OPEN_ONLY,
                    false,
                    false,
                    false,
                    Long.MAX_VALUE,
                    0,
                    0,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    MemoryProfiler.Snapshot.empty(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Long.MAX_VALUE,
                    EntityCounts.empty(),
                    ChunkCounts.empty(),
                    SystemMetricsProfiler.Snapshot.empty(),
                    0,
                    List.of(),
                    0,
                    0,
                    Map.of(),
                    List.of(),
                    false,
                    0,
                    ""
            );
        }
    }

    private static final ProfilerManager INSTANCE = new ProfilerManager();
    public static ProfilerManager getInstance() { return INSTANCE; }

    private static final int WINDOW_SIZE = 20;
    private static final long SPIKE_THRESHOLD_NS = 50_000_000L;
    private static final int MAX_SPIKES = 8;
    private static final Gson EXPORT_GSON = new GsonBuilder().serializeSpecialFloatingPointValues().setPrettyPrinting().create();
    private static final Pattern CHUNK_DEBUG_PATTERN = Pattern.compile("C:\\s*(\\d+)/(\\d+)");

    private final Deque<Map<String, CpuSamplingProfiler.Snapshot>> cpuWindows = new ArrayDeque<>();
    private final Deque<Map<String, CpuSamplingProfiler.DetailSnapshot>> cpuDetailWindows = new ArrayDeque<>();
    private final Deque<Map<String, ModTimingSnapshot>> modWindows = new ArrayDeque<>();
    private final Deque<Map<String, RenderPhaseProfiler.PhaseSnapshot>> renderWindows = new ArrayDeque<>();
    private final Deque<SpikeCapture> spikes = new ArrayDeque<>();
    private final Deque<SessionPoint> sessionHistory = new ArrayDeque<>();
    private final Deque<List<HotChunkSnapshot>> hotChunkHistory = new ArrayDeque<>();
    private final Map<Long, Deque<Integer>> chunkActivityHistory = new LinkedHashMap<>();


    private volatile List<HotChunkSnapshot> latestHotChunks = List.of();
    private volatile List<EntityHotspot> latestEntityHotspots = List.of();
    private volatile List<BlockEntityHotspot> latestBlockEntityHotspots = List.of();
    private volatile List<String> latestLockSummaries = List.of();
    private volatile List<RuleFinding> latestRuleFindings = List.of();
    private final Deque<Map<String, Object>> stutterJumpSnapshots = new ArrayDeque<>();
    private double lastStutterScore = 0.0;
    private volatile boolean screenOpen = false;
    private volatile CaptureMode mode = CaptureMode.OPEN_ONLY;
    private volatile ProfilerSnapshot currentSnapshot = ProfilerSnapshot.empty();
    private volatile String lastExportStatus = "";
    private volatile EntityCounts latestEntityCounts = EntityCounts.empty();
    private volatile ChunkCounts latestChunkCounts = ChunkCounts.empty();
    private volatile boolean sessionLogging;
    private volatile long sessionLoggingStartedAtMillis;
    private volatile boolean sessionRecorded;
    private volatile long sessionRecordedAtMillis;
    private long lastSeenFrameSequence = 0;
    private long lastSnapshotPublishedAtMillis = 0L;

    public void initialize() {
        mode = ConfigManager.getCaptureMode();
        CpuSamplingProfiler.getInstance().start();
        publishSnapshot(true);
    }

    public void onScreenOpened() {
        screenOpen = true;
        if (mode == CaptureMode.OPEN_ONLY || mode == CaptureMode.MANUAL_DEEP) {
            clearRollingWindows();
            RenderPhaseProfiler.getInstance().reset();
            ModTimingProfiler.getInstance().reset();
            CpuSamplingProfiler.getInstance().reset();
            FlamegraphProfiler.getInstance().reset();
            TickProfiler.getInstance().reset();
        }
        MemoryProfiler.getInstance().sampleJvm();
        publishSnapshot(true);
    }

    public void onScreenClosed() {
        screenOpen = false;
        if (mode == CaptureMode.OPEN_ONLY) {
            clearRollingWindows();
            publishSnapshot(true);
        }
    }

    public void cycleMode() {
        setMode(mode.next());
    }

    public void setMode(CaptureMode mode) {
        this.mode = mode;
        ConfigManager.setCaptureMode(mode);
        clearRollingWindows();
        publishSnapshot(true);
    }

    public boolean isCaptureActive() {
        return switch (mode) {
            case OFF -> false;
            case OPEN_ONLY -> screenOpen;
            case PASSIVE_LIGHTWEIGHT, SPIKE_CAPTURE -> true;
            case MANUAL_DEEP -> screenOpen;
        };
    }

    public CaptureMode getMode() {
        return mode;
    }

    public ProfilerSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    public boolean isSessionLogging() {
        return sessionLogging;
    }

    public long getSessionLoggingElapsedMillis() {
        if (!sessionLogging || sessionLoggingStartedAtMillis == 0L) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - sessionLoggingStartedAtMillis);
    }

    public boolean isSessionRecorded() {
        return sessionRecorded;
    }

    public long getSessionRecordedAgeMillis() {
        if (!sessionRecorded || sessionRecordedAtMillis == 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, System.currentTimeMillis() - sessionRecordedAtMillis);
    }

    public void toggleSessionLogging() {
        if (sessionLogging) {
            sessionLogging = false;
            publishSnapshot(true);
            return;
        }
        sessionHistory.clear();
        hotChunkHistory.clear();
        chunkActivityHistory.clear();
        latestHotChunks = List.of();
        latestRuleFindings = List.of();
        sessionLogging = true;
        sessionRecorded = false;
        sessionRecordedAtMillis = 0L;
        sessionLoggingStartedAtMillis = System.currentTimeMillis();
        publishSnapshot(true);
    }

    public void onClientTickEnd(MinecraftClient client) {
        latestEntityCounts = sampleEntityCounts(client);
        latestChunkCounts = sampleChunkCounts(client);
        latestHotChunks = sampleHotChunks(client);
        latestEntityHotspots = sampleEntityHotspots(client);
        latestBlockEntityHotspots = sampleBlockEntityHotspots(client);

        if (client.world != null && client.world.getTime() % 200 == 0) {
            MemoryProfiler.getInstance().sampleJvm();
        }
        SystemMetricsProfiler.getInstance().sample(MemoryProfiler.getInstance().getDetailedSnapshot());
        ThreadLoadProfiler.getInstance().sample();
        NetworkPacketProfiler.getInstance().drainWindow();
        latestLockSummaries = buildLockSummaries(SystemMetricsProfiler.getInstance().getSnapshot());

        if (!isCaptureActive()) {
            enforceSessionWindow(client);
            publishSnapshot(false);
            return;
        }

        CpuSamplingProfiler.WindowSnapshot cpuWindow = CpuSamplingProfiler.getInstance().drainWindow();
        Map<String, ModTimingSnapshot> modWindow = ModTimingProfiler.getInstance().drainSnapshot();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> renderWindow = RenderPhaseProfiler.getInstance().drainSnapshot();

        pushWindow(cpuWindows, cpuWindow.samples());
        pushWindow(cpuDetailWindows, cpuWindow.detailsByMod());
        pushWindow(modWindows, modWindow);
        pushWindow(renderWindows, renderWindow);

        boolean allowDeepMemory = screenOpen || mode == CaptureMode.MANUAL_DEEP;
        if (allowDeepMemory && TaskManagerScreen.isMemoryTabActive(client) && MemoryProfiler.getInstance().getLastModSampleAgeMillis() > 2000) {
            MemoryProfiler.getInstance().samplePerMod();
        }

        latestRuleFindings = buildRuleFindings();
        captureSpikeIfNeeded();
        captureStutterJumpSnapshot(client);
        recordSessionPoint();
        enforceSessionWindow(client);
        publishSnapshot(false, cpuWindow.lastSampleAgeMillis());
    }

    public java.util.List<SessionPoint> getSessionHistory() {
        return java.util.List.copyOf(sessionHistory);
    }

    public String exportSession() {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("generatedAtEpochMillis", System.currentTimeMillis());
        export.put("captureMode", mode.name());
        export.put("sessionDurationSeconds", ConfigManager.getSessionDurationSeconds());
        export.put("entityCounts", latestEntityCounts);
        export.put("chunkCounts", latestChunkCounts);
        export.put("stutterScore", FrameTimelineProfiler.getInstance().getStutterScore());
        export.put("systemMetrics", SystemMetricsProfiler.getInstance().getSnapshot());
        export.put("topThreadBreakdown", SystemMetricsProfiler.getInstance().getSnapshot().threadLoadPercentByName());
        export.put("topThreadDetails", SystemMetricsProfiler.getInstance().getSnapshot().threadDetailsByName());
        export.put("highestThreadCpuThread", highestCpuThreadName(SystemMetricsProfiler.getInstance().getSnapshot()));
        export.put("stutterScoreTopThread", buildStutterScoreThreadLink());
        export.put("threadLoadHistory", ThreadLoadProfiler.getInstance().getHistory());
        export.put("networkPacketHistory", NetworkPacketProfiler.getInstance().getHistory());
        export.put("networkSpikeBookmarks", NetworkPacketProfiler.getInstance().getSpikeHistory());
        export.put("spikeBookmarks", buildSpikeBookmarks());
        export.put("hotChunks", latestHotChunks);
        export.put("hotChunkHistory", buildHotChunkHistoryExport());
        export.put("entityHotspots", latestEntityHotspots);
        export.put("blockEntityHotspots", latestBlockEntityHotspots);
        export.put("lockSummaries", latestLockSummaries);
        export.put("ruleFindings", latestRuleFindings);
        export.put("stutterJumpSnapshots", new ArrayList<>(stutterJumpSnapshots));
        export.put("exportSummary", buildExportSummary());
        export.put("diagnosis", buildDiagnosis());
        export.put("spikes", new ArrayList<>(spikes));
        export.put("sessionPoints", new ArrayList<>(sessionHistory));
        export.put("currentSnapshot", currentSnapshot);

        Path dir = FabricLoader.getInstance().getGameDir().resolve("taskmanager-sessions");
        try {
            Files.createDirectories(dir);
            long exportTimestamp = System.currentTimeMillis();
            Path file = dir.resolve("taskmanager-session-" + exportTimestamp + ".json");
            Files.writeString(file, EXPORT_GSON.toJson(export));
            Path htmlFile = dir.resolve("taskmanager-session-" + exportTimestamp + ".html");
            Files.writeString(htmlFile, buildHtmlReport(export));
            lastExportStatus = "Exported " + file.getFileName() + " + " + htmlFile.getFileName();
            taskmanagerClient.LOGGER.info(
                    "TaskManager export {} entities total/living/block {}/{}/{} chunks loaded/rendered {}/{} stutterScore {}",
                    file.getFileName(),
                    latestEntityCounts.totalEntities(),
                    latestEntityCounts.livingEntities(),
                    latestEntityCounts.blockEntities(),
                    latestChunkCounts.loadedChunks(),
                    latestChunkCounts.renderedChunks(),
                    String.format("%.1f", FrameTimelineProfiler.getInstance().getStutterScore())
            );
        } catch (Exception e) {
            lastExportStatus = "Export failed: " + e.getMessage();
        }
        publishSnapshot();
        return lastExportStatus;
    }

    private void recordSessionPoint() {
        Map<String, CpuSamplingProfiler.Snapshot> cpu = aggregateCpuWindows();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = aggregateCpuDetailWindows();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases = aggregateRenderWindows();
        long totalRenderSamples = cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::renderSamples).sum();
        List<String> topCpu = cpu.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
        List<String> topGpu = cpu.entrySet().stream()
                .filter(entry -> entry.getValue().renderSamples() > 0)
                .sorted((a, b) -> Long.compare(b.getValue().renderSamples(), a.getValue().renderSamples()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        MemoryProfiler.Snapshot memory = MemoryProfiler.getInstance().getDetailedSnapshot();
        double usedHeapMb = memory.heapUsedBytes() / (1024.0 * 1024.0);
        double allocatedHeapMb = memory.heapCommittedBytes() / (1024.0 * 1024.0);
        SessionPoint previous = sessionHistory.peekLast();
        boolean isGcEvent = previous != null && (previous.usedHeapMb() - usedHeapMb) >= 500.0;

        long drawCalls = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuCalls).sum();
        long bufferUpdates = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuCalls).sum();
        double clientTickMs = TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        double millisecondsPerTick = Math.max(clientTickMs, serverTickMs);
        double msptP95 = TickProfiler.getInstance().getAverageServerTickNs() > 0 ? TickProfiler.getInstance().getServerTickP95Ns() / 1_000_000.0 : 0.0;
        double msptP99 = TickProfiler.getInstance().getAverageServerTickNs() > 0 ? TickProfiler.getInstance().getServerTickP99Ns() / 1_000_000.0 : 0.0;
        FrameTimelineProfiler frameTimeline = FrameTimelineProfiler.getInstance();
        double frameTimeMs = frameTimeline.getLatestFrameNs() / 1_000_000.0;
        double frameTimeP95Ms = frameTimeline.getPercentileFrameNs(0.95) / 1_000_000.0;
        double frameTimeP99Ms = frameTimeline.getPercentileFrameNs(0.99) / 1_000_000.0;
        double frameBuildTimeMs = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuNanos).sum() / 1_000_000.0;
        double gpuFrameTimeMs = renderPhases.values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0;
        double gpuFrameTimeP95Ms = percentileSessionGpuFrameTime(gpuFrameTimeMs);
        SystemMetricsProfiler.Snapshot systemSnapshot = SystemMetricsProfiler.getInstance().getSnapshot();
        List<NetworkPacketProfiler.Snapshot> packetHistory = NetworkPacketProfiler.getInstance().getHistory();
        NetworkPacketProfiler.Snapshot networkSnapshot = packetHistory.isEmpty()
                ? new NetworkPacketProfiler.Snapshot(0L, 0L, Map.of(), Map.of(), Map.of(), Map.of())
                : packetHistory.get(packetHistory.size() - 1);
        HotChunkSnapshot topHotChunk = latestHotChunks.isEmpty() ? null : latestHotChunks.getFirst();
        Map<String, Integer> cpuThreadCountsByMod = new LinkedHashMap<>();
        Map<String, Integer> gpuThreadCountsByMod = new LinkedHashMap<>();
        Map<String, Integer> memoryClassCountsByMod = new LinkedHashMap<>();
        cpuDetails.forEach((modId, detail) -> {
            cpuThreadCountsByMod.put(modId, detail.sampledThreadCount());
            if (cpu.containsKey(modId) && cpu.get(modId).renderSamples() > 0) {
                gpuThreadCountsByMod.put(modId, detail.sampledThreadCount());
            }
        });
        MemoryProfiler.getInstance().getTopClassesByMod().forEach((modId, classes) -> memoryClassCountsByMod.put(modId, classes.size()));

        sessionHistory.addLast(new SessionPoint(
                System.currentTimeMillis(),
                frameTimeline.getCurrentFps(),
                frameTimeline.getAverageFps(),
                frameTimeline.getOnePercentLowFps(),
                frameTimeline.getPointOnePercentLowFps(),
                frameTimeMs,
                frameTimeline.getAverageFrameNs() / 1_000_000.0,
                frameTimeP95Ms,
                frameTimeP99Ms,
                frameTimeline.getFrameStdDevMs(),
                frameTimeline.getFrameVarianceMs(),
                frameTimeline.getFrameTimeHistogram(),
                frameBuildTimeMs,
                gpuFrameTimeMs,
                gpuFrameTimeP95Ms,
                millisecondsPerTick,
                millisecondsPerTick,
                millisecondsPerTick,
                msptP95,
                msptP99,
                clientTickMs,
                serverTickMs,
                frameTimeline.getStutterScore(),
                usedHeapMb,
                allocatedHeapMb,
                memory.heapUsedBytes(),
                memory.heapCommittedBytes(),
                isGcEvent,
                memory.gcPauseDurationMs(),
                memory.gcTimeMillis(),
                memory.gcPauseDurationMs(),
                memory.gcCount(),
                memory.gcType(),
                cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum(),
                totalRenderSamples,
                drawCalls,
                bufferUpdates,
                systemSnapshot.mouseInputLatencyMs(),
                highestCpuThreadName(systemSnapshot),
                sessionParallelismFlag(systemSnapshot, frameTimeline.getStutterScore()),
                systemSnapshot.chunksGenerating(),
                systemSnapshot.chunksMeshing(),
                systemSnapshot.chunksUploading(),
                systemSnapshot.lightsUpdatePending(),
                systemSnapshot.chunkMeshesRebuilt(),
                systemSnapshot.chunkMeshesUploaded(),
                systemSnapshot.playerSpeedBlocksPerSecond(),
                systemSnapshot.chunksEnteredLastSecond(),
                systemSnapshot.distanceTravelledBlocks(),
                systemSnapshot.vramUsedBytes(),
                systemSnapshot.vramTotalBytes(),
                systemSnapshot.textureUploadRate(),
                latestEntityCounts,
                latestChunkCounts,
                systemSnapshot,
                networkSnapshot,
                topHotChunk,
                List.copyOf(latestEntityHotspots),
                List.copyOf(latestBlockEntityHotspots),
                List.copyOf(latestLockSummaries),
                List.copyOf(latestRuleFindings),
                topCpu,
                topGpu,
                cpuThreadCountsByMod,
                gpuThreadCountsByMod,
                memoryClassCountsByMod
        ));
    }

    private List<Map<String, Object>> buildSpikeBookmarks() {
        List<SessionPoint> points = new ArrayList<>(sessionHistory);
        List<Map<String, Object>> bookmarks = new ArrayList<>();
        for (SpikeCapture spike : spikes) {
            int nearestIndex = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                long distance = Math.abs(points.get(i).capturedAtEpochMillis() - spike.capturedAtEpochMillis());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearestIndex = i;
                }
            }
            Map<String, Object> bookmark = new LinkedHashMap<>();
            bookmark.put("capturedAtEpochMillis", spike.capturedAtEpochMillis());
            bookmark.put("frameDurationMs", spike.frameDurationMs());
            bookmark.put("stutterScore", spike.stutterScore());
            bookmark.put("nearestSessionPointIndex", nearestIndex);
            bookmark.put("likelyBottleneck", spike.likelyBottleneck());
            bookmark.put("topCpuMods", spike.topCpuMods());
            bookmark.put("topRenderPhases", spike.topRenderPhases());
            bookmark.put("topThreads", spike.topThreads());
            bookmark.put("findings", spike.findings());
            bookmarks.add(bookmark);
        }
        return bookmarks;
    }

    private Map<String, Object> buildExportSummary() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        Map<String, Object> summary = new LinkedHashMap<>();
        SpikeCapture worstSpike = spikes.stream().max((a, b) -> Double.compare(a.frameDurationMs(), b.frameDurationMs())).orElse(null);
        summary.put("worstSpike", worstSpike);
        summary.put("likelyBottleneck", currentBottleneckLabel());
        summary.put("topHotThreads", system.threadDetailsByName());
        summary.put("topBlockedThreads", topBlockedThreadSummaries(system));
        summary.put("stutterScoreTopThread", buildStutterScoreThreadLink());
        summary.put("gcAnomalies", sessionHistory.stream().filter(SessionPoint::isGcEvent).count());
        summary.put("thermalState", thermalStateLabel(system));
        summary.put("pagingState", system.vramPagingActive() ? "VRAM paging detected" : "No VRAM paging detected");
        summary.put("topHotChunk", latestHotChunks.isEmpty() ? null : latestHotChunks.getFirst());
        summary.put("topEntityHotspots", latestEntityHotspots);
        summary.put("topBlockEntityHotspots", latestBlockEntityHotspots);
        summary.put("lockSummaries", latestLockSummaries);
        summary.put("networkSpikeBookmarks", NetworkPacketProfiler.getInstance().getSpikeHistory());
        summary.put("ruleFindingsBySeverity", buildRuleFindingSeverityBreakdown());
        summary.put("parallelismEfficiency", system.parallelismEfficiency());
        summary.put("serverThreadWaitMs", system.serverThreadWaitMs());
        summary.put("serverThreadBlockedMs", system.serverThreadBlockedMs());
        summary.put("activeIdleWorkerRatio", String.format(Locale.ROOT, "%d/%d (%.2f)", system.activeWorkers(), system.idleWorkers(), system.activeToIdleWorkerRatio()));
        summary.put("offHeapAllocationRate", system.offHeapAllocationRateBytesPerSecond());
        summary.put("currentBiome", system.currentBiome());
        summary.put("lightUpdateQueue", system.lightUpdateQueue());
        summary.put("maxEntitiesInHotChunk", system.maxEntitiesInHotChunk());
        summary.put("sensorErrors", system.sensorErrorCode());
        summary.put("redFlagThresholds", buildRedFlagThresholds());
        double frameAvg = FrameTimelineProfiler.getInstance().getAverageFrameNs() / 1_000_000.0;
        double frameP95 = FrameTimelineProfiler.getInstance().getPercentileFrameNs(0.95) / 1_000_000.0;
        double frameP99 = FrameTimelineProfiler.getInstance().getPercentileFrameNs(0.99) / 1_000_000.0;
        double gpuFrameMs = aggregateRenderWindows().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0;
        double msptAvg = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        double msptP95 = TickProfiler.getInstance().getServerTickP95Ns() / 1_000_000.0;
        double msptP99 = TickProfiler.getInstance().getServerTickP99Ns() / 1_000_000.0;
        summary.put("frameTimeAvgMs", frameAvg);
        summary.put("frameTimeP95Ms", frameP95);
        summary.put("frameTimeP99Ms", frameP99);
        summary.put("gpuFrameTimeMs", gpuFrameMs);
        summary.put("gpuFrameTimeP95Ms", percentileSessionGpuFrameTime(gpuFrameMs));
        summary.put("msptAvg", msptAvg);
        summary.put("msptP95", msptP95);
        summary.put("msptP99", msptP99);
        summary.put("frameTimeHistogram", FrameTimelineProfiler.getInstance().getFrameTimeHistogram());
        summary.put("cpuTemperatureReason", system.cpuTemperatureUnavailableReason());
        summary.put("sensorDiagnostics", buildSensorDiagnostics(system));
        summary.put("worstFrame", Map.of("frameTimeMs", Math.max(frameP99, currentSnapshot.spikes().stream().mapToDouble(SpikeCapture::frameDurationMs).max().orElse(frameP99)), "avgFrameTimeMs", frameAvg, "p95FrameTimeMs", frameP95, "p99FrameTimeMs", frameP99));
        summary.put("worstMsptSpike", Map.of("msptAvg", msptAvg, "msptP95", msptP95, "msptP99", msptP99));
        summary.put("topCpuMods", buildTopCpuModSummary());
        summary.put("topGpuMods", buildTopGpuModSummary());
        summary.put("topMemoryMods", buildTopMemoryModSummary());
        summary.put("hotChunkSummary", latestHotChunks.isEmpty() ? null : latestHotChunks.getFirst());
        summary.put("blockEntityClasses", latestBlockEntityHotspots);
        return summary;
    }

    private Map<String, Object> buildSensorDiagnostics(SystemMetricsProfiler.Snapshot system) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("activeSource", system.sensorSource());
        diagnostics.put("status", system.cpuSensorStatus());
        diagnostics.put("lastError", system.sensorErrorCode());
        diagnostics.put("cpuTemperatureReason", system.cpuTemperatureUnavailableReason());
        return diagnostics;
    }

    private List<Map<String, Object>> buildTopCpuModSummary() {
        long totalCpuSamples = Math.max(1L, currentSnapshot.totalCpuSamples());
        return currentSnapshot.cpuMods().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("samples", entry.getValue().totalSamples());
                    row.put("percentCpu", entry.getValue().totalSamples() * 100.0 / totalCpuSamples);
                    row.put("threadCount", currentSnapshot.cpuDetails().get(entry.getKey()) == null ? 0 : currentSnapshot.cpuDetails().get(entry.getKey()).sampledThreadCount());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopGpuModSummary() {
        long totalRenderSamples = Math.max(1L, currentSnapshot.totalRenderSamples());
        long totalGpuNs = currentSnapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum();
        return currentSnapshot.cpuMods().entrySet().stream()
                .filter(entry -> entry.getValue().renderSamples() > 0)
                .sorted((a, b) -> Long.compare(b.getValue().renderSamples(), a.getValue().renderSamples()))
                .limit(5)
                .map(entry -> {
                    double share = entry.getValue().renderSamples() / (double) totalRenderSamples;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("renderSamples", entry.getValue().renderSamples());
                    row.put("percentGpuEstimate", share * 100.0);
                    row.put("gpuFrameTimeMsEstimate", totalGpuNs * share / 1_000_000.0);
                    row.put("threadCount", currentSnapshot.cpuDetails().get(entry.getKey()) == null ? 0 : currentSnapshot.cpuDetails().get(entry.getKey()).sampledThreadCount());
                    return row;
                })
                .toList();
    }

    private List<Map<String, Object>> buildTopMemoryModSummary() {
        long totalMemory = Math.max(1L, currentSnapshot.memoryMods().values().stream().mapToLong(Long::longValue).sum());
        return currentSnapshot.memoryMods().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("mod", entry.getKey());
                    row.put("memoryMb", entry.getValue() / (1024.0 * 1024.0));
                    row.put("percentAttributedMemory", entry.getValue() * 100.0 / totalMemory);
                    row.put("classCount", currentSnapshot.memoryClassesByMod().getOrDefault(entry.getKey(), Map.of()).size());
                    return row;
                })
                .toList();
    }

    private String currentBottleneckLabel() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        if (system.gpuCoreLoadPercent() > 90.0 && serverTickMs < 15.0) {
            return "GPU bottleneck";
        }
        if (serverTickMs > 40.0) {
            return "Logic bottleneck";
        }
        if (system.diskWriteBytesPerSecond() > 8L * 1024L * 1024L) {
            return "I/O bottleneck";
        }
        return "Balanced";
    }

    private String thermalStateLabel(SystemMetricsProfiler.Snapshot system) {
        if (system.gpuTemperatureC() > 85.0 || system.cpuTemperatureC() > 90.0) {
            return "Thermal warning";
        }
        if (system.cpuTemperatureC() < 0 && system.gpuTemperatureC() < 0) {
            return "Sensors unavailable";
        }
        return "Thermals nominal";
    }


    private List<String> topBlockedThreadSummaries(SystemMetricsProfiler.Snapshot system) {
        return system.threadDetailsByName().entrySet().stream()
                .filter(entry -> entry.getValue().blockedCountDelta() > 0 || entry.getValue().waitedCountDelta() > 0 || "BLOCKED".equals(entry.getValue().state()) || "WAITING".equals(entry.getValue().state()))
                .limit(5)
                .map(entry -> {
                    ThreadLoadProfiler.ThreadSnapshot details = entry.getValue();
                    String lock = describeLock(details);
                    return entry.getKey() + " | " + details.state() + " | blocked " + details.blockedCountDelta() + " | waited " + details.waitedCountDelta() + " | lock " + lock;
                })
                .toList();
    }

    private Map<String, Long> buildRuleFindingSeverityBreakdown() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (RuleFinding finding : latestRuleFindings) {
            counts.merge(finding.severity(), 1L, Long::sum);
        }
        return counts;
    }

    private String describeLock(ThreadLoadProfiler.ThreadSnapshot detail) {
        if (detail == null) {
            return "unknown lock";
        }
        if (detail.lockName() != null && !detail.lockName().isBlank()) {
            return detail.lockName();
        }
        if (detail.lockOwnerName() != null && !detail.lockOwnerName().isBlank()) {
            return "owned by " + detail.lockOwnerName();
        }
        return "unknown lock";
    }

    private double percentileSessionGpuFrameTime(double currentGpuFrameTimeMs) {
        java.util.List<Double> values = new java.util.ArrayList<>();
        for (SessionPoint point : sessionHistory) {
            values.add(point.gpuFrameTimeMs());
        }
        values.add(currentGpuFrameTimeMs);
        values.sort(Double::compareTo);
        if (values.isEmpty()) {
            return 0.0;
        }
        int idx = Math.min(values.size() - 1, Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1));
        return values.get(idx);
    }

    private Map<String, Object> buildStutterScoreThreadLink() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stutterScore", FrameTimelineProfiler.getInstance().getStutterScore());
        result.put("topThreadName", highestCpuThreadName(system));
        result.put("parallelismFlag", sessionParallelismFlag(system, FrameTimelineProfiler.getInstance().getStutterScore()));
        return result;
    }

    private String sessionParallelismFlag(SystemMetricsProfiler.Snapshot system, double stutterScore) {
        if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && stutterScore > 10.0) {
            return "Thread Overscheduling Warning";
        }
        return system.cpuParallelismFlag();
    }

    private String highestCpuThreadName(SystemMetricsProfiler.Snapshot system) {
        if (system.threadLoadPercentByName().isEmpty()) {
            return "unknown";
        }
        return system.threadLoadPercentByName().entrySet().iterator().next().getKey();
    }

    private String buildDiagnosis() {
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        MemoryProfiler.Snapshot memory = MemoryProfiler.getInstance().getDetailedSnapshot();
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        String systemBound = system.gpuCoreLoadPercent() > 90.0 && serverTickMs < 15.0 ? "GPU-bound" : serverTickMs > 40.0 ? "CPU-bound" : "Balanced";
        String thermal = system.gpuTemperatureC() > 85.0 || system.cpuTemperatureC() > 90.0 ? "Thermal warning." : "Thermals are optimal.";
        boolean memoryPressure = false;
        SessionPoint lastPoint = sessionHistory.peekLast();
        if (lastPoint != null) {
            memoryPressure = lastPoint.isGcEvent() && lastPoint.usedHeapMb() >= (lastPoint.allocatedHeapMb() * 0.9);
        }
        String memoryText = memoryPressure ? "Memory pressure detected." : "Memory pressure is low.";
        String logicText = serverTickMs > 40.0 ? String.format("Entity logic overhead is high (%.1fms).", serverTickMs) : String.format("Entity logic overhead is low (%.1fms).", serverTickMs);
        String schedulingText = system.schedulingConflictSummary() == null || system.schedulingConflictSummary().isBlank() ? "" : (" " + system.schedulingConflictSummary() + ".");
        String overscheduleText = sessionParallelismFlag(system, FrameTimelineProfiler.getInstance().getStutterScore()).equals("Thread Overscheduling Warning") ? " Thread overscheduling is likely." : "";
        return "Status: Healthy. System is " + systemBound + ". " + thermal + " " + memoryText + " " + logicText + " Parallelism Efficiency: " + system.parallelismEfficiency() + schedulingText + overscheduleText;
    }
    private void enforceSessionWindow(MinecraftClient client) {
        int maxSessionPoints = Math.max(60, ConfigManager.getSessionDurationSeconds() * 20);
        while (sessionHistory.size() > maxSessionPoints) {
            sessionHistory.removeFirst();
        }
        if (sessionLogging && getSessionLoggingElapsedMillis() >= ConfigManager.getSessionDurationSeconds() * 1000L) {
            sessionLogging = false;
            sessionRecorded = true;
            sessionRecordedAtMillis = System.currentTimeMillis();
            String exportStatus = exportSession();
            if (client != null && client.player != null) {
                client.player.sendMessage(net.minecraft.text.Text.literal("Task Manager: Session recorded. " + exportStatus), false);
            }
        }
    }

    private void captureSpikeIfNeeded() {
        FrameTimelineProfiler frameProfiler = FrameTimelineProfiler.getInstance();
        if (frameProfiler.getFrameSequence() == lastSeenFrameSequence) {
            return;
        }

        lastSeenFrameSequence = frameProfiler.getFrameSequence();
        long frameNs = frameProfiler.getLatestFrameNs();
        if (frameNs < SPIKE_THRESHOLD_NS && mode != CaptureMode.SPIKE_CAPTURE) {
            return;
        }

        Map<String, CpuSamplingProfiler.Snapshot> cpu = aggregateCpuWindows();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> render = aggregateRenderWindows();

        List<String> topCpuMods = cpu.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().totalSamples(), a.getValue().totalSamples()))
                .limit(3)
                .map(entry -> entry.getKey() + " " + entry.getValue().totalSamples())
                .toList();

        List<String> topRenderPhases = render.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().cpuNanos() + b.getValue().gpuNanos(), a.getValue().cpuNanos() + a.getValue().gpuNanos()))
                .limit(3)
                .map(entry -> entry.getKey() + " " + String.format("%.2f ms", (entry.getValue().cpuNanos() + entry.getValue().gpuNanos()) / 1_000_000.0))
                .toList();

        double frameDurationMs = frameNs / 1_000_000.0;
        double stutterScore = frameProfiler.getStutterScore();
        List<String> topThreads = SystemMetricsProfiler.getInstance().getSnapshot().threadDetailsByName().entrySet().stream()
                .limit(5)
                .map(entry -> entry.getKey() + " " + String.format("%.1f%%", entry.getValue().loadPercent()))
                .toList();
        spikes.addFirst(new SpikeCapture(System.currentTimeMillis(), frameDurationMs, stutterScore, latestEntityCounts, latestChunkCounts, topCpuMods, topRenderPhases, topThreads, currentBottleneckLabel(), latestRuleFindings));
        NetworkPacketProfiler.getInstance().captureSpikeBookmark();
        while (spikes.size() > MAX_SPIKES) {
            spikes.removeLast();
        }
    }

    private void publishSnapshot() {
        publishSnapshot(false, Long.MAX_VALUE);
    }

    private void publishSnapshot(boolean force) {
        publishSnapshot(force, Long.MAX_VALUE);
    }

    private void publishSnapshot(long cpuSampleAgeMillis) {
        publishSnapshot(false, cpuSampleAgeMillis);
    }

    private void publishSnapshot(boolean force, long cpuSampleAgeMillis) {
        long now = System.currentTimeMillis();
        if (!force && lastSnapshotPublishedAtMillis != 0L && now - lastSnapshotPublishedAtMillis < ConfigManager.getProfilerUpdateDelayMs()) {
            return;
        }
        lastSnapshotPublishedAtMillis = now;
        Map<String, CpuSamplingProfiler.Snapshot> cpu = aggregateCpuWindows();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = aggregateCpuDetailWindows();
        Map<String, ModTimingSnapshot> modInvokes = aggregateModWindows();
        Map<String, RenderPhaseProfiler.PhaseSnapshot> render = aggregateRenderWindows();

        long totalCpuSamples = cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::totalSamples).sum();
        long totalRenderSamples = cpu.values().stream().mapToLong(CpuSamplingProfiler.Snapshot::renderSamples).sum();

        StartupTimingProfiler startup = StartupTimingProfiler.getInstance();
        currentSnapshot = new ProfilerSnapshot(
                System.currentTimeMillis(),
                mode,
                isCaptureActive(),
                CpuSamplingProfiler.getInstance().hasEnoughCpuSamples(totalCpuSamples),
                CpuSamplingProfiler.getInstance().hasEnoughRenderSamples(totalRenderSamples),
                cpuSampleAgeMillis,
                totalCpuSamples,
                totalRenderSamples,
                cpu,
                cpuDetails,
                modInvokes,
                render,
                MemoryProfiler.getInstance().getDetailedSnapshot(),
                MemoryProfiler.getInstance().getModMemoryBytes(),
                MemoryProfiler.getInstance().getSharedClassFamilies(),
                MemoryProfiler.getInstance().getSharedFamilyClasses(),
                MemoryProfiler.getInstance().getTopClassesByMod(),
                MemoryProfiler.getInstance().getLastModSampleAgeMillis(),
                latestEntityCounts,
                latestChunkCounts,
                SystemMetricsProfiler.getInstance().getSnapshot(),
                FrameTimelineProfiler.getInstance().getStutterScore(),
                startup.getSortedRows(),
                startup.getGlobalFirst(),
                startup.getGlobalLast(),
                FlamegraphProfiler.getInstance().getStacks(),
                new ArrayList<>(spikes),
                sessionLogging,
                getSessionLoggingElapsedMillis(),
                lastExportStatus
        );
    }

    public List<HotChunkSnapshot> getLatestHotChunks() {
        return latestHotChunks;
    }

    public List<Integer> getChunkActivityHistory(ChunkPos chunkPos) {
        if (chunkPos == null) {
            return List.of();
        }
        Deque<Integer> history = chunkActivityHistory.get(chunkKey(chunkPos.x, chunkPos.z));
        return history == null ? List.of() : List.copyOf(history);
    }

    public List<RuleFinding> getLatestRuleFindings() {
        return latestRuleFindings;
    }

    public List<EntityHotspot> getLatestEntityHotspots() {
        return latestEntityHotspots;
    }

    public List<BlockEntityHotspot> getLatestBlockEntityHotspots() {
        return latestBlockEntityHotspots;
    }

    public List<String> getLatestLockSummaries() {
        return latestLockSummaries;
    }

    public String getCurrentBottleneckLabel() {
        return currentBottleneckLabel();
    }

    private List<HotChunkSnapshot> sampleHotChunks(MinecraftClient client) {
        if (client.world == null) {
            hotChunkHistory.clear();
            chunkActivityHistory.clear();
            return List.of();
        }

        record ChunkAggregate(int entityCount, int blockEntityCount, Map<String, Integer> entityClasses, Map<String, Integer> blockEntityClasses) {}
        Map<Long, int[]> counts = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> entityClasses = new LinkedHashMap<>();
        Map<Long, Map<String, Integer>> blockEntityClasses = new LinkedHashMap<>();
        for (Entity entity : client.world.getEntities()) {
            ChunkPos pos = entity.getChunkPos();
            long key = chunkKey(pos.x, pos.z);
            counts.computeIfAbsent(key, ignored -> new int[2])[0]++;
            entityClasses.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(entity.getClass().getSimpleName(), 1, Integer::sum);
        }
        for (BlockEntity blockEntity : client.world.getBlockEntities()) {
            ChunkPos pos = new ChunkPos(blockEntity.getPos());
            long key = chunkKey(pos.x, pos.z);
            counts.computeIfAbsent(key, ignored -> new int[2])[1]++;
            blockEntityClasses.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(blockEntity.getClass().getSimpleName(), 1, Integer::sum);
        }
        List<HotChunkSnapshot> result = counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare((b.getValue()[0] + (b.getValue()[1] * 2)), (a.getValue()[0] + (a.getValue()[1] * 2))))
                .limit(8)
                .map(entry -> new HotChunkSnapshot(
                        (int) (entry.getKey() >> 32),
                        (int) (long) entry.getKey(),
                        entry.getValue()[0],
                        entry.getValue()[1],
                        topClassName(entityClasses.get(entry.getKey())),
                        topClassName(blockEntityClasses.get(entry.getKey())),
                        entry.getValue()[0] + (entry.getValue()[1] * 2.0)
                ))
                .toList();
        hotChunkHistory.addLast(result);
        while (hotChunkHistory.size() > 120) {
            hotChunkHistory.removeFirst();
        }
        for (HotChunkSnapshot chunk : result) {
            long key = chunkKey(chunk.chunkX(), chunk.chunkZ());
            Deque<Integer> history = chunkActivityHistory.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            history.addLast(chunk.entityCount() + chunk.blockEntityCount() * 2);
            while (history.size() > 120) {
                history.removeFirst();
            }
        }
        if (chunkActivityHistory.size() > 64) {
            List<Long> keep = result.stream().map(chunk -> chunkKey(chunk.chunkX(), chunk.chunkZ())).toList();
            chunkActivityHistory.keySet().removeIf(key -> !keep.contains(key));
        }
        return result;
    }

    private List<Map<String, Object>> buildHotChunkHistoryExport() {
        List<Map<String, Object>> export = new ArrayList<>();
        for (HotChunkSnapshot chunk : latestHotChunks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkX", chunk.chunkX());
            row.put("chunkZ", chunk.chunkZ());
            row.put("entityCount", chunk.entityCount());
            row.put("blockEntityCount", chunk.blockEntityCount());
            row.put("topEntityClass", chunk.topEntityClass());
            row.put("topBlockEntityClass", chunk.topBlockEntityClass());
            row.put("activityHistory", getChunkActivityHistory(new ChunkPos(chunk.chunkX(), chunk.chunkZ())));
            export.add(row);
        }
        return export;
    }

    private List<RuleFinding> buildRuleFindings() {
        List<RuleFinding> findings = new ArrayList<>();
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        double latestFrameMs = FrameTimelineProfiler.getInstance().getLatestFrameNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        if (system.gpuCoreLoadPercent() > 90.0 && latestFrameMs > 16.7 && serverTickMs < 15.0) {
            findings.add(new RuleFinding("warning", "gpu", "GPU appears saturated while logic stays healthy.", "measured"));
        }
        if (serverTickMs > 40.0) {
            findings.add(new RuleFinding("warning", "logic", String.format("Server tick is elevated at %.1f ms.", serverTickMs), "measured"));
        }
        if (system.diskWriteBytesPerSecond() > 8L * 1024L * 1024L && latestFrameMs > 50.0) {
            findings.add(new RuleFinding("warning", "io", "Heavy disk writes overlap with a bad frame spike.", "measured"));
        }
        if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && FrameTimelineProfiler.getInstance().getStutterScore() > 10.0) {
            findings.add(new RuleFinding("warning", "threads", "Thread overscheduling warning: too many high-load threads are active for the estimated physical core budget.", "inferred"));
        }
        if (!latestHotChunks.isEmpty() && serverTickMs > 20.0) {
            HotChunkSnapshot hot = latestHotChunks.getFirst();
            findings.add(new RuleFinding("info", "chunks", String.format(Locale.ROOT, "Hot chunk %d,%d has %d entities and %d block entities.", hot.chunkX(), hot.chunkZ(), hot.entityCount(), hot.blockEntityCount()), "measured"));
        }
        if (!latestEntityHotspots.isEmpty()) {
            EntityHotspot hotspot = latestEntityHotspots.getFirst();
            if (!"none".equals(hotspot.heuristic())) {
                findings.add(new RuleFinding("warning", "entities", hotspot.className() + " is dominating recent entity cost signals: " + hotspot.heuristic(), "inferred"));
            }
        }
        if (!latestBlockEntityHotspots.isEmpty()) {
            BlockEntityHotspot hotspot = latestBlockEntityHotspots.getFirst();
            if (hotspot.count() >= 20) {
                findings.add(new RuleFinding("warning", "block-entities", hotspot.className() + " is dense across loaded chunks and may be ticking heavily.", "inferred"));
            }
        }
        if (!latestLockSummaries.isEmpty()) {
            findings.add(new RuleFinding("info", "locks", latestLockSummaries.getFirst(), "measured"));
            boolean chunkIoLock = latestLockSummaries.stream()
                    .map(summary -> summary.toLowerCase(Locale.ROOT))
                    .anyMatch(summary -> summary.contains("region") || summary.contains("chunk") || summary.contains("poi") || summary.contains("anvil") || summary.contains("storage"));
            if (chunkIoLock && (serverTickMs > 20.0 || latestFrameMs > 25.0)) {
                findings.add(new RuleFinding("warning", "chunk-io", "Threads are waiting on chunk or region style locks during a slow window.", "inferred"));
            }
        }
        if (system.cpuTemperatureC() < 0 && system.gpuTemperatureC() < 0) {
            findings.add(new RuleFinding("info", "sensors", "Temperature sensors are unavailable on this machine/provider combination; falling back to load-only telemetry.", "unavailable"));
        }
        if (currentSnapshot.memory().gcPauseDurationMs() > 0) {
            findings.add(new RuleFinding("info", "gc", "Recent GC pause detected: " + currentSnapshot.memory().gcType() + " " + currentSnapshot.memory().gcPauseDurationMs() + " ms.", "measured"));
        }
        return findings;
    }

    private String topClassName(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return "none";
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
    }

    private long chunkKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private List<EntityHotspot> sampleEntityHotspots(MinecraftClient client) {
        if (client.world == null) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entity entity : client.world.getEntities()) {
            counts.merge(entity.getType().toString(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new EntityHotspot(entry.getKey(), entry.getValue(), classifyEntityHeuristic(entry.getKey())))
                .toList();
    }

    private List<BlockEntityHotspot> sampleBlockEntityHotspots(MinecraftClient client) {
        if (client.world == null) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockEntity blockEntity : client.world.getBlockEntities()) {
            counts.merge(blockEntity.getClass().getSimpleName(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(entry -> new BlockEntityHotspot(entry.getKey(), entry.getValue(), classifyBlockEntityHeuristic(entry.getKey())))
                .toList();
    }

    private List<String> buildLockSummaries(SystemMetricsProfiler.Snapshot system) {
        return system.threadDetailsByName().entrySet().stream()
                .filter(entry -> entry.getValue().blockedCountDelta() > 0 || entry.getValue().waitedCountDelta() > 0 || "BLOCKED".equals(entry.getValue().state()) || "WAITING".equals(entry.getValue().state()))
                .limit(5)
                .map(entry -> {
                    ThreadLoadProfiler.ThreadSnapshot detail = entry.getValue();
                    String lockName = detail.lockName() == null || detail.lockName().isBlank() ? "unknown lock" : detail.lockName();
                    return entry.getKey() + " waiting on " + lockName + " (blocked " + detail.blockedCountDelta() + ", waited " + detail.waitedCountDelta() + ")";
                })
                .toList();
    }

    private String classifyEntityHeuristic(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.contains("villager") || lower.contains("bee") || lower.contains("piglin") || lower.contains("warden") || lower.contains("zombie") || lower.contains("creeper") || lower.contains("animal")) {
            return "AI/pathfinding-heavy mob cluster";
        }
        if (lower.contains("item") || lower.contains("experience_orb") || lower.contains("projectile") || lower.contains("arrow")) {
            return "High transient entity count";
        }
        if (lower.contains("boat") || lower.contains("minecart")) {
            return "Collision-heavy vehicle cluster";
        }
        return "none";
    }

    private String classifyBlockEntityHeuristic(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (lower.contains("hopper") || lower.contains("pipe") || lower.contains("conveyor")) {
            return "Inventory transfer / item routing";
        }
        if (lower.contains("chest") || lower.contains("storage") || lower.contains("barrel")) {
            return "Storage dense chunk";
        }
        if (lower.contains("spawner") || lower.contains("beacon") || lower.contains("furnace") || lower.contains("machine")) {
            return "Ticking machine / utility block entity";
        }
        return "General block entity density";
    }



    private void captureStutterJumpSnapshot(MinecraftClient client) {
        double currentStutter = FrameTimelineProfiler.getInstance().getStutterScore();
        if (currentStutter > 20.0 && lastStutterScore <= 20.0) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("capturedAtEpochMillis", System.currentTimeMillis());
            row.put("stutterScore", currentStutter);
            row.put("currentBiome", sampleCurrentBiome(client));
            row.put("closestEntities", sampleClosestEntities(client, 3));
            row.put("topThreads", SystemMetricsProfiler.getInstance().getSnapshot().threadDetailsByName().entrySet().stream()
                    .limit(2)
                    .map(entry -> entry.getKey() + " " + String.format(Locale.ROOT, "%.1f%%", entry.getValue().loadPercent()))
                    .toList());
            stutterJumpSnapshots.addFirst(row);
            while (stutterJumpSnapshots.size() > 8) {
                stutterJumpSnapshots.removeLast();
            }
        }
        lastStutterScore = currentStutter;
    }

    private String sampleCurrentBiome(MinecraftClient client) {
        try {
            if (client == null || client.world == null || client.player == null) {
                return "unknown";
            }
            RegistryEntry<?> biome = client.world.getBiome(client.player.getBlockPos());
            return biome.getKey().map(key -> key.getValue().toString()).orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private List<String> sampleClosestEntities(MinecraftClient client, int limit) {
        if (client == null || client.world == null || client.player == null) {
            return List.of();
        }
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        for (Entity entity : client.world.getEntities()) {
            if (entity != client.player) {
                entities.add(entity);
            }
        }
        return entities.stream()
                .sorted((a, b) -> Double.compare(a.squaredDistanceTo(client.player), b.squaredDistanceTo(client.player)))
                .limit(limit)
                .map(entity -> entity.getName().getString())
                .toList();
    }

    private List<Map<String, String>> buildRedFlagThresholds() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(redFlag("Sync-Lock Latency", "Catches mod conflicts.", "> 10ms"));
        rows.add(redFlag("Draw Call Counter", "Tells you if your base has too many chests/signs.", "> 8,000"));
        rows.add(redFlag("Lighting Queue", "Detects lag caused by light/shadow recalculations.", "> 500 updates"));
        rows.add(redFlag("Thread State Ratio", "Shows if your CPU is working or just waiting.", "< 0.5 ratio"));
        rows.add(redFlag("IO Write Speed", "Detects if your SSD is slowing down the world save.", "> 200ms"));
        return rows;
    }

    private Map<String, String> redFlag(String feature, String why, String value) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("featureName", feature);
        row.put("whyYouNeedIt", why);
        row.put("redFlagValue", value);
        return row;
    }


    private EntityCounts sampleEntityCounts(MinecraftClient client) {
        if (client.world == null) {
            return EntityCounts.empty();
        }

        int total = 0;
        int living = 0;
        for (Entity entity : client.world.getEntities()) {
            total++;
            if (entity instanceof LivingEntity) {
                living++;
            }
        }

        int blockEntities = client.world.getBlockEntities().size();
        return new EntityCounts(total, living, blockEntities);
    }

    private ChunkCounts sampleChunkCounts(MinecraftClient client) {
        if (client.worldRenderer == null) {
            return ChunkCounts.empty();
        }

        String debug = client.worldRenderer.getChunksDebugString();
        if (debug == null || debug.isBlank()) {
            return ChunkCounts.empty();
        }

        Matcher matcher = CHUNK_DEBUG_PATTERN.matcher(debug);
        if (!matcher.find()) {
            return ChunkCounts.empty();
        }

        try {
            int rendered = Integer.parseInt(matcher.group(1));
            int loaded = Integer.parseInt(matcher.group(2));
            return new ChunkCounts(loaded, rendered);
        } catch (NumberFormatException ignored) {
            return ChunkCounts.empty();
        }
    }

    private Map<String, CpuSamplingProfiler.Snapshot> aggregateCpuWindows() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Map<String, CpuSamplingProfiler.Snapshot> window : cpuWindows) {
            window.forEach((mod, snapshot) -> {
                long[] value = totals.computeIfAbsent(mod, ignored -> new long[3]);
                value[0] += snapshot.totalSamples();
                value[1] += snapshot.clientSamples();
                value[2] += snapshot.renderSamples();
            });
        }

        Map<String, CpuSamplingProfiler.Snapshot> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(entry -> result.put(entry.getKey(), new CpuSamplingProfiler.Snapshot(entry.getValue()[0], entry.getValue()[1], entry.getValue()[2])));
        return result;
    }

    private Map<String, CpuSamplingProfiler.DetailSnapshot> aggregateCpuDetailWindows() {
        Map<String, Map<String, Long>> threadTotals = new LinkedHashMap<>();
        Map<String, Map<String, Long>> frameTotals = new LinkedHashMap<>();
        Map<String, Integer> distinctThreadCounts = new LinkedHashMap<>();
        for (Map<String, CpuSamplingProfiler.DetailSnapshot> window : cpuDetailWindows) {
            window.forEach((mod, detail) -> {
                mergeLongMap(threadTotals.computeIfAbsent(mod, ignored -> new LinkedHashMap<>()), detail.topThreads());
                mergeLongMap(frameTotals.computeIfAbsent(mod, ignored -> new LinkedHashMap<>()), detail.topFrames());
                distinctThreadCounts.merge(mod, detail.sampledThreadCount(), Math::max);
            });
        }

        Map<String, CpuSamplingProfiler.DetailSnapshot> result = new LinkedHashMap<>();
        for (String mod : aggregateCpuWindows().keySet()) {
            result.put(mod, new CpuSamplingProfiler.DetailSnapshot(
                    topEntries(threadTotals.get(mod), 5),
                    topEntries(frameTotals.get(mod), 5),
                    distinctThreadCounts.getOrDefault(mod, 0)
            ));
        }
        return result;
    }

    private void mergeLongMap(Map<String, Long> target, Map<String, Long> source) {
        if (source == null) {
            return;
        }
        source.forEach((key, value) -> target.merge(key, value, Long::sum));
    }

    private Map<String, Long> topEntries(Map<String, Long> source, int limit) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private Map<String, ModTimingSnapshot> aggregateModWindows() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Map<String, ModTimingSnapshot> window : modWindows) {
            window.forEach((mod, snapshot) -> {
                long[] value = totals.computeIfAbsent(mod, ignored -> new long[2]);
                value[0] += snapshot.totalNanos();
                value[1] += snapshot.calls();
            });
        }

        Map<String, ModTimingSnapshot> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(entry -> result.put(entry.getKey(), new ModTimingSnapshot(entry.getValue()[0], entry.getValue()[1])));
        return result;
    }

    private Map<String, RenderPhaseProfiler.PhaseSnapshot> aggregateRenderWindows() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (Map<String, RenderPhaseProfiler.PhaseSnapshot> window : renderWindows) {
            window.forEach((phase, snapshot) -> {
                long[] value = totals.computeIfAbsent(phase, ignored -> new long[4]);
                value[0] += snapshot.cpuNanos();
                value[1] += snapshot.cpuCalls();
                value[2] += snapshot.gpuNanos();
                value[3] += snapshot.gpuCalls();
            });
        }

        Map<String, RenderPhaseProfiler.PhaseSnapshot> result = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted((a, b) -> Long.compare((b.getValue()[0] + b.getValue()[2]), (a.getValue()[0] + a.getValue()[2])))
                .forEach(entry -> result.put(entry.getKey(), new RenderPhaseProfiler.PhaseSnapshot(
                        entry.getValue()[0],
                        entry.getValue()[1],
                        entry.getValue()[2],
                        entry.getValue()[3]
                )));
        return result;
    }

    private <T> void pushWindow(Deque<Map<String, T>> deque, Map<String, T> window) {
        deque.addLast(window);
        while (deque.size() > WINDOW_SIZE) {
            deque.removeFirst();
        }
    }

    private String buildHtmlReport(Map<String, Object> export) {
        Map<String, Object> summary = buildExportSummary();
        String diagnosis = buildDiagnosis();
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset='utf-8'><title>Task Manager Session</title><style>")
                .append("body{font-family:Segoe UI,Arial,sans-serif;background:#0f1115;color:#e5e7eb;margin:24px;}h1,h2{color:#f8fafc;}section{background:#171a21;border:1px solid #2b3240;border-radius:10px;padding:16px;margin:12px 0;}code{color:#93c5fd;}table{border-collapse:collapse;width:100%;}td,th{border-bottom:1px solid #2b3240;padding:6px 8px;text-align:left;} .warn{color:#fbbf24;} .good{color:#86efac;}")
                .append("</style></head><body>");
        html.append("<h1>Task Manager Session Report</h1>");
        html.append("<section><h2>Diagnosis</h2><p>").append(escapeHtml(diagnosis)).append("</p></section>");
        html.append("<section><h2>Summary</h2><table>");
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            html.append("<tr><th>").append(escapeHtml(entry.getKey())).append("</th><td>").append(escapeHtml(String.valueOf(entry.getValue()))).append("</td></tr>");
        }
        html.append("</table></section>");
        html.append("<section><h2>Rule Findings</h2><ul>");
        for (RuleFinding finding : latestRuleFindings) {
            html.append("<li><strong>").append(escapeHtml(finding.category())).append("</strong> [").append(escapeHtml(finding.severity())).append("] ")
                    .append(escapeHtml(finding.message())).append(" <code>").append(escapeHtml(finding.confidence())).append("</code></li>");
        }
        html.append("</ul></section>");
        html.append("<section><h2>Entity Hotspots</h2><ul>");
        for (EntityHotspot hotspot : latestEntityHotspots) {
            html.append("<li>").append(escapeHtml(hotspot.className())).append(" x").append(hotspot.count()).append(" - ").append(escapeHtml(hotspot.heuristic())).append("</li>");
        }
        html.append("</ul></section>");
        html.append("<section><h2>Block Entity Hotspots</h2><ul>");
        for (BlockEntityHotspot hotspot : latestBlockEntityHotspots) {
            html.append("<li>").append(escapeHtml(hotspot.className())).append(" x").append(hotspot.count()).append(" - ").append(escapeHtml(hotspot.heuristic())).append("</li>");
        }
        html.append("</ul></section>");
        html.append("<section><h2>Locks / Waiting Threads</h2><ul>");
        for (String lockSummary : latestLockSummaries) {
            html.append("<li>").append(escapeHtml(lockSummary)).append("</li>");
        }
        html.append("</ul></section>");
        html.append("<section><h2>Spike Bookmarks</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(buildSpikeBookmarks()))).append("</pre></section>");
        html.append("<section><h2>Network Spike Bookmarks</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(NetworkPacketProfiler.getInstance().getSpikeHistory()))).append("</pre></section>");
                html.append("<section><h2>Stutter Jump Snapshots</h2><pre>").append(escapeHtml(EXPORT_GSON.toJson(new ArrayList<>(stutterJumpSnapshots)))).append("</pre></section>");
        html.append("</body></html>");
        return html.toString();
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void clearRollingWindows() {
        cpuWindows.clear();
        cpuDetailWindows.clear();
        modWindows.clear();
        renderWindows.clear();
        spikes.clear();
        sessionHistory.clear();
        hotChunkHistory.clear();
        chunkActivityHistory.clear();
        latestHotChunks = List.of();
        latestEntityHotspots = List.of();
        latestBlockEntityHotspots = List.of();
        latestLockSummaries = List.of();
        latestRuleFindings = List.of();
        stutterJumpSnapshots.clear();
        lastStutterScore = 0.0;
        NetworkPacketProfiler.getInstance().reset();
        ThreadLoadProfiler.getInstance().reset();
        lastSeenFrameSequence = 0;
        sessionLoggingStartedAtMillis = 0L;
        sessionLogging = false;
        sessionRecorded = false;
        sessionRecordedAtMillis = 0L;
    }
}



























