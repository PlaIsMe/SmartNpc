package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Population admission for natural Player NPC spawning. This is deliberately independent from
 * routine AI worker ownership and force-ticket management.
 */
@EventBusSubscriber(modid = SmartNpc.MODID)
public final class PlayerNpcNaturalSpawnCap {
    private static final int STARTUP_AUTO_CAP = 4;
    private static final int MAX_EXPLORATION_AUTO_CAP = 16;
    private static final int POLICY_EVALUATION_TICKS = 20 * 5;
    private static final int NORMAL_EVALUATIONS_BEFORE_GROWTH = 3;
    private static final int FAST_EVALUATIONS_BEFORE_GROWTH = 2;
    private static final int FAST_GROWTH_TARGET = 10;
    private static final int RESERVATION_EXPIRY_TICKS = 20 * 10;
    private static final int LOADED_WORLD_SPAWN_INTERVAL_TICKS = 20 * 20;
    private static final double AUTO_PROBE_MAX_MSPT = 45.0D;
    private static final double AUTO_FAST_PROBE_MAX_MSPT = 40.0D;
    private static final double AUTO_REDUCTION_MSPT = 50.0D;
    private static final double MIN_ESTIMATED_NPC_MSPT = 2.5D;
    private static final Map<MinecraftServer, PopulationState> SERVER_STATES = new WeakHashMap<>();

    private PlayerNpcNaturalSpawnCap() {
    }

    public static boolean isNaturalSpawnType(EntitySpawnReason spawnType) {
        return spawnType != EntitySpawnReason.SPAWN_ITEM_USE
                && spawnType != EntitySpawnReason.COMMAND
                && spawnType != EntitySpawnReason.STRUCTURE;
    }

    /** Custom automatic spawning must obey the same world rule as vanilla natural spawning. */
    public static boolean isNaturalSpawningEnabled(ServerLevel level) {
        return level != null && level.getGameRules().get(GameRules.SPAWN_MOBS);
    }

    /**
     * Called only after all other spawn predicates pass. The cached policy is safe to read from
     * world-generation workers and a reservation prevents concurrent candidates overshooting it.
     */
    public static boolean tryReserveNaturalSpawn(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        PopulationState state = state(server);
        return state.tryReserve(server.getTickCount());
    }

    /** Cheap non-reserving preflight; the post-rule reservation remains authoritative. */
    public static boolean mayAttemptNaturalSpawn(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        return state(server).hasCapacity(server.getTickCount());
    }

    /** Binds one successful natural-spawn predicate reservation to the entity being finalized. */
    public static void onNaturalSpawnFinalized(PlayerNpcEntity playerNpc) {
        if (playerNpc == null || playerNpc.level().isClientSide()) {
            return;
        }
        MinecraftServer server = playerNpc.getServer();
        if (server != null) {
            state(server).bindFinalized(playerNpc.getUUID(), server.getTickCount());
        }
    }

    /** Read-only diagnostics; automatic cap calculation itself runs only on the server tick. */
    public static SpawnCapSnapshot snapshot(MinecraftServer server) {
        if (server == null) {
            return SpawnCapSnapshot.empty();
        }
        return state(server).snapshot();
    }

