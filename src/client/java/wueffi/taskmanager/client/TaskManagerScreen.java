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
import net.minecraft.text.OrderedText;
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

    private record LagMapLayout(int left, int miniTabY, int summaryY, int mapRenderY, int mapWidth, int mapHeight, int cell, int radius, int mapTop) {}

    private record FindingClickTarget(int x, int y, int width, int height, String key) {}

    private record TooltipTarget(int x, int y, int width, int height, String text) {}

    private enum TableId {
        TASKS,
        GPU,
        MEMORY
    }

    private enum WorldMiniTab {
        LAG_MAP,
        BLOCK_ENTITIES
    }

    private enum SystemMiniTab {
        OVERVIEW,
        CPU_GRAPH,
        GPU_GRAPH,
        MEMORY_GRAPH
    }

    private enum ColorSetting {
        CPU,
        GPU
    }

    private enum StartupSort {
        NAME,
        START,
        END,
        ACTIVE,
        ENTRYPOINTS,
        REGISTRATIONS
    }

    private enum TaskSort {
        NAME,
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
        NAME,
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
        NAME,
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
    private static final int STARTUP_ROW_HEIGHT = 28;
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
    private String startupSearch = "";
    private TableId focusedSearchTable;
    private boolean startupSearchFocused;
    private ColorSetting focusedColorSetting;
    private String colorEditValue = "";
    private TaskSort taskSort = TaskSort.CPU;
    private boolean taskSortDescending = true;
    private GpuSort gpuSort = GpuSort.EST_GPU;
    private boolean gpuSortDescending = true;
    private MemorySort memorySort = MemorySort.MEMORY_MB;
    private boolean memorySortDescending = true;
    private StartupSort startupSort = StartupSort.ACTIVE;
    private boolean startupSortDescending = true;
    private WorldMiniTab worldMiniTab = WorldMiniTab.LAG_MAP;
    private SystemMiniTab systemMiniTab = SystemMiniTab.OVERVIEW;
    private float uiScale = 1.0f;
    private float uiOffsetX = 0.0f;
    private float uiOffsetY = 0.0f;
    private int layoutWidth;
    private int layoutHeight;
    private final List<FindingClickTarget> findingClickTargets = new ArrayList<>();
    private final List<TooltipTarget> tooltipTargets = new ArrayList<>();
    private String selectedFindingKey;
    private ProfilerManager.ProfilerSnapshot snapshot = ProfilerManager.getInstance().getCurrentSnapshot();
    private LagMapLayout lastRenderedLagMapLayout;

    public TaskManagerScreen() {
        this(lastOpenedTab);
    }

    public TaskManagerScreen(int initialTab) {
        super(Text.literal("Task Manager"));
        this.activeTab = Math.max(0, Math.min(TAB_NAMES.length - 1, initialTab));
        lastOpenedTab = this.activeTab;
        this.tasksSearch = ConfigManager.getTasksSearch();
        this.gpuSearch = ConfigManager.getGpuSearch();
        this.memorySearch = ConfigManager.getMemorySearch();
        this.startupSearch = ConfigManager.getStartupSearch();
        try { this.taskSort = TaskSort.valueOf(ConfigManager.getTaskSort()); } catch (Exception ignored) { this.taskSort = TaskSort.CPU; }
        this.taskSortDescending = ConfigManager.isTaskSortDescending();
        try { this.gpuSort = GpuSort.valueOf(ConfigManager.getGpuSort()); } catch (Exception ignored) { this.gpuSort = GpuSort.EST_GPU; }
        this.gpuSortDescending = ConfigManager.isGpuSortDescending();
        try { this.memorySort = MemorySort.valueOf(ConfigManager.getMemorySort()); } catch (Exception ignored) { this.memorySort = MemorySort.MEMORY_MB; }
        this.memorySortDescending = ConfigManager.isMemorySortDescending();
        try { this.startupSort = StartupSort.valueOf(ConfigManager.getStartupSort()); } catch (Exception ignored) { this.startupSort = StartupSort.ACTIVE; }
        this.startupSortDescending = ConfigManager.isStartupSortDescending();
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
        ConfigManager.setTasksSearch(tasksSearch);
        ConfigManager.setGpuSearch(gpuSearch);
        ConfigManager.setMemorySearch(memorySearch);
        ConfigManager.setStartupSearch(startupSearch);
        ConfigManager.setTaskSortState(taskSort.name(), taskSortDescending);
        ConfigManager.setGpuSortState(gpuSort.name(), gpuSortDescending);
        ConfigManager.setMemorySortState(memorySort.name(), memorySortDescending);
        ConfigManager.setStartupSortState(startupSort.name(), startupSortDescending);
        super.close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        snapshot = ProfilerManager.getInstance().getCurrentSnapshot();
        findingClickTargets.clear();
        tooltipTargets.clear();
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

        renderTooltipOverlay(ctx, logicalMouseX, logicalMouseY);
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
        drawTopChip(ctx, x + PADDING + 208, infoY + 22, 78, 16, false);
        ctx.drawText(textRenderer, "CPU Graph", x + PADDING + 226, infoY + 26, TEXT_DIM, false);
        renderSearchBox(ctx, x + listW - 160, infoY + 24, 152, 16, "Search mods", tasksSearch, focusedSearchTable == TableId.TASKS);
        renderResetButton(ctx, x + listW - 214, infoY + 24, 48, 16, hasTaskFilter());
        renderSortSummary(ctx, x + PADDING, infoY + 28, "Sort", formatSort(taskSort, taskSortDescending), TEXT_DIM);
        ctx.drawText(textRenderer, rows.size() + " mods", x + PADDING + 108, infoY + 28, TEXT_DIM, false);

        if (!rows.isEmpty() && (selectedTaskMod == null || !rows.contains(selectedTaskMod))) {
            selectedTaskMod = rows.getFirst();
        }

        int headerY = infoY + 50;
        ctx.fill(x, headerY, x + listW, headerY + 14, HEADER_COLOR);
        ctx.drawText(textRenderer, headerLabel("MOD", taskSort == TaskSort.NAME, taskSortDescending), x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        addTooltip(x + PADDING + ICON_SIZE + 6, headerY + 1, 44, 14, "Sort by mod display name.");
        int pctX = x + listW - 206;
        int threadsX = x + listW - 146;
        int samplesX = x + listW - 92;
        int invokesX = x + listW - 42;
        if (isColumnVisible(TableId.TASKS, "cpu")) { ctx.drawText(textRenderer, headerLabel("%CPU", taskSort == TaskSort.CPU, taskSortDescending), pctX, headerY + 3, TEXT_DIM, false); addTooltip(pctX, headerY + 1, 42, 14, "Sampled CPU share from rolling stack windows."); }
        if (isColumnVisible(TableId.TASKS, "threads")) { ctx.drawText(textRenderer, headerLabel("THREADS", taskSort == TaskSort.THREADS, taskSortDescending), threadsX, headerY + 3, TEXT_DIM, false); addTooltip(threadsX, headerY + 1, 58, 14, "Distinct sampled threads attributed to this mod."); }
        if (isColumnVisible(TableId.TASKS, "samples")) { ctx.drawText(textRenderer, headerLabel("SAMPLES", taskSort == TaskSort.SAMPLES, taskSortDescending), samplesX, headerY + 3, TEXT_DIM, false); addTooltip(samplesX, headerY + 1, 56, 14, "Total CPU samples attributed in the rolling window."); }
        if (isColumnVisible(TableId.TASKS, "invokes")) { ctx.drawText(textRenderer, headerLabel("INVOKES", taskSort == TaskSort.INVOKES, taskSortDescending), invokesX, headerY + 3, TEXT_DIM, false); addTooltip(invokesX, headerY + 1, 54, 14, "Tracked event invokes, shown separately from sampled CPU ownership."); }

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
        drawTopChip(ctx, x + PADDING + 218, infoY + 22, 78, 16, false);
        ctx.drawText(textRenderer, "GPU Graph", x + PADDING + 236, infoY + 26, TEXT_DIM, false);
        renderSearchBox(ctx, x + listW - 160, infoY + 24, 152, 16, "Search mods", gpuSearch, focusedSearchTable == TableId.GPU);
        renderResetButton(ctx, x + listW - 214, infoY + 24, 48, 16, hasGpuFilter());
        renderSortSummary(ctx, x + PADDING, infoY + 28, "Sort", formatSort(gpuSort, gpuSortDescending), TEXT_DIM);
        ctx.drawText(textRenderer, getGpuRows().size() + " mods", x + PADDING + 108, infoY + 28, TEXT_DIM, false);

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
        ctx.drawText(textRenderer, headerLabel("MOD", gpuSort == GpuSort.NAME, gpuSortDescending), x + PADDING + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        addTooltip(x + PADDING + ICON_SIZE + 6, headerY + 1, 44, 14, "Sort by mod display name.");
        int pctX = x + listW - 232;
        int threadsX = x + listW - 172;
        int gpuMsX = x + listW - 108;
        int renderSamplesX = x + listW - 42;
        if (isColumnVisible(TableId.GPU, "pct")) { ctx.drawText(textRenderer, headerLabel("EST %GPU", gpuSort == GpuSort.EST_GPU, gpuSortDescending), pctX, headerY + 3, TEXT_DIM, false); addTooltip(pctX, headerY + 1, 58, 14, "Estimated GPU share derived from render-thread sampling and GPU time."); }
        if (isColumnVisible(TableId.GPU, "threads")) { ctx.drawText(textRenderer, headerLabel("THREADS", gpuSort == GpuSort.THREADS, gpuSortDescending), threadsX, headerY + 3, TEXT_DIM, false); addTooltip(threadsX, headerY + 1, 58, 14, "Distinct sampled render threads contributing to this row."); }
        if (isColumnVisible(TableId.GPU, "gpums")) { ctx.drawText(textRenderer, headerLabel("Est ms", gpuSort == GpuSort.GPU_MS, gpuSortDescending), gpuMsX, headerY + 3, TEXT_DIM, false); addTooltip(gpuMsX, headerY + 1, 48, 14, "Estimated GPU milliseconds in the rolling window."); }
        if (isColumnVisible(TableId.GPU, "rsamples")) { ctx.drawText(textRenderer, headerLabel("R.S", gpuSort == GpuSort.RENDER_SAMPLES, gpuSortDescending), renderSamplesX, headerY + 3, TEXT_DIM, false); addTooltip(renderSamplesX, headerY + 1, 26, 14, "Render samples attributed to this mod."); }

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
        addTooltip(x + PADDING, headerY + 1, 44, 14, "Render phase name.");
        int shareX = w - 220;
        int cpuMsX = w - 160;
        int gpuMsX = w - 100;
        int callsX = w - 45;
        ctx.drawText(textRenderer, "%CPU", shareX, headerY + 3, TEXT_DIM, false);
        addTooltip(shareX, headerY + 1, 38, 14, "CPU share of this render phase in the current window.");
        ctx.drawText(textRenderer, "CPU ms", cpuMsX, headerY + 3, TEXT_DIM, false);
        addTooltip(cpuMsX, headerY + 1, 42, 14, "Average CPU milliseconds per call for this phase.");
        ctx.drawText(textRenderer, "GPU ms", gpuMsX, headerY + 3, TEXT_DIM, false);
        addTooltip(gpuMsX, headerY + 1, 42, 14, "Average GPU milliseconds per call when timer queries are available.");
        ctx.drawText(textRenderer, "CALLS", callsX, headerY + 3, TEXT_DIM, false);
        addTooltip(callsX, headerY + 1, 42, 14, "Approximate call count for this phase in the rolling window.");

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
        beginFullPageScissor(ctx, x, y, w, h);
        int left = x + PADDING;
        int top = getFullPageScrollTop(y);
        boolean measuredEntrypoints = snapshot.startupRows().stream().anyMatch(StartupTimingProfiler.StartupRow::measuredEntrypoints);
        String startupIntro = measuredEntrypoints
                ? "Measured Fabric startup activity by mod in explicit wall-clock milliseconds. Search, sort, and compare entrypoint timing here."
                : "Observed startup registration timing by mod in explicit wall-clock milliseconds. Search and sort rows to isolate slow paths.";
        top = renderSectionHeader(ctx, left, top, "Startup", startupIntro);

        java.util.List<StartupTimingProfiler.StartupRow> rows = getStartupRows();
        long totalSpan = Math.max(snapshot.startupLast() - snapshot.startupFirst(), 1);
        int searchY = top;
        renderSearchBox(ctx, x + w - 160, searchY, 152, 16, "Search mods", startupSearch, startupSearchFocused);
        renderResetButton(ctx, x + w - 214, searchY, 48, 16, hasStartupFilter());
        int sortY = searchY + 20;
        renderSortSummary(ctx, left, sortY + 4, "Sort", formatSort(startupSort, startupSortDescending), TEXT_DIM);
        ctx.drawText(textRenderer, rows.size() + " mods", left + 132, sortY + 4, TEXT_DIM, false);

        int headerY = sortY + 20;
        ctx.fill(x, headerY, x + w, headerY + 14, HEADER_COLOR);
        int regsX = x + w - 34;
        int epX = regsX - 30;
        int activeMsX = epX - 62;
        int endMsX = activeMsX - 56;
        int startMsX = endMsX - 56;
        int barW = Math.max(110, Math.min(180, w / 8));
        int barX = startMsX - barW - 22;
        int nameW = Math.max(150, barX - (left + ICON_SIZE + 16));
        ctx.drawText(textRenderer, headerLabel("MOD", startupSort == StartupSort.NAME, startupSortDescending), left + ICON_SIZE + 6, headerY + 3, TEXT_DIM, false);
        addTooltip(left + ICON_SIZE + 6, headerY + 1, 44, 14, "Sort by mod display name.");
        ctx.drawText(textRenderer, "TIMELINE", barX, headerY + 3, TEXT_DIM, false);
        addTooltip(barX, headerY + 1, 64, 14, "Observed startup span across the global startup window.");
        ctx.drawText(textRenderer, headerLabel("START", startupSort == StartupSort.START, startupSortDescending), startMsX, headerY + 3, TEXT_DIM, false);
        addTooltip(startMsX, headerY + 1, 42, 14, "Milliseconds from startup begin until this mod first became active.");
        ctx.drawText(textRenderer, headerLabel("END", startupSort == StartupSort.END, startupSortDescending), endMsX, headerY + 3, TEXT_DIM, false);
        addTooltip(endMsX, headerY + 1, 34, 14, "Milliseconds from startup begin until this mod last appeared active.");
        ctx.drawText(textRenderer, headerLabel("ACTIVE", startupSort == StartupSort.ACTIVE, startupSortDescending), activeMsX, headerY + 3, TEXT_DIM, false);
        addTooltip(activeMsX, headerY + 1, 48, 14, "Measured active wall-clock milliseconds attributed to this mod.");
        ctx.drawText(textRenderer, headerLabel("EP", startupSort == StartupSort.ENTRYPOINTS, startupSortDescending), epX, headerY + 3, TEXT_DIM, false);
        addTooltip(epX, headerY + 1, 18, 14, "Entrypoint count observed for this mod.");
        ctx.drawText(textRenderer, headerLabel("REG", startupSort == StartupSort.REGISTRATIONS, startupSortDescending), regsX, headerY + 3, TEXT_DIM, false);
        addTooltip(regsX, headerY + 1, 24, 14, "Registration events observed during startup fallback timing.");

        int listY = headerY + 16;
        int listH = h - (listY - y) - 16;
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, startupSearch.isBlank() ? "No startup data captured yet." : "No startup rows match the current search/filter.", left, listY + 6, TEXT_DIM, false);
        } else {
            ctx.enableScissor(x, listY, x + w, listY + listH);
            int rowY = listY - scrollOffset;
            int rowIdx = 0;
            for (StartupTimingProfiler.StartupRow row : rows) {
                if (rowY + STARTUP_ROW_HEIGHT > listY && rowY < listY + listH) {
                    renderStripedRowVariable(ctx, x, w, rowY, STARTUP_ROW_HEIGHT, rowIdx, mouseX, mouseY);
                    Identifier icon = ModIconCache.getInstance().getIcon(row.modId());
                    ctx.drawTexture(RenderPipelines.GUI_TEXTURED, icon, left, rowY + 5, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, 0xFFFFFFFF);
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(getDisplayName(row.modId()), nameW), left + ICON_SIZE + 6, rowY + 3, TEXT_PRIMARY, false);
                    String startupMeta = row.measuredEntrypoints() ? row.stageSummary() : "fallback registration timing";
                    String startupHint = row.definitionSummary().isBlank() ? startupMeta : (startupMeta + " | " + row.definitionSummary());
                    ctx.drawText(textRenderer, textRenderer.trimToWidth(startupHint, nameW), left + ICON_SIZE + 6, rowY + 14, TEXT_DIM, false);

                    int barStart = (int) ((row.first() - snapshot.startupFirst()) * barW / totalSpan);
                    int barLen = Math.max(1, (int) ((row.last() - row.first()) * barW / totalSpan));
                    ctx.fill(barX, rowY + 11, barX + barW, rowY + 16, 0x33FFFFFF);
                    ctx.fill(barX + barStart, rowY + 10, Math.min(barX + barW, barX + barStart + barLen), rowY + 17, ACCENT_YELLOW);

                    double startMs = (row.first() - snapshot.startupFirst()) / 1_000_000.0;
                    double endMs = (row.last() - snapshot.startupFirst()) / 1_000_000.0;
                    double activeMs = row.activeNanos() / 1_000_000.0;
                    ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", startMs), startMsX, rowY + 8, TEXT_DIM, false);
                    ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", endMs), endMsX, rowY + 8, TEXT_DIM, false);
                    ctx.drawText(textRenderer, String.format(Locale.ROOT, "%.1f", activeMs), activeMsX, rowY + 8, ACCENT_YELLOW, false);
                    ctx.drawText(textRenderer, String.valueOf(row.entrypoints()), epX, rowY + 8, TEXT_DIM, false);
                    ctx.drawText(textRenderer, String.valueOf(row.registrations()), regsX, rowY + 8, TEXT_DIM, false);
                }
                if (rowY > listY + listH) break;
                rowY += STARTUP_ROW_HEIGHT;
                rowIdx++;
            }
            ctx.disableScissor();
        }

        ctx.fill(x, y + h - 14, x + w, y + h, HEADER_COLOR);
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Startup span %.1f ms | %d mods | %s", totalSpan / 1_000_000.0, snapshot.startupRows().size(), measuredEntrypoints ? "measured entrypoints" : "fallback registration path"), left, y + h - 10, TEXT_DIM, false);
        endFullPageScissor(ctx);
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

        long heapMax = memory.heapMaxBytes() > 0 ? memory.heapMaxBytes() : memory.heapCommittedBytes();
        double usedPct = heapMax > 0 ? (memory.heapUsedBytes() * 100.0 / heapMax) : 0;

        drawTopChip(ctx, x + tableW - 106, top + 2, 98, 16, false);
        ctx.drawText(textRenderer, "Memory Graph", x + tableW - 92, top + 6, TEXT_DIM, false);

        int controlsY = top + 28;

        renderSearchBox(ctx, x + tableW - 160, controlsY, 152, 16, "Search mods", memorySearch, focusedSearchTable == TableId.MEMORY);
        renderResetButton(ctx, x + tableW - 214, controlsY, 48, 16, hasMemoryFilter());
        renderSortSummary(ctx, left, controlsY + 4, "Sort", formatSort(memorySort, memorySortDescending), TEXT_DIM);
        ctx.drawText(textRenderer, rows.size() + " mods", left + 108, controlsY + 4, TEXT_DIM, false);

        int barY = controlsY + 24;
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
        addTooltip(x + PADDING + ICON_SIZE + 6, headerY + 1, 44, 14, "Sort by mod display name.");
        int classesX = x + tableW - 140;
        int mbX = x + tableW - 94;
        int pctX = x + tableW - 42;
        if (isColumnVisible(TableId.MEMORY, "classes")) { ctx.drawText(textRenderer, headerLabel("CLS", memorySort == MemorySort.CLASS_COUNT, memorySortDescending), classesX, headerY + 3, TEXT_DIM, false); addTooltip(classesX, headerY + 1, 28, 14, "Distinct live class families attributed to this mod."); }
        if (isColumnVisible(TableId.MEMORY, "mb")) { ctx.drawText(textRenderer, headerLabel("MB", memorySort == MemorySort.MEMORY_MB, memorySortDescending), mbX, headerY + 3, TEXT_DIM, false); addTooltip(mbX, headerY + 1, 22, 14, "Attributed live heap in megabytes."); }
        if (isColumnVisible(TableId.MEMORY, "pct")) { ctx.drawText(textRenderer, headerLabel("%", memorySort == MemorySort.PERCENT, memorySortDescending), pctX, headerY + 3, TEXT_DIM, false); addTooltip(pctX, headerY + 1, 16, 14, "Share of currently attributed live heap."); }

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
        return y + measureWrappedHeight(wrappedWidth, text);
    }

    private int measureWrappedHeight(int width, String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int wrappedWidth = Math.max(40, width);
        int lineCount = Math.max(1, textRenderer.wrapLines(Text.literal(text), wrappedWidth).size());
        return lineCount * 12;
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
        SystemMetricsProfiler metrics = SystemMetricsProfiler.getInstance();

        top = renderSectionHeader(ctx, left, top, "System", "Runtime health, sensors, and CPU/GPU load history.");
        drawTopChip(ctx, left, top, 78, 16, systemMiniTab == SystemMiniTab.OVERVIEW);
        drawTopChip(ctx, left + 84, top, 88, 16, systemMiniTab == SystemMiniTab.CPU_GRAPH);
        drawTopChip(ctx, left + 178, top, 88, 16, systemMiniTab == SystemMiniTab.GPU_GRAPH);
        drawTopChip(ctx, left + 272, top, 108, 16, systemMiniTab == SystemMiniTab.MEMORY_GRAPH);
        ctx.drawText(textRenderer, "Overview", left + 14, top + 4, systemMiniTab == SystemMiniTab.OVERVIEW ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, "CPU Graph", left + 100, top + 4, systemMiniTab == SystemMiniTab.CPU_GRAPH ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, "GPU Graph", left + 194, top + 4, systemMiniTab == SystemMiniTab.GPU_GRAPH ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, "Memory Graph", left + 286, top + 4, systemMiniTab == SystemMiniTab.MEMORY_GRAPH ? TEXT_PRIMARY : TEXT_DIM, false);
        top += 24;

        if (systemMiniTab == SystemMiniTab.CPU_GRAPH) {
            int graphWidth = getPreferredGraphWidth(w);
            int graphLeft = x + Math.max(PADDING, (w - graphWidth) / 2);
            renderFixedScaleSeriesGraph(ctx, graphLeft, top, graphWidth, 146, metrics.getOrderedCpuLoadHistory(), "CPU Load", "% load", getCpuGraphColor(), 100.0, metrics.getHistorySpanSeconds());
            top += 164;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "Current CPU Load", formatPercent(system.cpuCoreLoadPercent()));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "CPU Temperature", formatTemperature(system.cpuTemperatureC()));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "CPU Info", formatCpuInfo());
            endFullPageScissor(ctx);
            return;
        }

        if (systemMiniTab == SystemMiniTab.GPU_GRAPH) {
            int graphWidth = getPreferredGraphWidth(w);
            int graphLeft = x + Math.max(PADDING, (w - graphWidth) / 2);
            renderFixedScaleSeriesGraph(ctx, graphLeft, top, graphWidth, 146, metrics.getOrderedGpuLoadHistory(), "GPU Load", "% load", getGpuGraphColor(), 100.0, metrics.getHistorySpanSeconds());
            top += 164;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "Current GPU Load", formatPercent(system.gpuCoreLoadPercent()));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "GPU Temperature", formatTemperature(system.gpuTemperatureC()));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "GPU Info", blankToUnknown(system.gpuVendor()) + " | " + blankToUnknown(system.gpuRenderer()));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "VRAM Usage", formatBytesMb(system.vramUsedBytes()) + " / " + formatBytesMb(system.vramTotalBytes()));
            endFullPageScissor(ctx);
            return;
        }

        if (systemMiniTab == SystemMiniTab.MEMORY_GRAPH) {
            int graphWidth = getPreferredGraphWidth(w);
            int graphLeft = x + Math.max(PADDING, (w - graphWidth) / 2);
            long heapMaxBytes = snapshot.memory().heapMaxBytes() > 0 ? snapshot.memory().heapMaxBytes() : Runtime.getRuntime().maxMemory();
            double heapMaxMb = Math.max(1.0, heapMaxBytes / (1024.0 * 1024.0));
            renderFixedScaleSeriesGraph(ctx, graphLeft, top, graphWidth, 146,
                    metrics.getOrderedMemoryUsedHistory(),
                    metrics.getOrderedMemoryCommittedHistory(),
                    "Memory Load", "MB", getMemoryGraphColor(), 0x6688B5FF, heapMaxMb,
                    metrics.getHistorySpanSeconds());
            top += 164;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "Heap Used", formatBytesMb(snapshot.memory().heapUsedBytes()));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "Heap Allocated", formatBytesMb(snapshot.memory().heapCommittedBytes()));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "Heap Max", formatBytesMb(heapMaxBytes));
            top += 16;
            drawMetricRow(ctx, graphLeft, top, graphWidth, "Non-Heap", formatBytesMb(snapshot.memory().nonHeapUsedBytes()));
            endFullPageScissor(ctx);
            return;
        }


        drawMetricRow(ctx, left, top, w - 32, "CPU Info", formatCpuInfo());
        top += 16;
        drawMetricRow(ctx, left, top, w - 32, "GPU Info", blankToUnknown(system.gpuVendor()) + " | " + blankToUnknown(system.gpuRenderer()));
        top += 16;
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
        top += 22;
        renderSensorsPanel(ctx, left, top, w - 24, system);
        top += 124;
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
        beginFullPageScissor(ctx, x, y, w, h);
        LagMapLayout layout = getLagMapLayout(y, w, h);
        lastRenderedLagMapLayout = layout;
        int top = getFullPageScrollTop(y);
        top = renderSectionHeader(ctx, left, top, "World", "Chunk pressure, entity hotspots, and block-entity drilldown grouped into world-focused views.");
        int lagTabW = 76;
        int blockTabW = 108;
        drawTopChip(ctx, left, layout.miniTabY(), lagTabW, 16, worldMiniTab == WorldMiniTab.LAG_MAP);
        drawTopChip(ctx, left + lagTabW + 6, layout.miniTabY(), blockTabW, 16, worldMiniTab == WorldMiniTab.BLOCK_ENTITIES);
        ctx.drawText(textRenderer, "Lag Map", left + 16, layout.miniTabY() + 4, worldMiniTab == WorldMiniTab.LAG_MAP ? TEXT_PRIMARY : TEXT_DIM, false);
        ctx.drawText(textRenderer, "Block Entities", left + lagTabW + 20, layout.miniTabY() + 4, worldMiniTab == WorldMiniTab.BLOCK_ENTITIES ? TEXT_PRIMARY : TEXT_DIM, false);
        int findingsCount = ProfilerManager.getInstance().getLatestRuleFindings().size();
        ctx.drawText(textRenderer, String.format(Locale.ROOT, "Selected chunk: %s | hot chunks: %d | findings: %d", selectedLagChunk == null ? "none" : (selectedLagChunk.x + "," + selectedLagChunk.z), ProfilerManager.getInstance().getLatestHotChunks().size(), findingsCount), left, layout.summaryY(), TEXT_DIM, false);
        top = layout.mapRenderY();

        if (worldMiniTab == WorldMiniTab.LAG_MAP) {
            renderLagMap(ctx, layout.left(), layout.mapRenderY(), layout.mapWidth(), layout.mapHeight());
            top = layout.mapTop() + (layout.cell() * ((layout.radius() * 2) + 1)) + 18;
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
        ctx.fill(x, y, x + width, y + 116, 0x14000000);
        String source = blankToUnknown(system.sensorSource());
        String[] sourceParts = source.split("\\| Tried: ", 2);
        String activeSource = sourceParts[0].trim();
        String attempts = sourceParts.length > 1 ? sourceParts[1].trim() : "provider attempts unavailable";
        String status = blankToUnknown(system.cpuSensorStatus());
        String availability = system.cpuTemperatureC() >= 0 || system.gpuTemperatureC() >= 0 ? "Measured temperatures available" : "Falling back to load-only telemetry";
        ctx.fill(x, y, x + width, y + 16, 0x22000000);
        ctx.drawText(textRenderer, "Sensors", x + 6, y + 4, TEXT_PRIMARY, false);
        addTooltip(x + 6, y + 2, 50, 14, "Sensor diagnostics shows provider availability, fallback path, and the last bridge error.");
        String statusLabel = textRenderer.trimToWidth(status, width - 12);
        ctx.drawText(textRenderer, statusLabel, x + width - 6 - textRenderer.getWidth(statusLabel), y + 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Provider: " + activeSource, width - 12), x + 6, y + 22, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Availability: " + availability, width - 12), x + 6, y + 36, system.cpuTemperatureC() >= 0 || system.gpuTemperatureC() >= 0 ? ACCENT_GREEN : ACCENT_YELLOW, false);
        String tempSummary = "CPU temp " + formatTemperature(system.cpuTemperatureC()) + " | GPU temp " + formatTemperature(system.gpuTemperatureC()) + " | CPU load " + formatPercent(system.cpuCoreLoadPercent()) + " | GPU load " + formatPercent(system.gpuCoreLoadPercent());
        ctx.drawText(textRenderer, textRenderer.trimToWidth(tempSummary, width - 12), x + 6, y + 50, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Counter source: " + blankToUnknown(system.counterSource()), width - 12), x + 6, y + 64, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Attempts: " + attempts, width - 12), x + 6, y + 78, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Reason: " + blankToUnknown(system.cpuTemperatureUnavailableReason()), width - 12), x + 6, y + 92, TEXT_DIM, false);
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Last provider error: " + blankToUnknown(system.sensorErrorCode()), width - 12), x + 6, y + 106, ACCENT_YELLOW, false);
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
        int maxEntitiesOverall = Math.max(1, snapshot.entityCounts().totalEntities());
        rowY = renderWrappedText(ctx, x, rowY, width, String.format(Locale.ROOT, "Measured counts: %d entities | %d block entities | %d activity samples | loaded-world max %d", totalEntities, totalBlockEntities, activityHistory.size(), maxEntitiesOverall), TEXT_DIM);
        String topEntityClass = entityCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
        String topBlockEntityClass = blockEntityCounts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
        rowY = renderWrappedText(ctx, x, rowY + 2, width, "Top hot classes: entity " + topEntityClass + " | block entity " + topBlockEntityClass, TEXT_DIM);
        boolean chunkIoHint = ProfilerManager.getInstance().getLatestLockSummaries().stream()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .anyMatch(line -> line.contains("chunk") || line.contains("region") || line.contains("anvil") || line.contains("poi"));
        if (chunkIoHint) {
            rowY = renderWrappedText(ctx, x, rowY + 2, width, "Chunk I/O lock hint active in current window. Cross-check blocked threads below and on the System tab.", ACCENT_YELLOW);
        }
        rowY += renderSimpleHistoryGraph(ctx, x, rowY + 2, width, 64, activityHistory, "Chunk activity over time [measured]", "activity", maxEntitiesOverall) + 8;
        rowY = renderCountMap(ctx, x, rowY, width, "Top entities [measured counts]", entityCounts, false) + 6;
        rowY = renderCountMap(ctx, x, rowY, width, "Top block entities [measured counts]", blockEntityCounts) + 6;
        return rowY;
    }


    private int renderCountMap(DrawContext ctx, int x, int y, int width, String title, Map<String, Integer> counts) {
        return renderCountMap(ctx, x, y, width, title, counts, true);
    }

    private int renderCountMap(DrawContext ctx, int x, int y, int width, String title, Map<String, Integer> counts, boolean normalizeLabels) {
        ctx.drawText(textRenderer, title, x, y, TEXT_DIM, false);
        int rowY = y + 12;
        if (counts.isEmpty()) {
            ctx.drawText(textRenderer, "none", x + 6, rowY, TEXT_DIM, false);
            return rowY + 12;
        }
        int shown = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).toList()) {
            String rawLabel = normalizeLabels ? cleanProfilerLabel(entry.getKey()) : entry.getKey();
            String label = textRenderer.trimToWidth(rawLabel, Math.max(60, width - 36));
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
        if (selectedFindingKey == null || findings.stream().noneMatch(finding -> findingKey(finding).equals(selectedFindingKey))) {
            selectedFindingKey = findingKey(findings.getFirst());
        }
        boolean stacked = activeTab == 9 || width < 720;
        int listWidth = stacked ? width : Math.max(180, width / 2);
        int shown = 0;
        for (ProfilerManager.RuleFinding finding : findings) {
            int color = switch (finding.severity()) {
                case "critical" -> 0xFFFF4444;
                case "warning" -> ACCENT_YELLOW;
                case "error" -> ACCENT_RED;
                default -> TEXT_DIM;
            };
            boolean selected = findingKey(finding).equals(selectedFindingKey);
            int itemHeight = 24;
            if (selected) {
                ctx.fill(x + 2, rowY - 2, x + listWidth, rowY + itemHeight - 2, 0x18000000);
            }
            findingClickTargets.add(new FindingClickTarget(x + 2, rowY - 2, listWidth - 2, itemHeight, findingKey(finding)));
            String heading = prettifyKey(finding.category()) + " | " + finding.severity().toUpperCase(Locale.ROOT) + " | " + finding.confidence();
            ctx.drawText(textRenderer, textRenderer.trimToWidth(heading, listWidth - 12), x + 6, rowY, color, false);
            rowY += 12;
            ctx.drawText(textRenderer, textRenderer.trimToWidth(finding.message(), listWidth - 18), x + 12, rowY, TEXT_PRIMARY, false);
            rowY += 14;
            shown++;
            if (shown >= 5) {
                break;
            }
        }
        ProfilerManager.RuleFinding selected = findings.stream().filter(finding -> findingKey(finding).equals(selectedFindingKey)).findFirst().orElse(findings.getFirst());
        int detailX = stacked ? x : x + listWidth + 8;
        int detailW = stacked ? width : Math.max(140, width - listWidth - 8);
        int detailBoxY = stacked ? rowY : y + 12;
        int detailInnerY = detailBoxY + 22;
        int detailTextHeight = measureWrappedHeight(detailW - 16, selected.message())
                + measureWrappedHeight(detailW - 16, "Why: " + selected.details())
                + measureWrappedHeight(detailW - 16, "Metrics: " + selected.metricSummary())
                + measureWrappedHeight(detailW - 16, "Next step: " + selected.nextStep())
                + 18;
        int detailHeight = Math.max(92, detailTextHeight + 18);
        drawInsetPanel(ctx, detailX, detailBoxY, detailW, stacked ? detailHeight : Math.max(detailHeight, rowY - y + 18));
        ctx.drawText(textRenderer, "Finding drilldown", detailX + 8, detailBoxY + 8, TEXT_PRIMARY, false);
        int detailY = renderWrappedText(ctx, detailX + 8, detailInnerY, detailW - 16, selected.message(), TEXT_PRIMARY);
        detailY = renderWrappedText(ctx, detailX + 8, detailY + 2, detailW - 16, "Why: " + selected.details(), TEXT_DIM);
        detailY = renderWrappedText(ctx, detailX + 8, detailY + 2, detailW - 16, "Metrics: " + selected.metricSummary(), TEXT_DIM);
        renderWrappedText(ctx, detailX + 8, detailY + 2, detailW - 16, "Next step: " + selected.nextStep(), ACCENT_YELLOW);
        return stacked ? Math.max((detailBoxY + detailHeight + 8) - y, rowY - y) : Math.max(rowY - y, detailHeight + 8);
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
        return renderSimpleHistoryGraph(ctx, x, y, width, height, history, title, units, -1);
    }

    private int renderSimpleHistoryGraph(DrawContext ctx, int x, int y, int width, int height, java.util.List<Integer> history, String title, String units, int explicitMax) {
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
        int max = explicitMax > 0 ? explicitMax : history.stream().mapToInt(Integer::intValue).max().orElse(1);
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
        startupSearchFocused = false;
        focusedColorSetting = null;

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

        for (FindingClickTarget target : findingClickTargets) {
            if (isInside(mouseX, mouseY, target.x(), target.y(), target.width(), target.height())) {
                selectedFindingKey = target.key();
                return true;
            }
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
            if (isInside(mouseX, mouseY, PADDING + 208, getContentY() + PADDING + 22, 78, 16)) {
                activeTab = 10;
                systemMiniTab = SystemMiniTab.CPU_GRAPH;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 160, getContentY() + PADDING + 24, 152, 16)) {
                focusedSearchTable = TableId.TASKS;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 214, getContentY() + PADDING + 24, 48, 16)) {
                resetTasksTable();
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
            if (isInside(mouseX, mouseY, PADDING + 218, getContentY() + PADDING + 22, 78, 16)) {
                activeTab = 10;
                systemMiniTab = SystemMiniTab.GPU_GRAPH;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 160, getContentY() + PADDING + 24, 152, 16)) {
                focusedSearchTable = TableId.GPU;
                return true;
            }
            if (isInside(mouseX, mouseY, listW - 214, getContentY() + PADDING + 24, 48, 16)) {
                resetGpuTable();
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
            if (isInside(mouseX, mouseY, tableW - 106, getContentY() + PADDING + 2, 98, 16)) {
                activeTab = 10;
                systemMiniTab = SystemMiniTab.MEMORY_GRAPH;
                scrollOffset = 0;
                return true;
            }
            if (isInside(mouseX, mouseY, tableW - 160, getContentY() + PADDING + 28, 152, 16)) {
                focusedSearchTable = TableId.MEMORY;
                return true;
            }
            if (isInside(mouseX, mouseY, tableW - 214, getContentY() + PADDING + 28, 48, 16)) {
                resetMemoryTable();
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

        if (activeTab == 3) {
            if (isInside(mouseX, mouseY, getScreenWidth() - 160, getContentY() + PADDING + 28 - scrollOffset, 152, 16)) {
                startupSearchFocused = true;
                return true;
            }
            if (isInside(mouseX, mouseY, getScreenWidth() - 214, getContentY() + PADDING + 28 - scrollOffset, 48, 16)) {
                resetStartupTable();
                return true;
            }
            if (handleStartupHeaderClick(mouseX, mouseY, 0, getScreenWidth())) {
                return true;
            }
        }

        if (activeTab == 9) {
            LagMapLayout lagMapLayout = lastRenderedLagMapLayout != null
                    ? lastRenderedLagMapLayout
                    : getLagMapLayout(getContentY(), getScreenWidth(), getScreenHeight() - getContentY() - PADDING);
            int left = lagMapLayout.left();
            int top = lagMapLayout.miniTabY();
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

        if (activeTab == 10) {
            int left = PADDING;
            int top = getFullPageScrollTop(getContentY()) + 28;
            if (isInside(mouseX, mouseY, left, top, 78, 16)) {
                systemMiniTab = SystemMiniTab.OVERVIEW;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 84, top, 88, 16)) {
                systemMiniTab = SystemMiniTab.CPU_GRAPH;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 178, top, 88, 16)) {
                systemMiniTab = SystemMiniTab.GPU_GRAPH;
                return true;
            }
            if (isInside(mouseX, mouseY, left + 272, top, 108, 16)) {
                systemMiniTab = SystemMiniTab.MEMORY_GRAPH;
                return true;
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
            int[] colorOffsets = {706, 728};
            ColorSetting[] colorSettings = {ColorSetting.CPU, ColorSetting.GPU};
            for (int i = 0; i < colorOffsets.length; i++) {
                if (isInside(mouseX, mouseY, left, top + colorOffsets[i], getScreenWidth() - 16, 16)) {
                    focusedColorSetting = colorSettings[i];
                    colorEditValue = getColorSettingHex(focusedColorSetting);
                    return true;
                }
            }
            if (isInside(mouseX, mouseY, left, top + 750, getScreenWidth() - 16, 16)) {
                ConfigManager.resetGraphColors();
                focusedColorSetting = null;
                colorEditValue = "";
                return true;
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
            case 3 -> Math.max(visibleHeight, 68 + (snapshot.startupRows().size() * STARTUP_ROW_HEIGHT));
            case 4 -> Math.max(visibleHeight, 214 + (snapshot.memoryMods().size() * ROW_HEIGHT));
            case 5 -> Math.max(visibleHeight, 44 + (Math.min(20, snapshot.flamegraphStacks().size()) * 12));
            case 6 -> Math.max(visibleHeight, 430);
            case 7 -> Math.max(visibleHeight, 560);
            case 8 -> Math.max(visibleHeight, 240);
            case 9 -> Math.max(visibleHeight, 1160);
            case 10 -> Math.max(visibleHeight, 1240);
            case 11 -> Math.max(visibleHeight, 980);
            default -> visibleHeight;
        };
        return Math.max(0, contentHeight - visibleHeight);
    }

    private boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (focusedColorSetting != null && input.isValidChar()) {
            colorEditValue = normalizeColorEdit(colorEditValue + input.asString());
            return true;
        }
        if (startupSearchFocused && input.isValidChar()) {
            startupSearch += input.asString();
            scrollOffset = 0;
            return true;
        }
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
        if (focusedColorSetting != null) {
            if (input.key() == 259) {
                if (!colorEditValue.isEmpty()) {
                    colorEditValue = colorEditValue.substring(0, Math.max(0, colorEditValue.length() - 1));
                    if (colorEditValue.isEmpty()) colorEditValue = "#";
                }
                return true;
            }
            if (input.key() == 257 || input.key() == 335) {
                applyColorSetting(focusedColorSetting, colorEditValue);
                focusedColorSetting = null;
                colorEditValue = "";
                return true;
            }
            if (input.key() == 256) {
                focusedColorSetting = null;
                colorEditValue = "";
                return true;
            }
        }
        if (startupSearchFocused) {
            if (input.key() == 259) {
                if (!startupSearch.isEmpty()) {
                    startupSearch = startupSearch.substring(0, startupSearch.length() - 1);
                }
                return true;
            }
            if (input.key() == 256) {
                startupSearchFocused = false;
                return true;
            }
        }
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
        if (taskSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> taskComparator(Map<String, CpuSamplingProfiler.Snapshot> cpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails, Map<String, ModTimingSnapshot> invokes) {
        return switch (taskSort) {
            case NAME -> Comparator.comparing((String modId) -> getDisplayName(modId).toLowerCase(Locale.ROOT));
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
        if (gpuSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> gpuComparator(Map<String, CpuSamplingProfiler.Snapshot> cpu, Map<String, CpuSamplingProfiler.DetailSnapshot> cpuDetails, long totalRenderSamples, long totalGpuNs) {
        return switch (gpuSort) {
            case NAME -> Comparator.comparing((String modId) -> getDisplayName(modId).toLowerCase(Locale.ROOT));
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
        if (memorySortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<String> memoryComparator(Map<String, Long> memoryMods, Map<String, Map<String, Long>> memoryClassesByMod, long totalAttributedBytes) {
        return switch (memorySort) {
            case NAME -> Comparator.comparing((String modId) -> getDisplayName(modId).toLowerCase(Locale.ROOT));
            case CLASS_COUNT -> Comparator.comparingInt((String modId) -> memoryClassesByMod.getOrDefault(modId, Map.of()).size());
            case PERCENT -> Comparator.comparingDouble((String modId) -> memoryMods.getOrDefault(modId, 0L) * 100.0 / totalAttributedBytes);
            case MEMORY_MB -> Comparator.comparingLong((String modId) -> memoryMods.getOrDefault(modId, 0L));
        };
    }

    private java.util.List<StartupTimingProfiler.StartupRow> getStartupRows() {
        String query = startupSearch.toLowerCase(Locale.ROOT);
        java.util.List<StartupTimingProfiler.StartupRow> rows = new ArrayList<>();
        for (StartupTimingProfiler.StartupRow row : snapshot.startupRows()) {
            String haystack = (row.modId() + " " + getDisplayName(row.modId()) + " " + row.stageSummary() + " " + row.definitionSummary()).toLowerCase(Locale.ROOT);
            if (query.isBlank() || haystack.contains(query)) {
                rows.add(row);
            }
        }
        rows.sort(startupComparator());
        if (startupSortDescending) {
            Collections.reverse(rows);
        }
        return rows;
    }

    private Comparator<StartupTimingProfiler.StartupRow> startupComparator() {
        return switch (startupSort) {
            case NAME -> Comparator.comparing((StartupTimingProfiler.StartupRow row) -> getDisplayName(row.modId()).toLowerCase(Locale.ROOT));
            case START -> Comparator.comparingLong(StartupTimingProfiler.StartupRow::first);
            case END -> Comparator.comparingLong(StartupTimingProfiler.StartupRow::last);
            case ACTIVE -> Comparator.comparingLong(StartupTimingProfiler.StartupRow::activeNanos);
            case ENTRYPOINTS -> Comparator.comparingInt(StartupTimingProfiler.StartupRow::entrypoints);
            case REGISTRATIONS -> Comparator.comparingInt(StartupTimingProfiler.StartupRow::registrations);
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
        int modX = PADDING + ICON_SIZE + 6;
        int pctX = listW - 206;
        int threadsX = listW - 146;
        int samplesX = listW - 92;
        int invokesX = listW - 42;
        if (isInside(mouseX, mouseY, modX, headerY, 120, 14)) { toggleTaskSort(TaskSort.NAME); return true; }
        if (isInside(mouseX, mouseY, pctX, headerY, 54, 14)) { toggleTaskSort(TaskSort.CPU); return true; }
        if (isInside(mouseX, mouseY, threadsX, headerY, 62, 14)) { toggleTaskSort(TaskSort.THREADS); return true; }
        if (isInside(mouseX, mouseY, samplesX, headerY, 62, 14)) { toggleTaskSort(TaskSort.SAMPLES); return true; }
        if (isInside(mouseX, mouseY, invokesX, headerY, 58, 14)) { toggleTaskSort(TaskSort.INVOKES); return true; }
        return false;
    }

    private boolean handleGpuHeaderClick(double mouseX, double mouseY, int listW) {
        int headerY = getContentY() + PADDING + 50;
        int modX = PADDING + ICON_SIZE + 6;
        int pctX = listW - 232;
        int threadsX = listW - 172;
        int gpuMsX = listW - 108;
        int renderSamplesX = listW - 42;
        if (isInside(mouseX, mouseY, modX, headerY, 120, 14)) { toggleGpuSort(GpuSort.NAME); return true; }
        if (isInside(mouseX, mouseY, pctX, headerY, 64, 14)) { toggleGpuSort(GpuSort.EST_GPU); return true; }
        if (isInside(mouseX, mouseY, threadsX, headerY, 62, 14)) { toggleGpuSort(GpuSort.THREADS); return true; }
        if (isInside(mouseX, mouseY, gpuMsX, headerY, 54, 14)) { toggleGpuSort(GpuSort.GPU_MS); return true; }
        if (isInside(mouseX, mouseY, renderSamplesX, headerY, 42, 14)) { toggleGpuSort(GpuSort.RENDER_SAMPLES); return true; }
        return false;
    }

    private boolean handleMemoryHeaderClick(double mouseX, double mouseY, int tableW) {
        int headerY = getContentY() + PADDING + 96;
        int modX = PADDING + ICON_SIZE + 6;
        int classesX = tableW - 140;
        int mbX = tableW - 94;
        int pctX = tableW - 42;
        if (isInside(mouseX, mouseY, modX, headerY, 120, 14)) { toggleMemorySort(MemorySort.NAME); return true; }
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

    private boolean handleStartupHeaderClick(double mouseX, double mouseY, int x, int w) {
        int headerY = getContentY() + PADDING + 68 - scrollOffset;
        int regsX = x + w - 34;
        int epX = regsX - 28;
        int activeMsX = epX - 54;
        int endMsX = activeMsX - 54;
        int startMsX = endMsX - 54;
        int barW = Math.max(120, Math.min(220, w / 7));
        int barX = startMsX - barW - 14;
        int modX = x + PADDING + ICON_SIZE + 6;
        if (isInside(mouseX, mouseY, modX, headerY, Math.max(100, barX - modX - 8), 14)) { toggleStartupSort(StartupSort.NAME); return true; }
        if (isInside(mouseX, mouseY, startMsX, headerY, 44, 14)) { toggleStartupSort(StartupSort.START); return true; }
        if (isInside(mouseX, mouseY, endMsX, headerY, 40, 14)) { toggleStartupSort(StartupSort.END); return true; }
        if (isInside(mouseX, mouseY, activeMsX, headerY, 48, 14)) { toggleStartupSort(StartupSort.ACTIVE); return true; }
        if (isInside(mouseX, mouseY, epX, headerY, 22, 14)) { toggleStartupSort(StartupSort.ENTRYPOINTS); return true; }
        if (isInside(mouseX, mouseY, regsX, headerY, 28, 14)) { toggleStartupSort(StartupSort.REGISTRATIONS); return true; }
        return false;
    }

    private void toggleStartupSort(StartupSort sort) {
        if (startupSort == sort) {
            startupSortDescending = !startupSortDescending;
        } else {
            startupSort = sort;
            startupSortDescending = true;
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

        LagMapLayout layout = lastRenderedLagMapLayout != null
                ? lastRenderedLagMapLayout
                : getLagMapLayout(getContentY(), getScreenWidth(), getScreenHeight() - getContentY() - PADDING);
        ChunkPos playerChunk = client.player.getChunkPos();
        for (int dz = -layout.radius(); dz <= layout.radius(); dz++) {
            for (int dx = -layout.radius(); dx <= layout.radius(); dx++) {
                int px = layout.left() + (dx + layout.radius()) * layout.cell();
                int py = layout.mapTop() + (dz + layout.radius()) * layout.cell();
                if (mouseX >= px && mouseX < px + layout.cell() && mouseY >= py && mouseY < py + layout.cell()) {
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

    private void renderStripedRowVariable(DrawContext ctx, int x, int width, int rowY, int rowHeight, int rowIdx, int mouseX, int mouseY) {
        if (rowIdx % 2 == 0) {
            ctx.fill(x, rowY, x + width, rowY + rowHeight, ROW_ALT);
        }
        if (mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight) {
            ctx.fill(x, rowY, x + width, rowY + rowHeight, 0x22FFFFFF);
        }
    }

    private LagMapLayout getLagMapLayout(int contentY, int contentW, int contentH) {
        int left = PADDING;
        int top = getFullPageScrollTop(contentY);
        top = renderSectionHeaderOffset(top, true);
        int miniTabY = top;
        int summaryY = miniTabY + 24;
        int mapRenderY = summaryY + 14;
        int mapWidth = Math.min(260, contentW - 24);
        int mapHeight = Math.min(260, contentH - 32);
        int radius = 4;
        int cell = Math.max(12, Math.min(20, Math.min(mapWidth, mapHeight - 18) / ((radius * 2) + 1)));
        int mapTop = mapRenderY + 14;
        return new LagMapLayout(left, miniTabY, summaryY, mapRenderY, mapWidth, mapHeight, cell, radius, mapTop);
    }

    private int renderSectionHeaderOffset(int y, boolean hasSubtitle) {
        return hasSubtitle ? y + 28 : y + 16;
    }

    private void drawSettingRow(DrawContext ctx, int x, int y, int width, String label, String value, int mouseX, int mouseY) {
        if (isInside(mouseX, mouseY, x - 4, y - 2, width + 8, 16)) {
            ctx.fill(x - 4, y - 2, x + width + 4, y + 14, 0x1AFFFFFF);
        }
        drawMetricRow(ctx, x, y, width, label, value);
    }

    private void renderStripedRow(DrawContext ctx, int x, int width, int rowY, int rowIdx, int mouseX, int mouseY) {
        if (rowIdx % 2 == 0) {
            ctx.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_ALT);
        }
        if (mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
            ctx.fill(x, rowY, x + width, rowY + ROW_HEIGHT, 0x22FFFFFF);
        }
    }
    private boolean hasTaskFilter() {
        return !tasksSearch.isBlank() || taskSort != TaskSort.CPU || !taskSortDescending;
    }

    private boolean hasGpuFilter() {
        return !gpuSearch.isBlank() || gpuSort != GpuSort.EST_GPU || !gpuSortDescending;
    }

    private boolean hasMemoryFilter() {
        return !memorySearch.isBlank() || memorySort != MemorySort.MEMORY_MB || !memorySortDescending;
    }

    private boolean hasStartupFilter() {
        return !startupSearch.isBlank() || startupSort != StartupSort.ACTIVE || !startupSortDescending;
    }

    private void resetTasksTable() {
        tasksSearch = "";
        taskSort = TaskSort.CPU;
        taskSortDescending = true;
        focusedSearchTable = null;
    }

    private void resetGpuTable() {
        gpuSearch = "";
        gpuSort = GpuSort.EST_GPU;
        gpuSortDescending = true;
        focusedSearchTable = null;
    }

    private void resetStartupTable() {
        startupSearch = "";
        startupSort = StartupSort.ACTIVE;
        startupSortDescending = true;
        startupSearchFocused = false;
    }

    private void resetMemoryTable() {
        memorySearch = "";
        memorySort = MemorySort.MEMORY_MB;
        memorySortDescending = true;
        focusedSearchTable = null;
    }

    private String findingKey(ProfilerManager.RuleFinding finding) {
        return finding.category() + "|" + finding.message();
    }

    private void renderResetButton(DrawContext ctx, int x, int y, int width, int height, boolean active) {
        ctx.fill(x, y, x + width, y + height, active ? 0x223A3A3A : 0x14141414);
        ctx.fill(x, y, x + width, y + 1, PANEL_OUTLINE);
        ctx.fill(x, y + height - 1, x + width, y + height, PANEL_OUTLINE);
        ctx.drawText(textRenderer, "Reset", x + 8, y + 4, active ? TEXT_PRIMARY : TEXT_DIM, false);
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

    private void addTooltip(int x, int y, int width, int height, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        tooltipTargets.add(new TooltipTarget(x, y, width, height, text));
    }

    private void renderTooltipOverlay(DrawContext ctx, int mouseX, int mouseY) {
        TooltipTarget target = null;
        for (TooltipTarget candidate : tooltipTargets) {
            if (isInside(mouseX, mouseY, candidate.x(), candidate.y(), candidate.width(), candidate.height())) {
                target = candidate;
            }
        }
        if (target == null) {
            return;
        }
        int maxWidth = Math.min(320, getScreenWidth() - 24);
        List<OrderedText> wrapped = textRenderer.wrapLines(Text.literal(target.text()), maxWidth);
        int widest = 0;
        for (OrderedText line : wrapped) {
            widest = Math.max(widest, textRenderer.getWidth(line));
        }
        int boxW = Math.min(maxWidth + 10, Math.max(100, widest + 10));
        int boxH = Math.max(18, wrapped.size() * 12 + 6);
        int boxX = Math.min(getScreenWidth() - boxW - 8, mouseX + 10);
        int boxY = Math.min(getScreenHeight() - boxH - 8, mouseY + 10);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xE0121212);
        ctx.fill(boxX, boxY, boxX + boxW, boxY + 1, PANEL_OUTLINE);
        ctx.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, PANEL_OUTLINE);
        ctx.fill(boxX, boxY, boxX + 1, boxY + boxH, PANEL_OUTLINE);
        ctx.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, PANEL_OUTLINE);
        int textY = boxY + 4;
        for (OrderedText line : wrapped) {
            ctx.drawText(textRenderer, line, boxX + 5, textY, TEXT_PRIMARY, false);
            textY += 12;
        }
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

        int color = TEXT_PRIMARY;
        if (shown.equalsIgnoreCase("On") || shown.equalsIgnoreCase("Yes")) {
            color = ACCENT_GREEN;
        } else if (shown.equalsIgnoreCase("Off") || shown.equalsIgnoreCase("No")) {
            color = ACCENT_RED;
        }

        ctx.drawText(textRenderer, shown, x + width - textRenderer.getWidth(shown), y, color, false);
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

    private String formatCpuInfo() {
        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        int logicalCores = Runtime.getRuntime().availableProcessors();
        if (identifier == null || identifier.isBlank()) {
            return "CPU: " + System.getProperty("os.arch", "Unknown") + " | " + logicalCores + " logical cores";
        }
        String lower = identifier.toLowerCase(Locale.ROOT);
        String vendor = lower.contains("authenticamd") ? "AMD CPU" : (lower.contains("genuineintel") ? "Intel CPU" : "CPU");
        String family = extractCpuToken(identifier, "Family");
        String model = extractCpuToken(identifier, "Model");
        if (!family.isBlank() && !model.isBlank()) {
            return vendor + " | Family " + family + " Model " + model + " | " + logicalCores + " logical cores";
        }
        return vendor + " | " + identifier + " | " + logicalCores + " logical cores";
    }

    private String extractCpuToken(String identifier, String token) {
        int index = identifier.indexOf(token);
        if (index < 0) {
            return "";
        }
        String tail = identifier.substring(index + token.length()).trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char ch = tail.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.toString();
    }

    private int getCpuGraphColor() {
        return ConfigManager.getCpuGraphColor();
    }

    private int getGpuGraphColor() {
        return ConfigManager.getGpuGraphColor();
    }

    private int getMemoryGraphColor() {
        return 0xFF325C99;
    }

    private String getColorSettingHex(ColorSetting setting) {
        return switch (setting) {
            case CPU -> ConfigManager.getCpuGraphColorHex();
            case GPU -> ConfigManager.getGpuGraphColorHex();
        };
    }

    private void applyColorSetting(ColorSetting setting, String value) {
        switch (setting) {
            case CPU -> ConfigManager.setCpuGraphColorHex(value);
            case GPU -> ConfigManager.setGpuGraphColorHex(value);
        }
    }

    private String normalizeColorEdit(String value) {
        if (value == null || value.isBlank()) return "#";
        String cleaned = value.strip().toUpperCase(Locale.ROOT).replace("#", "");
        if (cleaned.length() > 6) cleaned = cleaned.substring(0, 6);
        cleaned = cleaned.replaceAll("[^0-9A-F]", "");
        return "#" + cleaned;
    }

    private String stutterBand(double score) {
        if (score < 5.0) return "Excellent";
        if (score < 10.0) return "Good";
        if (score < 20.0) return "Noticeable";
        if (score < 35.0) return "Bad";
        return "Severe";
    }

    private int stutterBandColor(double score) {
        if (score < 5.0) return ACCENT_GREEN;
        if (score < 10.0) return INTEL_COLOR;
        if (score < 20.0) return ACCENT_YELLOW;
        if (score < 35.0) return 0xFFFF8844;
        return ACCENT_RED;
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
        int graphHeight = Math.max(64, height - 30);
        int plotRightPadding = 74;
        int plotWidth = Math.max(72, graphWidth - plotRightPadding);
        int axisX = graphX + plotWidth + 8;
        int cardColor = 0x28101010;
        int borderColor = 0x44383838;
        int majorGridColor = 0x26FFFFFF;
        int minorGridColor = 0x12FFFFFF;
        ctx.fill(graphX - 3, graphY - 3, graphX + graphWidth + 3, graphY + graphHeight + 3, borderColor);
        ctx.fill(graphX - 2, graphY - 2, graphX + graphWidth + 2, graphY + graphHeight + 2, cardColor);

        double max = niceGraphMax(primary, secondary);
        double mid = max / 2.0;
        int midY = graphY + graphHeight / 2;
        int quarterY = graphY + graphHeight / 4;
        int threeQuarterY = graphY + (graphHeight * 3) / 4;

        ctx.fill(graphX, graphY, graphX + plotWidth, graphY + 1, majorGridColor);
        ctx.fill(graphX, midY, graphX + plotWidth, midY + 1, majorGridColor);
        ctx.fill(graphX, quarterY, graphX + plotWidth, quarterY + 1, minorGridColor);
        ctx.fill(graphX, threeQuarterY, graphX + plotWidth, threeQuarterY + 1, minorGridColor);
        ctx.fill(graphX, graphY + graphHeight - 1, graphX + plotWidth, graphY + graphHeight, majorGridColor);

        drawAxisLabel(ctx, axisX, graphY - 4, formatGraphValue(max, units));
        drawAxisLabel(ctx, axisX, midY - 4, formatGraphValue(mid, units));
        drawAxisLabel(ctx, axisX, graphY + graphHeight - 8, formatGraphValue(0.0, units));
        ctx.drawText(textRenderer, formatHistoryWindowLabel(spanSeconds), graphX, graphY + graphHeight + 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, "now", graphX + plotWidth - textRenderer.getWidth("now"), graphY + graphHeight + 4, TEXT_DIM, false);

        drawSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, primary, max, primaryColor);
        if (secondary != null && secondary.length > 0) {
            drawSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, secondary, max, secondaryColor);
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

    private void renderFixedScaleSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] values, String title, String units, int color, double fixedMax, double spanSeconds) {
        renderFixedScaleSeriesGraph(ctx, x, y, width, height, values, null, title, units, color, 0, fixedMax, spanSeconds);
    }

    private void renderFixedScaleSeriesGraph(DrawContext ctx, int x, int y, int width, int height, double[] primary, double[] secondary, String title, String units, int primaryColor, int secondaryColor, double fixedMax, double spanSeconds) {
        ctx.drawText(textRenderer, title + " (" + units + ")", x, y, TEXT_PRIMARY, false);
        int graphX = x;
        int graphY = y + 14;
        int graphWidth = Math.max(120, width);
        int graphHeight = Math.max(64, height - 30);
        int plotRightPadding = 74;
        int plotWidth = Math.max(72, graphWidth - plotRightPadding);
        int axisX = graphX + plotWidth + 8;
        int cardColor = 0x28101010;
        int borderColor = 0x44383838;
        int majorGridColor = 0x26FFFFFF;
        int minorGridColor = 0x12FFFFFF;
        ctx.fill(graphX - 3, graphY - 3, graphX + graphWidth + 3, graphY + graphHeight + 3, borderColor);
        ctx.fill(graphX - 2, graphY - 2, graphX + graphWidth + 2, graphY + graphHeight + 2, cardColor);

        double max = Math.max(1.0, fixedMax);
        double mid = max / 2.0;
        int midY = graphY + graphHeight / 2;
        int quarterY = graphY + graphHeight / 4;
        int threeQuarterY = graphY + (graphHeight * 3) / 4;
        ctx.fill(graphX, graphY, graphX + plotWidth, graphY + 1, majorGridColor);
        ctx.fill(graphX, midY, graphX + plotWidth, midY + 1, majorGridColor);
        ctx.fill(graphX, quarterY, graphX + plotWidth, quarterY + 1, minorGridColor);
        ctx.fill(graphX, threeQuarterY, graphX + plotWidth, threeQuarterY + 1, minorGridColor);
        ctx.fill(graphX, graphY + graphHeight - 1, graphX + plotWidth, graphY + graphHeight, majorGridColor);

        drawAxisLabel(ctx, axisX, graphY - 4, formatGraphValue(max, units));
        drawAxisLabel(ctx, axisX, midY - 4, formatGraphValue(mid, units));
        drawAxisLabel(ctx, axisX, graphY + graphHeight - 8, formatGraphValue(0.0, units));
        ctx.drawText(textRenderer, formatHistoryWindowLabel(spanSeconds), graphX, graphY + graphHeight + 4, TEXT_DIM, false);
        ctx.drawText(textRenderer, "now", graphX + plotWidth - textRenderer.getWidth("now"), graphY + graphHeight + 4, TEXT_DIM, false);
        drawSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, primary, max, primaryColor);
        if (secondary != null && secondary.length > 0) {
            drawSeriesBars(ctx, graphX, graphY, plotWidth, graphHeight, secondary, max, secondaryColor);
        }
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

    private void drawAxisLabel(DrawContext ctx, int x, int y, String text) {
        ctx.drawText(textRenderer, text, x, y, TEXT_DIM, false);
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
        double stutterScore = frames.getStutterScore();
        drawMetricRow(ctx, left, top, graphWidth, "Jitter Variance", String.format(Locale.ROOT, "stddev %.2f ms | variance %.2f ms^2 | stutter %.1f", frames.getFrameStdDevMs(), frames.getFrameVarianceMs(), stutterScore));
        top += 18;
        drawMetricRow(ctx, left, top, graphWidth, "Stutter Score", String.format(Locale.ROOT, "%.1f | %s", stutterScore, stutterBand(stutterScore)));
        top += 18;
        ctx.drawText(textRenderer, "Stutter guide", left, top, TEXT_PRIMARY, false);
        top += 14;
        top = renderWrappedText(ctx, left + 6, top, graphWidth - 12, "0-5 Excellent | 5-10 Good | 10-20 Noticeable | 20-35 Bad | 35+ Severe", stutterBandColor(stutterScore)) + 4;
        top = renderWrappedText(ctx, left + 6, top, graphWidth - 12, "Higher stutter scores mean frame pacing is less consistent even if average FPS still looks healthy.", TEXT_DIM) + 6;
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
        drawSettingRow(ctx, left, top, w - 24, "Session Logging", ProfilerManager.getInstance().isSessionLogging() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Session Duration", ConfigManager.getSessionDurationSeconds() + "s", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Metrics Update Interval", ConfigManager.getMetricsUpdateIntervalMs() + "ms", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Profiler Update Delay", ConfigManager.getProfilerUpdateDelayMs() + "ms", mouseX, mouseY);
        top += 32;
        ctx.drawText(textRenderer, "HUD Settings", left, top, TEXT_PRIMARY, false);
        top += 18;
        drawSettingRow(ctx, left, top, w - 24, "Enabled", ConfigManager.isHudEnabled() ? "Yes" : "No", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Position", String.valueOf(ConfigManager.getHudPosition()), mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Layout", String.valueOf(ConfigManager.getHudLayoutMode()), mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Trigger Mode", String.valueOf(ConfigManager.getHudTriggerMode()), mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "FPS", ConfigManager.isHudShowFps() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Frame Stats", ConfigManager.isHudShowFrame() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Tick Stats", ConfigManager.isHudShowTicks() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Utilization", ConfigManager.isHudShowUtilization() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Temperatures", ConfigManager.isHudShowTemperatures() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Parallelism", ConfigManager.isHudShowParallelism() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Memory", ConfigManager.isHudShowMemory() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "World", ConfigManager.isHudShowWorld() ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Session Status", ConfigManager.isHudShowSession() ? "On" : "Off", mouseX, mouseY);
        top += 32;
        ctx.drawText(textRenderer, "Table Columns", left, top, TEXT_PRIMARY, false);
        top += 18;
        drawSettingRow(ctx, left, top, w - 24, "Tasks: %CPU", ConfigManager.isTasksColumnVisible("cpu") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Tasks: Threads", ConfigManager.isTasksColumnVisible("threads") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Tasks: Samples", ConfigManager.isTasksColumnVisible("samples") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Tasks: Invokes", ConfigManager.isTasksColumnVisible("invokes") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "GPU: %GPU", ConfigManager.isGpuColumnVisible("pct") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "GPU: Threads", ConfigManager.isGpuColumnVisible("threads") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "GPU: Est ms", ConfigManager.isGpuColumnVisible("gpums") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "GPU: R.S", ConfigManager.isGpuColumnVisible("rsamples") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Memory: CLS", ConfigManager.isMemoryColumnVisible("classes") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Memory: MB", ConfigManager.isMemoryColumnVisible("mb") ? "On" : "Off", mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Memory: %", ConfigManager.isMemoryColumnVisible("pct") ? "On" : "Off", mouseX, mouseY);
        top += 32;
        ctx.drawText(textRenderer, "Graph Colours", left, top, TEXT_PRIMARY, false);
        top += 18;
        drawSettingRow(ctx, left, top, w - 24, "CPU Colour", focusedColorSetting == ColorSetting.CPU ? colorEditValue + "_" : getColorSettingHex(ColorSetting.CPU), mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "GPU Colour", focusedColorSetting == ColorSetting.GPU ? colorEditValue + "_" : getColorSettingHex(ColorSetting.GPU), mouseX, mouseY);
        top += 22;
        drawSettingRow(ctx, left, top, w - 24, "Reset Graph Colours", "Defaults", mouseX, mouseY);
        top += 20;
        ctx.drawText(textRenderer, textRenderer.trimToWidth("Click a colour row, type a hex value like #5EA9FF, then press Enter to save.", w - 24), left, top, TEXT_DIM, false);
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

















































