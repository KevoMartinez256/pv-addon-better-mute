package com.kevo.pvaddonmute;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VoiceAdminScreen extends Screen {

    private static final int REFRESH_INTERVAL_TICKS = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int FACE_SIZE = 16;
    private static final int TOP_BAR_Y = 34;
    private static final int SIDE_PADDING = 20;
    private static final int TOP_BUTTON_HEIGHT = 20;
    private static final int TOP_BUTTON_GAP = 6;
    private static final int TABLE_TOP_GAP = 28;
    private static final int COLUMN_GAP = 12;
    private static final int ACTION_BUTTON_GAP = 4;
    private static final DurationPreset[] TEMP_MUTE_PRESETS = new DurationPreset[] {
            new DurationPreset("30s", "screen.pvaddonmute.admin.duration.30s"),
            new DurationPreset("1m", "screen.pvaddonmute.admin.duration.1m"),
            new DurationPreset("5m", "screen.pvaddonmute.admin.duration.5m"),
            new DurationPreset("10m", "screen.pvaddonmute.admin.duration.10m"),
            new DurationPreset("30m", "screen.pvaddonmute.admin.duration.30m"),
            new DurationPreset("1h", "screen.pvaddonmute.admin.duration.1h"),
            new DurationPreset("2h", "screen.pvaddonmute.admin.duration.2h")
    };

    private final List<PlayerRow> players = new ArrayList<>();
    private int page = 0;
    private int refreshTicker = REFRESH_INTERVAL_TICKS;
    private long lastSnapshotTime = Long.MIN_VALUE;
    private int tempMutePresetIndex = 2;
    private boolean showTempMuteList = false;
    private int controlsBottomY = TOP_BAR_Y + TOP_BUTTON_HEIGHT;

    public VoiceAdminScreen() {
        super(tr("screen.pvaddonmute.admin.title"));
    }

    @Override
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            onClose();
            return;
        }

        refreshTicker--;
        if (refreshTicker <= 0) {
            refreshTicker = REFRESH_INTERVAL_TICKS;
            reloadPlayers();
            rebuildUi();
            VoiceAdminClientNetworking.requestSnapshot();
        }

        long snapshotTime = VoiceAdminClientNetworking.getLatestSnapshotTime();
        if (snapshotTime != lastSnapshotTime) {
            lastSnapshotTime = snapshotTime;
            rebuildUi();
        }
    }

    @Override
    protected void init() {
        reloadPlayers();
        rebuildUi();
        VoiceAdminClientNetworking.requestSnapshot();
        refreshTicker = REFRESH_INTERVAL_TICKS;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int titleX = this.width / 2 - this.font.width(this.title) / 2;
        guiGraphics.drawString(this.font, this.title, titleX, 10, 0xFFFFFF, true);

        page = Math.min(page, maxPage());

        int rowStartY = getRowStartY();
        int headerY = rowStartY - 16;
        TableLayout layout = computeTableLayout();
        List<PlayerRow> visible = getVisiblePlayers();
        VoiceAdminPayloads.AdminStateSnapshotPayload snapshot = VoiceAdminClientNetworking.getLatestSnapshot();
        Map<UUID, VoiceAdminPayloads.PlayerVoiceState> states = indexSnapshotStates(snapshot);
        boolean lockdownEnabled = snapshot != null && snapshot.lockdownEnabled();

        guiGraphics.drawString(this.font, tr("screen.pvaddonmute.admin.column.player"), layout.playerX(), headerY, 0xBBBBBB, false);
        guiGraphics.drawString(this.font, tr("screen.pvaddonmute.admin.column.state"), layout.stateX(), headerY, 0xBBBBBB, false);
        guiGraphics.drawString(this.font, tr("screen.pvaddonmute.admin.column.actions"), layout.actionsX(), headerY, 0xBBBBBB, false);

        for (int i = 0; i < visible.size(); i++) {
            PlayerRow row = visible.get(i);
            int y = rowStartY + i * ROW_HEIGHT;
            int background = i % 2 == 0 ? 0x22000000 : 0x16000000;
            guiGraphics.fill(SIDE_PADDING - 4, y - 1, this.width - SIDE_PADDING + 4, y + 20, background);

            PlayerFaceRenderer.draw(guiGraphics, row.info().getSkin(), layout.playerX(), y + 2, FACE_SIZE);

            int maxPlayerTextWidth = Math.max(24, layout.stateX() - layout.playerTextX() - 8);
            String playerName = ellipsize(row.playerName(), maxPlayerTextWidth);
            guiGraphics.drawString(this.font, Component.literal(playerName), layout.playerTextX(), y + 6, 0xFFFFFF, false);

            StatusLine status = describePlayerState(row.playerId(), lockdownEnabled, states);
            int maxStateTextWidth = Math.max(24, layout.actionsX() - layout.stateX() - 8);
            String stateText = ellipsize(status.text().getString(), maxStateTextWidth);
            guiGraphics.drawString(this.font, Component.literal(stateText), layout.stateX(), y + 6, status.color(), false);
        }

        Component pageText = tr("screen.pvaddonmute.admin.page", page + 1, maxPage() + 1, players.size());
        guiGraphics.drawString(this.font, pageText, SIDE_PADDING, this.height - 24, 0xAAAAAA, false);

        Component syncText;
        int syncColor;
        if (snapshot == null) {
            syncText = tr("screen.pvaddonmute.admin.sync_waiting");
            syncColor = 0xAAAAAA;
        } else {
            long ageMs = Math.max(0L, System.currentTimeMillis() - snapshot.generatedAtEpochMs());
            if (snapshot.lockdownRemainingMs() > 0L) {
                syncText = tr(
                        "screen.pvaddonmute.admin.sync_ago_temp",
                        formatDuration(ageMs),
                        formatDuration(snapshot.lockdownRemainingMs())
                );
            } else {
                syncText = tr(
                        "screen.pvaddonmute.admin.sync_ago",
                        formatDuration(ageMs),
                        lockdownEnabled ? tr("common.pvaddonmute.on") : tr("common.pvaddonmute.off")
                );
            }
            syncColor = ageMs <= 3000L ? 0x55FF55 : 0xFFCC66;
        }
        guiGraphics.drawString(this.font, syncText, SIDE_PADDING, this.height - 36, syncColor, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuildUi() {
        clearWidgets();
        page = Math.min(page, maxPage());

        int[] topCursor = new int[] {SIDE_PADDING, TOP_BAR_Y};

        Component lockdownOnLabel = tr("screen.pvaddonmute.admin.lockdown_on");
        Component lockdownOffLabel = tr("screen.pvaddonmute.admin.lockdown_off");
        Component lockdownTempLabel = tr("screen.pvaddonmute.admin.lockdown_temp", selectedTempMutePresetLabel());
        Component statusLabel = tr("screen.pvaddonmute.admin.status");
        Component refreshLabel = tr("screen.pvaddonmute.admin.refresh");
        Component closeLabel = tr("screen.pvaddonmute.admin.close");
        Component tempListLabel = tr("screen.pvaddonmute.admin.tempmute_list", selectedTempMutePresetLabel());

        placeTopButton(topCursor, lockdownOnLabel, b -> runCommand("vcmod lockdown on"), buttonWidth(lockdownOnLabel, 100, 170));
        placeTopButton(topCursor, lockdownOffLabel, b -> runCommand("vcmod lockdown off"), buttonWidth(lockdownOffLabel, 100, 170));
        placeTopButton(topCursor, lockdownTempLabel, b -> runCommand("vcmod lockdown temp " + selectedTempMutePresetToken()), buttonWidth(lockdownTempLabel, 140, 220));
        placeTopButton(topCursor, statusLabel, b -> runCommand("vcmod status"), buttonWidth(statusLabel, 72, 120));
        placeTopButton(topCursor, refreshLabel, b -> {
            reloadPlayers();
            rebuildUi();
        }, buttonWidth(refreshLabel, 78, 140));
        placeTopButton(topCursor, closeLabel, b -> onClose(), buttonWidth(closeLabel, 72, 120));
        Button tempListButton = placeTopButton(topCursor, tempListLabel, b -> {
            showTempMuteList = !showTempMuteList;
            rebuildUi();
        }, buttonWidth(tempListLabel, 130, 220));

        controlsBottomY = topCursor[1] + TOP_BUTTON_HEIGHT;

        if (showTempMuteList) {
            int listWidth = Math.max(92, tempListButton.getWidth());
            int listX = tempListButton.getX();
            int listY = tempListButton.getY() + TOP_BUTTON_HEIGHT + 2;

            for (int i = 0; i < TEMP_MUTE_PRESETS.length; i++) {
                int index = i;
                DurationPreset preset = TEMP_MUTE_PRESETS[i];
                Button presetButton = addRenderableWidget(Button.builder(
                                tr(preset.labelKey()),
                                b -> {
                                    tempMutePresetIndex = index;
                                    showTempMuteList = false;
                                    rebuildUi();
                                })
                        .bounds(listX, listY + (i * TOP_BUTTON_HEIGHT), listWidth, TOP_BUTTON_HEIGHT)
                        .build());
                presetButton.active = i != tempMutePresetIndex;
            }

            controlsBottomY = Math.max(controlsBottomY, listY + (TEMP_MUTE_PRESETS.length * TOP_BUTTON_HEIGHT));
        }

        VoiceAdminPayloads.AdminStateSnapshotPayload snapshot = VoiceAdminClientNetworking.getLatestSnapshot();
        Map<UUID, VoiceAdminPayloads.PlayerVoiceState> states = indexSnapshotStates(snapshot);
        boolean lockdownEnabled = snapshot != null && snapshot.lockdownEnabled();
        List<PlayerRow> visible = getVisiblePlayers();
        int rowStartY = getRowStartY();

        TableLayout layout = computeTableLayout();
        int tempMuteX = layout.actionsX();
        int muteX = tempMuteX + layout.actionButtonWidth() + ACTION_BUTTON_GAP;
        int unmuteX = muteX + layout.actionButtonWidth() + ACTION_BUTTON_GAP;
        int allowX = unmuteX + layout.actionButtonWidth() + ACTION_BUTTON_GAP;
        int disallowX = allowX + layout.actionButtonWidth() + ACTION_BUTTON_GAP;

        for (int i = 0; i < visible.size(); i++) {
            PlayerRow row = visible.get(i);
            int y = rowStartY + i * ROW_HEIGHT;
            String playerName = row.playerName();
            VoiceAdminPayloads.PlayerVoiceState state = states.get(row.playerId());

            Button tempMute = addRenderableWidget(Button.builder(
                            tr("screen.pvaddonmute.admin.tempmute"),
                            b -> runCommand("vcmod tempmute " + playerName + " " + selectedTempMutePresetToken())
                    )
                    .bounds(tempMuteX, y, layout.actionButtonWidth(), TOP_BUTTON_HEIGHT)
                    .build());
            tempMute.active = state == null || !state.voiceMuted();

            Button mute = addRenderableWidget(Button.builder(tr("screen.pvaddonmute.admin.mute"), b -> runCommand("vcmod mute " + playerName))
                    .bounds(muteX, y, layout.actionButtonWidth(), TOP_BUTTON_HEIGHT)
                    .build());
            mute.active = state == null || !state.voiceMuted();

            Button unmute = addRenderableWidget(Button.builder(tr("screen.pvaddonmute.admin.unmute"), b -> runCommand("vcmod unmute " + playerName))
                    .bounds(unmuteX, y, layout.actionButtonWidth(), TOP_BUTTON_HEIGHT)
                    .build());
            unmute.active = state == null || state.voiceMuted();

            Button allow = addRenderableWidget(Button.builder(tr("screen.pvaddonmute.admin.allow"), b -> runCommand("vcmod allow " + playerName))
                    .bounds(allowX, y, layout.actionButtonWidth(), TOP_BUTTON_HEIGHT)
                    .build());
            allow.active = lockdownEnabled && (state == null || (!state.operator() && !state.allowedDuringLockdown()));

            Button disallow = addRenderableWidget(Button.builder(tr("screen.pvaddonmute.admin.disallow"), b -> runCommand("vcmod disallow " + playerName))
                    .bounds(disallowX, y, layout.actionButtonWidth(), TOP_BUTTON_HEIGHT)
                    .build());
            disallow.active = lockdownEnabled && state != null && !state.operator() && state.allowedDuringLockdown();
        }

        int pagerGap = 10;
        int pagerWidth = Math.min(96, Math.max(74, (this.width - (SIDE_PADDING * 2) - pagerGap) / 2));
        int nextX = this.width - SIDE_PADDING - pagerWidth;
        int prevX = nextX - pagerGap - pagerWidth;
        if (prevX < SIDE_PADDING) {
            prevX = SIDE_PADDING;
            nextX = prevX + pagerWidth + pagerGap;
        }

        Button prev = Button.builder(tr("screen.pvaddonmute.admin.prev"), b -> {
                    if (page > 0) {
                        page--;
                        rebuildUi();
                    }
                })
                .bounds(prevX, this.height - 28, pagerWidth, TOP_BUTTON_HEIGHT)
                .build();
        prev.active = page > 0;
        addRenderableWidget(prev);

        Button next = Button.builder(tr("screen.pvaddonmute.admin.next"), b -> {
                    if (page < maxPage()) {
                        page++;
                        rebuildUi();
                    }
                })
                .bounds(nextX, this.height - 28, pagerWidth, TOP_BUTTON_HEIGHT)
                .build();
        next.active = page < maxPage();
        addRenderableWidget(next);
    }

    private Button placeTopButton(int[] cursor, Component label, Button.OnPress onPress, int width) {
        int maxX = this.width - SIDE_PADDING;
        if (cursor[0] + width > maxX && cursor[0] > SIDE_PADDING) {
            cursor[0] = SIDE_PADDING;
            cursor[1] += TOP_BUTTON_HEIGHT + TOP_BUTTON_GAP;
        }

        Button button = addRenderableWidget(Button.builder(label, onPress)
                .bounds(cursor[0], cursor[1], width, TOP_BUTTON_HEIGHT)
                .build());
        cursor[0] += width + TOP_BUTTON_GAP;
        return button;
    }

    private int buttonWidth(Component label, int minWidth, int maxWidth) {
        int natural = this.font.width(label.getString()) + 20;
        return Math.max(minWidth, Math.min(maxWidth, natural));
    }

    private List<PlayerRow> getVisiblePlayers() {
        int rows = rowsPerPage();
        int from = Math.min(page * rows, players.size());
        int to = Math.min(players.size(), from + rows);
        return players.subList(from, to);
    }

    private int maxPage() {
        int rows = rowsPerPage();
        if (players.isEmpty() || rows <= 0) {
            return 0;
        }
        return (players.size() - 1) / rows;
    }

    private int rowsPerPage() {
        int availableHeight = this.height - getRowStartY() - 56;
        int rows = availableHeight / ROW_HEIGHT;
        return Math.max(1, Math.min(12, rows));
    }

    private void reloadPlayers() {
        players.clear();

        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            page = 0;
            return;
        }

        for (PlayerInfo playerInfo : client.getConnection().getOnlinePlayers()) {
            GameProfile profile = playerInfo.getProfile();
            if (profile == null) {
                continue;
            }
            if (profile.getId() == null) {
                continue;
            }
            players.add(new PlayerRow(profile.getId(), profile.getName(), playerInfo));
        }

        players.sort(Comparator.comparing(PlayerRow::playerName, String::compareToIgnoreCase));
        page = Math.min(page, maxPage());
    }

    private void runCommand(String commandWithoutSlash) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) {
            return;
        }
        client.player.connection.sendCommand(commandWithoutSlash);
        VoiceAdminClientNetworking.requestSnapshot();
    }

    private int getRowStartY() {
        return controlsBottomY + TABLE_TOP_GAP;
    }

    private TableLayout computeTableLayout() {
        int totalWidth = Math.max(360, this.width - (SIDE_PADDING * 2));

        int minPlayerWidth = 110;
        int minStateWidth = 92;
        int minActionButtonWidth = 34;
        int preferredActionButtonWidth = 52;

        int maxActionsArea = totalWidth - (COLUMN_GAP * 2) - minPlayerWidth - minStateWidth;
        int preferredActionsArea = (preferredActionButtonWidth * 5) + (ACTION_BUTTON_GAP * 4);

        int actionButtonWidth = preferredActionButtonWidth;
        if (maxActionsArea < preferredActionsArea) {
            actionButtonWidth = Math.max(minActionButtonWidth, (maxActionsArea - (ACTION_BUTTON_GAP * 4)) / 5);
        }

        int actionsWidth = (actionButtonWidth * 5) + (ACTION_BUTTON_GAP * 4);
        int remainingWidth = totalWidth - actionsWidth - (COLUMN_GAP * 2);

        if (remainingWidth < (minPlayerWidth + minStateWidth)) {
            int rescueButtonWidth = (totalWidth - (COLUMN_GAP * 2) - minPlayerWidth - minStateWidth - (ACTION_BUTTON_GAP * 4)) / 5;
            actionButtonWidth = Math.max(minActionButtonWidth, Math.min(actionButtonWidth, rescueButtonWidth));
            actionsWidth = (actionButtonWidth * 5) + (ACTION_BUTTON_GAP * 4);
            remainingWidth = Math.max(minPlayerWidth + minStateWidth, totalWidth - actionsWidth - (COLUMN_GAP * 2));
        }

        int playerWidth = Math.max(minPlayerWidth, Math.min(190, remainingWidth / 2));
        int stateWidth = remainingWidth - playerWidth;
        if (stateWidth < minStateWidth) {
            int missing = minStateWidth - stateWidth;
            playerWidth = Math.max(minPlayerWidth, playerWidth - missing);
            stateWidth = remainingWidth - playerWidth;
        }

        int playerX = SIDE_PADDING;
        int playerTextX = playerX + FACE_SIZE + 6;
        int stateX = playerX + playerWidth + COLUMN_GAP;
        int actionsX = stateX + stateWidth + COLUMN_GAP;

        return new TableLayout(playerX, playerTextX, playerWidth, stateX, stateWidth, actionsX, actionButtonWidth);
    }

    private String selectedTempMutePresetToken() {
        return TEMP_MUTE_PRESETS[tempMutePresetIndex].token();
    }

    private Component selectedTempMutePresetLabel() {
        return tr(TEMP_MUTE_PRESETS[tempMutePresetIndex].labelKey());
    }

    private String ellipsize(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }

        if (this.font.width(text) <= maxWidth) {
            return text;
        }

        String suffix = "...";
        int allowed = Math.max(0, maxWidth - this.font.width(suffix));
        if (allowed == 0) {
            return suffix;
        }

        return this.font.plainSubstrByWidth(text, allowed) + suffix;
    }

    private Map<UUID, VoiceAdminPayloads.PlayerVoiceState> indexSnapshotStates(VoiceAdminPayloads.AdminStateSnapshotPayload snapshot) {
        Map<UUID, VoiceAdminPayloads.PlayerVoiceState> map = new HashMap<>();
        if (snapshot == null) {
            return map;
        }

        for (VoiceAdminPayloads.PlayerVoiceState state : snapshot.players()) {
            map.put(state.playerId(), state);
        }
        return map;
    }

    private StatusLine describePlayerState(
            UUID playerId,
            boolean lockdownEnabled,
            Map<UUID, VoiceAdminPayloads.PlayerVoiceState> states
    ) {
        VoiceAdminPayloads.PlayerVoiceState state = states.get(playerId);
        if (state == null) {
            return new StatusLine(tr("screen.pvaddonmute.admin.state.syncing"), 0xAAAAAA);
        }

        if (state.voiceMuted()) {
            if (state.manualMuted()) {
                if (state.manualMuteRemainingMs() > 0L) {
                    return new StatusLine(tr("screen.pvaddonmute.admin.state.muted_temp", formatDuration(state.manualMuteRemainingMs())), 0xFFAA33);
                }
                return new StatusLine(tr("screen.pvaddonmute.admin.state.muted_manual"), 0xFF6666);
            }

            if (state.lockdownMuted()) {
                return new StatusLine(tr("screen.pvaddonmute.admin.state.muted_lockdown"), 0xFF8844);
            }

            return new StatusLine(tr("screen.pvaddonmute.admin.state.muted"), 0xFF6666);
        }

        if (lockdownEnabled) {
            if (state.operator()) {
                return new StatusLine(tr("screen.pvaddonmute.admin.state.allowed_op"), 0x66FF66);
            }
            if (state.allowedDuringLockdown()) {
                return new StatusLine(tr("screen.pvaddonmute.admin.state.allowed"), 0x66FF66);
            }
            return new StatusLine(tr("screen.pvaddonmute.admin.state.lockdown_blocked"), 0xFFCC66);
        }

        if (state.operator()) {
            return new StatusLine(tr("screen.pvaddonmute.admin.state.unmuted_op"), 0x99FF99);
        }
        return new StatusLine(tr("screen.pvaddonmute.admin.state.unmuted"), 0x99FF99);
    }

    private String formatDuration(long millis) {
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

    private record PlayerRow(UUID playerId, String playerName, PlayerInfo info) {}

    private static Component tr(String key, Object... args) {
        return Component.translatable(key, args);
    }

    private record DurationPreset(String token, String labelKey) {}

    private record StatusLine(Component text, int color) {}

    private record TableLayout(
            int playerX,
            int playerTextX,
            int playerColumnWidth,
            int stateX,
            int stateColumnWidth,
            int actionsX,
            int actionButtonWidth
    ) {}
}

