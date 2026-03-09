package wueffi.taskmanager.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import wueffi.taskmanager.client.util.ConfigManager;
import wueffi.taskmanager.client.util.ModIconCache;
import wueffi.taskmanager.client.util.ModTimingSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TaskManagerScreen extends Screen {

    private enum TableId {
        TASKS,
        GPU,
        MEMORY
    }

    private enum WorldMiniTab {
        LAG_MAP,
        BLOCK_ENTITIES
    }

    private enum TaskSort {
        CPU,
        THREADS,
        SAMPLES,
        INVOKES;

        TaskSort next() {
            TaskSort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private enum GpuSort {
        EST_GPU,
        THREADS,
        GPU_MS,
        RENDER_SAMPLES;

        GpuSort next() {
            GpuSort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private enum MemorySort {
        MEMORY_MB,
        CLASS_COUNT,
        PERCENT;

        MemorySort next() {
            MemorySort[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private static final int TAB_HEIGHT = 24;
    private static final int PADDING = 8;
    private static final int ROW_HEIGHT = 20;
    private static final int ICON_SIZE = 16;
    private static final int GRAPH_TOP = 34;
    private static final int GRAPH_HEIGHT = 60;
    private static final String[] TAB_NAMES = {"Tasks", "GPU", "Render", "Startup", "Memory", "Flame", "Timeline", "Network", "Disk", "World", "System", "Settings"};

    private static final int BG_COLOR = 0xE0101010;
    private static final int PANEL_COLOR = 0xCC1A1A1A;
    private static final int TAB_ACTIVE = 0xFF2A2A2A;
    private static final int TAB_INACTIVE = 0xFF161616;
    private static final int BORDER_COLOR = 0xFF3A3A3A;
    private static final int TEXT_PRIMARY = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xFF888888;
    private static final int ACCENT_GREEN = 0xFF4CAF50;
    private static final int ACCENT_YELLOW = 0xFFFFB300;
    private static final int ACCENT_RED = 0xFFFF6666;
    private static final int HEADER_COLOR = 0xFF222222;
    private static final int ROW_ALT = 0x11FFFFFF;
    private static final int PANEL_SOFT = 0x99161616;
    private static final int PANEL_OUTLINE = 0x55343434;
    private static final int AMD_COLOR = 0xFFFF6B6B;
    private static final int INTEL_COLOR = 0xFF5EA9FF;
    private static final int NVIDIA_COLOR = 0xFF77DD77;
    private static int lastOpenedTab = 0;

    private int activeTab = 0;
    private int scrollOffset = 0;
    private String selectedTaskMod;
    private String selectedGpuMod;
    private String selectedMemoryMod;
    private String selectedSharedFamily;
    private ChunkPos selectedLagChunk;
    private String tasksSearch = "";
    private String gpuSearch = "";
    private String memorySearch = "";
    private TableId focusedSearchTable;
    private TaskSort taskSort = TaskSort.CPU;
    private boolean taskSortDescending = true;
    private GpuSort gpuSort = GpuSort.EST_GPU;
    private boolean gpuSortDescending = true;
    private MemorySort memorySort = MemorySort.MEMORY_MB;
    private boolean memorySortDescending = true;
    private WorldMiniTab worldMiniTab = WorldMiniTab.LAG_MAP;
    private float uiScale = 1.0f;
    private float uiOffsetX = 0.0f;
    private float uiOffsetY = 0.0f;
    private int layoutWidth;
    private int layoutHeight;
    private ProfilerManager.ProfilerSnapshot snapshot = ProfilerManager.getInstance().getCurrentSnapshot();

    public TaskManagerScreen() {
        this(lastOpenedTab);
    }

    public TaskManagerScreen(int initialTab) {
        super(Text.literal("Task Manager"));
        this.activeTab = Math.max(0, Math.min(TAB_NAMES.length - 1, initialTab));
        lastOpenedTab = this.activeTab;
        FlamegraphProfiler.getInstance().reset();
        FlamegraphProfiler.getInstance().start();
        ProfilerManager.getInstance().onScreenOpened();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        FlamegraphProfiler.getInstance().stop();
        ProfilerManager.getInstance().onScreenClosed();
        lastOpenedTab = activeTab;
        super.close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        snapshot = ProfilerManager.getInstance().getCurrentSnapshot();
        updateUiScale();
        int logicalMouseX = toLogicalX(mouseX);
        int logicalMouseY = toLogicalY(mouseY);

        ctx.fill(0, 0, this.width, this.height, BG_COLOR);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(uiOffsetX, uiOffsetY);
        ctx.getMatrices().scale(uiScale, uiScale);

        int w = getScreenWidth();
        int h = getScreenHeight();

        ctx.fill(0, 0, w, h, BG_COLOR);
        ctx.fill(0, 0, w, 30, 0xFF0A0A0A);
        ctx.drawText(textRenderer, "Task Manager", PADDING, 6, TEXT_PRIMARY, false);

        renderModeButton(ctx, logicalMouseX, logicalMouseY);
        renderHudToggle(ctx, logicalMouseX, logicalMouseY);
        renderExportButton(ctx, logicalMouseX, logicalMouseY);

        double clientTickMs = TickProfiler.getInstance().getAverageClientTickNs() / 1_000_000.0;
        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        String headerMetrics = String.format(
                "Client tick %.2f ms | Server tick %.2f ms | Entities %d/%d/%d | Chunks %d/%d",
                clientTickMs,
                serverTickMs,
                snapshot.entityCounts().totalEntities(),
                snapshot.entityCounts().livingEntities(),
                snapshot.entityCounts().blockEntities(),
                snapshot.chunkCounts().loadedChunks(),
                snapshot.chunkCounts().renderedChunks()
        );
        ctx.drawText(textRenderer, textRenderer.trimToWidth(headerMetrics, w - 240), PADDING, 18, TEXT_DIM, false);
        if (!snapshot.lastExportStatus().isBlank()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(snapshot.lastExportStatus(), 220), w - 226, 18, TEXT_DIM, false);
        }

        int tabY = getTabY();
        int tabW = Math.max(66, Math.min(84, (w - (PADDING * 2) - ((TAB_NAMES.length - 1) * 2)) / TAB_NAMES.length));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int tx = PADDING + i * (tabW + 2);
            ctx.fill(tx, tabY, tx + tabW, tabY + TAB_HEIGHT, i == activeTab ? TAB_ACTIVE : TAB_INACTIVE);
            if (i == activeTab) ctx.fill(tx, tabY, tx + tabW, tabY + 2, ACCENT_GREEN);
            ctx.fill(tx, tabY + TAB_HEIGHT - 1, tx + tabW, tabY + TAB_HEIGHT, BORDER_COLOR);
            int textX = tx + (tabW - textRenderer.getWidth(TAB_NAMES[i])) / 2;
            ctx.drawText(textRenderer, TAB_NAMES[i], textX, tabY + 8, i == activeTab ? TEXT_PRIMARY : TEXT_DIM, false);
        }

        int contentY = getContentY();
        int contentH = h - contentY - PADDING;
        ctx.fill(0, contentY, w, h, PANEL_COLOR);
        ctx.fill(0, contentY, w, contentY + 1, BORDER_COLOR);

        if (activeTab == 0) renderTasks(ctx, 0, contentY, w, contentH, logicalMouseX, logicalMouseY);
        else if (activeTab == 1) renderGpu(ctx, 0, contentY, w, contentH, logicalMouseX, logicalMouseY);
        else if (activeTab == 2) renderRender(ctx, 0, contentY, w, contentH, logicalMouseX, logicalMouseY);
        else if (activeTab == 3) renderStartup(ctx, 0, contentY, w, contentH, logicalMouseX, logicalMouseY);
        else if (activeTab == 4) renderMemory(ctx, 0, contentY, w, contentH, logicalMouseX, logicalMouseY);
        else if (activeTab == 5) renderFlamegraph(ctx, 0, contentY, w, contentH);
        else if (activeTab == 6) renderTimeline(ctx, 0, contentY, w, contentH);
        else if (activeTab == 7) renderNetwork(ctx, 0, contentY, w, contentH);
        else if (activeTab == 8) renderDisk(ctx, 0, contentY, w, contentH);
        else if (activeTab == 9) renderWorldTab(ctx, 0, contentY, w, contentH);
        else if (activeTab == 10) renderSystem(ctx, 0, contentY, w, contentH);
        else renderSettings(ctx, 0, contentY, w, contentH, logicalMouseX, logicalMouseY);

        ctx.getMatrices().popMatrix();
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void updateUiScale() {
        float widthScale = this.width / 1180.0f;
        float heightScale = this.height / 760.0f;
        uiScale = Math.min(1.0f, Math.min(widthScale, heightScale));
        if (uiScale <= 0.0f) {
            uiScale = 1.0f;
        }
        layoutWidth = Math.max(1, Math.round(this.width / uiScale));
        layoutHeight = Math.max(1, Math.round(this.height / uiScale));
        uiOffsetX = (this.width - (layoutWidth * uiScale)) / 2.0f;
        uiOffsetY = (this.height - (layoutHeight * uiScale)) / 2.0f;
    }

    private int getScreenWidth() {
        return layoutWidth > 0 ? layoutWidth : this.width;
    }

    private int getScreenHeight() {
        return layoutHeight > 0 ? layoutHeight : this.height;
    }

    private int toLogicalX(double mouseX) {
        return Math.round((float) ((mouseX - uiOffsetX) / uiScale));
    }

    private int toLogicalY(double mouseY) {
        return Math.round((float) ((mouseY - uiOffsetY) / uiScale));
    }

    private int getTabY() {
        return 34;
    }

    private int getContentY() {
        return getTabY() + TAB_HEIGHT + 1;
    }

    private void drawTopChip(DrawContext ctx, int x, int y, int width, int height, boolean hovered) {
        ctx.fill(x, y, x + width, y + height, hovered ? 0x66404040 : 0x3A202020);
        ctx.fill(x, y, x + width, y + 1, PANEL_OUTLINE);
        ctx.fill(x, y + height - 1, x + width, y + height, PANEL_OUTLINE);
    }

    private void drawInsetPanel(DrawContext ctx, int x, int y, int width, int height) {
        ctx.fill(x, y, x + width, y + height, PANEL_SOFT);
        ctx.fill(x, y, x + width, y + 1, PANEL_OUTLINE);
        ctx.fill(x, y + height - 1, x + width, y + height, PANEL_OUTLINE);
        ctx.fill(x, y, x + 1, y + height, PANEL_OUTLINE);
        ctx.fill(x + width - 1, y, x + width, y + height, PANEL_OUTLINE);
    }

    private int renderSectionHeader(DrawContext ctx, int x, int y, String title, String subtitle) {
        ctx.drawText(textRenderer, title, x, y, TEXT_PRIMARY, false);
        if (subtitle != null && !subtitle.isBlank()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(subtitle, Math.max(120, getScreenWidth() - (x * 2))), x, y + 12, TEXT_DIM, false);
            return y + 28;
        }
        return y + 16;
    }

    private void renderModeButton(DrawContext ctx, int mouseX, int mouseY) {
        int x = PADDING + 106;
        int y = 3;
        int w = 132;
        int h = 14;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        drawTopChip(ctx, x, y, w, h, hovered);
        ctx.drawText(textRenderer, "Mode: " + formatMode(snapshot.mode()), x + 8, y + 3, hovered ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderHudToggle(DrawContext ctx, int mouseX, int mouseY) {
        int checkX = getScreenWidth() - 254;
        int checkY = 3;
        int chipW = 96;
        int chipH = 14;
        boolean hovered = mouseX >= checkX && mouseX < checkX + chipW && mouseY >= checkY && mouseY < checkY + chipH;
        drawTopChip(ctx, checkX, checkY, chipW, chipH, hovered);
        ctx.fill(checkX + 6, checkY + 3, checkX + 16, checkY + 13, hovered ? 0xFF444444 : 0xFF2A2A2A);
        ctx.fill(checkX + 7, checkY + 4, checkX + 15, checkY + 12, 0xFF1A1A1A);
        if (ConfigManager.isHudEnabled()) ctx.fill(checkX + 8, checkY + 5, checkX + 14, checkY + 11, ACCENT_GREEN);
        ctx.drawText(textRenderer, "HUD overlay", checkX + 20, checkY + 3, hovered ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderExportButton(DrawContext ctx, int mouseX, int mouseY) {
        int x = getScreenWidth() - 118;
        int y = 3;
        int w = 110;
        int h = 14;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        drawTopChip(ctx, x, y, w, h, hovered);
        ctx.drawText(textRenderer, "Export Session", x + 10, y + 3, hovered ? TEXT_PRIMARY : TEXT_DIM, false);
    }

    private void renderTasks(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        Map<String, CpuSamplingProfiler.Snapshot> cpu = snapshot.cpuMods();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = snapshot.cpuDetails();
        Map<String, ModTimingSnapshot> invokes = snapshot.modInvokes();
        List<String> rows = getTaskRows();

        int detailW = Math.min(420, Math.max(320, w / 3));
        int gap = PADDING;
        int listW = w - detailW - gap;
        int infoY = y + PADDING;
        ctx.drawText(textRenderer, "CPU share from rolling sampled stack windows. Event invokes shown separately.", x + PADDING, infoY, TEXT_DIM, false);
        ctx.drawText(textRenderer, cpuStatusText(snapshot.cpuReady(), snapshot.totalCpuSamples(), snapshot.cpuSampleAgeMillis()), x + PADDING, infoY + 10, getCpuStatusColor(snapshot.cpuReady()), false);
        renderSearchBox(ctx, x + listW - 160, infoY + 24, 152, 16, "Search mods", tasksSearch, focusedSearchTable == TableId.TASKS);
        renderSortSummary(ctx, x + PADDING, infoY + 28, "Sort", formatSort(taskSort, taskSortDescending), TEXT_DIM);

        if (!rows.isEmpty() && (selectedTaskMod == null || !rows.contains(selectedTaskMod))) {
            selectedTaskMod = rows.getFirst();
        }

        int headerY = infoY + 50;
        ctx.fill(x, headerY, x + listW, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "MOD", x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        int pctX = x + listW - 206;
        int threadsX = x + listW - 146;
        int samplesX = x + listW - 92;
        int invokesX = x + listW - 42;
        if (isColumnVisible(TableId.TASKS, "cpu")) ctx.drawText(textRenderer, headerLabel("%CPU", taskSort == TaskSort.CPU, taskSortDescending), pctX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.TASKS, "threads")) ctx.drawText(textRenderer, headerLabel("THREADS", taskSort == TaskSort.THREADS, taskSortDescending), threadsX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.TASKS, "samples")) ctx.drawText(textRenderer, headerLabel("SAMPLES", taskSort == TaskSort.SAMPLES, taskSortDescending), samplesX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.TASKS, "invokes")) ctx.drawText(textRenderer, headerLabel("INVOKES", taskSort == TaskSort.INVOKES, taskSortDescending), invokesX, headerY + 3, TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y);
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, tasksSearch.isBlank() ? "Waiting for CPU samples..." : "No task rows match the current search/filter.", x + PADDING, listY + 6, TEXT_DIM, false);
        } else {
            ctx.enableScissor(x, listY, x + listW, listY + listH);
            int rowY = listY - scrollOffset;
            int rowIdx = 0;
            for (String modId : rows) {
                if (rowY + ROW_HEIGHT > listY && rowY < listY + listH) {
                    renderStripedRow(ctx, x, listW, rowY, rowIdx, mouseX, mouseY);
                    if (modId.equals(selectedTaskMod)) {
                        ctx.fill(x, rowY, x + 3, rowY + ROW_HEIGHT, ACCENT_GREEN);
                    }
                    Identifier icon = ModIconCache.getInstance().getIcon(modId);
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + PADDING, rowY + 2, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);

                    CpuSamplingProfiler.Snapshot cpuSnapshot = cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0));
                    CpuSamplingProfiler.DetailSnapshot detailSnapshot = cpuDetails.get(modId);
                    long invokesCount = invokes.getOrDefault(modId, new ModTimingSnapshot(0, 0)).calls();
                    double pct = snapshot.totalCpuSamples() > 0 ? cpuSnapshot.totalSamples() * 100.0 / snapshot.totalCpuSamples() : 0;
                    int threadCount = detailSnapshot == null ? 0 : detailSnapshot.sampledThreadCount();

                    ctx.drawText(textRenderer, getDisplayName(modId), x + PADDING + ICON_SIZE + 6, rowY + 6, TEXT_PRIMARY, false);
                    if (isColumnVisible(TableId.TASKS, "cpu")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f%%", pct), pctX, rowY + 6, getHeatColor(pct), false);
                    if (isColumnVisible(TableId.TASKS, "threads")) ctx.drawText(textRenderer, Integer.toString(threadCount), threadsX, rowY + 6, TEXT_DIM, false);
                    if (isColumnVisible(TableId.TASKS, "samples")) ctx.drawText(textRenderer, formatCount(cpuSnapshot.totalSamples()), samplesX, rowY + 6, TEXT_DIM, false);
                    if (isColumnVisible(TableId.TASKS, "invokes")) ctx.drawText(textRenderer, formatCount(invokesCount), invokesX, rowY + 6, TEXT_DIM, false);
                }
                if (rowY > listY + listH) break;
                rowY += ROW_HEIGHT;
                rowIdx++;
            }
            ctx.disableScissor();
        }

        renderCpuDetailPanel(ctx, x + listW + gap, y + PADDING, detailW, h - (PADDING * 2), selectedTaskMod, cpu.get(selectedTaskMod), cpuDetails.get(selectedTaskMod), invokes.get(selectedTaskMod));
    }

    private void renderGpu(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        Map<String, CpuSamplingProfiler.Snapshot> cpu = snapshot.cpuMods();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = snapshot.cpuDetails();
        long totalRenderSamples = snapshot.totalRenderSamples();
        long totalGpuNs = snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum();

        int detailW = Math.min(420, Math.max(320, w / 3));
        int gap = PADDING;
        int listW = w - detailW - gap;
        int infoY = y + PADDING;
        ctx.drawText(textRenderer, "Estimated GPU share from render-thread samples weighted by measured GPU timer-query time.", x + PADDING, infoY, TEXT_DIM, false);
        ctx.drawText(textRenderer, cpuStatusText(snapshot.gpuReady(), totalRenderSamples, snapshot.cpuSampleAgeMillis()), x + PADDING, infoY + 10, getGpuStatusColor(snapshot.gpuReady()), false);
        renderSearchBox(ctx, x + listW - 160, infoY + 24, 152, 16, "Search mods", gpuSearch, focusedSearchTable == TableId.GPU);
        renderSortSummary(ctx, x + PADDING, infoY + 28, "Sort", formatSort(gpuSort, gpuSortDescending), TEXT_DIM);

        if (totalRenderSamples == 0 || totalGpuNs == 0) {
            ctx.drawText(textRenderer, "No GPU attribution yet. Render some frames with timer queries enabled.", x + PADDING, infoY + 52, TEXT_DIM, false);
            renderGpuDetailPanel(ctx, x + listW + gap, y + PADDING, detailW, h - (PADDING * 2), selectedGpuMod, null, null, totalRenderSamples, totalGpuNs);
            return;
        }

        List<String> rows = getGpuRows();
        if (!rows.isEmpty() && (selectedGpuMod == null || !rows.contains(selectedGpuMod))) {
            selectedGpuMod = rows.getFirst();
        }

        int headerY = infoY + 50;
        ctx.fill(x, headerY, x + listW, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "MOD", x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        int pctX = x + listW - 232;
        int threadsX = x + listW - 172;
        int gpuMsX = x + listW - 108;
        int renderSamplesX = x + listW - 42;
        if (isColumnVisible(TableId.GPU, "pct")) ctx.drawText(textRenderer, headerLabel("EST %GPU", gpuSort == GpuSort.EST_GPU, gpuSortDescending), pctX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.GPU, "threads")) ctx.drawText(textRenderer, headerLabel("THREADS", gpuSort == GpuSort.THREADS, gpuSortDescending), threadsX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.GPU, "gpums")) ctx.drawText(textRenderer, headerLabel("Est ms", gpuSort == GpuSort.GPU_MS, gpuSortDescending), gpuMsX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.GPU, "rsamples")) ctx.drawText(textRenderer, headerLabel("R.S", gpuSort == GpuSort.RENDER_SAMPLES, gpuSortDescending), renderSamplesX, headerY + 3, TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y);
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, gpuSearch.isBlank() ? "Waiting for render-thread samples..." : "No GPU rows match the current search/filter.", x + PADDING, listY + 6, TEXT_DIM, false);
        } else {
            ctx.enableScissor(x, listY, x + listW, listY + listH);
            int rowY = listY - scrollOffset;
            int rowIdx = 0;
            for (String modId : rows) {
                if (rowY + ROW_HEIGHT > listY && rowY < listY + listH) {
                    renderStripedRow(ctx, x, listW, rowY, rowIdx, mouseX, mouseY);
                    if (modId.equals(selectedGpuMod)) {
                        ctx.fill(x, rowY, x + 3, rowY + ROW_HEIGHT, ACCENT_GREEN);
                    }
                    CpuSamplingProfiler.Snapshot cpuSnapshot = cpu.get(modId);
                    CpuSamplingProfiler.DetailSnapshot detailSnapshot = cpuDetails.get(modId);
                    double share = cpuSnapshot.renderSamples() / (double) totalRenderSamples;
                    double gpuMs = totalGpuNs * share / 1_000_000.0;
                    double pct = share * 100.0;
                    int threadCount = detailSnapshot == null ? 0 : detailSnapshot.sampledThreadCount();

                    Identifier icon = ModIconCache.getInstance().getIcon(modId);
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + PADDING, rowY + 2, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);
                    ctx.drawText(textRenderer, getDisplayName(modId), x + PADDING + ICON_SIZE + 6, rowY + 6, TEXT_PRIMARY, false);
                    if (isColumnVisible(TableId.GPU, "pct")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f%%", pct), pctX, rowY + 6, getHeatColor(pct), false);
                    if (isColumnVisible(TableId.GPU, "threads")) ctx.drawText(textRenderer, Integer.toString(threadCount), threadsX, rowY + 6, TEXT_DIM, false);
                    if (isColumnVisible(TableId.GPU, "gpums")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.2f", gpuMs), gpuMsX, rowY + 6, TEXT_DIM, false);
                    if (isColumnVisible(TableId.GPU, "rsamples")) ctx.drawText(textRenderer, formatCount(cpuSnapshot.renderSamples()), renderSamplesX, rowY + 6, TEXT_DIM, false);
                }
                if (rowY > listY + listH) break;
                rowY += ROW_HEIGHT;
                rowIdx++;
            }
            ctx.disableScissor();
        }

        renderGpuDetailPanel(ctx, x + listW + gap, y + PADDING, detailW, h - (PADDING * 2), selectedGpuMod, cpu.get(selectedGpuMod), cpuDetails.get(selectedGpuMod), totalRenderSamples, totalGpuNs);
    }

    private void renderRender(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        List<String> phases = new ArrayList<>(snapshot.renderPhases().keySet());
        if (phases.isEmpty()) {
            ctx.drawText(textRenderer, "No render data.", x + PADDING, y + PADDING + 4, TEXT_DIM, false);
            return;
        }

        long totalCpuNanos = snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuNanos).sum();

        int headerY = y + PADDING;
        ctx.fill(x, headerY, x + w, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "PHASE", x + PADDING, headerY + 3, TEXT_DIM, false);
        int shareX = w - 220;
        int cpuMsX = w - 160;
        int gpuMsX = w - 100;
        int callsX = w - 45;
        ctx.drawText(textRenderer, "%CPU", shareX, headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "CPU ms", cpuMsX, headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "GPU ms", gpuMsX, headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "CALLS", callsX, headerY + 3, TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y);
        ctx.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - scrollOffset;
        int rowIdx = 0;
        for (String phase : phases) {
            if (rowY + ROW_HEIGHT > listY && rowY < listY + listH) {
                renderStripedRow(ctx, x, w, rowY, rowIdx, mouseX, mouseY);
                RenderPhaseProfiler.PhaseSnapshot phaseSnapshot = snapshot.renderPhases().get(phase);
                long phaseCalls = Math.max(phaseSnapshot.cpuCalls(), phaseSnapshot.gpuCalls());
                double pct = totalCpuNanos > 0 ? phaseSnapshot.cpuNanos() * 100.0 / totalCpuNanos : 0;
                double avgCpuMs = phaseCalls > 0 ? (phaseSnapshot.cpuNanos() / 1_000_000.0) / phaseCalls : 0;
                double avgGpuMs = phaseCalls > 0 ? (phaseSnapshot.gpuNanos() / 1_000_000.0) / phaseCalls : 0;

                ctx.drawText(textRenderer, phase, x + PADDING, rowY + 6, TEXT_PRIMARY, false);
                ctx.drawText(textRenderer, String.format("%.1f%%", pct), shareX, rowY + 6, getHeatColor(pct), false);
                ctx.drawText(textRenderer, String.format("%.2f", avgCpuMs), cpuMsX, rowY + 6, TEXT_DIM, false);
                ctx.drawText(textRenderer, phaseSnapshot.gpuNanos() > 0 ? String.format("%.2f", avgGpuMs) : "-", gpuMsX, rowY + 6, TEXT_DIM, false);
                ctx.drawText(textRenderer, formatCount(phaseCalls), callsX, rowY + 6, TEXT_DIM, false);
            }
            if (rowY > listY + listH) break;
            rowY += ROW_HEIGHT;
            rowIdx++;
        }
        ctx.disableScissor();
    }

    private void renderStartup(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (snapshot.startupRows().isEmpty()) {
            ctx.drawText(textRenderer, "No startup data.", x + PADDING, y + PADDING + 4, TEXT_DIM, false);
            return;
        }

        long totalSpan = Math.max(snapshot.startupLast() - snapshot.startupFirst(), 1);

        ctx.drawText(textRenderer, "Observed startup listener registration activity by mod, shown in actual wall-clock milliseconds.", x + PADDING, y + PADDING, TEXT_DIM, false);

        int headerY = y + PADDING + 20;
        ctx.fill(x, headerY, x + w, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "MOD", x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        int barX = x + w - 260;
        int startMsX = x + w - 132;
        int activeMsX = x + w - 78;
        int regsX = x + w - 30;
        ctx.drawText(textRenderer, "TIMELINE", barX, headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "START", startMsX, headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "ACTIVE", activeMsX, headerY + 3, TEXT_DIM, false);
        ctx.drawText(textRenderer, "REG", regsX, headerY + 3, TEXT_DIM, false);

        int listY = headerY + 16;
        int listH = h - (listY - y) - 14;
        ctx.enableScissor(x, listY, x + w, listY + listH);

        int rowY = listY - scrollOffset;
        int rowIdx = 0;
        for (var row : snapshot.startupRows()) {
            if (rowY + ROW_HEIGHT > listY && rowY < listY + listH) {
                renderStripedRow(ctx, x, w, rowY, rowIdx, mouseX, mouseY);
                Identifier icon = ModIconCache.getInstance().getIcon(row.modId());
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + PADDING, rowY + 2, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);
                ctx.drawText(textRenderer, getDisplayName(row.modId()), x + PADDING + ICON_SIZE + 6, rowY + 6, TEXT_PRIMARY, false);

                int barTotalW = 120;
                int barStart = (int) ((row.first() - snapshot.startupFirst()) * barTotalW / totalSpan);
                int barLen = Math.max(1, (int) ((row.last() - row.first()) * barTotalW / totalSpan));
                ctx.fill(barX, rowY + 8, barX + barTotalW, rowY + 12, 0x33FFFFFF);
                ctx.fill(barX + barStart, rowY + 7, barX + barStart + barLen, rowY + 13, ACCENT_YELLOW);

                double startMs = (row.first() - snapshot.startupFirst()) / 1_000_000.0;
                double activeMs = (row.last() - row.first()) / 1_000_000.0;
                ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", startMs), startMsX, rowY + 6, TEXT_DIM, false);
                ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", activeMs), activeMsX, rowY + 6, ACCENT_YELLOW, false);
                ctx.drawText(textRenderer, String.valueOf(row.registrations()), regsX, rowY + 6, TEXT_DIM, false);
            }
            if (rowY > listY + listH) break;
            rowY += ROW_HEIGHT;
            rowIdx++;
        }
        ctx.disableScissor();

        ctx.fill(x, y + h - 14, x + w, y + h, HEADER_COLOR);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Observed startup span: %.1f ms", totalSpan / 1_000_000.0), x + PADDING, y + h - 10, TEXT_DIM, false);
    }

    private void renderMemory(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        MemoryProfiler.Snapshot memory = snapshot.memory();
        Map<String, Long> memoryMods = snapshot.memoryMods();
        Map<String, Long> sharedFamilies = snapshot.sharedMemoryFamilies();
        Map<String, Map<String, Long>> sharedFamilyClasses = snapshot.sharedFamilyClasses();
        Map<String, Map<String, Long>> memoryClassesByMod = snapshot.memoryClassesByMod();

        if (selectedSharedFamily == null && !sharedFamilies.isEmpty()) {
            selectedSharedFamily = sharedFamilies.keySet().iterator().next();
        }

        List<String> rows = getMemoryRows();
        if (selectedMemoryMod == null || !rows.contains(selectedMemoryMod)) {
            selectedMemoryMod = rows.isEmpty() ? null : rows.getFirst();
        }

        int sharedPanelW = sharedFamilies.isEmpty() ? 0 : Math.min(280, Math.max(220, w / 4));
        int detailH = 116;
        int panelGap = sharedPanelW > 0 ? PADDING : 0;
        int tableW = w - sharedPanelW - panelGap;
        int left = x + PADDING;
        int top = y + PADDING;
        ctx.drawText(textRenderer, "Estimated live heap by mod plus JVM runtime buckets. Updated asynchronously.", left, top, TEXT_DIM, false);
        ctx.drawText(textRenderer, memoryStatusText(snapshot.memoryAgeMillis()), left, top + 10, snapshot.memoryAgeMillis() <= 15000 ? ACCENT_GREEN : ACCENT_YELLOW, false);
        renderSearchBox(ctx, x + tableW - 160, top, 152, 16, "Search mods", memorySearch, focusedSearchTable == TableId.MEMORY);
        renderSortSummary(ctx, left, top + 22, "Sort", formatSort(memorySort, memorySortDescending), TEXT_DIM);

        long heapMax = memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes();
        double usedPct = heapMax > 0 ? (memory.heapUsedBytes() * 100.0 / heapMax) : 0;

        int barY = top + 38;
        int barW = Math.min(320, tableW - (PADDING * 2));
        ctx.fill(left, barY, left + barW, barY + 10, 0x33FFFFFF);
        ctx.fill(left, barY, left + (int) (barW * Math.min(1.0, usedPct / 100.0)), barY + 10, usedPct > 85 ? 0x99FF4444 : usedPct > 70 ? 0x99FFB300 : 0x994CAF50);

        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Heap used %.1f MB / allocated %.1f MB | Non-heap %.1f MB | GC %d (%d ms)",
                memory.heapUsedBytes() / (1024.0 * 1024.0),
                memory.heapCommittedBytes() / (1024.0 * 1024.0),
                memory.nonHeapUsedBytes() / (1024.0 * 1024.0),
                memory.gcCount(),
                memory.gcTimeMillis()), left, barY + 16, TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Young GC %d | Old/Full GC %d | Last pause %d ms | Last GC %s",
                memory.youngGcCount(),
                memory.oldGcCount(),
                memory.gcPauseDurationMs(),
                memory.gcType()), left, barY + 28, TEXT_DIM, false);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Off-heap direct %.1f MB / %s",
                (memory.directBufferBytes() + memory.mappedBufferBytes()) / (1024.0 * 1024.0),
                formatBytesMb(memory.directMemoryMaxBytes())), left, barY + 40, TEXT_DIM, false);

        if (sharedPanelW > 0) {
            renderSharedFamiliesPanel(ctx, x + tableW + panelGap, y + PADDING, sharedPanelW, h - (PADDING * 2), sharedFamilies);
        }

        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, memorySearch.isBlank() ? "Per-mod memory snapshot not available yet." : "No memory rows match the current search/filter.", left, barY + 48, TEXT_DIM, false);
            return;
        }

        int headerY = barY + 58;
        ctx.fill(x, headerY, x + tableW, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, "MOD", x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        int classesX = x + tableW - 140;
        int mbX = x + tableW - 94;
        int pctX = x + tableW - 42;
        if (isColumnVisible(TableId.MEMORY, "classes")) ctx.drawText(textRenderer, headerLabel("CLS", memorySort == MemorySort.CLASS_COUNT, memorySortDescending), classesX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.MEMORY, "mb")) ctx.drawText(textRenderer, headerLabel("MB", memorySort == MemorySort.MEMORY_MB, memorySortDescending), mbX, headerY + 3, TEXT_DIM, false);
        if (isColumnVisible(TableId.MEMORY, "pct")) ctx.drawText(textRenderer, headerLabel("%", memorySort == MemorySort.PERCENT, memorySortDescending), pctX, headerY + 3, TEXT_DIM, false);

        long totalAttributedBytes = Math.max(1, memoryMods.values().stream().mapToLong(Long::longValue).sum());
        int listY = headerY + 16;
        int listH = h - (listY - y) - detailH;
        ctx.enableScissor(x, listY, x + tableW, listY + listH);

        int rowY = listY - scrollOffset;
        int rowIdx = 0;
        for (String modId : rows) {
            long bytes = memoryMods.getOrDefault(modId, 0L);
            if (rowY + ROW_HEIGHT > listY && rowY < listY + listH) {
                renderStripedRow(ctx, x, tableW, rowY, rowIdx, mouseX, mouseY);
                if (modId.equals(selectedMemoryMod)) {
                    ctx.fill(x, rowY, x + 3, rowY + ROW_HEIGHT, ACCENT_GREEN);
                }
                double mb = bytes / (1024.0 * 1024.0);
                double pct = bytes * 100.0 / totalAttributedBytes;
                int classCount = memoryClassesByMod.getOrDefault(modId, Map.of()).size();

                Identifier icon = ModIconCache.getInstance().getIcon(modId);
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + PADDING, rowY + 2, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);
                ctx.drawText(textRenderer, getDisplayName(modId), x + PADDING + ICON_SIZE + 6, rowY + 6, TEXT_PRIMARY, false);
                if (isColumnVisible(TableId.MEMORY, "classes")) ctx.drawText(textRenderer, Integer.toString(classCount), classesX, rowY + 6, TEXT_DIM, false);
                if (isColumnVisible(TableId.MEMORY, "mb")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", mb), mbX, rowY + 6, TEXT_DIM, false);
                if (isColumnVisible(TableId.MEMORY, "pct")) ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f%%", pct), pctX, rowY + 6, getHeatColor(pct), false);
            }
            if (rowY > listY + listH) break;
            rowY += ROW_HEIGHT;
            rowIdx++;
        }
        ctx.disableScissor();

        if (detailH > 0) {
            if (selectedMemoryMod != null) {
                renderMemoryDetailPanel(ctx, x, y + h - detailH, tableW, detailH, selectedMemoryMod, memoryClassesByMod.getOrDefault(selectedMemoryMod, Map.of()));
            } else {
                renderSharedFamilyDetail(ctx, x, y + h - detailH, tableW, detailH, sharedFamilyClasses.getOrDefault(selectedSharedFamily, Map.of()));
            }
        }
    }

    private void renderCpuDetailPanel(DrawContext ctx, int x, int y, int width, int height, String modId, CpuSamplingProfiler.Snapshot cpuSnapshot, CpuSamplingProfiler.DetailSnapshot detail, ModTimingSnapshot invokeSnapshot) {
        drawInsetPanel(ctx, x, y, width, height);
        if (modId == null || cpuSnapshot == null) {
            ctx.drawText(textRenderer, "Select a row to inspect sampled CPU causes.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        ctx.enableScissor(x, y, x + width, y + height);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(modId), width - 16), x + 8, y + 8, TEXT_PRIMARY, false);
        double pct = snapshot.totalCpuSamples() > 0 ? cpuSnapshot.totalSamples() * 100.0 / snapshot.totalCpuSamples() : 0.0;
        int rowY = renderWrappedText(ctx, x + 8, y + 20, width - 16, String.format(Locale.ROOT, "%.1f%% CPU | %s threads | %s samples | %s invokes", pct, detail == null ? 0 : detail.sampledThreadCount(), formatCount(cpuSnapshot.totalSamples()), formatCount(invokeSnapshot == null ? 0 : invokeSnapshot.calls())), TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, "Attribution: sampled stack ownership [measured/inferred]", TEXT_DIM) + 6;
        if ("shared/jvm".equals(modId) || "shared/framework".equals(modId)) {
            rowY = renderThreadSnapshotSection(ctx, x + 8, rowY, width - 16, "Top JVM threads [measured]", snapshot.systemMetrics().threadDetailsByName());
            rowY = renderStringListSection(ctx, x + 8, rowY + 6, width - 16, "Top waits / locks [measured]", ProfilerManager.getInstance().getLatestLockSummaries());
        } else {
            rowY = renderReasonSection(ctx, x + 8, rowY, width - 16, "Top threads [sampled]", effectiveThreadBreakdown(modId, detail));
        }
        renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Top sampled frames [sampled]", detail == null ? Map.of() : detail.topFrames());
        ctx.disableScissor();
    }

    private void renderGpuDetailPanel(DrawContext ctx, int x, int y, int width, int height, String modId, CpuSamplingProfiler.Snapshot cpuSnapshot, CpuSamplingProfiler.DetailSnapshot detail, long totalRenderSamples, long totalGpuNs) {
        drawInsetPanel(ctx, x, y, width, height);
        if (modId == null || cpuSnapshot == null) {
            ctx.drawText(textRenderer, "Select a row to inspect estimated GPU work.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        double share = totalRenderSamples > 0 ? cpuSnapshot.renderSamples() / (double) totalRenderSamples : 0.0;
        double gpuMs = totalGpuNs * share / 1_000_000.0;
        ctx.enableScissor(x, y, x + width, y + height);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(modId), width - 16), x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = renderWrappedText(ctx, x + 8, y + 20, width - 16, String.format(Locale.ROOT, "%.1f%% est GPU | %s threads | %.2f ms est GPU time | %s render samples", share * 100.0, detail == null ? 0 : detail.sampledThreadCount(), gpuMs, formatCount(cpuSnapshot.renderSamples())), TEXT_DIM);
        rowY = renderWrappedText(ctx, x + 8, rowY, width - 16, "Attribution: render-thread sampling weighted by GPU timers [estimated]", TEXT_DIM) + 6;
        if ("shared/jvm".equals(modId) || "shared/framework".equals(modId)) {
            rowY = renderThreadSnapshotSection(ctx, x + 8, rowY, width - 16, "Top JVM threads [measured]", snapshot.systemMetrics().threadDetailsByName());
            rowY = renderStringListSection(ctx, x + 8, rowY + 6, width - 16, "Top waits / locks [measured]", ProfilerManager.getInstance().getLatestLockSummaries());
        } else {
            rowY = renderReasonSection(ctx, x + 8, rowY, width - 16, "Render threads [sampled]", effectiveThreadBreakdown(modId, detail));
        }
        renderReasonSection(ctx, x + 8, rowY + 6, width - 16, "Top sampled render frames [sampled]", detail == null ? Map.of() : detail.topFrames());
        ctx.disableScissor();
    }

    private void renderMemoryDetailPanel(DrawContext ctx, int x, int y, int width, int height, String modId, Map<String, Long> topClasses) {
        drawInsetPanel(ctx, x, y, width, height);
        if (modId == null) {
            ctx.drawText(textRenderer, "Select a row to inspect top live classes.", x + 8, y + 8, TEXT_DIM, false);
            return;
        }
        ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(modId), width - 16), x + 8, y + 8, TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, "Top live class families in the latest histogram sample [measured by owning class].", x + 8, y + 20, TEXT_DIM, false);
        renderReasonSection(ctx, x + 8, y + 38, width - 16, "Top classes", topClasses);
    }

    private int renderThreadSnapshotSection(DrawContext ctx, int x, int y, int width, String title, Map<String, ThreadLoadProfiler.ThreadSnapshot> data) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        if (data == null || data.isEmpty()) {
            ctx.drawText(textRenderer, "No thread diagnostics captured in the current window.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot> entry : data.entrySet()) {
            ThreadLoadProfiler.ThreadSnapshot details = entry.getValue();
            String summary = entry.getKey() + " | " + String.format(Locale.ROOT, "%.1f%% %s", details.loadPercent(), blankToUnknown(details.state()));
            rowY = renderWrappedText(ctx, x + 6, rowY, width - 12, summary, getHeatColor(details.loadPercent()));
            if (details.blockedCountDelta() > 0 || details.waitedCountDelta() > 0 || details.lockName() != null || details.lockOwnerName() != null) {
                String waitLine = "blocked " + details.blockedCountDelta()
                        + " / " + details.blockedTimeDeltaMs() + "ms | waited "
                        + details.waitedCountDelta() + " / " + details.waitedTimeDeltaMs()
                        + "ms | lock " + describeLock(details);
                rowY = renderWrappedText(ctx, x + 12, rowY, width - 18, waitLine, TEXT_DIM);
            }
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    private int renderReasonSection(DrawContext ctx, int x, int y, int width, String title, Map<String, ? extends Number> data) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        if (data == null || data.isEmpty()) {
            ctx.drawText(textRenderer, "No detail captured in the current window.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (Map.Entry<String, ? extends Number> entry : data.entrySet()) {
            String label = textRenderer.trimToWidth(entry.getKey(), Math.max(80, width - 50));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_PRIMARY, false);
            String value = formatDetailValue(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - textRenderer.getWidth(value), rowY, TEXT_DIM, false);
            rowY += 12;
            shown++;
            if (shown >= 5) {
                break;
            }
        }
        return rowY;
    }

    private int renderStringListSection(DrawContext ctx, int x, int y, int width, String title, java.util.List<String> lines) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        if (lines == null || lines.isEmpty()) {
            ctx.drawText(textRenderer, "No detail captured in the current window.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (String line : lines) {
            rowY = renderWrappedText(ctx, x + 6, rowY, Math.max(80, width - 12), line, TEXT_PRIMARY);
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    private int renderWrappedText(DrawContext ctx, int x, int y, int width, String text, int color) {
        if (text == null || text.isBlank()) {
            return y;
        }
        int wrappedWidth = Math.max(40, width);
        ctx.drawWrappedText(textRenderer, Text.literal(text), x, y, wrappedWidth, color, false);
        int lineCount = Math.max(1, textRenderer.wrapLines(Text.literal(text), wrappedWidth).size());
        return y + (lineCount * 12);
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

    private Map<String, Number> effectiveThreadBreakdown(String modId, CpuSamplingProfiler.DetailSnapshot detail) {
        if ("shared/jvm".equals(modId) || "shared/framework".equals(modId)) {
            Map<String, Number> result = new LinkedHashMap<>();
            int shown = 0;
            for (Map.Entry<String, Double> entry : snapshot.systemMetrics().threadLoadPercentByName().entrySet()) {
                result.put(entry.getKey(), entry.getValue());
                shown++;
                if (shown >= 5) {
                    break;
                }
            }
            return result;
        }
        return detail == null ? Map.of() : new LinkedHashMap<>(detail.topThreads());
    }

    private String formatDetailValue(Number value) {
        if (value == null) {
            return "0";
        }
        if (value instanceof Double || value instanceof Float) {
            return String.format(Locale.ROOT, "%.1f%%", value.doubleValue());
        }
        return formatCount(value.longValue());
    }

    private int getFullPageScrollTop(int y) {
        return y + PADDING - scrollOffset;
    }

    private int getFullPageContentHeight(int h) {
        return Math.max(1, h - PADDING);
    }

    private void beginFullPageScissor(DrawContext ctx, int x, int y, int w, int h) {
        ctx.enableScissor(x, y, x + w, y + h);
    }

    private void endFullPageScissor(DrawContext ctx) {
        ctx.disableScissor();
    }

    private void renderNetwork(DrawContext ctx, int x, int y, int w, int h) {
        SystemMetricsProfiler metrics = SystemMetricsProfiler.getInstance();
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        int graphWidth = getPreferredGraphWidth(w);
        int graphX = x + Math.max(PADDING, (w - graphWidth) / 2);
        int columnGap = 20;
        int columnWidth = Math.max(120, (w - (PADDING * 2) - columnGap) / 2);
        int rightColumnX = left + columnWidth + columnGap;
        beginFullPageScissor(ctx, x, y, w, h);
        ctx.drawText(textRenderer, "Network throughput and packet/channel attribution during capture.", left, top, TEXT_DIM, false);
        top += 14;
        drawMetricRow(ctx, left, top, w - 16, "Inbound", formatBytesPerSecond(snapshot.systemMetrics().bytesReceivedPerSecond()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 16, "Outbound", formatBytesPerSecond(snapshot.systemMetrics().bytesSentPerSecond()));
        top += 20;
        int graphHeight = 132;
        renderMetricGraph(ctx, graphX - PADDING, top, graphWidth + (PADDING * 2), graphHeight, metrics.getOrderedNetworkInHistory(), metrics.getOrderedNetworkOutHistory(), "Network In/Out", "B/s", metrics.getHistorySpanSeconds());
        top += graphHeight + 2;
        top += renderGraphLegend(ctx, graphX, top, new String[]{"Inbound", "Outbound"}, new int[]{INTEL_COLOR, ACCENT_YELLOW}) + 14;

        java.util.List<NetworkPacketProfiler.Snapshot> packetHistory = NetworkPacketProfiler.getInstance().getHistory();
        NetworkPacketProfiler.Snapshot latestPackets = packetHistory.isEmpty() ? null : packetHistory.get(packetHistory.size() - 1);
        ctx.drawText(textRenderer, "Inbound categories", left, top, TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, "Outbound categories", rightColumnX, top, TEXT_PRIMARY, false);
        top += 14;
        int categoryHeight = Math.max(
                renderPacketBreakdownColumn(ctx, left, top, columnWidth, latestPackets != null ? latestPackets.inboundByCategory() : Map.of()),
                renderPacketBreakdownColumn(ctx, rightColumnX, top, columnWidth, latestPackets != null ? latestPackets.outboundByCategory() : Map.of())
        );
        top += categoryHeight + 12;

        ctx.drawText(textRenderer, "Inbound packet types", left, top, TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, "Outbound packet types", rightColumnX, top, TEXT_PRIMARY, false);
        top += 14;
        int typeHeight = Math.max(
                renderPacketBreakdownColumn(ctx, left, top, columnWidth, latestPackets != null ? latestPackets.inboundByType() : Map.of()),
                renderPacketBreakdownColumn(ctx, rightColumnX, top, columnWidth, latestPackets != null ? latestPackets.outboundByType() : Map.of())
        );
        top += typeHeight + 12;

        ctx.drawText(textRenderer, "Packet spike bookmarks", left, top, TEXT_PRIMARY, false);
        top += 14;
        top += renderPacketSpikeBookmarks(ctx, left, top, w - 16, NetworkPacketProfiler.getInstance().getSpikeHistory());
        endFullPageScissor(ctx);
    }


    private void renderDisk(DrawContext ctx, int x, int y, int w, int h) {
        SystemMetricsProfiler metrics = SystemMetricsProfiler.getInstance();
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        int graphWidth = getPreferredGraphWidth(w);
        int graphX = x + Math.max(PADDING, (w - graphWidth) / 2);
        beginFullPageScissor(ctx, x, y, w, h);
        ctx.drawText(textRenderer, "Disk throughput from OS counters during capture. Unsupported platforms may show unavailable.", left, top, TEXT_DIM, false);
        top += 14;
        drawMetricRow(ctx, left, top, w - 16, "Read", formatBytesPerSecond(snapshot.systemMetrics().diskReadBytesPerSecond()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 16, "Write", formatBytesPerSecond(snapshot.systemMetrics().diskWriteBytesPerSecond()));
        top += 20;
        int graphHeight = 132;
        renderMetricGraph(ctx, graphX - PADDING, top, graphWidth + (PADDING * 2), graphHeight, metrics.getOrderedDiskReadHistory(), metrics.getOrderedDiskWriteHistory(), "Disk Read/Write", "B/s", metrics.getHistorySpanSeconds());
        top += graphHeight + 2;
        renderGraphLegend(ctx, graphX, top, new String[]{"Read", "Write"}, new int[]{INTEL_COLOR, ACCENT_YELLOW});
        endFullPageScissor(ctx);
    }
    private void renderSystem(DrawContext ctx, int x, int y, int w, int h) {
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        beginFullPageScissor(ctx, x, y, w, h);
        SystemMetricsProfiler.Snapshot system = snapshot.systemMetrics();
        MemoryProfiler.Snapshot memory = snapshot.memory();

        ctx.drawText(textRenderer, "System metrics and runtime health.", left, top, TEXT_DIM, false);
        top += 20;
        drawMetricRow(ctx, left, top, w - 32, "VRAM Usage", formatBytesMb(system.vramUsedBytes()) + " / " + formatBytesMb(system.vramTotalBytes()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "VRAM Paging", system.vramPagingActive() ? formatBytesMb(system.vramPagingBytes()) : "none detected");
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Committed Virtual Memory", formatBytesMb(system.committedVirtualMemoryBytes()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Off-Heap Direct", formatBytesMb(system.directMemoryUsedBytes()) + " / " + formatBytesMb(system.directMemoryMaxBytes()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "CPU Core Load", formatPercent(system.cpuCoreLoadPercent()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "GPU Core Load", formatPercent(system.gpuCoreLoadPercent()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "CPU Temperature", formatTemperature(system.cpuTemperatureC()));
        top += 16;
        if (system.cpuTemperatureC() < 0) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth("Why CPU temp is unavailable: " + blankToUnknown(system.cpuTemperatureUnavailableReason()), w - 24), left + 6, top, ACCENT_YELLOW, false);
            top += 14;
        }
        drawMetricRow(ctx, left, top, w - 32, "GPU Temperature", formatTemperature(system.gpuTemperatureC()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Main Logic", blankToUnknown(system.mainLogicSummary()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Background", blankToUnknown(system.backgroundSummary()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "CPU Parallelism", blankToUnknown(system.cpuParallelismFlag()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Parallelism Efficiency", blankToUnknown(system.parallelismEfficiency()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Thread Load", String.format(Locale.ROOT, "%.1f%% total", system.totalThreadLoadPercent()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "High-Load Threads", system.activeHighLoadThreads() + " >50% | est physical cores " + system.estimatedPhysicalCores());
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Server Wait-Time", system.serverThreadWaitMs() + " ms waited | " + system.serverThreadBlockedMs() + " ms blocked");
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Worker Ratio", system.activeWorkers() + " active / " + system.idleWorkers() + " idle (" + String.format(Locale.ROOT, "%.2f", system.activeToIdleWorkerRatio()) + ")");
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "CPU Sensor Status", blankToUnknown(system.cpuSensorStatus()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Off-Heap Allocation Rate", formatBytesPerSecond(system.offHeapAllocationRateBytesPerSecond()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Current Biome", prettifyKey(blankToUnknown(system.currentBiome())));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Light Update Queue", blankToUnknown(system.lightUpdateQueue()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Max Entities In Hot Chunk", String.valueOf(system.maxEntitiesInHotChunk()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Packet Latency", system.packetProcessingLatencyMs() < 0 ? "unavailable" : String.format(Locale.ROOT, "%.1f ms [estimated]", system.packetProcessingLatencyMs()));
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "Packet Buffer Pressure", blankToUnknown(system.networkBufferSaturation()));
        top += 18;
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Export sessions keep the current runtime summary, findings, hotspots, and HTML report for offline inspection.", w - 24), left, top, TEXT_DIM, false);
        endFullPageScissor(ctx);
    }

    private void renderBlockEntities(DrawContext ctx, int x, int y, int w, int h) {
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        beginFullPageScissor(ctx, x, y, w, h);
        ctx.drawText(textRenderer, "Measured block-entity hotspots, chunk density, and findings focused on ticking/storage pressure.", left, top, TEXT_DIM, false);
        top += 18;
        top = renderBlockEntityHotspotSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestBlockEntityHotspots(), "Top Block Entity Hotspots") + 10;
        java.util.List<ProfilerManager.HotChunkSnapshot> hotChunks = ProfilerManager.getInstance().getLatestHotChunks();
        ctx.drawText(textRenderer, "Block-entity heavy chunks", left, top, TEXT_PRIMARY, false);
        top += 14;
        if (hotChunks.isEmpty()) {
            ctx.drawText(textRenderer, "No hot chunks sampled yet.", left + 6, top, TEXT_DIM, false);
            top += 12;
        } else {
            int shown = 0;
            for (ProfilerManager.HotChunkSnapshot hotChunk : hotChunks) {
                String line = String.format(Locale.ROOT, "%d,%d | %d block entities | top %s | score %.1f", hotChunk.chunkX(), hotChunk.chunkZ(), hotChunk.blockEntityCount(), cleanProfilerLabel(hotChunk.topBlockEntityClass()), hotChunk.activityScore());
                ctx.drawText(textRenderer, textRenderer.trimToWidth(line, w - 24), left + 6, top, hotChunk.blockEntityCount() >= 16 ? ACCENT_YELLOW : TEXT_DIM, false);
                top += 12;
                shown++;
                if (shown >= 6) {
                    break;
                }
            }
        }
        top += 8;
        top += renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        if (selectedLagChunk != null) {
            ctx.drawText(textRenderer, "Selected chunk block entities", left, top, TEXT_PRIMARY, false);
            top += 14;
            MinecraftClient client = MinecraftClient.getInstance();
            Map<String, Integer> blockEntityCounts = new HashMap<>();
            if (client.world != null) {
                for (BlockEntity blockEntity : client.world.getBlockEntities()) {
                    ChunkPos chunkPos = new ChunkPos(blockEntity.getPos());
                    if (chunkPos.x == selectedLagChunk.x && chunkPos.z == selectedLagChunk.z) {
                        blockEntityCounts.merge(cleanProfilerLabel(blockEntity.getClass().getSimpleName()), 1, Integer::sum);
                    }
                }
            }
            top = renderCountMap(ctx, left, top, w - 24, "Top block entities in selected chunk [measured counts]", blockEntityCounts) + 6;
        }
        endFullPageScissor(ctx);
    }

    private void renderWorldTab(DrawContext ctx, int x, int y, int w, int h) {
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        beginFullPageScissor(ctx, x, y, w, h);
        top = renderSectionHeader(ctx, left, top, "World", "Chunk pressure, entity hotspots, and block-entity drilldown grouped into world-focused views.");
        int lagTabW = 76;
        int blockTabW = 108;
        drawTopChip(ctx, left, top, lagTabW, 16, worldMiniTab == WorldMiniTab.LAG_MAP);
        drawTopChip(ctx, left + lagTabW + 6, top, blockTabW, 16, worldMiniTab == WorldMiniTab.BLOCK_ENTITIES);
        ctx.drawText(textRenderer, "Lag Map", left + 16, top + 4, worldMiniTab == WorldMiniTab.LAG_MAP ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, "Block Entities", left + lagTabW + 20, top + 4, worldMiniTab == WorldMiniTab.BLOCK_ENTITIES ? TEXT_PRIMARY : TEXT_DIM, false);
        top += 24;

        if (worldMiniTab == WorldMiniTab.LAG_MAP) {
            int mapWidth = Math.min(260, w - 24);
            int mapHeight = Math.min(260, h - 32);
            renderLagMap(ctx, left, top, mapWidth, mapHeight);
            top += mapHeight + 18;
            top = renderLagChunkDetail(ctx, left, top, w - 24, h - 40) + 8;
            ctx.drawText(textRenderer, "Top thread CPU load", left, top, TEXT_PRIMARY, false);
            top += 16;
            if (snapshot.systemMetrics().threadLoadPercentByName().isEmpty()) {
                ctx.drawText(textRenderer, "Waiting for JVM thread CPU samples...", left, top, TEXT_DIM, false);
                endFullPageScissor(ctx);
                return;
            }
            int shown = 0;
            for (Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot> entry : snapshot.systemMetrics().threadDetailsByName().entrySet()) {
                ThreadLoadProfiler.ThreadSnapshot details = entry.getValue();
                String summary = cleanProfilerLabel(entry.getKey()) + " | " + String.format(Locale.ROOT, "%.1f%% %s", details.loadPercent(), details.state());
                top = renderWrappedText(ctx, left, top, w - 24, summary, getHeatColor(details.loadPercent()));
                String waitLine = "blocked " + details.blockedCountDelta() + " / " + details.blockedTimeDeltaMs() + "ms | waited " + details.waitedCountDelta() + " / " + details.waitedTimeDeltaMs() + "ms | lock " + describeLock(details);
                top = renderWrappedText(ctx, left + 8, top, w - 32, waitLine, TEXT_DIM);
                shown++;
                if (shown >= 5) {
                    break;
                }
            }
            top += 8;
            top = renderEntityHotspotSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestEntityHotspots(), "Entity Hotspots") + 8;
            top += renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        } else {
            top = renderBlockEntityHotspotSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestBlockEntityHotspots(), "Block Entity Hotspots") + 8;
            if (selectedLagChunk != null) {
                ctx.drawText(textRenderer, "Selected chunk block entities", left, top, TEXT_PRIMARY, false);
                top += 14;
                MinecraftClient client = MinecraftClient.getInstance();
                Map<String, Integer> blockEntityCounts = new HashMap<>();
                if (client.world != null) {
                    for (BlockEntity blockEntity : client.world.getBlockEntities()) {
                        ChunkPos chunkPos = new ChunkPos(blockEntity.getPos());
                        if (chunkPos.x == selectedLagChunk.x && chunkPos.z == selectedLagChunk.z) {
                            blockEntityCounts.merge(cleanProfilerLabel(blockEntity.getClass().getSimpleName()), 1, Integer::sum);
                        }
                    }
                }
                top = renderCountMap(ctx, left, top, w - 24, "Top block entities in selected chunk [measured counts]", blockEntityCounts) + 8;
            } else {
                top = renderWrappedText(ctx, left, top, w - 24, "Select a chunk from the Lag Map mini-tab to inspect block entities for that chunk.", TEXT_DIM) + 8;
            }
            top += renderRuleFindingsSection(ctx, left, top, w - 24, ProfilerManager.getInstance().getLatestRuleFindings()) + 8;
        }
        endFullPageScissor(ctx);
    }

    private void renderLagMap(DrawContext ctx, int x, int y, int width, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        ctx.drawText(textRenderer, "Lag Map", x, y, TEXT_PRIMARY, false);
        if (client.player == null || client.world == null) {
            ctx.drawText(textRenderer, "World not loaded.", x, y + 14, TEXT_DIM, false);
            return;
        }

        double serverTickMs = TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0;
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Entity entity : client.world.getEntities()) {
            ChunkPos chunkPos = entity.getChunkPos();
            long key = (((long) chunkPos.x) << 32) ^ (chunkPos.z & 0xFFFFFFFFL);
            counts.merge(key, 1, Integer::sum);
        }

        ChunkPos playerChunk = client.player.getChunkPos();
        int radius = 4;
        int cell = Math.max(12, Math.min(20, Math.min(width, height - 18) / ((radius * 2) + 1)));
        int maxCount = Math.max(1, counts.values().stream().mapToInt(Integer::intValue).max().orElse(1));
        int mapTop = y + 14;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = playerChunk.x + dx;
                int chunkZ = playerChunk.z + dz;
                long key = (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
                int count = counts.getOrDefault(key, 0);
                double intensity = count / (double) maxCount;
                int color = serverTickMs > 40.0
                        ? (0x22000000 | ((int) (40 + (215 * intensity)) << 16))
                        : (0x22000000 | ((int) (40 + (120 * intensity)) << 8));
                int px = x + (dx + radius) * cell;
                int py = mapTop + (dz + radius) * cell;
                ctx.fill(px, py, px + cell - 1, py + cell - 1, color);
                if (dx == 0 && dz == 0) {
                    ctx.fill(px + 3, py + 3, px + cell - 4, py + cell - 4, 0x99FFFFFF);
                }
                if (selectedLagChunk != null && selectedLagChunk.x == chunkX && selectedLagChunk.z == chunkZ) {
                    ctx.fill(px, py, px + cell - 1, py + 1, 0xFFFFFFFF);
                    ctx.fill(px, py + cell - 2, px + cell - 1, py + cell - 1, 0xFFFFFFFF);
                    ctx.fill(px, py, px + 1, py + cell - 1, 0xFFFFFFFF);
                    ctx.fill(px + cell - 2, py, px + cell - 1, py + cell - 1, 0xFFFFFFFF);
                }
            }
        }

        String legend = serverTickMs > 40.0
                ? "High server tick: dense chunks highlighted red"
                : "Server tick stable: map shows relative entity density";
        ctx.drawText(textRenderer, legend, x, mapTop + (cell * ((radius * 2) + 1)) + 4, TEXT_DIM, false);
    }

    private void renderSensorsPanel(DrawContext ctx, int x, int y, int width, SystemMetricsProfiler.Snapshot system) {
        ctx.fill(x, y, x + width, y + 96, 0x14000000);
        String source = blankToUnknown(system.sensorSource());
        String[] sourceParts = source.split("\\| Tried: ", 2);
        String activeSource = sourceParts[0].trim();
        String attempts = sourceParts.length > 1 ? sourceParts[1].trim() : "provider attempts unavailable";
        ctx.drawText(textRenderer, "Sensors", x + 6, y + 4, TEXT_PRIMARY, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(activeSource, width - 12), x + 6, y + 18, TEXT_DIM, false);
        String tempSummary = "CPU temp " + formatTemperature(system.cpuTemperatureC()) + " | GPU temp " + formatTemperature(system.gpuTemperatureC());
        ctx.drawText(textRenderer, textRenderer.trimToWidth(tempSummary, width - 12), x + 6, y + 32, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Attempts: " + attempts, width - 12), x + 6, y + 46, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("CPU status: " + blankToUnknown(system.cpuSensorStatus()), width - 12), x + 6, y + 60, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Last provider error: " + blankToUnknown(system.sensorErrorCode()), width - 12), x + 6, y + 74, ACCENT_YELLOW, false);
    }


    private int renderPacketBreakdownColumn(DrawContext ctx, int x, int y, int width, Map<String, Long> breakdown) {
        if (breakdown.isEmpty()) {
            ctx.drawText(textRenderer, "No packet attribution yet.", x, y, TEXT_DIM, false);
            return 12;
        }
        int rowY = y;
        int shown = 0;
        for (Map.Entry<String, Long> entry : breakdown.entrySet()) {
            String label = textRenderer.trimToWidth(entry.getKey(), Math.max(60, width - 46));
            ctx.drawText(textRenderer, label, x, rowY, TEXT_DIM, false);
            String value = String.valueOf(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - textRenderer.getWidth(value), rowY, TEXT_PRIMARY, false);
            rowY += 12;
            shown++;
            if (shown >= 6) {
                break;
            }
        }
        return rowY - y;
    }


    private int renderLagChunkDetail(DrawContext ctx, int x, int y, int width, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || selectedLagChunk == null) {
            ctx.drawText(textRenderer, "Click a chunk in the world map to inspect its entities and block entities.", x, y, TEXT_DIM, false);
            return y + 12;
        }
        ctx.drawText(textRenderer, "Selected chunk " + selectedLagChunk.x + ", " + selectedLagChunk.z, x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        Map<String, Integer> entityCounts = new HashMap<>();
        for (Entity entity : client.world.getEntities()) {
            ChunkPos chunkPos = entity.getChunkPos();
            if (chunkPos.x == selectedLagChunk.x && chunkPos.z == selectedLagChunk.z) {
                entityCounts.merge(cleanEntityName(entity), 1, Integer::sum);
            }
        }
        Map<String, Integer> blockEntityCounts = new HashMap<>();
        for (BlockEntity blockEntity : client.world.getBlockEntities()) {
            ChunkPos chunkPos = new ChunkPos(blockEntity.getPos());
            if (chunkPos.x == selectedLagChunk.x && chunkPos.z == selectedLagChunk.z) {
                blockEntityCounts.merge(cleanProfilerLabel(blockEntity.getClass().getSimpleName()), 1, Integer::sum);
            }
        }
        int totalEntities = entityCounts.values().stream().mapToInt(Integer::intValue).sum();
        int totalBlockEntities = blockEntityCounts.values().stream().mapToInt(Integer::intValue).sum();
        java.util.List<Integer> activityHistory = ProfilerManager.getInstance().getChunkActivityHistory(selectedLagChunk);
        int maxEntitiesChunk = ProfilerManager.getInstance().getLatestHotChunks().stream().mapToInt(ProfilerManager.HotChunkSnapshot::entityCount).max().orElse(totalEntities);
        rowY = renderWrappedText(ctx, x, rowY, width, String.format(Locale.ROOT, "Measured counts: %d entities | %d block entities | %d activity samples | chunk max %d", totalEntities, totalBlockEntities, activityHistory.size(), maxEntitiesChunk), TEXT_DIM);
        String topEntityClass = entityCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
        String topBlockEntityClass = blockEntityCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
        rowY = renderWrappedText(ctx, x, rowY + 2, width, "Top hot classes: entity " + topEntityClass + " | block entity " + topBlockEntityClass, TEXT_DIM);
        boolean chunkIoHint = ProfilerManager.getInstance().getLatestLockSummaries().stream()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.contains("chunk") || line.contains("region") || line.contains("anvil") || line.contains("poi"));
        if (chunkIoHint) {
            rowY = renderWrappedText(ctx, x, rowY + 2, width, "Chunk I/O lock hint active in current window. Cross-check blocked threads below and on the System tab.", ACCENT_YELLOW);
        }
        rowY += renderSimpleHistoryGraph(ctx, x, rowY + 2, width, 64, activityHistory, "Chunk activity over time [measured]", "activity") + 8;
        rowY = renderCountMap(ctx, x, rowY, width, "Top entities [measured counts]", entityCounts) + 6;
        rowY = renderCountMap(ctx, x, rowY, width, "Top block entities [measured counts]", blockEntityCounts) + 6;
        return rowY;
    }


    private int renderCountMap(DrawContext ctx, int x, int y, int width, String title, Map<String, Integer> counts) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        if (counts.isEmpty()) {
            ctx.drawText(textRenderer, "none", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).toList()) {
            String label = textRenderer.trimToWidth(cleanProfilerLabel(entry.getKey()), Math.max(60, width - 36));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_PRIMARY, false);
            String value = String.valueOf(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - textRenderer.getWidth(value), rowY, TEXT_DIM, false);
            rowY += 12;
            shown++;
            if (shown >= 5) {
                break;
            }
        }
        return rowY;
    }


    private int renderRuleFindingsSection(DrawContext ctx, int x, int y, int width, java.util.List<ProfilerManager.RuleFinding> findings) {
        ctx.drawText(textRenderer, "Known problem detector", x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        if (findings == null || findings.isEmpty()) {
            ctx.drawText(textRenderer, "No active findings in the current window.", x + 6, rowY, TEXT_DIM, false);
            return 24;
        }
        int shown = 0;
        for (ProfilerManager.RuleFinding finding : findings) {
            int color = switch (finding.severity()) {
                case "warning" -> ACCENT_YELLOW;
                case "error" -> ACCENT_RED;
                default -> TEXT_DIM;
            };
            String heading = finding.category() + " | " + finding.severity() + " | " + finding.confidence();
            ctx.drawText(textRenderer, textRenderer.trimToWidth(heading, width), x + 6, rowY, color, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(finding.message(), width - 6), x + 12, rowY, TEXT_PRIMARY, false);
            rowY += 14;
            shown++;
            if (shown >= 5) {
                break;
            }
        }
        return rowY - y;
    }

    private void renderThreadWaitSection(DrawContext ctx, int x, int y, int width, Map<String, ThreadLoadProfiler.ThreadSnapshot> details) {
        ctx.drawText(textRenderer, "Blocked / waiting analysis", x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        java.util.List<Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot>> interesting = details.entrySet().stream()
                .filter(entry -> entry.getValue().blockedCountDelta() > 0 || entry.getValue().waitedCountDelta() > 0 || "BLOCKED".equals(entry.getValue().state()) || "WAITING".equals(entry.getValue().state()))
                .limit(5)
                .toList();
        if (interesting.isEmpty()) {
            ctx.drawText(textRenderer, "No blocked or waiting thread anomalies in the current window.", x + 6, rowY, TEXT_DIM, false);
            return;
        }
        for (Map.Entry<String, ThreadLoadProfiler.ThreadSnapshot> entry : interesting) {
            ThreadLoadProfiler.ThreadSnapshot detail = entry.getValue();
            String summary = entry.getKey() + " | " + detail.state() + " | blocked " + detail.blockedCountDelta() + " | waited " + detail.waitedCountDelta();
            ctx.drawText(textRenderer, textRenderer.trimToWidth(summary, width), x + 6, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            if (detail.lockName() != null && !detail.lockName().isBlank()) {
                ctx.drawText(textRenderer, textRenderer.trimToWidth("Lock: " + detail.lockName(), width - 6), x + 12, rowY, TEXT_DIM, false);
                rowY += 12;
            }
        }
    }

    private int renderPacketSpikeBookmarks(DrawContext ctx, int x, int y, int width, java.util.List<NetworkPacketProfiler.SpikeSnapshot> spikes) {
        if (spikes == null || spikes.isEmpty()) {
            ctx.drawText(textRenderer, "No packet spike bookmarks yet.", x + 6, y, TEXT_DIM, false);
            return 14;
        }
        int rowY = y;
        int shown = 0;
        for (NetworkPacketProfiler.SpikeSnapshot spike : spikes) {
            String header = "Spike @ " + formatDuration(Math.max(0L, System.currentTimeMillis() - spike.capturedAtEpochMillis())) + " ago";
            ctx.drawText(textRenderer, header, x + 6, rowY, TEXT_DIM, false);
            rowY += 12;
            String inbound = "In categories: " + formatPacketSummary(spike.inboundByCategory());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(inbound, width - 12), x + 12, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            String inboundTypes = "In types: " + formatPacketSummary(spike.inboundByType());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(inboundTypes, width - 12), x + 12, rowY, TEXT_DIM, false);
            rowY += 12;
            String outbound = "Out categories: " + formatPacketSummary(spike.outboundByCategory());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(outbound, width - 12), x + 12, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            String outboundTypes = "Out types: " + formatPacketSummary(spike.outboundByType());
            ctx.drawText(textRenderer, textRenderer.trimToWidth(outboundTypes, width - 12), x + 12, rowY, TEXT_DIM, false);
            rowY += 14;
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY - y;
    }

    private void renderSpikeInspector(DrawContext ctx, int x, int y, int width) {
        ctx.drawText(textRenderer, "Spike inspector", x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        if (snapshot.spikes().isEmpty()) {
            ctx.drawText(textRenderer, "No spike bookmarks yet. Capture a hitch to inspect it here.", x + 6, rowY, TEXT_DIM, false);
            return;
        }
        ProfilerManager.SpikeCapture latest = snapshot.spikes().getFirst();
        ProfilerManager.SpikeCapture worst = snapshot.spikes().stream().max((a, b) -> Double.compare(a.frameDurationMs(), b.frameDurationMs())).orElse(latest);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT, "Latest spike %.1f ms | stutter %.1f | %s", latest.frameDurationMs(), latest.stutterScore(), latest.likelyBottleneck()), width), x + 6, rowY, ACCENT_YELLOW, false);
        rowY += 12;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(String.format(Locale.ROOT, "Worst spike %.1f ms | entities %d | chunks %d/%d", worst.frameDurationMs(), worst.entityCounts().totalEntities(), worst.chunkCounts().loadedChunks(), worst.chunkCounts().renderedChunks()), width), x + 6, rowY, TEXT_PRIMARY, false);
        rowY += 12;
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Top threads: " + String.join(" | ", latest.topThreads()), width), x + 6, rowY, TEXT_DIM, false);
        rowY += 12;
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Top CPU mods: " + String.join(" | ", latest.topCpuMods()), width), x + 6, rowY, TEXT_DIM, false);
        rowY += 12;
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Top render phases: " + String.join(" | ", latest.topRenderPhases()), width), x + 6, rowY, TEXT_DIM, false);
        rowY += 14;
        renderRuleFindingsSection(ctx, x + 6, rowY, width - 6, latest.findings());
    }

    private int renderSimpleHistoryGraph(DrawContext ctx, int x, int y, int width, int height, java.util.List<Integer> history, String title, String units) {
        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_DIM, false);
        int gx = x;
        int gy = y + 14;
        int graphWidth = Math.max(80, width);
        int graphHeight = Math.max(36, height - 18);
        ctx.fill(gx - 2, gy - 2, gx + graphWidth + 2, gy + graphHeight + 2, 0x66000000);
        if (history == null || history.isEmpty()) {
            ctx.drawText(textRenderer, "No activity history yet.", gx + 8, gy + graphHeight / 2 - 4, TEXT_DIM, false);
            return height;
        }
        int max = history.stream().mapToInt(Integer::intValue).max().orElse(1);
        String topLabel = String.valueOf(max);
        String midLabel = String.valueOf(Math.max(0, max / 2));
        ctx.drawText(textRenderer, topLabel, gx + graphWidth - textRenderer.getWidth(topLabel), gy - 10, TEXT_DIM, false);
        ctx.drawText(textRenderer, midLabel, gx + graphWidth - textRenderer.getWidth(midLabel), gy + graphHeight / 2 - 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, "0", gx + graphWidth - textRenderer.getWidth("0"), gy + graphHeight - 8, TEXT_DIM, false);
        for (int px = 0; px < graphWidth; px++) {
            int start = (int) Math.floor(px * history.size() / (double) graphWidth);
            int end = (int) Math.floor((px + 1) * history.size() / (double) graphWidth) - 1;
            if (end < start) {
                end = start;
            }
            start = Math.max(0, Math.min(history.size() - 1, start));
            end = Math.max(0, Math.min(history.size() - 1, end));
            int peak = 0;
            for (int i = start; i <= end; i++) {
                peak = Math.max(peak, history.get(i));
            }
            int barHeight = (int) Math.min(graphHeight, Math.round((peak / (double) Math.max(1, max)) * graphHeight));
            if (barHeight <= 0) {
                continue;
            }
            ctx.fill(gx + px, gy + graphHeight - barHeight, gx + px + 1, gy + graphHeight, 0xFF5EA9FF);
        }
        return height;
    }

    private int renderEntityHotspotSection(DrawContext ctx, int x, int y, int width, java.util.List<ProfilerManager.EntityHotspot> hotspots, String title) {
        ctx.drawText(textRenderer, title, x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        if (hotspots == null || hotspots.isEmpty()) {
            ctx.drawText(textRenderer, "No entity hotspots in the current window.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (ProfilerManager.EntityHotspot hotspot : hotspots) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.className() + " x" + hotspot.count(), width), x + 6, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.heuristic(), width - 6), x + 12, rowY, TEXT_DIM, false);
            rowY += 14;
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    private int renderBlockEntityHotspotSection(DrawContext ctx, int x, int y, int width, java.util.List<ProfilerManager.BlockEntityHotspot> hotspots, String title) {
        ctx.drawText(textRenderer, title, x, y, TEXT_PRIMARY, false);
        int rowY = y + 14;
        if (hotspots == null || hotspots.isEmpty()) {
            ctx.drawText(textRenderer, "No block entity hotspots in the current window.", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (ProfilerManager.BlockEntityHotspot hotspot : hotspots) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.className() + " x" + hotspot.count(), width), x + 6, rowY, TEXT_PRIMARY, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(hotspot.heuristic(), width - 6), x + 12, rowY, TEXT_DIM, false);
            rowY += 14;
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return rowY;
    }

    private String formatPacketSummary(Map<String, Long> packetMap) {
        if (packetMap == null || packetMap.isEmpty()) {
            return "none";
        }
        return packetMap.entrySet().stream()
                .limit(3)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .reduce((left, right) -> left + " | " + right)
                .orElse("none");
    }

    private String formatTopThreads(Map<String, Double> threadLoads, int maxThreads) {
        if (threadLoads == null || threadLoads.isEmpty()) {
            return "no recent thread samples";
        }
        return threadLoads.entrySet().stream()
                .limit(maxThreads)
                .map(entry -> entry.getKey() + " " + String.format("%.1f%%", entry.getValue()))
                .reduce((left, right) -> left + " | " + right)
                .orElse("no recent thread samples");
    }


    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = toLogicalX(click.x());
        double mouseY = toLogicalY(click.y());
        focusedSearchTable = null;

        if (isInside(mouseX, mouseY, PADDING + 106, 3, 128, 14)) {
            ProfilerManager.getInstance().cycleMode();
            return true;
        }

        if (isInside(mouseX, mouseY, getScreenWidth() - 250, 5, 90, 12)) {
            ConfigManager.setHudEnabled(!ConfigManager.isHudEnabled());
            return true;
        }

        if (isInside(mouseX, mouseY, getScreenWidth() - 116, 4, 108, 12)) {
            ProfilerManager.getInstance().exportSession();
            return true;
        }

        int tabY = getTabY();
        int tabW = Math.max(66, Math.min(84, (getScreenWidth() - (PADDING * 2) - ((TAB_NAMES.length - 1) * 2)) / TAB_NAMES.length));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int tx = PADDING + i * (tabW + 2);
            if (isInside(mouseX, mouseY, tx, tabY, tabW, TAB_HEIGHT)) {
                activeTab = i;
                lastOpenedTab = activeTab;
                scrollOffset = 0;
                return true;
            }
        }

        if (activeTab == 0) {
            int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
            int listW = getScreenWidth() - detailW - PADDING;
            if (isInside(mouseX, mouseY, listW - 160, getContentY() + PADDING + 24, 152, 16)) {
                focusedSearchTable = TableId.TASKS;
                return true;
            }
            if (handleTaskHeaderClick(mouseX, mouseY, listW)) {
                return true;
            }
            String clickedMod = findTaskRowAt(mouseX, mouseY);
            if (clickedMod != null) {
                selectedTaskMod = clickedMod;
                return true;
            }
        }

        if (activeTab == 1) {
            int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
            int listW = getScreenWidth() - detailW - PADDING;
            if (isInside(mouseX, mouseY, listW - 160, getContentY() + PADDING + 24, 152, 16)) {
                focusedSearchTable = TableId.GPU;
                return true;
            }
            if (handleGpuHeaderClick(mouseX, mouseY, listW)) {
                return true;
            }
            String clickedMod = findGpuRowAt(mouseX, mouseY);
            if (clickedMod != null) {
                selectedGpuMod = clickedMod;
                return true;
            }
        }

        if (activeTab == 4) {
            int sharedPanelW = snapshot.sharedMemoryFamilies().isEmpty() ? 0 : Math.min(280, Math.max(220, getScreenWidth() / 4));
            int tableW = getScreenWidth() - sharedPanelW - (sharedPanelW > 0 ? PADDING : 0);
            if (isInside(mouseX, mouseY, tableW - 160, getContentY() + PADDING, 152, 16)) {
                focusedSearchTable = TableId.MEMORY;
                return true;
            }
            if (handleMemoryHeaderClick(mouseX, mouseY, tableW)) {
                return true;
            }
            String clickedMod = findMemoryRowAt(mouseX, mouseY);
            if (clickedMod != null) {
                selectedMemoryMod = clickedMod;
                return true;
            }
            String clickedFamily = findSharedFamilyAt(mouseX, mouseY);
            if (clickedFamily != null) {
                selectedSharedFamily = clickedFamily;
                return true;
            }
        }

        if (activeTab == 9) {
            int left = PADDING;
            int top = getContentY() + PADDING + 28 - scrollOffset;
            if (isInside(mouseX, mouseY, left, top, 76, 16)) {
                worldMiniTab = WorldMiniTab.LAG_MAP;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 82, top, 108, 16)) {
                worldMiniTab = WorldMiniTab.BLOCK_ENTITIES;
                return true;
            }
            if (worldMiniTab == WorldMiniTab.LAG_MAP) {
                ChunkPos clickedChunk = findLagMapChunkAt(mouseX, mouseY);
                if (clickedChunk != null) {
                    selectedLagChunk = clickedChunk;
                    return true;
                }
            }
        }

        if (activeTab == 11) {
            int left = PADDING;
            int top = getContentY() + PADDING + 18 - scrollOffset;
            int[] offsets = {0, 22, 44, 66, 116, 138, 160, 182, 204, 226, 248, 270, 292, 314, 336, 358, 380, 432, 454, 476, 498, 520, 542, 564, 586, 608, 630, 652};
            Runnable[] actions = {
                () -> ProfilerManager.getInstance().toggleSessionLogging(),
                ConfigManager::cycleSessionDurationSeconds,
                ConfigManager::cycleMetricsUpdateIntervalMs,
                ConfigManager::cycleProfilerUpdateDelayMs,
                () -> ConfigManager.setHudEnabled(!ConfigManager.isHudEnabled()),
                ConfigManager::cycleHudPosition,
                ConfigManager::cycleHudLayoutMode,
                ConfigManager::cycleHudTriggerMode,
                ConfigManager::toggleHudShowFps,
                ConfigManager::toggleHudShowFrame,
                ConfigManager::toggleHudShowTicks,
                ConfigManager::toggleHudShowUtilization,
                ConfigManager::toggleHudShowTemperatures,
                ConfigManager::toggleHudShowParallelism,
                ConfigManager::toggleHudShowMemory,
                ConfigManager::toggleHudShowWorld,
                ConfigManager::toggleHudShowSession,
                () -> ConfigManager.toggleTasksColumn("cpu"),
                () -> ConfigManager.toggleTasksColumn("threads"),
                () -> ConfigManager.toggleTasksColumn("samples"),
                () -> ConfigManager.toggleTasksColumn("invokes"),
                () -> ConfigManager.toggleGpuColumn("pct"),
                () -> ConfigManager.toggleGpuColumn("threads"),
                () -> ConfigManager.toggleGpuColumn("gpums"),
                () -> ConfigManager.toggleGpuColumn("rsamples"),
                () -> ConfigManager.toggleMemoryColumn("classes"),
                () -> ConfigManager.toggleMemoryColumn("mb"),
                () -> ConfigManager.toggleMemoryColumn("pct")
            };
            for (int i = 0; i < offsets.length; i++) {
                if (isInside(mouseX, mouseY, left, top + offsets[i], getScreenWidth() - 16, 16)) {
                    actions[i].run();
                    return true;
                }
            }
        }

        return super.mouseClicked(click, doubled);
    }


    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        mouseX = toLogicalX(mouseX);
        mouseY = toLogicalY(mouseY);
        int maxScroll = getMaxScrollOffset();
        if (maxScroll <= 0) {
            scrollOffset = 0;
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (verticalAmount * 18.0)));
        return true;
    }

    private int getMaxScrollOffset() {
        int visibleHeight = Math.max(1, getScreenHeight() - getContentY() - PADDING);
        int contentHeight = switch (activeTab) {
            case 0 -> Math.max(visibleHeight, 40 + (snapshot.cpuMods().size() * ROW_HEIGHT));
            case 1 -> Math.max(visibleHeight, 40 + ((int) snapshot.cpuMods().values().stream().filter(entry -> entry.renderSamples() > 0).count() * ROW_HEIGHT));
            case 2 -> Math.max(visibleHeight, 32 + (snapshot.renderPhases().size() * ROW_HEIGHT));
            case 3 -> Math.max(visibleHeight, 56 + (snapshot.startupRows().size() * ROW_HEIGHT));
            case 4 -> Math.max(visibleHeight, 214 + (snapshot.memoryMods().size() * ROW_HEIGHT));
            case 5 -> Math.max(visibleHeight, 44 + (Math.min(20, snapshot.flamegraphStacks().size()) * 12));
            case 6 -> Math.max(visibleHeight, 430);
            case 7 -> Math.max(visibleHeight, 560);
            case 8 -> Math.max(visibleHeight, 240);
            case 9 -> Math.max(visibleHeight, 1160);
            case 10 -> Math.max(visibleHeight, 1100);
            case 11 -> Math.max(visibleHeight, 760);
            default -> visibleHeight;
        };
        return Math.max(0, contentHeight - visibleHeight);
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (focusedSearchTable == null || !input.isValidChar()) {
            return super.charTyped(input);
        }
        String current = getSearchValue(focusedSearchTable);
        setSearchValue(focusedSearchTable, current + input.asString());
        scrollOffset = 0;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (focusedSearchTable != null) {
            if (input.key() == 259) {
                String current = getSearchValue(focusedSearchTable);
                if (!current.isEmpty()) {
                    setSearchValue(focusedSearchTable, current.substring(0, current.length() - 1));
                }
                return true;
            }
            if (input.key() == 256) {
                focusedSearchTable = null;
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private String getSearchValue(TableId tableId) {
        return switch (tableId) {
            case TASKS -> tasksSearch;
            case GPU -> gpuSearch;
            case MEMORY -> memorySearch;
        };
    }

    private void setSearchValue(TableId tableId, String value) {
        String normalized = value == null ? "" : value;
        switch (tableId) {
            case TASKS -> tasksSearch = normalized;
            case GPU -> gpuSearch = normalized;
            case MEMORY -> memorySearch = normalized;
        }
    }

    private List<String> getTaskRows() {
        Map<String, CpuSamplingProfiler.Snapshot> cpu = snapshot.cpuMods();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = snapshot.cpuDetails();
        Map<String, ModTimingSnapshot> invokes = snapshot.modInvokes();
        LinkedHashSet<String> mods = new LinkedHashSet<>();
        mods.addAll(cpu.keySet());
        mods.addAll(invokes.keySet());
        String query = tasksSearch.toLowerCase(Locale.ROOT);
        List<String> rows = new ArrayList<>();
        for (String modId : mods) {
            String haystack = (modId + " " + getDisplayName(modId)).toLowerCase(Locale.ROOT);
            if (query.isBlank() || haystack.contains(query)) {
                rows.add(modId);
            }
        }
        rows.sort(taskComparator(cpu, cpuDetails, invokes));
        if (!taskSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> taskComparator(Map<String, CpuSamplingProfiler.Snapshot> cpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails, Map<String, ModTimingSnapshot> invokes) {
        return switch (taskSort) {
            case THREADS -> Comparator.comparingInt((String modId) -> cpuDetails.get(modId) == null ? 0 : cpuDetails.get(modId).sampledThreadCount());
            case SAMPLES -> Comparator.comparingLong((String modId) -> cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples());
            case INVOKES -> Comparator.comparingLong((String modId) -> invokes.getOrDefault(modId, new ModTimingSnapshot(0, 0)).calls());
            case CPU -> Comparator.comparingDouble((String modId) -> snapshot.totalCpuSamples() > 0 ? (cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).totalSamples() * 100.0 / snapshot.totalCpuSamples()) : 0.0);
        };
    }

    private List<String> getGpuRows() {
        Map<String, CpuSamplingProfiler.Snapshot> cpu = snapshot.cpuMods();
        Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails = snapshot.cpuDetails();
        long totalRenderSamples = snapshot.totalRenderSamples();
        long totalGpuNs = snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum();
        String query = gpuSearch.toLowerCase(Locale.ROOT);
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, CpuSamplingProfiler.Snapshot> entry : cpu.entrySet()) {
            if (entry.getValue().renderSamples() <= 0) {
                continue;
            }
            String modId = entry.getKey();
            String haystack = (modId + " " + getDisplayName(modId)).toLowerCase(Locale.ROOT);
            if (query.isBlank() || haystack.contains(query)) {
                rows.add(modId);
            }
        }
        rows.sort(gpuComparator(cpu, cpuDetails, totalRenderSamples, totalGpuNs));
        if (!gpuSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> gpuComparator(Map<String, CpuSamplingProfiler.Snapshot> cpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails, long totalRenderSamples, long totalGpuNs) {
        return switch (gpuSort) {
            case THREADS -> Comparator.comparingInt((String modId) -> cpuDetails.get(modId) == null ? 0 : cpuDetails.get(modId).sampledThreadCount());
            case GPU_MS -> Comparator.comparingDouble((String modId) -> totalRenderSamples > 0 ? ((cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).renderSamples() / (double) totalRenderSamples) * totalGpuNs / 1_000_000.0) : 0.0);
            case RENDER_SAMPLES -> Comparator.comparingLong((String modId) -> cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).renderSamples());
            case EST_GPU -> Comparator.comparingDouble((String modId) -> totalRenderSamples > 0 ? (cpu.getOrDefault(modId, new CpuSamplingProfiler.Snapshot(0, 0, 0)).renderSamples() * 100.0 / totalRenderSamples) : 0.0);
        };
    }

    private List<String> getMemoryRows() {
        Map<String, Long> memoryMods = snapshot.memoryMods();
        Map<String, Map<String, Long>> memoryClassesByMod = snapshot.memoryClassesByMod();
        long totalAttributedBytes = Math.max(1, memoryMods.values().stream().mapToLong(Long::longValue).sum());
        String query = memorySearch.toLowerCase(Locale.ROOT);
        List<String> rows = new ArrayList<>();
        for (String modId : memoryMods.keySet()) {
            String haystack = (modId + " " + getDisplayName(modId)).toLowerCase(Locale.ROOT);
            if (query.isBlank() || haystack.contains(query)) {
                rows.add(modId);
            }
        }
        rows.sort(memoryComparator(memoryMods, memoryClassesByMod, totalAttributedBytes));
        if (!memorySortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> memoryComparator(Map<String, Long> memoryMods, Map<String, Map<String, Long>> memoryClassesByMod, long totalAttributedBytes) {
        return switch (memorySort) {
            case CLASS_COUNT -> Comparator.comparingInt((String modId) -> memoryClassesByMod.getOrDefault(modId, Map.of()).size());
            case PERCENT -> Comparator.comparingDouble((String modId) -> memoryMods.getOrDefault(modId, 0L) * 100.0 / totalAttributedBytes);
            case MEMORY_MB -> Comparator.comparingLong((String modId) -> memoryMods.getOrDefault(modId, 0L));
        };
    }

    private boolean isColumnVisible(TableId tableId, String key) {
        return switch (tableId) {
            case TASKS -> ConfigManager.isTasksColumnVisible(key);
            case GPU -> ConfigManager.isGpuColumnVisible(key);
            case MEMORY -> ConfigManager.isMemoryColumnVisible(key);
        };
    }

    private void toggleColumn(TableId tableId, String key) {
        switch (tableId) {
            case TASKS -> ConfigManager.toggleTasksColumn(key);
            case GPU -> ConfigManager.toggleGpuColumn(key);
            case MEMORY -> ConfigManager.toggleMemoryColumn(key);
        }
    }

    private boolean handleTaskHeaderClick(double mouseX, double mouseY, int listW) {
        int headerY = getContentY() + PADDING + 50;
        int pctX = listW - 206;
        int threadsX = listW - 146;
        int samplesX = listW - 92;
        int invokesX = listW - 42;
        if (isInside(mouseX, mouseY, pctX, headerY, 54, 14)) { toggleTaskSort(TaskSort.CPU); return true; }
        if (isInside(mouseX, mouseY, threadsX, headerY, 62, 14)) { toggleTaskSort(TaskSort.THREADS); return true; }
        if (isInside(mouseX, mouseY, samplesX, headerY, 62, 14)) { toggleTaskSort(TaskSort.SAMPLES); return true; }
        if (isInside(mouseX, mouseY, invokesX, headerY, 58, 14)) { toggleTaskSort(TaskSort.INVOKES); return true; }
        return false;
    }

    private boolean handleGpuHeaderClick(double mouseX, double mouseY, int listW) {
        int headerY = getContentY() + PADDING + 50;
        int pctX = listW - 232;
        int threadsX = listW - 172;
        int gpuMsX = listW - 108;
        int renderSamplesX = listW - 42;
        if (isInside(mouseX, mouseY, pctX, headerY, 64, 14)) { toggleGpuSort(GpuSort.EST_GPU); return true; }
        if (isInside(mouseX, mouseY, threadsX, headerY, 62, 14)) { toggleGpuSort(GpuSort.THREADS); return true; }
        if (isInside(mouseX, mouseY, gpuMsX, headerY, 54, 14)) { toggleGpuSort(GpuSort.GPU_MS); return true; }
        if (isInside(mouseX, mouseY, renderSamplesX, headerY, 42, 14)) { toggleGpuSort(GpuSort.RENDER_SAMPLES); return true; }
        return false;
    }

    private boolean handleMemoryHeaderClick(double mouseX, double mouseY, int tableW) {
        int headerY = getContentY() + PADDING + 96;
        int classesX = tableW - 140;
        int mbX = tableW - 94;
        int pctX = tableW - 42;
        if (isInside(mouseX, mouseY, classesX, headerY, 34, 14)) { toggleMemorySort(MemorySort.CLASS_COUNT); return true; }
        if (isInside(mouseX, mouseY, mbX, headerY, 28, 14)) { toggleMemorySort(MemorySort.MEMORY_MB); return true; }
        if (isInside(mouseX, mouseY, pctX, headerY, 20, 14)) { toggleMemorySort(MemorySort.PERCENT); return true; }
        return false;
    }

    private void toggleTaskSort(TaskSort sort) {
        if (taskSort == sort) {
            taskSortDescending = !taskSortDescending;
        } else {
            taskSort = sort;
            taskSortDescending = true;
        }
    }

    private void toggleGpuSort(GpuSort sort) {
        if (gpuSort == sort) {
            gpuSortDescending = !gpuSortDescending;
        } else {
            gpuSort = sort;
            gpuSortDescending = true;
        }
    }

    private void toggleMemorySort(MemorySort sort) {
        if (memorySort == sort) {
            memorySortDescending = !memorySortDescending;
        } else {
            memorySort = sort;
            memorySortDescending = true;
        }
    }

    private String findTaskRowAt(double mouseX, double mouseY) {
        if (activeTab != 0) {
            return null;
        }
        int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
        int listW = width - detailW - PADDING;
        return findRowAt(mouseX, mouseY, getContentY() + PADDING + 66, listW, getTaskRows());
    }

    private String findGpuRowAt(double mouseX, double mouseY) {
        if (activeTab != 1) {
            return null;
        }
        int detailW = Math.min(420, Math.max(320, getScreenWidth() / 3));
        int listW = width - detailW - PADDING;
        return findRowAt(mouseX, mouseY, getContentY() + PADDING + 66, listW, getGpuRows());
    }

    private String findMemoryRowAt(double mouseX, double mouseY) {
        if (activeTab != 4) {
            return null;
        }
        int sharedPanelW = snapshot.sharedMemoryFamilies().isEmpty() ? 0 : Math.min(280, Math.max(220, getScreenWidth() / 4));
        int tableW = getScreenWidth() - sharedPanelW - (sharedPanelW > 0 ? PADDING : 0);
        return findRowAt(mouseX, mouseY, getContentY() + PADDING + 110, tableW, getMemoryRows());
    }

    private String findSharedFamilyAt(double mouseX, double mouseY) {

        Map<String, Long> sharedFamilies = snapshot.sharedMemoryFamilies();
        if (activeTab != 4 || sharedFamilies.isEmpty()) {
            return null;
        }
        int sharedPanelW = Math.min(280, Math.max(220, getScreenWidth() / 4));
        int panelX = getScreenWidth() - sharedPanelW;
        int rowY = getContentY() + PADDING + 118 - scrollOffset;
        for (String family : sharedFamilies.keySet()) {
            if (isInside(mouseX, mouseY, panelX, rowY - 2, sharedPanelW, 12)) {
                return family;
            }
            rowY += 12;
        }
        return null;
    }

    private ChunkPos findLagMapChunkAt(double mouseX, double mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || activeTab != 9 || worldMiniTab != WorldMiniTab.LAG_MAP) {
            return null;
        }

        int left = PADDING;
        int top = getFullPageScrollTop(getContentY());
        top += 26; // Matches renderSectionHeader spacing for the World tab.
        top += 24; // Mini-tab row.

        int mapWidth = Math.min(260, getScreenWidth() - 24);
        int mapHeight = Math.min(260, getScreenHeight() - getContentY() - 32);
        int radius = 4;
        int cell = Math.max(12, Math.min(20, Math.min(mapWidth, mapHeight - 18) / ((radius * 2) + 1)));
        int mapTop = top + 14; // Matches renderLagMap title offset.

        ChunkPos playerChunk = client.player.getChunkPos();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int px = left + (dx + radius) * cell;
                int py = mapTop + (dz + radius) * cell;
                if (mouseX >= px && mouseX < px + cell && mouseY >= py && mouseY < py + cell) {
                    return new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                }
            }
        }
        return null;
    }

    private String findRowAt(double mouseX, double mouseY, int startY, int width, List<String> rows) {
        int rowY = startY - scrollOffset;
        for (String row : rows) {
            if (isInside(mouseX, mouseY, 0, rowY, width, ROW_HEIGHT)) {
                return row;
            }
            rowY += ROW_HEIGHT;
        }
        return null;
    }


    public static boolean isProfilingActive() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.currentScreen instanceof TaskManagerScreen;
    }

    public static boolean isMemoryTabActive(MinecraftClient client) {
        return client != null && client.currentScreen instanceof TaskManagerScreen screen && screen.activeTab == 4;
    }

    private String formatMode(ProfilerManager.CaptureMode mode) {
        return mode == null ? "Unknown" : mode.name().replace('_', ' ');
    }

    private String cpuStatusText(boolean ready, long samples, long ageMillis) {
        if (!ready) {
            return "Warming up | " + formatCount(samples) + " samples";
        }
        return "Loaded | " + formatCount(samples) + " samples | updated " + formatDuration(ageMillis) + " ago";
    }

    private int getCpuStatusColor(boolean ready) {
        return ready ? ACCENT_GREEN : ACCENT_YELLOW;
    }

    private int getGpuStatusColor(boolean ready) {
        return ready ? ACCENT_GREEN : ACCENT_YELLOW;
    }

    private void renderStripedRow(DrawContext ctx, int x, int width, int rowY, int rowIdx, int mouseX, int mouseY) {
        if (rowIdx % 2 == 0) {
            ctx.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_ALT);
        }
        if (mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
            ctx.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x22FFFFFF);
        }
    }

    private void renderSearchBox(DrawContext ctx, int x, int y, int width, int height, String placeholder, String value, boolean focused) {
        ctx.fill(x, y, x + width, y + height, focused ? 0x442A2A2A : 0x22181818);
        ctx.fill(x, y, x + width, y + 1, focused ? ACCENT_GREEN : BORDER_COLOR);
        ctx.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);
        String content = value == null || value.isBlank() ? placeholder : value + (focused ? "_" : "");
        int color = value == null || value.isBlank() ? TEXT_DIM : TEXT_PRIMARY;
        ctx.drawText(textRenderer, textRenderer.trimToWidth(content, width - 8), x + 4, y + 4, color, false);
    }

    private void renderSortSummary(DrawContext ctx, int x, int y, String label, String value, int color) {
        ctx.drawText(textRenderer, label + ": " + value, x, y, color, false);
    }

    private String headerLabel(String label, boolean active, boolean descending) {
        if (!active) {
            return label;
        }
        return label + (descending ? " v" : " ^");
    }

    private String formatSort(Enum<?> sort, boolean descending) {
        return prettifyKey(sort.name()) + (descending ? " (desc)" : " (asc)");
    }

    private String getDisplayName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getName())
                .orElseGet(() -> cleanProfilerLabel(modId));
    }

    private int getHeatColor(double pct) {
        if (pct >= 50.0) return ACCENT_RED;
        if (pct >= 15.0) return ACCENT_YELLOW;
        return ACCENT_GREEN;
    }

    private String formatCount(long value) {
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return Long.toString(value);
    }

    private String memoryStatusText(long ageMillis) {
        if (ageMillis == Long.MAX_VALUE) return "No memory sample yet";
        return "Updated " + formatDuration(ageMillis) + " ago";
    }

    private void renderSharedFamiliesPanel(DrawContext ctx, int x, int y, int width, int height, Map<String, Long> sharedFamilies) {
        drawInsetPanel(ctx, x, y, width, height);
        ctx.drawText(textRenderer, "Shared family detail", x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = y + 20;
        for (Map.Entry<String, Long> entry : sharedFamilies.entrySet()) {
            String label = textRenderer.trimToWidth(cleanProfilerLabel(entry.getKey()), Math.max(80, width - 50));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_DIM, false);
            String value = formatBytesMb(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - 6 - textRenderer.getWidth(value), rowY, TEXT_PRIMARY, false);
            rowY += 12;
            if (rowY > y + height - 12) break;
        }
    }

    private void renderSharedFamilyDetail(DrawContext ctx, int x, int y, int width, int height, Map<String, Long> classes) {
        drawInsetPanel(ctx, x, y, width, height);
        ctx.drawText(textRenderer, "Shared family classes", x + 8, y + 8, TEXT_PRIMARY, false);
        int rowY = y + 20;
        for (Map.Entry<String, Long> entry : classes.entrySet()) {
            String label = textRenderer.trimToWidth(cleanProfilerLabel(entry.getKey()), Math.max(80, width - 50));
            ctx.drawText(textRenderer, label, x + 6, rowY, TEXT_DIM, false);
            String value = formatBytesMb(entry.getValue());
            ctx.drawText(textRenderer, value, x + width - 6 - textRenderer.getWidth(value), rowY, TEXT_PRIMARY, false);
            rowY += 12;
            if (rowY > y + height - 12) break;
        }
    }

    private void drawMetricRow(DrawContext ctx, int x, int y, int width, String label, String value) {
        ctx.drawText(textRenderer, label, x, y, TEXT_DIM, false);
        String shown = textRenderer.trimToWidth(value, Math.max(80, width - 120));
        ctx.drawText(textRenderer, shown, x + width - textRenderer.getWidth(shown), y, TEXT_PRIMARY, false);
    }

    private String formatBytesMb(long bytes) {
        if (bytes < 0) return "N/A";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String formatPercent(double value) {
        if (value < 0 || !Double.isFinite(value)) return "N/A";
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    private String formatTemperature(double value) {
        if (value < 0 || !Double.isFinite(value)) return "N/A";
        return String.format(Locale.ROOT, "%.1f C", value);
    }

    private String formatBytesPerSecond(long value) {
        if (value < 0) return "N/A";
        if (value >= 1024L * 1024L) return String.format(Locale.ROOT, "%.2f MB/s", value / (1024.0 * 1024.0));
        if (value >= 1024L) return String.format(Locale.ROOT, "%.1f KB/s", value / 1024.0);
        return value + " B/s";
    }

    private String currentBottleneckSummary() {
        return ProfilerManager.getInstance().getCurrentBottleneckLabel();
    }

    private double percentileGpuFrameLabel() {
        java.util.List<ProfilerManager.SessionPoint> points = new java.util.ArrayList<>(ProfilerManager.getInstance().getSessionHistory());
        if (points.isEmpty()) {
            return snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0;
        }
        java.util.List<Double> values = points.stream().map(ProfilerManager.SessionPoint::gpuFrameTimeMs).sorted().toList();
        int idx = Math.min(values.size() - 1, Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1));
        return values.get(idx);
    }

    private String formatFrameHistogram(Map<String, Double> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return "No frame histogram yet";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Map.Entry<String, Double> entry : buckets.entrySet()) {
            parts.add(entry.getKey() + ": " + String.format(Locale.ROOT, "%.0f%%", entry.getValue()));
        }
        return String.join(" | ", parts);
    }

    private int getPreferredGraphWidth(int availableWidth) {
        return Math.max(220, Math.min(availableWidth - (PADDING * 2), 1000));
    }

    private void renderMetricGraph(DrawContext ctx, int x, int y, int width, int height, long[] primary, long[] secondary, String title, String units, double spanSeconds) {
        int graphHeight = Math.max(96, height);
        renderSeriesGraph(ctx, x + PADDING, y, width - (PADDING * 2), graphHeight, toDoubleArray(primary), toDoubleArray(secondary), title, units, INTEL_COLOR, ACCENT_YELLOW, spanSeconds);
    }

    private void renderSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] primary, double[] secondary, String title, String units, int primaryColor, int secondaryColor, double spanSeconds) {
        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_PRIMARY, false);
        int graphX = x;
        int graphY = y + 14;
        int graphWidth = Math.max(120, width);
        int graphHeight = Math.max(64, height - 26);
        int cardColor = 0x28101010;
        int borderColor = 0x44383838;
        int majorGridColor = 0x26FFFFFF;
        int minorGridColor = 0x12FFFFFF;
        int labelBg = 0xAA111111;
        ctx.fill(graphX - 3, graphY - 3, graphX + graphWidth + 3, graphY + graphHeight + 3, borderColor);
        ctx.fill(graphX - 2, graphY - 2, graphX + graphWidth + 2, graphY + graphHeight + 2, cardColor);

        double max = niceGraphMax(primary, secondary);
        double mid = max / 2.0;
        int midY = graphY + graphHeight / 2;
        int quarterY = graphY + graphHeight / 4;
        int threeQuarterY = graphY + (graphHeight * 3) / 4;

        ctx.fill(graphX, graphY, graphX + graphWidth, graphY + 1, majorGridColor);
        ctx.fill(graphX, midY, graphX + graphWidth, midY + 1, majorGridColor);
        ctx.fill(graphX, quarterY, graphX + graphWidth, quarterY + 1, minorGridColor);
        ctx.fill(graphX, threeQuarterY, graphX + graphWidth, threeQuarterY + 1, minorGridColor);
        ctx.fill(graphX, graphY + graphHeight - 1, graphX + graphWidth, graphY + graphHeight, majorGridColor);

        String topLabel = formatGraphValue(max, units);
        String midLabel = formatGraphValue(mid, units);
        String bottomLabel = formatGraphValue(0.0, units);
        drawGraphLabel(ctx, graphX + graphWidth - textRenderer.getWidth(topLabel) - 6, graphY - 12, topLabel, labelBg);
        drawGraphLabel(ctx, graphX + graphWidth - textRenderer.getWidth(midLabel) - 6, midY - 10, midLabel, labelBg);
        drawGraphLabel(ctx, graphX + graphWidth - textRenderer.getWidth(bottomLabel) - 6, graphY + graphHeight - 10, bottomLabel, labelBg);
        drawGraphLabel(ctx, graphX, graphY + graphHeight + 3, formatHistoryWindowLabel(spanSeconds), labelBg);
        drawGraphLabel(ctx, graphX + graphWidth - textRenderer.getWidth("now") - 6, graphY + graphHeight + 3, "now", labelBg);

        drawSeriesBars(ctx, graphX, graphY, graphWidth, graphHeight, primary, max, primaryColor);
        if (secondary != null && secondary.length > 0) {
            int overlayColor = (secondaryColor & 0x00FFFFFF) | 0xCC000000;
            drawSeriesBars(ctx, graphX, graphY, graphWidth, graphHeight, secondary, max, overlayColor);
        }
    }

    private int renderGraphLegend(DrawContext ctx, int x, int y, String[] labels, int[] colors) {
        int currentX = x;
        int boxSize = 8;
        for (int i = 0; i < labels.length; i++) {
            int color = colors[i];
            ctx.fill(currentX, y + 2, currentX + boxSize, y + 2 + boxSize, color);
            ctx.fill(currentX, y + 2, currentX + boxSize, y + 3, 0x66FFFFFF);
            ctx.fill(currentX, y + boxSize + 1, currentX + boxSize, y + boxSize + 2, 0x44000000);
            ctx.drawText(textRenderer, labels[i], currentX + boxSize + 6, y, TEXT_DIM, false);
            currentX += boxSize + 6 + textRenderer.getWidth(labels[i]) + 14;
        }
        return 12;
    }

    private void drawSeriesBars(DrawContext ctx, int graphX, int graphY, int graphWidth, int graphHeight, double[] values, double max, int color) {
        if (values == null || values.length == 0) {
            return;
        }
        for (int px = 0; px < graphWidth; px++) {
            int start = (int) Math.floor(px * values.length / (double) graphWidth);
            int end = (int) Math.floor((px + 1) * values.length / (double) graphWidth) - 1;
            if (end < start) {
                end = start;
            }
            start = Math.max(0, Math.min(values.length - 1, start));
            end = Math.max(0, Math.min(values.length - 1, end));
            double peak = 0.0;
            for (int i = start; i <= end; i++) {
                peak = Math.max(peak, values[i]);
            }
            int valueHeight = (int) Math.min(graphHeight, Math.round((peak / Math.max(1.0, max)) * graphHeight));
            if (valueHeight <= 0) {
                continue;
            }
            int barX = graphX + px;
            ctx.fill(barX, graphY + graphHeight - valueHeight, barX + 1, graphY + graphHeight, color);
        }
    }

    private void drawGraphLabel(DrawContext ctx, int x, int y, String text, int backgroundColor) {
        int textX = Math.max(0, x);
        int textY = y;
        int textWidth = textRenderer.getWidth(text);
        ctx.fill(textX - 3, textY - 1, textX + textWidth + 3, textY + 9, backgroundColor);
        ctx.drawText(textRenderer, text, textX, textY, TEXT_DIM, false);
    }

    private double niceGraphMax(double[] primary, double[] secondary) {
        double rawMax = 0.0;
        if (primary != null) {
            for (double value : primary) {
                rawMax = Math.max(rawMax, value);
            }
        }
        if (secondary != null) {
            for (double value : secondary) {
                rawMax = Math.max(rawMax, value);
            }
        }
        if (rawMax <= 0.0) {
            return 1.0;
        }
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(rawMax)));
        double normalized = rawMax / magnitude;
        double niceNormalized = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
        return niceNormalized * magnitude;
    }

    private String formatHistoryWindowLabel(double spanSeconds) {
        if (spanSeconds <= 0.0) {
            return "start";
        }
        return String.format(Locale.ROOT, "-%.1fs", spanSeconds);
    }

    private String formatGraphValue(double value, String units) {
        if ("B/s".equals(units)) {
            return formatBytesPerSecond(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units);
    }
    private String formatDuration(long millis) {
        if (millis == Long.MAX_VALUE) return "never";
        if (millis < 1000) return millis + "ms";
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private void renderFlamegraph(DrawContext ctx, int x, int y, int w, int h) {
        beginFullPageScissor(ctx, x, y, w, h);
        int top = getFullPageScrollTop(y);
        top = renderSectionHeader(ctx, x + PADDING, top, "Flamegraph", "Captured stack samples from the current profiling window.");
        int rowY = top + 4;
        Map<String, Long> stacks = snapshot.flamegraphStacks();
        if (stacks.isEmpty()) {
            ctx.drawText(textRenderer, "No flamegraph samples yet.", x + PADDING, rowY, TEXT_DIM, false);
        } else {
            int shown = 0;
            for (Map.Entry<String, Long> entry : stacks.entrySet()) {
                ctx.drawText(textRenderer, textRenderer.trimToWidth(entry.getKey(), w - 120), x + PADDING, rowY, TEXT_DIM, false);
                String count = formatCount(entry.getValue());
                ctx.drawText(textRenderer, count, x + w - PADDING - textRenderer.getWidth(count), rowY, TEXT_PRIMARY, false);
                rowY += 12;
                if (++shown >= 20) break;
            }
        }
        endFullPageScissor(ctx);
    }

    private void renderTimeline(DrawContext ctx, int x, int y, int w, int h) {
        beginFullPageScissor(ctx, x, y, w, h);
        int graphWidth = getPreferredGraphWidth(w);
        int left = x + Math.max(PADDING, (w - graphWidth) / 2);
        int top = getFullPageScrollTop(y);
        FrameTimelineProfiler frames = FrameTimelineProfiler.getInstance();
        top = renderSectionHeader(ctx, left, top, "Timeline", "Frame pacing, FPS lows, jitter, and spike context over the live capture window.");

        drawMetricRow(ctx, left, top, graphWidth, "FPS", String.format(Locale.ROOT, "current %.1f | avg %.1f | 1%% low %.1f | 0.1%% low %.1f", frames.getCurrentFps(), frames.getAverageFps(), frames.getOnePercentLowFps(), frames.getPointOnePercentLowFps()));
        top += 24;
        renderSeriesGraph(ctx, left, top, graphWidth, 126, frames.getOrderedFrameMsHistory(), null, "Frame Timeline", "ms/frame", ACCENT_YELLOW, 0, frames.getHistorySpanSeconds());
        top += 144;
        renderSeriesGraph(ctx, left, top, graphWidth, 126, frames.getOrderedFpsHistory(), null, "FPS Timeline", "fps", INTEL_COLOR, 0, frames.getHistorySpanSeconds());
        top += 144;
        drawMetricRow(ctx, left, top, graphWidth, "Jitter Variance", String.format(Locale.ROOT, "stddev %.2f ms | variance %.2f ms^2 | stutter %.1f", frames.getFrameStdDevMs(), frames.getFrameVarianceMs(), frames.getStutterScore()));
        top += 18;
        drawMetricRow(ctx, left, top, graphWidth, "Frame / Tick Breakdown", String.format(Locale.ROOT, "frame %.2f ms | p95 %.2f | p99 %.2f | build %.2f | gpu %.2f | gpu p95 %.2f | mspt %.2f | mspt p95 %.2f | mspt p99 %.2f", frames.getLatestFrameNs() / 1_000_000.0, frames.getPercentileFrameNs(0.95) / 1_000_000.0, frames.getPercentileFrameNs(0.99) / 1_000_000.0, snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::cpuNanos).sum() / 1_000_000.0, snapshot.renderPhases().values().stream().mapToLong(RenderPhaseProfiler.PhaseSnapshot::gpuNanos).sum() / 1_000_000.0, percentileGpuFrameLabel(), TickProfiler.getInstance().getAverageServerTickNs() / 1_000_000.0, TickProfiler.getInstance().getServerTickP95Ns() / 1_000_000.0, TickProfiler.getInstance().getServerTickP99Ns() / 1_000_000.0));
        top += 22;
        drawMetricRow(ctx, left, top, graphWidth, "Frame Histogram", formatFrameHistogram(frames.getFrameTimeHistogram()));
        top += 22;
        renderSpikeInspector(ctx, left, top, graphWidth);
        endFullPageScissor(ctx);
    }

    private void renderSettings(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        beginFullPageScissor(ctx, x, y, w, h);
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        ctx.drawText(textRenderer, "Settings", left, top, TEXT_PRIMARY, false);
        top += 18;
        drawMetricRow(ctx, left, top, w - 24, "Session Logging", ProfilerManager.getInstance().isSessionLogging() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Session Duration", ConfigManager.getSessionDurationSeconds() + "s");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Metrics Update Interval", ConfigManager.getMetricsUpdateIntervalMs() + "ms");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Profiler Update Delay", ConfigManager.getProfilerUpdateDelayMs() + "ms");
        top += 32;
        ctx.drawText(textRenderer, "HUD Settings", left, top, TEXT_PRIMARY, false);
        top += 18;
        drawMetricRow(ctx, left, top, w - 24, "Enabled", ConfigManager.isHudEnabled() ? "Yes" : "No");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Position", String.valueOf(ConfigManager.getHudPosition()));
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Layout", String.valueOf(ConfigManager.getHudLayoutMode()));
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Trigger Mode", String.valueOf(ConfigManager.getHudTriggerMode()));
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "FPS", ConfigManager.isHudShowFps() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Frame Stats", ConfigManager.isHudShowFrame() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Tick Stats", ConfigManager.isHudShowTicks() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Utilization", ConfigManager.isHudShowUtilization() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Temperatures", ConfigManager.isHudShowTemperatures() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Parallelism", ConfigManager.isHudShowParallelism() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Memory", ConfigManager.isHudShowMemory() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "World", ConfigManager.isHudShowWorld() ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Session Status", ConfigManager.isHudShowSession() ? "On" : "Off");
        top += 32;
        ctx.drawText(textRenderer, "Table Columns", left, top, TEXT_PRIMARY, false);
        top += 18;
        drawMetricRow(ctx, left, top, w - 24, "Tasks: %CPU", ConfigManager.isTasksColumnVisible("cpu") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Tasks: Threads", ConfigManager.isTasksColumnVisible("threads") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Tasks: Samples", ConfigManager.isTasksColumnVisible("samples") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Tasks: Invokes", ConfigManager.isTasksColumnVisible("invokes") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "GPU: %GPU", ConfigManager.isGpuColumnVisible("pct") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "GPU: Threads", ConfigManager.isGpuColumnVisible("threads") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "GPU: Est ms", ConfigManager.isGpuColumnVisible("gpums") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "GPU: R.S", ConfigManager.isGpuColumnVisible("rsamples") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Memory: CLS", ConfigManager.isMemoryColumnVisible("classes") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Memory: MB", ConfigManager.isMemoryColumnVisible("mb") ? "On" : "Off");
        top += 22;
        drawMetricRow(ctx, left, top, w - 24, "Memory: %", ConfigManager.isMemoryColumnVisible("pct") ? "On" : "Off");
        endFullPageScissor(ctx);
    }


    private long[] toLongArray(double[] values) {
        if (values == null || values.length == 0) {
            return new long[1];
        }
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = Math.round(values[i]);
        }
        return result;
    }

    private long[] toLongArray(long[] values) {
        return values == null || values.length == 0 ? new long[1] : values;
    }

    private double[] toDoubleArray(long[] values) {
        if (values == null || values.length == 0) {
            return new double[] {0.0};
        }
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private double[] toFrameMsArray(long[] values) {
        if (values == null || values.length == 0) {
            return new double[] {0.0};
        }
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i] / 1_000_000.0;
        }
        return result;
    }

    private String cleanEntityName(Entity entity) {
        String name = entity.getName().getString();
        if (name != null && !name.isBlank() && !name.startsWith("entity.")) {
            return name;
        }
        return cleanProfilerLabel(entity.getType().toString());
    }

    private String cleanProfilerLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        if (raw.startsWith("entity.minecraft.")) {
            return prettifyKey(raw.substring("entity.minecraft.".length()));
        }
        if (raw.startsWith("minecraft:")) {
            return prettifyKey(raw.substring("minecraft:".length()));
        }
        if (raw.startsWith("[L") && raw.endsWith(";")) {
            return cleanProfilerLabel(raw.substring(2, raw.length() - 1)) + "[]";
        }
        if (raw.startsWith("[")) {
            return switch (raw) {
                case "[B" -> "byte[]";
                case "[C" -> "char[]";
                case "[D" -> "double[]";
                case "[F" -> "float[]";
                case "[I" -> "int[]";
                case "[J" -> "long[]";
                case "[S" -> "short[]";
                case "[Z" -> "boolean[]";
                default -> raw;
            };
        }
        if ("java.lang.Object".equals(raw)) {
            return "Object";
        }
        if (raw.contains(".")) {
            String[] parts = raw.split("\\.");
            return prettifyKey(parts[parts.length - 1]);
        }
        return prettifyKey(raw);
    }

    private String prettifyKey(String key) {
        String cleaned = key == null ? "unknown" : key.replace('_', ' ').replace('-', ' ').replace(':', ' ');
        String[] parts = cleaned.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? "unknown" : builder.toString();
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}










































