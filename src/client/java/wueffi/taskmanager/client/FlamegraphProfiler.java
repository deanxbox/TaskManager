package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModClassIndex;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class FlamegraphProfiler {

    private static final FlamegraphProfiler INSTANCE = new FlamegraphProfiler();
    public static FlamegraphProfiler getInstance() { return INSTANCE; }

    private static final int SAMPLE_INTERVAL_MS = 5;
    private static final int MAX_STACK_DEPTH = 20;

    private final Map<String, LongAdder> stacks = new ConcurrentHashMap<>();
    private final Map<String, String> classModCache = new ConcurrentHashMap<>();
    private final Map<String, String> methodCache = new ConcurrentHashMap<>();

    private volatile boolean running = false;

    private Thread samplerThread;
    private Thread targetThread;

    private final StringBuilder stackBuilder = new StringBuilder(512);

    public void start() {
        if (running) return;

        running = true;
        targetThread = findMinecraftThread();

        samplerThread = new Thread(this::runSampler, "TaskManager-Flamegraph");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    public void stop() {
        running = false;
    }

    private void runSampler() {
        while (running) {
            try {
                sample();
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (Throwable ignored) {
            }
        }
    }

    private void sample() {
        Thread thread = targetThread;
        if (thread == null) return;

        StackTraceElement[] stack = thread.getStackTrace();
        if (stack.length == 0) return;

        stackBuilder.setLength(0);

        int depth = Math.min(stack.length, MAX_STACK_DEPTH);
        for (int i = depth - 1; i >= 0; i--) {
            StackTraceElement e = stack[i];
            String className = e.getClassName();
            String mod = classModCache.computeIfAbsent(className, this::resolveMod);
            String methodKey = className + "#" + e.getMethodName();
            String method = methodCache.computeIfAbsent(methodKey, k -> formatMethodEntry(mod, className, e.getMethodName()));
            stackBuilder.append(method).append(';');
        }

        String key = stackBuilder.toString();
        stacks.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    private String formatMethodEntry(String mod, String className, String methodName) {
        String tag = tagFor(className, methodName, mod);
        return "[" + tag + "] " + mod + "." + methodName;
    }

    private String tagFor(String className, String methodName, String mod) {
        String haystack = (className + " " + methodName + " " + mod).toLowerCase(Locale.ROOT);
        if (haystack.contains("iris") || haystack.contains("shader") || haystack.contains("shadow")) return "Shaders/Iris";
        if (haystack.contains("sodium") || haystack.contains("chunk") || haystack.contains("section")) return "Chunks";
        if (haystack.contains("blockentity") || haystack.contains("block_entity")) return "Block Entities";
        if (haystack.contains("entity")) return "Entities";
        if (haystack.contains("particle")) return "Particles";
        if (haystack.contains("screen") || haystack.contains("gui") || haystack.contains("hud")) return "UI";
        if (haystack.contains("packet") || haystack.contains("network")) return "Networking";
        if (haystack.contains("sound") || haystack.contains("audio")) return "Audio";
        if (haystack.contains("world") || haystack.contains("level")) return "World";
        if (haystack.contains("render")) return "Rendering";
        return "General";
    }

    private String resolveMod(String className) {
        try {
            Class<?> clazz = Class.forName(className, false, targetThread.getContextClassLoader());
            String mod = ModClassIndex.getModForClassName(clazz);
            if (mod == null) return "minecraft";
            return mod;
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    public Map<String, Long> getStacks() {
        Map<String, Long> result = new LinkedHashMap<>();
        stacks.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }

    public void reset() {
        stacks.clear();
    }

    private Thread findMinecraftThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if ("Render thread".equals(name) || "Client thread".equals(name)) {
                return thread;
            }
        }
        return Thread.currentThread();
    }
}
