package com.kevo.pvaddonmute;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import su.plo.voice.api.server.mute.MuteDurationUnit;
import su.plo.voice.api.server.mute.MuteManager;
import su.plo.voice.api.server.mute.ServerMuteInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class VoiceMuteController {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAINTENANCE_INTERVAL_MS = 1000L;

    private static final Map<UUID, ManualMuteEntry> MANUAL_MUTES = new HashMap<>();
    private static final Set<UUID> LOCKDOWN_MUTES = new HashSet<>();
    private static final Set<UUID> LOCKDOWN_ALLOWED = new HashSet<>();

    private static boolean lockdownEnabled = false;
    private static long lockdownEndsAtEpochMs = 0L;

    private static volatile MuteManager CACHED_MANAGER;
    private static volatile boolean attemptedReflection = false;

    private static MinecraftServer ACTIVE_SERVER;
    private static Path STATE_PATH;
    private static long nextMaintenanceAtMs = 0L;

    private VoiceMuteController() {}

    public static void initialize(MinecraftServer server) {
        ACTIVE_SERVER = server;
        STATE_PATH = server.getServerDirectory().resolve("config").resolve("pv-better-mute-state.json");
        loadState();
    }

    public static void shutdown() {
        saveState();
        ACTIVE_SERVER = null;
        STATE_PATH = null;
        nextMaintenanceAtMs = 0L;
    }

    public static void onVoiceReady(MinecraftServer server) {
        if (ACTIVE_SERVER == null) {
            initialize(server);
        }
        applyPolicies(server);
    }

    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now < nextMaintenanceAtMs) {
            return;
        }
        nextMaintenanceAtMs = now + MAINTENANCE_INTERVAL_MS;

        if (lockdownEnabled && lockdownEndsAtEpochMs > 0L && now >= lockdownEndsAtEpochMs) {
            disableLockdown(server);
            notifyLockdownExpired(server);
        }

        cleanupExpiredMutes(server);
        if (isReady()) {
            applyPolicies(server);
        }
    }

    public static void setMuteManager(MuteManager manager) {
        if (manager != null) {
            CACHED_MANAGER = manager;
        }
    }

    public static boolean isReady() {
        return getMuteManager(false) != null;
    }

    private static MuteManager getMuteManager() {
        MuteManager manager = getMuteManager(true);
        if (manager == null) {
            throw new IllegalStateException("Plasmo Voice addon is not initialized yet");
        }
        return manager;
    }

    private static MuteManager getMuteManager(boolean allowLazyReflection) {
        if (CACHED_MANAGER != null) {
            return CACHED_MANAGER;
        }

        PvAddonMuteAddon addon = PvAddonMuteAddon.getInstance();
        if (addon != null && addon.getMuteManager() != null) {
            CACHED_MANAGER = addon.getMuteManager();
            return CACHED_MANAGER;
        }

        if (allowLazyReflection && !attemptedReflection) {
            attemptedReflection = true;
            try {
                Class<?> impl = Class.forName("su.plo.voice.server.ModVoiceServer");
                for (java.lang.reflect.Field field : impl.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof su.plo.voice.api.server.PlasmoVoiceServer server) {
                        CACHED_MANAGER = server.getMuteManager();
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return CACHED_MANAGER;
    }

    public static boolean mutePlayer(ServerPlayer target, ServerPlayer staff, String reason, boolean recordManual) {
        return mutePlayerWithDuration(target, staff, reason, 0L, null, recordManual);
    }

    public static boolean mutePlayerTemporary(ServerPlayer target, ServerPlayer staff, String reason, long duration, MuteDurationUnit durationUnit) {
        if (duration <= 0L || durationUnit == null) {
            return false;
        }
        return mutePlayerWithDuration(target, staff, reason, duration, durationUnit, true);
    }

    private static boolean mutePlayerWithDuration(ServerPlayer target, ServerPlayer staff, String reason, long duration, MuteDurationUnit durationUnit, boolean recordManual) {
        MuteManager muteManager = getMuteManager();
        UUID targetId = target.getUUID();

        if (muteManager.getMute(targetId).isPresent()) {
            return false;
        }

        UUID staffId = staff != null ? staff.getUUID() : null;

        if (duration > 0L && durationUnit != null) {
            muteManager.mute(targetId, staffId, duration, durationUnit, reason, false);
        } else {
            muteManager.mute(targetId, staffId, 0L, null, reason, false);
        }

        if (recordManual) {
            long expiresAt = 0L;
            if (duration > 0L && durationUnit != null) {
                if (durationUnit == MuteDurationUnit.TIMESTAMP) {
                    expiresAt = duration;
                } else {
                    expiresAt = System.currentTimeMillis() + durationUnit.multiply(duration);
                }
            }
            MANUAL_MUTES.put(targetId, new ManualMuteEntry(reason, staffId, expiresAt));
            saveState();
        }

        notifyPlayerMuted(target);
        return true;
    }

    public static boolean unmutePlayer(ServerPlayer target) {
        MuteManager muteManager = getMuteManager();
        UUID targetId = target.getUUID();

        Optional<ServerMuteInfo> info = muteManager.unmute(targetId, false);
        MANUAL_MUTES.remove(targetId);
        LOCKDOWN_MUTES.remove(targetId);
        saveState();

        if (info.isPresent()) {
            notifyPlayerUnmuted(target);
            return true;
        }

        return false;
    }

    public static int enableLockdown(MinecraftServer server, ServerPlayer staff) {
        return activateLockdown(server, staff, 0L);
    }

    public static int enableLockdownTemporary(MinecraftServer server, ServerPlayer staff, long durationMs) {
        if (durationMs <= 0L) {
            return 0;
        }

        long endsAt = System.currentTimeMillis() + durationMs;
        return activateLockdown(server, staff, endsAt);
    }

    public static int disableLockdown(MinecraftServer server) {
        if (!lockdownEnabled) {
            return 0;
        }

        lockdownEnabled = false;
        lockdownEndsAtEpochMs = 0L;

        MuteManager muteManager = getMuteManager();
        int count = 0;

        for (UUID id : new HashSet<>(LOCKDOWN_MUTES)) {
            Optional<ServerMuteInfo> info = muteManager.unmute(id, false);
            if (info.isPresent()) {
                notifyPlayerUnmuted(id, server);
                count++;
            }
        }

        LOCKDOWN_MUTES.clear();
        LOCKDOWN_ALLOWED.clear();
        saveState();
        return count;
    }

    public static boolean isLockdownEnabled() {
        return lockdownEnabled;
    }

    public static long getLockdownRemainingMs() {
        if (!lockdownEnabled || lockdownEndsAtEpochMs <= 0L) {
            return 0L;
        }
        return Math.max(0L, lockdownEndsAtEpochMs - System.currentTimeMillis());
    }

    public static boolean isLockdownTemporary() {
        return lockdownEnabled && lockdownEndsAtEpochMs > 0L;
    }

    public static boolean allowPlayerDuringLockdown(ServerPlayer target) {
        UUID id = target.getUUID();
        boolean changed = LOCKDOWN_ALLOWED.add(id);

        if (LOCKDOWN_MUTES.remove(id)) {
            Optional<ServerMuteInfo> info = getMuteManager().unmute(id, false);
            if (info.isPresent()) {
                notifyPlayerUnmuted(target);
                changed = true;
            }
        }

        if (changed) {
            saveState();
            target.sendSystemMessage(voiceMessage("message.pvaddonmute.lockdown_allowed", ChatFormatting.GREEN), false);
        }

        return changed;
    }

    public static boolean disallowPlayerDuringLockdown(ServerPlayer target, ServerPlayer staff) {
        UUID id = target.getUUID();
        boolean changed = LOCKDOWN_ALLOWED.remove(id);

        if (target.hasPermissions(2)) {
            if (changed) {
                saveState();
            }
            return changed;
        }

        if (!lockdownEnabled) {
            if (changed) {
                saveState();
            }
            return changed;
        }

        MuteManager muteManager = getMuteManager();
        if (muteManager.getMute(id).isEmpty()) {
            UUID staffId = staff != null ? staff.getUUID() : null;
            muteManager.mute(id, staffId, 0L, null, "Voice lockdown is active", false);
            LOCKDOWN_MUTES.add(id);
            notifyPlayerLockdownMuted(target);
            changed = true;
        }

        if (changed) {
            saveState();
        }

        return changed;
    }

    public static void handleJoin(ServerPlayer player) {
        if (!isReady()) {
            return;
        }

        cleanupExpiredMutes(player.server);
        applyPoliciesToPlayer(player, getMuteManager());
    }

    public static Collection<UUID> getMutedPlayers() {
        Set<UUID> all = new HashSet<>();
        all.addAll(MANUAL_MUTES.keySet());
        all.addAll(LOCKDOWN_MUTES);
        return Collections.unmodifiableSet(all);
    }

    public static Collection<UUID> getAllowedPlayers() {
        return Collections.unmodifiableSet(LOCKDOWN_ALLOWED);
    }

    public static Collection<UUID> getLockdownMutedPlayers() {
        return Collections.unmodifiableSet(LOCKDOWN_MUTES);
    }

    public static Collection<ManualMuteSnapshot> getManualMuteSnapshots() {
        List<ManualMuteSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<UUID, ManualMuteEntry> entry : MANUAL_MUTES.entrySet()) {
            snapshots.add(new ManualMuteSnapshot(
                    entry.getKey(),
                    entry.getValue().reason,
                    entry.getValue().staffId,
                    entry.getValue().expiresAtEpochMs
            ));
        }
        return snapshots;
    }

    public static boolean isVoiceMuted(UUID playerId) {
        MuteManager muteManager = getMuteManager(false);
        if (muteManager == null) {
            return MANUAL_MUTES.containsKey(playerId) || LOCKDOWN_MUTES.contains(playerId);
        }

        return muteManager.getMute(playerId).isPresent();
    }

    private static boolean shouldMuteDuringLockdown(ServerPlayer player) {
        if (!lockdownEnabled) {
            return false;
        }
        if (player.hasPermissions(2)) {
            return false;
        }
        return !LOCKDOWN_ALLOWED.contains(player.getUUID());
    }

    private static void applyPolicies(MinecraftServer server) {
        MuteManager muteManager = getMuteManager(false);
        if (muteManager == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            applyPoliciesToPlayer(player, muteManager);
        }
    }

    private static void applyPoliciesToPlayer(ServerPlayer player, MuteManager muteManager) {
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();

        ManualMuteEntry manual = MANUAL_MUTES.get(id);
        if (manual != null) {
            if (manual.isExpired(now)) {
                MANUAL_MUTES.remove(id);
                saveState();
            } else if (muteManager.getMute(id).isEmpty()) {
                if (manual.expiresAtEpochMs > 0L) {
                    muteManager.mute(id, manual.staffId, manual.expiresAtEpochMs, MuteDurationUnit.TIMESTAMP, manual.reason, false);
                } else {
                    muteManager.mute(id, manual.staffId, 0L, null, manual.reason, false);
                }
            }
            return;
        }

        if (!shouldMuteDuringLockdown(player)) {
            return;
        }

        if (muteManager.getMute(id).isPresent()) {
            return;
        }

        muteManager.mute(id, null, 0L, null, "Voice lockdown is active", false);
        LOCKDOWN_MUTES.add(id);
        notifyPlayerLockdownMuted(player);
    }

    private static void cleanupExpiredMutes(MinecraftServer server) {
        long now = System.currentTimeMillis();
        boolean changed = false;

        Set<UUID> expired = new HashSet<>();
        for (Map.Entry<UUID, ManualMuteEntry> entry : MANUAL_MUTES.entrySet()) {
            if (entry.getValue().isExpired(now)) {
                expired.add(entry.getKey());
            }
        }

        if (expired.isEmpty()) {
            return;
        }

        for (UUID id : expired) {
            MANUAL_MUTES.remove(id);
            changed = true;

            MuteManager manager = getMuteManager(false);
            if (manager != null) {
                Optional<ServerMuteInfo> info = manager.unmute(id, false);
                if (info.isPresent()) {
                    notifyPlayerUnmuted(id, server);
                }
            }
        }

        if (changed) {
            saveState();
        }
    }

    private static void loadState() {
        if (STATE_PATH == null || !Files.exists(STATE_PATH)) {
            return;
        }

        try {
            String json = Files.readString(STATE_PATH, StandardCharsets.UTF_8);
            PersistentState state = GSON.fromJson(json, PersistentState.class);
            if (state == null) {
                return;
            }

            lockdownEnabled = state.lockdownEnabled;
            lockdownEndsAtEpochMs = Math.max(0L, state.lockdownEndsAtEpochMs);
            LOCKDOWN_ALLOWED.clear();
            MANUAL_MUTES.clear();

            if (state.lockdownAllowed != null) {
                for (String raw : state.lockdownAllowed) {
                    UUID id = parseUuid(raw);
                    if (id != null) {
                        LOCKDOWN_ALLOWED.add(id);
                    }
                }
            }

            if (state.manualMutes != null) {
                for (ManualMuteJson mute : state.manualMutes) {
                    UUID playerId = parseUuid(mute.playerId);
                    if (playerId == null) {
                        continue;
                    }
                    UUID staffId = parseUuid(mute.staffId);
                    String reason = mute.reason == null || mute.reason.isBlank() ? "Manual mute" : mute.reason;
                    long expiresAt = Math.max(0L, mute.expiresAtEpochMs);
                    MANUAL_MUTES.put(playerId, new ManualMuteEntry(reason, staffId, expiresAt));
                }
            }

            if (lockdownEnabled && lockdownEndsAtEpochMs > 0L && System.currentTimeMillis() >= lockdownEndsAtEpochMs) {
                lockdownEnabled = false;
                lockdownEndsAtEpochMs = 0L;
                LOCKDOWN_ALLOWED.clear();
            }

            cleanupExpiredMutes(ACTIVE_SERVER);
        } catch (Exception e) {
            pvaddonmute.LOGGER.error("[pv-better-mute] Failed to load state file {}", STATE_PATH, e);
        }
    }

    private static void saveState() {
        if (STATE_PATH == null) {
            return;
        }

        PersistentState state = new PersistentState();
        state.lockdownEnabled = lockdownEnabled;
        state.lockdownEndsAtEpochMs = lockdownEndsAtEpochMs;

        for (UUID id : LOCKDOWN_ALLOWED) {
            state.lockdownAllowed.add(id.toString());
        }

        for (Map.Entry<UUID, ManualMuteEntry> entry : MANUAL_MUTES.entrySet()) {
            ManualMuteJson mute = new ManualMuteJson();
            mute.playerId = entry.getKey().toString();
            mute.staffId = entry.getValue().staffId != null ? entry.getValue().staffId.toString() : null;
            mute.reason = entry.getValue().reason;
            mute.expiresAtEpochMs = entry.getValue().expiresAtEpochMs;
            state.manualMutes.add(mute);
        }

        try {
            Path parent = STATE_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    STATE_PATH,
                    GSON.toJson(state),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            pvaddonmute.LOGGER.error("[pv-better-mute] Failed to save state file {}", STATE_PATH, e);
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static final class ManualMuteSnapshot {
        private final UUID playerId;
        private final String reason;
        private final UUID staffId;
        private final long expiresAtEpochMs;

        private ManualMuteSnapshot(UUID playerId, String reason, UUID staffId, long expiresAtEpochMs) {
            this.playerId = playerId;
            this.reason = reason;
            this.staffId = staffId;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getReason() {
            return reason;
        }

        public UUID getStaffId() {
            return staffId;
        }

        public long getExpiresAtEpochMs() {
            return expiresAtEpochMs;
        }

        public boolean isTemporary() {
            return expiresAtEpochMs > 0L;
        }

        public long getRemainingMs(long nowMs) {
            if (expiresAtEpochMs <= 0L) {
                return 0L;
            }
            return Math.max(0L, expiresAtEpochMs - nowMs);
        }
    }

    private static final class ManualMuteEntry {
        private final String reason;
        private final UUID staffId;
        private final long expiresAtEpochMs;

        private ManualMuteEntry(String reason, UUID staffId, long expiresAtEpochMs) {
            this.reason = reason;
            this.staffId = staffId;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        private boolean isExpired(long nowMs) {
            return expiresAtEpochMs > 0L && nowMs >= expiresAtEpochMs;
        }

        private long remainingDurationMs(long nowMs) {
            if (expiresAtEpochMs <= 0L) {
                return 0L;
            }
            return Math.max(1L, expiresAtEpochMs - nowMs);
        }
    }

    private static final class PersistentState {
        private boolean lockdownEnabled;
        private long lockdownEndsAtEpochMs;
        private List<String> lockdownAllowed = new ArrayList<>();
        private List<ManualMuteJson> manualMutes = new ArrayList<>();
    }

    private static final class ManualMuteJson {
        private String playerId;
        private String staffId;
        private String reason;
        private long expiresAtEpochMs;
    }

    private static void notifyPlayerMuted(ServerPlayer player) {
        player.sendSystemMessage(voiceMessage("message.pvaddonmute.muted", ChatFormatting.RED), false);
        player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.9F, 1.0F);
    }

    private static void notifyPlayerLockdownMuted(ServerPlayer player) {
        player.sendSystemMessage(voiceMessage("message.pvaddonmute.lockdown_muted", ChatFormatting.DARK_RED), false);
        player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.9F, 0.9F);
    }

    private static void notifyPlayerUnmuted(ServerPlayer player) {
        player.sendSystemMessage(voiceMessage("message.pvaddonmute.unmuted", ChatFormatting.GREEN), false);
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8F, 1.2F);
    }

    private static void notifyPlayerUnmuted(UUID playerId, MinecraftServer server) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            notifyPlayerUnmuted(online);
        }
    }

    private static Component voiceMessage(String key, ChatFormatting color) {
        return Component.translatable(key).withStyle(color);
    }

    private static int activateLockdown(MinecraftServer server, ServerPlayer staff, long endsAtEpochMs) {
        lockdownEnabled = true;
        lockdownEndsAtEpochMs = Math.max(0L, endsAtEpochMs);

        MuteManager muteManager = getMuteManager();
        UUID staffId = staff != null ? staff.getUUID() : null;
        int count = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!shouldMuteDuringLockdown(player)) {
                continue;
            }

            UUID id = player.getUUID();
            if (muteManager.getMute(id).isPresent()) {
                continue;
            }

            muteManager.mute(id, staffId, 0L, null, "Voice lockdown is active", false);
            LOCKDOWN_MUTES.add(id);
            notifyPlayerLockdownMuted(player);
            count++;
        }

        saveState();
        return count;
    }

    private static void notifyLockdownExpired(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.hasPermissions(2)) {
                continue;
            }
            player.sendSystemMessage(voiceMessage("message.pvaddonmute.lockdown_expired", ChatFormatting.GREEN), false);
        }
    }
}
