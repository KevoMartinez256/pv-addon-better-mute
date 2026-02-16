package com.kevo.pvaddonmute;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VoiceAdminNetworking {

    private VoiceAdminNetworking() {}

    public static void register() {
        PayloadTypeRegistry.playC2S().register(
                VoiceAdminPayloads.AdminStateRequestPayload.TYPE,
                VoiceAdminPayloads.AdminStateRequestPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                VoiceAdminPayloads.AdminStateSnapshotPayload.TYPE,
                VoiceAdminPayloads.AdminStateSnapshotPayload.STREAM_CODEC
        );

        ServerPlayNetworking.registerGlobalReceiver(
                VoiceAdminPayloads.AdminStateRequestPayload.TYPE,
                (payload, context) -> {
                    if (!context.player().hasPermissions(2)) {
                        return;
                    }

                    ServerPlayNetworking.send(context.player(), buildSnapshot(context.server()));
                }
        );
    }

    private static VoiceAdminPayloads.AdminStateSnapshotPayload buildSnapshot(MinecraftServer server) {
        long now = System.currentTimeMillis();
        boolean lockdownEnabled = VoiceMuteController.isLockdownEnabled();
        long lockdownRemainingMs = VoiceMuteController.getLockdownRemainingMs();
        Set<UUID> lockdownMuted = new HashSet<>(VoiceMuteController.getLockdownMutedPlayers());
        Set<UUID> allowed = new HashSet<>(VoiceMuteController.getAllowedPlayers());
        Map<UUID, VoiceMuteController.ManualMuteSnapshot> manualMutes = new HashMap<>();

        for (VoiceMuteController.ManualMuteSnapshot snapshot : VoiceMuteController.getManualMuteSnapshots()) {
            manualMutes.put(snapshot.getPlayerId(), snapshot);
        }

        List<VoiceAdminPayloads.PlayerVoiceState> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            VoiceMuteController.ManualMuteSnapshot manual = manualMutes.get(playerId);
            boolean op = player.hasPermissions(2);

            players.add(new VoiceAdminPayloads.PlayerVoiceState(
                    playerId,
                    player.getGameProfile().getName(),
                    op,
                    VoiceMuteController.isVoiceMuted(playerId),
                    manual != null,
                    lockdownMuted.contains(playerId),
                    op || allowed.contains(playerId),
                    manual != null ? manual.getRemainingMs(now) : 0L
            ));
        }

        players.sort(Comparator.comparing(VoiceAdminPayloads.PlayerVoiceState::playerName, String::compareToIgnoreCase));
        return new VoiceAdminPayloads.AdminStateSnapshotPayload(lockdownEnabled, lockdownRemainingMs, now, players);
    }
}
