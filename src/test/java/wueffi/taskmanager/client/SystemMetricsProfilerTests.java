package wueffi.taskmanager.client;

public final class SystemMetricsProfilerTests {

    private SystemMetricsProfilerTests() {
    }

    public static void run() {
        classifyThreadRoleDetectsGeneralExecutorPools();
        classifyThreadRoleDetectsStorageIoWithoutWorkerMainPrefix();
        classifyThreadRoleDetectsChunkMeshingByStackAncestry();
    }

    private static void classifyThreadRoleDetectsGeneralExecutorPools() {
        String role = SystemMetricsProfiler.classifyThreadRole(
                "ForkJoinPool.commonPool-worker-3",
                new StackTraceElement[] {
                        new StackTraceElement("java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 0),
                        new StackTraceElement("java.util.concurrent.CompletableFuture", "asyncSupplyStage", "CompletableFuture.java", 0)
                }
        );
        assertEquals("Worker Pool", role, "executor-based workers should not depend on Worker-Main naming");
    }

    private static void classifyThreadRoleDetectsStorageIoWithoutWorkerMainPrefix() {
        String role = SystemMetricsProfiler.classifyThreadRole(
                "Storage-IO-4",
                new StackTraceElement[] {
                        new StackTraceElement("net.minecraft.world.storage.RegionBasedStorage", "sync", "RegionBasedStorage.java", 0),
                        new StackTraceElement("java.nio.channels.FileChannel", "write", "FileChannel.java", 0)
                }
        );
        assertEquals("IO Pool", role, "storage threads should classify as IO pools");
    }

    private static void classifyThreadRoleDetectsChunkMeshingByStackAncestry() {
        String role = SystemMetricsProfiler.classifyThreadRole(
                "terrain-builder-1",
                new StackTraceElement[] {
                        new StackTraceElement("me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuilderMeshingTask", "run", "ChunkBuilderMeshingTask.java", 0),
                        new StackTraceElement("java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 0)
                }
        );
        assertEquals("Chunk Meshing Worker", role, "meshing stacks should classify without hardcoded thread names");
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
