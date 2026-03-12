package wueffi.taskmanager.client;

public final class HudOverlayRendererTests {

    private HudOverlayRendererTests() {
    }

    public static void run() {
        displayedMetricRefreshesOnlyAfterInterval();
    }

    private static void displayedMetricRefreshesOnlyAfterInterval() {
        assertTrue(HudOverlayRenderer.shouldRefreshDisplayedMetric(100L, 0L, 50), "first refresh should always run");
        assertFalse(HudOverlayRenderer.shouldRefreshDisplayedMetric(140L, 100L, 50), "display cache should hold before interval elapses");
        assertTrue(HudOverlayRenderer.shouldRefreshDisplayedMetric(150L, 100L, 50), "display cache should refresh at interval boundary");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }
}
