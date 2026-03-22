package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ConfigManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ConfigManagerMigrationTests {

    private ConfigManagerMigrationTests() {
    }

    public static void run() {
        primitiveHudDefaultsAreStable();
        migrateLegacyFieldsRepairsZeroThresholds();
        frameBudgetTargetDefaultsAndCycles();
    }

    private static void primitiveHudDefaultsAreStable() {
        try {
            Class<?> configDataClass = Class.forName("wueffi.taskmanager.client.util.ConfigManager$ConfigData");
            Object configData = newConfigData(configDataClass);

            assertPrimitiveBoolean(configDataClass, "hudShowLogic");
            assertPrimitiveBoolean(configDataClass, "hudShowBackground");
            assertPrimitiveBoolean(configDataClass, "hudShowFrameBudget");
            assertPrimitiveBoolean(configDataClass, "performanceAlertsEnabled");
            assertPrimitiveBoolean(configDataClass, "performanceAlertChatEnabled");

            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowLogic"), "logic should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowBackground"), "background should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowFrameBudget"), "frame budget should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowVram"), "vram should default on");
            assertEquals(false, getBooleanField(configDataClass, configData, "hudShowNetwork"), "network should default off");
            assertEquals(false, getBooleanField(configDataClass, configData, "hudShowChunkActivity"), "chunk activity should default off");
            assertEquals(false, getBooleanField(configDataClass, configData, "hudShowDiskIo"), "disk I/O should default off");
            assertEquals(false, getBooleanField(configDataClass, configData, "hudShowInputLatency"), "input latency should default off");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowVramRateOfChange"), "vram rate should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowNetworkRateOfChange"), "network rate should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowChunkActivityRateOfChange"), "chunk activity rate should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowDiskIoRateOfChange"), "disk I/O rate should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudShowInputLatencyRateOfChange"), "input latency rate should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudAutoFocusAlertRow"), "auto-focus alert should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "hudBudgetColorMode"), "budget color mode should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "performanceAlertsEnabled"), "performance alerts should default on");
            assertEquals(true, getBooleanField(configDataClass, configData, "performanceAlertChatEnabled"), "performance alert chat should default on");
        } catch (Exception e) {
            throw new AssertionError("config defaults reflection failed", e);
        }
    }

    private static void migrateLegacyFieldsRepairsZeroThresholds() {
        try {
            Class<?> configDataClass = Class.forName("wueffi.taskmanager.client.util.ConfigManager$ConfigData");
            Object configData = newConfigData(configDataClass);

            setField(configDataClass, configData, "performanceAlertFrameThresholdMs", 0);
            setField(configDataClass, configData, "performanceAlertServerThresholdMs", 0);
            setField(configDataClass, configData, "performanceAlertConsecutiveTicks", 0);

            withInjectedConfig(configData, () -> {
                invokeMigrateLegacyFields();
                assertEquals(25, ConfigManager.getPerformanceAlertFrameThresholdMs(), "frame alert threshold default");
                assertEquals(20, ConfigManager.getPerformanceAlertServerThresholdMs(), "server alert threshold default");
                assertEquals(3, ConfigManager.getPerformanceAlertConsecutiveTicks(), "performance alert consecutive ticks default");
            });
        } catch (Exception e) {
            throw new AssertionError("config migration reflection failed", e);
        }
    }

    private static void frameBudgetTargetDefaultsAndCycles() {
        try {
            Class<?> configDataClass = Class.forName("wueffi.taskmanager.client.util.ConfigManager$ConfigData");
            Object configData = newConfigData(configDataClass);
            setField(configDataClass, configData, "frameBudgetTargetFps", 0);

            withInjectedConfig(configData, () -> {
                invokeMigrateLegacyFields();
                assertEquals(60, ConfigManager.getFrameBudgetTargetFps(), "frame budget target fps default");
                ConfigManager.cycleFrameBudgetTargetFps();
                assertEquals(72, ConfigManager.getFrameBudgetTargetFps(), "frame budget target fps cycle");
            });
        } catch (Exception e) {
            throw new AssertionError("frame budget target reflection failed", e);
        }
    }

    private static Object newConfigData(Class<?> configDataClass) throws Exception {
        Constructor<?> constructor = configDataClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void withInjectedConfig(Object configData, ThrowingRunnable body) throws Exception {
        Field configField = ConfigManager.class.getDeclaredField("config");
        configField.setAccessible(true);
        Object previous = configField.get(null);
        configField.set(null, configData);
        try {
            body.run();
        } finally {
            configField.set(null, previous);
        }
    }

    private static void invokeMigrateLegacyFields() throws Exception {
        Method migrateMethod = ConfigManager.class.getDeclaredMethod("migrateLegacyFields");
        migrateMethod.setAccessible(true);
        migrateMethod.invoke(null);
    }

    private static boolean getBooleanField(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void assertPrimitiveBoolean(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        if (field.getType() != boolean.class) {
            throw new AssertionError(fieldName + " should be a primitive boolean but was " + field.getType());
        }
    }

    private static void assertEquals(boolean expected, boolean actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
