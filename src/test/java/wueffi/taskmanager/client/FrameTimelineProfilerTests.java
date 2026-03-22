package wueffi.taskmanager.client;

public final class FrameTimelineProfilerTests {

    private FrameTimelineProfilerTests() {
    }

    public static void run() {
        rollingFpsUsesRecentWindow();
        averageAndLowFpsTrackRecordedFrames();
        orderedFrameTimestampsStayInInsertionOrder();
        selfCostHistoryTracksLastFrame();
        returnedArraysAreDefensiveCopies();
    }

    private static void rollingFpsUsesRecentWindow() {
        FrameTimelineProfiler profiler = new FrameTimelineProfiler();
        profiler.reset();
        long[] timestamps = {100_000_000L, 200_000_000L, 300_000_000L, 400_000_000L, 500_000_000L};
        for (long timestamp : timestamps) {
            profiler.recordFrame(100_000_000L, timestamp);
        }
        assertNear(15.0, profiler.getCurrentFps(), 0.0001, "current FPS should use only the last 250ms window");
    }

    private static void averageAndLowFpsTrackRecordedFrames() {
        FrameTimelineProfiler profiler = new FrameTimelineProfiler();
        profiler.reset();
        profiler.recordFrame(16_000_000L, 16_000_000L);
        profiler.recordFrame(20_000_000L, 36_000_000L);
        profiler.recordFrame(25_000_000L, 61_000_000L);

        assertNear(49.1803278688, profiler.getAverageFps(), 0.0001, "average FPS should come from average frame time");
        assertNear(40.0, profiler.getOnePercentLowFps(), 0.0001, "1% low should reflect slowest recorded frame in a tiny sample");
        assertNear(40.0, profiler.getPointOnePercentLowFps(), 0.0001, "0.1% low should reflect slowest recorded frame in a tiny sample");
    }

    private static void orderedFrameTimestampsStayInInsertionOrder() {
        FrameTimelineProfiler profiler = new FrameTimelineProfiler();
        profiler.reset();
        profiler.recordFrame(10_000_000L, 10_000_000L);
        profiler.recordFrame(11_000_000L, 21_000_000L);
        profiler.recordFrame(12_000_000L, 33_000_000L);

        long[] timestamps = profiler.getOrderedFrameTimestampHistory();
        assertEquals(3, timestamps.length, "timestamp history length");
        assertEquals(10_000_000L, timestamps[0], "first timestamp");
        assertEquals(21_000_000L, timestamps[1], "second timestamp");
        assertEquals(33_000_000L, timestamps[2], "third timestamp");
    }

    private static void selfCostHistoryTracksLastFrame() {
        FrameTimelineProfiler profiler = new FrameTimelineProfiler();
        profiler.reset();
        profiler.addSelfCost(250_000L);
        profiler.recordFrame(10_000_000L, 10_000_000L);
        profiler.addSelfCost(750_000L);
        profiler.recordFrame(11_000_000L, 21_000_000L);

        double[] selfCostHistory = profiler.getOrderedSelfCostMsHistory();
        assertEquals(2, selfCostHistory.length, "self-cost history length");
        assertNear(0.25, selfCostHistory[0], 0.0001, "first self-cost value");
        assertNear(0.75, selfCostHistory[1], 0.0001, "second self-cost value");
        assertNear(0.5, profiler.getSelfCostAvgMs(), 0.0001, "average self-cost");
        assertNear(0.75, profiler.getSelfCostMaxMs(), 0.0001, "max self-cost");
    }

    private static void returnedArraysAreDefensiveCopies() {
        FrameTimelineProfiler profiler = new FrameTimelineProfiler();
        profiler.reset();
        profiler.recordFrame(10_000_000L, 10_000_000L);
        profiler.recordFrame(11_000_000L, 21_000_000L);

        long[] frames = profiler.getFrames();
        double[] fpsHistory = profiler.getFpsHistory();
        frames[0] = 999L;
        fpsHistory[0] = 999L;

        assertEquals(10_000_000L, profiler.getFrames()[0], "frame history should be copied");
        assertNear(100.0, profiler.getFpsHistory()[0], 0.0001, "fps history should be copied");
    }

    private static void assertNear(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
