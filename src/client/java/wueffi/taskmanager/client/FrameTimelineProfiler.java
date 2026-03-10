package wueffi.taskmanager.client;

import java.util.Arrays;

public class FrameTimelineProfiler {

    private static final FrameTimelineProfiler INSTANCE = new FrameTimelineProfiler();
    public static FrameTimelineProfiler getInstance() { return INSTANCE; }

    private static final int SIZE = 300;
    private static final long FPS_WINDOW_NS = 50_000_000L;

    private final long[] frameTimes = new long[SIZE];
    private final long[] frameTimestamps = new long[SIZE];
    private final double[] fpsHistory = new double[SIZE];
    private int index = 0;
    private int count = 0;
    private long latestFrameNs = 0;
    private long frameSequence = 0;
    private long fpsWindowStartNs = 0;
    private int fpsWindowFrames = 0;
    private double currentFps = 0.0;

    private long frameStart;

    public void beginFrame() {
        frameStart = System.nanoTime();
    }

    public void endFrame() {
        long now = System.nanoTime();
        long duration = now - frameStart;

        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = now;
        }
        fpsWindowFrames++;
        long elapsed = now - fpsWindowStartNs;
        if (elapsed >= FPS_WINDOW_NS) {
            double measuredFps = fpsWindowFrames * 1_000_000_000.0 / elapsed;
            currentFps = currentFps == 0.0 ? measuredFps : (currentFps * 0.35) + (measuredFps * 0.65);
            fpsWindowFrames = 0;
            fpsWindowStartNs = now;
        }

        frameTimes[index] = duration;
        frameTimestamps[index] = now;
        fpsHistory[index] = currentFps > 0.0 ? currentFps : 1_000_000_000.0 / Math.max(1L, duration);
        latestFrameNs = duration;
        frameSequence++;
        index = (index + 1) % SIZE;
        if (count < SIZE) {
            count++;
        }
    }

    public long[] getFrames() {
        return frameTimes;
    }

    public double[] getFpsHistory() {
        return fpsHistory;
    }

    public int getIndex() {
        return index;
    }

    public int getCount() {
        return count;
    }

    public long getLatestFrameNs() {
        return latestFrameNs;
    }

    public long getFrameSequence() {
        return frameSequence;
    }

    public double getCurrentFps() {
        return currentFps > 0.0 ? currentFps : getAverageFps();
    }

    public double getAverageFps() {
        long averageFrameNs = getAverageFrameNs();
        if (averageFrameNs <= 0L) {
            return 0.0;
        }
        return 1_000_000_000.0 / averageFrameNs;
    }

    public double getOnePercentLowFps() {
        long percentileNs = getPercentileFrameNs(0.99);
        return percentileNs <= 0L ? 0.0 : 1_000_000_000.0 / percentileNs;
    }

    public double getPointOnePercentLowFps() {
        long percentileNs = getPercentileFrameNs(0.999);
        return percentileNs <= 0L ? 0.0 : 1_000_000_000.0 / percentileNs;
    }

    public long getAverageFrameNs() {
        if (count == 0) return 0;

        long total = 0;
        for (int i = 0; i < count; i++) {
            total += frameTimes[i];
        }
        return total / count;
    }

    public double getFrameVarianceMs() {
        if (count == 0) return 0;

        double meanMs = getAverageFrameNs() / 1_000_000.0;
        double variance = 0;
        for (int i = 0; i < count; i++) {
            double frameMs = frameTimes[i] / 1_000_000.0;
            double delta = frameMs - meanMs;
            variance += delta * delta;
        }
        return variance / count;
    }

    public double getFrameStdDevMs() {
        return Math.sqrt(getFrameVarianceMs());
    }

    public double getStutterScore() {
        double stdDevMs = getFrameStdDevMs();
        return Math.min(100.0, stdDevMs * 8.0);
    }

    public long getMaxFrameNs() {
        long max = 0;
        for (int i = 0; i < count; i++) {
            if (frameTimes[i] > max) {
                max = frameTimes[i];
            }
        }
        return max;
    }

    public double getMaxFps() {
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            if (fpsHistory[i] > max) {
                max = fpsHistory[i];
            }
        }
        return max;
    }

    public long getPercentileFrameNs(double percentile) {
        if (count == 0) return 0;

        long[] copy = new long[count];
        System.arraycopy(frameTimes, 0, copy, 0, count);
        Arrays.sort(copy);

        int idx = Math.min(copy.length - 1, Math.max(0, (int) Math.ceil(percentile * copy.length) - 1));
        return copy[idx];
    }

    public double[] getOrderedFrameMsHistory() {
        double[] ordered = new double[count];
        for (int i = 0; i < count; i++) {
            int sourceIndex = (index - count + i + SIZE) % SIZE;
            ordered[i] = frameTimes[sourceIndex] / 1_000_000.0;
        }
        return ordered;
    }

    public double[] getOrderedFpsHistory() {
        double[] ordered = new double[count];
        for (int i = 0; i < count; i++) {
            int sourceIndex = (index - count + i + SIZE) % SIZE;
            ordered[i] = fpsHistory[sourceIndex];
        }
        return ordered;
    }

    public double getHistorySpanSeconds() {
        if (count < 2) {
            return 0.0;
        }
        int firstIndex = (index - count + SIZE) % SIZE;
        int lastIndex = (index - 1 + SIZE) % SIZE;
        long first = frameTimestamps[firstIndex];
        long last = frameTimestamps[lastIndex];
        if (first <= 0L || last <= first) {
            return 0.0;
        }
        return (last - first) / 1_000_000_000.0;
    }

    public java.util.Map<String, Double> getFrameTimeHistogram() {
        if (count == 0) {
            return java.util.Map.of("<8ms", 0.0, "8-16ms", 0.0, "16-32ms", 0.0, ">32ms", 0.0);
        }
        int under8 = 0;
        int under16 = 0;
        int under32 = 0;
        int over32 = 0;
        for (int i = 0; i < count; i++) {
            double ms = frameTimes[i] / 1_000_000.0;
            if (ms < 8.0) under8++;
            else if (ms < 16.0) under16++;
            else if (ms < 32.0) under32++;
            else over32++;
        }
        java.util.Map<String, Double> buckets = new java.util.LinkedHashMap<>();
        buckets.put("<8ms", under8 * 100.0 / count);
        buckets.put("8-16ms", under16 * 100.0 / count);
        buckets.put("16-32ms", under32 * 100.0 / count);
        buckets.put(">32ms", over32 * 100.0 / count);
        return buckets;
    }
}
