package wueffi.taskmanager.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import wueffi.taskmanager.client.util.ConfigManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HudOverlayRenderer {

    private static final int BG = 0xC8141418;
    private static final int BORDER = 0xAA3A3F46;
    private static final int HEADER = 0xFFEEF2F6;
    private static final int TEXT = 0xFFD7DDE4;
    private static final int DIM = 0xFF97A3AF;
    private static final int ACCENT = 0xFF70C7A7;
    private static final int WARN = 0xFFFFC857;
    private static final int ERROR = 0xFFFF9F43;
    private static final int CRITICAL = 0xFFFF6B6B;
    private static final int PADDING = 8;
    private static final int GAP = 8;
    private static final int HEADER_HEIGHT = 16;
    private static final int ROW_HEIGHT = 12;
    private static final int SINGLE_COLUMN_WIDTH = 250;
    private static final int TWO_COLUMN_WIDTH = 176;
    private static final int THREE_COLUMN_WIDTH = 138;
    private static final int LABEL_WIDTH = 54;
    private static final int LABEL_VALUE_GAP = 8;
    private static final int VALUE_RIGHT_PADDING = 4;
    private static final int MAX_MEMORY_RATE_SAMPLES = 24;
    private static long lastDisplayedFpsUpdateAtMillis;
    private static long lastDisplayedMemoryUpdateAtMillis;
    private static String displayedFpsText = "0 now | 0 avg";
    private static String displayedLowFpsText = "1% 0 | 0.1% 0";
    private static String displayedMemoryText = "0/0 MB";
    private static final Deque<MemoryRateSample> memoryRateSamples = new ArrayDeque<>();
    private static final Map<String, RateSample> rateSamples = new HashMap<>();
    private static final Map<String, SensorRateSample> sensorRateSamples = new HashMap<>();

    private HudOverlayRenderer() {
    }

    public static void render(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!ConfigManager.isHudEnabled()) {
            return;
        }
        if (client.options.hudHidden || client.currentScreen instanceof TaskManagerScreen) {
            return;
        }

        ProfilerManager profilerManager = ProfilerManager.getInstance();
        ProfilerManager.ProfilerSnapshot snapshot = profilerManager.getCurrentSnapshot();
        FrameTimelineProfiler frame = FrameTimelineProfiler.getInstance();
        MemoryProfiler.Snapshot memory = snapshot.memory();
        SystemMetricsProfiler.Snapshot system = snapshot.systemMetrics();
        refreshDisplayedFps(frame);
        refreshDisplayedMemory(memory);
        ProfilerManager.RuleFinding highestFinding = highestFinding(profilerManager);
        double latestFrameMs = frame.getLatestFrameNs() / 1_000_000.0;
        long recentSpikeAge = snapshot.spikes().isEmpty() ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - snapshot.spikes().get(0).capturedAtEpochMillis());
        boolean actionableWarning = hasActionableWarning(snapshot, highestFinding, latestFrameMs, recentSpikeAge);
        int alertColor = severityColor(highestFinding == null ? null : highestFinding.severity(), actionableWarning);

        if (!shouldRenderHud(snapshot, highestFinding, latestFrameMs, recentSpikeAge)) {
            return;
        }

        List<Entry> entries = new ArrayList<>();
        if (ConfigManager.getHudConfigMode() == ConfigManager.HudConfigMode.PRESET) {
            if (ConfigManager.getHudPreset() == ConfigManager.HudPreset.COMPACT) {
                buildCompactEntries(entries, snapshot, frame, memory, system);
            } else {
                buildPresetFullEntries(entries, snapshot, frame, memory, system);
            }
        } else {
            buildCustomEntries(entries, snapshot, frame, memory, system);
        }

        Entry autoFocusEntry = ConfigManager.isHudAutoFocusAlertRow() ? buildAutoFocusEntry(profilerManager, snapshot, highestFinding, latestFrameMs, alertColor) : null;
        if (autoFocusEntry != null) {
            entries.add(0, autoFocusEntry);
        }

        boolean showExpandedDetails = !ConfigManager.isHudExpandedOnWarning() || actionableWarning;
        if (showExpandedDetails) {
            appendExpandedDetails(entries, profilerManager, snapshot, highestFinding, alertColor, autoFocusEntry != null);
        }

        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        int maxContentWidth = Math.max(160, screenW - 16 - (PADDING * 2));
        int columns = ConfigManager.getHudLayoutMode().columns();
        int cellWidth = getCellWidth(columns, maxContentWidth);
        List<Entry> layoutEntries = normalizeEntriesForColumns(entries, client.textRenderer, columns, cellWidth, maxContentWidth);
        List<Row> rows = buildRows(layoutEntries, columns);
        int contentWidth = Math.min(maxContentWidth, columns == 1 ? cellWidth : (columns * cellWidth) + ((columns - 1) * GAP));
        int width = contentWidth + (PADDING * 2);
        int height = HEADER_HEIGHT + PADDING + (rows.size() * ROW_HEIGHT) + PADDING;
        int borderColor = actionableWarning ? alertColor : BORDER;

        int x = 8;
        int y = 8;
        switch (ConfigManager.getHudPosition()) {
            case TOP_RIGHT -> x = screenW - width - 8;
            case BOTTOM_LEFT -> y = screenH - height - 8;
            case BOTTOM_RIGHT -> {
                x = screenW - width - 8;
                y = screenH - height - 8;
            }
            default -> {
            }
        }

        int backgroundColor = applyHudTransparency(BG);
        int borderFillColor = applyHudTransparency(borderColor);
        int dividerColor = applyHudTransparency(0x443A3F46);
        ctx.fill(x, y, x + width, y + height, backgroundColor);
        ctx.fill(x, y, x + width, y + 1, borderFillColor);
        ctx.fill(x, y, x + 1, y + height, borderFillColor);
        ctx.fill(x + width - 1, y, x + width, y + height, borderFillColor);
        ctx.fill(x, y + height - 1, x + width, y + height, borderFillColor);
        ctx.fill(x, y + HEADER_HEIGHT, x + width, y + HEADER_HEIGHT + 1, dividerColor);

        TextRenderer textRenderer = client.textRenderer;
        ctx.drawText(textRenderer, "Task Manager", x + PADDING, y + 5, actionableWarning ? HEADER : DIM, false);
        String modeText = snapshot.mode().name().replace('_', ' ');
        ctx.drawText(textRenderer, modeText, x + width - PADDING - textRenderer.getWidth(modeText), y + 5, actionableWarning ? alertColor : DIM, false);

        int rowY = y + HEADER_HEIGHT + 6;
        for (Row row : rows) {
            if (row.fullWidth()) {
                drawEntry(ctx, textRenderer, x + PADDING, rowY, contentWidth, row.entries().getFirst(), true);
            } else {
                for (int i = 0; i < row.entries().size(); i++) {
                    int cellX = x + PADDING + i * (cellWidth + GAP);
                    drawEntry(ctx, textRenderer, cellX, rowY, cellWidth, row.entries().get(i), false);
                }
            }
            rowY += ROW_HEIGHT;
        }
    }

    private static void buildCompactEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
        entries.add(new Entry("Frame Budget", frameBudgetText(frame), frameBudgetColor(frame), true));
        entries.add(new Entry("CPU", formatUtilAndTemp(system, true), utilizationColor(system, true), false));
        entries.add(new Entry("GPU", formatUtilAndTemp(system, false), utilizationColor(system, false), false));
        entries.add(new Entry("Memory", displayedMemoryText(memory), memoryColor(memory), false));
        entries.add(new Entry("VRAM", vramText(system), vramColor(system), false));
        if (snapshot.sessionLogging()) {
            entries.add(new Entry("Session", formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L), WARN, false));
        }
    }

    private static void buildPresetFullEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
        entries.add(new Entry("FPS Lows", displayedLowFpsText(frame), HEADER, false));
        entries.add(new Entry("Frame Budget", frameBudgetText(frame), frameBudgetColor(frame), false));
        entries.add(new Entry("Frame", compactFrameText(frame), frameBudgetColor(frame), false));
        entries.add(new Entry("Client Tick", tickText("tick.client", TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0), tickColor(TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0, false), false));
        entries.add(new Entry("Server Tick", tickText("tick.server", TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0), tickColor(TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0, true), false));
        entries.add(new Entry("CPU", formatUtilAndTemp(system, true), utilizationColor(system, true), false));
        entries.add(new Entry("GPU", formatUtilAndTemp(system, false), utilizationColor(system, false), false));
        entries.add(new Entry("Input Latency", inputLatencyText(system), inputLatencyColor(system), false));
        entries.add(new Entry("Memory", displayedMemoryText(memory), memoryColor(memory), false));
        entries.add(new Entry("VRAM", vramText(system), vramColor(system), false));
        entries.add(new Entry("Network", networkText(system), networkColor(system), true));
        entries.add(new Entry("Chunk Activity", chunkActivityText(system), chunkActivityColor(system), true));
        entries.add(new Entry("Entities", worldEntitiesText(snapshot), DIM, false));
        entries.add(new Entry("Chunks", worldChunksText(snapshot), DIM, false));
        entries.add(new Entry("Disk I/O", diskIoText(system), DIM, true));
        if (snapshot.sessionLogging()) {
            entries.add(new Entry("Session", formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L), WARN, false));
        }
    }

    private static void buildCustomEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        if (ConfigManager.isHudShowFps()) {
            entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
            entries.add(new Entry("FPS Lows", displayedLowFpsText(frame), HEADER, false));
        }
        if (ConfigManager.isHudShowFrame()) {
            entries.add(new Entry("Frame", compactFrameText(frame), TEXT, false));
        }
        if (ConfigManager.isHudShowTicks()) {
            entries.add(new Entry("Client Tick", tickText("tick.client", TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0), TEXT, false));
            entries.add(new Entry("Server Tick", tickText("tick.server", TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0), TEXT, false));
        }
        if (ConfigManager.isHudShowUtilization()) {
            entries.add(new Entry("CPU", formatUtilAndTemp(system, true), utilizationColor(system, true), false));
            entries.add(new Entry("GPU", formatUtilAndTemp(system, false), utilizationColor(system, false), false));
            if (ConfigManager.isHudShowLogic()) {
                entries.add(new Entry("Main Logic", shorten(system.mainLogicSummary().replace("Main Logic: ", ""), 36), DIM, true));
            }
            if (ConfigManager.isHudShowBackground()) {
                entries.add(new Entry("Background", shorten(system.backgroundSummary().replace("Background: ", ""), 36), DIM, true));
            }
        }
        if (ConfigManager.isHudShowParallelism()) {
            String parallelText = system.cpuParallelismFlag();
            if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && snapshot.stutterScore() > 10.0) {
                parallelText = "Thread overscheduling";
            }
            entries.add(new Entry("Parallel", parallelText, WARN, true));
        }
        if (ConfigManager.isHudShowFrameBudget()) {
            entries.add(new Entry("Frame Budget", frameBudgetText(frame), frameBudgetColor(frame), true));
        }
        if (ConfigManager.isHudShowMemory()) {
            entries.add(new Entry("Memory", displayedMemoryText(memory), memoryColor(memory), false));
        }
        if (ConfigManager.isHudShowVram()) {
            entries.add(new Entry("VRAM", vramText(system), vramColor(system), false));
        }
        if (ConfigManager.isHudShowNetwork()) {
            entries.add(new Entry("Network", networkText(system), networkColor(system), true));
        }
        if (ConfigManager.isHudShowChunkActivity()) {
            entries.add(new Entry("Chunk Activity", chunkActivityText(system), chunkActivityColor(system), true));
        }
        if (ConfigManager.isHudShowWorld()) {
            entries.add(new Entry("Entities", worldEntitiesText(snapshot), DIM, false));
            entries.add(new Entry("Chunks", worldChunksText(snapshot), DIM, false));
        }
        if (ConfigManager.isHudShowDiskIo()) {
            entries.add(new Entry("Disk I/O", diskIoText(system), DIM, true));
        }
        if (ConfigManager.isHudShowInputLatency()) {
            entries.add(new Entry("Input Latency", inputLatencyText(system), inputLatencyColor(system), false));
        }
        if (ConfigManager.isHudShowSession() && snapshot.sessionLogging()) {
            entries.add(new Entry("Session", formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L), WARN, false));
        }
    }

    private static void appendExpandedDetails(List<Entry> entries, ProfilerManager profilerManager, ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, int alertColor, boolean hasAutoFocusRow) {
        long warningCount = profilerManager.getLatestRuleFindings().stream().filter(finding -> severityRank(finding.severity()) >= 1).count();
        if (warningCount > 0 && !hasAutoFocusRow) {
            entries.add(new Entry("Alert", warningCount + " active | stutter " + format1(snapshot.stutterScore()), alertColor, true));
            if (highestFinding != null) {
                entries.add(new Entry("Why", shorten(highestFinding.category() + ": " + highestFinding.message(), 64), alertColor, true));
            }
        }
        if (!snapshot.spikes().isEmpty()) {
            ProfilerManager.SpikeCapture latestSpike = snapshot.spikes().get(0);
            entries.add(new Entry("Spike", format1(latestSpike.frameDurationMs()) + " ms | " + shorten(latestSpike.likelyBottleneck(), 32), WARN, true));
        }
        String bottleneck = profilerManager.getCurrentBottleneckLabel();
        if (bottleneck != null && !bottleneck.isBlank()) {
            entries.add(new Entry("Focus", shorten(bottleneck, 48), highestFinding != null ? alertColor : DIM, true));
        }
    }

    private static boolean shouldRenderHud(ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, double latestFrameMs, long recentSpikeAge) {
        return switch (ConfigManager.getHudTriggerMode()) {
            case ALWAYS -> true;
            case SPIKES_ONLY -> latestFrameMs >= 40.0 || recentSpikeAge <= 5000L || snapshot.stutterScore() >= 10.0 || (highestFinding != null && severityRank(highestFinding.severity()) >= 2);
            case WARNINGS_ONLY -> highestFinding != null && severityRank(highestFinding.severity()) >= 1;
        };
    }

    private static boolean hasActionableWarning(ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, double latestFrameMs, long recentSpikeAge) {
        return latestFrameMs >= 40.0
                || recentSpikeAge <= 5000L
                || snapshot.stutterScore() >= 10.0
                || (highestFinding != null && severityRank(highestFinding.severity()) >= 1);
    }

    private static ProfilerManager.RuleFinding highestFinding(ProfilerManager profilerManager) {
        return profilerManager.getLatestRuleFindings().stream()
                .sorted((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())))
                .findFirst()
                .orElse(null);
    }

    private static List<Row> buildRows(List<Entry> entries, int columns) {
        List<Row> rows = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.fullWidth()) {
                if (!current.isEmpty()) {
                    rows.add(new Row(List.copyOf(current), false));
                    current.clear();
                }
                rows.add(new Row(List.of(entry), true));
                continue;
            }
            current.add(entry);
            if (current.size() == columns) {
                rows.add(new Row(List.copyOf(current), false));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            rows.add(new Row(List.copyOf(current), false));
        }
        return rows;
    }

    private static int getCellWidth(int columns, int maxContentWidth) {
        int preferredWidth = switch (columns) {
            case 1 -> SINGLE_COLUMN_WIDTH;
            case 2 -> TWO_COLUMN_WIDTH;
            default -> THREE_COLUMN_WIDTH;
        };
        if (columns <= 1) {
            return Math.min(preferredWidth, maxContentWidth);
        }
        int available = Math.max(96, maxContentWidth - ((columns - 1) * GAP));
        return Math.max(96, Math.min(preferredWidth, available / columns));
    }

    private static List<Entry> normalizeEntriesForColumns(List<Entry> entries, TextRenderer textRenderer, int columns, int cellWidth, int contentWidth) {
        if (columns <= 1) {
            return entries;
        }
        List<Entry> normalized = new ArrayList<>(entries.size());
        int fullWidthLimit = Math.max(80, contentWidth - VALUE_RIGHT_PADDING);
        int cellLimit = Math.max(64, cellWidth - VALUE_RIGHT_PADDING);
        for (Entry entry : entries) {
            if (entry.fullWidth()) {
                normalized.add(entry);
                continue;
            }
            int estimatedWidth = estimateEntryWidth(textRenderer, entry, false);
            if (estimatedWidth > cellLimit && estimateEntryWidth(textRenderer, entry, true) <= fullWidthLimit) {
                normalized.add(new Entry(entry.label(), entry.value(), entry.color(), true));
                continue;
            }
            normalized.add(entry);
        }
        return normalized;
    }

    private static int estimateEntryWidth(TextRenderer textRenderer, Entry entry, boolean fullWidth) {
        int preferredLabelWidth = Math.max(LABEL_WIDTH, textRenderer.getWidth(entry.label()) + LABEL_VALUE_GAP);
        int maxLabelWidth = Math.max(LABEL_WIDTH, fullWidth ? 120 : 92);
        int labelWidth = Math.min(preferredLabelWidth, maxLabelWidth);
        return labelWidth + textRenderer.getWidth(entry.value()) + VALUE_RIGHT_PADDING;
    }

    private static void drawEntry(DrawContext ctx, TextRenderer textRenderer, int x, int y, int width, Entry entry, boolean fullWidth) {
        int labelColor = entry.color() == CRITICAL ? HEADER : DIM;
        int maxLabelWidth = Math.max(LABEL_WIDTH, fullWidth ? width / 3 : width / 2);
        String label = trimWithEllipsis(textRenderer, entry.label(), maxLabelWidth);
        ctx.drawText(textRenderer, label, x, y, labelColor, false);
        int preferredLabelWidth = Math.max(LABEL_WIDTH, textRenderer.getWidth(label) + LABEL_VALUE_GAP);
        int labelWidth = Math.min(preferredLabelWidth, maxLabelWidth);
        int valueX = x + labelWidth;
        int valueWidth = Math.max(24, width - (valueX - x) - VALUE_RIGHT_PADDING);
        String value = trimWithEllipsis(textRenderer, entry.value(), valueWidth);
        ctx.drawText(textRenderer, value, valueX, y, entry.color(), false);
    }

    private static String trimWithEllipsis(TextRenderer textRenderer, String value, int width) {
        if (value == null || value.isEmpty() || width <= 0) {
            return "";
        }
        if (textRenderer.getWidth(value) <= width) {
            return value;
        }
        String ellipsis = "...";
        int ellipsisWidth = textRenderer.getWidth(ellipsis);
        if (ellipsisWidth >= width) {
            return textRenderer.trimToWidth(ellipsis, width);
        }
        return textRenderer.trimToWidth(value, Math.max(0, width - ellipsisWidth)) + ellipsis;
    }

    private static String displayedFpsText(FrameTimelineProfiler frame) {
        return displayedFpsText;
    }

    private static String displayedLowFpsText(FrameTimelineProfiler frame) {
        return displayedLowFpsText;
    }

    private static String displayedMemoryText(MemoryProfiler.Snapshot memory) {
        return displayedMemoryText;
    }

    private static void refreshDisplayedFps(FrameTimelineProfiler frame) {
        long now = System.currentTimeMillis();
        if (lastDisplayedFpsUpdateAtMillis != 0L && now - lastDisplayedFpsUpdateAtMillis < ConfigManager.getMetricsUpdateIntervalMs()) {
            return;
        }
        lastDisplayedFpsUpdateAtMillis = now;
        displayedFpsText = format0(frame.getCurrentFps()) + " now | " + format0(frame.getAverageFps()) + " avg" + rateSuffix("fps", frame.getCurrentFps(), now, "fps/s", ConfigManager.isHudShowFpsRateOfChange());
        displayedLowFpsText = "1% " + format0(frame.getOnePercentLowFps()) + " | 0.1% " + format0(frame.getPointOnePercentLowFps());
    }

    private static void refreshDisplayedMemory(MemoryProfiler.Snapshot memory) {
        long now = System.currentTimeMillis();
        if (lastDisplayedMemoryUpdateAtMillis != 0L && now - lastDisplayedMemoryUpdateAtMillis < ConfigManager.getMetricsUpdateIntervalMs()) {
            return;
        }
        lastDisplayedMemoryUpdateAtMillis = now;
        displayedMemoryText = formatMemoryDisplay(memory, now);
    }

    private static String compactFrameText(FrameTimelineProfiler frame) {
        double avgFrameMs = frame.getAverageFrameNs() / 1_000_000.0;
        return format1(avgFrameMs) + " avg | " + format1(frame.getMaxFrameNs() / 1_000_000.0) + " max" + rateSuffix("frame", avgFrameMs, System.currentTimeMillis(), "ms/s", ConfigManager.isHudShowFrameRateOfChange());
    }

    private static String formatMemoryDisplay(MemoryProfiler.Snapshot memory, long now) {
        long usedBytes = Math.max(0L, memory.heapUsedBytes());
        long maxBytes = Math.max(usedBytes, memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes());
        memoryRateSamples.addLast(new MemoryRateSample(now, usedBytes));
        while (memoryRateSamples.size() > MAX_MEMORY_RATE_SAMPLES) {
            memoryRateSamples.removeFirst();
        }
        long cutoff = now - 4000L;
        while (memoryRateSamples.size() > 2 && memoryRateSamples.peekFirst() != null && memoryRateSamples.peekFirst().capturedAtMillis() < cutoff) {
            memoryRateSamples.removeFirst();
        }

        String base = formatMegabytes(usedBytes) + "/" + formatMegabytes(maxBytes) + " MB";
        if (!ConfigManager.isHudShowMemoryAllocationRate()) {
            return base;
        }
        double rateMbPerSecond = computeAverageAllocationRateMbPerSecond();
        if ((Double.isNaN(rateMbPerSecond) || Math.abs(rateMbPerSecond) < 0.05) && !ConfigManager.isHudShowZeroRateOfChange()) {
            return base;
        }
        return base + " (" + signedRateText(rateMbPerSecond) + ")";
    }

    private static String formatUtilAndTemp(SystemMetricsProfiler.Snapshot system, boolean cpu) {
        long now = System.currentTimeMillis();
        String sensorKey = cpu ? "cpu" : "gpu";
        double loadPercent = cpu ? system.cpuCoreLoadPercent() : system.gpuCoreLoadPercent();
        double temperatureC = cpu ? system.cpuTemperatureC() : system.gpuTemperatureC();
        String load = loadPercent >= 0.0 ? format0(loadPercent) + "%" : "n/a";
        String loadRateSuffix = ConfigManager.isHudShowUtilizationRateOfChange()
                ? heldSensorRateSuffix(sensorKey + ".load", loadPercent, now, "%/s")
                : "";
        if (!ConfigManager.isHudShowTemperatures()) {
            return load + appendSuffix(loadRateSuffix);
        }
        String temp = temperatureC >= 0.0 ? format0(temperatureC) + "C" : "n/a";
        if (!ConfigManager.isHudShowUtilizationRateOfChange()) {
            return load + " / " + temp;
        }
        String tempRateSuffix = temperatureC < 0.0 ? "" : heldSensorRateSuffix(sensorKey + ".temp", temperatureC, now, "C/s");
        String combined = joinRateParts(loadRateSuffix, tempRateSuffix);
        return load + " / " + temp + appendSuffix(combined);
    }

    private static Entry buildAutoFocusEntry(ProfilerManager profilerManager, ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, double latestFrameMs, int alertColor) {
        if (highestFinding != null) {
            return new Entry("Focus", shorten(highestFinding.category() + ": " + highestFinding.message(), 72), alertColor, true);
        }
        if (!snapshot.spikes().isEmpty()) {
            ProfilerManager.SpikeCapture latestSpike = snapshot.spikes().get(0);
            return new Entry("Focus", "Spike " + format1(latestSpike.frameDurationMs()) + " ms | " + shorten(latestSpike.likelyBottleneck(), 40), alertColor, true);
        }
        String bottleneck = profilerManager.getCurrentBottleneckLabel();
        if (bottleneck != null && !bottleneck.isBlank()) {
            return new Entry("Focus", shorten(bottleneck, 72), alertColor, true);
        }
        if (latestFrameMs > 16.7) {
            return new Entry("Focus", "Frame budget exceeded by " + format1(Math.max(0.0, latestFrameMs - 16.7)) + " ms", alertColor, true);
        }
        return null;
    }

    private static String frameBudgetText(FrameTimelineProfiler frame) {
        long now = System.currentTimeMillis();
        double currentFrameMs = frame.getLatestFrameNs() / 1_000_000.0;
        double overBudgetMs = currentFrameMs - 16.7;
        String budgetState = overBudgetMs > 0.0 ? "+" + format1(overBudgetMs) + " over" : format1(Math.abs(overBudgetMs)) + " headroom";
        return format1(currentFrameMs) + "/16.7 ms | " + budgetState + rateSuffix("frame.budget", currentFrameMs, now, "ms/s", ConfigManager.isHudShowFrameRateOfChange());
    }

    private static String vramText(SystemMetricsProfiler.Snapshot system) {
        long now = System.currentTimeMillis();
        if (system.vramUsedBytes() < 0 || system.vramTotalBytes() <= 0) {
            return "n/a";
        }
        String base = compactBytes(system.vramUsedBytes()) + "/" + compactBytes(system.vramTotalBytes());
        if (system.vramPagingActive() && system.vramPagingBytes() > 0) {
            base += " | paging " + compactBytes(system.vramPagingBytes());
        }
        if (!ConfigManager.isHudShowVramRateOfChange()) {
            return base;
        }
        return base + appendSuffix(optionalByteRateSuffix("vram.used", system.vramUsedBytes(), now, "/s"));
    }

    private static String networkText(SystemMetricsProfiler.Snapshot system) {
        long now = System.currentTimeMillis();
        String down = compactIo(system.bytesReceivedPerSecond());
        String up = compactIo(system.bytesSentPerSecond());
        String base = "D " + down + " | U " + up;
        if (!ConfigManager.isHudShowNetworkRateOfChange()) {
            return base;
        }
        String downRate = optionalByteRateSuffix("network.down", system.bytesReceivedPerSecond(), now, "/s/s");
        String upRate = optionalByteRateSuffix("network.up", system.bytesSentPerSecond(), now, "/s/s");
        return base + appendSuffix(joinRateParts(downRate, upRate));
    }

    private static String chunkActivityText(SystemMetricsProfiler.Snapshot system) {
        long now = System.currentTimeMillis();
        String base = "G " + system.chunksGenerating() + " | M " + system.chunksMeshing() + " | U " + system.chunksUploading();
        if (!ConfigManager.isHudShowChunkActivityRateOfChange()) {
            return base;
        }
        String generatingRate = optionalRateSuffix("chunks.gen", system.chunksGenerating(), now, "/s");
        String meshingRate = optionalRateSuffix("chunks.mesh", system.chunksMeshing(), now, "/s");
        String uploadRate = optionalRateSuffix("chunks.upload", system.chunksUploading(), now, "/s");
        String combined = joinRateParts(joinRateParts(generatingRate, meshingRate), uploadRate);
        return base + appendSuffix(combined);
    }

    private static String inputLatencyText(SystemMetricsProfiler.Snapshot system) {
        double latencyMs = system.mouseInputLatencyMs();
        if (latencyMs < 0.0) {
            return "n/a";
        }
        return format1(latencyMs) + " ms" + rateSuffix("input.latency", latencyMs, System.currentTimeMillis(), "ms/s", ConfigManager.isHudShowInputLatencyRateOfChange());
    }

    private static int frameBudgetColor(FrameTimelineProfiler frame) {
        return budgetColorForFrame(frame.getLatestFrameNs() / 1_000_000.0);
    }

    private static int tickColor(double millis, boolean server) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return TEXT;
        }
        double warnThreshold = server ? 30.0 : 16.7;
        double errorThreshold = server ? 50.0 : 25.0;
        double criticalThreshold = server ? 75.0 : 40.0;
        return severityColorForValue(millis, warnThreshold, errorThreshold, criticalThreshold, TEXT);
    }

    private static int utilizationColor(SystemMetricsProfiler.Snapshot system, boolean cpu) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return ACCENT;
        }
        double load = cpu ? system.cpuCoreLoadPercent() : system.gpuCoreLoadPercent();
        double temperature = cpu ? system.cpuTemperatureC() : system.gpuTemperatureC();
        int loadColor = severityColorForValue(load, 80.0, 92.0, 98.0, ACCENT);
        int tempColor = severityColorForValue(temperature, 75.0, 85.0, 92.0, ACCENT);
        return maxSeverityColor(loadColor, tempColor, ACCENT);
    }

    private static int memoryColor(MemoryProfiler.Snapshot memory) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        long maxBytes = memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes();
        double usage = maxBytes > 0 ? (memory.heapUsedBytes() * 100.0 / maxBytes) : 0.0;
        return severityColorForValue(usage, 75.0, 88.0, 96.0, DIM);
    }

    private static int vramColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        if (system.vramPagingActive()) {
            return ERROR;
        }
        double usage = system.vramTotalBytes() > 0 ? (system.vramUsedBytes() * 100.0 / system.vramTotalBytes()) : 0.0;
        return severityColorForValue(usage, 75.0, 88.0, 96.0, DIM);
    }

    private static int networkColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        double packetLatency = system.packetProcessingLatencyMs();
        return severityColorForValue(packetLatency, 30.0, 75.0, 140.0, DIM);
    }

    private static int chunkActivityColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        double total = system.chunksGenerating() + system.chunksMeshing() + system.chunksUploading();
        return severityColorForValue(total, 2.0, 5.0, 8.0, DIM);
    }

    private static int inputLatencyColor(SystemMetricsProfiler.Snapshot system) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return DIM;
        }
        return severityColorForValue(system.mouseInputLatencyMs(), 20.0, 40.0, 80.0, DIM);
    }

    private static int budgetColorForFrame(double frameMs) {
        if (!ConfigManager.isHudBudgetColorMode()) {
            return TEXT;
        }
        return severityColorForValue(frameMs, 16.7, 25.0, 40.0, TEXT);
    }

    private static int severityColorForValue(double value, double warnThreshold, double errorThreshold, double criticalThreshold, int normalColor) {
        if (!Double.isFinite(value) || value < 0.0) {
            return normalColor;
        }
        if (value >= criticalThreshold) {
            return CRITICAL;
        }
        if (value >= errorThreshold) {
            return ERROR;
        }
        if (value >= warnThreshold) {
            return WARN;
        }
        return normalColor;
    }

    private static int maxSeverityColor(int first, int second, int normalColor) {
        return severityRankForColor(first, normalColor) >= severityRankForColor(second, normalColor) ? first : second;
    }

    private static int severityRankForColor(int color, int normalColor) {
        if (color == CRITICAL) return 3;
        if (color == ERROR) return 2;
        if (color == WARN) return 1;
        return color == normalColor ? 0 : 0;
    }

    private static String optionalByteRateSuffix(String key, long currentValue, long now, String units) {
        RateSample previous = rateSamples.get(key);
        if (previous == null) {
            String initial = ConfigManager.isHudShowZeroRateOfChange() ? "~0 B" + units : "";
            rateSamples.put(key, new RateSample(now, currentValue, initial));
            return initial;
        }
        long elapsedMillis = now - previous.capturedAtMillis();
        if (elapsedMillis < 45L) {
            return previous.displaySuffix();
        }
        double deltaPerSecond = (currentValue - previous.value()) * 1000.0 / Math.max(1L, elapsedMillis);
        String suffix = ((!Double.isFinite(deltaPerSecond) || Math.abs(deltaPerSecond) < 1.0) && !ConfigManager.isHudShowZeroRateOfChange())
                ? ""
                : signedByteRate(deltaPerSecond, units);
        rateSamples.put(key, new RateSample(now, currentValue, suffix));
        return suffix;
    }

    private static String signedByteRate(double value, String units) {
        if (!Double.isFinite(value) || Math.abs(value) < 1.0) {
            return "~0 B" + units;
        }
        return (value > 0.0 ? "+" : "-") + compactBytes(Math.round(Math.abs(value))) + units;
    }

    private static String compactBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return format1(bytes / 1024.0) + " KB";
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return format1(bytes / (1024.0 * 1024.0)) + " MB";
        }
        return format1(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB";
    }

    private static String millisText(double millis) {
        return format1(millis) + " ms";
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return pad2(minutes) + ":" + pad2(seconds);
    }

    private static String pad2(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private static String format0(double value) {
        return Long.toString(Math.round(value));
    }

    private static String format1(double value) {
        long scaled = Math.round(value * 10.0);
        long whole = scaled / 10L;
        long decimal = Math.abs(scaled % 10L);
        return whole + "." + decimal;
    }

    private static String formatMegabytes(long bytes) {
        return format0(bytes / (1024.0 * 1024.0));
    }

    private static double computeAverageAllocationRateMbPerSecond() {
        if (memoryRateSamples.size() < 2) {
            return Double.NaN;
        }
        MemoryRateSample first = memoryRateSamples.peekFirst();
        MemoryRateSample last = memoryRateSamples.peekLast();
        if (first == null || last == null) {
            return Double.NaN;
        }
        long elapsedMillis = Math.max(1L, last.capturedAtMillis() - first.capturedAtMillis());
        double deltaMb = (last.usedBytes() - first.usedBytes()) / (1024.0 * 1024.0);
        return deltaMb * 1000.0 / elapsedMillis;
    }

    private static String signedRateText(double rateMbPerSecond) {
        String prefix = rateMbPerSecond > 0.0 ? "+" : "-";
        return prefix + format1(Math.abs(rateMbPerSecond)) + " MB/s";
    }

    private static String signedSensorRate(double value, String units) {
        if (!Double.isFinite(value) || Math.abs(value) < 0.05) {
            return "~0 " + units;
        }
        return (value > 0.0 ? "+" : "-") + format1(Math.abs(value)) + " " + units;
    }

    private static String worldEntitiesText(ProfilerManager.ProfilerSnapshot snapshot) {
        long now = System.currentTimeMillis();
        return Integer.toString(snapshot.entityCounts().totalEntities()) + rateSuffix("world.entities", snapshot.entityCounts().totalEntities(), now, "/s", ConfigManager.isHudShowWorldRateOfChange());
    }

    private static String worldChunksText(ProfilerManager.ProfilerSnapshot snapshot) {
        long now = System.currentTimeMillis();
        String loaded = Long.toString(snapshot.chunkCounts().loadedChunks());
        String rendered = Long.toString(snapshot.chunkCounts().renderedChunks());
        if (!ConfigManager.isHudShowWorldRateOfChange()) {
            return loaded + "/" + rendered;
        }
        String loadedRate = optionalRateSuffix("world.chunks.loaded", snapshot.chunkCounts().loadedChunks(), now, "/s");
        String renderedRate = optionalRateSuffix("world.chunks.rendered", snapshot.chunkCounts().renderedChunks(), now, "/s");
        String combined = joinRateParts(loadedRate, renderedRate);
        return loaded + "/" + rendered + appendSuffix(combined);
    }

    private static String diskIoText(SystemMetricsProfiler.Snapshot system) {
        long now = System.currentTimeMillis();
        String read = compactIo(system.diskReadBytesPerSecond());
        String write = compactIo(system.diskWriteBytesPerSecond());
        if (!ConfigManager.isHudShowDiskIoRateOfChange()) {
            return "R " + read + " | W " + write;
        }
        String readRate = optionalRateSuffix("disk.read", system.diskReadBytesPerSecond(), now, "B/s/s");
        String writeRate = optionalRateSuffix("disk.write", system.diskWriteBytesPerSecond(), now, "B/s/s");
        String combined = joinRateParts(readRate, writeRate);
        return "R " + read + " | W " + write + appendSuffix(combined);
    }

    private static String tickText(String key, double millis) {
        return format1(millis) + " ms" + rateSuffix(key, millis, System.currentTimeMillis(), "ms/s", ConfigManager.isHudShowTickRateOfChange());
    }

    private static String rateSuffix(String key, double currentValue, long now, String units, boolean enabled) {
        if (!enabled) {
            return "";
        }
        String rate = optionalRateSuffix(key, currentValue, now, units);
        return appendSuffix(rate);
    }

    private static String optionalRateSuffix(String key, double currentValue, long now, String units) {
        RateSample previous = rateSamples.get(key);
        if (previous == null) {
            String initial = ConfigManager.isHudShowZeroRateOfChange() ? "~0 " + units : "";
            rateSamples.put(key, new RateSample(now, currentValue, initial));
            return initial;
        }

        long elapsedMillis = now - previous.capturedAtMillis();
        if (elapsedMillis < 45L) {
            return previous.displaySuffix();
        }

        double deltaPerSecond = (currentValue - previous.value()) * 1000.0 / Math.max(1L, elapsedMillis);
        String suffix = ((!Double.isFinite(deltaPerSecond) || Math.abs(deltaPerSecond) < 0.05) && !ConfigManager.isHudShowZeroRateOfChange())
                ? ""
                : signedDynamicRate(deltaPerSecond, units);
        rateSamples.put(key, new RateSample(now, currentValue, suffix));
        return suffix;
    }

    private static String signedDynamicRate(double value, String units) {
        if (!Double.isFinite(value) || Math.abs(value) < 0.05) {
            return "~0 " + units;
        }
        return (value > 0.0 ? "+" : "-") + format1(Math.abs(value)) + " " + units;
    }

    private static String heldSensorRateSuffix(String key, double currentValue, long now, String units) {
        SensorRateSample previous = sensorRateSamples.get(key);
        if (!Double.isFinite(currentValue) || currentValue < 0.0) {
            if (previous == null) {
                return ConfigManager.isHudShowZeroRateOfChange() ? "~0 " + units : "";
            }
            return previous.displaySuffix();
        }
        if (previous == null) {
            String initial = ConfigManager.isHudShowZeroRateOfChange() ? "~0 " + units : "";
            sensorRateSamples.put(key, new SensorRateSample(now, currentValue, now, currentValue, initial));
            return initial;
        }

        if (Math.abs(currentValue - previous.lastObservedValue()) < 0.05) {
            sensorRateSamples.put(key, previous.withObserved(now, currentValue));
            return previous.displaySuffix();
        }

        long elapsedMillis = Math.max(1L, now - previous.lastChangeAtMillis());
        double deltaPerSecond = (currentValue - previous.lastChangeValue()) * 1000.0 / elapsedMillis;
        String suffix = ((!Double.isFinite(deltaPerSecond) || Math.abs(deltaPerSecond) < 0.05) && !ConfigManager.isHudShowZeroRateOfChange())
                ? ""
                : signedDynamicRate(deltaPerSecond, units);
        sensorRateSamples.put(key, new SensorRateSample(now, currentValue, now, currentValue, suffix));
        return suffix;
    }

    private static String joinRateParts(String first, String second) {
        if ((first == null || first.isBlank()) && (second == null || second.isBlank())) {
            return "";
        }
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " / " + second;
    }

    private static String appendSuffix(String suffix) {
        return suffix == null || suffix.isBlank() ? "" : " (" + suffix + ")";
    }

    private static String compactIo(long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return "n/a";
        }
        if (bytesPerSecond >= 1024L * 1024L) {
            return format1(bytesPerSecond / (1024.0 * 1024.0)) + " MB/s";
        }
        if (bytesPerSecond >= 1024L) {
            return format1(bytesPerSecond / 1024.0) + " KB/s";
        }
        return bytesPerSecond + " B/s";
    }

    private static String shorten(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "n/a";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "info" : severity.toLowerCase()) {
            case "critical" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    private static int severityColor(String severity, boolean actionableWarning) {
        return switch (severityRank(severity)) {
            case 3 -> CRITICAL;
            case 2 -> ERROR;
            case 1 -> WARN;
            default -> actionableWarning ? WARN : DIM;
        };
    }

    private static int applyHudTransparency(int color) {
        int alpha = (color >>> 24) & 0xFF;
        int scaledAlpha = alpha * ConfigManager.getHudTransparencyPercent() / 100;
        return (color & 0x00FFFFFF) | (scaledAlpha << 24);
    }

    private record Entry(String label, String value, int color, boolean fullWidth) {
    }

    private record Row(List<Entry> entries, boolean fullWidth) {
    }

    private record MemoryRateSample(long capturedAtMillis, long usedBytes) {
    }

    private record RateSample(long capturedAtMillis, double value, String displaySuffix) {
    }

    private record SensorRateSample(long lastObservedAtMillis, double lastObservedValue, long lastChangeAtMillis, double lastChangeValue, String displaySuffix) {
        private SensorRateSample withObserved(long observedAtMillis, double observedValue) {
            return new SensorRateSample(observedAtMillis, observedValue, lastChangeAtMillis, lastChangeValue, displaySuffix);
        }
    }
}
