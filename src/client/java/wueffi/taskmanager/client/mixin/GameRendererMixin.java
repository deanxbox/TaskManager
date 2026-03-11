package wueffi.taskmanager.client.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.FrameTimelineProfiler;
import wueffi.taskmanager.client.InputLatencyProfiler;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.GpuTimer;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void taskmanager$onRenderHead(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;

        FrameTimelineProfiler.getInstance().beginFrame();
        GpuTimer.collectResults();
        RenderPhaseProfiler.getInstance().beginCpuPhase("frame.total");
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void taskmanager$onRenderTail(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;

        RenderPhaseProfiler.getInstance().endCpuPhase("frame.total");
        FrameTimelineProfiler.getInstance().endFrame();
        InputLatencyProfiler.getInstance().onFramePresented();
    }

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void taskmanager$onRenderWorldHead(CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("gameRenderer.renderWorld");
        GpuTimer.begin("gameRenderer.renderWorld");
    }

    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void taskmanager$onRenderWorldTail(CallbackInfo ci) {
        if (!TaskManagerScreen.isLiveMetricsActive()) return;
        GpuTimer.end("gameRenderer.renderWorld");
        RenderPhaseProfiler.getInstance().endCpuPhase("gameRenderer.renderWorld");
    }
}


