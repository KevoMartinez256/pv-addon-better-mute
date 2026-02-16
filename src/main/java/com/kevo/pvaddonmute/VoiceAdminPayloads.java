package com.kevo.pvaddonmute;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

public final class VoiceAdminPayloads {

    private VoiceAdminPayloads() {}

    public record AdminStateRequestPayload() implements CustomPacketPayload {
        public static final Type<AdminStateRequestPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(pvaddonmute.MODID, "admin_state_request"));
        public static final StreamCodec<FriendlyByteBuf, AdminStateRequestPayload> STREAM_CODEC =
                CustomPacketPayload.codec(AdminStateRequestPayload::write, AdminStateRequestPayload::new);

        private AdminStateRequestPayload(FriendlyByteBuf buffer) {
            this();
        }

        private void write(FriendlyByteBuf buffer) {
        }

        @Override
        public Type<AdminStateRequestPayload> type() {
            return TYPE;
        }
    }

    public record PlayerVoiceState(
            UUID playerId,
            String playerName,
            boolean operator,
            boolean voiceMuted,
            boolean manualMuted,
            boolean lockdownMuted,
            boolean allowedDuringLockdown,
            long manualMuteRemainingMs
    ) {
        private PlayerVoiceState(FriendlyByteBuf buffer) {
            this(
                    buffer.readUUID(),
                    buffer.readUtf(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readBoolean(),
                    buffer.readVarLong()
            );
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(playerId);
            buffer.writeUtf(playerName);
            buffer.writeBoolean(operator);
            buffer.writeBoolean(voiceMuted);
            buffer.writeBoolean(manualMuted);
            buffer.writeBoolean(lockdownMuted);
            buffer.writeBoolean(allowedDuringLockdown);
            buffer.writeVarLong(Math.max(0L, manualMuteRemainingMs));
        }
    }

    public record AdminStateSnapshotPayload(
            boolean lockdownEnabled,
            long lockdownRemainingMs,
            long generatedAtEpochMs,
            List<PlayerVoiceState> players
    ) implements CustomPacketPayload {
        public static final Type<AdminStateSnapshotPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(pvaddonmute.MODID, "admin_state_snapshot"));
        public static final StreamCodec<FriendlyByteBuf, AdminStateSnapshotPayload> STREAM_CODEC =
                CustomPacketPayload.codec(AdminStateSnapshotPayload::write, AdminStateSnapshotPayload::new);

        public AdminStateSnapshotPayload {
            players = List.copyOf(players);
        }

        private AdminStateSnapshotPayload(FriendlyByteBuf buffer) {
            this(
                    buffer.readBoolean(),
                    buffer.readVarLong(),
                    buffer.readLong(),
                    buffer.readList(PlayerVoiceState::new)
            );
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeBoolean(lockdownEnabled);
            buffer.writeVarLong(Math.max(0L, lockdownRemainingMs));
            buffer.writeLong(generatedAtEpochMs);
            buffer.writeCollection(players, (buf, state) -> state.write(buf));
        }

        @Override
        public Type<AdminStateSnapshotPayload> type() {
            return TYPE;
        }
    }
}
