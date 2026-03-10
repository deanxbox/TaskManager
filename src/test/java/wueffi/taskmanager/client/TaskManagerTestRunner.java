package wueffi.taskmanager.client;

public final class TaskManagerTestRunner {

    private TaskManagerTestRunner() {
    }

    public static void main(String[] args) {
        FrameTimelineProfilerTests.run();
        ProfilerManagerTests.run();
        System.out.println("TaskManager tests passed.");
    }
}