    /** Removes a stale/dead identity discovered by the legacy force-ticket registry. */
    public static void onKnownNpcPermanentlyRemoved(MinecraftServer server, UUID npcId) {
        if (server != null && npcId != null) {
            state(server).onUnloaded(npcId, true);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc
                && event.getLevel() instanceof ServerLevel serverLevel
                && playerNpc.isAlive()
                && !playerNpc.isRemoved()) {
            state(serverLevel.getServer()).onLoaded(playerNpc.getUUID());
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof PlayerNpcEntity playerNpc)
                || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity.RemovalReason removalReason = playerNpc.getRemovalReason();
        boolean permanentlyRemoved = !playerNpc.isAlive()
                || playerNpc.isDeadOrDying()
                || removalReason == Entity.RemovalReason.KILLED
                || removalReason == Entity.RemovalReason.DISCARDED;
        state(serverLevel.getServer()).onUnloaded(playerNpc.getUUID(), permanentlyRemoved);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc
                && playerNpc.level() instanceof ServerLevel serverLevel) {
            state(serverLevel.getServer()).onUnloaded(playerNpc.getUUID(), true);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        PopulationState state = state(server);
        PlayerNpcPopulationData populationData = PlayerNpcPopulationData.get(server);
        Set<UUID> persistedNpcIds = new LinkedHashSet<>(populationData.npcIds());
        if (!populationData.isInitialized()) {
            for (PlayerNpcForceTickData.Entry entry : PlayerNpcForceTickData.get(server).entries()) {
                persistedNpcIds.add(entry.npcId());
            }
            state.markPersistentDirty();
        }
        state.mergePersistent(persistedNpcIds);
        state.restoreLearnedAutoCap(populationData.learnedAutoCap());
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == SmartNpcModEntities.PLAYER_NPC.get()
                        && entity instanceof PlayerNpcEntity playerNpc
                        && playerNpc.isAlive()
                        && !playerNpc.isRemoved()) {
                    state.onLoaded(playerNpc.getUUID());
                }
            }
        }
        state.updatePolicy(server, true);
        state.flushPersistent(server);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        PopulationState state = state(event.getServer());
        state.updatePolicy(event.getServer(), false);
        state.tryLoadedWorldSpawn(event.getServer());
        state.flushPersistent(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PopulationState state;
        synchronized (SERVER_STATES) {
            state = SERVER_STATES.remove(event.getServer());
        }
        if (state != null) {
            state.flushPersistent(event.getServer());
        }
    }

    private static PopulationState state(MinecraftServer server) {
        synchronized (SERVER_STATES) {
            return SERVER_STATES.computeIfAbsent(server, ignored -> new PopulationState());
        }
    }

    private static int explorationCeiling() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        long maxHeapBytes = Math.max(1L, Runtime.getRuntime().maxMemory());
        long heapGiB = Math.max(1L, maxHeapBytes / (1024L * 1024L * 1024L));
        long cpuExplorationLimit = Math.max(1L, (long) processors * 2L);
        long heapExplorationLimit = Math.max(1L, heapGiB * 4L);
        return (int) Math.max(1L, Math.min(
                MAX_EXPLORATION_AUTO_CAP,
                Math.min(cpuExplorationLimit, heapExplorationLimit)
        ));
    }

    private static int advisoryLinearForecastLimit(int explorationLimit, int loadedCount) {
        double averageMspt = PlayerNpcPerformanceMonitor.getRollingAverageMspt();
        double averageNpcMs = PlayerNpcPerformanceMonitor.getRollingAverageNpcMs();
        double nonNpcMs = Math.max(0.0D, averageMspt - averageNpcMs);
        double measuredPerNpcMs = loadedCount <= 0 ? 0.0D : averageNpcMs / loadedCount;
        double estimatedPerNpcMs = Math.max(MIN_ESTIMATED_NPC_MSPT, measuredPerNpcMs);
        int loadLimit = (int) Math.floor(
                Math.max(0.0D, AUTO_PROBE_MAX_MSPT - nonNpcMs) / estimatedPerNpcMs
        );
        return Math.max(1, Math.min(explorationLimit, loadLimit));
    }

    private static final class PopulationState {
        private final Set<UUID> livingNpcIds = new LinkedHashSet<>();
        private final Set<UUID> loadedNpcIds = new LinkedHashSet<>();
        private final Set<UUID> permanentlyRemovedNpcIds = new LinkedHashSet<>();
        private final Deque<Long> unboundReservations = new ArrayDeque<>();
        private final Map<UUID, Long> finalizedPendingJoin = new LinkedHashMap<>();
        private int configuredLimit = SmartNpcConfig.getMaxNaturalPlayerNpcs();
        private int explorationLimit = explorationCeiling();
        private int effectiveLimit = this.configuredLimit >= 0
                ? this.configuredLimit
                : Math.min(STARTUP_AUTO_CAP, this.explorationLimit);
        private int healthyEvaluationCount;
        private int healthyEvaluationsRequired = NORMAL_EVALUATIONS_BEFORE_GROWTH;
        private int learnedSafeLimit;
        private boolean hasSeenStableSample;
        private long lastPolicyEvaluationTick = Long.MIN_VALUE;
        private boolean persistentDirty;
        private double lastAverageMspt;
        private double lastAverageNpcMs;
        private double lastBaselineMspt;
        private int lastAdvisoryForecastLimit;
        private String reason = this.configuredLimit == 0
                ? "disabled"
                : this.configuredLimit > 0 ? "fixed" : "warming_up";
        private String probeMode = "warming";
        private long nextLoadedWorldSpawnTick;

        /**
         * Vanilla CREATURE spawning can remain saturated by ordinary passive mobs, leaving a
         * biome entry visible only during chunk-generation spawning. Make one conservative,
         * reservation-protected attempt in already-loaded terrain; never generate/load a chunk.
         */
        private synchronized void tryLoadedWorldSpawn(MinecraftServer server) {
            ServerLevel level = server.overworld();
            if (!isNaturalSpawningEnabled(level)) {
                return;
            }
            long tick = server.getTickCount();
            if (tick < this.nextLoadedWorldSpawnTick || !this.hasCapacity(tick)) {
                return;
            }
            this.nextLoadedWorldSpawnTick = tick + LOADED_WORLD_SPAWN_INTERVAL_TICKS;
            if (level.isDarkOutside() || level.players().isEmpty()) {
                return;
            }
            ServerPlayer player = level.players().get(level.getRandom().nextInt(level.players().size()));
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
                int distance = 24 + level.getRandom().nextInt(25);
                int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
                int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
                BlockPos column = new BlockPos(x, level.getMinY(), z);
                if (!level.hasChunkAt(column)) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, y, z);
                if (!PlayerNpcEntity.canSpawn(SmartNpcModEntities.PLAYER_NPC.get(), level,
                        EntitySpawnReason.NATURAL, pos, level.getRandom())) {
                    continue;
                }
                PlayerNpcEntity npc = SmartNpcModEntities.PLAYER_NPC.get().create(level, EntitySpawnReason.NATURAL);
                if (npc == null) {
                    return;
                }
                npc.snapTo(x + 0.5D, y, z + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
                if (!level.noCollision(npc)) {
                    return;
                }
                npc.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.NATURAL, null);
                level.addFreshEntityWithPassengers(npc);
                return;
            }
        }

        private synchronized boolean tryReserve(long serverTick) {
            this.prunePending(serverTick);
            if (!this.hasCapacityWithoutPrune()) {
                return false;
            }
            this.unboundReservations.addLast(serverTick);
            return true;
        }

        private synchronized boolean hasCapacity(long serverTick) {
            this.prunePending(serverTick);
            return this.hasCapacityWithoutPrune();
        }

        private boolean hasCapacityWithoutPrune() {
            int occupied = this.livingNpcIds.size()
                    + this.unboundReservations.size()
                    + this.finalizedPendingJoin.size();
            return this.effectiveLimit > 0 && occupied < this.effectiveLimit;
        }

        private synchronized void bindFinalized(UUID npcId, long serverTick) {
            this.prunePending(serverTick);
            if (!this.unboundReservations.isEmpty()) {
                this.unboundReservations.removeFirst();
            }
            if (!this.livingNpcIds.contains(npcId)) {
                this.finalizedPendingJoin.put(npcId, serverTick);
            }
        }

        private synchronized void onLoaded(UUID npcId) {
            this.finalizedPendingJoin.remove(npcId);
            this.permanentlyRemovedNpcIds.remove(npcId);
            this.loadedNpcIds.add(npcId);
            if (this.livingNpcIds.add(npcId)) {
                this.persistentDirty = true;
            }
        }

        private synchronized void onUnloaded(UUID npcId, boolean permanentlyRemoved) {
            this.loadedNpcIds.remove(npcId);
            this.finalizedPendingJoin.remove(npcId);
            if (permanentlyRemoved) {
                this.permanentlyRemovedNpcIds.add(npcId);
                this.livingNpcIds.remove(npcId);
                this.persistentDirty = true;
            }
        }

        private synchronized void mergePersistent(Set<UUID> npcIds) {
            for (UUID npcId : npcIds) {
                if (!this.permanentlyRemovedNpcIds.contains(npcId)) {
                    this.livingNpcIds.add(npcId);
                }
            }
        }

        private synchronized void restoreLearnedAutoCap(int persistedLearnedAutoCap) {
            this.learnedSafeLimit = Math.min(this.explorationLimit, Math.max(0, persistedLearnedAutoCap));
            if (this.configuredLimit < 0) {
                int occupiedRestore = Math.min(this.learnedSafeLimit, this.livingNpcIds.size());
                this.effectiveLimit = Math.min(
                        this.explorationLimit,
                        Math.max(Math.min(STARTUP_AUTO_CAP, this.explorationLimit), occupiedRestore)
                );
            }
        }

        private synchronized void markPersistentDirty() {
            this.persistentDirty = true;
        }

        private synchronized void updatePolicy(MinecraftServer server, boolean force) {
            long serverTick = server.getTickCount();
            this.prunePending(serverTick);
            int nextConfiguredLimit = SmartNpcConfig.getMaxNaturalPlayerNpcs();
            if (nextConfiguredLimit != this.configuredLimit) {
                this.configuredLimit = nextConfiguredLimit;
                this.healthyEvaluationCount = 0;
                if (nextConfiguredLimit < 0) {
                    // Do not inherit untested free capacity when switching from a fixed cap to auto.
                    int occupiedRestore = Math.min(this.learnedSafeLimit, this.livingNpcIds.size());
                    this.effectiveLimit = Math.min(
                            explorationCeiling(),
                            Math.max(Math.min(STARTUP_AUTO_CAP, explorationCeiling()), occupiedRestore)
                    );
                    this.hasSeenStableSample = false;
                }
                force = true;
            }
            this.explorationLimit = explorationCeiling();

            if (this.configuredLimit >= 0) {
                this.effectiveLimit = this.configuredLimit;
                this.reason = this.configuredLimit == 0 ? "disabled" : "fixed";
                this.lastPolicyEvaluationTick = serverTick;
                return;
            }
            if (!force && this.lastPolicyEvaluationTick != Long.MIN_VALUE
                    && serverTick - this.lastPolicyEvaluationTick < POLICY_EVALUATION_TICKS) {
                return;
            }
            this.lastPolicyEvaluationTick = serverTick;

            if (!PlayerNpcPerformanceMonitor.hasStableRollingSample()) {
                if (!this.hasSeenStableSample) {
                    this.effectiveLimit = Math.min(
                            this.effectiveLimit,
                            Math.min(STARTUP_AUTO_CAP, this.explorationLimit)
                    );
                } else {
                    this.effectiveLimit = Math.min(this.effectiveLimit, this.explorationLimit);
                }
                this.healthyEvaluationCount = 0;
                this.lastAverageMspt = PlayerNpcPerformanceMonitor.getRollingAverageMspt();
                this.lastAverageNpcMs = PlayerNpcPerformanceMonitor.getRollingAverageNpcMs();
                this.lastBaselineMspt = PlayerNpcPerformanceMonitor.getRollingBaselineMspt();
                this.lastAdvisoryForecastLimit = 0;
                this.healthyEvaluationsRequired = NORMAL_EVALUATIONS_BEFORE_GROWTH;
                this.probeMode = "warming";
                this.reason = this.hasSeenStableSample ? "sample_warmup_hold" : "warming_up";
                return;
            }

            this.hasSeenStableSample = true;
            this.lastAverageMspt = PlayerNpcPerformanceMonitor.getRollingAverageMspt();
            this.lastAverageNpcMs = PlayerNpcPerformanceMonitor.getRollingAverageNpcMs();
            this.lastBaselineMspt = PlayerNpcPerformanceMonitor.getRollingBaselineMspt();
            int advisoryForecast = advisoryLinearForecastLimit(
                    this.explorationLimit,
                    this.loadedNpcIds.size()
            );
            int observedLivingLoaded = Math.min(this.livingNpcIds.size(), this.loadedNpcIds.size());
            this.lastAdvisoryForecastLimit = advisoryForecast;
            boolean aboveExplorationCeiling = this.effectiveLimit > this.explorationLimit;
            if (aboveExplorationCeiling) {
                this.effectiveLimit = this.explorationLimit;
                this.healthyEvaluationCount = 0;
                this.reason = "exploration_ceiling_reduced";
                this.probeMode = "ceiling";
                return;
            }

            if (this.lastBaselineMspt >= AUTO_REDUCTION_MSPT) {
                this.healthyEvaluationCount = 0;
                this.probeMode = "overload";
                if (observedLivingLoaded <= 0) {
                    this.effectiveLimit = Math.min(this.effectiveLimit, 1);
                    this.lowerLearnedSafeLimit(this.effectiveLimit);
                    this.reason = "server_overload_safe_floor";
                } else if (this.effectiveLimit >= observedLivingLoaded) {
                    // Target one population level below the measured overloaded level. Further
                    // reductions wait until natural attrition actually tests that lower level.
                    this.effectiveLimit = Math.max(1, observedLivingLoaded - 1);
                    this.lowerLearnedSafeLimit(this.effectiveLimit);
                    this.reason = "server_load_reduced";
                } else {
                    this.reason = "overload_population_drain";
                }
                return;
            }

            if (this.lastBaselineMspt > AUTO_PROBE_MAX_MSPT) {
                this.healthyEvaluationCount = 0;
                this.probeMode = "hold";
                if (observedLivingLoaded < this.effectiveLimit) {
                    // Headroom disappeared before the open probe slot was populated; revoke only
                    // that untested capacity and retain every already living/loaded NPC.
                    this.effectiveLimit = Math.max(1, observedLivingLoaded);
                    this.reason = "headroom_probe_retracted";
                } else {
                    this.reason = "headroom_hysteresis_hold";
                }
                return;
            }

            int observedLimit = Math.min(this.explorationLimit, observedLivingLoaded);
            this.markLearnedSafe(observedLimit);
            if (observedLimit > this.effectiveLimit) {
                // This raises the displayed policy only to an already living and loaded population,
                // so it cannot create a free natural-spawn admission slot.
                this.effectiveLimit = observedLimit;
                this.markLearnedSafe(this.effectiveLimit);
                this.healthyEvaluationCount = 0;
                this.reason = "observed_population_sync";
                return;
            }

            boolean fastProbe = this.lastBaselineMspt <= AUTO_FAST_PROBE_MAX_MSPT
                    && this.effectiveLimit < FAST_GROWTH_TARGET;
            this.healthyEvaluationsRequired = fastProbe
                    ? FAST_EVALUATIONS_BEFORE_GROWTH
                    : NORMAL_EVALUATIONS_BEFORE_GROWTH;
            this.probeMode = fastProbe ? "fast_plus_2" : "normal_plus_1";

            if (this.effectiveLimit < this.explorationLimit
                    && this.livingNpcIds.size() >= this.effectiveLimit
                    && this.loadedNpcIds.size() >= this.effectiveLimit) {
                this.healthyEvaluationCount++;
                if (this.healthyEvaluationCount >= this.healthyEvaluationsRequired) {
                    int growthStep = fastProbe ? 2 : 1;
                    int growthCeiling = fastProbe
                            ? Math.min(FAST_GROWTH_TARGET, this.explorationLimit)
                            : this.explorationLimit;
                    this.effectiveLimit = Math.min(growthCeiling, this.effectiveLimit + growthStep);
                    this.healthyEvaluationCount = 0;
                    this.reason = "stable_headroom_growth";
                } else {
                    this.reason = "stable_headroom_pending";
                }
                return;
            }
            this.healthyEvaluationCount = 0;
            if (this.effectiveLimit >= this.explorationLimit) {
                this.reason = "exploration_ceiling";
            } else if (this.livingNpcIds.size() < this.effectiveLimit) {
                this.reason = "awaiting_population";
            } else {
                this.reason = "awaiting_loaded_population";
            }
        }

        private synchronized void flushPersistent(MinecraftServer server) {
            if (!this.persistentDirty) {
                return;
            }
            PlayerNpcPopulationData.get(server).replace(
                    Set.copyOf(this.livingNpcIds),
                    this.learnedSafeLimit
            );
            this.persistentDirty = false;
        }

        private void markLearnedSafe(int testedLimit) {
            int bounded = Math.min(this.explorationLimit, Math.max(0, testedLimit));
            if (bounded > this.learnedSafeLimit) {
                this.learnedSafeLimit = bounded;
                this.persistentDirty = true;
            }
        }

        private void lowerLearnedSafeLimit(int reducedLimit) {
            int bounded = Math.max(0, reducedLimit);
            if (this.learnedSafeLimit > bounded) {
                this.learnedSafeLimit = bounded;
                this.persistentDirty = true;
            }
        }

        private synchronized SpawnCapSnapshot snapshot() {
            return new SpawnCapSnapshot(
                    this.configuredLimit,
                    this.effectiveLimit,
                    this.livingNpcIds.size(),
                    this.loadedNpcIds.size(),
                    this.unboundReservations.size() + this.finalizedPendingJoin.size(),
                    this.explorationLimit,
                    this.lastAdvisoryForecastLimit,
                    this.lastAverageMspt,
                    this.lastAverageNpcMs,
                    this.lastBaselineMspt,
                    this.healthyEvaluationCount,
                    this.healthyEvaluationsRequired,
                    this.learnedSafeLimit,
                    this.probeMode,
                    this.reason
            );
        }

        private void prunePending(long serverTick) {
            while (!this.unboundReservations.isEmpty()
                    && serverTick - this.unboundReservations.peekFirst() > RESERVATION_EXPIRY_TICKS) {
                this.unboundReservations.removeFirst();
            }
            this.finalizedPendingJoin.entrySet().removeIf(
                    entry -> serverTick - entry.getValue() > RESERVATION_EXPIRY_TICKS
            );
        }
    }

    public record SpawnCapSnapshot(
            int configuredLimit,
            int effectiveLimit,
            int livingCount,
            int loadedCount,
            int pendingCount,
            int explorationLimit,
            int advisoryForecastLimit,
            double averageMspt,
            double averageNpcMs,
            double baselineMspt,
            int healthyEvaluationCount,
            int healthyEvaluationsRequired,
            int learnedSafeLimit,
            String probeMode,
            String reason
    ) {
        private static SpawnCapSnapshot empty() {
            return new SpawnCapSnapshot(
                    0, 0, 0, 0, 0, 0, 0, 0.0D, 0.0D, 0.0D,
                    0, 0, 0, "unavailable", "unavailable"
            );
        }

        public boolean automatic() {
            return this.configuredLimit < 0;
        }
    }
}
