package com.pla.smart_npc.client.gui;
import com.pla.smart_npc.platform.event.NpcClientEvents.*;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.network.PlayerNpcGoalTracePacket;
import com.pla.smart_npc.network.PlayerNpcInspectatorCyclePacket;
import com.pla.smart_npc.network.PlayerNpcInspectatorCycleResultPacket;
import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import com.pla.smart_npc.network.PlayerNpcInspectorPacket;
import com.pla.smart_npc.network.PlayerNpcInspectorRequestPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import com.pla.smart_npc.network.NpcPackets;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import com.pla.smart_npc.mixin.ClientInputAccessor;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class SmartNpcInspectorOverlay {
    private static final int PANEL_WIDTH = 196;
    private static final int PANEL_HEIGHT = 294;
    private static final int REQUIREMENTS_PANEL_WIDTH = 250;
    private static final int RESOURCE_PANEL_WIDTH = 264;
    private static final int RESOURCE_PANEL_MIN_WIDTH = 160;
    private static final int RESOURCE_PANEL_HEADER_HEIGHT = 23;
    private static final int RESOURCE_PANEL_FOOTER_HEIGHT = 15;
    private static final int REQUIREMENTS_PANEL_MAX_LINES = 20;
    private static final int REQUIREMENT_ROW_HEIGHT = 24;
    private static final String REQUIREMENTS_PAYLOAD_VERSION = "#smart_npc_requirements_v1";
    private static final int SLOT_SIZE = 18;
    private static final int TEXT_LINE_HEIGHT = 11;
    private static final int TASK_DETAIL_MAX_LINES = 3;
    private static final int REFRESH_INTERVAL_TICKS = 20;
    private static final long DISPLAY_CACHE_INTERVAL_MS = 500L;
    private static final double INSPECTATOR_SWITCH_RADIUS = 64.0D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_DEFAULT = 4.0D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_MIN = 1.5D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_MAX = 12.0D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_STEP = 0.5D;
    private static final int INSPECTATOR_ZOOM_REPEAT_TICKS = 4;
    private static int inspectedEntityId = -1;
    private static List<ItemStack> snapshot = List.of();
    private static String snapshotBuildStatusText = "";
    private static String snapshotPerformanceText = "";
    private static String snapshotDailyJobText = "";
    private static String snapshotRequirementsText = "";
    private static String snapshotAiResourceText = "";
    private static PlayerNpcInspectorPacket.TeamInfo snapshotTeamInfo = PlayerNpcInspectorPacket.TeamInfo.none();
    private static boolean snapshotTraceEnabled;
    private static boolean requirementsVisible;
    private static long lastRefreshGameTime = Long.MIN_VALUE;
    private static long lastDisplayCacheMillis = Long.MIN_VALUE;
    private static boolean inspectatorActive;
    private static int inspectatorEntityId = -1;
    private static double inspectatorCameraDistance = INSPECTATOR_CAMERA_DISTANCE_DEFAULT;
    private static CameraType previousCameraType;
    private static Entity previousCameraEntity;
    private static int inspectatorZoomRepeatTicks;
    private static int pendingInspectatorTargetTicks;
    private static boolean previousInspectatorToggleDown;
    private static boolean previousCycleLeftDown;
    private static boolean previousCycleRightDown;
    private static boolean previousTraceToggleDown;
    private static boolean previousRequirementsToggleDown;
    private static boolean previousRequirementScrollUpDown;
    private static boolean previousRequirementScrollDownDown;
    private static boolean previousResourceScrollUpDown;
    private static boolean previousResourceScrollDownDown;
    private static int resourceScrollOffset;
    private static int cachedAiResourceWrapWidth = -1;
    private static Component cachedTitle = Component.empty();
    private static Component cachedHealthText = Component.empty();
    private static int cachedHealthColor = 0xFF74E291;
    private static Component cachedAiText = Component.empty();
    private static List<String> cachedInterestsLines = List.of("");
    private static List<String> cachedTeamLines = List.of("");
    private static String cachedDailyJobText = "";
    private static String cachedBuildStatusText = "";
    private static List<String> cachedPerformanceLines = List.of("");
    private static List<String> cachedAiResourceLines = List.of("");
    private static String cachedTraceText = "";
    private static Component cachedRequirementsTitle = Component.empty();
    private static Component cachedRequirementsLayout = Component.empty();
    private static List<RequirementRow> cachedRequirementRows = List.of();
    private static int cachedRequirementMore;
    private static int requirementScrollOffset;
    private static List<String> cachedRequirementLines = List.of("");
    private static Component cachedTaskLabel = Component.empty();
    private static int cachedTaskLabelWidth = 0;
    private static List<String> cachedTaskValueLines = List.of("");
    private static Component cachedMainHandText = Component.empty();
    private static String cachedInspectatorHint = "";

    public static void handlePacket(PlayerNpcInspectorPacket packet) {
        int previousEntityId = inspectedEntityId;
        if (packet.entityId() == -1) {
            disableTraceIfNeeded();
            stopInspectator(Minecraft.getInstance(), true);
            requirementsVisible = false;
            pendingInspectatorTargetTicks = 0;
            previousRequirementsToggleDown = false;
            resetRequirementScroll();
            resetRequirementScrollInput();
        } else if (packet.entityId() == PlayerNpcInspectorPacket.OVERALL_ENTITY_ID) {
            disableTraceIfNeeded();
            stopInspectator(Minecraft.getInstance(), true);
            requirementsVisible = false;
            pendingInspectatorTargetTicks = 0;
            previousRequirementsToggleDown = false;
            resetRequirementScroll();
            resetRequirementScrollInput();
        }
        inspectedEntityId = packet.entityId();
        if (inspectedEntityId != previousEntityId) {
            resetRequirementScroll();
            resetRequirementScrollInput();
            resetResourceScroll();
            resetResourceScrollInput();
        }
        snapshot = packet.items();
        snapshotBuildStatusText = packet.buildStatusText();
        snapshotPerformanceText = packet.performanceText();
        snapshotDailyJobText = packet.dailyJobText();
        snapshotRequirementsText = packet.requirementsText();
        snapshotAiResourceText = packet.aiResourceText();
        snapshotTeamInfo = packet.teamInfo();
        snapshotTraceEnabled = packet.traceEnabled();
        lastDisplayCacheMillis = Long.MIN_VALUE;
    }

    public static void handleInspectatorCycleResult(PlayerNpcInspectatorCycleResultPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !inspectatorActive) {
            return;
        }

        if (!packet.handledByServer()) {
            Entity entity = minecraft.level.getEntity(inspectedEntityId);
            if (entity instanceof PlayerNpcEntity currentNpc && currentNpc.isAlive()) {
                cycleInspectedNpc(minecraft, currentNpc, packet.direction());
            }
            return;
        }

        if (packet.entityId() >= 0) {
            acceptServerInspectatorTarget(minecraft, packet.entityId());
        }
    }

    public static boolean isInspectatorActive() {
        return inspectatorActive;
    }

    public static double getInspectatorCameraDistance(double vanillaDistance) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!inspectatorActive || minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            return vanillaDistance;
        }
        return inspectatorCameraDistance;
    }

    public static boolean shouldHideInspectatorLocalPlayer(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return inspectatorActive
                && minecraft.player != null
                && entity == minecraft.player;
    }

    public static boolean shouldRenderInspectatorCameraTargetBody(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return inspectatorActive
                && minecraft.options.getCameraType() == CameraType.FIRST_PERSON
                && entity != null
                && entity.getId() == inspectatorEntityId;
    }

    public static boolean shouldForceInspectatorTargetName(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return inspectatorActive
                && minecraft.options.getCameraType() != CameraType.FIRST_PERSON
                && entity != null
                && entity.getId() == inspectatorEntityId;
    }

    public static InspectatorCameraTransform getInspectatorCameraTransform(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!inspectatorActive
                || minecraft.level == null
                || minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            return null;
        }

        Entity entity = minecraft.level.getEntity(inspectatorEntityId);
        if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
            return null;
        }
        return new InspectatorCameraTransform(
                playerNpc.getEyePosition(partialTick),
                playerNpc.getViewYRot(partialTick),
                playerNpc.getViewXRot(partialTick)
        );
    }

    public static boolean shouldSuppressInspectatorRotationPacket(Packet<?> packet) {
        if (!inspectatorActive
                || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON
                || !(packet instanceof ServerboundMovePlayerPacket movePacket)) {
            return false;
        }
        return movePacket.hasRotation() && !movePacket.hasPosition();
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        tickInspectatorControls(minecraft);
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (inspectatorActive
                && event.getNewScreen() instanceof InventoryScreen
                && minecraft.level != null
                && minecraft.player != null
                && isInspectatorToggleDown(minecraft)) {
            event.setCanceled(true);
        }
    }

    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getEntity() != minecraft.player) {
            return;
        }

        if (inspectatorActive) {
            event.getInput().keyPresses = Input.EMPTY;
            ((ClientInputAccessor) event.getInput()).smartNpc$setMoveVector(Vec2.ZERO);
        }
    }

    public static void onRenderGuiOverlayPre(RenderGuiLayerEvent.Pre event) {
        if (inspectatorActive && Identifier.withDefaultNamespace("contextual_info_bar").equals(event.getName())) {
            event.setCanceled(true);
        }
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (inspectedEntityId == -1) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        GuiGraphicsExtractor guiGraphics = event.getGuiGraphics();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        if (inspectedEntityId == PlayerNpcInspectorPacket.OVERALL_ENTITY_ID) {
            requestRefresh(minecraft);
            int resourceWidth = Math.max(RESOURCE_PANEL_MIN_WIDTH, Math.min(RESOURCE_PANEL_WIDTH, screenWidth - 24));
            refreshOverallDisplayCache(minecraft.font, resourceWidth - 16);
            int resourceX = Math.max(4, screenWidth - resourceWidth - 12);
            renderAiResourcePanel(guiGraphics, minecraft.font, resourceX, 18, resourceWidth, screenHeight);
            return;
        }

        Entity entity = minecraft.level.getEntity(inspectedEntityId);
        if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
            if (isWaitingForInspectatorTarget()) {
                return;
            }
            clear();
            return;
        }
        pendingInspectatorTargetTicks = 0;

        requestRefresh(minecraft);
        refreshDisplayCache(minecraft.font, playerNpc);

        int x = screenWidth - PANEL_WIDTH - 12;
        int y = Math.max(4, Math.min(18, screenHeight - currentPanelHeight() - 4));
        renderPanel(guiGraphics, minecraft.font, x, y);
        if (requirementsVisible) {
            int requirementsX = Math.max(4, x - REQUIREMENTS_PANEL_WIDTH - 8);
            renderRequirementsPanel(guiGraphics, minecraft.font, requirementsX, y);
        }
        renderHoveredItemTooltip(guiGraphics, minecraft, x, y, screenWidth, screenHeight);
    }

    private static void requestRefresh(Minecraft minecraft) {
        long gameTime = minecraft.level.getGameTime();
        if (lastRefreshGameTime != Long.MIN_VALUE
                && gameTime - lastRefreshGameTime < REFRESH_INTERVAL_TICKS) {
            return;
        }

        lastRefreshGameTime = gameTime;
        sendToServerIfConnected(new PlayerNpcInspectorRequestPacket(inspectedEntityId, requirementsVisible));
    }

    private static void refreshDisplayCache(Font font, PlayerNpcEntity playerNpc) {
        long now = Util.getMillis();
        if (lastDisplayCacheMillis != Long.MIN_VALUE
                && now - lastDisplayCacheMillis < DISPLAY_CACHE_INTERVAL_MS) {
            return;
        }

        lastDisplayCacheMillis = now;
        cachedTitle = Component.translatable("gui.player_npc.inspector.title", playerNpc.getDisplayName());

        float health = playerNpc.getHealth();
        float maxHealth = playerNpc.getMaxHealth();
        cachedHealthColor = health <= maxHealth * 0.35F ? 0xFFFF6B6B : health <= maxHealth * 0.65F ? 0xFFFFD166 : 0xFF74E291;
        cachedHealthText = Component.translatable(
                "gui.player_npc.inspector.health",
                String.format(Locale.ROOT, "%.1f", health),
                String.format(Locale.ROOT, "%.1f", maxHealth)
        );

        cachedAiText = Component.translatable(
                "gui.player_npc.inspector.ai",
                Component.translatable(playerNpc.getCurrentAiState()).withStyle(ChatFormatting.AQUA)
        );
        cachedInterestsLines = wrappedLines(
                font,
                Component.translatable("gui.player_npc.inspector.interests", playerNpc.getInterestsDisplayText()).getString(),
                PANEL_WIDTH - 16
        );
        cachedTeamLines = wrappedLines(font, teamText().getString(), PANEL_WIDTH - 16);
        cachedDailyJobText = trimToWidth(
                font,
                Component.translatable("gui.player_npc.inspector.daily_job", snapshotDailyJobText).getString(),
                PANEL_WIDTH - 16
        );
        cachedBuildStatusText = trimToWidth(
                font,
                Component.translatable("gui.player_npc.inspector.build", snapshotBuildStatusText).getString(),
                PANEL_WIDTH - 16
        );
        cachedPerformanceLines = performanceLines(
                font,
                Component.translatable("gui.player_npc.inspector.performance", snapshotPerformanceText).getString(),
                PANEL_WIDTH - 16
        );
        cachedAiResourceLines = resourceLines(font, snapshotAiResourceText, RESOURCE_PANEL_WIDTH - 16);
        cachedTraceText = trimToWidth(
                font,
                Component.translatable(
                        "gui.player_npc.inspector.trace",
                        Component.translatable(snapshotTraceEnabled
                                ? "gui.player_npc.inspector.trace_on"
                                : "gui.player_npc.inspector.trace_off")
                ).getString(),
                PANEL_WIDTH - 16
        );
        cachedRequirementsTitle = Component.translatable("gui.player_npc.inspector.requirements_title");
        RequirementPayload requirementsPayload = parseRequirementsPayload(snapshotRequirementsText);
        if (requirementsPayload.structured()) {
            cachedRequirementsLayout = requirementsPayload.layout();
            cachedRequirementRows = requirementsPayload.rows();
            cachedRequirementMore = requirementsPayload.more();
            cachedRequirementLines = List.of("");
            clampRequirementScrollOffset();
        } else {
            cachedRequirementsLayout = Component.empty();
            cachedRequirementRows = List.of();
            cachedRequirementMore = 0;
            resetRequirementScroll();
            cachedRequirementLines = requirementsLines(font, snapshotRequirementsText, REQUIREMENTS_PANEL_WIDTH - 16);
        }

        cachedTaskLabel = Component.translatable("gui.player_npc.inspector.task");
        cachedTaskLabelWidth = font.width(cachedTaskLabel);
        int taskMaxWidth = PANEL_WIDTH - cachedTaskLabelWidth - 20;
        int taskFullLineWidth = PANEL_WIDTH - 16;
        String detail = playerNpc.getCurrentAiDetail();
        if (detail == null || detail.isBlank()) {
            String state = playerNpc.getCurrentAiState();
            detail = PlayerNpcEntity.AI_IDLE.equals(state)
                    ? Component.translatable("ai.player_npc.looking_for_work").getString()
                    : Component.translatable(state).getString();
        }
        cachedTaskValueLines = taskLines(font, detail, taskMaxWidth, taskFullLineWidth);

        ItemStack mainHand = getSnapshotItem(0);
        Component itemName = mainHand.isEmpty()
                ? Component.translatable("gui.player_npc.inspector.empty")
                : mainHand.getHoverName();
        cachedMainHandText = Component.translatable("gui.player_npc.inspector.item_name", itemName);
        cachedInspectatorHint = trimToWidth(
                font,
                Component.translatable(spectatorHintKey()).getString(),
                PANEL_WIDTH - 16
        );
    }

    private static void renderPanel(GuiGraphicsExtractor guiGraphics, Font font, int x, int y) {
        int panelHeight = currentPanelHeight();
        int interestsExtraHeight = interestsExtraHeight();
        int teamExtraHeight = teamExtraHeight();
        int mainHandY = y + mainHandOffset();
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + panelHeight, 0xE80F1720);
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + 1, 0xFF4FD1C5);
        guiGraphics.fill(x, y + panelHeight - 1, x + PANEL_WIDTH, y + panelHeight, 0xFF243447);
        guiGraphics.fill(x, y, x + 1, y + panelHeight, 0xFF243447);
        guiGraphics.fill(x + PANEL_WIDTH - 1, y, x + PANEL_WIDTH, y + panelHeight, 0xFF243447);

        guiGraphics.text(font, cachedTitle, x + 8, y + 7, 0xFFE6FFFA, false);
        guiGraphics.text(font, cachedHealthText, x + 8, y + 21, cachedHealthColor, false);
        guiGraphics.text(font, cachedAiText, x + 8, y + 35, 0xFFB7C9E2, false);
        for (int i = 0; i < cachedInterestsLines.size(); i++) {
            guiGraphics.text(font, cachedInterestsLines.get(i), x + 8, y + 49 + i * TEXT_LINE_HEIGHT, 0xFFB7C9E2, false);
        }
        for (int i = 0; i < cachedTeamLines.size(); i++) {
            guiGraphics.text(font, cachedTeamLines.get(i), x + 8,
                    y + 63 + interestsExtraHeight + i * TEXT_LINE_HEIGHT, 0xFF9ED7C5, false);
        }
        int contentExtraHeight = interestsExtraHeight + teamExtraHeight;
        guiGraphics.text(font, cachedDailyJobText, x + 8, y + 77 + contentExtraHeight, 0xFFD6E4FF, false);
        guiGraphics.text(font, cachedBuildStatusText, x + 8, y + 91 + contentExtraHeight, 0xFFB7C9E2, false);
        int performanceY = y + 105 + contentExtraHeight;
        for (String line : cachedPerformanceLines) {
            guiGraphics.text(font, line, x + 8, performanceY, 0xFFB7C9E2, false);
            performanceY += TEXT_LINE_HEIGHT;
        }
        guiGraphics.text(font, cachedTraceText, x + 8, performanceY, snapshotTraceEnabled ? 0xFF74E291 : 0xFFB7C9E2, false);

        renderTaskDetail(guiGraphics, font, x + 8, performanceY + 15);

        guiGraphics.text(font, cachedMainHandText, x + 8, mainHandY, 0xFFD6E4FF, false);

        renderEquipment(guiGraphics, font, x + 8, mainHandY + 30);
        renderInventory(guiGraphics, font, x + 8, mainHandY + 64);
        guiGraphics.text(font, cachedInspectatorHint, x + 8, y + panelHeight - 15, 0xFF94A3B8, false);
    }

    private static void refreshOverallDisplayCache(Font font, int wrapWidth) {
        long now = Util.getMillis();
        if (lastDisplayCacheMillis != Long.MIN_VALUE
                && now - lastDisplayCacheMillis < DISPLAY_CACHE_INTERVAL_MS
                && cachedAiResourceWrapWidth == wrapWidth) {
            return;
        }
        lastDisplayCacheMillis = now;
        cachedAiResourceWrapWidth = wrapWidth;
        cachedAiResourceLines = resourceLines(font, snapshotAiResourceText, wrapWidth);
    }

    private static void renderRequirementsPanel(GuiGraphicsExtractor guiGraphics, Font font, int x, int y) {
        if (!cachedRequirementRows.isEmpty()) {
            renderRequirementRowsPanel(guiGraphics, font, x, y);
            return;
        }

        int lineHeight = 11;
        int height = Math.min(PANEL_HEIGHT, 34 + cachedRequirementLines.size() * lineHeight);
        guiGraphics.fill(x, y, x + REQUIREMENTS_PANEL_WIDTH, y + height, 0xE80F1720);
        guiGraphics.fill(x, y, x + REQUIREMENTS_PANEL_WIDTH, y + 1, 0xFFFFD166);
        guiGraphics.fill(x, y + height - 1, x + REQUIREMENTS_PANEL_WIDTH, y + height, 0xFF243447);
        guiGraphics.fill(x, y, x + 1, y + height, 0xFF243447);
        guiGraphics.fill(x + REQUIREMENTS_PANEL_WIDTH - 1, y, x + REQUIREMENTS_PANEL_WIDTH, y + height, 0xFF243447);

        guiGraphics.text(font, cachedRequirementsTitle, x + 8, y + 7, 0xFFFFD166, false);
        int lineY = y + 23;
        for (String line : cachedRequirementLines) {
            if (lineY + lineHeight > y + height - 4) {
                break;
            }
            guiGraphics.text(font, line, x + 8, lineY, 0xFFD6E4FF, false);
            lineY += lineHeight;
        }
    }

    private static void renderAiResourcePanel(
            GuiGraphicsExtractor guiGraphics,
            Font font,
            int x,
            int y,
            int width,
            int screenHeight
    ) {
        int availableHeight = Math.max(RESOURCE_PANEL_HEADER_HEIGHT + TEXT_LINE_HEIGHT + 5, screenHeight - y - 8);
        int linesWithoutFooter = Math.max(1, (availableHeight - RESOURCE_PANEL_HEADER_HEIGHT - 5) / TEXT_LINE_HEIGHT);
        boolean scrollable = cachedAiResourceLines.size() > linesWithoutFooter;
        int footerHeight = scrollable ? RESOURCE_PANEL_FOOTER_HEIGHT : 5;
        int visibleLines = Math.max(1, (availableHeight - RESOURCE_PANEL_HEADER_HEIGHT - footerHeight) / TEXT_LINE_HEIGHT);
        int maxScroll = Math.max(0, cachedAiResourceLines.size() - visibleLines);
        resourceScrollOffset = Math.max(0, Math.min(maxScroll, resourceScrollOffset));
        int renderedLines = Math.min(visibleLines, cachedAiResourceLines.size() - resourceScrollOffset);
        int height = Math.min(availableHeight, RESOURCE_PANEL_HEADER_HEIGHT + renderedLines * TEXT_LINE_HEIGHT + footerHeight);
        guiGraphics.fill(x, y, x + width, y + height, 0xE80F1720);
        guiGraphics.fill(x, y, x + width, y + 1, 0xFF60A5FA);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, 0xFF243447);
        guiGraphics.fill(x, y, x + 1, y + height, 0xFF243447);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, 0xFF243447);
        guiGraphics.text(
                font,
                Component.translatable("gui.player_npc.inspector.ai_resources_title"),
                x + 8,
                y + 7,
                0xFFBFDBFE,
                false
        );

        int lineY = y + RESOURCE_PANEL_HEADER_HEIGHT;
        for (int i = 0; i < renderedLines; i++) {
            String line = cachedAiResourceLines.get(resourceScrollOffset + i);
            int color = line.startsWith("> ") ? 0xFF74E291
                    : line.startsWith("  ") ? 0xFF94A3B8
                    : 0xFFD6E4FF;
            guiGraphics.text(font, line, x + 8, lineY, color, false);
            lineY += TEXT_LINE_HEIGHT;
        }
        if (scrollable) {
            String range = (resourceScrollOffset + 1) + "-" + (resourceScrollOffset + renderedLines)
                    + "/" + cachedAiResourceLines.size() + "  ↑/↓ scroll";
            guiGraphics.text(font, range, x + 8, y + height - 12, 0xFF94A3B8, false);
        }
    }

    private static void renderRequirementRowsPanel(GuiGraphicsExtractor guiGraphics, Font font, int x, int y) {
        int headerHeight = requirementHeaderHeight();
        int availableRows = visibleRequirementRows();
        clampRequirementScrollOffset();
        int rowsToRender = Math.min(availableRows, Math.max(0, cachedRequirementRows.size() - requirementScrollOffset));
        int hiddenRowsAbove = requirementScrollOffset;
        int hiddenRowsBelow = Math.max(0, cachedRequirementRows.size() - requirementScrollOffset - rowsToRender) + cachedRequirementMore;
        int footerHeight = hiddenRowsAbove > 0 || hiddenRowsBelow > 0 ? 15 : 5;
        int height = Math.min(PANEL_HEIGHT, headerHeight + rowsToRender * REQUIREMENT_ROW_HEIGHT + footerHeight);

        guiGraphics.fill(x, y, x + REQUIREMENTS_PANEL_WIDTH, y + height, 0xE80F1720);
        guiGraphics.fill(x, y, x + REQUIREMENTS_PANEL_WIDTH, y + 1, 0xFFFFD166);
        guiGraphics.fill(x, y + height - 1, x + REQUIREMENTS_PANEL_WIDTH, y + height, 0xFF243447);
        guiGraphics.fill(x, y, x + 1, y + height, 0xFF243447);
        guiGraphics.fill(x + REQUIREMENTS_PANEL_WIDTH - 1, y, x + REQUIREMENTS_PANEL_WIDTH, y + height, 0xFF243447);

        guiGraphics.text(font, cachedRequirementsTitle, x + 8, y + 7, 0xFFFFD166, false);
        if (!cachedRequirementsLayout.getString().isBlank()) {
            guiGraphics.text(
                    font,
                    trimToWidth(font, cachedRequirementsLayout.getString(), REQUIREMENTS_PANEL_WIDTH - 16),
                    x + 8,
                    y + 19,
                    0xFF94A3B8,
                    false
            );
        }

        int rowY = y + headerHeight;
        for (int i = 0; i < rowsToRender; i++) {
            renderRequirementRow(guiGraphics, font, cachedRequirementRows.get(requirementScrollOffset + i), x, rowY, i);
            rowY += REQUIREMENT_ROW_HEIGHT;
        }

        if (hiddenRowsAbove > 0) {
            String text = "^ " + hiddenRowsAbove + " above";
            guiGraphics.text(font, text, x + 8, rowY + 3, 0xFF94A3B8, false);
        }
        if (hiddenRowsBelow > 0) {
            String text = "+" + hiddenRowsBelow + " more";
            guiGraphics.text(
                    font,
                    text,
                    x + REQUIREMENTS_PANEL_WIDTH - 8 - font.width(text),
                    rowY + 3,
                    0xFF94A3B8,
                    false
            );
        }
    }

    private static void renderRequirementRow(GuiGraphicsExtractor guiGraphics, Font font, RequirementRow row, int x, int y, int index) {
        int rowColor = index % 2 == 0 ? 0xAA111827 : 0xAA0B1322;
        guiGraphics.fill(x + 5, y, x + REQUIREMENTS_PANEL_WIDTH - 5, y + REQUIREMENT_ROW_HEIGHT - 1, rowColor);
        guiGraphics.fill(x + 5, y + REQUIREMENT_ROW_HEIGHT - 1, x + REQUIREMENTS_PANEL_WIDTH - 5, y + REQUIREMENT_ROW_HEIGHT, 0xFF243447);
        guiGraphics.fill(x + 8, y + 3, x + 26, y + 21, 0xFF0B1120);
        guiGraphics.item(row.stack(), x + 9, y + 4);

        String name = trimToWidth(font, row.stack().getHoverName().getString(), REQUIREMENTS_PANEL_WIDTH - 106);
        guiGraphics.text(font, name, x + 31, y + 4, 0xFFE6FFFA, false);

        String countText = row.carried() + " / " + row.remaining();
        int countColor = row.missing() <= 0 ? 0xFF74E291 : 0xFFFFD166;
        guiGraphics.text(
                font,
                countText,
                x + REQUIREMENTS_PANEL_WIDTH - 8 - font.width(countText),
                y + 4,
                countColor,
                false
        );

        String placedText = "placed " + row.placed() + " of " + row.required();
        guiGraphics.text(font, placedText, x + 31, y + 14, 0xFF94A3B8, false);

        String missingText = row.missing() <= 0 ? "ready" : "missing " + row.missing();
        int missingColor = row.missing() <= 0 ? 0xFF74E291 : 0xFFFF6B6B;
        guiGraphics.text(
                font,
                missingText,
                x + REQUIREMENTS_PANEL_WIDTH - 8 - font.width(missingText),
                y + 14,
                missingColor,
                false
        );
    }

    private static void renderTaskDetail(GuiGraphicsExtractor guiGraphics, Font font, int x, int y) {
        guiGraphics.text(font, cachedTaskLabel, x, y, 0xFF4FD1C5, false);

        int valueX = x + cachedTaskLabelWidth + 4;
        for (int i = 0; i < cachedTaskValueLines.size(); i++) {
            int lineX = i == 0 ? valueX : x;
            guiGraphics.text(font, cachedTaskValueLines.get(i), lineX, y + i * 11, 0xFFFFD166, false);
        }
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }

        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private static List<String> wrappedLines(Font font, String text, int fullLineWidth) {
        String remaining = text == null ? "" : text.strip();
        if (remaining.isEmpty()) {
            return List.of("");
        }

        List<String> lines = new ArrayList<>();
        while (!remaining.isEmpty()) {
            if (font.width(remaining) <= fullLineWidth) {
                lines.add(remaining);
                break;
            }

            String fitting = font.plainSubstrByWidth(remaining, fullLineWidth);
            int commaBreak = fitting.lastIndexOf(", ");
            int spaceBreak = fitting.lastIndexOf(' ');
            int splitIndex = commaBreak > 0 ? commaBreak + 1 : spaceBreak;
            if (splitIndex <= 0) {
                splitIndex = fitting.length();
            }
            if (splitIndex <= 0) {
                lines.add(remaining);
                break;
            }

            lines.add(remaining.substring(0, splitIndex).stripTrailing());
            remaining = remaining.substring(splitIndex).stripLeading();
        }
        return List.copyOf(lines);
    }

    private static int interestsExtraHeight() {
        return Math.max(0, cachedInterestsLines.size() - 1) * TEXT_LINE_HEIGHT;
    }

    private static int teamExtraHeight() {
        return Math.max(0, cachedTeamLines.size() - 1) * TEXT_LINE_HEIGHT;
    }

    private static int mainHandOffset() {
        int contentExtraHeight = interestsExtraHeight() + teamExtraHeight();
        int traceOffset = 105 + contentExtraHeight + cachedPerformanceLines.size() * TEXT_LINE_HEIGHT;
        int taskOffset = traceOffset + 15;
        int taskBottom = taskOffset + cachedTaskValueLines.size() * TEXT_LINE_HEIGHT;
        return Math.max(165 + contentExtraHeight, taskBottom + 3);
    }

    private static int currentPanelHeight() {
        return Math.max(PANEL_HEIGHT + 14 + interestsExtraHeight() + teamExtraHeight(), mainHandOffset() + 143);
    }

    private static Component teamText() {
        return switch (snapshotTeamInfo.role()) {
            case LEADER -> Component.translatable(
                    "gui.player_npc.inspector.team_leader",
                    snapshotTeamInfo.teamName(),
                    snapshotTeamInfo.leaderName()
            );
            case NPC_FOLLOWER -> snapshotTeamInfo.leaderName().isBlank()
                    ? Component.translatable(
                    "gui.player_npc.inspector.team_npc_follower_unavailable",
                    snapshotTeamInfo.teamName()
            )
                    : Component.translatable(
                    "gui.player_npc.inspector.team_npc_follower",
                    snapshotTeamInfo.teamName(),
                    snapshotTeamInfo.leaderName()
            );
            case PLAYER_FOLLOWER -> snapshotTeamInfo.leaderName().isBlank()
                    ? Component.translatable(
                    "gui.player_npc.inspector.team_player_follower_unavailable",
                    snapshotTeamInfo.teamName()
            )
                    : Component.translatable(
                    "gui.player_npc.inspector.team_player_follower",
                    snapshotTeamInfo.teamName(),
                    snapshotTeamInfo.leaderName()
            );
            case NONE -> Component.translatable("gui.player_npc.inspector.team_none");
        };
    }

    private static List<String> taskLines(Font font, String detail, int firstLineWidth, int fullLineWidth) {
        List<String> lines = new ArrayList<>(TASK_DETAIL_MAX_LINES);
        String[] rawLines = detail.split("\\R", -1);
        for (String rawLine : rawLines) {
            if (lines.size() >= TASK_DETAIL_MAX_LINES) {
                break;
            }
            appendWrappedTaskLine(font, lines, rawLine, firstLineWidth, fullLineWidth);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private static List<String> performanceLines(Font font, String text, int fullLineWidth) {
        String source = text == null ? "" : text.replace(" | ", "\n");
        List<String> lines = new ArrayList<>(2);
        String[] rawLines = source.split("\\R", -1);
        for (String rawLine : rawLines) {
            if (lines.size() >= 2) {
                break;
            }
            lines.add(trimToWidth(font, rawLine.strip(), fullLineWidth));
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private static List<String> resourceLines(Font font, String text, int fullLineWidth) {
        List<String> lines = new ArrayList<>();
        String source = text == null || text.isBlank() ? "No scheduler resource holders" : text;
        for (String rawLine : source.split("\\R", -1)) {
            appendWrappedResourceLine(font, lines, rawLine, fullLineWidth);
        }
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }

    private static void appendWrappedResourceLine(Font font, List<String> lines, String rawLine, int fullLineWidth) {
        String source = rawLine == null ? "" : rawLine;
        int leadingSpaces = 0;
        while (leadingSpaces < source.length() && source.charAt(leadingSpaces) == ' ') {
            leadingSpaces++;
        }
        String remaining = source.substring(leadingSpaces).stripTrailing();
        String firstPrefix = " ".repeat(leadingSpaces);
        String continuationPrefix = firstPrefix + "  ";
        String prefix = firstPrefix;
        if (remaining.isEmpty()) {
            lines.add(firstPrefix);
            return;
        }

        while (!remaining.isEmpty()) {
            int availableWidth = Math.max(1, fullLineWidth - font.width(prefix));
            if (font.width(remaining) <= availableWidth) {
                lines.add(prefix + remaining);
                return;
            }
            String fitting = font.plainSubstrByWidth(remaining, availableWidth);
            int breakAt = fitting.lastIndexOf(' ');
            if (breakAt <= 0) {
                breakAt = fitting.length();
            }
            if (breakAt <= 0) {
                breakAt = 1;
            }
            lines.add(prefix + remaining.substring(0, breakAt).stripTrailing());
            remaining = remaining.substring(breakAt).stripLeading();
            prefix = continuationPrefix;
        }
    }

    private static List<String> requirementsLines(Font font, String text, int fullLineWidth) {
        List<String> lines = new ArrayList<>(REQUIREMENTS_PANEL_MAX_LINES);
        String source = text == null || text.isBlank()
                ? Component.translatable("gui.player_npc.inspector.requirements_empty").getString()
                : text;
        String[] rawLines = source.split("\\R", -1);
        for (String rawLine : rawLines) {
            if (lines.size() >= REQUIREMENTS_PANEL_MAX_LINES) {
                break;
            }
            appendWrappedRequirementLine(font, lines, rawLine, fullLineWidth);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private static RequirementPayload parseRequirementsPayload(String text) {
        if (text == null || text.isBlank()) {
            return RequirementPayload.fallback();
        }

        String[] rawLines = text.split("\\R", -1);
        if (rawLines.length == 0 || !REQUIREMENTS_PAYLOAD_VERSION.equals(rawLines[0])) {
            return RequirementPayload.fallback();
        }

        Component layout = Component.empty();
        List<RequirementRow> rows = new ArrayList<>();
        int more = 0;
        for (int i = 1; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (line.startsWith("layout\t")) {
                layout = Component.literal(line.substring("layout\t".length()));
                continue;
            }
            if (line.startsWith("more\t")) {
                more = parseNonNegativeInt(line.substring("more\t".length()));
                continue;
            }
            if (!line.startsWith("item\t")) {
                continue;
            }

            String[] parts = line.split("\t", -1);
            if (parts.length < 6) {
                continue;
            }

            Identifier itemId = Identifier.tryParse(parts[1]);
            if (itemId == null) {
                continue;
            }

            Item item = BuiltInRegistries.ITEM.getValue(itemId);
            if (item == null) {
                continue;
            }

            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }

            rows.add(new RequirementRow(
                    stack,
                    parseNonNegativeInt(parts[2]),
                    parseNonNegativeInt(parts[3]),
                    parseNonNegativeInt(parts[4]),
                    parseNonNegativeInt(parts[5])
            ));
        }
        return new RequirementPayload(true, layout, List.copyOf(rows), more);
    }

    private static int parseNonNegativeInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int requirementHeaderHeight() {
        return cachedRequirementsLayout.getString().isBlank() ? 25 : 35;
    }

    private static void appendWrappedRequirementLine(Font font, List<String> lines, String rawLine, int fullLineWidth) {
        String remaining = rawLine == null ? "" : rawLine.stripLeading();
        if (remaining.isEmpty()) {
            lines.add("");
            return;
        }

        while (!remaining.isEmpty() && lines.size() < REQUIREMENTS_PANEL_MAX_LINES) {
            boolean isLastLine = lines.size() == REQUIREMENTS_PANEL_MAX_LINES - 1;
            if (font.width(remaining) <= fullLineWidth) {
                lines.add(remaining);
                return;
            }
            if (isLastLine) {
                lines.add(trimToWidth(font, remaining, fullLineWidth));
                return;
            }

            String segment = font.plainSubstrByWidth(remaining, fullLineWidth).stripTrailing();
            if (segment.isEmpty()) {
                lines.add(trimToWidth(font, remaining, fullLineWidth));
                return;
            }

            lines.add(segment);
            remaining = remaining.substring(segment.length()).stripLeading();
        }
    }

    private static void appendWrappedTaskLine(
            Font font,
            List<String> lines,
            String rawLine,
            int firstLineWidth,
            int fullLineWidth
    ) {
        String remaining = rawLine == null ? "" : rawLine.stripLeading();
        if (remaining.isEmpty()) {
            lines.add("");
            return;
        }

        while (!remaining.isEmpty() && lines.size() < TASK_DETAIL_MAX_LINES) {
            int width = lines.isEmpty() ? firstLineWidth : fullLineWidth;
            boolean isLastTaskLine = lines.size() == TASK_DETAIL_MAX_LINES - 1;
            if (font.width(remaining) <= width) {
                lines.add(remaining);
                return;
            }
            if (isLastTaskLine) {
                lines.add(trimToWidth(font, remaining, width));
                return;
            }

            String segment = font.plainSubstrByWidth(remaining, width).stripTrailing();
            if (segment.isEmpty()) {
                lines.add(trimToWidth(font, remaining, width));
                return;
            }

            lines.add(segment);
            remaining = remaining.substring(segment.length()).stripLeading();
        }
    }

    private static void renderEquipment(GuiGraphicsExtractor guiGraphics, Font font, int x, int y) {
        guiGraphics.text(font, Component.translatable("gui.player_npc.inspector.equipment"), x, y - 10, 0xFF4FD1C5, false);
        for (int i = 0; i < 6; i++) {
            renderSlot(guiGraphics, font, getSnapshotItem(i), x + i * (SLOT_SIZE + 3), y);
        }
    }

    private static void renderInventory(GuiGraphicsExtractor guiGraphics, Font font, int x, int y) {
        guiGraphics.text(font, Component.translatable("gui.player_npc.inspector.inventory"), x, y - 10, 0xFF4FD1C5, false);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int index = 6 + column + row * 9;
                renderSlot(guiGraphics, font, getSnapshotItem(index), x + column * (SLOT_SIZE + 3), y + row * (SLOT_SIZE + 2));
            }
        }
    }

    private static void renderSlot(GuiGraphicsExtractor guiGraphics, Font font, ItemStack stack, int x, int y) {
        guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF0B1120);
        guiGraphics.fill(x, y, x + 16, y + 16, 0xFF1E293B);
        if (!stack.isEmpty()) {
            guiGraphics.item(stack, x, y);
            guiGraphics.itemDecorations(font, stack, x, y);
        }
    }

    private static ItemStack getSnapshotItem(int index) {
        if (index < 0 || index >= snapshot.size()) {
            return ItemStack.EMPTY;
        }
        return snapshot.get(index);
    }

    private static Component getItemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.translatable("gui.player_npc.inspector.empty");
        }

        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? Component.literal("unknown") : Component.literal(id.toString());
    }

    private static void tickInspectatorControls(Minecraft minecraft) {
        boolean chatOpen = isChatScreen(minecraft.gui.screen());
        if (inspectedEntityId == PlayerNpcInspectorPacket.OVERALL_ENTITY_ID) {
            if (inspectatorActive) {
                stopInspectator(minecraft, true);
            }
            resetInspectatorToggle();
            previousCycleLeftDown = false;
            previousCycleRightDown = false;
            previousTraceToggleDown = false;
            previousRequirementsToggleDown = false;
            resetRequirementScrollInput();
            handleResourceScrollInput(minecraft);
            return;
        }
        if (inspectedEntityId < 0 || minecraft.gui.screen() != null && !chatOpen) {
            if (inspectatorActive) {
                stopInspectator(minecraft, true);
            }
            resetInspectatorToggle();
            previousCycleLeftDown = false;
            previousCycleRightDown = false;
            previousTraceToggleDown = false;
            previousRequirementsToggleDown = false;
            resetRequirementScrollInput();
            inspectatorZoomRepeatTicks = 0;
            return;
        }

        Entity entity = minecraft.level.getEntity(inspectedEntityId);
        if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
            if (tickPendingInspectatorTarget(minecraft)) {
                return;
            }
            clear();
            return;
        }
        pendingInspectatorTargetTicks = 0;

        if (chatOpen) {
            resetInspectatorToggle();
            previousCycleLeftDown = false;
            previousCycleRightDown = false;
            previousTraceToggleDown = false;
            previousRequirementsToggleDown = false;
            resetRequirementScrollInput();
            inspectatorZoomRepeatTicks = 0;
            if (inspectatorActive) {
                tickActiveInspectator(minecraft, playerNpc, false);
            }
            return;
        }

        handleTraceToggleInput(minecraft, inspectedEntityId);
        handleRequirementsToggleInput(minecraft);
        handleRequirementsScrollInput(minecraft);

        boolean toggleDown = isInspectatorToggleDown(minecraft);
        if (toggleDown && !previousInspectatorToggleDown) {
            if (inspectatorActive && inspectatorEntityId == inspectedEntityId) {
                stopInspectator(minecraft, true);
            } else {
                startInspectator(minecraft, playerNpc);
            }
        }
        previousInspectatorToggleDown = toggleDown;

        if (inspectatorActive) {
            tickActiveInspectator(minecraft, playerNpc, true);
            return;
        }

        previousCycleLeftDown = false;
        previousCycleRightDown = false;
    }

    private static void startInspectator(Minecraft minecraft, PlayerNpcEntity playerNpc) {
        boolean startingFresh = !inspectatorActive;
        if (startingFresh) {
            previousCameraType = minecraft.options.getCameraType();
            previousCameraEntity = minecraft.getCameraEntity();
        }
        if (inspectatorEntityId != playerNpc.getId()) {
            snapshotTraceEnabled = false;
        }

        inspectatorActive = true;
        inspectatorEntityId = playerNpc.getId();
        pendingInspectatorTargetTicks = 0;
        lastDisplayCacheMillis = Long.MIN_VALUE;
        sendToServerIfConnected(new PlayerNpcInspectatorModePacket(true, inspectatorEntityId));
        if (startingFresh) {
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            inspectatorCameraDistance = INSPECTATOR_CAMERA_DISTANCE_DEFAULT;
        }
        updateInspectatorCameraMode(minecraft, playerNpc);
        if (minecraft.gui.screen() == null) {
            minecraft.mouseHandler.grabMouse();
        }
    }

    private static void tickActiveInspectator(Minecraft minecraft, PlayerNpcEntity playerNpc, boolean allowHotkeys) {
        updateInspectatorCameraMode(minecraft, playerNpc);

        if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            inspectatorZoomRepeatTicks = 0;
        } else if (allowHotkeys) {
            handleInspectatorZoomInput(minecraft);
        }

        if (allowHotkeys) {
            handleInspectatorCycleInput(minecraft, playerNpc);
        }
        suppressPlayerInput(minecraft);
    }

    private static void stopInspectator(Minecraft minecraft, boolean notifyServer) {
        boolean wasActive = inspectatorActive;
        inspectatorActive = false;
        inspectatorEntityId = -1;
        pendingInspectatorTargetTicks = 0;
        resetInspectatorToggle();
        previousCycleLeftDown = false;
        previousCycleRightDown = false;
        previousTraceToggleDown = false;
        previousRequirementsToggleDown = false;
        resetRequirementScrollInput();
        inspectatorZoomRepeatTicks = 0;
        lastDisplayCacheMillis = Long.MIN_VALUE;

        if (minecraft != null && minecraft.player != null) {
            Entity cameraToRestore = previousCameraEntity;
            if (cameraToRestore == null
                    || cameraToRestore.isRemoved()
                    || minecraft.level == null
                    || cameraToRestore.level() != minecraft.level) {
                cameraToRestore = minecraft.player;
            }
            minecraft.setCameraEntity(cameraToRestore);
            if (previousCameraType != null) {
                minecraft.options.setCameraType(previousCameraType);
            }
            if (minecraft.gui.screen() == null && wasActive) {
                minecraft.mouseHandler.grabMouse();
            }
        }

        previousCameraEntity = null;
        previousCameraType = null;
        if (notifyServer && wasActive) {
            sendToServerIfConnected(new PlayerNpcInspectatorModePacket(false, -1));
        }
    }

    private static boolean isInspectatorToggleDown(Minecraft minecraft) {
        return isInspectatorModifierDown(minecraft);
    }

    private static boolean isInspectatorModifierDown(Minecraft minecraft) {
        return isPhysicalKeyDown(minecraft, InputConstants.KEY_LALT)
                || isPhysicalKeyDown(minecraft, InputConstants.KEY_RALT);
    }

    private static void resetInspectatorToggle() {
        previousInspectatorToggleDown = false;
    }

    private static void handleInspectatorCycleInput(Minecraft minecraft, PlayerNpcEntity currentNpc) {
        boolean leftDown = minecraft.options.keyLeft.isDown() || isPhysicalKeyDown(minecraft, InputConstants.KEY_LEFT);
        boolean rightDown = minecraft.options.keyRight.isDown() || isPhysicalKeyDown(minecraft, InputConstants.KEY_RIGHT);

        if (leftDown && !previousCycleLeftDown) {
            requestInspectatorCycle(currentNpc, -1);
        } else if (rightDown && !previousCycleRightDown) {
            requestInspectatorCycle(currentNpc, 1);
        }

        previousCycleLeftDown = leftDown;
        previousCycleRightDown = rightDown;
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
    }

    private static void handleTraceToggleInput(Minecraft minecraft, int entityId) {
        boolean traceDown = isPhysicalKeyDown(minecraft, InputConstants.KEY_Z);
        if (traceDown && !previousTraceToggleDown && entityId >= 0) {
            snapshotTraceEnabled = !snapshotTraceEnabled;
            lastDisplayCacheMillis = Long.MIN_VALUE;
            sendToServerIfConnected(new PlayerNpcGoalTracePacket(entityId, snapshotTraceEnabled));
        }
        previousTraceToggleDown = traceDown;
    }

    private static void handleRequirementsToggleInput(Minecraft minecraft) {
        boolean requirementsDown = isPhysicalKeyDown(minecraft, InputConstants.KEY_X);
        if (requirementsDown && !previousRequirementsToggleDown) {
            requirementsVisible = !requirementsVisible;
            if (!requirementsVisible) {
                resetRequirementScroll();
            }
            resetRequirementScrollInput();
            lastRefreshGameTime = Long.MIN_VALUE;
            lastDisplayCacheMillis = Long.MIN_VALUE;
        }
        previousRequirementsToggleDown = requirementsDown;
    }

    private static void handleRequirementsScrollInput(Minecraft minecraft) {
        if (!requirementsVisible || !hasScrollableRequirementRows()) {
            resetRequirementScrollInput();
            return;
        }

        boolean scrollUpDown = isPhysicalKeyDown(minecraft, InputConstants.KEY_UP);
        boolean scrollDownDown = isPhysicalKeyDown(minecraft, InputConstants.KEY_DOWN);
        if (scrollUpDown && !previousRequirementScrollUpDown) {
            scrollRequirementRows(-1);
        } else if (scrollDownDown && !previousRequirementScrollDownDown) {
            scrollRequirementRows(1);
        }

        previousRequirementScrollUpDown = scrollUpDown;
        previousRequirementScrollDownDown = scrollDownDown;
    }

    private static void handleResourceScrollInput(Minecraft minecraft) {
        boolean scrollUpDown = isPhysicalKeyDown(minecraft, InputConstants.KEY_UP);
        boolean scrollDownDown = isPhysicalKeyDown(minecraft, InputConstants.KEY_DOWN);
        if (scrollUpDown && !previousResourceScrollUpDown) {
            resourceScrollOffset = Math.max(0, resourceScrollOffset - 1);
        } else if (scrollDownDown && !previousResourceScrollDownDown) {
            resourceScrollOffset++;
        }
        previousResourceScrollUpDown = scrollUpDown;
        previousResourceScrollDownDown = scrollDownDown;
    }

    private static void handleInspectatorZoomInput(Minecraft minecraft) {
        if (requirementsVisible && hasScrollableRequirementRows()) {
            inspectatorZoomRepeatTicks = 0;
            return;
        }
        if (inspectatorZoomRepeatTicks > 0) {
            inspectatorZoomRepeatTicks--;
        }

        boolean zoomIn = isPhysicalKeyDown(minecraft, InputConstants.KEY_UP);
        boolean zoomOut = isPhysicalKeyDown(minecraft, InputConstants.KEY_DOWN);
        if (zoomIn == zoomOut || inspectatorZoomRepeatTicks > 0) {
            return;
        }

        double delta = zoomIn ? -INSPECTATOR_CAMERA_DISTANCE_STEP : INSPECTATOR_CAMERA_DISTANCE_STEP;
        inspectatorCameraDistance = clamp(
                inspectatorCameraDistance + delta,
                INSPECTATOR_CAMERA_DISTANCE_MIN,
                INSPECTATOR_CAMERA_DISTANCE_MAX
        );
        inspectatorZoomRepeatTicks = INSPECTATOR_ZOOM_REPEAT_TICKS;
    }

    private static boolean isChatScreen(Screen screen) {
        return screen instanceof ChatScreen;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int visibleRequirementRows() {
        return Math.max(1, (PANEL_HEIGHT - requirementHeaderHeight() - 16) / REQUIREMENT_ROW_HEIGHT);
    }

    private static int maxRequirementScrollOffset() {
        return Math.max(0, cachedRequirementRows.size() - visibleRequirementRows());
    }

    private static boolean hasScrollableRequirementRows() {
        return maxRequirementScrollOffset() > 0;
    }

    private static void scrollRequirementRows(int delta) {
        requirementScrollOffset = Math.max(0, Math.min(maxRequirementScrollOffset(), requirementScrollOffset + delta));
        lastDisplayCacheMillis = Long.MIN_VALUE;
    }

    private static void clampRequirementScrollOffset() {
        requirementScrollOffset = Math.max(0, Math.min(maxRequirementScrollOffset(), requirementScrollOffset));
    }

    private static void resetRequirementScroll() {
        requirementScrollOffset = 0;
    }

    private static void resetRequirementScrollInput() {
        previousRequirementScrollUpDown = false;
        previousRequirementScrollDownDown = false;
    }

    private static void resetResourceScroll() {
        resourceScrollOffset = 0;
    }

    private static void resetResourceScrollInput() {
        previousResourceScrollUpDown = false;
        previousResourceScrollDownDown = false;
    }

    private static boolean isPhysicalKeyDown(Minecraft minecraft, int key) {
        return InputConstants.isKeyDown(key);
    }

    private static void requestInspectatorCycle(PlayerNpcEntity currentNpc, int direction) {
        sendToServerIfConnected(new PlayerNpcInspectatorCyclePacket(currentNpc.getId(), direction, requirementsVisible));
    }

    private static void acceptServerInspectatorTarget(Minecraft minecraft, int entityId) {
        boolean startingFresh = !inspectatorActive;
        if (startingFresh) {
            previousCameraType = minecraft.options.getCameraType();
            previousCameraEntity = minecraft.getCameraEntity();
        }
        if (inspectatorEntityId != entityId) {
            snapshotTraceEnabled = false;
        }

        inspectatorActive = true;
        inspectatorEntityId = entityId;
        inspectedEntityId = entityId;
        snapshot = List.of();
        snapshotTeamInfo = PlayerNpcInspectorPacket.TeamInfo.none();
        resetRequirementScroll();
        resetRequirementScrollInput();
        resetResourceScroll();
        resetResourceScrollInput();
        pendingInspectatorTargetTicks = 80;
        lastRefreshGameTime = Long.MIN_VALUE;
        lastDisplayCacheMillis = Long.MIN_VALUE;
        if (startingFresh) {
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            inspectatorCameraDistance = INSPECTATOR_CAMERA_DISTANCE_DEFAULT;
        }
        Entity target = minecraft.level == null ? null : minecraft.level.getEntity(entityId);
        if (target instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive()) {
            updateInspectatorCameraMode(minecraft, playerNpc);
        }
        if (minecraft.gui.screen() == null) {
            minecraft.mouseHandler.grabMouse();
        }
    }

    private static boolean isWaitingForInspectatorTarget() {
        return inspectatorActive && pendingInspectatorTargetTicks > 0;
    }

    private static void updateInspectatorCameraMode(Minecraft minecraft, PlayerNpcEntity playerNpc) {
        CameraType cameraType = minecraft.options.getCameraType();
        Entity desiredCamera = cameraType == CameraType.FIRST_PERSON ? minecraft.player : playerNpc;
        if (desiredCamera != null && minecraft.getCameraEntity() != desiredCamera) {
            minecraft.setCameraEntity(desiredCamera);
        }
    }

    private static boolean tickPendingInspectatorTarget(Minecraft minecraft) {
        if (!isWaitingForInspectatorTarget()) {
            return false;
        }

        pendingInspectatorTargetTicks--;
        suppressPlayerInput(minecraft);
        return true;
    }

    private static void cycleInspectedNpc(Minecraft minecraft, PlayerNpcEntity currentNpc, int direction) {
        List<PlayerNpcEntity> nearby = new ArrayList<>(minecraft.level.getEntitiesOfClass(
                PlayerNpcEntity.class,
                currentNpc.getBoundingBox().inflate(INSPECTATOR_SWITCH_RADIUS),
                npc -> npc.isAlive()
        ));
        if (nearby.size() < 2) {
            return;
        }

        nearby.sort(Comparator
                .comparingDouble((PlayerNpcEntity npc) -> currentNpc.distanceToSqr(npc))
                .thenComparingInt(Entity::getId));

        int currentIndex = -1;
        for (int i = 0; i < nearby.size(); i++) {
            if (nearby.get(i).getId() == currentNpc.getId()) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            return;
        }

        int nextIndex = Math.floorMod(currentIndex + direction, nearby.size());
        PlayerNpcEntity nextNpc = nearby.get(nextIndex);
        inspectedEntityId = nextNpc.getId();
        snapshot = List.of();
        snapshotTeamInfo = PlayerNpcInspectorPacket.TeamInfo.none();
        snapshotTraceEnabled = false;
        resetRequirementScroll();
        resetRequirementScrollInput();
        lastRefreshGameTime = Long.MIN_VALUE;
        lastDisplayCacheMillis = Long.MIN_VALUE;
        sendToServerIfConnected(new PlayerNpcInspectorRequestPacket(inspectedEntityId, requirementsVisible));
        startInspectator(minecraft, nextNpc);
    }

    private static void suppressPlayerInput(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keyAttack.setDown(false);
        minecraft.options.keyUse.setDown(false);
        minecraft.options.keyDrop.setDown(false);
        minecraft.options.keyPickItem.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyInventory.setDown(false);
        if (minecraft.player != null && minecraft.player.input != null) {
            minecraft.player.input.keyPresses = Input.EMPTY;
            ((ClientInputAccessor) minecraft.player.input).smartNpc$setMoveVector(Vec2.ZERO);
        }
    }

    private static void renderHoveredItemTooltip(
            GuiGraphicsExtractor guiGraphics,
            Minecraft minecraft,
            int panelX,
            int panelY,
            int screenWidth,
            int screenHeight
    ) {
        int mouseX = (int) (minecraft.mouseHandler.xpos() * screenWidth / minecraft.getWindow().getScreenWidth());
        int mouseY = (int) (minecraft.mouseHandler.ypos() * screenHeight / minecraft.getWindow().getScreenHeight());
        if (requirementsVisible && !cachedRequirementRows.isEmpty()) {
            int requirementsX = Math.max(4, panelX - REQUIREMENTS_PANEL_WIDTH - 8);
            ItemStack hoveredRequirement = getHoveredRequirementItem(requirementsX, panelY, mouseX, mouseY);
            if (!hoveredRequirement.isEmpty()) {
                guiGraphics.setTooltipForNextFrame(minecraft.font, hoveredRequirement, mouseX, mouseY);
                return;
            }
        }

        ItemStack hovered = getHoveredItem(panelX, panelY, mouseX, mouseY);
        if (!hovered.isEmpty()) {
            guiGraphics.setTooltipForNextFrame(minecraft.font, hovered, mouseX, mouseY);
        }
    }

    private static ItemStack getHoveredRequirementItem(int panelX, int panelY, int mouseX, int mouseY) {
        int rowStartY = panelY + requirementHeaderHeight();
        if (mouseX < panelX + 8 || mouseX >= panelX + 26 || mouseY < rowStartY) {
            return ItemStack.EMPTY;
        }

        int rowIndex = (mouseY - rowStartY) / REQUIREMENT_ROW_HEIGHT;
        clampRequirementScrollOffset();
        int rowsToRender = Math.min(visibleRequirementRows(), Math.max(0, cachedRequirementRows.size() - requirementScrollOffset));
        if (rowIndex < 0 || rowIndex >= rowsToRender) {
            return ItemStack.EMPTY;
        }

        return cachedRequirementRows.get(requirementScrollOffset + rowIndex).stack();
    }

    private static ItemStack getHoveredItem(int panelX, int panelY, int mouseX, int mouseY) {
        int mainHandY = panelY + mainHandOffset();
        ItemStack equipment = getHoveredItemInGrid(panelX + 8, mainHandY + 30, 6, 1, mouseX, mouseY, 0);
        if (!equipment.isEmpty()) {
            return equipment;
        }

        return getHoveredItemInGrid(panelX + 8, mainHandY + 64, 9, 3, mouseX, mouseY, 6);
    }

    private static ItemStack getHoveredItemInGrid(
            int x,
            int y,
            int columns,
            int rows,
            int mouseX,
            int mouseY,
            int itemOffset
    ) {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int slotX = x + column * (SLOT_SIZE + 3);
                int slotY = y + row * (SLOT_SIZE + 2);
                if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                    return getSnapshotItem(itemOffset + column + row * columns);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static String spectatorHintKey() {
        return inspectatorActive
                ? "gui.player_npc.inspector.inspectator_active"
                : "gui.player_npc.inspector.inspectator_hint";
    }

    private static void clear() {
        disableTraceIfNeeded();
        stopInspectator(Minecraft.getInstance(), true);
        inspectedEntityId = -1;
        pendingInspectatorTargetTicks = 0;
        snapshot = new ArrayList<>();
        snapshotBuildStatusText = "";
        snapshotPerformanceText = "";
        snapshotDailyJobText = "";
        snapshotRequirementsText = "";
        snapshotAiResourceText = "";
        snapshotTeamInfo = PlayerNpcInspectorPacket.TeamInfo.none();
        snapshotTraceEnabled = false;
        requirementsVisible = false;
        resetRequirementScroll();
        resetRequirementScrollInput();
        lastRefreshGameTime = Long.MIN_VALUE;
        lastDisplayCacheMillis = Long.MIN_VALUE;
        cachedTitle = Component.empty();
        cachedHealthText = Component.empty();
        cachedHealthColor = 0xFF74E291;
        cachedAiText = Component.empty();
        cachedInterestsLines = List.of("");
        cachedTeamLines = List.of("");
        cachedDailyJobText = "";
        cachedBuildStatusText = "";
        cachedPerformanceLines = List.of("");
        cachedAiResourceLines = List.of("");
        cachedAiResourceWrapWidth = -1;
        cachedTraceText = "";
        cachedRequirementsTitle = Component.empty();
        cachedRequirementsLayout = Component.empty();
        cachedRequirementRows = List.of();
        cachedRequirementMore = 0;
        cachedRequirementLines = List.of("");
        cachedTaskLabel = Component.empty();
        cachedTaskLabelWidth = 0;
        cachedTaskValueLines = List.of("");
        cachedMainHandText = Component.empty();
        cachedInspectatorHint = "";
    }

    private static void disableTraceIfNeeded() {
        if (snapshotTraceEnabled && inspectedEntityId >= 0) {
            sendToServerIfConnected(new PlayerNpcGoalTracePacket(inspectedEntityId, false));
        }
        snapshotTraceEnabled = false;
    }

    private static void sendToServerIfConnected(CustomPacketPayload packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }

        NpcPackets.sendToServer(packet);
    }

    private record RequirementPayload(boolean structured, Component layout, List<RequirementRow> rows, int more) {
        private static RequirementPayload fallback() {
            return new RequirementPayload(false, Component.empty(), List.of(), 0);
        }
    }

    private record RequirementRow(ItemStack stack, int required, int placed, int carried, int missing) {
        private int remaining() {
            return Math.max(0, this.required - this.placed);
        }
    }

    public record InspectatorCameraTransform(Vec3 eyePosition, float yRot, float xRot) {}
}
