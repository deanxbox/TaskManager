package wueffi.taskmanager.client;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.util.Locale;

final class HardwareInfoResolver {

    private static final String UNKNOWN_CPU = "Unknown CPU";
    private static volatile String cachedCpuDisplayName;

    private HardwareInfoResolver() {
    }

    static String getCpuDisplayName() {
        String cached = cachedCpuDisplayName;
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        String resolved = resolveCpuDisplayName();
        cachedCpuDisplayName = resolved;
        return resolved;
    }

    private static String resolveCpuDisplayName() {
        String registryName = readWindowsCpuRegistryName();
        if (!registryName.isBlank()) {
            return registryName;
        }
        String envIdentifier = sanitizeCpuLabel(System.getenv("PROCESSOR_IDENTIFIER"));
        if (!envIdentifier.isBlank()) {
            return envIdentifier;
        }
        String architecture = sanitizeCpuLabel(System.getProperty("os.arch", ""));
        return architecture.isBlank() ? UNKNOWN_CPU : architecture;
    }

    private static String readWindowsCpuRegistryName() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            return "";
        }
        try {
            String value = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                    "ProcessorNameString"
            );
            return sanitizeCpuLabel(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String sanitizeCpuLabel(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\0', ' ').replaceAll("\\s+", " ").trim();
    }
}
