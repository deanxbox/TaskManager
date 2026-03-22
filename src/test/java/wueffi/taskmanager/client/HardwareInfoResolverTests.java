package wueffi.taskmanager.client;

public final class HardwareInfoResolverTests {

    private HardwareInfoResolverTests() {
    }

    public static void run() {
        sanitizeCpuLabelCollapsesWhitespace();
    }

    private static void sanitizeCpuLabelCollapsesWhitespace() {
        String value = HardwareInfoResolver.sanitizeCpuLabel(" AMD Ryzen 7 7800X3D \0   8-Core Processor ");
        if (!"AMD Ryzen 7 7800X3D 8-Core Processor".equals(value)) {
            throw new AssertionError("CPU label sanitization should collapse whitespace and nulls: " + value);
        }
    }
}
