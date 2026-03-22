package wueffi.taskmanager.client;

import java.lang.reflect.Method;

public final class WindowsTelemetryBridgeTests {

    private WindowsTelemetryBridgeTests() {
    }

    public static void run() {
        mergeWithPreviousKeepsLastValidTemperatures();
    }

    private static void mergeWithPreviousKeepsLastValidTemperatures() {
        try {
            WindowsTelemetryBridge bridge = new WindowsTelemetryBridge();
            Method merge = WindowsTelemetryBridge.class.getDeclaredMethod("mergeWithPrevious", WindowsTelemetryBridge.Sample.class, WindowsTelemetryBridge.Sample.class);
            merge.setAccessible(true);
            WindowsTelemetryBridge.Sample previous = new WindowsTelemetryBridge.Sample(
                    1000L, true, "Windows Performance Counters", "CPU: LibreHardwareMonitor DLL [Package] | GPU: LibreHardwareMonitor DLL [Core] | GPU Hot Spot: HWiNFO Shared Memory [Hot Spot]", "none",
                    "LibreHardwareMonitor DLL", "LibreHardwareMonitor DLL", "HWiNFO Shared Memory",
                    20.0, 60.0, 71.5, 84.0, 65.0, 100L, 200L, 300L, 400L, 25L
            );
            WindowsTelemetryBridge.Sample current = new WindowsTelemetryBridge.Sample(
                    2000L, true, "Windows Performance Counters", "Unavailable", "none",
                    "Unavailable", "Unavailable", "Unavailable",
                    25.0, 62.0, -1.0, -1.0, -1.0, 120L, 220L, 320L, 420L, 20L
            );

            WindowsTelemetryBridge.Sample merged = (WindowsTelemetryBridge.Sample) merge.invoke(bridge, current, previous);
            assertEquals(71.5, merged.gpuTemperatureC(), "GPU temperature should survive transient sensor gaps");
            assertEquals(84.0, merged.gpuHotSpotTemperatureC(), "GPU hot spot temperature should survive transient sensor gaps");
            assertEquals(65.0, merged.cpuTemperatureC(), "CPU temperature should survive transient sensor gaps");
            assertEquals("HWiNFO Shared Memory", merged.gpuHotSpotTemperatureProvider(), "GPU hot spot provider should survive transient sensor gaps");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("reflection failure", e);
        }
    }

    private static void assertEquals(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > 0.0001) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
