package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModClassIndex;
import wueffi.taskmanager.client.util.BoundedMaps;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class FlamegraphProfiler {

    private static final FlamegraphProfiler INSTANCE = new FlamegraphProfiler();
    public static FlamegraphProfiler getInstance() { return INSTANCE; }

    private static final int SAMPLE_INTERVAL_MS = 5;
    private static final int MAX_STACK_DEPTH = 20;
    private static final int MAX_CLASS_MOD_CACHE_ENTRIES = 8_192;
    private static final int MAX_METHOD_CACHE_ENTRIES = 16_384;

    private final Map<String, LongAdder> stacks = new ConcurrentHashMap<>();
    private final Map<String, String> classModCache = BoundedMaps.synchronizedLru(MAX_CLASS_MOD_CACHE_ENTRIES);
    private final Map<String, String> methodCache = BoundedMaps.synchronizedLru(MAX_METHOD_CACHE_ENTRIES);

    private volatile boolean running = false;

    private Thread samplerThread;
    private final StringBuilder stackBuilder = new StringBuilder(512);

    public void start() {
        if (running) return;

        running = true;

        samplerThread = new Thread(this::runSampler, "TaskManager-Flamegraph");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
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
        for (ThreadSnapshotCollector.ThreadStackSnapshot threadSnapshot : findTrackedThreads()) {
            StackTraceElement[] stack = threadSnapshot.stack();
            if (stack.length == 0) {
                continue;
            }

            stackBuilder.setLength(0);
            stackBuilder.append("{").append(threadSnapshot.threadName()).append("};");

            int depth = Math.min(stack.length, MAX_STACK_DEPTH);
            for (int i = depth - 1; i >= 0; i--) {
                StackTraceElement e = stack[i];
                String className = e.getClassName();
                String mod = BoundedMaps.getOrCompute(classModCache, className, this::resolveMod);
                String methodKey = className + "#" + e.getMethodName();
                String method = BoundedMaps.getOrCompute(methodCache, methodKey, k -> formatMethodEntry(mod, className, e.getMethodName()));
                stackBuilder.append(method).append(';');
            }

            String key = stackBuilder.toString();
            stacks.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
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
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            Class<?> clazz = Class.forName(className, false, contextLoader != null ? contextLoader : FlamegraphProfiler.class.getClassLoader());
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

    private List<ThreadSnapshotCollector.ThreadStackSnapshot> findTrackedThreads() {
        return ThreadSnapshotCollector.getInstance().getLatestNamedSnapshots("Render thread", "Client thread");
    }
}
