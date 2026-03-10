package wueffi.taskmanager.client;

public class InputLatencyProfiler {

    private static final long MAX_EVENT_AGE_NS = 250_000_000L;
    private static final double MOVE_EPSILON = 0.01;
    private static final InputLatencyProfiler INSTANCE = new InputLatencyProfiler();

    public static InputLatencyProfiler getInstance() {
        return INSTANCE;
    }

    private volatile long lastInputEventNs;
    private volatile double lastPresentedLatencyMs = -1.0;
    private volatile double lastMouseX = Double.NaN;
    private volatile double lastMouseY = Double.NaN;

    public void recordMouseMove(double x, double y) {
        if (Double.isNaN(lastMouseX) || Double.isNaN(lastMouseY)
                || Math.abs(x - lastMouseX) > MOVE_EPSILON
                || Math.abs(y - lastMouseY) > MOVE_EPSILON) {
            lastMouseX = x;
            lastMouseY = y;
            lastInputEventNs = System.nanoTime();
        }
    }

    public void recordInputEvent() {
        lastInputEventNs = System.nanoTime();
    }

    public void onFramePresented() {
        long eventNs = lastInputEventNs;
        if (eventNs == 0L) {
            lastPresentedLatencyMs = -1.0;
            return;
        }
        long ageNs = System.nanoTime() - eventNs;
        if (ageNs > MAX_EVENT_AGE_NS) {
            lastPresentedLatencyMs = -1.0;
            return;
        }
        lastPresentedLatencyMs = Math.max(0.0, ageNs / 1_000_000.0);
    }

    public double getLastPresentedLatencyMs() {
        return lastPresentedLatencyMs;
    }

    public void reset() {
        lastInputEventNs = 0L;
        lastPresentedLatencyMs = -1.0;
        lastMouseX = Double.NaN;
        lastMouseY = Double.NaN;
    }
}
