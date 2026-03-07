package wueffi.taskmanager.client.mixin;

import org.spongepowered.asm.mixin.Unique;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.taskmanagerClient;
import wueffi.taskmanager.client.util.GpuTimer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void taskmanager$onRenderHead(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!TaskManagerScreen.isProfilingActive()) return;
        GpuTimer.collectResults();
        RenderPhaseProfiler.getInstance().beginCpuPhase("frame.total");
        GpuTimer.begin("frame.total");
    }

    @Inject(
        method = "render",
        at = @At("TAIL")
    )
    private void taskmanager$onRenderTail(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        if (!TaskManagerScreen.isProfilingActive()) return;
        GpuTimer.end("frame.total");
        RenderPhaseProfiler.getInstance().endCpuPhase("frame.total");
    }

    @Inject(
        method = "renderWorld",
        at = @At("HEAD")
    )
    private void taskmanager$onRenderWorldHead(CallbackInfo ci) {
        if (!TaskManagerScreen.isProfilingActive()) return;
        RenderPhaseProfiler.getInstance().beginCpuPhase("gameRenderer.renderWorld");
        GpuTimer.begin("gameRenderer.renderWorld");
    }

    @Inject(
        method = "renderWorld",
        at = @At("TAIL")
    )
    private void taskmanager$onRenderWorldTail(CallbackInfo ci) {
        if (!TaskManagerScreen.isProfilingActive()) return;
        GpuTimer.end("gameRenderer.renderWorld");
        RenderPhaseProfiler.getInstance().endCpuPhase("gameRenderer.renderWorld");
    }
}
