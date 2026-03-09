package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModClassIndex;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryProfiler {

    private static final MemoryProfiler INSTANCE = new MemoryProfiler();
    public static MemoryProfiler getInstance() { return INSTANCE; }

    private static final ObjectName DIAGNOSTIC_COMMAND_NAME;
    private static final int MAX_SHARED_FAMILIES = 8;
    private static final int MAX_CLASSES_PER_FAMILY = 8;

    static {
        ObjectName name = null;
        try {
            name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        } catch (Exception ignored) {
        }
        DIAGNOSTIC_COMMAND_NAME = name;
    }

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile Map<String, Long> modMemoryBytes = Map.of();
    private volatile Map<String, Long> sharedClassFamilies = Map.of();
    private volatile Map<String, Map<String, Long>> sharedFamilyClasses = Map.of();
    private volatile Map<String, Map<String, Long>> topClassesByMod = Map.of();
    private final Map<String, String> classModCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastGcCountsByName = new ConcurrentHashMap<>();
    private final Map<String, Long> lastGcTimesByName = new ConcurrentHashMap<>();
    private final AtomicLong lastJvmSampleAtMillis = new AtomicLong(0);
    private final AtomicLong lastModSampleAtMillis = new AtomicLong(0);

    public void sampleJvm() {
        try {
            MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
            List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
            List<MemoryPoolMXBean> memoryPools = ManagementFactory.getMemoryPoolMXBeans();

            long gcCount = 0;
            long gcTime = 0;
            long youngGcCount = 0;
            long oldGcCount = 0;
            long gcPauseDurationMs = 0;
            String gcType = "none";
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                long count = gcBean.getCollectionCount();
                long time = gcBean.getCollectionTime();
                if (count > 0) gcCount += count;
                if (time > 0) gcTime += time;

                String beanName = gcBean.getName();
                long previousCount = lastGcCountsByName.getOrDefault(beanName, count);
                long previousTime = lastGcTimesByName.getOrDefault(beanName, time);
                long deltaCount = Math.max(0L, count - previousCount);
                long deltaTime = Math.max(0L, time - previousTime);
                lastGcCountsByName.put(beanName, count);
                lastGcTimesByName.put(beanName, time);

                if (deltaCount > 0 || deltaTime > 0) {
                    if (isOldGenCollector(beanName)) {
                        oldGcCount += deltaCount;
                    } else {
                        youngGcCount += deltaCount;
                    }
                    if (deltaTime >= gcPauseDurationMs) {
                        gcPauseDurationMs = deltaTime;
                        gcType = beanName;
                    }
                }
            }

            long directBufferBytes = 0;
            long mappedBufferBytes = 0;
            for (BufferPoolMXBean pool : bufferPools) {
                String name = pool.getName().toLowerCase();
                if (name.contains("direct")) {
                    directBufferBytes += Math.max(0, pool.getMemoryUsed());
                } else if (name.contains("mapped")) {
                    mappedBufferBytes += Math.max(0, pool.getMemoryUsed());
                }
            }

            long metaspaceBytes = 0;
            long codeCacheBytes = 0;
            long classSpaceBytes = 0;
            for (MemoryPoolMXBean pool : memoryPools) {
                long used = Math.max(0, pool.getUsage() == null ? 0 : pool.getUsage().getUsed());
                String name = pool.getName().toLowerCase();
                if (name.contains("metaspace")) {
                    metaspaceBytes += used;
                } else if (name.contains("code") || name.contains("codeheap")) {
                    codeCacheBytes += used;
                } else if (name.contains("class")) {
                    classSpaceBytes += used;
                }
            }

            long heapUsed = bean.getHeapMemoryUsage().getUsed();
            long heapCommitted = bean.getHeapMemoryUsage().getCommitted();

            snapshot = new Snapshot(
                    heapUsed,
                    heapCommitted,
                    bean.getHeapMemoryUsage().getMax(),
                    bean.getNonHeapMemoryUsage().getUsed(),
                    gcCount,
                    gcTime,
                    youngGcCount,
                    oldGcCount,
                    gcPauseDurationMs,
                    gcType,
                    directBufferBytes,
                    mappedBufferBytes,
                    SystemMetricsProfiler.getInstance().getSnapshot().directMemoryMaxBytes(),
                    metaspaceBytes,
                    codeCacheBytes,
                    classSpaceBytes,
                    Math.max(0, heapCommitted - heapUsed)
            );
            lastJvmSampleAtMillis.set(System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    public void samplePerMod() {
        if (DIAGNOSTIC_COMMAND_NAME == null) {
            return;
        }

        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Object result = server.invoke(
                    DIAGNOSTIC_COMMAND_NAME,
                    "gcClassHistogram",
                    new Object[]{new String[0]},
                    new String[]{String[].class.getName()}
            );

            if (!(result instanceof String histogram)) {
                return;
            }

            Map<String, Long> bytesByMod = new LinkedHashMap<>();
            Map<String, Long> familyTotals = new LinkedHashMap<>();
            Map<String, Map<String, Long>> familyClasses = new LinkedHashMap<>();
            Map<String, Map<String, Long>> classesByMod = new LinkedHashMap<>();
            String[] lines = histogram.split("\\R");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || !Character.isDigit(trimmed.charAt(0))) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+", 4);
                if (parts.length < 4 || !parts[0].endsWith(":")) {
                    continue;
                }

                long bytes;
                try {
                    bytes = Long.parseLong(parts[2]);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                String className = normalizeHistogramClassName(parts[3]);
                String mod = classModCache.computeIfAbsent(className, this::resolveHistogramMod);
                bytesByMod.merge(mod, bytes, Long::sum);
                classesByMod.computeIfAbsent(mod, ignored -> new LinkedHashMap<>()).merge(className, bytes, Long::sum);

                if ("shared/jvm".equals(mod) || "shared/framework".equals(mod)) {
                    String family = classFamily(className);
                    familyTotals.merge(family, bytes, Long::sum);
                    familyClasses.computeIfAbsent(family, ignored -> new LinkedHashMap<>()).merge(className, bytes, Long::sum);
                }
            }

            addRuntimeBucket(bytesByMod, "runtime/native-direct", snapshot.directBufferBytes() + snapshot.mappedBufferBytes());
            addRuntimeBucket(bytesByMod, "runtime/metaspace", snapshot.metaspaceBytes());
            addRuntimeBucket(bytesByMod, "runtime/code-cache", snapshot.codeCacheBytes());
            addRuntimeBucket(bytesByMod, "runtime/class-space", snapshot.classSpaceBytes());
            addRuntimeBucket(bytesByMod, "runtime/gc-headroom", snapshot.gcHeadroomBytes());

            modMemoryBytes = bytesByMod.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);

            sharedClassFamilies = familyTotals.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                    .limit(MAX_SHARED_FAMILIES)
                    .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);

            LinkedHashMap<String, Map<String, Long>> trimmedFamilyClasses = new LinkedHashMap<>();
            for (String family : sharedClassFamilies.keySet()) {
                Map<String, Long> topClasses = familyClasses.getOrDefault(family, Map.of()).entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .limit(MAX_CLASSES_PER_FAMILY)
                        .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
                trimmedFamilyClasses.put(family, topClasses);
            }
            sharedFamilyClasses = trimmedFamilyClasses;

            LinkedHashMap<String, Map<String, Long>> trimmedClassesByMod = new LinkedHashMap<>();
            for (String mod : modMemoryBytes.keySet()) {
                Map<String, Long> topClasses = classesByMod.getOrDefault(mod, Map.of()).entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .limit(MAX_CLASSES_PER_FAMILY)
                        .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
                trimmedClassesByMod.put(mod, topClasses);
            }
            topClassesByMod = trimmedClassesByMod;

            lastModSampleAtMillis.set(System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    public Snapshot getDetailedSnapshot() {
        return snapshot;
    }

    public Map<String, Long> getModMemoryBytes() {
        return modMemoryBytes;
    }

    public Map<String, Long> getSharedClassFamilies() {
        return sharedClassFamilies;
    }

    public Map<String, Map<String, Long>> getSharedFamilyClasses() {
        return sharedFamilyClasses;
    }

    public Map<String, Map<String, Long>> getTopClassesByMod() {
        return topClassesByMod;
    }

    public long getLastModSampleAgeMillis() {
        long last = lastModSampleAtMillis.get();
        if (last == 0) return Long.MAX_VALUE;
        return Math.max(0, System.currentTimeMillis() - last);
    }

    public void reset() {
        snapshot = Snapshot.empty();
        modMemoryBytes = Map.of();
        sharedClassFamilies = Map.of();
        sharedFamilyClasses = Map.of();
        topClassesByMod = Map.of();
        lastGcCountsByName.clear();
        lastGcTimesByName.clear();
        lastJvmSampleAtMillis.set(0);
        lastModSampleAtMillis.set(0);
    }

    private boolean isOldGenCollector(String name) {
        String lower = name.toLowerCase();
        return lower.contains("old") || lower.contains("mark") || lower.contains("mixed") || lower.contains("full") || lower.contains("tenured");
    }

    private void addRuntimeBucket(Map<String, Long> bytesByMod, String id, long bytes) {
        if (bytes > 0) {
            bytesByMod.put(id, bytes);
        }
    }

    private String normalizeHistogramClassName(String rawClassName) {
        String className = rawClassName.split("\\s+", 2)[0];
        if (className.startsWith("class ")) {
            className = className.substring(6);
        }
        if (className.startsWith("[L") && className.endsWith(";")) {
            return className.substring(2, className.length() - 1).replace('/', '.');
        }
        return className.replace('/', '.');
    }

    private String classFamily(String className) {
        if (className.startsWith("[")) {
            return "primitive arrays";
        }
        if (className.startsWith("java.util.")) {
            String[] parts = className.split("\\.");
            return parts.length >= 3 ? "java.util." + parts[2] : "java.util";
        }
        if (className.startsWith("java.lang.")) {
            String[] parts = className.split("\\.");
            return parts.length >= 3 ? "java.lang." + parts[2] : "java.lang";
        }
        if (className.startsWith("java.")) {
            int idx = className.indexOf('.', 5);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("javax.")) {
            int idx = className.indexOf('.', 6);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("jdk.")) {
            int idx = className.indexOf('.', 4);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("sun.")) {
            int idx = className.indexOf('.', 4);
            return idx > 0 ? className.substring(0, idx) : className;
        }
        if (className.startsWith("net.fabricmc.")) {
            return "net.fabricmc";
        }
        if (className.startsWith("org.spongepowered.")) {
            return "org.spongepowered";
        }
        if (className.startsWith("org.lwjgl.")) {
            return "org.lwjgl";
        }
        return className;
    }

    private String resolveHistogramMod(String className) {
        String mod = ModClassIndex.getModForClassName(className);
        if (mod != null) {
            if ("fabricloader".equals(mod) || mod.startsWith("fabric-") || mod.startsWith("fabric_api")) {
                return "shared/framework";
            }
            return mod;
        }

        String sanitized = className
                .replaceAll("\\$\\$Lambda.*", "")
                .replaceAll("\\$\\d+$", "")
                .replaceAll("\\$Subclass\\d+", "")
                .replaceAll("\\$MixinProxy.*", "");
        if (!sanitized.equals(className)) {
            mod = ModClassIndex.getModForClassName(sanitized);
            if (mod != null) {
                return mod;
            }
        }

        if (sanitized.startsWith("net.minecraft.") || sanitized.startsWith("com.mojang.")) {
            return "minecraft";
        }

        if (sanitized.startsWith("net.fabricmc.") || sanitized.startsWith("org.spongepowered.asm.")) {
            return "shared/framework";
        }

        if (sanitized.startsWith("java.") || sanitized.startsWith("javax.") || sanitized.startsWith("jdk.") || sanitized.startsWith("sun.") || sanitized.startsWith("org.lwjgl.")) {
            return "shared/jvm";
        }

        if (sanitized.startsWith("[")) {
            return "shared/jvm";
        }

        return "unknown";
    }

    public record Snapshot(
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long gcCount,
            long gcTimeMillis,
            long youngGcCount,
            long oldGcCount,
            long gcPauseDurationMs,
            String gcType,
            long directBufferBytes,
            long mappedBufferBytes,
            long directMemoryMaxBytes,
            long metaspaceBytes,
            long codeCacheBytes,
            long classSpaceBytes,
            long gcHeadroomBytes
    ) {
        static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, "none", 0, 0, -1, 0, 0, 0, 0);
        }
    }
}
