package com.kevo.pvaddonmute.commands;

import com.kevo.pvaddonmute.VoiceMuteController;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import su.plo.voice.api.server.mute.MuteDurationUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MuteCommands {
    private MuteCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("vcmod")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("mute")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> mute(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), "Manual mute"))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> mute(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "reason"))))))
                        .then(Commands.literal("tempmute")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("duration", StringArgumentType.word())
                                                .executes(ctx -> tempmute(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "duration"),
                                                        "Temporary mute"
                                                ))
                                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                        .executes(ctx -> tempmute(
                                                                ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "duration"),
                                                                StringArgumentType.getString(ctx, "reason")
                                                        ))))))
                        .then(Commands.literal("unmute")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> unmute(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("lockdown")
                                .then(Commands.literal("on").executes(ctx -> lockdown(ctx.getSource(), true)))
                                .then(Commands.literal("off").executes(ctx -> lockdown(ctx.getSource(), false)))
                                .then(Commands.literal("temp")
                                        .then(Commands.argument("duration", StringArgumentType.word())
                                                .executes(ctx -> lockdownTemp(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "duration")
                                                )))))
                        .then(Commands.literal("allow")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> allow(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("disallow")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> disallow(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
        );
    }

    private static int mute(CommandSourceStack source, ServerPlayer target, String reason) {
        ServerPlayer staff = tryGetSourcePlayer(source);
        boolean ok = VoiceMuteController.mutePlayer(target, staff, reason, true);

        if (ok) {
            source.sendSuccess(() -> trColor(ChatFormatting.RED, "command.pvaddonmute.muted", target.getName()), true);
            return 1;
        }

        source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.already_muted"));
        return 0;
    }

    private static int tempmute(CommandSourceStack source, ServerPlayer target, String durationRaw, String reason) {
        DurationSpec duration = parseDuration(durationRaw);
        if (duration == null) {
            source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.invalid_duration"));
            return 0;
        }

        ServerPlayer staff = tryGetSourcePlayer(source);
        boolean ok = VoiceMuteController.mutePlayerTemporary(target, staff, reason, duration.value, duration.unit);
        if (ok) {
            source.sendSuccess(() -> trColor(ChatFormatting.RED, "command.pvaddonmute.tempmuted", target.getName(), formatDuration(duration.toMillis())), true);
            return 1;
        }

        source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.already_muted"));
        return 0;
    }

    private static int unmute(CommandSourceStack source, ServerPlayer target) {
        boolean ok = VoiceMuteController.unmutePlayer(target);
        if (ok) {
            source.sendSuccess(() -> trColor(ChatFormatting.GREEN, "command.pvaddonmute.unmuted", target.getName()), true);
            return 1;
        }

        source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.not_muted"));
        return 0;
    }

    private static int lockdown(CommandSourceStack source, boolean enable) {
        ServerPlayer staff = tryGetSourcePlayer(source);

        int count;
        if (enable) {
            count = VoiceMuteController.enableLockdown(source.getServer(), staff);
            source.sendSuccess(() -> trColor(ChatFormatting.RED, "command.pvaddonmute.lockdown.on", count), true);
        } else {
            count = VoiceMuteController.disableLockdown(source.getServer());
            source.sendSuccess(() -> trColor(ChatFormatting.GREEN, "command.pvaddonmute.lockdown.off", count), true);
        }

        return 1;
    }

    private static int lockdownTemp(CommandSourceStack source, String durationRaw) {
        DurationSpec duration = parseDuration(durationRaw);
        if (duration == null) {
            source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.invalid_duration"));
            return 0;
        }

        ServerPlayer staff = tryGetSourcePlayer(source);
        int count = VoiceMuteController.enableLockdownTemporary(source.getServer(), staff, duration.toMillis());
        source.sendSuccess(() -> trColor(
                ChatFormatting.RED,
                "command.pvaddonmute.lockdown.temp",
                formatDuration(duration.toMillis()),
                count
        ), true);
        return 1;
    }

    private static int allow(CommandSourceStack source, ServerPlayer target) {
        if (!VoiceMuteController.isLockdownEnabled()) {
            source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.lockdown_not_enabled"));
            return 0;
        }

        if (target.hasPermissions(2)) {
            source.sendSuccess(() -> trColor(ChatFormatting.YELLOW, "command.pvaddonmute.allow.already_op", target.getName()), false);
            return 1;
        }

        boolean changed = VoiceMuteController.allowPlayerDuringLockdown(target);
        if (changed) {
            source.sendSuccess(() -> trColor(ChatFormatting.GREEN, "command.pvaddonmute.allow.success", target.getName()), true);
            return 1;
        }

        source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.already_allowed"));
        return 0;
    }

    private static int disallow(CommandSourceStack source, ServerPlayer target) {
        if (!VoiceMuteController.isLockdownEnabled()) {
            source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.lockdown_not_enabled"));
            return 0;
        }

        if (target.hasPermissions(2)) {
            source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.op_disallow"));
            return 0;
        }

        ServerPlayer staff = tryGetSourcePlayer(source);
        boolean changed = VoiceMuteController.disallowPlayerDuringLockdown(target, staff);
        if (changed) {
            source.sendSuccess(() -> trColor(ChatFormatting.RED, "command.pvaddonmute.disallow.success", target.getName()), true);
            return 1;
        }

        source.sendFailure(trColor(ChatFormatting.RED, "command.pvaddonmute.error.already_blocked"));
        return 0;
    }

    private static int status(CommandSourceStack source) {
        int manualCount = VoiceMuteController.getManualMuteSnapshots().size();
        int lockdownMutedCount = VoiceMuteController.getLockdownMutedPlayers().size();
        int allowedCount = VoiceMuteController.getAllowedPlayers().size();
        long lockdownRemaining = VoiceMuteController.getLockdownRemainingMs();

        source.sendSuccess(() -> tr("command.pvaddonmute.status.lockdown", lockStateComponent()), false);
        if (lockdownRemaining > 0L) {
            source.sendSuccess(() -> tr("command.pvaddonmute.status.lockdown_remaining", formatDuration(lockdownRemaining)), false);
        }
        source.sendSuccess(() -> tr("command.pvaddonmute.status.manual_mutes", manualCount), false);
        source.sendSuccess(() -> tr("command.pvaddonmute.status.lockdown_muted", lockdownMutedCount), false);
        source.sendSuccess(() -> tr("command.pvaddonmute.status.allowed", allowedCount), false);
        source.sendSuccess(() -> tr("command.pvaddonmute.status.file", "config/pv-better-mute-state.json"), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        long now = System.currentTimeMillis();

        Collection<UUID> lockdownMuted = VoiceMuteController.getLockdownMutedPlayers();
        Collection<UUID> allowed = VoiceMuteController.getAllowedPlayers();
        List<VoiceMuteController.ManualMuteSnapshot> manual = new ArrayList<>(VoiceMuteController.getManualMuteSnapshots());
        long lockdownRemaining = VoiceMuteController.getLockdownRemainingMs();
        manual.sort(Comparator.comparing(VoiceMuteController.ManualMuteSnapshot::getPlayerId));

        source.sendSuccess(() -> tr("command.pvaddonmute.list.header"), false);
        source.sendSuccess(() -> tr("command.pvaddonmute.status.lockdown", lockStateComponent()), false);
        if (lockdownRemaining > 0L) {
            source.sendSuccess(() -> tr("command.pvaddonmute.status.lockdown_remaining", formatDuration(lockdownRemaining)), false);
        }

        source.sendSuccess(() -> tr("command.pvaddonmute.list.manual_header", manual.size()), false);
        if (manual.isEmpty()) {
            source.sendSuccess(() -> tr("command.pvaddonmute.list.none"), false);
        } else {
            for (VoiceMuteController.ManualMuteSnapshot mute : manual) {
                String name = resolveName(source, mute.getPlayerId());
                Component duration = mute.isTemporary()
                        ? tr("command.pvaddonmute.list.duration_left", formatDuration(mute.getRemainingMs(now)))
                        : tr("command.pvaddonmute.list.duration_permanent");
                Component reason = mute.getReason() == null || mute.getReason().isBlank()
                        ? tr("command.pvaddonmute.list.no_reason")
                        : Component.literal(mute.getReason());
                source.sendSuccess(() -> tr("command.pvaddonmute.list.item", name, duration, reason), false);
            }
        }

        source.sendSuccess(() -> tr("command.pvaddonmute.list.lockdown_muted", lockdownMuted.size(), formatUuidPlayerList(source, lockdownMuted)), false);
        source.sendSuccess(() -> tr("command.pvaddonmute.list.allowed", allowed.size(), formatUuidPlayerList(source, allowed)), false);
        return 1;
    }

    private static ServerPlayer tryGetSourcePlayer(CommandSourceStack source) {
        try {
            return source.getPlayer();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveName(CommandSourceStack source, UUID id) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(id);
        return online != null ? online.getName().getString() : id.toString();
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private static Component trColor(ChatFormatting color, String key, Object... args) {
        return tr(key, args).withStyle(color);
    }

    private static Component lockStateComponent() {
        return VoiceMuteController.isLockdownEnabled() ? tr("common.pvaddonmute.on") : tr("common.pvaddonmute.off");
    }

    private static String formatUuidPlayerList(CommandSourceStack source, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();
        for (UUID id : ids) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(resolveName(source, id));
        }
        return sb.toString();
    }

    private static DurationSpec parseDuration(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() < 2) {
            return null;
        }

        char unit = value.charAt(value.length() - 1);
        String numberPart = value.substring(0, value.length() - 1);

        long amount;
        try {
            amount = Long.parseLong(numberPart);
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (amount <= 0L) {
            return null;
        }

        MuteDurationUnit durationUnit = switch (unit) {
            case 's' -> MuteDurationUnit.SECOND;
            case 'm' -> MuteDurationUnit.MINUTE;
            case 'h' -> MuteDurationUnit.HOUR;
            case 'd' -> MuteDurationUnit.DAY;
            default -> null;
        };

        if (durationUnit == null) {
            return null;
        }

        return new DurationSpec(amount, durationUnit);
    }

    private static String formatDuration(long millis) {
        if (millis <= 0L) {
            return "0s";
        }

        long totalSeconds = millis / 1000L;
        long days = totalSeconds / 86_400L;
        totalSeconds %= 86_400L;
        long hours = totalSeconds / 3_600L;
        totalSeconds %= 3_600L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        List<String> parts = new ArrayList<>();
        if (days > 0L) parts.add(days + "d");
        if (hours > 0L) parts.add(hours + "h");
        if (minutes > 0L) parts.add(minutes + "m");
        if (seconds > 0L || parts.isEmpty()) parts.add(seconds + "s");

        return String.join(" ", parts);
    }

    private static final class DurationSpec {
        private final long value;
        private final MuteDurationUnit unit;

        private DurationSpec(long value, MuteDurationUnit unit) {
            this.value = value;
            this.unit = unit;
        }

        private long toMillis() {
            return unit.multiply(value);
        }
    }
}
