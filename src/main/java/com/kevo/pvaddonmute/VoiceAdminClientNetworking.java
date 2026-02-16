package com.kevo.pvaddonmute;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class VoiceAdminClientNetworking {

    private static volatile VoiceAdminPayloads.AdminStateSnapshotPayload latestSnapshot;
    private static volatile long latestSnapshotTime;

    private VoiceAdminClientNetworking() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                VoiceAdminPayloads.AdminStateSnapshotPayload.TYPE,
                (payload, context) -> {
                    latestSnapshot = payload;
                    latestSnapshotTime = payload.generatedAtEpochMs();
                }
        );

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    public static void requestSnapshot() {
        if (!ClientPlayNetworking.canSend(VoiceAdminPayloads.AdminStateRequestPayload.TYPE)) {
            return;
        }

        ClientPlayNetworking.send(new VoiceAdminPayloads.AdminStateRequestPayload());
    }

    public static VoiceAdminPayloads.AdminStateSnapshotPayload getLatestSnapshot() {
        return latestSnapshot;
    }

    public static long getLatestSnapshotTime() {
        return latestSnapshotTime;
    }

    public static void clear() {
        latestSnapshot = null;
        latestSnapshotTime = 0L;
    }
}
