package com.pla.smart_npc.util;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerNpcForceTickManager {
    // One distance-2 region ticket already propagates the center through the surrounding loaded
    // status levels. Anchoring every chunk in a 3x3 square made all nine chunks independent
    // force-tick centers per NPC and scaled badly for distant NPCs.
    private static final int FORCE_TICK_RADIUS_CHUNKS = 0;
    private static final int FORCE_TICK_DISTANCE = 2;
    private static final int TRACKED_NPC_REFRESH_INTERVAL_TICKS = 20;
    private static final int TRACKED_NPC_METADATA_REFRESH_INTERVAL_TICKS = 20 * 5;
    private static final int RESTORED_ENTITY_LOAD_GRACE_TICKS = 20 * 30;
    private static final int AUTO_EVALUATION_INTERVAL_TICKS = 20 * 5;
    private static final double AUTO_HEALTHY_GROWTH_MSPT = 40.0D;
    private static final double AUTO_OVERLOAD_REDUCTION_MSPT = 52.0D;
    private static final int AUTO_HEALTHY_GROWTH_CHECKS = 2;
    // Known population, the routine-worker target, and measured MSPT provide the live bounds.
    // Keep the absolute guard aligned with the supported fixed worker maximum.
    private static final int AUTO_MAX_FORCE_TICK_SLOTS = 64;
    private static final long WORKER_HANDOFF_PREFETCH_TICKS = 20 * 10L;
    private static final String NPC_TAB_PREFIX = "[NPC] ";
    private static final String NPC_TAB_PROFILE_PREFIX = "zzNPC";
    private static final int TAB_PROFILE_NAME_LENGTH = 16;
    private static final TicketType<TicketKey> PLAYER_NPC_TICKET = TicketType.create(
            SmartNpc.MODID + ":player_npc_force_tick",
            Comparator.comparing(TicketKey::npcId).thenComparingLong(TicketKey::chunkLong)
    );
    private static final Map<UUID, ManagedNpc> MANAGED_NPCS = new LinkedHashMap<>();
    @Nullable
    private static Boolean lastEnabled;
    private static int automaticSlotTarget = 1;
    private static final NpcLoadSheddingPolicy LOAD_SHEDDING = new NpcLoadSheddingPolicy();
    private static int automaticCapabilityLimit = 1;
    private static int healthyAutomaticEvaluations;
    private static long lastAutomaticEvaluationTick = Long.MIN_VALUE;
    private static double automaticBaselineMspt;
    private static String automaticReason = "warming_up";
    private static int effectiveForceTickSlots;
    private static int extraSelectionCursor;
    private static long handoffProtectionUntilTick = Long.MIN_VALUE;
    private static int handoffProtectedSlotFloor;

    private PlayerNpcForceTickManager() {
    }

    public static boolean isEnabled() {
        return SmartNpcConfig.isForceTickManageEnabled();
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc
                && event.getLevel() instanceof ServerLevel) {
            track(playerNpc);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc
                && event.getLevel() instanceof ServerLevel) {
            Entity.RemovalReason removalReason = playerNpc.getRemovalReason();
            boolean permanentlyRemoved = !playerNpc.isAlive()
                    || playerNpc.isDeadOrDying()
                    || removalReason == Entity.RemovalReason.KILLED
                    || removalReason == Entity.RemovalReason.DISCARDED;
            release(playerNpc, permanentlyRemoved);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc) {
            release(playerNpc, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!isEnabled() || !(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return;
        }

        // ServerStarted/the first enabled server tick owns restoration and reconciliation. A
        // joining viewer only needs the already-managed tab entries, not another world-wide
        // entity reconciliation and ticket refresh.
        ensureInitialized(server);
        List<ServerPlayer> tabEntries = new ArrayList<>();
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            PlayerNpcEntity npc = managedNpc.resolve(server);
            if (npc != null && npc.level() instanceof ServerLevel level) {
                tabEntries.add(managedNpc.tabPlayer(level, npc));
            }
        }
        if (!tabEntries.isEmpty()) {
            serverPlayer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(tabEntries));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long performanceStartNanos = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();

        MinecraftServer server = event.getServer();
        boolean enabled = isEnabled();
        if (!enabled) {
            if (!MANAGED_NPCS.isEmpty()) {
                releaseAll(server);
            }
            lastEnabled = false;
            PlayerNpcPerformanceMonitor.recordForceManagerTick(performanceStartNanos);
            return;
        }

        if (!Boolean.TRUE.equals(lastEnabled)) {
            restorePersistentTickets(server);
            reconcileLoadedNpcs(server);
            lastEnabled = true;
        }

        updateTrackedNpcs(server);
        rebalanceForceTickets(server);
        PlayerNpcPerformanceMonitor.recordForceManagerTick(performanceStartNanos);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetAutomaticState();
        if (isEnabled()) {
            restorePersistentTickets(event.getServer());
            reconcileLoadedNpcs(event.getServer());
            lastEnabled = true;
            rebalanceForceTickets(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        releaseAll(event.getServer());
        lastEnabled = null;
        resetAutomaticState();
    }

    private static void rebalanceForceTickets(MinecraftServer server) {
        if (server == null || !isEnabled()) {
            effectiveForceTickSlots = 0;
            return;
        }

        PlayerNpcAiWorkBudget.ResourceSnapshot workerSnapshot = PlayerNpcAiWorkBudget.resourceSnapshot(server);
        LinkedHashSet<UUID> workerIds = new LinkedHashSet<>();
        for (PlayerNpcAiWorkBudget.ResourceHolder holder : workerSnapshot.holders()) {
            if (!holder.worker()) {
                continue;
            }
            if (!MANAGED_NPCS.containsKey(holder.npcId()) && holder.playerNpc() != null) {
                track(holder.playerNpc());
            }
            if (MANAGED_NPCS.containsKey(holder.npcId())) {
                workerIds.add(holder.npcId());
            }
        }

        int mode = SmartNpcConfig.getForceTickMode();
        boolean overloaded = PlayerNpcPerformanceMonitor.isAiWorkOverloaded();
        boolean handoffPrefetch = mode < 0 && !overloaded && workerSnapshot.holders().stream()
                .filter(PlayerNpcAiWorkBudget.ResourceHolder::worker)
                .anyMatch(holder -> holder.shiftRemainingTicks() > 0L
                        && holder.shiftRemainingTicks() <= WORKER_HANDOFF_PREFETCH_TICKS);
        if (handoffPrefetch) {
            // The scheduler briefly has no active worker while it rotates the time-1000 roster.
            // Keep both the old holder and its prefetched replacement loaded through that gap so
            // the replacement can request work and receive the new shift before either ticket is
            // reclaimed as a spare.
            if (server.getTickCount() > handoffProtectionUntilTick) {
                handoffProtectedSlotFloor = workerIds.size() + 1;
            } else {
                handoffProtectedSlotFloor = Math.max(handoffProtectedSlotFloor, workerIds.size() + 1);
            }
            handoffProtectionUntilTick = Math.max(handoffProtectionUntilTick, server.getTickCount() + 60L);
        }
        boolean handoffProtectionActive = mode < 0 && !overloaded
                && server.getTickCount() <= handoffProtectionUntilTick;
        if (!handoffProtectionActive) {
            handoffProtectedSlotFloor = 0;
            if (overloaded) {
                handoffProtectionUntilTick = Long.MIN_VALUE;
            }
        }
        int slotLimit;
        if (mode > 0) {
            automaticReason = "fully_enabled";
            automaticBaselineMspt = PlayerNpcPerformanceMonitor.getRollingBaselineMspt();
            slotLimit = MANAGED_NPCS.size();
        } else {
            updateAutomaticSlotTarget(
                    server,
                    workerIds.size(),
                    MANAGED_NPCS.size(),
                    workerSnapshot.effectiveWorkerLimit()
            );
            slotLimit = Math.max(workerIds.size(), automaticSlotTarget);
            if (handoffPrefetch && MANAGED_NPCS.size() > workerIds.size()) {
                // Load one future candidate before the time-1000 worker handoff. The scheduler can
                // then grant that already-ticking NPC the new shift before the old worker's spare
                // ticket is considered for retention.
                slotLimit = Math.max(slotLimit, workerIds.size() + 1);
            }
            if (handoffProtectionActive) {
                slotLimit = Math.max(slotLimit,
                        Math.min(MANAGED_NPCS.size(), handoffProtectedSlotFloor));
            }
            slotLimit = Math.min(MANAGED_NPCS.size(), slotLimit);
        }
        effectiveForceTickSlots = slotLimit;

        LinkedHashSet<UUID> desired = new LinkedHashSet<>();
        desired.addAll(workerIds);
        if (mode > 0) {
            desired.addAll(MANAGED_NPCS.keySet());
        } else {
            // Preserve existing spare holders when possible. Worker changes still take priority:
            // a newly selected worker consumes a slot before any old non-worker can retain it.
            for (ManagedNpc managedNpc : MANAGED_NPCS.values()) {
                if (desired.size() >= slotLimit) {
                    break;
                }
                if (managedNpc.forceTicketSelected) {
                    desired.add(managedNpc.npcId);
                }
            }
            fillAvailableSpareSlots(server, desired, slotLimit);
        }

        // Add replacements before releasing old holders so the worker handoff never creates a
        // moment with no force-ticketed worker chunk.
        for (UUID desiredId : desired) {
            ManagedNpc managedNpc = MANAGED_NPCS.get(desiredId);
            if (managedNpc != null) {
                managedNpc.setForceTicketSelected(server, true);
            }
        }
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            if (!desired.contains(managedNpc.npcId)) {
                managedNpc.setForceTicketSelected(server, false);
            }
        }
    }

    private static void fillAvailableSpareSlots(
            MinecraftServer server,
            LinkedHashSet<UUID> desired,
            int slotLimit
    ) {
        if (desired.size() >= slotLimit || MANAGED_NPCS.isEmpty()) {
            return;
        }
        List<UUID> candidates = new ArrayList<>(MANAGED_NPCS.keySet());
        int candidateCount = candidates.size();
        int start = Math.floorMod(extraSelectionCursor, candidateCount);
        // Grant new spare slots to NPCs that are already loaded first. Persisted unresolved
        // entries remain a bounded bootstrap fallback, allowing one of them to be loaded when no
        // currently ticking candidate is available.
        for (boolean requireLoaded : new boolean[]{true, false}) {
            int examined = 0;
            while (desired.size() < slotLimit && examined < candidateCount) {
                int index = (start + examined) % candidateCount;
                UUID candidate = candidates.get(index);
                ManagedNpc managedNpc = MANAGED_NPCS.get(candidate);
                boolean loaded = managedNpc != null && managedNpc.resolve(server) != null;
                if (!desired.contains(candidate) && loaded == requireLoaded) {
                    desired.add(candidate);
                    extraSelectionCursor = (index + 1) % candidateCount;
                }
                examined++;
            }
        }
    }

    private static void updateAutomaticSlotTarget(
            MinecraftServer server,
            int activeWorkerCount,
            int knownNpcCount,
            int effectiveWorkerLimit
    ) {
        automaticCapabilityLimit = AUTO_MAX_FORCE_TICK_SLOTS;
        int workerPrefetchCeiling = Math.max(1, effectiveWorkerLimit + 1);
        int liveCeiling = Math.max(1, Math.min(knownNpcCount,
                Math.min(automaticCapabilityLimit, workerPrefetchCeiling)));
        automaticSlotTarget = Math.max(1, Math.min(
                Math.max(automaticSlotTarget, activeWorkerCount),
                liveCeiling
        ));

        long tick = server.getTickCount();
        if (!PlayerNpcPerformanceMonitor.hasStableRollingSample()) {
            LOAD_SHEDDING.reset();
            healthyAutomaticEvaluations = 0;
            automaticReason = "warming_up";
            return;
        }
        if (lastAutomaticEvaluationTick != Long.MIN_VALUE
                && tick - lastAutomaticEvaluationTick < AUTO_EVALUATION_INTERVAL_TICKS) {
            return;
        }
        lastAutomaticEvaluationTick = tick;
        automaticBaselineMspt = PlayerNpcPerformanceMonitor.getRollingBaselineMspt();
        double rollingMspt = PlayerNpcPerformanceMonitor.getRollingAverageMspt();

        int protectedWorkerFloor = Math.max(1, activeWorkerCount);
        if (LOAD_SHEDDING.observe(rollingMspt, AUTO_OVERLOAD_REDUCTION_MSPT)) {
            healthyAutomaticEvaluations = 0;
            // The automatic worker controller can now shrink to one. Release spare tickets
            // with it instead of retaining chunks at a stale three-worker floor. A manually
            // fixed worker count remains protected; do not unload an executing worker.
            automaticSlotTarget = protectedWorkerFloor;
            automaticReason = "sustained_overload_worker_floor";
            return;
        }
        if (rollingMspt >= AUTO_OVERLOAD_REDUCTION_MSPT) {
            healthyAutomaticEvaluations = 0;
            automaticReason = "overload_confirmation_pending";
            return;
        }
        if (Math.max(activeWorkerCount, automaticSlotTarget) >= knownNpcCount) {
            healthyAutomaticEvaluations = 0;
            automaticReason = "awaiting_unticketed_candidate";
            return;
        }
        if (automaticSlotTarget >= liveCeiling) {
            healthyAutomaticEvaluations = 0;
            automaticReason = automaticSlotTarget >= automaticCapabilityLimit
                    ? "capability_ceiling"
                    : "worker_prefetch_ceiling";
            return;
        }

        double growthMspt = Math.max(automaticBaselineMspt, rollingMspt);
        if (growthMspt <= AUTO_HEALTHY_GROWTH_MSPT) {
            automaticReason = "healthy_growth_pending";
        } else {
            healthyAutomaticEvaluations = 0;
            automaticReason = "holding_for_headroom";
            return;
        }

        if (++healthyAutomaticEvaluations >= AUTO_HEALTHY_GROWTH_CHECKS) {
            automaticSlotTarget++;
            healthyAutomaticEvaluations = 0;
            automaticReason = "healthy_slot_added";
        }
    }

    private static void resetAutomaticState() {
        LOAD_SHEDDING.reset();
        automaticSlotTarget = 1;
        automaticCapabilityLimit = 1;
        healthyAutomaticEvaluations = 0;
        lastAutomaticEvaluationTick = Long.MIN_VALUE;
        automaticBaselineMspt = 0.0D;
        automaticReason = "warming_up";
        effectiveForceTickSlots = 0;
        extraSelectionCursor = 0;
        handoffProtectionUntilTick = Long.MIN_VALUE;
        handoffProtectedSlotFloor = 0;
    }

    public static ForceTickSnapshot forceTickSnapshot(MinecraftServer server) {
        int mode = SmartNpcConfig.getForceTickMode();
        List<UUID> selected = new ArrayList<>();
        int workerPriorityCount = 0;
        int loadedNpcCount = 0;
        Set<UUID> workerIds = new LinkedHashSet<>();
        if (server != null) {
            for (PlayerNpcAiWorkBudget.ResourceHolder holder
                    : PlayerNpcAiWorkBudget.resourceSnapshot(server).holders()) {
                if (holder.worker()) {
                    workerIds.add(holder.npcId());
                }
            }
        }
        for (ManagedNpc managedNpc : MANAGED_NPCS.values()) {
            if (server != null && managedNpc.resolve(server) != null) {
                loadedNpcCount++;
            }
            if (!managedNpc.forceTicketSelected) {
                continue;
            }
            selected.add(managedNpc.npcId);
            if (workerIds.contains(managedNpc.npcId)) {
                workerPriorityCount++;
            }
        }
        return new ForceTickSnapshot(
                mode,
                mode == 0 ? 0 : effectiveForceTickSlots,
                selected.size(),
                workerPriorityCount,
                loadedNpcCount,
                MANAGED_NPCS.size(),
                automaticCapabilityLimit,
                automaticBaselineMspt,
                mode < 0 ? automaticReason : mode == 0 ? "disabled" : "fully_enabled",
                mode < 0 && server != null && server.getTickCount() <= handoffProtectionUntilTick,
                List.copyOf(selected)
        );
    }

    public static boolean hasForceTicket(UUID npcId) {
        ManagedNpc managedNpc = npcId == null ? null : MANAGED_NPCS.get(npcId);
        return managedNpc != null && managedNpc.forceTicketSelected;
    }

    @SubscribeEvent
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        ManagedNpc managedNpc = MANAGED_NPCS.get(event.getEntity().getUUID());
        if (managedNpc != null) {
            event.setDisplayName(managedNpc.tabDisplayName());
        }
    }

    public static boolean isNpcTabProfileName(String profileName) {
        if (profileName == null
                || profileName.length() != TAB_PROFILE_NAME_LENGTH
                || !profileName.startsWith(NPC_TAB_PROFILE_PREFIX)) {
            return false;
        }
        for (int i = NPC_TAB_PROFILE_PREFIX.length(); i < profileName.length(); i++) {
            if (Character.digit(profileName.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static void track(PlayerNpcEntity npc) {
        if (!isEnabled()
                || npc == null
                || npc.level().isClientSide()
                || !npc.isAlive()
                || npc.isRemoved()
                || !(npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        ManagedNpc managedNpc = MANAGED_NPCS.computeIfAbsent(npc.getUUID(), ManagedNpc::new);
        managedNpc.updateFrom(serverLevel.getServer(), npc);
    }

    public static void release(PlayerNpcEntity npc) {
        release(npc, false);
    }

    private static void release(PlayerNpcEntity npc, boolean removePersistentEntry) {
        if (npc == null || npc.level().isClientSide()) {
            return;
        }

        MinecraftServer server = npc.level().getServer();
        if (server == null) {
            return;
        }
        if (removePersistentEntry) {
            release(npc.getUUID(), server, true);
            return;
        }

        // Chunk unload and dimension transfer are not deletion. Keep the saved center/name so an
        // automatic spare slot can select this NPC again later without requiring a restart.
        ManagedNpc managedNpc = MANAGED_NPCS.get(npc.getUUID());
        if (managedNpc != null) {
            managedNpc.entityId = -1;
            managedNpc.unresolvedTicks = 0;
            managedNpc.broadcastTabRemove(server);
        }
    }

    public static Optional<PlayerNpcEntity> chooseRandomByName(MinecraftServer server, String rawName) {
        if (!isEnabled() || server == null || rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }

        String wantedName = normalizeLookupName(rawName);
        List<PlayerNpcEntity> matches = new ArrayList<>();
        for (PlayerNpcEntity npc : aliveTrackedNpcs(server)) {
            if (normalizeLookupName(displayName(npc)).equals(wantedName)) {
                matches.add(npc);
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(server.overworld().random.nextInt(matches.size())));
    }

    public static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestNpcNames(
            MinecraftServer server,
            SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(availableNpcNames(server), builder);
    }

    public static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestNpcNames(MinecraftServer server, SuggestionsBuilder builder, boolean quoteNames) {
        return SharedSuggestionProvider.suggest(availableNpcNames(server).stream().map(name -> quoteNames ? StringArgumentType.escapeIfRequired(name) : name), builder);
    }

    public static Optional<PlayerNpcEntity> findNextForInspectator(
            MinecraftServer server,
            @Nullable PlayerNpcEntity currentNpc,
            int direction
    ) {
        if (!isEnabled() || server == null) {
            return Optional.empty();
        }

        if (currentNpc != null) {
            track(currentNpc);
        }

        List<PlayerNpcEntity> npcs = aliveTrackedNpcs(server);
        if (npcs.size() < 2) {
            return Optional.empty();
        }

        npcs.sort(Comparator
                .comparing((PlayerNpcEntity npc) -> npc.level().dimension().location().toString())
                .thenComparing(npc -> displayName(npc).toLowerCase(Locale.ROOT))
                .thenComparing(Entity::getUUID));

        int currentIndex = -1;
        if (currentNpc != null) {
            UUID currentId = currentNpc.getUUID();
            for (int i = 0; i < npcs.size(); i++) {
                if (npcs.get(i).getUUID().equals(currentId)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        if (currentIndex < 0) {
            currentIndex = 0;
        }

        int step = direction < 0 ? -1 : 1;
        int nextIndex = Math.floorMod(currentIndex + step, npcs.size());
        return Optional.of(npcs.get(nextIndex));
    }

    public static Optional<PlayerNpcEntity> findTrackedByEntityId(MinecraftServer server, ServerLevel preferredLevel, int entityId) {
        if (!isEnabled() || server == null || entityId < 0) {
            return Optional.empty();
        }

        Entity preferredEntity = preferredLevel.getEntity(entityId);
        if (preferredEntity instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive()) {
            return Optional.of(playerNpc);
        }

        for (PlayerNpcEntity npc : aliveTrackedNpcs(server)) {
            if (npc.getId() == entityId) {
                return Optional.of(npc);
            }
        }
        return Optional.empty();
    }

    public static List<PlayerNpcEntity> aliveTrackedNpcs(MinecraftServer server) {
        List<PlayerNpcEntity> result = new ArrayList<>();
        if (server == null) {
            return result;
        }

        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            PlayerNpcEntity npc = managedNpc.resolve(server);
            if (npc != null) {
                result.add(npc);
            }
        }
        return result;
    }

    public static Set<String> livingNpcNameKeys(MinecraftServer server) {
        if (!isEnabled() || server == null) {
            return Set.of();
        }

        ensureInitialized(server);
        Set<String> result = new LinkedHashSet<>();
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            if (!managedNpc.username.isBlank()) {
                addCombinedNameKeys(result, managedNpc.username);
                continue;
            }

            PlayerNpcEntity npc = managedNpc.resolve(server);
            if (npc != null && npc.hasUsername()) {
                addNameKey(result, npc.getUsername().getSkinName());
                addNameKey(result, npc.getUsername().getDisplayName());
            }
        }
        return Set.copyOf(result);
    }

    public static int livingNpcCount(MinecraftServer server) {
        if (!isEnabled() || server == null) {
            return 0;
        }

        ensureInitialized(server);
        return MANAGED_NPCS.size();
    }

    private static List<String> availableNpcNames(MinecraftServer server) {
        List<String> result = new ArrayList<>();
        for (PlayerNpcEntity npc : aliveTrackedNpcs(server)) {
            String name = displayName(npc);
            if (!result.contains(name)) {
                result.add(name);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static void updateTrackedNpcs(MinecraftServer server) {
        int serverTick = server.getTickCount();
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            if (!managedNpc.isRefreshDue(serverTick)) {
                continue;
            }
            PlayerNpcEntity npc = managedNpc.resolve(server);
            if (npc == null || !npc.isAlive() || npc.isRemoved()) {
                if (!managedNpc.shouldKeepWaitingForEntity(server)) {
                    release(managedNpc.npcId, server, true);
                }
            } else {
                managedNpc.updateFrom(server, npc);
            }
        }
    }

    private static void ensureInitialized(MinecraftServer server) {
        if (Boolean.TRUE.equals(lastEnabled)) {
            return;
        }
        restorePersistentTickets(server);
        reconcileLoadedNpcs(server);
        lastEnabled = true;
    }

    private static void reconcileLoadedNpcs(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == SmartNpcModEntities.PLAYER_NPC.get()
                        && entity instanceof PlayerNpcEntity playerNpc
                        && playerNpc.isAlive()
                        && !playerNpc.isRemoved()) {
                    track(playerNpc);
                }
            }
        }
    }

    private static void restorePersistentTickets(MinecraftServer server) {
        if (!isEnabled()) {
            return;
        }

        for (PlayerNpcForceTickData.Entry entry : PlayerNpcForceTickData.get(server).entries()) {
            ServerLevel level = server.getLevel(entry.levelKey());
            if (level == null) {
                continue;
            }

            ManagedNpc managedNpc = MANAGED_NPCS.computeIfAbsent(entry.npcId(), ManagedNpc::new);
            managedNpc.restoreFromData(server, level, entry.centerChunk(), entry.username());
        }
    }

    private static void release(UUID npcId, MinecraftServer server, boolean removePersistentEntry) {
        ManagedNpc managedNpc = MANAGED_NPCS.remove(npcId);
        if (managedNpc != null) {
            managedNpc.releaseTickets(server);
            managedNpc.broadcastTabRemove(server);
        }

        if (removePersistentEntry) {
            PlayerNpcForceTickData.get(server).remove(npcId);
            PlayerNpcNaturalSpawnCap.onKnownNpcPermanentlyRemoved(server, npcId);
        }
    }

    private static void releaseAll(MinecraftServer server) {
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            managedNpc.releaseTickets(server);
            managedNpc.broadcastTabRemove(server);
        }
        MANAGED_NPCS.clear();
    }

    private static String displayName(PlayerNpcEntity npc) {
        return npc.getName().getString();
    }

    private static String normalizeLookupName(String rawName) {
        String name = rawName.trim();
        if (name.startsWith(NPC_TAB_PREFIX)) {
            name = name.substring(NPC_TAB_PREFIX.length()).trim();
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void addNameKey(Set<String> result, String name) {
        if (name != null && !name.isBlank()) {
            result.add(name.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static void addCombinedNameKeys(Set<String> result, String combinedName) {
        if (combinedName == null || combinedName.isBlank()) {
            return;
        }
        String[] names = combinedName.split(":", 2);
        addNameKey(result, names[0]);
        if (names.length > 1) {
            addNameKey(result, names[1]);
        }
    }

    private static GameProfile createTabProfile(PlayerNpcEntity npc) {
        GameProfile profile = new GameProfile(npc.getUUID(), tabProfileName(npc.getUUID()));
        copyProfileProperties(npc.getProfile(), profile);
        return profile;
    }

    private static void copyProfileProperties(@Nullable GameProfile sourceProfile, GameProfile targetProfile) {
        targetProfile.getProperties().clear();
        if (sourceProfile != null) {
            targetProfile.getProperties().putAll(sourceProfile.getProperties());
        }
    }

    private static String profilePropertiesSignature(@Nullable GameProfile profile) {
        if (profile == null || profile.getProperties().isEmpty()) {
            return "";
        }

        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, com.mojang.authlib.properties.Property> entry : profile.getProperties().entries()) {
            com.mojang.authlib.properties.Property property = entry.getValue();
            entries.add(entry.getKey()
                    + "="
                    + property.getValue()
                    + "|"
                    + Objects.toString(property.getSignature(), ""));
        }
        entries.sort(String::compareTo);
        return String.join(";", entries);
    }

    private static String tabProfileName(UUID npcId) {
        String compactId = npcId.toString().replace("-", "");
        return (NPC_TAB_PROFILE_PREFIX + compactId).substring(0, TAB_PROFILE_NAME_LENGTH);
    }

    private static Set<ChunkPos> forceTickChunksAround(ServerLevel level, ChunkPos center) {
        Set<ChunkPos> result = new LinkedHashSet<>();
        for (int dx = -FORCE_TICK_RADIUS_CHUNKS; dx <= FORCE_TICK_RADIUS_CHUNKS; dx++) {
            for (int dz = -FORCE_TICK_RADIUS_CHUNKS; dz <= FORCE_TICK_RADIUS_CHUNKS; dz++) {
                ChunkPos candidate = new ChunkPos(center.x + dx, center.z + dz);
                // The NPC's center is already loaded during live tracking and must be restored
                // after a restart. Keep a single moving anchor: the distance-2 ticket supplies the
                // loaded navigation fringe without turning every neighbouring chunk into another
                // force-tick center.
                if (candidate.equals(center) || level.hasChunk(candidate.x, candidate.z)) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    public record ForceTickSnapshot(
            int configuredMode,
            int effectiveSlots,
            int usedSlots,
            int workerTicketCount,
            int eligibleNpcCount,
            int knownNpcCount,
            int capabilityLimit,
            double baselineMspt,
            String reason,
            boolean handoffProtectionActive,
            List<UUID> selectedNpcIds
    ) {
        public boolean automatic() {
            return this.configuredMode < 0;
        }

        public String modeText() {
            return this.configuredMode < 0 ? "AUTO" : this.configuredMode == 0 ? "OFF" : "FULL";
        }
    }

    private record TicketKey(UUID npcId, long chunkLong) {
    }

    private static final class ManagedNpc {
        private final UUID npcId;
        private final Set<ChunkPos> forcedChunks = new LinkedHashSet<>();
        @Nullable
        private net.minecraftforge.common.util.FakePlayer tabPlayer;
        @Nullable
        private net.minecraft.resources.ResourceKey<Level> levelKey;
        @Nullable
        private net.minecraft.resources.ResourceKey<Level> tabPlayerLevelKey;
        @Nullable
        private ChunkPos centerChunk;
        private int entityId = -1;
        private String displayName = "";
        private String username = "";
        private String profileSignature = "";
        private int nextMetadataRefreshTick;
        private int unresolvedTicks;
        private boolean tabListed;
        private boolean forceTicketSelected;

        private ManagedNpc(UUID npcId) {
            this.npcId = npcId;
        }

        private boolean isRefreshDue(int serverTick) {
            return Math.floorMod(serverTick, TRACKED_NPC_REFRESH_INTERVAL_TICKS)
                    == Math.floorMod(this.npcId.hashCode(), TRACKED_NPC_REFRESH_INTERVAL_TICKS);
        }

        private void updateFrom(MinecraftServer server, PlayerNpcEntity npc) {
            ServerLevel level = (ServerLevel) npc.level();
            net.minecraft.resources.ResourceKey<Level> currentLevelKey = level.dimension();
            boolean dimensionChanged = this.levelKey == null || !this.levelKey.equals(currentLevelKey);
            if (this.levelKey != null && dimensionChanged) {
                this.removeForceTickets(server);
            }

            ChunkPos nextCenterChunk = npc.chunkPosition();
            String nextUsername = npc.hasUsername() ? npc.getUsername().getCombinedNames() : "";
            boolean centerChanged = !Objects.equals(this.centerChunk, nextCenterChunk);
            boolean persistentStateChanged = dimensionChanged
                    || centerChanged
                    || !Objects.equals(this.username, nextUsername);
            this.levelKey = currentLevelKey;
            boolean entityIdChanged = this.entityId != npc.getId();
            this.entityId = npc.getId();
            this.username = nextUsername;
            this.unresolvedTicks = 0;
            this.centerChunk = nextCenterChunk;

            if (this.forceTicketSelected
                    && (dimensionChanged || centerChanged || this.forcedChunks.isEmpty())) {
                this.refreshForceTickets(level, nextCenterChunk);
            }
            if (persistentStateChanged) {
                PlayerNpcForceTickData.get(server).put(
                        this.npcId,
                        currentLevelKey,
                        nextCenterChunk,
                        this.username
                );
            }

            int serverTick = server.getTickCount();
            if (!this.tabListed || entityIdChanged || serverTick >= this.nextMetadataRefreshTick) {
                this.nextMetadataRefreshTick = serverTick
                        + TRACKED_NPC_METADATA_REFRESH_INTERVAL_TICKS
                        + Math.floorMod(this.npcId.hashCode(), TRACKED_NPC_REFRESH_INTERVAL_TICKS);
                String nextDisplayName = displayName(npc);
                boolean displayNameChanged = !Objects.equals(this.displayName, nextDisplayName);
                this.displayName = nextDisplayName;

                String nextProfileSignature = profilePropertiesSignature(npc.getProfile());
                boolean profileChanged = !Objects.equals(this.profileSignature, nextProfileSignature);
                this.profileSignature = nextProfileSignature;
                this.updateTabList(server, level, npc, displayNameChanged, profileChanged);
            }
        }

        @Nullable
        private PlayerNpcEntity resolve(MinecraftServer server) {
            if (this.levelKey == null) {
                return null;
            }

            ServerLevel level = server.getLevel(this.levelKey);
            if (level == null) {
                return null;
            }

            Entity entity = level.getEntity(this.npcId);
            if (entity instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive() && !playerNpc.isRemoved()) {
                return playerNpc;
            }
            return null;
        }

        private void restoreFromData(MinecraftServer server, ServerLevel level, ChunkPos savedCenterChunk, String savedUsername) {
            net.minecraft.resources.ResourceKey<Level> savedLevelKey = level.dimension();
            if (this.levelKey != null && !this.levelKey.equals(savedLevelKey)) {
                this.removeForceTickets(server);
            }

            this.levelKey = savedLevelKey;
            this.entityId = -1;
            this.username = Objects.requireNonNullElse(savedUsername, "");
            this.unresolvedTicks = 0;
            this.centerChunk = savedCenterChunk;
        }

        private boolean shouldKeepWaitingForEntity(MinecraftServer server) {
            if (this.levelKey == null || this.centerChunk == null) {
                return false;
            }

            ServerLevel level = server.getLevel(this.levelKey);
            if (level == null) {
                return false;
            }

            if (!this.forceTicketSelected) {
                // Deliberately unticketed automatic candidates remain known through SavedData.
                // They may be selected later when MSPT headroom adds a slot or a worker rotates.
                this.unresolvedTicks = 0;
                return true;
            }
            if (this.forcedChunks.isEmpty()) {
                this.refreshForceTickets(level, this.centerChunk);
            }

            this.unresolvedTicks += TRACKED_NPC_REFRESH_INTERVAL_TICKS;
            return this.unresolvedTicks <= RESTORED_ENTITY_LOAD_GRACE_TICKS;
        }

        private void refreshForceTickets(ServerLevel level, ChunkPos nextCenterChunk) {
            if (nextCenterChunk == null) {
                return;
            }
            Set<ChunkPos> nextChunks = forceTickChunksAround(level, nextCenterChunk);
            if (this.forcedChunks.equals(nextChunks)) {
                return;
            }
            for (ChunkPos oldChunk : new ArrayList<>(this.forcedChunks)) {
                if (!nextChunks.contains(oldChunk)) {
                    this.removeTicket(level, oldChunk);
                    this.forcedChunks.remove(oldChunk);
                }
            }
            for (ChunkPos nextChunk : nextChunks) {
                if (!this.forcedChunks.contains(nextChunk)) {
                    this.addTicket(level, nextChunk);
                    this.forcedChunks.add(nextChunk);
                }
            }
        }

        private void setForceTicketSelected(MinecraftServer server, boolean selected) {
            if (this.forceTicketSelected == selected) {
                return;
            }
            this.forceTicketSelected = selected;
            if (!selected) {
                this.removeForceTickets(server);
                return;
            }
            if (this.levelKey == null || this.centerChunk == null) {
                return;
            }
            ServerLevel level = server.getLevel(this.levelKey);
            if (level != null) {
                this.refreshForceTickets(level, this.centerChunk);
            }
        }

        private void addTicket(ServerLevel level, ChunkPos chunkPos) {
            level.getChunkSource().addRegionTicket(
                    PLAYER_NPC_TICKET,
                    chunkPos,
                    FORCE_TICK_DISTANCE,
                    new TicketKey(this.npcId, chunkPos.toLong()),
                    // Level 31 already keeps the center ENTITY_TICKING so the NPC, scheduled
                    // ticks, and ticking block entities continue to run. Forge's forceTicks flag
                    // additionally opts an otherwise-distant chunk into tickChunk/random ticks
                    // and natural spawning. Match vanilla /forceload semantics and do not make
                    // every roaming NPC a synthetic player for chunk-environment work.
                    false
            );
        }

        private void removeTicket(ServerLevel level, ChunkPos chunkPos) {
            level.getChunkSource().removeRegionTicket(
                    PLAYER_NPC_TICKET,
                    chunkPos,
                    FORCE_TICK_DISTANCE,
                    new TicketKey(this.npcId, chunkPos.toLong()),
                    false
            );
        }

        private void releaseTickets(MinecraftServer server) {
            this.forceTicketSelected = false;
            this.removeForceTickets(server);
            this.centerChunk = null;
        }

        private void removeForceTickets(MinecraftServer server) {
            if (this.levelKey == null || this.forcedChunks.isEmpty()) {
                this.forcedChunks.clear();
                return;
            }
            ServerLevel level = server.getLevel(this.levelKey);
            if (level != null) {
                for (ChunkPos chunkPos : new ArrayList<>(this.forcedChunks)) {
                    this.removeTicket(level, chunkPos);
                }
            }
            this.forcedChunks.clear();
        }

        private void updateTabList(
                MinecraftServer server,
                ServerLevel level,
                PlayerNpcEntity npc,
                boolean displayNameChanged,
                boolean profileChanged
        ) {
            if (this.tabListed && !displayNameChanged && !profileChanged) {
                return;
            }
            net.minecraftforge.common.util.FakePlayer fakePlayer = this.tabPlayer(level, npc);
            if (!this.tabListed) {
                server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fakePlayer)));
                this.tabListed = true;
                return;
            }

            if (profileChanged) {
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(this.npcId)));
                server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fakePlayer)));
                this.tabListed = true;
                return;
            }

            if (displayNameChanged) {
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                        fakePlayer
                ));
            }
        }

        private net.minecraftforge.common.util.FakePlayer tabPlayer(ServerLevel level, PlayerNpcEntity npc) {
            if (this.tabPlayer == null || !Objects.equals(this.tabPlayerLevelKey, level.dimension())) {
                this.tabPlayer = FakePlayerFactory.get(level, createTabProfile(npc));
                this.tabPlayerLevelKey = level.dimension();
            }
            copyProfileProperties(npc.getProfile(), this.tabPlayer.getGameProfile());
            this.tabPlayer.latency = 0;
            if (this.tabPlayer.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                this.tabPlayer.setGameMode(GameType.SPECTATOR);
            }
            return this.tabPlayer;
        }

        private void broadcastTabRemove(MinecraftServer server) {
            if (!this.tabListed) {
                return;
            }
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(this.npcId)));
            this.tabListed = false;
        }

        private Component tabDisplayName() {
            return Component.literal(NPC_TAB_PREFIX)
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(this.displayName));
        }
    }
}
