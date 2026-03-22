package wueffi.taskmanager.client;

import net.fabricmc.loader.api.FabricLoader;
import wueffi.taskmanager.client.util.ConfigManager;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RuleEngine {

    List<String> buildJvmTuningAdvisor(MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        List<String> advice = new ArrayList<>();
        double heapUsedPct = memory.heapCommittedBytes() > 0 ? memory.heapUsedBytes() * 100.0 / memory.heapCommittedBytes() : 0.0;
        double directPct = memory.directMemoryMaxBytes() > 0 ? (memory.directBufferBytes() + memory.mappedBufferBytes()) * 100.0 / memory.directMemoryMaxBytes() : -1.0;
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        String xmx = firstJvmArgValue(args, "-Xmx");
        String xms = firstJvmArgValue(args, "-Xms");

        if (heapUsedPct >= 85.0) {
            advice.add("Heap is running hot at " + String.format(Locale.ROOT, "%.0f%%", heapUsedPct) + " of committed memory. Raise -Xmx only if this pressure is sustained and GC pauses are appearing.");
        }
        if (memory.gcPauseDurationMs() >= 75L || memory.oldGcCount() > 0L) {
            advice.add("Recent GC pressure is visible (" + memory.gcType() + " " + memory.gcPauseDurationMs() + " ms). Prefer moderate heap sizing over very large -Xmx values so collections stay cheaper.");
        }
        if (directPct >= 80.0) {
            advice.add("Direct/off-heap buffers are near their cap at " + String.format(Locale.ROOT, "%.0f%%", directPct) + ". Check shaders, Sodium/Iris, and high-resolution packs before tweaking heap flags.");
        }
        if (xmx == null) {
            advice.add("No explicit -Xmx flag was detected. Automatic heap sizing is usually fine unless you can prove the JVM is under-allocating for this modpack.");
        } else if (xms != null && xms.equalsIgnoreCase(xmx)) {
            advice.add("Xms matches Xmx (" + xmx + "). Keeping the whole heap committed from startup is not always helpful; reduce Xms if startup memory commit is a concern.");
        }
        if (system.vramPagingActive() && FabricLoader.getInstance().isModLoaded("iris")) {
            advice.add("VRAM paging is active with Iris/shaders enabled. Reduce shader or texture load before reaching for JVM-only tuning.");
        }
        if (advice.isEmpty()) {
            advice.add("No obvious JVM tuning red flags were detected in the current window. Keep defaults or small, measured changes unless a repeatable bottleneck says otherwise.");
        }
        return advice.stream().limit(4).toList();
    }

    List<ProfilerManager.RuleFinding> buildRuleFindings(ProfilerManager manager, MemoryProfiler.Snapshot memorySnapshot) {
        List<ProfilerManager.RuleFinding> findings = new ArrayList<>();
        SystemMetricsProfiler.Snapshot system = SystemMetricsProfiler.getInstance().getSnapshot();
        double latestFrameMs = FrameTimelineProfiler.getInstance().getLatestFrameNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        double clientTickMs = TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0;
        double stutterScore = FrameTimelineProfiler.getInstance().getStutterScore();
        double heapUsedPct = memorySnapshot.heapCommittedBytes() > 0
                ? memorySnapshot.heapUsedBytes() * 100.0 / memorySnapshot.heapCommittedBytes()
                : 0.0;

        if (system.gpuCoreLoadPercent() > 90.0 && latestFrameMs > ConfigManager.getFrameBudgetTargetFrameMs() && serverTickMs < 15.0) {
            findings.add(new ProfilerManager.RuleFinding(latestFrameMs > 33.0 && system.gpuCoreLoadPercent() > 97.0 ? "critical" : "warning", "gpu", "GPU appears saturated while logic stays healthy.", "measured",
                    "The render path is spending time on the GPU while server-side logic remains within budget.",
                    "Check heavy shader packs, high-resolution effects, and the GPU tab's hottest render phases.",
                    String.format(Locale.ROOT, "GPU %.0f%% | frame %.1f ms | server %.1f ms", system.gpuCoreLoadPercent(), latestFrameMs, serverTickMs)));
        }
        if (serverTickMs > 40.0) {
            findings.add(new ProfilerManager.RuleFinding(serverTickMs > 80.0 ? "critical" : "warning", "logic", String.format(Locale.ROOT, "Server tick is elevated at %.1f ms.", serverTickMs), "measured",
                    "Integrated-server work is exceeding a comfortable frame budget and will usually show up as simulation hitching.",
                    "Inspect the World tab for hot chunks, block entities, and thread wait activity around the same window.",
                    String.format(Locale.ROOT, "server %.1f ms | client %.1f ms | stutter %.1f", serverTickMs, clientTickMs, stutterScore)));
        }
        if (system.diskWriteBytesPerSecond() > 8L * 1024L * 1024L && latestFrameMs > 50.0) {
            findings.add(new ProfilerManager.RuleFinding(system.diskWriteBytesPerSecond() > 24L * 1024L * 1024L ? "critical" : "warning", "io", "Heavy disk writes overlap with a bad frame spike.", "measured",
                    "High write throughput is coinciding with a visible hitch and may point to saves, chunk flushes, or logging bursts.",
                    "Check the Disk tab and world activity for saves, region writes, or mods with frequent persistence.",
                    String.format(Locale.ROOT, "writes %s | frame %.1f ms", formatBytesPerSecond(system.diskWriteBytesPerSecond()), latestFrameMs)));
        }
        for (ProfilerManager.SaveEvent saveEvent : manager.getRecentSaves()) {
            if (saveEvent.durationMs() <= 100L) {
                continue;
            }
            findings.add(new ProfilerManager.RuleFinding(saveEvent.durationMs() >= 500L ? "critical" : "warning", "save-stall",
                    saveEvent.type() + " took " + saveEvent.durationMs() + " ms.",
                    "measured",
                    "A world save completed recently with a duration long enough to overlap noticeable stutter or MSPT spikes.",
                    "Check save cadence, world-storage mods, and disk throughput when this repeats.",
                    String.format(Locale.ROOT, "%s | %d ms", saveEvent.type(), saveEvent.durationMs())));
            break;
        }
        for (ProfilerManager.ConflictEdge edge : manager.getLatestConflictEdges()) {
            boolean concretePair = isConcreteMod(edge.waiterMod()) && isConcreteMod(edge.ownerMod()) && !edge.waiterMod().equals(edge.ownerMod());
            long totalWaitMs = edge.blockedTimeMs() + edge.waitedTimeMs();
            if (concretePair && edge.slowdownObservations() > 0L) {
                boolean repeated = edge.observations() >= 3L || edge.slowdownObservations() >= 2L;
                findings.add(new ProfilerManager.RuleFinding(
                        repeated ? "warning" : "info",
                        repeated ? "conflict-repeated" : "conflict-confirmed",
                        edge.waiterMod() + " is repeatedly waiting on " + edge.ownerMod() + " through " + edge.lockName() + ".",
                        edge.confidence(),
                        "Observed pairwise lock contention links the waiting and owning threads to different mods in the same slowdown window.",
                        "Open the Threads and System tabs, inspect " + edge.waiterThreadName() + " vs " + edge.ownerThreadName() + ", and check whether one mod can reduce async world/storage overlap.",
                        String.format(Locale.ROOT, "obs %d | slowdown %d | blocked %d ms | waited %d ms", edge.observations(), edge.slowdownObservations(), edge.blockedTimeMs(), edge.waitedTimeMs())));
                continue;
            }
            if (totalWaitMs > 0L) {
                findings.add(new ProfilerManager.RuleFinding(
                        "info",
                        "conflict-weak",
                        "Contention hints involve " + edge.waiterMod() + " and " + edge.ownerMod() + " around " + edge.lockName() + ".",
                        "weak heuristic",
                        "The lock wait is real, but one or both mod owners are still low-confidence candidates rather than a clean mod-to-mod match.",
                        "Use the thread detail panel to review alternate owner candidates before treating this as a confirmed incompatibility.",
                        String.format(Locale.ROOT, "obs %d | blocked %d ms | waited %d ms", edge.observations(), edge.blockedTimeMs(), edge.waitedTimeMs())));
            }
        }
        if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && stutterScore > 10.0) {
            findings.add(new ProfilerManager.RuleFinding(system.activeHighLoadThreads() > Math.max(2, system.estimatedPhysicalCores()) ? "critical" : "warning", "threads", "Thread overscheduling warning: too many high-load threads are active for the estimated physical core budget.", "weak heuristic",
                    "Multiple hot threads are competing for a limited physical-core budget during a stutter window.",
                    "Inspect the System tab's top threads and worker activity to see whether chunk builders or async workers are crowding the CPU.",
                    String.format(Locale.ROOT, "high-load threads %d | est. physical cores %d | stutter %.1f", system.activeHighLoadThreads(), system.estimatedPhysicalCores(), stutterScore)));
        }
        if (!manager.getLatestHotChunks().isEmpty() && serverTickMs > 20.0) {
            ProfilerManager.HotChunkSnapshot hot = manager.getLatestHotChunks().getFirst();
            findings.add(new ProfilerManager.RuleFinding("info", "chunks", String.format(Locale.ROOT, "Hot chunk %d,%d has %d entities and %d block entities.", hot.chunkX(), hot.chunkZ(), hot.entityCount(), hot.blockEntityCount()), "measured",
                    "A single chunk is standing out in the current window and may be central to the slowdown.",
                    "Select the chunk in the World tab and inspect entity density, block entities, and thread load together.",
                    String.format(Locale.ROOT, "activity %.1f | entities %d | block entities %d", hot.activityScore(), hot.entityCount(), hot.blockEntityCount())));
        }
        if (!manager.getLatestEntityHotspots().isEmpty()) {
            ProfilerManager.EntityHotspot hotspot = manager.getLatestEntityHotspots().getFirst();
            if (!"none".equals(hotspot.heuristic())) {
                findings.add(new ProfilerManager.RuleFinding(hotspot.count() >= 100 ? "critical" : "warning", "entities", hotspot.className() + " is dominating recent entity cost signals: " + hotspot.heuristic(), "inferred",
                        "Recent world samples point to one entity family as the strongest source of per-chunk entity pressure.",
                        "Inspect mob AI density, farms, and clustered spawns in the World tab near the hot chunk.",
                        String.format(Locale.ROOT, "%s x%d", hotspot.className(), hotspot.count())));
            }
        }
        if (!manager.getLatestBlockEntityHotspots().isEmpty()) {
            ProfilerManager.BlockEntityHotspot hotspot = manager.getLatestBlockEntityHotspots().getFirst();
            if (hotspot.count() >= 20) {
                findings.add(new ProfilerManager.RuleFinding(hotspot.count() >= 60 ? "critical" : "warning", "block-entities", hotspot.className() + " is dense across loaded chunks and may be ticking heavily.", "inferred",
                        "A block entity class is showing up frequently enough to plausibly drive ticking or storage pressure.",
                        "Open the Block Entities mini-tab and inspect the selected chunk plus the global hotspot list.",
                        String.format(Locale.ROOT, "%s x%d | %s", hotspot.className(), hotspot.count(), hotspot.heuristic())));
            }
        }
        if (manager.getLatestConflictEdges().isEmpty() && !manager.getLatestLockSummaries().isEmpty()) {
            findings.add(new ProfilerManager.RuleFinding("info", "locks", manager.getLatestLockSummaries().getFirst(), "measured",
                    "A thread spent time blocked or waiting in the current window.",
                    "Use the System tab to check the owning thread and see whether the wait lines up with chunk IO or background workers.",
                    manager.getLatestLockSummaries().getFirst()));
            boolean chunkIoLock = manager.getLatestLockSummaries().stream()
                    .map(summary -> summary.toLowerCase(Locale.ROOT))
                    .anyMatch(summary -> summary.contains("region") || summary.contains("chunk") || summary.contains("poi") || summary.contains("anvil") || summary.contains("storage"));
            if (chunkIoLock && (serverTickMs > 20.0 || latestFrameMs > 25.0)) {
                findings.add(new ProfilerManager.RuleFinding((serverTickMs > 50.0 || latestFrameMs > 40.0) ? "critical" : "warning", "chunk-io", "Threads are waiting on chunk or region style locks during a slow window.", "weak heuristic",
                        "The lock names look chunk-storage related and overlap with a visible slowdown.",
                        "Check async chunk mods, world storage activity, and the Disk tab for matching spikes.",
                        String.format(Locale.ROOT, "server %.1f ms | frame %.1f ms | lock count %d", serverTickMs, latestFrameMs, manager.getLatestLockSummaries().size())));
            }
        }
        if (system.bytesReceivedPerSecond() > 512L * 1024L && latestFrameMs > 20.0) {
            findings.add(new ProfilerManager.RuleFinding("info", "network", "A network burst overlaps with a slower frame window.", "measured",
                    "Inbound traffic is elevated enough to plausibly disturb the client if packet handling or chunk delivery is busy.",
                    "Inspect the Network tab's packet types and recent spike bookmarks.",
                    String.format(Locale.ROOT, "inbound %s | packet latency %.1f ms", formatBytesPerSecond(system.bytesReceivedPerSecond()), system.packetProcessingLatencyMs())));
        }
        if ((system.chunksGenerating() > 0 || system.chunksMeshing() > 0 || system.chunksUploading() > 0) && (latestFrameMs > 20.0 || serverTickMs > 20.0)) {
            findings.add(new ProfilerManager.RuleFinding("info", "chunk-pipeline", "Chunk generation, meshing, or upload work is active during the current slow window.", "measured",
                    "World streaming work is non-idle and may be contributing to a hitch, especially while moving quickly or exploring new terrain.",
                    "Check the World tab and render metrics for generation, meshing, upload, and lighting pressure.",
                    String.format(Locale.ROOT, "gen %d | mesh %d | upload %d | lights %d", system.chunksGenerating(), system.chunksMeshing(), system.chunksUploading(), system.lightsUpdatePending())));
        }
        if (heapUsedPct > 85.0) {
            findings.add(new ProfilerManager.RuleFinding("info", "memory", "Heap usage is high relative to committed memory.", "measured",
                    "Live heap usage is near the current committed ceiling, which can increase GC pressure or mask leaks.",
                    "Inspect the Memory tab for dominant mods and shared JVM buckets, especially if GC pauses are appearing too.",
                    String.format(Locale.ROOT, "heap %.0f%% | used %s", heapUsedPct, formatBytesMb(memorySnapshot.heapUsedBytes()))));
        }
        if (memorySnapshot.gcPauseDurationMs() > 0) {
            findings.add(new ProfilerManager.RuleFinding("info", "gc", "Recent GC pause detected: " + memorySnapshot.gcType() + " " + memorySnapshot.gcPauseDurationMs() + " ms.", "measured",
                    "A garbage-collection pause occurred recently and may explain a hitch if it aligns with frame or tick spikes.",
                    "Correlate the pause with frame-time spikes and high heap usage in the Timeline and Memory tabs.",
                    String.format(Locale.ROOT, "%s | pause %d ms", memorySnapshot.gcType(), memorySnapshot.gcPauseDurationMs())));
            if (latestFrameMs > ConfigManager.getFrameBudgetTargetFrameMs()) {
                findings.add(new ProfilerManager.RuleFinding(memorySnapshot.gcPauseDurationMs() >= 75L ? "warning" : "info", "gc-stutter", "A GC pause overlaps with a slow frame window.", "measured",
                        "Frame time is currently above budget and the JVM reported a recent collection pause in the same sampling window.",
                        "Treat this as a likely stutter source before blaming raw mod CPU alone; check heap pressure and allocation-heavy mods.",
                        String.format(Locale.ROOT, "frame %.1f ms | %s %d ms", latestFrameMs, memorySnapshot.gcType(), memorySnapshot.gcPauseDurationMs())));
            }
        }
        ShaderCompilationProfiler.CompileEvent latestShaderCompile = ShaderCompilationProfiler.getInstance().getLatestEvent();
        if (latestShaderCompile != null
                && ShaderCompilationProfiler.getInstance().hasRecentCompilation(1_500L)
                && latestFrameMs > Math.max(25.0, ConfigManager.getFrameBudgetTargetFrameMs() * 1.5)) {
            findings.add(new ProfilerManager.RuleFinding(latestShaderCompile.durationNs() >= 25_000_000L ? "warning" : "info", "shader-compile",
                    "Shader compilation lined up with a frame spike: " + latestShaderCompile.label() + ".",
                    "measured",
                    "A recent shader/program creation completed close to the current slow frame, which is a common cause of one-off freezes.",
                    "Check shader toggles, resource reloads, or first-use render paths if this repeats.",
                    String.format(Locale.ROOT, "frame %.1f ms | shader %.2f ms", latestFrameMs, latestShaderCompile.durationNs() / 1_000_000.0)));
        }
        EntityCostProfiler.Snapshot entityCosts = EntityCostProfiler.getInstance().getSnapshot();
        if (!entityCosts.tickNanosByType().isEmpty()) {
            Map.Entry<String, Long> topTickEntity = entityCosts.tickNanosByType().entrySet().iterator().next();
            long calls = entityCosts.tickCallsByType().getOrDefault(topTickEntity.getKey(), 0L);
            if (topTickEntity.getValue() >= 5_000_000L) {
                findings.add(new ProfilerManager.RuleFinding(topTickEntity.getValue() >= 20_000_000L ? "warning" : "info", "entity-cost",
                        topTickEntity.getKey() + " is consuming measurable entity tick time in the current window.",
                        "measured",
                        "Per-entity-type timing shows one entity family standing out beyond simple count-based hotspots.",
                        "Inspect mob farms, villager halls, or custom AI-heavy entities before assuming the whole world is equally expensive.",
                        String.format(Locale.ROOT, "%s | %.2f ms | %d calls", topTickEntity.getKey(), topTickEntity.getValue() / 1_000_000.0, calls)));
            }
        }
        ChunkWorkProfiler.Snapshot chunkWork = ChunkWorkProfiler.getInstance().getSnapshot();
        if (!chunkWork.durationNanosByLabel().isEmpty() && (serverTickMs > 20.0 || latestFrameMs > 20.0)) {
            Map.Entry<String, Long> topChunkPhase = chunkWork.durationNanosByLabel().entrySet().iterator().next();
            findings.add(new ProfilerManager.RuleFinding(topChunkPhase.getValue() >= 25_000_000L ? "warning" : "info", "chunk-work",
                    "Chunk loading or generation work is visible in the current slow window: " + topChunkPhase.getKey() + ".",
                    "measured",
                    "Direct chunk-phase timing captured synchronous chunk work instead of relying only on thread-name heuristics.",
                    "Use the World tab's chunk cost section to see whether generation or main-thread chunk loads dominate.",
                    String.format(Locale.ROOT, "%s | %.2f ms | %d calls", topChunkPhase.getKey(), topChunkPhase.getValue() / 1_000_000.0, chunkWork.callsByLabel().getOrDefault(topChunkPhase.getKey(), 0L))));
        }
        if (system.cpuTemperatureC() < 0 && system.gpuTemperatureC() < 0) {
            findings.add(new ProfilerManager.RuleFinding("info", "sensors", "Temperature sensors are unavailable on this machine/provider combination; falling back to load-only telemetry.", "unavailable",
                    "The profiler can still report utilization, but package/core temperatures are not currently exposed by any detected provider.",
                    "Open the System tab's Sensors panel to see provider attempts and the last bridge error.",
                    system.cpuTemperatureUnavailableReason()));
        } else if (system.cpuTemperatureC() >= 85.0 || system.gpuTemperatureC() >= 85.0) {
            findings.add(new ProfilerManager.RuleFinding((system.cpuTemperatureC() >= 92.0 || system.gpuTemperatureC() >= 90.0) ? "critical" : "warning", "thermals", "A CPU or GPU temperature is entering a throttling-prone range.", "measured",
                    "Sustained temperatures in the mid-80s or higher can cause clocks to drop and make spikes harder to explain from software alone.",
                    "Check cooling, fan curves, and whether the slowdown lines up with a thermal ramp in exported sessions.",
                    String.format(Locale.ROOT, "CPU %s | GPU %s", system.cpuTemperatureC() >= 0 ? String.format(Locale.ROOT, "%.1f C", system.cpuTemperatureC()) : "N/A", system.gpuTemperatureC() >= 0 ? String.format(Locale.ROOT, "%.1f C", system.gpuTemperatureC()) : "N/A")));
        }
        findings.sort((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())));
        return findings;
    }

    static int severityRank(String severity) {
        return switch (severity == null ? "info" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    static String classifyEntityHeuristic(String className) {
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

    static String classifyBlockEntityHeuristic(String className) {
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

    private String firstJvmArgValue(List<String> args, String prefix) {
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }

    private static String formatBytesPerSecond(long value) {
        if (value < 0) {
            return "N/A";
        }
        if (value >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f MB/s", value / (1024.0 * 1024.0));
        }
        if (value >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KB/s", value / 1024.0);
        }
        return value + " B/s";
    }

    private static String formatBytesMb(long bytes) {
        if (bytes < 0) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private boolean isConcreteMod(String modId) {
        return modId != null && !modId.isBlank() && !modId.startsWith("shared/") && !modId.startsWith("runtime/");
    }
}
