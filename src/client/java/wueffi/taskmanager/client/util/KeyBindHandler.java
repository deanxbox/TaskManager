package wueffi.taskmanager.client.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import wueffi.taskmanager.client.ProfilerManager;
import wueffi.taskmanager.client.TaskManagerScreen;
import wueffi.taskmanager.client.util.ConfigManager;

public class KeyBindHandler {

    private static KeyBinding openKey;
    private static KeyBinding sessionKey;
    private static KeyBinding hudToggleKey;

    public static void register() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmanager.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F12,
                new KeyBinding.Category(Identifier.of("category.taskmanager"))
        ));
        sessionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmanager.session",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F11,
                new KeyBinding.Category(Identifier.of("category.taskmanager"))
        ));
        hudToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.taskmanager.hud_toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10,
                new KeyBinding.Category(Identifier.of("category.taskmanager"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new TaskManagerScreen());
            }
            while (sessionKey.wasPressed()) {
                ProfilerManager.getInstance().toggleSessionLogging();
            }
            while (hudToggleKey.wasPressed()) {
                ConfigManager.setHudEnabled(!ConfigManager.isHudEnabled());
            }
        });
    }
}
