package wueffi.taskmanager.client;

public final class FrameTimelineProfilerTests {

    private FrameTimelineProfilerTests() {
    }

    public static void run() {
        rollingFpsUsesRecentWindow();
        averageAndLowFpsTrackRecordedFrames();
        orderedFrameTimestampsStayInInsertionOrder();
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
