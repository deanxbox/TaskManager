package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModClassIndex;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class CpuSamplingProfiler {

    private static final CpuSamplingProfiler INSTANCE = new CpuSamplingProfiler();
    public static CpuSamplingProfiler getInstance() { return INSTANCE; }

    private static final int SAMPLE_INTERVAL_MS = 2;
    private static final int READY_CPU_SAMPLES = 180;
    private static final int READY_RENDER_SAMPLES = 90;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, String> classModCache = new ConcurrentHashMap<>();
    private final AtomicLong lastSampleAtMillis = new AtomicLong(0);
    private final Map<String, Map<String, LongAdder>> threadReasonsByMod = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LongAdder>> frameReasonsByMod = new ConcurrentHashMap<>();
    private final Map<String, Map<String, LongAdder>> renderReasonsByMod = new ConcurrentHashMap<>();

    private volatile boolean running = false;
    private Thread samplerThread;
    private volatile Thread clientThread;
    private volatile Thread renderThread;

    private static class Counter {
        final LongAdder totalSamples = new LongAdder();
        final LongAdder clientSamples = new LongAdder();
        final LongAdder renderSamples = new LongAdder();
    }

    private record SampleAttribution(String modId, String threadName, String frameReason) {}

    public record Snapshot(long totalSamples, long clientSamples, long renderSamples) {}
    public record DetailSnapshot(Map<String, Long> topThreads, Map<String, Long> topFrames, int sampledThreadCount) {}
    public record WindowSnapshot(Map<String, Snapshot> samples, Map<String, DetailSnapshot> detailsByMod, long lastSampleAgeMillis) {}

    public synchronized void start() {
        if (running) return;

        running = true;
        refreshThreads();

        samplerThread = new Thread(this::runSampler, "TaskManager-CPU-Sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    public synchronized void stop() {
        running = false;
    }

    public void reset() {
        counters.clear();
        threadReasonsByMod.clear();
        frameReasonsByMod.clear();
        renderReasonsByMod.clear();
        lastSampleAtMillis.set(0);
    }

    public WindowSnapshot drainWindow() {
        long ageMillis = getLastSampleAgeMillis();
        Map<String, Snapshot> result = new LinkedHashMap<>();
        counters.forEach((mod, counter) -> result.put(mod, new Snapshot(
                counter.totalSamples.sum(),
                counter.clientSamples.sum(),
                counter.renderSamples.sum()
        )));
        Map<String, DetailSnapshot> details = new LinkedHashMap<>();
        for (String mod : result.keySet()) {
            Map<String, Long> topThreads = snapshotReasonMap(threadReasonsByMod.get(mod));
            Map<String, Long> topFrames = snapshotReasonMap(mergeReasonMaps(frameReasonsByMod.get(mod), renderReasonsByMod.get(mod)));
            int sampledThreadCount = threadReasonsByMod.get(mod) == null ? 0 : threadReasonsByMod.get(mod).size();
            details.put(mod, new DetailSnapshot(topThreads, topFrames, sampledThreadCount));
        }
        reset();
        return new WindowSnapshot(result, details, ageMillis);
    }

    public long getLastSampleAgeMillis() {
        long last = lastSampleAtMillis.get();
        if (last == 0) return Long.MAX_VALUE;
        return Math.max(0, System.currentTimeMillis() - last);
    }

    public boolean hasEnoughCpuSamples(long samples) {
        return samples >= READY_CPU_SAMPLES;
    }

    public boolean hasEnoughRenderSamples(long samples) {
        return samples >= READY_RENDER_SAMPLES;
    }

    private void runSampler() {
        while (running) {
            try {
                if (ProfilerManager.getInstance().isCaptureActive()) {
                    sampleThreads();
                }
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private void sampleThreads() {
        Thread client = ensureThread(clientThread, "Client thread");
        if (client != null) {
            clientThread = client;
            sampleThread(client, false);
        }

        Thread render = ensureThread(renderThread, "Render thread");
        if (render != null) {
            renderThread = render;
            sampleThread(render, true);
        }
    }

    private void refreshThreads() {
        clientThread = ensureThread(null, "Client thread");
        renderThread = ensureThread(null, "Render thread");
    }

    private Thread ensureThread(Thread current, String name) {
        if (current != null && current.isAlive()) {
            return current;
        }

        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }

    private void sampleThread(Thread thread, boolean render) {
        StackTraceElement[] stack = thread.getStackTrace();
        if (stack.length == 0) return;

        SampleAttribution attribution = attributeStack(stack, thread.getName());
        Counter counter = counters.computeIfAbsent(attribution.modId(), ignored -> new Counter());
        counter.totalSamples.increment();
        if (render) {
            counter.renderSamples.increment();
        } else {
            counter.clientSamples.increment();
        }
        incrementReason(threadReasonsByMod, attribution.modId(), attribution.threadName());
        incrementReason(render ? renderReasonsByMod : frameReasonsByMod, attribution.modId(), attribution.frameReason());
        lastSampleAtMillis.set(System.currentTimeMillis());
    }

    private SampleAttribution attributeStack(StackTraceElement[] stack, String threadName) {
        String firstConcrete = null;
        String firstFramework = null;
        String firstKnown = null;
        String reasonFrame = null;

        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("wueffi.taskmanager.")) {
                continue;
            }

            String mod = classModCache.computeIfAbsent(className, this::resolveModForClassName);
            if (mod == null || "unknown".equals(mod)) {
                continue;
            }

            if (firstKnown == null) {
                firstKnown = mod;
            }

            if (isFrameworkMod(mod, className)) {
                if (firstFramework == null) {
                    firstFramework = mod;
                }
                if (reasonFrame == null) {
                    reasonFrame = formatFrameReason(frame);
                }
                continue;
            }

            if (firstConcrete == null) {
                firstConcrete = mod;
                reasonFrame = formatFrameReason(frame);
            }
        }

        if (firstConcrete != null) return new SampleAttribution(firstConcrete, threadName, reasonFrame == null ? "unknown-frame" : reasonFrame);
        if (firstFramework != null) return new SampleAttribution(firstFramework, threadName, reasonFrame == null ? findFallbackFrame(stack) : reasonFrame);
        return new SampleAttribution(firstKnown == null ? "minecraft" : firstKnown, threadName, reasonFrame == null ? findFallbackFrame(stack) : reasonFrame);
    }

    private String findFallbackFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!className.startsWith("wueffi.taskmanager.")) {
                return formatFrameReason(frame);
            }
        }
        return "unknown-frame";
    }

    private String formatFrameReason(StackTraceElement frame) {
        String className = frame.getClassName();
        int lastDot = className.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
        return simpleName + "#" + frame.getMethodName();
    }

    private void incrementReason(Map<String, Map<String, LongAdder>> target, String mod, String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        target.computeIfAbsent(mod, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(reason, ignored -> new LongAdder())
                .increment();
    }

    private Map<String, LongAdder> mergeReasonMaps(Map<String, LongAdder> first, Map<String, LongAdder> second) {
        Map<String, LongAdder> merged = new ConcurrentHashMap<>();
        if (first != null) {
            first.forEach((key, value) -> merged.computeIfAbsent(key, ignored -> new LongAdder()).add(value.sum()));
        }
        if (second != null) {
            second.forEach((key, value) -> merged.computeIfAbsent(key, ignored -> new LongAdder()).add(value.sum()));
        }
        return merged;
    }

    private Map<String, Long> snapshotReasonMap(Map<String, LongAdder> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(5)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().sum()));
        return result;
    }

    private String resolveModForClassName(String className) {
        String mod = ModClassIndex.getModForClassName(className);
        if (mod != null) {
            return mod;
        }

        if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) {
            return "minecraft";
        }

        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.") || className.startsWith("sun.") || className.startsWith("org.lwjgl.")) {
            return "shared/jvm";
        }

        if (className.startsWith("net.fabricmc.") || className.startsWith("org.spongepowered.asm.")) {
            return "shared/framework";
        }

        return "unknown";
    }

    private boolean isFrameworkMod(String mod, String className) {
        return "shared/framework".equals(mod)
                || "fabricloader".equals(mod)
                || mod.startsWith("fabric-")
                || mod.startsWith("fabric_api")
                || className.startsWith("net.fabricmc.")
                || className.startsWith("org.spongepowered.asm.");
    }
}
