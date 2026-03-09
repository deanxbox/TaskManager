package wueffi.taskmanager.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Path;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.Map;

public class ModClassIndex {

    private static final Map<String, String> cache = new HashMap<>();
    private static final Map<String, String> normalizedRootToMod = new HashMap<>();
    private static boolean built = false;

    public static void build() {
        if (built) return;
        built = true;

        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String modId = container.getMetadata().getId();
            for (Path root : container.getRootPaths()) {
                try {
                    String normalized = normalizePath(root.toUri().toURL().toString());
                    normalizedRootToMod.put(normalized, modId);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static String getModForClassName(Class<?> clazz) {
        if (!built) build();

        String className = sanitizeClassName(clazz.getName());
        if (className == null || className.isBlank()) {
            return null;
        }
        if (cache.containsKey(className)) return cache.get(className);

        String classSource = null;
        try {
            CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                classSource = normalizePath(cs.getLocation().toString());
            }
        } catch (Exception ignored) {
        }

        if (classSource != null) {
            for (Map.Entry<String, String> entry : normalizedRootToMod.entrySet()) {
                if (classSource.equals(entry.getKey()) || classSource.startsWith(entry.getKey())) {
                    cache.put(className, entry.getValue());
                    return entry.getValue();
                }
            }
        }

        cache.put(className, null);
        return null;
    }

    public static String getModForClassName(String rawClassName) {
        if (!built) build();

        String className = sanitizeClassName(rawClassName);
        if (className == null || className.isBlank()) {
            return null;
        }

        if (cache.containsKey(className)) {
            return cache.get(className);
        }

        try {
            Class<?> clazz = Class.forName(className, false, ModClassIndex.class.getClassLoader());
            return getModForClassName(clazz);
        } catch (Throwable ignored) {
            cache.put(className, null);
            return null;
        }
    }

    private static String sanitizeClassName(String rawClassName) {
        if (rawClassName == null || rawClassName.isBlank()) {
            return rawClassName;
        }

        String className = rawClassName
                .replace('/', '.')
                .replaceAll("\\$\\$Lambda.*", "")
                .replaceAll("\\$\\d+$", "")
                .replaceAll("\\$Subclass\\d+", "")
                .replaceAll("\\$MixinProxy.*", "");

        if (!className.startsWith("[")) {
            return className;
        }

        while (className.startsWith("[")) {
            className = className.substring(1);
        }

        if (className.startsWith("L") && className.endsWith(";")) {
            return className.substring(1, className.length() - 1).replace('/', '.');
        }

        return null;
    }

    private static String normalizePath(String url) {
        return url
                .replace("jar:file:///", "")
                .replace("jar:file://", "")
                .replace("jar:file:/", "")
                .replace("file:///", "")
                .replace("file://", "")
                .replace("file:/", "")
                .replace("!/", "")
                .replace("!", "")
                .toLowerCase();
    }
}
