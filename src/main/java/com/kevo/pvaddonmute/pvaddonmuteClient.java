package com.kevo.pvaddonmute;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class pvaddonmuteClient implements ClientModInitializer {

    private static KeyMapping openPanelKey;

    @Override
    public void onInitializeClient() {
        VoiceAdminClientNetworking.register();

        openPanelKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.pvaddonmute.open_panel",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.pvaddonmute"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPanelKey.consumeClick()) {
                if (client.player == null) {
                    continue;
                }
                if (!client.player.hasPermissions(2)) {
                    client.player.displayClientMessage(Component.translatable("message.pvaddonmute.error.open_panel_permission"), true);
                    continue;
                }
                client.setScreen(new VoiceAdminScreen());
            }
        });
    }
}
