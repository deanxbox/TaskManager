package wueffi.taskmanager.client;

import java.util.Arrays;

public class FrameTimelineProfiler {

    private static final FrameTimelineProfiler INSTANCE = new FrameTimelineProfiler();
    public static FrameTimelineProfiler getInstance() { return INSTANCE; }

    private static final int SIZE = 300;
    private static final long FPS_WINDOW_NS = 250_000_000L;
    private static final long AVERAGE_FPS_WINDOW_NS = 1_500_000_000L;

    private final long[] frameTimes = new long[SIZE];
    private final long[] frameTimestamps = new long[SIZE];
    private final double[] fpsHistory = new double[SIZE];
    private final long[] selfCostHistory = new long[SIZE];
    private int index = 0;
    private int count = 0;
    private long latestFrameNs = 0;
    private long frameSequence = 0;
    private double currentFps = 0.0;
    private long pendingSelfCostNs = 0L;

    private long frameStart;

    public synchronized void beginFrame() {
        frameStart = System.nanoTime();
    }

    public synchronized void endFrame() {
        long now = System.nanoTime();
        recordFrame(now - frameStart, now);
    }

    synchronized void recordFrame(long durationNs, long timestampNs) {
        frameTimes[index] = durationNs;
        frameTimestamps[index] = timestampNs;
        latestFrameNs = durationNs;
        frameSequence++;
        currentFps = computeRollingFps(timestampNs, index, Math.min(count + 1, SIZE));
        fpsHistory[index] = currentFps > 0.0 ? currentFps : 1_000_000_000.0 / Math.max(1L, durationNs);
        selfCostHistory[index] = pendingSelfCostNs;
        pendingSelfCostNs = 0L;
        index = (index + 1) % SIZE;
        if (count < SIZE) {
            count++;
        }
    }

    synchronized void reset() {
        Arrays.fill(frameTimes, 0L);
        Arrays.fill(frameTimestamps, 0L);
        Arrays.fill(fpsHistory, 0.0);
        Arrays.fill(selfCostHistory, 0L);
        index = 0;
        count = 0;
        latestFrameNs = 0L;
        frameSequence = 0L;
        currentFps = 0.0;
        pendingSelfCostNs = 0L;
        frameStart = 0L;
    }

    public synchronized long[] getFrames() {
        return Arrays.copyOf(frameTimes, frameTimes.length);
    }

    public synchronized double[] getFpsHistory() {
        return Arrays.copyOf(fpsHistory, fpsHistory.length);
    }

    public synchronized int getIndex() {
        return index;
    }

    public synchronized int getCount() {
        return count;
    }

    public synchronized long getLatestFrameNs() {
        return latestFrameNs;
    }

    public synchronized long getFrameSequence() {
        return frameSequence;
    }

    public synchronized double getCurrentFps() {
        if (count == 0) {
            return 0.0;
        }
        int latestIndex = (index - 1 + SIZE) % SIZE;
        double rolling = computeRollingFps(frameTimestamps[latestIndex], latestIndex, count);
        return rolling > 0.0 ? rolling : (currentFps > 0.0 ? currentFps : getAverageFps());
    }

    public synchronized double getAverageFps() {
        if (count == 0) {
            return 0.0;
        }
        int latestIndex = (index - 1 + SIZE) % SIZE;
        long latestTimestamp = frameTimestamps[latestIndex];
        double rollingAverage = computeAverageFpsOverWindow(latestTimestamp, latestIndex, count, AVERAGE_FPS_WINDOW_NS);
        if (rollingAverage > 0.0) {
            return rollingAverage;
        }
        long averageFrameNs = getAverageFrameNs();
        if (averageFrameNs <= 0L) {
            return 0.0;
        }
        return 1_000_000_000.0 / averageFrameNs;
    }

    public synchronized double getOnePercentLowFps() {
        long percentileNs = getPercentileFrameNs(0.99);
        return percentileNs <= 0L ? 0.0 : 1_000_000_000.0 / percentileNs;
    }

    public synchronized double getPointOnePercentLowFps() {
        long percentileNs = getPercentileFrameNs(0.999);
        return percentileNs <= 0L ? 0.0 : 1_000_000_000.0 / percentileNs;
    }

    public synchronized long getAverageFrameNs() {
        if (count == 0) return 0;

        long total = 0;
        for (int i = 0; i < count; i++) {
            total += frameTimes[i];
        }
        return total / count;
    }

