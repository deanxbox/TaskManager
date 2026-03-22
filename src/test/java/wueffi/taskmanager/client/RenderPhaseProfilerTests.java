package wueffi.taskmanager.client;

public final class RenderPhaseProfilerTests {

    private RenderPhaseProfilerTests() {
    }

    public static void run() {
        nestedCpuScopesTrackEachPhaseSeparately();
        renderContextOwnerBecomesLikelyOwnerHint();
    }

    private static void nestedCpuScopesTrackEachPhaseSeparately() {
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();
        profiler.reset();

        profiler.beginCpuPhase("outer");
        profiler.beginCpuPhase("inner");
        profiler.endCpuPhase("inner");
        profiler.endCpuPhase("outer");

        RenderPhaseProfiler.PhaseSnapshot outer = profiler.getSnapshot().get("outer");
        RenderPhaseProfiler.PhaseSnapshot inner = profiler.getSnapshot().get("inner");

        assertNotNull(outer, "outer phase snapshot");
        assertNotNull(inner, "inner phase snapshot");
        assertEquals(1L, outer.cpuCalls(), "outer call count");
        assertEquals(1L, inner.cpuCalls(), "inner call count");
        assertTrue(outer.cpuNanos() > 0L, "outer cpu nanos");
        assertTrue(inner.cpuNanos() > 0L, "inner cpu nanos");
    }

    private static void renderContextOwnerBecomesLikelyOwnerHint() {
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();
        profiler.reset();

        profiler.pushContextOwner("chunky");
        profiler.beginCpuPhase("worldRenderer.renderEntities", "shared/render");
        profiler.endCpuPhase("worldRenderer.renderEntities");
        profiler.popContextOwner();

        RenderPhaseProfiler.PhaseSnapshot snapshot = profiler.getSnapshot().get("worldRenderer.renderEntities");
        assertNotNull(snapshot, "render phase snapshot");
        assertEquals("shared/render", snapshot.ownerMod(), "shared phase owner should stay honest");
        assertEquals(1L, snapshot.likelyOwners().getOrDefault("chunky", 0L), "context owner should be captured as a likely owner hint");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }
}
