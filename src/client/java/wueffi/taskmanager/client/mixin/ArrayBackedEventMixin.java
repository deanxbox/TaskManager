package wueffi.taskmanager.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import wueffi.taskmanager.client.ProfilerManager;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wueffi.taskmanager.client.ModTimingProfiler;
import wueffi.taskmanager.client.StartupTimingProfiler;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.ModClassIndex;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;

@Mixin(targets = "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", remap = false)
public abstract class ArrayBackedEventMixin {

    @Redirect(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"
            )
    )
    private Object taskmanager$wrapInvoker(java.util.function.Function<Object, Object> factory, Object listeners) {
        recordStartupListeners(listeners);
        Object original = factory.apply(listeners);

        if (!ProfilerManager.getInstance().shouldCollectDetailedMetrics()) {
            return original;
        }

        Class<?>[] interfaces = original.getClass().getInterfaces();
        if (interfaces.length == 0) {
            return original;
        }

        return Proxy.newProxyInstance(
                original.getClass().getClassLoader(),
                interfaces,
                (proxy, method, args) -> {
                    long start = System.nanoTime();

                    try {
                        return method.invoke(original, args);
                    } finally {
                        long duration = System.nanoTime() - start;
                        String mod = ModClassIndex.getModForClassName(method.getDeclaringClass());
                        if (mod == null) mod = "unknown";

                        ModTimingProfiler.getInstance().record(mod, method.getName(), duration);
                    }
                }
        );
    }

    private void recordStartupListeners(Object listeners) {
        if (StartupTimingProfiler.closed || listeners == null || !listeners.getClass().isArray()) {
            return;
        }

        int length = Array.getLength(listeners);
        for (int i = 0; i < length; i++) {
            Object listener = Array.get(listeners, i);
            if (listener == null) continue;

            String mod = ModClassIndex.getModForClassName(listener.getClass());
            if (mod == null) {
                mod = "minecraft";
            }
            StartupTimingProfiler.getInstance().recordRegistration(mod);
        }
    }
}
