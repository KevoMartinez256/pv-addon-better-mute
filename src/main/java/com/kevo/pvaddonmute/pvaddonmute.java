package com.kevo.pvaddonmute;

import com.kevo.pvaddonmute.commands.MuteCommands;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import su.plo.voice.api.server.PlasmoVoiceServer;

public class pvaddonmute implements ModInitializer {

    public static final String MODID = "pvaddonmute";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String[] SERVER_IMPL_CANDIDATES = new String[] {
            "su.plo.voice.server.ModVoiceServer",
            "su.plo.voice.server.FabricVoiceServer"
    };

    @Override
    public void onInitialize() {
        LOGGER.info("[pv-better-mute] Initializing Fabric mod");
        VoiceAdminNetworking.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                MuteCommands.register(dispatcher)
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                VoiceMuteController.handleJoin(handler.player)
        );

        ServerTickEvents.END_SERVER_TICK.register(VoiceMuteController::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> VoiceMuteController.shutdown());
    }

    private void onServerStarted(MinecraftServer server) {
        VoiceMuteController.initialize(server);

        LOGGER.info("[pv-better-mute] Attempting to load Plasmo Voice addon");
        try {
            PvAddonMuteAddon addon = new PvAddonMuteAddon();
            PvAddonMuteAddon.setEarlyInstance(addon);
            PlasmoVoiceServer.getAddonsLoader().load(addon);
            LOGGER.info("[pv-better-mute] Addon registered, scheduling server instance injection");
            server.execute(() -> injectVoiceServer(server, addon));
        } catch (Throwable t) {
            LOGGER.error("[pv-better-mute] Failed to load Plasmo Voice addon", t);
        }
    }

    private void injectVoiceServer(MinecraftServer minecraftServer, PvAddonMuteAddon addon) {
        for (String className : SERVER_IMPL_CANDIDATES) {
            try {
                Class<?> impl = Class.forName(className);
                for (java.lang.reflect.Field field : impl.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof PlasmoVoiceServer server) {
                        addon.setVoiceServer(server);
                        VoiceMuteController.onVoiceReady(minecraftServer);
                        LOGGER.info("[pv-better-mute] Injected Plasmo Voice server from {}.{}", className, field.getName());
                        return;
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                LOGGER.debug("[pv-better-mute] Unable to inspect {}", className, t);
            }
        }

        LOGGER.error("[pv-better-mute] Could not locate a PlasmoVoiceServer instance. Commands will stay unavailable.");
    }
}
