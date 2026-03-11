package wueffi.taskmanager.client;

import wueffi.taskmanager.client.util.ConfigManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ConfigManagerMigrationTests {

    private ConfigManagerMigrationTests() {
    }

    public static void run() {
        migrateLegacyFieldsBackfillsNewHudDefaults();
    }

    private static void migrateLegacyFieldsBackfillsNewHudDefaults() {
        try {
            Class<?> configDataClass = Class.forName("wueffi.taskmanager.client.util.ConfigManager$ConfigData");
            Constructor<?> constructor = configDataClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object configData = constructor.newInstance();

            setField(configDataClass, configData, "hudShowLogic", null);
            setField(configDataClass, configData, "hudShowBackground", null);
            setField(configDataClass, configData, "hudShowFrameBudget", null);
            setField(configDataClass, configData, "hudShowVram", null);
            setField(configDataClass, configData, "hudShowNetwork", null);
            setField(configDataClass, configData, "hudShowChunkActivity", null);
            setField(configDataClass, configData, "hudShowDiskIo", null);
            setField(configDataClass, configData, "hudShowInputLatency", null);
            setField(configDataClass, configData, "hudShowVramRateOfChange", null);
            setField(configDataClass, configData, "hudShowNetworkRateOfChange", null);
            setField(configDataClass, configData, "hudShowChunkActivityRateOfChange", null);
            setField(configDataClass, configData, "hudShowDiskIoRateOfChange", null);
            setField(configDataClass, configData, "hudShowInputLatencyRateOfChange", null);
            setField(configDataClass, configData, "hudAutoFocusAlertRow", null);
            setField(configDataClass, configData, "hudBudgetColorMode", null);

            Field configField = ConfigManager.class.getDeclaredField("config");
            configField.setAccessible(true);
            Object previous = configField.get(null);
            configField.set(null, configData);
            try {
                Method migrateMethod = ConfigManager.class.getDeclaredMethod("migrateLegacyFields");
                migrateMethod.setAccessible(true);
                migrateMethod.invoke(null);

                assertTrue(ConfigManager.isHudShowLogic(), "logic should default on");
                assertTrue(ConfigManager.isHudShowBackground(), "background should default on");
                assertTrue(ConfigManager.isHudShowFrameBudget(), "frame budget should default on");
                assertTrue(ConfigManager.isHudShowVram(), "vram should default on");
                assertFalse(ConfigManager.isHudShowNetwork(), "network should default off");
                assertFalse(ConfigManager.isHudShowChunkActivity(), "chunk activity should default off");
                assertFalse(ConfigManager.isHudShowDiskIo(), "disk I/O should default off");
                assertFalse(ConfigManager.isHudShowInputLatency(), "input latency should default off");
                assertTrue(ConfigManager.isHudShowVramRateOfChange(), "vram rate should default on");
                assertTrue(ConfigManager.isHudShowNetworkRateOfChange(), "network rate should default on");
                assertTrue(ConfigManager.isHudShowChunkActivityRateOfChange(), "chunk activity rate should default on");
                assertTrue(ConfigManager.isHudShowDiskIoRateOfChange(), "disk I/O rate should default on");
                assertTrue(ConfigManager.isHudShowInputLatencyRateOfChange(), "input latency rate should default on");
                assertTrue(ConfigManager.isHudAutoFocusAlertRow(), "auto-focus alert should default on");
                assertTrue(ConfigManager.isHudBudgetColorMode(), "budget color mode should default on");
            } finally {
                configField.set(null, previous);
            }
        } catch (Exception e) {
            throw new AssertionError("config migration reflection failed", e);
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }
}
