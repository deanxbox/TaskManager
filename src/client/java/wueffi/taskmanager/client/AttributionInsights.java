package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ModClassIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class AttributionInsights {

    enum Confidence {
        MEASURED("Measured"),
        INFERRED("Inferred"),
        PAIRWISE_INFERRED("Pairwise inferred"),
        KNOWN_INCOMPATIBILITY("Known incompatibility"),
        WEAK_HEURISTIC("Weak heuristic");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record AttributionCandidate(String modId, Confidence confidence, String reasonFrame, int weightPercent) {
        String displayLabel() {
            return modId + " " + weightPercent + "% | " + confidence.label() + " | " + reasonFrame;
        }
    }

    record ThreadAttribution(String ownerMod, Confidence confidence, String reasonFrame, List<String> topFrames, List<AttributionCandidate> candidates) {
        List<String> candidateLabels() {
            return candidates.stream()
                    .map(AttributionCandidate::displayLabel)
                    .toList();
        }
    }

    private AttributionInsights() {
    }

    static Confidence cpuConfidence(String modId, CpuSamplingProfiler.DetailSnapshot detail, long rawSamples, long shownSamples, long redistributedSamples) {
        if (modId == null) {
            return Confidence.WEAK_HEURISTIC;
        }
        if (isSharedAttributionBucket(modId)) {
            return Confidence.INFERRED;
        }
        if (isLowConfidenceCpuAttribution(detail, rawSamples, shownSamples, redistributedSamples)) {
            return Confidence.WEAK_HEURISTIC;
        }
        if (redistributedSamples > 0L || isRenderSubmissionHeavy(detail)) {
            return Confidence.INFERRED;
        }
        return rawSamples > 0L ? Confidence.MEASURED : Confidence.WEAK_HEURISTIC;
    }

    static Confidence gpuConfidence(String modId, long rawGpuNanos, long displayGpuNanos, long redistributedGpuNanos, long rawRenderSamples, long displayRenderSamples) {
        if (modId == null) {
            return Confidence.WEAK_HEURISTIC;
        }
        if (isSharedAttributionBucket(modId)) {
            return Confidence.INFERRED;
        }
        if ((rawGpuNanos <= 0L && redistributedGpuNanos > 0L)
                || (displayGpuNanos > 0L && redistributedGpuNanos * 10L >= displayGpuNanos * 7L)
                || (rawRenderSamples < 4L && displayRenderSamples > 0L && displayRenderSamples > rawRenderSamples)) {
            return Confidence.WEAK_HEURISTIC;
        }
        if (redistributedGpuNanos > 0L || displayRenderSamples > rawRenderSamples) {
            return Confidence.INFERRED;
        }
        return rawGpuNanos > 0L ? Confidence.MEASURED : Confidence.WEAK_HEURISTIC;
    }

    static Confidence memoryConfidence(String modId, long rawBytes, long displayBytes, long redistributedBytes, long memoryAgeMillis) {
        if (modId == null) {
            return Confidence.WEAK_HEURISTIC;
        }
        if (isSharedAttributionBucket(modId)) {
            return Confidence.INFERRED;
        }
        if (memoryAgeMillis > 15_000L || (rawBytes <= 0L && redistributedBytes > 0L && displayBytes > 0L)) {
            return Confidence.WEAK_HEURISTIC;
        }
        if (redistributedBytes > 0L) {
            return Confidence.INFERRED;
        }
        return rawBytes > 0L ? Confidence.MEASURED : Confidence.WEAK_HEURISTIC;
    }

    static String cpuProvenance(long rawSamples, long redistributedSamples, CpuSamplingProfiler.DetailSnapshot detail) {
        long renderSubmission = countOpaqueFrames(detail, true);
        return rawSamples + " raw | " + redistributedSamples + " redist | " + renderSubmission + " render-sub";
    }

    static String gpuProvenance(long rawGpuNanos, long redistributedGpuNanos, long rawRenderSamples, long displayRenderSamples) {
        return String.format(Locale.ROOT, "%.2f ms raw | %.2f ms redist | %d/%d render samp",
                rawGpuNanos / 1_000_000.0,
                redistributedGpuNanos / 1_000_000.0,
                rawRenderSamples,
                displayRenderSamples);
    }

    static String memoryProvenance(long rawBytes, long redistributedBytes, long memoryAgeMillis) {
        return String.format(Locale.ROOT, "%.1f MB raw | %.1f MB redist | age %d ms",
                rawBytes / (1024.0 * 1024.0),
                redistributedBytes / (1024.0 * 1024.0),
                memoryAgeMillis);
    }

    static ThreadAttribution attributeThread(String threadName, StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            String fallback = fallbackThreadOwner(threadName);
            return new ThreadAttribution(
                    fallback,
                    Confidence.WEAK_HEURISTIC,
                    "unknown-frame",
                    List.of(),
                    List.of(new AttributionCandidate(fallback, Confidence.WEAK_HEURISTIC, "unknown-frame", 100))
            );
        }
        String firstConcrete = null;
        String firstFramework = null;
        String firstKnown = null;
        String reason = "unknown-frame";
        List<String> topFrames = new ArrayList<>(3);
        Map<String, Double> weightsByMod = new LinkedHashMap<>();
        Map<String, String> reasonsByMod = new LinkedHashMap<>();
        int relevantDepth = 0;
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("wueffi.taskmanager.")) {
                continue;
            }
            relevantDepth++;
            if (topFrames.size() < 3 && !isOpaqueRuntimeFrame(className)) {
                topFrames.add(formatFrameReason(frame));
            }
            String mod = resolveModForClassName(className);
            if (firstKnown == null && mod != null) {
                firstKnown = mod;
                reason = formatFrameReason(frame);
            }
            if (mod == null || "unknown".equals(mod)) {
                continue;
            }
            double weight = frameWeight(relevantDepth, className, mod);
            weightsByMod.merge(mod, weight, Double::sum);
            reasonsByMod.putIfAbsent(mod, formatFrameReason(frame));
            if (isFrameworkMod(mod, className)) {
                if (firstFramework == null) {
                    firstFramework = mod;
                }
                continue;
            }
            if (!isSharedAttributionBucket(mod) && firstConcrete == null) {
                firstConcrete = mod;
                reason = formatFrameReason(frame);
            }
        }
        if (topFrames.isEmpty()) {
            for (StackTraceElement frame : stack) {
                if (!frame.getClassName().startsWith("wueffi.taskmanager.")) {
                    topFrames.add(formatFrameReason(frame));
                }
                if (topFrames.size() >= 3) {
                    break;
                }
            }
        }
        List<AttributionCandidate> candidates = buildCandidates(weightsByMod, reasonsByMod);
        AttributionCandidate leadCandidate = candidates.isEmpty() ? null : candidates.getFirst();
        if (firstConcrete != null) {
            Confidence confidence = isOpaqueRenderSubmissionFrame(topFrames.isEmpty() ? reason : topFrames.getFirst())
                    ? Confidence.WEAK_HEURISTIC
                    : (leadCandidate != null && firstConcrete.equals(leadCandidate.modId()) && leadCandidate.weightPercent() >= 55
                    ? Confidence.INFERRED
                    : Confidence.WEAK_HEURISTIC);
            return new ThreadAttribution(firstConcrete, confidence, reason, List.copyOf(topFrames), candidates);
        }
        if (firstFramework != null) {
            return new ThreadAttribution(firstFramework, Confidence.WEAK_HEURISTIC, reason, List.copyOf(topFrames), candidates);
        }
        String fallback = firstKnown == null ? fallbackThreadOwner(threadName) : firstKnown;
        if (candidates.isEmpty()) {
            candidates = List.of(new AttributionCandidate(fallback, Confidence.WEAK_HEURISTIC, reason, 100));
        }
        return new ThreadAttribution(fallback, Confidence.WEAK_HEURISTIC, reason, List.copyOf(topFrames), candidates);
    }

    static boolean isLowConfidenceCpuAttribution(CpuSamplingProfiler.DetailSnapshot detail, long rawSamples, long shownSamples, long redistributedSamples) {
        if (detail == null || detail.topFrames() == null || detail.topFrames().isEmpty()) {
            return rawSamples <= 0L && redistributedSamples > 0L && shownSamples > 0L;
        }
        boolean smallRawSampleSet = rawSamples > 0L && rawSamples < 12L;
        boolean redistributedHeavy = redistributedSamples > 0L && redistributedSamples * 10L >= Math.max(1L, shownSamples) * 4L;
        long opaqueFrames = countOpaqueFrames(detail, false);
        long totalFrames = detail.topFrames().values().stream().mapToLong(Long::longValue).sum();
        boolean opaqueFrameHeavy = totalFrames > 0L && opaqueFrames * 10L >= totalFrames * 6L;
        return smallRawSampleSet && redistributedHeavy && opaqueFrameHeavy;
    }

    static boolean isRenderSubmissionHeavy(CpuSamplingProfiler.DetailSnapshot detail) {
        if (detail == null || detail.topFrames() == null || detail.topFrames().isEmpty()) {
            return false;
        }
        boolean renderThreadDominant = detail.topThreads() != null && detail.topThreads().keySet().stream()
                .findFirst()
                .map(name -> name.toLowerCase(Locale.ROOT).contains("render"))
                .orElse(false);
        if (!renderThreadDominant) {
            return false;
        }
        long opaqueFrames = countOpaqueFrames(detail, true);
        long totalFrames = detail.topFrames().values().stream().mapToLong(Long::longValue).sum();
        return totalFrames > 0L && opaqueFrames * 2L >= totalFrames;
    }

    static long countOpaqueFrames(CpuSamplingProfiler.DetailSnapshot detail, boolean renderOnly) {
        if (detail == null || detail.topFrames() == null) {
            return 0L;
        }
        return detail.topFrames().entrySet().stream()
                .filter(entry -> renderOnly ? isOpaqueRenderSubmissionFrame(entry.getKey()) : isOpaqueCpuAttributionFrame(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    static boolean isOpaqueCpuAttributionFrame(String frame) {
        if (frame == null) {
            return false;
        }
        String lower = frame.toLowerCase(Locale.ROOT);
        return isOpaqueRenderSubmissionFrame(frame)
                || lower.startsWith("native#")
                || lower.contains("operatingsystemimpl#")
                || lower.contains("processcpuload")
                || lower.contains("managementfactory")
                || lower.contains("spark")
                || lower.contains("oshi")
                || lower.contains("jna")
                || lower.contains("hwinfo")
                || lower.contains("telemetry")
                || lower.contains("sensor")
                || lower.contains("perf");
    }

    static boolean isOpaqueRenderSubmissionFrame(String frame) {
        if (frame == null) {
            return false;
        }
        String lower = frame.toLowerCase(Locale.ROOT);
        return lower.startsWith("gl")
                || lower.startsWith("jni#")
                || lower.contains("org.lwjgl")
                || lower.contains("framebuffer")
                || lower.contains("blaze3d")
                || lower.contains("fencesync");
    }

    private static boolean isSharedAttributionBucket(String modId) {
        return modId != null && (modId.startsWith("shared/") || modId.startsWith("runtime/"));
    }

    private static double frameWeight(int depth, String className, String mod) {
        double base = switch (depth) {
            case 1 -> 1.0;
            case 2 -> 0.82;
            case 3 -> 0.68;
            case 4 -> 0.56;
            case 5 -> 0.46;
            case 6 -> 0.38;
            default -> Math.max(0.14, 0.32 - ((depth - 7) * 0.02));
        };
        if (isOpaqueRuntimeFrame(className)) {
            base *= 0.35;
        }
        if (isFrameworkMod(mod, className)) {
            base *= 0.65;
        } else if (isSharedAttributionBucket(mod)) {
            base *= 0.5;
        }
        return base;
    }

    private static List<AttributionCandidate> buildCandidates(Map<String, Double> weightsByMod, Map<String, String> reasonsByMod) {
        if (weightsByMod.isEmpty()) {
            return List.of();
        }
        double total = weightsByMod.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0.0) {
            return List.of();
        }
        return weightsByMod.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(entry -> new AttributionCandidate(
                        entry.getKey(),
                        candidateConfidence(entry.getKey(), entry.getValue(), total),
                        reasonsByMod.getOrDefault(entry.getKey(), "unknown-frame"),
                        (int) Math.round(entry.getValue() * 100.0 / total)
                ))
                .collect(Collectors.toList());
    }

    private static Confidence candidateConfidence(String modId, double weight, double total) {
        if (modId == null || total <= 0.0) {
            return Confidence.WEAK_HEURISTIC;
        }
        if (isSharedAttributionBucket(modId)) {
            return Confidence.WEAK_HEURISTIC;
        }
        return weight * 100.0 / total >= 55.0 ? Confidence.INFERRED : Confidence.WEAK_HEURISTIC;
    }

    private static String fallbackThreadOwner(String threadName) {
        String normalized = threadName == null ? "" : threadName.toLowerCase(Locale.ROOT);
        if (normalized.contains("render")) {
            return "shared/render";
        }
        if (normalized.contains("server")) {
            return "minecraft";
        }
        return "shared/jvm";
    }

    private static String resolveModForClassName(String className) {
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

    private static boolean isFrameworkMod(String mod, String className) {
        return "shared/framework".equals(mod)
                || "fabricloader".equals(mod)
                || mod.startsWith("fabric-")
                || mod.startsWith("fabric_api")
                || className.startsWith("net.fabricmc.")
                || className.startsWith("org.spongepowered.asm.");
    }

    private static boolean isOpaqueRuntimeFrame(String className) {
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("org.lwjgl.")
                || className.startsWith("com.mojang.blaze3d.");
    }

    private static String formatFrameReason(StackTraceElement frame) {
        String className = frame.getClassName();
        int lastDot = className.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? className.substring(lastDot + 1) : className;
        return simpleName + "#" + frame.getMethodName();
    }
}
