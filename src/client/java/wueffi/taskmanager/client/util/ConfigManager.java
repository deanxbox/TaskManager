package wueffi.taskmanager.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir() .resolve("taskmanager.json");
    private static ConfigData config = new ConfigData();

    public static boolean getOnlyProfileWhenOpen() {
        return config.onlyProfileWhenOpen;
    }

    public static void setOnlyProfileWhenOpen(boolean value) {
        config.onlyProfileWhenOpen = value;
        saveConfig();
    }

    public static void loadConfig() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                config = GSON.fromJson(json, ConfigData.class);

                if (config == null) {
                    config = new ConfigData();
                }

            } catch (IOException e) {
                e.printStackTrace();
                config = new ConfigData();
            }
        } else {
            saveConfig();
        }
    }

    public static void saveConfig() {
        try {
            String json = GSON.toJson(config);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ConfigData {
        public boolean onlyProfileWhenOpen = true;
    }
}