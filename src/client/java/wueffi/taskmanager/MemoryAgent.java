package wueffi.taskmanager;

import java.lang.instrument.Instrumentation;

public class MemoryAgent {

    private static volatile Instrumentation instrumentation;

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}