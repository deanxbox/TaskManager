package wueffi.taskmanager.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SessionExporterTests {

    private SessionExporterTests() {
    }

    public static void run() {
        importSessionBuildsBaselineFromSessionPoints();
    }

    private static void importSessionBuildsBaselineFromSessionPoints() {
        SessionExporter exporter = new SessionExporter();
        try {
            Path file = Files.createTempFile("taskmanager-session-export-test", ".json");
            Files.writeString(file, """
                    {
                      "sessionPoints": [
                        {
                          "averageFps": 100.0,
                          "onePercentLowFps": 70.0,
                          "msptAvg": 12.0,
                          "msptP95": 18.0,
                          "heapUsedBytes": 104857600,
                          "cpuEffectivePercentByMod": {"moda": 10.0},
                          "gpuEffectivePercentByMod": {"moda": 20.0},
                          "memoryEffectiveMbByMod": {"moda": 128.0}
                        },
                        {
                          "averageFps": 80.0,
                          "onePercentLowFps": 60.0,
                          "msptAvg": 16.0,
                          "msptP95": 24.0,
                          "heapUsedBytes": 209715200,
                          "cpuEffectivePercentByMod": {"moda": 14.0, "modb": 8.0},
                          "gpuEffectivePercentByMod": {"moda": 30.0},
                          "memoryEffectiveMbByMod": {"moda": 96.0}
                        }
                      ]
                    }
                    """);

            ProfilerManager.SessionBaseline baseline = exporter.importSession(file);
            if (baseline == null) {
                throw new AssertionError("baseline should not be null");
            }
            assertNear(90.0, baseline.avgFps(), 0.0001, "average fps");
            assertNear(65.0, baseline.onePercentLowFps(), 0.0001, "one percent low");
            assertNear(14.0, baseline.avgMspt(), 0.0001, "average mspt");
            assertNear(21.0, baseline.msptP95(), 0.0001, "p95 mspt");
            assertNear(12.0, baseline.cpuEffectivePercentByMod().get("moda"), 0.0001, "averaged cpu map");
            assertNear(8.0, baseline.cpuEffectivePercentByMod().get("modb"), 0.0001, "single-entry cpu map");
            assertNear(25.0, baseline.gpuEffectivePercentByMod().get("moda"), 0.0001, "averaged gpu map");
            assertNear(112.0, baseline.memoryEffectiveMbByMod().get("moda"), 0.0001, "averaged memory map");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new AssertionError("temp file failure", e);
        }
    }

    private static void assertNear(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
