package wueffi.taskmanager.client;

public class InputLatencyProfiler {

    private static final InputLatencyProfiler INSTANCE = new InputLatencyProfiler();

    public static InputLatencyProfiler getInstance() {
        return INSTANCE;
    }

    private volatile long lastInputEventNs;
    private volatile double lastPresentedLatencyMs;

    public void recordInputEvent() {
        lastInputEventNs = System.nanoTime();
    }

    public void onFramePresented() {
        long eventNs = lastInputEventNs;
        if (eventNs == 0L) {
            return;
        }
        lastPresentedLatencyMs = Math.max(0.0, (System.nanoTime() - eventNs) / 1_000_000.0);
    }

    public double getLastPresentedLatencyMs() {
        return lastPresentedLatencyMs;
    }

    public void reset() {
        lastInputEventNs = 0L;
        lastPresentedLatencyMs = 0.0;
    }
}
