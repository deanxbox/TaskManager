package wueffi.taskmanager.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import wueffi.taskmanager.client.util.ConfigManager;

import java.util.ArrayList;
import java.util.List;

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
    private static long lastDisplayedFpsUpdateAtMillis;
    private static String displayedFpsText = "0 now | 0 avg";
    private static String displayedLowFpsText = "1% 0 | 0.1% 0";

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
                buildCompactEntries(entries, frame, memory, system);
            } else {
                buildPresetFullEntries(entries, snapshot, frame, memory, system);
            }
        } else {
            buildCustomEntries(entries, snapshot, frame, memory, system);
        }

        boolean showExpandedDetails = !ConfigManager.isHudExpandedOnWarning() || actionableWarning;
        if (showExpandedDetails) {
            appendExpandedDetails(entries, profilerManager, snapshot, highestFinding, alertColor);
        }

        int requestedColumns = ConfigManager.getHudLayoutMode().columns();
        int columns = requestedColumns;
        if (requestedColumns > 1 && entries.stream().filter(Entry::fullWidth).count() >= 2) {
            columns = 2;
        }
        int cellWidth = switch (columns) {
            case 1 -> SINGLE_COLUMN_WIDTH;
            case 2 -> TWO_COLUMN_WIDTH;
            default -> THREE_COLUMN_WIDTH;
        };
        List<Row> rows = buildRows(entries, columns);
        int contentWidth = columns == 1 ? cellWidth : (columns * cellWidth) + ((columns - 1) * GAP);
        int width = contentWidth + (PADDING * 2);
        int height = HEADER_HEIGHT + PADDING + (rows.size() * ROW_HEIGHT) + PADDING;
        int borderColor = actionableWarning ? alertColor : BORDER;

        int x = 8;
        int y = 8;
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
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

    private static void buildCompactEntries(List<Entry> entries, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
        entries.add(new Entry("Frame", compactFrameText(frame), TEXT, false));
        entries.add(new Entry("CPU", formatUtilAndTemp(system.cpuCoreLoadPercent(), system.cpuTemperatureC()), ACCENT, false));
        entries.add(new Entry("GPU", formatUtilAndTemp(system.gpuCoreLoadPercent(), system.gpuTemperatureC()), ACCENT, false));
        entries.add(new Entry("Memory", memoryText(memory), DIM, false));
    }

    private static void buildPresetFullEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
        entries.add(new Entry("Lows", displayedLowFpsText(frame), HEADER, false));
        entries.add(new Entry("Frame", compactFrameText(frame), TEXT, false));
        entries.add(new Entry("Client", millisText(TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0), TEXT, false));
        entries.add(new Entry("Server", millisText(TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0), TEXT, false));
        entries.add(new Entry("CPU", formatUtilAndTemp(system.cpuCoreLoadPercent(), system.cpuTemperatureC()), ACCENT, false));
        entries.add(new Entry("GPU", formatUtilAndTemp(system.gpuCoreLoadPercent(), system.gpuTemperatureC()), ACCENT, false));
        entries.add(new Entry("Memory", memoryText(memory), DIM, false));
        entries.add(new Entry("Entities", Integer.toString(snapshot.entityCounts().totalEntities()), DIM, false));
        entries.add(new Entry("Chunks", snapshot.chunkCounts().loadedChunks() + "/" + snapshot.chunkCounts().renderedChunks(), DIM, false));
        if (snapshot.sessionLogging()) {
            entries.add(new Entry("Session", formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L), WARN, false));
        }
    }

    private static void buildCustomEntries(List<Entry> entries, ProfilerManager.ProfilerSnapshot snapshot, FrameTimelineProfiler frame, MemoryProfiler.Snapshot memory, SystemMetricsProfiler.Snapshot system) {
        if (ConfigManager.isHudShowFps()) {
            entries.add(new Entry("FPS", displayedFpsText(frame), HEADER, false));
            entries.add(new Entry("Lows", displayedLowFpsText(frame), HEADER, false));
        }
        if (ConfigManager.isHudShowFrame()) {
            entries.add(new Entry("Frame", compactFrameText(frame), TEXT, false));
        }
        if (ConfigManager.isHudShowTicks()) {
            entries.add(new Entry("Client", millisText(TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0), TEXT, false));
            entries.add(new Entry("Server", millisText(TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0), TEXT, false));
        }
        if (ConfigManager.isHudShowUtilization()) {
            entries.add(new Entry("CPU", formatUtilAndTemp(system.cpuCoreLoadPercent(), system.cpuTemperatureC()), ACCENT, false));
            entries.add(new Entry("GPU", formatUtilAndTemp(system.gpuCoreLoadPercent(), system.gpuTemperatureC()), ACCENT, false));
            entries.add(new Entry("Logic", shorten(system.mainLogicSummary().replace("Main Logic: ", ""), 30), DIM, false));
            entries.add(new Entry("Bg", shorten(system.backgroundSummary().replace("Background: ", ""), 30), DIM, false));
        }
        if (ConfigManager.isHudShowParallelism()) {
            String parallelText = system.cpuParallelismFlag();
            if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && snapshot.stutterScore() > 10.0) {
                parallelText = "Thread overscheduling";
            }
            entries.add(new Entry("Parallel", parallelText, WARN, true));
        }
        if (ConfigManager.isHudShowMemory()) {
            entries.add(new Entry("Memory", memoryText(memory), DIM, false));
        }
        if (ConfigManager.isHudShowWorld()) {
            entries.add(new Entry("Entities", Integer.toString(snapshot.entityCounts().totalEntities()), DIM, false));
            entries.add(new Entry("Chunks", snapshot.chunkCounts().loadedChunks() + "/" + snapshot.chunkCounts().renderedChunks(), DIM, false));
        }
        if (ConfigManager.isHudShowSession() && snapshot.sessionLogging()) {
            entries.add(new Entry("Session", formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L), WARN, false));
        }
    }

    private static void appendExpandedDetails(List<Entry> entries, ProfilerManager profilerManager, ProfilerManager.ProfilerSnapshot snapshot, ProfilerManager.RuleFinding highestFinding, int alertColor) {
        long warningCount = profilerManager.getLatestRuleFindings().stream().filter(finding -> severityRank(finding.severity()) >= 1).count();
        if (warningCount > 0) {
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

    private static void drawEntry(DrawContext ctx, TextRenderer textRenderer, int x, int y, int width, Entry entry, boolean fullWidth) {
        int labelColor = entry.color() == CRITICAL ? HEADER : DIM;
        ctx.drawText(textRenderer, entry.label(), x, y, labelColor, false);
        int valueX = x + (fullWidth ? 70 : LABEL_WIDTH);
        int valueWidth = Math.max(24, width - (valueX - x));
        String value = textRenderer.trimToWidth(entry.value(), valueWidth);
        ctx.drawText(textRenderer, value, valueX, y, entry.color(), false);
    }

    private static String displayedFpsText(FrameTimelineProfiler frame) {
        refreshDisplayedFps(frame);
        return displayedFpsText;
    }

    private static String displayedLowFpsText(FrameTimelineProfiler frame) {
        refreshDisplayedFps(frame);
        return displayedLowFpsText;
    }

    private static void refreshDisplayedFps(FrameTimelineProfiler frame) {
        long now = System.currentTimeMillis();
        if (now - lastDisplayedFpsUpdateAtMillis < ConfigManager.getHudFpsDisplayDelayMs()) {
            return;
        }
        lastDisplayedFpsUpdateAtMillis = now;
        displayedFpsText = format0(frame.getCurrentFps()) + " now | " + format0(frame.getAverageFps()) + " avg";
        displayedLowFpsText = "1% " + format0(frame.getOnePercentLowFps()) + " | 0.1% " + format0(frame.getPointOnePercentLowFps());
    }

    private static String compactFrameText(FrameTimelineProfiler frame) {
        return format1(frame.getAverageFrameNs() / 1_000_000.0) + " avg | " + format1(frame.getMaxFrameNs() / 1_000_000.0) + " max";
    }

    private static String memoryText(MemoryProfiler.Snapshot memory) {
        double used = memory.heapUsedBytes() / (1024.0 * 1024.0);
        double max = (memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes()) / (1024.0 * 1024.0);
        return format0(used) + "/" + format0(max) + " MB";
    }

    private static String formatUtilAndTemp(double loadPercent, double temperatureC) {
        String load = loadPercent >= 0.0 ? format0(loadPercent) + "%" : "n/a";
        if (!ConfigManager.isHudShowTemperatures()) {
            return load;
        }
        String temp = temperatureC >= 0.0 ? format0(temperatureC) + "C" : "n/a";
        return load + " / " + temp;
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
}
