package wueffi.taskmanager.client;

import java.util.List;

public final class RuleEngineTests {

    private RuleEngineTests() {
    }

    public static void run() {
        severityRankOrdersFindingsByImportance();
        heuristicsClassifyEntityAndBlockEntityFamilies();
        jvmAdvisorFallsBackCleanlyForHealthySnapshot();
        jvmAdvisorFlagsDirectMemoryPressure();
    }

    private static void severityRankOrdersFindingsByImportance() {
        assertEquals(3, RuleEngine.severityRank("critical"), "critical rank");
        assertEquals(2, RuleEngine.severityRank("error"), "error rank");
        assertEquals(1, RuleEngine.severityRank("warning"), "warning rank");
        assertEquals(0, RuleEngine.severityRank("info"), "info rank");
    }

    private static void heuristicsClassifyEntityAndBlockEntityFamilies() {
        assertEquals("AI/pathfinding-heavy mob cluster", RuleEngine.classifyEntityHeuristic("minecraft:villager"), "villager heuristic");
        assertEquals("Inventory transfer / item routing", RuleEngine.classifyBlockEntityHeuristic("HopperBlockEntity"), "hopper heuristic");
        assertEquals("none", RuleEngine.classifyEntityHeuristic("minecraft:painting"), "neutral entity heuristic");
    }

    private static void jvmAdvisorFallsBackCleanlyForHealthySnapshot() {
        RuleEngine engine = new RuleEngine();
        List<String> advice = engine.buildJvmTuningAdvisor(MemoryProfiler.Snapshot.empty(), SystemMetricsProfiler.Snapshot.empty());
        assertFalse(advice.isEmpty(), "healthy snapshots should still produce fallback guidance");
        assertContainsOneOf(advice, new String[] {
                "No obvious JVM tuning red flags",
                "No explicit -Xmx flag was detected."
        }, "fallback guidance");
    }

    private static void jvmAdvisorFlagsDirectMemoryPressure() {
        RuleEngine engine = new RuleEngine();
        MemoryProfiler.Snapshot memory = new MemoryProfiler.Snapshot(
                512L,
                1024L,
                1024L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                "none",
                850L,
                100L,
                0L,
                0L,
                0L,
                0L,
                1000L,
                0L,
                0L,
                0L,
                0L
        );
        List<String> advice = engine.buildJvmTuningAdvisor(memory, SystemMetricsProfiler.Snapshot.empty());
        assertContains(advice, "Direct/off-heap buffers are near their cap", "direct memory pressure guidance");
    }

    private static void assertContains(List<String> advice, String needle, String message) {
        for (String line : advice) {
            if (line.contains(needle)) {
                return;
            }
        }
        throw new AssertionError(message + ": did not find '" + needle + "' in " + advice);
    }

    private static void assertContainsOneOf(List<String> advice, String[] needles, String message) {
        for (String needle : needles) {
            for (String line : advice) {
                if (line.contains(needle)) {
                    return;
                }
            }
        }
        throw new AssertionError(message + ": did not find any expected guidance in " + advice);
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
