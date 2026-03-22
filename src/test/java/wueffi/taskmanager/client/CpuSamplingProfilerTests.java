package wueffi.taskmanager.client;

import java.lang.reflect.Method;

public final class CpuSamplingProfilerTests {

    private CpuSamplingProfilerTests() {
    }

    public static void run() {
        fallbackFramePrefersNonRuntimeFrameOverDriverFrame();
        gpuDriverWaitClassifiesIntoSharedStallBucket();
    }

    private static void fallbackFramePrefersNonRuntimeFrameOverDriverFrame() {
        try {
            CpuSamplingProfiler profiler = CpuSamplingProfiler.getInstance();
            Method findFallbackFrame = CpuSamplingProfiler.class.getDeclaredMethod("findFallbackFrame", StackTraceElement[].class);
            findFallbackFrame.setAccessible(true);
            StackTraceElement[] stack = new StackTraceElement[] {
                    new StackTraceElement("org.lwjgl.opengl.GL30C", "glBindFramebuffer", "GL30C.java", 0),
                    new StackTraceElement("org.lwjgl.system.JNI", "invokePV", "JNI.java", 0),
                    new StackTraceElement("net.minecraft.client.render.WorldRenderer", "render", "WorldRenderer.java", 1200)
            };

            String reason = (String) findFallbackFrame.invoke(profiler, (Object) stack);
            assertEquals("WorldRenderer#render", reason, "attribution should explain the closest non-runtime CPU frame instead of the OpenGL submission frame");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void gpuDriverWaitClassifiesIntoSharedStallBucket() {
        try {
            CpuSamplingProfiler profiler = CpuSamplingProfiler.getInstance();
            Method attributeStack = CpuSamplingProfiler.class.getDeclaredMethod("attributeStack", StackTraceElement[].class, String.class, long.class, long.class);
            attributeStack.setAccessible(true);
            StackTraceElement[] stack = new StackTraceElement[] {
                    new StackTraceElement("org.lwjgl.opengl.GL32C", "glFenceSync", "GL32C.java", 0),
                    new StackTraceElement("org.lwjgl.system.JNI", "invokePV", "JNI.java", 0),
                    new StackTraceElement("net.fabricmc.fabric.impl.client.rendering.WorldRenderContextImpl", "render", "WorldRenderContextImpl.java", 120),
                    new StackTraceElement("org.embeddedt.chunky.SomeRenderHook", "draw", "SomeRenderHook.java", 64)
            };

            Object attribution = attributeStack.invoke(profiler, (Object) stack, "Render thread", 200_000L, 2_000_000L);
            Method modId = attribution.getClass().getDeclaredMethod("modId");
            String attributedMod = (String) modId.invoke(attribution);
            assertEquals("shared/gpu-stall", attributedMod, "GPU driver waits should be carried as a shared stall bucket instead of inflating a mod row");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
