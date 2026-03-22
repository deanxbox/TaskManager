package wueffi.taskmanager.client;

import java.util.Locale;

final class TelemetryTextFormatter {

    private TelemetryTextFormatter() {
    }

    static String formatTemperature(double value) {
        if (value < 0.0 || !Double.isFinite(value)) {
            return "N/A";
        }
        return String.format(Locale.ROOT, "%.1f C", value);
    }

    static String formatGpuTemperatureSummary(SystemMetricsProfiler.Snapshot system) {
        return "Core " + formatTemperature(system.gpuTemperatureC()) + " [" + blankToUnavailable(system.gpuTemperatureProvider())
                + "] | Hot Spot " + formatTemperature(system.gpuHotSpotTemperatureC()) + " [" + blankToUnavailable(system.gpuHotSpotProvider()) + "]";
    }

    static String formatGpuTemperatureCompact(SystemMetricsProfiler.Snapshot system) {
        String core = system.gpuTemperatureC() >= 0.0 ? String.format(Locale.ROOT, "%.0fC", system.gpuTemperatureC()) : "N/A";
        String hotSpot = system.gpuHotSpotTemperatureC() >= 0.0 ? String.format(Locale.ROOT, "%.0fC", system.gpuHotSpotTemperatureC()) : "N/A";
        return "Core " + core + " | Hot Spot " + hotSpot;
    }

    static String formatGpuTemperatureWithTrend(SystemMetricsProfiler.Snapshot system) {
        String base = "Core " + formatTemperature(system.gpuTemperatureC()) + " [" + blankToUnavailable(system.gpuTemperatureProvider()) + "]";
        if (system.gpuTemperatureC() >= 0.0 && Double.isFinite(system.gpuTemperatureChangePerSecond()) && Math.abs(system.gpuTemperatureChangePerSecond()) >= 0.05) {
            base += " (" + formatSignedRate(system.gpuTemperatureChangePerSecond(), "C/s") + ")";
        }
        return base;
    }

    static String formatSignedRate(double value, String units) {
        if (!Double.isFinite(value) || Math.abs(value) < 0.05) {
            return "~0 " + units;
        }
        return String.format(Locale.ROOT, "%+.1f %s", value, units);
    }

    private static String blankToUnavailable(String value) {
        return value == null || value.isBlank() ? "Unavailable" : value;
    }
}
