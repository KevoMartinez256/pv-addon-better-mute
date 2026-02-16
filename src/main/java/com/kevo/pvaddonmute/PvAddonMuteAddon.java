package com.kevo.pvaddonmute;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.mute.MuteManager;

@Addon(
        id = "pv-addon-mute",
        name = "PV Addon Mute",
        scope = AddonLoaderScope.SERVER,
        version = "1.0.0",
        authors = { "Kevoelgamerpro", "Chaparritas Studios" }
)
public final class PvAddonMuteAddon implements AddonInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static PvAddonMuteAddon INSTANCE;

    private PlasmoVoiceServer voiceServer;
    private MuteManager muteManager;

    public static PvAddonMuteAddon getInstance() {
        return INSTANCE;
    }

    // Permite registrar la instancia antes de que Plasmo Voice llame onAddonInitialize
    public static void setEarlyInstance(PvAddonMuteAddon inst) {
        if (INSTANCE == null) {
            INSTANCE = inst;
        }
    }

    public MuteManager getMuteManager() {
        return muteManager;
    }

    public PlasmoVoiceServer getVoiceServer() {
        return voiceServer;
    }
    
    public void setVoiceServer(PlasmoVoiceServer server) {
        this.voiceServer = server;
        if (server != null) {
            this.muteManager = server.getMuteManager();
            LOGGER.info("[pv-addon-mute] MuteManager inicializado via reflection");
            // Asegura que getInstance() devuelve algo aunque onAddonInitialize aún no haya corrido
            if (INSTANCE == null) {
                INSTANCE = this;
            }
            // Propagar al controlador para evitar problemas de classloader
            VoiceMuteController.setMuteManager(this.muteManager);
        }
    }

    @Override
    public void onAddonInitialize() {
        INSTANCE = this;
        LOGGER.info("[pv-addon-mute] Addon inicializado, intentando inyección tardía");
    }
}
