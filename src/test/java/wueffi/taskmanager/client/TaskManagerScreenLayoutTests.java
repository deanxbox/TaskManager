package wueffi.taskmanager.client;

public final class TaskManagerScreenLayoutTests {

    private TaskManagerScreenLayoutTests() {
    }

    public static void run() {
        hudSettingsRowCountsMatchExpectedClickLayout();
    }

    private static void hudSettingsRowCountsMatchExpectedClickLayout() {
        assertEquals(4, TaskManagerScreen.hudBaseActionCount(), "HUD base action count");
        assertEquals(4, TaskManagerScreen.hudModeActionCount(true), "HUD preset-mode action count");
        assertEquals(20, TaskManagerScreen.hudModeActionCount(false), "HUD custom-mode action count");
        assertEquals(12, TaskManagerScreen.hudRateActionCount(), "HUD rate action count");
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
