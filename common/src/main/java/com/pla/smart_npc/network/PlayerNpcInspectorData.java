package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBuildStatusUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcAdaptiveSearchScope;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class PlayerNpcInspectorData {
    private static final int MAX_RESOURCE_HOLDERS = 8;
    private static final int MAX_RESOURCE_TEXT_LENGTH = 96;

    private PlayerNpcInspectorData() {
    }

    public static List<ItemStack> createSnapshot(PlayerNpcEntity playerNpc) {
        SimpleContainer inventory = playerNpc.getInventory();
        List<ItemStack> items = new ArrayList<>(6 + inventory.getContainerSize());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.MAINHAND).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.OFFHAND).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.HEAD).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.CHEST).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.LEGS).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.FEET).copy());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            items.add(inventory.getItem(i).copy());
        }
        return items;
    }

    public static String createBuildStatusText(PlayerNpcEntity playerNpc) {
        return PlayerNpcBuildStatusUtil.describe(playerNpc);
    }

    public static String createDailyJobText(PlayerNpcEntity playerNpc) {
        String selectedJob = playerNpc.getSelectedDailyJobDisplayText();
        long selectedDay = playerNpc.getSelectedDailyJobDay();
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return selectedDay < 0 ? selectedJob : selectedJob + " day " + selectedDay;
        }

        long currentDay = serverLevel.getOverworldClockTime() / 24000L;
        if (selectedDay < 0) {
            return selectedJob + " today " + currentDay;
        }
        return selectedJob
                + " day "
                + selectedDay
                + (selectedDay == currentDay ? "" : " stale, current " + currentDay);
    }

    public static String createBuildRequirementsText(PlayerNpcEntity playerNpc) {
        return PlayerNpcBuildStatusUtil.describeRequirements(playerNpc);
    }

    public static PlayerNpcInspectorPacket.TeamInfo createTeamInfo(PlayerNpcEntity playerNpc) {
        if (!playerNpc.isTeamMember()) {
            return PlayerNpcInspectorPacket.TeamInfo.none();
        }
        String teamName = playerNpc.getTeamName();
        if (playerNpc.isTeamLeader()) {
            return new PlayerNpcInspectorPacket.TeamInfo(
                    teamName,
                    playerNpc.getDisplayName().getString(),
                    PlayerNpcInspectorPacket.TeamRole.LEADER
            );
        }
        LivingEntity leader = PlayerNpcTeamUpManager.resolveLeader(playerNpc);
        // The persisted, leader-named team remains visible even when the leader entity is not.
        // Keep the owner empty so the client can localize an explicit unavailable label.
        String leaderName = leader == null ? "" : leader.getDisplayName().getString();
        PlayerNpcInspectorPacket.TeamRole role = playerNpc.isTeamLeaderPlayer()
                ? PlayerNpcInspectorPacket.TeamRole.PLAYER_FOLLOWER
                : PlayerNpcInspectorPacket.TeamRole.NPC_FOLLOWER;
        return new PlayerNpcInspectorPacket.TeamInfo(teamName, leaderName, role);
    }

    public static String createPerformanceText() {
        return PlayerNpcPerformanceMonitor.createInspectorText();
    }

    public static String createAiResourceText(MinecraftServer server, PlayerNpcEntity selectedNpc) {
        PlayerNpcAiWorkBudget.ResourceSnapshot snapshot = PlayerNpcAiWorkBudget.resourceSnapshot(server);
        PlayerNpcForceTickManager.ForceTickSnapshot forceTicks = PlayerNpcForceTickManager.forceTickSnapshot(server);
        PlayerNpcNaturalSpawnCap.SpawnCapSnapshot spawnCap = PlayerNpcNaturalSpawnCap.snapshot(server);
        PlayerNpcAdaptiveSearchScope.SearchScopeSnapshot searchScope = PlayerNpcAdaptiveSearchScope.snapshot(server);
        List<PlayerNpcAiWorkBudget.ResourceHolder> holders = snapshot.holders();
        StringBuilder text = new StringBuilder(512);
        if (selectedNpc != null) {
            PlayerNpcAiWorkBudget.ResourceHolder selectedHolder = holders.stream()
                    .filter(holder -> holder.npcId().equals(selectedNpc.getUUID()))
                    .findFirst()
                    .orElse(null);
            text.append("Selected: ").append(bounded(selectedNpc.getDisplayName().getString()));
            text.append(selectedHolder == null ? " [no resource]" : " [" + roles(selectedHolder) + "]");
            text.append(PlayerNpcForceTickManager.hasForceTicket(selectedNpc.getUUID())
                    ? " [force ticket]"
                    : " [normal ticking]");
            text.append('\n');
        }
        text.append("Holders ").append(holders.size())
                .append(" | active ").append(snapshot.activeWorkerCount())
                .append(" | running ").append(snapshot.runningWorkerCount())
                .append(" | idle ").append(snapshot.idleWorkerCount())
                .append(" | waiting ").append(snapshot.waitingNpcCount())
                .append(" | limit ").append(snapshot.effectiveWorkerLimit());
        String automaticWorkerStatus = PlayerNpcAiWorkBudget.automaticWorkerLimitStatus(server);
        if (!automaticWorkerStatus.isBlank()) {
            text.append('\n').append("  Worker auto: ").append(automaticWorkerStatus);
        }
        text.append('\n').append("Force tickets ").append(forceTicks.modeText())
                .append(" | used ").append(forceTicks.usedSlots())
                .append('/').append(forceTicks.effectiveSlots())
                .append(" | workers ").append(forceTicks.workerTicketCount())
                .append(" | available ").append(forceTicks.eligibleNpcCount())
                .append(" | known ").append(forceTicks.knownNpcCount());
        if (forceTicks.automatic()) {
            text.append('\n').append("  Force auto: baseline ")
                    .append(String.format(java.util.Locale.ROOT, "%.1fms", forceTicks.baselineMspt()))
                    .append(" | exploration max ").append(forceTicks.capabilityLimit())
                    .append(" | ").append(forceTicks.reason().replace('_', ' '));
            if (forceTicks.handoffProtectionActive()) {
                text.append(" | worker handoff prefetch");
            }
        }
        text.append('\n').append("NPC population ").append(spawnCap.livingCount())
                .append(" | natural max ").append(spawnCap.effectiveLimit())
                .append(spawnCap.automatic() ? " (auto)" : " (fixed)")
                .append(" | loaded ").append(spawnCap.loadedCount())
                .append(" | pending ").append(spawnCap.pendingCount());
        if (spawnCap.automatic()) {
            text.append('\n').append("  Auto: baseline ")
                    .append(String.format(java.util.Locale.ROOT, "%.1fms", spawnCap.baselineMspt()))
                    .append(" | ").append(spawnCap.reason().replace('_', ' '));
            text.append('\n').append("  Limits: advisory forecast ")
                    .append(spawnCap.advisoryForecastLimit() > 0
                            ? Integer.toString(spawnCap.advisoryForecastLimit())
                            : "warming")
                    .append(" | learned safe ").append(spawnCap.learnedSafeLimit())
                    .append(" | exploration max ").append(spawnCap.explorationLimit());
        }
        text.append('\n').append("Search scope: logs ")
                .append(searchScope.logFootprintWidth()).append('x').append(searchScope.logFootprintWidth())
                .append(" | ores radius ").append(searchScope.oreRadius())
                .append(" | build materials radius ").append(searchScope.buildMaterialRadius());
        text.append('\n').append(PlayerNpcPerformanceMonitor.createInspectorText());

        int shown = Math.min(MAX_RESOURCE_HOLDERS, holders.size());
        for (int i = 0; i < shown; i++) {
            PlayerNpcAiWorkBudget.ResourceHolder holder = holders.get(i);
            PlayerNpcEntity npc = resolveHolder(server, holder);
            boolean selected = selectedNpc != null && holder.npcId().equals(selectedNpc.getUUID());
            String name = npc == null ? holder.npcId().toString().substring(0, 8) : npc.getDisplayName().getString();
            String state = npc == null ? "unloaded" : Component.translatable(npc.getCurrentAiState()).getString();
            String detail = npc == null ? "resource retained while target is unavailable" : npc.getCurrentAiDetail();
            if (detail == null || detail.isBlank()) {
                detail = state;
            }
            text.append('\n').append(selected ? "> " : "- ")
                    .append(bounded(name)).append(" [").append(roles(holder)).append(']')
                    .append(" shift ").append(formatShiftRemaining(holder.shiftRemainingTicks()))
                    .append('\n').append("  ").append(bounded(state)).append(" - ").append(bounded(detail));
        }
        if (holders.size() > shown) {
            text.append('\n').append('+').append(holders.size() - shown).append(" more holders");
        }
        return text.toString();
    }

    private static String formatShiftRemaining(long ticks) {
        long seconds = Math.max(0L, ticks / 20L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return minutes + "m" + remainingSeconds + "s";
    }

    private static PlayerNpcEntity resolveHolder(MinecraftServer server, PlayerNpcAiWorkBudget.ResourceHolder holder) {
        if (holder.playerNpc() != null && holder.playerNpc().isAlive()) {
            return holder.playerNpc();
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(holder.npcId()) instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive()) {
                return playerNpc;
            }
        }
        return null;
    }

    private static String roles(PlayerNpcAiWorkBudget.ResourceHolder holder) {
        List<String> roles = new ArrayList<>(3);
        if (holder.worker()) roles.add("worker");
        if (holder.probeTurn()) roles.add("probe");
        if (holder.expensiveSlice()) roles.add("expensive");
        return roles.isEmpty() ? "resource" : String.join("/", roles);
    }

    private static String bounded(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
        return clean.length() <= MAX_RESOURCE_TEXT_LENGTH
                ? clean
                : clean.substring(0, MAX_RESOURCE_TEXT_LENGTH - 3) + "...";
    }
}
