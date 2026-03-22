package wueffi.taskmanager.client.util;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import wueffi.taskmanager.client.RenderPhaseProfiler;
import wueffi.taskmanager.client.taskmanagerClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class GpuTimer {

    private static boolean supported = false;
    private static boolean checkedSupport = false;

    private static final Map<String, Deque<Long>> pending = new HashMap<>();
    private static final Deque<Integer> freeQueries = new ArrayDeque<>();
    private static final Deque<ActiveQuery> activeStack = new ArrayDeque<>();

    private record ActiveQuery(String phase, int startQueryId, int endQueryId) {}

    public static boolean isSupported() {
        if (!checkedSupport) {
            checkedSupport = true;
            try {
                supported = GL.getCapabilities().GL_ARB_timer_query ||
                            GL.getCapabilities().OpenGL33;
            } catch (Exception e) {
                supported = false;
            }
        }
        return supported;
    }

    public static void begin(String phase) {
        if (!isSupported()) return;
        try {
            int startQueryId = acquireQuery();
            int endQueryId = acquireQuery();
            ARBTimerQuery.glQueryCounter(startQueryId, ARBTimerQuery.GL_TIMESTAMP);
            activeStack.addLast(new ActiveQuery(phase, startQueryId, endQueryId));
        } catch (Exception e) {
            taskmanagerClient.LOGGER.debug("GpuTimer.begin failed: {}", e.getMessage());
        }
    }

    public static void end(String phase) {
        if (!isSupported()) return;
        if (activeStack.isEmpty()) return;
        try {
            ActiveQuery active = null;
            if (phase.equals(activeStack.peekLast().phase())) {
                active = activeStack.removeLast();
            } else {
                Deque<ActiveQuery> skipped = new ArrayDeque<>();
                while (!activeStack.isEmpty()) {
                    ActiveQuery candidate = activeStack.removeLast();
                    if (phase.equals(candidate.phase())) {
                        active = candidate;
                        break;
                    }
                    skipped.addFirst(candidate);
                }
                activeStack.addAll(skipped);
            }
            if (active == null) {
                return;
            }
            ARBTimerQuery.glQueryCounter(active.endQueryId(), ARBTimerQuery.GL_TIMESTAMP);
            pending.computeIfAbsent(active.phase(), k -> new ArrayDeque<>())
                   .addLast(packQueryPair(active.startQueryId(), active.endQueryId()));
        } catch (Exception e) {
            taskmanagerClient.LOGGER.debug("GpuTimer.end failed: {}", e.getMessage());
        }
    }

    public static void collectResults() {
        if (!isSupported()) return;
        RenderPhaseProfiler profiler = RenderPhaseProfiler.getInstance();

        pending.forEach((phase, queue) -> {
            while (!queue.isEmpty()) {
                long packedQueries = queue.peekFirst();
                int startQueryId = unpackStartQuery(packedQueries);
                int endQueryId = unpackEndQuery(packedQueries);
                int availableStart = GL15.glGetQueryObjecti(startQueryId, GL15.GL_QUERY_RESULT_AVAILABLE);
                int availableEnd = GL15.glGetQueryObjecti(endQueryId, GL15.GL_QUERY_RESULT_AVAILABLE);
                if (availableStart == 0 || availableEnd == 0) break;
                queue.pollFirst();
                long startNs = readQuery64(startQueryId);
                long endNs = readQuery64(endQueryId);
                releaseQuery(startQueryId);
                releaseQuery(endQueryId);
                if (startNs >= 0L && endNs >= startNs) {
                    profiler.recordGpuResult(phase, endNs - startNs);
                }
            }
        });
    }

    public static long readQuery64(int queryId) {
        int available = GL15.glGetQueryObjecti(queryId, GL15.GL_QUERY_RESULT_AVAILABLE);
        if (available == 0) return -1;
        return ARBTimerQuery.glGetQueryObjectui64(queryId, GL15.GL_QUERY_RESULT);
    }

    private static int acquireQuery() {
        Integer recycled = freeQueries.pollFirst();
        return recycled != null ? recycled : GL15.glGenQueries();
    }

    private static void releaseQuery(int queryId) {
        if (queryId != 0) {
            freeQueries.addLast(queryId);
        }
    }

    private static long packQueryPair(int startQueryId, int endQueryId) {
        return (((long) startQueryId) << 32) | (endQueryId & 0xffffffffL);
    }

    private static int unpackStartQuery(long packedQueries) {
        return (int) (packedQueries >>> 32);
    }

    private static int unpackEndQuery(long packedQueries) {
        return (int) packedQueries;
    }
}
