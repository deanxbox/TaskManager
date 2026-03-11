package wueffi.taskmanager.client;

import com.sun.management.OperatingSystemMXBean;
import org.lwjgl.opengl.GL;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import wueffi.taskmanager.client.util.ConfigManager;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SystemMetricsProfiler {

    public record Snapshot(
            String gpuVendor,
            String gpuRenderer,
            long vramUsedBytes,
            long vramTotalBytes,
            long vramPagingBytes,
            boolean vramPagingActive,
            long committedVirtualMemoryBytes,
            long directMemoryUsedBytes,
            long directMemoryMaxBytes,
            boolean windowsBridgeActive,
            String counterSource,
            String sensorSource,
            String sensorErrorCode,
            String cpuTemperatureUnavailableReason,
            double cpuCoreLoadPercent,
            double gpuCoreLoadPercent,
            double gpuTemperatureC,
            double cpuTemperatureC,
            double cpuLoadChangePerSecond,
            double gpuLoadChangePerSecond,
            double cpuTemperatureChangePerSecond,
            double gpuTemperatureChangePerSecond,
            double mouseInputLatencyMs,
            long bytesReceivedPerSecond,
            long bytesSentPerSecond,
            long diskReadBytesPerSecond,
            long diskWriteBytesPerSecond,
            Map<String, Double> threadLoadPercentByName,
            Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetailsByName,
            String schedulingConflictSummary,
            String cpuParallelismFlag,
            String cpuSensorStatus,
            int activeHighLoadThreads,
            int estimatedPhysicalCores,
            String mainLogicSummary,
            String backgroundSummary,
            double totalThreadLoadPercent,
            String parallelismEfficiency,
            long serverThreadBlockedMs,
            long serverThreadWaitMs,
            int activeWorkers,
            int idleWorkers,
            double activeToIdleWorkerRatio,
            long offHeapAllocationRateBytesPerSecond,
            double packetProcessingLatencyMs,
            String networkBufferSaturation,
            double bytesPerEntity,
            String currentBiome,
            String lightUpdateQueue,
            int maxEntitiesInHotChunk,
            int chunksGenerating,
            int chunksMeshing,
            int chunksUploading,
            int lightsUpdatePending,
            long chunkMeshesRebuilt,
            long chunkMeshesUploaded,
            long textureUploadRate,
            double playerSpeedBlocksPerSecond,
            int chunksEnteredLastSecond,
            double distanceTravelledBlocks
    ) {
        public static Snapshot empty() {
            return new Snapshot(
                    "",
                    "",
                    -1L,
                    -1L,
                    0L,
                    false,
                    -1L,
                    0L,
                    -1L,
                    false,
                    "Unavailable",
                    "Unavailable",
                    "No bridge data",
                    "No provider exposed a readable CPU package temperature",
                    -1.0,
                    -1.0,
                    -1.0,
                    -1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    -1L,
                    -1L,
                    -1L,
                    -1L,
                    Map.of(),
                    Map.of(),
                    "No scheduling conflict detected",
                    "Parallelism unknown",
                    "CPU sensor unavailable",
                    0,
                    Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                    "Main Logic: unknown",
                    "Background: unknown",
                    0.0,
                    "Parallelism unknown",
                    0L,
                    0L,
                    0,
                    0,
                    0.0,
                    0L,
                    -1.0,
                    "Unknown",
                    -1.0,
                    "unknown",
                    "unavailable",
                    0,
                    -1,
                    -1,
                    -1,
                    -1,
                    -1L,
                    -1L,
                    -1L,
                    -1.0,
                    -1,
                    0.0
            );
        }
    }

    private static final SystemMetricsProfiler INSTANCE = new SystemMetricsProfiler();
    public static SystemMetricsProfiler getInstance() { return INSTANCE; }

    private static final int GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;
    private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    private static final int HISTORY_SIZE = 180;

    private final long[] networkInHistory = new long[HISTORY_SIZE];
    private final long[] networkOutHistory = new long[HISTORY_SIZE];
    private final long[] diskReadHistory = new long[HISTORY_SIZE];
    private final long[] diskWriteHistory = new long[HISTORY_SIZE];
    private final double[] cpuLoadHistory = new double[HISTORY_SIZE];
    private final double[] gpuLoadHistory = new double[HISTORY_SIZE];
    private final double[] cpuTemperatureHistory = new double[HISTORY_SIZE];
    private final double[] gpuTemperatureHistory = new double[HISTORY_SIZE];
    private final double[] vramUsedHistory = new double[HISTORY_SIZE];
    private final double[] memoryUsedHistory = new double[HISTORY_SIZE];
    private final double[] memoryCommittedHistory = new double[HISTORY_SIZE];
    private final double[] entityCountHistory = new double[HISTORY_SIZE];
    private final double[] loadedChunkHistory = new double[HISTORY_SIZE];
    private final double[] renderedChunkHistory = new double[HISTORY_SIZE];
    private final WindowsTelemetryBridge windowsBridge = new WindowsTelemetryBridge();
    private int historyIndex;
    private int historyCount;

    private volatile Snapshot snapshot = Snapshot.empty();
    private long lastSampleAtMillis;
    private int lastSampleIntervalMillis = ConfigManager.getMetricsUpdateIntervalMs();
    private long lastDirectMemoryUsedBytes = -1L;
    private double lastCpuLoadPercent = Double.NaN;
    private double lastGpuLoadPercent = Double.NaN;
    private double lastCpuTemperatureC = Double.NaN;
    private double lastGpuTemperatureC = Double.NaN;
    private double lastPlayerX;
    private double lastPlayerY;
    private double lastPlayerZ;
    private boolean lastPlayerPosValid;
    private long lastPlayerSampleAtMillis;
    private int lastPlayerChunkX;
    private int lastPlayerChunkZ;
    private boolean lastPlayerChunkValid;
    private double distanceTravelledBlocks;
    private final Deque<Long> chunkEntryTimes = new ArrayDeque<>();

    public void sample(MemoryProfiler.Snapshot memorySnapshot, ProfilerManager.EntityCounts entityCounts, ProfilerManager.ChunkCounts chunkCounts) {
        long now = System.currentTimeMillis();
        int sampleIntervalMillis = ProfilerManager.getInstance().shouldCollectFrameMetrics() ? 50 : ConfigManager.getMetricsUpdateIntervalMs();
        if (now - lastSampleAtMillis < sampleIntervalMillis) {
            return;
        }
        long previousSampleAtMillis = lastSampleAtMillis;
        long elapsedMillis = previousSampleAtMillis <= 0L ? sampleIntervalMillis : Math.max(1L, now - previousSampleAtMillis);
        lastSampleAtMillis = now;
        lastSampleIntervalMillis = sampleIntervalMillis;

        String vendor = stringOrEmpty(GL11.glGetString(GL11.GL_VENDOR));
        String renderer = stringOrEmpty(GL11.glGetString(GL11.GL_RENDERER));

        long vramUsedBytes = -1;
        long vramTotalBytes = -1;
        try {
            if (GL.getCapabilities().GL_NVX_gpu_memory_info) {
                long totalKb = Integer.toUnsignedLong(GL11.glGetInteger(GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX));
                long availableKb = Integer.toUnsignedLong(GL11.glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX));
                if (totalKb > 0) {
                    vramTotalBytes = totalKb * 1024L;
                    vramUsedBytes = Math.max(0L, (totalKb - availableKb) * 1024L);
                }
            }
        } catch (Throwable ignored) {
        }

        long committedVirtualMemoryBytes = lookupCommittedVirtualMemoryBytes();
        long vramPagingBytes = vramTotalBytes > 0 && vramUsedBytes > vramTotalBytes ? vramUsedBytes - vramTotalBytes : 0L;
        boolean vramPagingActive = vramPagingBytes > 0L;
        long directMemoryUsedBytes = memorySnapshot.directBufferBytes() + memorySnapshot.mappedBufferBytes();
        long directMemoryMaxBytes = lookupDirectMemoryMaxBytes();

        windowsBridge.requestRefreshIfNeeded();
        WindowsTelemetryBridge.Sample bridgeSample = windowsBridge.getLatest();
        Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails = new LinkedHashMap<>(ThreadLoadProfiler.getInstance().getLatestThreadSnapshots());
        Map<String, Double> threadLoads = new LinkedHashMap<>();
        threadDetails.forEach((name, details) -> threadLoads.put(name, details.loadPercent()));
        double totalThreadLoad = threadDetails.values().stream().mapToDouble(ThreadLoadProfiler.ThreadSnapshot::loadPercent).sum();
        long offHeapAllocationRate = 0L;
        if (lastDirectMemoryUsedBytes >= 0L) {
            offHeapAllocationRate = Math.max(0L, Math.round((directMemoryUsedBytes - lastDirectMemoryUsedBytes) * 1000.0 / elapsedMillis));
        }
        lastDirectMemoryUsedBytes = directMemoryUsedBytes;
        ThreadLoadProfiler.ThreadSnapshot serverThread = threadDetails.get("Server Thread");
        int activeWorkers = countWorkers(threadDetails, true);
        int idleWorkers = countWorkers(threadDetails, false);
        double workerRatio = idleWorkers > 0 ? activeWorkers / (double) idleWorkers : activeWorkers;
        int totalEntities = entityCounts.totalEntities();
        double bytesPerEntity = totalEntities > 0 && bridgeSample.bytesReceivedPerSecond() >= 0 ? bridgeSample.bytesReceivedPerSecond() / (double) totalEntities : -1.0;
        NetworkPacketProfiler.Snapshot latestPacketSnapshot = NetworkPacketProfiler.getInstance().getLatestSnapshot();
        long packetVolume = latestPacketSnapshot.inboundPackets() + latestPacketSnapshot.outboundPackets();
        double packetProcessingLatencyMs = packetVolume > 0 ? Math.min(250.0, (packetVolume / 20.0) + (TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0 * 0.25)) : -1.0;
        String networkBufferSaturation = packetVolume > 400 ? "High packet burst pressure" : packetVolume > 120 ? "Moderate packet burst pressure" : "Low packet burst pressure";
        String biome = sampleBiome();
        String lightUpdateQueue = sampleLightQueue();
        int lightsUpdatePending = parseLeadingInt(lightUpdateQueue);
        int chunksGenerating = countThreadsMatching(threadDetails, "gen", "generation", "worldgen");
        int chunksMeshing = countThreadsMatching(threadDetails, "mesh", "builder", "chunk build");
        int chunksUploading = countThreadsMatching(threadDetails, "upload", "uploader");
        Map<String, RenderPhaseProfiler.PhaseSnapshot> renderPhases = RenderPhaseProfiler.getInstance().getSnapshot();
        long chunkMeshesRebuilt = sumPhaseCalls(renderPhases, "chunk", "mesh", "build", "rebuild");
        long chunkMeshesUploaded = sumPhaseCalls(renderPhases, "upload");
        long textureUploadRate = sumPhaseCalls(renderPhases, "texture", "upload");
        PlayerMotionSnapshot motion = samplePlayerMotion(now);
        List<ProfilerManager.HotChunkSnapshot> hotChunks = ProfilerManager.getInstance().getLatestHotChunks();
        int maxEntitiesInHotChunk = hotChunks.isEmpty() ? 0 : hotChunks.getFirst().entityCount();
        double cpuLoadChangePerSecond = computeDeltaPerSecond(bridgeSample.cpuCoreLoadPercent(), lastCpuLoadPercent, elapsedMillis);
        double gpuLoadChangePerSecond = computeDeltaPerSecond(bridgeSample.gpuCoreLoadPercent(), lastGpuLoadPercent, elapsedMillis);
        double cpuTemperatureChangePerSecond = computeDeltaPerSecond(bridgeSample.cpuTemperatureC(), lastCpuTemperatureC, elapsedMillis);
        double gpuTemperatureChangePerSecond = computeDeltaPerSecond(bridgeSample.gpuTemperatureC(), lastGpuTemperatureC, elapsedMillis);
        lastCpuLoadPercent = bridgeSample.cpuCoreLoadPercent();
        lastGpuLoadPercent = bridgeSample.gpuCoreLoadPercent();
        lastCpuTemperatureC = bridgeSample.cpuTemperatureC();
        lastGpuTemperatureC = bridgeSample.gpuTemperatureC();

        snapshot = new Snapshot(
                vendor,
                renderer,
                vramUsedBytes,
                vramTotalBytes,
                vramPagingBytes,
                vramPagingActive,
                committedVirtualMemoryBytes,
                directMemoryUsedBytes,
                directMemoryMaxBytes,
                bridgeSample.bridgeActive(),
                bridgeSample.counterSource(),
                bridgeSample.sensorSource(),
                bridgeSample.sensorErrorCode(),
                buildCpuTemperatureUnavailableReason(bridgeSample),
                bridgeSample.cpuCoreLoadPercent(),
                bridgeSample.gpuCoreLoadPercent(),
                bridgeSample.gpuTemperatureC(),
                bridgeSample.cpuTemperatureC(),
                cpuLoadChangePerSecond,
                gpuLoadChangePerSecond,
                cpuTemperatureChangePerSecond,
                gpuTemperatureChangePerSecond,
                InputLatencyProfiler.getInstance().getLastPresentedLatencyMs(),
                bridgeSample.bytesReceivedPerSecond(),
                bridgeSample.bytesSentPerSecond(),
                bridgeSample.diskReadBytesPerSecond(),
                bridgeSample.diskWriteBytesPerSecond(),
                threadLoads,
                threadDetails,
                buildSchedulingConflictSummary(threadDetails),
                buildParallelismFlag(threadDetails),
                buildCpuSensorStatus(bridgeSample.sensorSource()),
                countHighLoadThreads(threadDetails),
                estimatePhysicalCores(),
                buildMainLogicSummary(threadDetails),
                buildBackgroundSummary(threadDetails),
                totalThreadLoad,
                buildParallelismEfficiency(totalThreadLoad),
                serverThread == null ? 0L : serverThread.blockedTimeDeltaMs(),
                serverThread == null ? 0L : serverThread.waitedTimeDeltaMs(),
                activeWorkers,
                idleWorkers,
                workerRatio,
                offHeapAllocationRate,
                packetProcessingLatencyMs,
                networkBufferSaturation,
                bytesPerEntity,
                biome,
                lightUpdateQueue,
                maxEntitiesInHotChunk,
                chunksGenerating,
                chunksMeshing,
                chunksUploading,
                lightsUpdatePending,
                chunkMeshesRebuilt,
                chunkMeshesUploaded,
                textureUploadRate,
                motion.speedBlocksPerSecond(),
                motion.chunksEnteredLastSecond(),
                motion.distanceTravelledBlocks()
        );

        pushHistory(networkInHistory, Math.max(0L, snapshot.bytesReceivedPerSecond()));
        pushHistory(networkOutHistory, Math.max(0L, snapshot.bytesSentPerSecond()));
        pushHistory(diskReadHistory, Math.max(0L, snapshot.diskReadBytesPerSecond()));
        pushHistory(diskWriteHistory, Math.max(0L, snapshot.diskWriteBytesPerSecond()));
        pushHistory(cpuLoadHistory, Math.max(0.0, snapshot.cpuCoreLoadPercent()));
        pushHistory(gpuLoadHistory, Math.max(0.0, snapshot.gpuCoreLoadPercent()));
        pushHistory(cpuTemperatureHistory, snapshot.cpuTemperatureC());
        pushHistory(gpuTemperatureHistory, snapshot.gpuTemperatureC());
        pushHistory(vramUsedHistory, snapshot.vramUsedBytes() >= 0L ? Math.max(0.0, snapshot.vramUsedBytes() / (1024.0 * 1024.0)) : -1.0);
        pushHistory(memoryUsedHistory, Math.max(0.0, memorySnapshot.heapUsedBytes() / (1024.0 * 1024.0)));
        pushHistory(memoryCommittedHistory, Math.max(0.0, memorySnapshot.heapCommittedBytes() / (1024.0 * 1024.0)));
        pushHistory(entityCountHistory, Math.max(0.0, entityCounts.totalEntities()));
        pushHistory(loadedChunkHistory, Math.max(0.0, chunkCounts.loadedChunks()));
        pushHistory(renderedChunkHistory, Math.max(0.0, chunkCounts.renderedChunks()));
        advanceHistory();
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public long[] getNetworkInHistory() { return networkInHistory; }
    public long[] getNetworkOutHistory() { return networkOutHistory; }
    public long[] getDiskReadHistory() { return diskReadHistory; }
    public long[] getDiskWriteHistory() { return diskWriteHistory; }
    public int getHistoryIndex() { return historyIndex; }
    public int getHistoryCount() { return historyCount; }
    public long[] getOrderedNetworkInHistory() { return orderedHistory(networkInHistory); }
    public long[] getOrderedNetworkOutHistory() { return orderedHistory(networkOutHistory); }
    public long[] getOrderedDiskReadHistory() { return orderedHistory(diskReadHistory); }
    public long[] getOrderedDiskWriteHistory() { return orderedHistory(diskWriteHistory); }
    public double[] getOrderedCpuLoadHistory() { return orderedHistory(cpuLoadHistory); }
    public double[] getOrderedGpuLoadHistory() { return orderedHistory(gpuLoadHistory); }
    public double[] getOrderedCpuTemperatureHistory() { return orderedHistory(cpuTemperatureHistory); }
    public double[] getOrderedGpuTemperatureHistory() { return orderedHistory(gpuTemperatureHistory); }
    public double[] getOrderedVramUsedHistory() { return orderedHistory(vramUsedHistory); }
    public double[] getOrderedMemoryUsedHistory() { return orderedHistory(memoryUsedHistory); }
    public double[] getOrderedMemoryCommittedHistory() { return orderedHistory(memoryCommittedHistory); }
    public double[] getOrderedEntityCountHistory() { return orderedHistory(entityCountHistory); }
    public double[] getOrderedLoadedChunkHistory() { return orderedHistory(loadedChunkHistory); }
    public double[] getOrderedRenderedChunkHistory() { return orderedHistory(renderedChunkHistory); }
    public double getHistorySpanSeconds() { return historyCount <= 1 ? 0.0 : (historyCount - 1) * (lastSampleIntervalMillis / 1000.0); }

    private void pushHistory(long[] history, long value) {
        history[historyIndex] = value;
    }

    private void pushHistory(double[] history, double value) {
        history[historyIndex] = value;
    }

    private double computeDeltaPerSecond(double currentValue, double previousValue, long elapsedMillis) {
        if (!Double.isFinite(currentValue) || currentValue < 0.0 || !Double.isFinite(previousValue) || previousValue < 0.0 || elapsedMillis <= 0L) {
            return 0.0;
        }
        return (currentValue - previousValue) * 1000.0 / elapsedMillis;
    }

    private void advanceHistory() {
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        if (historyCount < HISTORY_SIZE) {
            historyCount++;
        }
    }

    private long[] orderedHistory(long[] history) {
        long[] ordered = new long[historyCount];
        for (int i = 0; i < historyCount; i++) {
            int sourceIndex = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
            ordered[i] = history[sourceIndex];
        }
        return ordered;
    }

    private double[] orderedHistory(double[] history) {
        double[] ordered = new double[historyCount];
        for (int i = 0; i < historyCount; i++) {
            int sourceIndex = (historyIndex - historyCount + i + HISTORY_SIZE) % HISTORY_SIZE;
            ordered[i] = history[sourceIndex];
        }
        return ordered;
    }

    private long lookupCommittedVirtualMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean sunBean) {
                return Math.max(0L, sunBean.getCommittedVirtualMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private long lookupDirectMemoryMaxBytes() {
        try {
            Class<?> vmClass = Class.forName("jdk.internal.misc.VM");
            Method method = vmClass.getDeclaredMethod("maxDirectMemory");
            method.setAccessible(true);
            Object value = method.invoke(null);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> vmClass = Class.forName("sun.misc.VM");
            Method method = vmClass.getDeclaredMethod("maxDirectMemory");
            method.setAccessible(true);
            Object value = method.invoke(null);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (Throwable ignored) {
        }

        String maxDirectMemorySize = System.getProperty("sun.nio.MaxDirectMemorySize");
        if (maxDirectMemorySize != null && !maxDirectMemorySize.isBlank()) {
            try {
                String trimmed = maxDirectMemorySize.trim().toUpperCase(Locale.ROOT);
                long multiplier = 1L;
                if (trimmed.endsWith("K")) {
                    multiplier = 1024L;
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                } else if (trimmed.endsWith("M")) {
                    multiplier = 1024L * 1024L;
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                } else if (trimmed.endsWith("G")) {
                    multiplier = 1024L * 1024L * 1024L;
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                return Long.parseLong(trimmed) * multiplier;
            } catch (NumberFormatException ignored) {
            }
        }

        long runtimeMaxMemory = Runtime.getRuntime().maxMemory();
        if (runtimeMaxMemory > 0) {
            return runtimeMaxMemory;
        }

        return -1;
    }



    private String buildCpuTemperatureUnavailableReason(WindowsTelemetryBridge.Sample bridgeSample) {
        if (bridgeSample.cpuTemperatureC() >= 0) {
            return "CPU temperature provider active";
        }
        String source = bridgeSample.sensorSource() == null ? "" : bridgeSample.sensorSource();
        String error = bridgeSample.sensorErrorCode() == null || bridgeSample.sensorErrorCode().isBlank() ? "no provider-specific error reported" : bridgeSample.sensorErrorCode();
        if (source.toLowerCase(Locale.ROOT).contains("unavailable")) {
            return "CPU temperature unavailable because no external sensor bridge exposed a readable package sensor. Last bridge error: " + error;
        }
        return "CPU temperature unavailable. Source: " + source + " | Last bridge error: " + error;
    }

    private int countThreadsMatching(Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails, String... needles) {
        return (int) threadDetails.keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .filter(name -> {
                    for (String needle : needles) {
                        if (name.contains(needle)) {
                            return true;
                        }
                    }
                    return false;
                })
                .count();
    }

    private long sumPhaseCalls(Map<String, RenderPhaseProfiler.PhaseSnapshot> phases, String... needles) {
        return phases.entrySet().stream()
                .filter(entry -> {
                    String lower = entry.getKey().toLowerCase(Locale.ROOT);
                    for (String needle : needles) {
                        if (lower.contains(needle)) {
                            return true;
                        }
                    }
                    return false;
                })
                .mapToLong(entry -> Math.max(entry.getValue().cpuCalls(), entry.getValue().gpuCalls()))
                .sum();
    }

    private int parseLeadingInt(String value) {
        if (value == null) {
            return -1;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record PlayerMotionSnapshot(double speedBlocksPerSecond, int chunksEnteredLastSecond, double distanceTravelledBlocks) {}

    private PlayerMotionSnapshot samplePlayerMotion(long now) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                return new PlayerMotionSnapshot(-1.0, chunkEntryTimes.size(), distanceTravelledBlocks);
            }
            double speed = -1.0;
            if (lastPlayerPosValid && lastPlayerSampleAtMillis > 0L) {
                long elapsed = Math.max(1L, now - lastPlayerSampleAtMillis);
                double dx = client.player.getX() - lastPlayerX;
                double dy = client.player.getY() - lastPlayerY;
                double dz = client.player.getZ() - lastPlayerZ;
                double distance = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
                distanceTravelledBlocks += distance;
                speed = distance * 1000.0 / elapsed;
            }
            lastPlayerX = client.player.getX();
            lastPlayerY = client.player.getY();
            lastPlayerZ = client.player.getZ();
            lastPlayerSampleAtMillis = now;
            lastPlayerPosValid = true;

            int chunkX = client.player.getChunkPos().x;
            int chunkZ = client.player.getChunkPos().z;
            if (!lastPlayerChunkValid || chunkX != lastPlayerChunkX || chunkZ != lastPlayerChunkZ) {
                chunkEntryTimes.addLast(now);
                lastPlayerChunkX = chunkX;
                lastPlayerChunkZ = chunkZ;
                lastPlayerChunkValid = true;
            }
            while (!chunkEntryTimes.isEmpty() && now - chunkEntryTimes.peekFirst() > 1000L) {
                chunkEntryTimes.removeFirst();
            }
            return new PlayerMotionSnapshot(speed, chunkEntryTimes.size(), distanceTravelledBlocks);
        } catch (Throwable ignored) {
            return new PlayerMotionSnapshot(-1.0, chunkEntryTimes.size(), distanceTravelledBlocks);
        }
    }

    private String buildSchedulingConflictSummary(Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails) {
        ThreadLoadProfiler.ThreadSnapshot server = threadDetails.get("Server Thread");
        ThreadLoadProfiler.ThreadSnapshot render = threadDetails.get("Render Thread");
        double workerLoad = threadDetails.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("Worker-Main-"))
                .mapToDouble(entry -> entry.getValue().loadPercent())
                .sum();
        int processors = Runtime.getRuntime().availableProcessors();
        if (server != null && server.loadPercent() > 35.0 && workerLoad > 50.0 && processors <= 8) {
            return String.format(Locale.ROOT, "Possible scheduling conflict: Server Thread %.1f%% with Worker-Main load %.1f%% across %d logical cores", server.loadPercent(), workerLoad, processors);
        }
        if (render != null && render.loadPercent() > 35.0 && workerLoad > 50.0 && processors <= 8) {
            return String.format(Locale.ROOT, "Possible render scheduling conflict: Render Thread %.1f%% with Worker-Main load %.1f%% across %d logical cores", render.loadPercent(), workerLoad, processors);
        }
        return "No scheduling conflict detected";
    }

    private String buildParallelismFlag(Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails) {
        long activeWorkers = threadDetails.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("Worker-Main-") && entry.getValue().loadPercent() >= 5.0)
                .count();
        long blockedWorkers = threadDetails.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("Worker-Main-") && (entry.getValue().blockedCountDelta() > 0 || entry.getValue().waitedCountDelta() > 0 || "BLOCKED".equals(entry.getValue().state()) || "WAITING".equals(entry.getValue().state())))
                .count();
        double workerLoad = threadDetails.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("Worker-Main-"))
                .mapToDouble(entry -> entry.getValue().loadPercent())
                .sum();
        ThreadLoadProfiler.ThreadSnapshot server = threadDetails.get("Server Thread");
        double serverLoad = server == null ? 0.0 : server.loadPercent();
        if (activeWorkers == 0 && workerLoad < 5.0) {
            return String.format(Locale.ROOT, "Parallelism low (%d high-load threads)", countHighLoadThreads(threadDetails));
        }
        if (blockedWorkers >= Math.max(2, activeWorkers) && activeWorkers > 0) {
            return String.format(Locale.ROOT, "Parallelism blocked (%d active / %d waiting)", activeWorkers, blockedWorkers);
        }
        if (serverLoad > 35.0 && workerLoad > 80.0) {
            return String.format(Locale.ROOT, "Parallelism saturated (%d workers / %d high-load threads)", activeWorkers, countHighLoadThreads(threadDetails));
        }
        return String.format(Locale.ROOT, "Parallelism healthy (%d workers / %d high-load threads)", activeWorkers, countHighLoadThreads(threadDetails));
    }

    private int estimatePhysicalCores() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    private int countHighLoadThreads(Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails) {
        return (int) threadDetails.values().stream()
                .filter(snapshot -> snapshot.loadPercent() >= 50.0)
                .count();
    }

    private String buildMainLogicSummary(Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails) {
        Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot> main = threadDetails.entrySet().stream()
                .filter(entry -> isMainLogicThread(entry.getKey()))
                .max((a, b) -> Double.compare(a.getValue().loadPercent(), b.getValue().loadPercent()))
                .orElse(null);
        if (main == null) {
            return "Main Logic: n/a";
        }
        return String.format(Locale.ROOT, "Main Logic: %s (%.0f%%)", main.getKey(), main.getValue().loadPercent());
    }

    private String buildBackgroundSummary(Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails) {
        double backgroundLoad = threadDetails.entrySet().stream()
                .filter(entry -> !isMainLogicThread(entry.getKey()))
                .mapToDouble(entry -> entry.getValue().loadPercent())
                .sum();
        String label = threadDetails.entrySet().stream()
                .filter(entry -> !isMainLogicThread(entry.getKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("Workers/JVM");
        if (label.startsWith("Worker-Main-") || label.startsWith("C2ME")) {
            label = "Workers/C2ME";
        } else if (label.startsWith("G1") || label.contains("GC")) {
            label = "Workers/GC";
        } else if (label.equals("unknown")) {
            label = "Infrastructure";
        }
        return String.format(Locale.ROOT, "Background: %s (%.0f%%)", label, backgroundLoad);
    }

    private boolean isMainLogicThread(String threadName) {
        return "Server Thread".equals(threadName) || "Render Thread".equals(threadName) || threadName.toLowerCase(Locale.ROOT).contains("main thread");
    }

    private String buildCpuSensorStatus(String sensorSource) {
        String lower = sensorSource == null ? "" : sensorSource.toLowerCase(Locale.ROOT);
        if (lower.contains("cpu: core temp shared memory")) {
            return "CPU sensor via Core Temp";
        }
        if (lower.contains("cpu: librehardwaremonitor dll") || lower.contains("cpu: root/librehardwaremonitor")) {
            return "CPU sensor via LibreHardwareMonitor";
        }
        if (lower.contains("cpu: openhardwaremonitor dll") || lower.contains("cpu: root/openhardwaremonitor")) {
            return "CPU sensor via OpenHardwareMonitor";
        }
        if (lower.contains("cpu: hwinfo shared memory")) {
            return "CPU sensor via HWiNFO";
        }
        if (lower.contains("cpu: unavailable")) {
            return "CPU sensor unavailable";
        }
        return "CPU sensor provider detected";
    }



    private String buildParallelismEfficiency(double totalThreadLoad) {
        if (totalThreadLoad > 800.0) {
            return "Heavy Multithreading Active (C2ME).";
        }
        if (totalThreadLoad < 300.0) {
            return "Light Multithreading Active.";
        }
        return "Moderate Multithreading Active.";
    }

    private int countWorkers(Map<String, ThreadLoadProfiler.ThreadSnapshot> threadDetails, boolean active) {
        return (int) threadDetails.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("Worker-Main-") || entry.getKey().toLowerCase(Locale.ROOT).contains("worker") || entry.getKey().contains("C2ME"))
                .filter(entry -> active
                        ? entry.getValue().loadPercent() >= 5.0 || "RUNNABLE".equals(entry.getValue().state())
                        : entry.getValue().loadPercent() < 5.0 && ("WAITING".equals(entry.getValue().state()) || "TIMED_WAITING".equals(entry.getValue().state())))
                .count();
    }

    private String sampleBiome() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) {
                return "unknown";
            }
            return client.world.getBiome(client.player.getBlockPos()).getKey().map(key -> key.getValue().toString()).orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private String sampleLightQueue() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.worldRenderer == null) {
                return "unavailable";
            }
            String debug = client.worldRenderer.getChunksDebugString();
            if (debug == null || debug.isBlank()) {
                return "unavailable";
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("L:\\s*(\\d+)").matcher(debug);
            if (matcher.find()) {
                return matcher.group(1) + " updates";
            }
        } catch (Throwable ignored) {
        }
        return "unavailable";
    }

    private String stringOrEmpty(String value) {
        return value == null ? "" : value;
    }
}







