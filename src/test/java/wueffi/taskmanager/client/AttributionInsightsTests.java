package wueffi.taskmanager.client;

public final class AttributionInsightsTests {

    private AttributionInsightsTests() {
    }

    public static void run() {
        attributeThreadPreservesAlternateCandidates();
        attributeThreadFallsBackCleanlyWithoutStack();
    }

    private static void attributeThreadPreservesAlternateCandidates() {
        StackTraceElement[] stack = new StackTraceElement[] {
                new StackTraceElement("net.minecraft.server.world.ServerChunkManager", "tick", "ServerChunkManager.java", 0),
                new StackTraceElement("net.fabricmc.fabric.impl.base.event.ArrayBackedEvent", "update", "ArrayBackedEvent.java", 0),
                new StackTraceElement("java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 0)
        };

        AttributionInsights.ThreadAttribution attribution = AttributionInsights.attributeThread("Chunk Task Executor", stack);
        assertEquals("minecraft", attribution.ownerMod(), "primary owner should prefer the first concrete mod frame");
        assertEquals(AttributionInsights.Confidence.INFERRED, attribution.confidence(), "primary owner confidence");
        assertFalse(attribution.candidates().isEmpty(), "candidates should not be empty");
        assertEquals("minecraft", attribution.candidates().getFirst().modId(), "first candidate should preserve the dominant mod");
        assertTrue(attribution.candidates().size() >= 2, "alternate candidates should survive beyond the first concrete frame");
        assertTrue(attribution.candidateLabels().stream().anyMatch(label -> label.contains("minecraft")), "candidate labels should include the dominant mod");
        assertTrue(attribution.candidateLabels().stream().anyMatch(label -> label.contains("shared/framework") || label.contains("shared/jvm")),
                "candidate labels should include alternate non-primary owners from deeper frames");
    }

    private static void attributeThreadFallsBackCleanlyWithoutStack() {
        AttributionInsights.ThreadAttribution attribution = AttributionInsights.attributeThread("Server Thread", null);
        assertEquals("minecraft", attribution.ownerMod(), "server fallback owner");
        assertEquals(AttributionInsights.Confidence.WEAK_HEURISTIC, attribution.confidence(), "fallback confidence should stay conservative");
        assertEquals(1, attribution.candidates().size(), "fallback attribution should still expose one candidate");
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

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(AttributionInsights.Confidence expected, AttributionInsights.Confidence actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