    public synchronized double getFrameVarianceMs() {
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

    public synchronized double getFrameStdDevMs() {
        return Math.sqrt(getFrameVarianceMs());
    }

    public synchronized double getStutterScore() {
        double stdDevMs = getFrameStdDevMs();
        return Math.min(100.0, stdDevMs * 8.0);
    }

    public synchronized long getMaxFrameNs() {
        long max = 0;
        for (int i = 0; i < count; i++) {
            if (frameTimes[i] > max) {
                max = frameTimes[i];
            }
        }
        return max;
    }

    public synchronized double getMaxFps() {
        double max = 0.0;
        for (int i = 0; i < count; i++) {
            if (fpsHistory[i] > max) {
                max = fpsHistory[i];
            }
        }
        return max;
    }

    public synchronized long getPercentileFrameNs(double percentile) {
        if (count == 0) return 0;

        long[] copy = new long[count];
        System.arraycopy(frameTimes, 0, copy, 0, count);
        Arrays.sort(copy);

        int idx = Math.min(copy.length - 1, Math.max(0, (int) Math.ceil(percentile * copy.length) - 1));
        return copy[idx];
    }

    public synchronized double[] getOrderedFrameMsHistory() {
        double[] ordered = new double[count];
        for (int i = 0; i < count; i++) {
            int sourceIndex = (index - count + i + SIZE) % SIZE;
            ordered[i] = frameTimes[sourceIndex] / 1_000_000.0;
        }
        return ordered;
    }

    public synchronized long[] getOrderedFrameTimestampHistory() {
        long[] ordered = new long[count];
        for (int i = 0; i < count; i++) {
            int sourceIndex = (index - count + i + SIZE) % SIZE;
            ordered[i] = frameTimestamps[sourceIndex];
        }
        return ordered;
    }

    public synchronized double[] getOrderedFpsHistory() {
        double[] ordered = new double[count];
        for (int i = 0; i < count; i++) {
            int sourceIndex = (index - count + i + SIZE) % SIZE;
            ordered[i] = fpsHistory[sourceIndex];
        }
        return ordered;
    }

    public synchronized void addSelfCost(long durationNs) {
        pendingSelfCostNs += Math.max(0L, durationNs);
    }

    public synchronized double[] getOrderedSelfCostMsHistory() {
        double[] ordered = new double[count];
        for (int i = 0; i < count; i++) {
            int sourceIndex = (index - count + i + SIZE) % SIZE;
            ordered[i] = selfCostHistory[sourceIndex] / 1_000_000.0;
        }
        return ordered;
    }

    public synchronized double getSelfCostAvgMs() {
        if (count == 0) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < count; i++) {
            total += selfCostHistory[i];
        }
        return total / (double) count / 1_000_000.0;
    }

    public synchronized double getSelfCostMaxMs() {
        long max = 0L;
        for (int i = 0; i < count; i++) {
            if (selfCostHistory[i] > max) {
                max = selfCostHistory[i];
            }
        }
        return max / 1_000_000.0;
    }

    public synchronized double getHistorySpanSeconds() {
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

    private double computeAverageFpsOverWindow(long endTimestampNs, int newestIndex, int availableSamples, long windowNs) {
        if (availableSamples <= 0 || endTimestampNs <= 0L) {
            return 0.0;
        }
        long windowStartNs = endTimestampNs - Math.max(windowNs, FPS_WINDOW_NS);
        long totalFrameNs = 0L;
        int framesInWindow = 0;
        for (int i = 0; i < availableSamples; i++) {
            int candidateIndex = (newestIndex - i + SIZE) % SIZE;
            long candidateTimestamp = frameTimestamps[candidateIndex];
            long candidateFrameNs = frameTimes[candidateIndex];
            if (candidateTimestamp <= 0L || candidateFrameNs <= 0L) {
                break;
            }
            if (candidateTimestamp < windowStartNs) {
                break;
            }
            totalFrameNs += candidateFrameNs;
            framesInWindow++;
        }
        if (framesInWindow <= 0 || totalFrameNs <= 0L) {
            return 0.0;
        }
        return framesInWindow * 1_000_000_000.0 / totalFrameNs;
    }

    private double computeRollingFps(long endTimestampNs, int newestIndex, int availableSamples) {
        if (availableSamples <= 1 || endTimestampNs <= 0L) {
            long newestFrame = frameTimes[newestIndex];
            return newestFrame > 0L ? 1_000_000_000.0 / newestFrame : 0.0;
        }

        long windowStartNs = endTimestampNs - FPS_WINDOW_NS;
        int framesInWindow = 1;
        int oldestIndexInWindow = newestIndex;

        for (int i = 1; i < availableSamples; i++) {
            int candidateIndex = (newestIndex - i + SIZE) % SIZE;
            long candidateTimestamp = frameTimestamps[candidateIndex];
            if (candidateTimestamp <= 0L || candidateTimestamp < windowStartNs) {
                break;
            }
            oldestIndexInWindow = candidateIndex;
            framesInWindow++;
        }

        long oldestTimestamp = frameTimestamps[oldestIndexInWindow];
        if (oldestTimestamp <= 0L || endTimestampNs <= oldestTimestamp) {
            long newestFrame = frameTimes[newestIndex];
            return newestFrame > 0L ? 1_000_000_000.0 / newestFrame : 0.0;
        }

        return framesInWindow * 1_000_000_000.0 / Math.max(1L, endTimestampNs - oldestTimestamp);
    }

    public synchronized java.util.Map<String, Double> getFrameTimeHistogram() {
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
