package wueffi.taskmanager.client;

import net.minecraft.client.MinecraftClient;
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

        ProfilerManager.ProfilerSnapshot snapshot = ProfilerManager.getInstance().getCurrentSnapshot();
        ConfigManager.HudTriggerMode triggerMode = ConfigManager.getHudTriggerMode();
        ProfilerManager.RuleFinding highestFinding = ProfilerManager.getInstance().getLatestRuleFindings().stream()
                .sorted((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())))
                .findFirst()
                .orElse(null);
        if (triggerMode == ConfigManager.HudTriggerMode.SPIKES_ONLY) {
            double latestFrameMs = FrameTimelineProfiler.getInstance().getLatestFrameNs() / 1_000_000.0;
            long recentSpikeAge = snapshot.spikes().isEmpty() ? Long.MAX_VALUE : Math.max(0L, System.currentTimeMillis() - snapshot.spikes().get(0).capturedAtEpochMillis());
            boolean shouldShow = latestFrameMs >= 40.0 || recentSpikeAge <= 5000L || snapshot.stutterScore() >= 10.0 || (highestFinding != null && severityRank(highestFinding.severity()) >= 2);
            if (!shouldShow) {
                return;
            }
        } else if (triggerMode == ConfigManager.HudTriggerMode.WARNINGS_ONLY) {
            if (highestFinding == null || severityRank(highestFinding.severity()) < 1) {
                return;
            }
        }
        MemoryProfiler.Snapshot memory = snapshot.memory();
        SystemMetricsProfiler.Snapshot system = snapshot.systemMetrics();
        FrameTimelineProfiler frame = FrameTimelineProfiler.getInstance();

        List<Entry> entries = new ArrayList<>();
        if (ConfigManager.isHudShowFps()) {
            entries.add(new Entry("FPS", String.format("%.0f | avg %.0f | 1%% %.0f | 0.1%% %.0f", frame.getCurrentFps(), frame.getAverageFps(), frame.getOnePercentLowFps(), frame.getPointOnePercentLowFps()), HEADER));
        }
        if (ConfigManager.isHudShowFrame()) {
            entries.add(new Entry("Frame", String.format("%.1f ms | worst %.1f ms", frame.getAverageFrameNs() / 1_000_000.0, frame.getMaxFrameNs() / 1_000_000.0), HEADER));
        }
        if (ConfigManager.isHudShowTicks()) {
            entries.add(new Entry("Client", String.format("%.1f ms", TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0), TEXT));
            entries.add(new Entry("Server", String.format("%.1f ms", TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0), TEXT));
        }
        if (ConfigManager.isHudShowUtilization()) {
            entries.add(new Entry("CPU", formatUtilAndTemp(system.cpuCoreLoadPercent(), system.cpuTemperatureC()), ACCENT));
            entries.add(new Entry("GPU", formatUtilAndTemp(system.gpuCoreLoadPercent(), system.gpuTemperatureC()), ACCENT));
            entries.add(new Entry("Main Logic", system.mainLogicSummary().replace("Main Logic: ", ""), TEXT));
            entries.add(new Entry("Background", system.backgroundSummary().replace("Background: ", ""), TEXT));
        }
        if (ConfigManager.isHudShowParallelism()) {
            String parallelText = system.cpuParallelismFlag();
            if (system.activeHighLoadThreads() > Math.max(1, system.estimatedPhysicalCores() / 2) && snapshot.stutterScore() > 10.0) {
                parallelText = "Thread Overscheduling Warning";
            }
            entries.add(new Entry("Parallel", parallelText, WARN));
        }
        if (ConfigManager.isHudShowMemory()) {
            entries.add(new Entry("Memory", String.format("%.0f/%.0f MB", memory.heapUsedBytes() / (1024.0 * 1024.0), (memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes()) / (1024.0 * 1024.0)), DIM));
        }
        if (ConfigManager.isHudShowWorld()) {
            entries.add(new Entry("Entities", String.valueOf(snapshot.entityCounts().totalEntities()), ACCENT));
            entries.add(new Entry("Chunks", snapshot.chunkCounts().loadedChunks() + "/" + snapshot.chunkCounts().renderedChunks(), ACCENT));
        }
        if (ConfigManager.isHudShowSession() && snapshot.sessionLogging()) {
            entries.add(new Entry("Session", formatDuration(snapshot.sessionLoggingElapsedMillis()) + " / " + formatDuration(ConfigManager.getSessionDurationSeconds() * 1000L), WARN));
        }
        long warningCount = ProfilerManager.getInstance().getLatestRuleFindings().stream().filter(finding -> severityRank(finding.severity()) >= 1).count();
        if (warningCount > 0) {
            String highestSeverity = highestFinding == null ? "warning" : highestFinding.severity();
            int findingColor = severityRank(highestSeverity) >= 3 ? 0xFFFF6B6B : WARN;
            entries.add(new Entry("Findings", warningCount + " active | " + snapshot.stutterScore() + " stutter", findingColor));
            if (highestFinding != null) {
                entries.add(new Entry("Alert", highestFinding.category() + ": " + highestFinding.message(), findingColor));
            }
        }
        if (triggerMode == ConfigManager.HudTriggerMode.SPIKES_ONLY && !snapshot.spikes().isEmpty()) {
            ProfilerManager.SpikeCapture latestSpike = snapshot.spikes().get(0);
            entries.add(new Entry("Spike", String.format("%.1f ms | %s", latestSpike.frameDurationMs(), latestSpike.likelyBottleneck()), WARN));
        }
        String bottleneck = ProfilerManager.getInstance().getCurrentBottleneckLabel();
        if (bottleneck != null && !bottleneck.isBlank()) {
            entries.add(new Entry("Bottleneck", bottleneck, DIM));
        }

        int columns = ConfigManager.getHudLayoutMode().columns();
        int cellWidth = columns == 1 ? 238 : switch (columns) {
            case 2 -> 156;
            default -> 120;
        };
        int gap = 8;
        int rows = Math.max(1, (int) Math.ceil(entries.size() / (double) columns));
        int width = columns == 1 ? cellWidth : (columns * cellWidth) + ((columns - 1) * gap) + 16;
        int height = 20 + (rows * 12) + 8;
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

        ctx.fill(x, y, x + width, y + height, BG);
        ctx.fill(x, y, x + width, y + 1, BORDER);
        ctx.fill(x, y, x + 1, y + height, BORDER);
        ctx.fill(x + width - 1, y, x + width, y + height, BORDER);
        ctx.fill(x, y + height - 1, x + width, y + height, BORDER);
        ctx.fill(x, y + 16, x + width, y + 17, 0x443A3F46);

        ctx.drawText(client.textRenderer, "Task Manager", x + 8, y + 5, HEADER, false);
        String modeText = snapshot.mode().name().replace('_', ' ');
        ctx.drawText(client.textRenderer, modeText, x + width - 8 - client.textRenderer.getWidth(modeText), y + 5, DIM, false);

        if (columns == 1) {
            int rowY = y + 22;
            for (Entry entry : entries) {
                ctx.drawText(client.textRenderer, entry.label(), x + 8, rowY, DIM, false);
                String value = client.textRenderer.trimToWidth(entry.value(), width - 86);
                ctx.drawText(client.textRenderer, value, x + 78, rowY, entry.color(), false);
                rowY += 12;
            }
            return;
        }

        int startY = y + 22;
        for (int i = 0; i < entries.size(); i++) {
            int column = i % columns;
            int row = i / columns;
            int cellX = x + 8 + column * (cellWidth + gap);
            int cellY = startY + row * 12;
            Entry entry = entries.get(i);
            String line = client.textRenderer.trimToWidth(entry.label() + " " + entry.value(), cellWidth);
            ctx.drawText(client.textRenderer, line, cellX, cellY, entry.color(), false);
        }
    }

    private static String formatUtilAndTemp(double loadPercent, double temperatureC) {
        String load = loadPercent >= 0.0 ? String.format("%.0f%%", loadPercent) : "n/a";
        if (!ConfigManager.isHudShowTemperatures()) {
            return load;
        }
        String temp = temperatureC >= 0.0 ? String.format("%.0fC", temperatureC) : "n/a";
        return load + " / " + temp;
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "info" : severity.toLowerCase()) {
            case "critical" -> 3;
            case "error" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    private record Entry(String label, String value, int color) {
    }
}
