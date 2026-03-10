package wueffi.taskmanager.client.mixin;

import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wueffi.taskmanager.client.InputLatencyProfiler;

@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onCursorPos", at = @At("HEAD"))
    private void taskmanager$onCursorPos(long window, double x, double y, CallbackInfo ci) {
        InputLatencyProfiler.getInstance().recordMouseMove(x, y);
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void taskmanager$onMouseButton(long window, MouseInput input, int modifiers, CallbackInfo ci) {
        InputLatencyProfiler.getInstance().recordInputEvent();
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"))
    private void taskmanager$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        InputLatencyProfiler.getInstance().recordInputEvent();
    }
}
