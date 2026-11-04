package com.pla.smart_npc.util;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Fair server-thread scheduler for routine Player NPC work and expensive search/path slices.
 * Worker count is a CPU scheduling limit, not a percentage of allocated memory.
 */
public final class PlayerNpcAiWorkBudget {
    private static final int MAX_EXPENSIVE_BATCHES_PER_TICK = 1;
    private static final long MINECRAFT_DAY_TICKS = 24_000L;
    // Minecraft time 1000 is about 07:00: one in-game hour after dawn/wake-up. Rotating here
    // avoids changing the worker roster in the same tick as sleep skips the night and wakes
    // players, while still giving the selected NPCs effectively the complete working day.
    private static final long WORKER_SHIFT_ROTATION_DAY_TIME = 1_000L;
    private static final int PROBE_REQUEST_STALE_TICKS = 5;
    private static final int EXPENSIVE_REQUEST_STALE_TICKS = 40;
    private static final int DENIAL_VISIBLE_TICKS = 20 * 2;
    // A stopped delegate gets enough time for more than one complete eight-slice selector sweep.
    // Only sustained overload plus real queued demand can end the otherwise day-long worker lease.
    private static final int OVERLOAD_IDLE_WORKER_GRACE_TICKS = 20 * 2;
    private static final double AUTO_TARGET_MSPT = 40.0D;
    private static final int AUTO_EVALUATION_INTERVAL_TICKS = 20 * 5;
    private static final int AUTO_HEALTHY_GROWTH_CHECKS = 2;
    private static final int AUTO_HIGH_HEALTHY_GROWTH_CHECKS = 3;
    private static final double AUTO_REDUCTION_MSPT = 52.0D;
    private static final int AUTO_INITIAL_ROUTINE_WORKERS = 3;
    private static final double AUTO_SEVERE_OVERLOAD_MSPT = 60.0D;
    private static final double AUTO_REDUCTION_MIN_NPC_MSPT = 20.0D;
    private static final double AUTO_REDUCTION_MIN_NPC_SHARE = 0.40D;
    // Demand and measured MSPT control gradual automatic growth. Keep an absolute guard aligned
    // with the config's supported fixed worker maximum.
    private static final int AUTO_MAX_ROUTINE_WORKERS = 64;
    private static final Map<MinecraftServer, SchedulerState> SERVER_SCHEDULERS = new WeakHashMap<>();

    private PlayerNpcAiWorkBudget() {
    }

    public static void clear(MinecraftServer server) {
        if (server != null) {
            SERVER_SCHEDULERS.remove(server);
        }
    }

    /**
     * Gives one NPC a one-tick probe turn. A slot is occupied beyond this tick only if a delegate
     * actually starts, so prerequisite-blocked NPCs cannot reserve the worker window.
     */
    public static boolean canStartWork(PlayerNpcEntity playerNpc, int predicateSlice, int sliceCount) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        SchedulerState scheduler = scheduler(serverLevel);
        return scheduler.canProbe(playerNpc, tick, resolveWorkerLimit(serverLevel.getServer()))
                && scheduler.canEvaluatePredicateSlice(playerNpc.getUUID(), tick, predicateSlice, sliceCount);
    }

    public static boolean canContinueWork(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        return scheduler(serverLevel).canContinue(playerNpc, tick, resolveWorkerLimit(serverLevel.getServer()));
    }

    public static void onWorkStarted(PlayerNpcEntity playerNpc) {
        if (playerNpc.level() instanceof ServerLevel serverLevel) {
            scheduler(serverLevel).workStarted(playerNpc, serverLevel.getServer().getTickCount(), resolveWorkerLimit(serverLevel.getServer()));
        }
    }

    public static void onWorkStopped(PlayerNpcEntity playerNpc) {
        if (playerNpc.level() instanceof ServerLevel serverLevel) {
            scheduler(serverLevel).workStopped(playerNpc, serverLevel.getServer().getTickCount(), resolveWorkerLimit(serverLevel.getServer()));
        }
    }

    public static boolean isWaitingForTurn(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        return scheduler(serverLevel).isWaiting(playerNpc.getUUID(), tick, resolveWorkerLimit(serverLevel.getServer()));
    }

    /**
     * Caps optional waiting-stroll path starts separately from routine worker ownership. Vanilla
     * target selection has already succeeded when this is requested. A denied NPC remains a
     * non-worker; this only prevents multiple visual fallback paths from being created in one tick.
     */
    public static boolean tryAcquireWaitingStrollPathStart(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return scheduler(serverLevel).tryAcquireOptionalPathStart(serverLevel.getServer().getTickCount());
    }

    /**
     * Globally admits one optional synchronous navigation path construction per server tick.
     * Callers retain their target/search cursor when denied and retry on a later tick.
     */
    public static boolean tryAcquireNavigationPathStart(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return scheduler(serverLevel).tryAcquireOptionalPathStart(serverLevel.getServer().getTickCount());
    }

    /**
     * True only while the NPC owns a scheduler worker/probe resource or consumed the current
     * tick's expensive-work slice. Queue membership alone is deliberately not a resource.
     */
    public static boolean hasResource(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        SchedulerState scheduler = SERVER_SCHEDULERS.get(serverLevel.getServer());
        if (scheduler == null) {
            return false;
        }
        scheduler.observeOverworldDayTime(serverLevel.getServer());
        long tick = serverLevel.getServer().getTickCount();
        return scheduler.hasResource(playerNpc.getUUID(), tick, resolveWorkerLimit(serverLevel.getServer()));
    }

    /**
     * Read-only startup-admission check. A one-tick probe counts here so a wrapped goal may run
     * its own {@code canUse()} predicate before {@link #onWorkStarted(PlayerNpcEntity)} promotes
     * that probe to a persistent worker. Do not use this method to authorize equipment changes,
     * navigation, or world mutation; those require {@link #hasActiveWorkerSlot(PlayerNpcEntity)}.
     */
    public static boolean hasWorkerSlot(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        SchedulerState scheduler = SERVER_SCHEDULERS.get(serverLevel.getServer());
        if (scheduler == null) {
            return false;
        }
        scheduler.observeOverworldDayTime(serverLevel.getServer());
        long tick = serverLevel.getServer().getTickCount();
        return scheduler.hasWorkerSlot(playerNpc.getUUID(), tick, resolveWorkerLimit(serverLevel.getServer()));
    }

    /**
     * True only for an NPC that currently owns a persistent worker slot. Startup probes are
     * deliberately excluded: GoalSelector may evaluate an unrelated direct safety goal while a
     * wrapped work goal owns the probe, before that wrapped goal has actually started.
     */
    public static boolean hasActiveWorkerSlot(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        SchedulerState scheduler = SERVER_SCHEDULERS.get(serverLevel.getServer());
        if (scheduler == null) {
            return false;
        }
        scheduler.observeOverworldDayTime(serverLevel.getServer());
        long tick = serverLevel.getServer().getTickCount();
        return scheduler.hasActiveWorkerSlot(
                playerNpc.getUUID(),
                tick,
                resolveWorkerLimit(serverLevel.getServer())
        );
    }

    /** Read-only, bounded view used by commands and diagnostics on the server thread. */
    public static ResourceSnapshot resourceSnapshot(MinecraftServer server) {
        int configuredLimit = SmartNpcConfig.AI_PROCESSING_NPC_LIMIT.get();
        int effectiveLimit = resolveWorkerLimit(server);
        if (server == null) {
            return new ResourceSnapshot(0L, configuredLimit, effectiveLimit, 0, 0, List.of());
        }

        long tick = server.getTickCount();
        SchedulerState scheduler = SERVER_SCHEDULERS.get(server);
        if (scheduler == null) {
            return new ResourceSnapshot(tick, configuredLimit, effectiveLimit, 0, 0, List.of());
        }
        scheduler.observeOverworldDayTime(server);
        return scheduler.snapshot(tick, configuredLimit, effectiveLimit);
    }

    /**
     * Immediately assigns one persistent worker lease to the named NPC. The handoff is still
     * bounded by the ordinary morning shift rotation; this is a manual roster change, not a
     * permanent scheduler pin. When the roster is full, an idle holder is preferred and the
     * oldest deterministic holder is selected within each activity class.
     */
    public static WorkerHandoffResult handoffWorkerResource(
            MinecraftServer server,
            PlayerNpcEntity receiver
    ) {
        int workerLimit = resolveWorkerLimit(server);
        if (server == null
                || receiver == null
                || !receiver.isAlive()
                || receiver.isRemoved()
                || receiver.level().getServer() != server) {
            return new WorkerHandoffResult(
                    WorkerHandoffStatus.UNAVAILABLE,
                    receiver,
                    null,
                    Math.max(0, workerLimit),
                    0L
            );
        }

        SchedulerState scheduler = scheduler(server);
        return scheduler.handoffWorker(receiver, server.getTickCount(), workerLimit);
    }

    public static boolean tryAcquire(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        if (serverLevel == null || playerNpc == null || serverLevel.getServer() == null) {
            return false;
        }

        long tick = serverLevel.getServer().getTickCount();
        SchedulerState scheduler = scheduler(serverLevel);
        boolean admitted = scheduler.tryAcquire(playerNpc, tick, resolveWorkerLimit(serverLevel.getServer()));
        if (admitted) {
            scheduler.clearDenied(playerNpc.getUUID());
        } else if (!scheduler.wasExpensiveAdmittedThisTick(playerNpc.getUUID(), tick)) {
            scheduler.markDenied(playerNpc.getUUID(), tick);
        }
        return admitted;
    }

    private static SchedulerState scheduler(ServerLevel serverLevel) {
        MinecraftServer server = serverLevel.getServer();
        SchedulerState scheduler = SERVER_SCHEDULERS.computeIfAbsent(server, ignored -> new SchedulerState());
        scheduler.observeOverworldDayTime(server);
        return scheduler;
    }

    private static int resolveWorkerLimit(MinecraftServer server) {
        int configured = SmartNpcConfig.AI_PROCESSING_NPC_LIMIT.get();
        if (configured >= 0) {
            return configured;
        }
        return server == null ? 1 : scheduler(server).resolveAutomaticWorkerLimit(server.getTickCount());
    }

    public static String automaticWorkerLimitStatus(MinecraftServer server) {
        if (SmartNpcConfig.AI_PROCESSING_NPC_LIMIT.get() >= 0 || server == null) {
            return "";
        }
        return scheduler(server).automaticWorkerLimitStatus();
    }

    private static SchedulerState scheduler(MinecraftServer server) {
        SchedulerState scheduler = SERVER_SCHEDULERS.computeIfAbsent(server, ignored -> new SchedulerState());
        scheduler.observeOverworldDayTime(server);
        return scheduler;
    }

    private static final class SchedulerState {
        private final Map<UUID, ActiveWorker> activeWorkers = new LinkedHashMap<>();
        private final Map<UUID, Request> waiting = new LinkedHashMap<>();
        private final Set<UUID> probeOwners = new LinkedHashSet<>();
        private final Map<UUID, Boolean> probeDecisions = new LinkedHashMap<>();
        private final Map<UUID, Integer> predicateSlices = new LinkedHashMap<>();
        private final Map<UUID, Long> predicateSliceTicks = new LinkedHashMap<>();
        private final Map<UUID, Long> deniedAtTick = new LinkedHashMap<>();
        private final Map<UUID, Long> expensiveRequestedAtTick = new LinkedHashMap<>();
        private final Map<UUID, Long> expensiveAdmittedAtTick = new LinkedHashMap<>();
        private final Deque<UUID> expensiveQueue = new ArrayDeque<>();
        private long schedulerTick = Long.MIN_VALUE;
        private long observedOverworldDayTime = Long.MIN_VALUE;
        private long nextWorkerShiftRotationDayTime = Long.MIN_VALUE;
        private boolean workerShiftRotationPending;
        private long admissionTick = Long.MIN_VALUE;
        private long waitingStrollAdmissionTick = Long.MIN_VALUE;
        private int admissionsThisTick;
        private int automaticWorkerLimit = AUTO_INITIAL_ROUTINE_WORKERS;
        private final NpcLoadSheddingPolicy loadShedding = new NpcLoadSheddingPolicy();
        private long lastAutomaticEvaluationTick = Long.MIN_VALUE;
        private int healthyWorkerEvaluations;
        private int automaticCapabilityLimit = 1;
        private int healthyWorkerEvaluationsRequired = AUTO_HEALTHY_GROWTH_CHECKS;
        private double automaticBaselineMspt;
        private String automaticWorkerReason = "warming_up";

        private void observeOverworldDayTime(MinecraftServer server) {
            if (server == null || server.overworld() == null) {
                return;
            }

            long dayTime = server.overworld().getOverworldClockTime();
            if (this.observedOverworldDayTime == Long.MIN_VALUE
                    || dayTime < this.observedOverworldDayTime) {
                // A fresh scheduler has no previous roster to rotate. Give its first holders a
                // full shift even when the server starts shortly before the morning threshold.
                // A backwards /time change likewise starts a new, future shift boundary.
                this.nextWorkerShiftRotationDayTime = nextFullDayMorning(dayTime);
                this.workerShiftRotationPending = false;
            } else if (dayTime >= this.nextWorkerShiftRotationDayTime) {
                this.workerShiftRotationPending = true;
                this.nextWorkerShiftRotationDayTime = nextFullDayMorning(dayTime);
            }
            this.observedOverworldDayTime = dayTime;
        }

        private static long nextFullDayMorning(long dayTime) {
            long nextDay = Math.floorDiv(dayTime, MINECRAFT_DAY_TICKS) + 1L;
            return nextDay * MINECRAFT_DAY_TICKS + WORKER_SHIFT_ROTATION_DAY_TIME;
        }

        private int resolveAutomaticWorkerLimit(long tick) {
            int capabilityLimit = AUTO_MAX_ROUTINE_WORKERS;
            this.automaticCapabilityLimit = capabilityLimit;
            this.automaticWorkerLimit = Math.min(this.automaticWorkerLimit, capabilityLimit);
            if (!PlayerNpcPerformanceMonitor.hasStableRollingSample()) {
                this.loadShedding.reset();
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "warming_up";
                return this.automaticWorkerLimit;
            }
            if (this.lastAutomaticEvaluationTick != Long.MIN_VALUE
                    && tick - this.lastAutomaticEvaluationTick < AUTO_EVALUATION_INTERVAL_TICKS) {
                return this.automaticWorkerLimit;
            }
            this.lastAutomaticEvaluationTick = tick;
            this.automaticBaselineMspt = PlayerNpcPerformanceMonitor.getRollingBaselineMspt();
            double configuredTarget = SmartNpcConfig.AI_TARGET_SERVER_MSPT.get();
            double healthyTarget = configuredTarget > 0.0D ? configuredTarget : AUTO_TARGET_MSPT;
            double reductionMspt = Math.min(AUTO_REDUCTION_MSPT, healthyTarget + 12.0D);
            double rollingMspt = PlayerNpcPerformanceMonitor.getRollingAverageMspt();
            double rollingNpcMs = PlayerNpcPerformanceMonitor.getRollingAverageNpcMs();
            double rollingNpcShare = rollingMspt <= 0.0D ? 0.0D : rollingNpcMs / rollingMspt;
            boolean npcOwnedOverload = rollingNpcMs >= AUTO_REDUCTION_MIN_NPC_MSPT
                    && rollingNpcShare >= AUTO_REDUCTION_MIN_NPC_SHARE;
            // Use the full rolling mean: repeated expensive ticks still lose TPS even when a
            // trimmed baseline hides them. Two separated evaluations reject a one-off spike.
            // Severe sustained overload sheds optional work even if other mods own most cost.
            boolean sustainedOverload = this.loadShedding.observe(rollingMspt,
                    npcOwnedOverload ? reductionMspt : AUTO_SEVERE_OVERLOAD_MSPT);
            if (sustainedOverload) {
                this.healthyWorkerEvaluations = 0;
                if (this.automaticWorkerLimit > 1) {
                    this.automaticWorkerLimit = this.estimateSafeWorkerLimit(healthyTarget);
                    this.automaticWorkerReason = "sustained_overload_reduced";
                    // Status/force-ticket queries may evaluate a limit after beginTick already
                    // ran. Retire excess leases now; wrappers stop cleanly at continuation.
                    this.trimWorkers(this.automaticWorkerLimit);
                } else {
                    this.automaticWorkerReason = "overload_minimum_one";
                }
                return this.automaticWorkerLimit;
            }
            if (rollingMspt >= reductionMspt) {
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "overload_growth_paused";
                return this.automaticWorkerLimit;
            }
            if (this.automaticWorkerLimit >= capabilityLimit) {
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "capability_ceiling";
                return this.automaticWorkerLimit;
            }
            if (this.activeWorkers.size() < this.automaticWorkerLimit || this.waiting.isEmpty()) {
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = this.activeWorkers.size() < this.automaticWorkerLimit
                        ? "awaiting_worker_roster_occupancy"
                        : "awaiting_queued_demand";
                return this.automaticWorkerLimit;
            }
            int requiredChecks;
            double growthMspt = Math.max(this.automaticBaselineMspt, rollingMspt);
            if (growthMspt <= healthyTarget) {
                requiredChecks = this.automaticWorkerLimit < 3
                        ? AUTO_HEALTHY_GROWTH_CHECKS
                        : AUTO_HIGH_HEALTHY_GROWTH_CHECKS;
                this.automaticWorkerReason = "healthy_growth_pending";
            } else {
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "holding_above_growth_target";
                return this.automaticWorkerLimit;
            }
            this.healthyWorkerEvaluationsRequired = requiredChecks;
            if (++this.healthyWorkerEvaluations >= requiredChecks) {
                this.automaticWorkerLimit++;
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "stable_headroom_growth";
            }
            return this.automaticWorkerLimit;
        }

        private int estimateSafeWorkerLimit(double targetMspt) {
            return NpcLoadSheddingPolicy.reducedWorkerLimit(this.automaticWorkerLimit,
                    this.activeWorkers.size(), PlayerNpcPerformanceMonitor.getRollingAverageMspt(),
                    PlayerNpcPerformanceMonitor.getRollingAverageNpcMs(), targetMspt);
        }

        private String automaticWorkerLimitStatus() {
            return "limit " + this.automaticWorkerLimit + " | exploration max " + this.automaticCapabilityLimit
                    + " | baseline " + String.format(java.util.Locale.ROOT, "%.1f", this.automaticBaselineMspt)
                    + "ms | " + this.automaticWorkerReason.replace('_', ' ');
        }

        private boolean tryAcquireOptionalPathStart(long tick) {
            if (this.waitingStrollAdmissionTick == tick) {
                return false;
            }
            this.waitingStrollAdmissionTick = tick;
            return true;
        }

        private boolean canProbe(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker activeWorker = this.activeWorkers.get(id);
            if (activeWorker != null && (workerLimit <= 0 || this.workerIndex(id) >= workerLimit)) {
                this.releaseWorker(id);
                activeWorker = null;
            }
            if (activeWorker != null) {
                activeWorker.lastRequestTick = tick;
                this.clearDenied(id);
                return true;
            }
            if (this.probeOwners.contains(id)) {
                this.clearDenied(id);
                return true;
            }

            Boolean cachedDecision = this.probeDecisions.get(id);
            if (cachedDecision != null) {
                return cachedDecision;
            }

            this.updateWaiting(playerNpc, tick);
            int availableProbes = Math.max(0, workerLimit - this.activeWorkers.size());
            if (PlayerNpcPerformanceMonitor.isAiWorkOverloaded()) {
                // Reclaimed slots must not turn into a same-tick burst of several full selector
                // slices. One queued NPC can earn a replacement lease per overloaded tick.
                availableProbes = Math.min(1, availableProbes);
            }
            if (this.probeOwners.size() >= availableProbes || this.waiting.isEmpty()) {
                this.markDenied(id, tick);
                this.probeDecisions.put(id, false);
                return false;
            }

            UUID nextId = this.waiting.keySet().iterator().next();
            if (!id.equals(nextId)) {
                this.markDenied(id, tick);
                this.probeDecisions.put(id, false);
                return false;
            }
            this.waiting.remove(id);
            this.probeOwners.add(id);
            this.probeDecisions.put(id, true);
            this.clearDenied(id);
            return true;
        }

        private boolean canEvaluatePredicateSlice(UUID id, long tick, int requestedSlice, int sliceCount) {
            // Persistent holders must see every compatible predicate on the selector pass. Several
            // gather/explore goals advance retained search state from consecutive canUse calls;
            // slicing a holder stretched each search step across eight selector passes and could
            // leave the worker idle indefinitely. Each goal retains its own scan/path bounds
            // and cadence; worker ownership must not add another competing admission quota.
            if (this.activeWorkers.containsKey(id)) {
                return true;
            }
            int boundedSliceCount = Math.max(1, sliceCount);
            if (this.predicateSliceTicks.getOrDefault(id, Long.MIN_VALUE) != tick) {
                int previousSlice = this.predicateSlices.getOrDefault(
                        id,
                        Math.floorMod(id.hashCode(), boundedSliceCount) - 1
                );
                int nextSlice = Math.floorMod(previousSlice + 1, boundedSliceCount);
                this.predicateSlices.put(id, nextSlice);
                this.predicateSliceTicks.put(id, tick);
            }
            return this.predicateSlices.getOrDefault(id, 0) == Math.floorMod(requestedSlice, boundedSliceCount);
        }

        private boolean canContinue(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker worker = this.activeWorkers.get(id);
            if (worker == null || workerLimit <= 0 || this.workerIndex(id) >= workerLimit) {
                return false;
            }
            worker.lastRequestTick = tick;
            this.clearDenied(id);
            return true;
        }

        private void workStarted(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker worker = this.activeWorkers.get(id);
            if (worker == null) {
                this.probeOwners.remove(id);
                this.waiting.remove(id);
                worker = new ActiveWorker(playerNpc, tick);
                this.activeWorkers.put(id, worker);
            } else {
                worker.playerNpc = playerNpc;
                worker.lastRequestTick = tick;
            }
            worker.runningGoals++;
            worker.idleSinceTick = Long.MIN_VALUE;
            this.clearDenied(id);
        }

        private void workStopped(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker worker = this.activeWorkers.get(id);
            if (worker == null) {
                return;
            }
            worker.runningGoals = Math.max(0, worker.runningGoals - 1);
            if (worker.runningGoals > 0) {
                return;
            }
            if (worker.idleSinceTick == Long.MIN_VALUE) {
                worker.idleSinceTick = tick;
            }
            if (workerLimit <= 0 || this.workerIndex(id) >= workerLimit) {
                this.releaseWorker(id);
                this.updateWaiting(playerNpc, tick);
                return;
            }
            // The NPC-level day shift deliberately survives idle gaps between routine delegates.
            // A cooldown/throttle is not proof that the overall job is complete.
        }

        private boolean tryAcquire(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            if (!this.isScheduled(id)) {
                this.updateWaiting(playerNpc, tick);
                return false;
            }
            if (this.activeWorkers.containsKey(id)) {
                // The worker lease already admits this NPC's routine AI. A second quota here
                // lets earlier eligibility checks consume the allowance before lower-priority
                // supply/exploration goals run, even when those earlier checks find no work.
                // Keep the fair one-batch queue below for probes earning a lease; holders use
                // normal goal arbitration with each goal's bounded scans and retry cadence.
                this.clearDenied(id);
                return true;
            }
            if (this.admissionTick != tick) {
                this.admissionTick = tick;
                this.admissionsThisTick = 0;
            }

            this.pruneExpensiveQueue(tick);
            if (this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick) {
                return false;
            }
            if (!this.expensiveQueue.contains(id)) {
                this.expensiveQueue.addLast(id);
            }
            this.expensiveRequestedAtTick.put(id, tick);
            if (this.admissionsThisTick >= MAX_EXPENSIVE_BATCHES_PER_TICK
                    || !id.equals(this.expensiveQueue.peekFirst())) {
                return false;
            }

            this.expensiveQueue.removeFirst();
            this.expensiveRequestedAtTick.remove(id);
            this.admissionsThisTick++;
            this.expensiveAdmittedAtTick.put(id, tick);
            return true;
        }

        private boolean wasExpensiveAdmittedThisTick(UUID id, long tick) {
            return this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick;
        }

        private boolean isWaiting(UUID id, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            Long deniedTick = this.deniedAtTick.get(id);
            return !this.isScheduled(id)
                    && deniedTick != null
                    && tick - deniedTick <= DENIAL_VISIBLE_TICKS;
        }

        private boolean hasResource(UUID id, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            return this.isScheduled(id)
                    || this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick;
        }

        private boolean hasWorkerSlot(UUID id, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            return this.isScheduled(id);
        }

        private boolean hasActiveWorkerSlot(UUID id, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            return this.activeWorkers.containsKey(id);
        }

        private ResourceSnapshot snapshot(long tick, int configuredLimit, int workerLimit) {
            this.beginTick(tick, workerLimit);
            Set<UUID> holderIds = new LinkedHashSet<>();
            holderIds.addAll(this.activeWorkers.keySet());
            holderIds.addAll(this.probeOwners);
            for (Map.Entry<UUID, Long> entry : this.expensiveAdmittedAtTick.entrySet()) {
                if (entry.getValue() == tick) {
                    holderIds.add(entry.getKey());
                }
            }

            List<ResourceHolder> holders = new ArrayList<>(holderIds.size());
            for (UUID id : holderIds) {
                ActiveWorker worker = this.activeWorkers.get(id);
                holders.add(new ResourceHolder(
                        id,
                        worker == null ? null : worker.playerNpc,
                        worker != null,
                        this.probeOwners.contains(id),
                        this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick,
                        worker == null ? 0 : worker.runningGoals,
                        worker == null ? 0L : Math.max(1L, tick - worker.startedTick + 1L),
                        worker == null ? 0L : this.workerShiftRemainingTicks()
                ));
            }
            return new ResourceSnapshot(
                    tick,
                    configuredLimit,
                    workerLimit,
                    this.activeWorkers.size(),
                    this.waiting.size(),
                    List.copyOf(holders)
            );
        }

        private WorkerHandoffResult handoffWorker(
                PlayerNpcEntity receiver,
                long tick,
                int workerLimit
        ) {
            this.beginTick(tick, workerLimit);
            int boundedLimit = Math.max(0, workerLimit);
            UUID receiverId = receiver.getUUID();
            if (boundedLimit <= 0) {
                return new WorkerHandoffResult(
                        WorkerHandoffStatus.DISABLED,
                        receiver,
                        null,
                        boundedLimit,
                        this.workerShiftRemainingTicks()
                );
            }
            if (this.activeWorkers.containsKey(receiverId)) {
                return new WorkerHandoffResult(
                        WorkerHandoffStatus.ALREADY_HOLDER,
                        receiver,
                        null,
                        boundedLimit,
                        this.workerShiftRemainingTicks()
                );
            }

            Map.Entry<UUID, ActiveWorker> donorEntry = null;
            if (this.activeWorkers.size() >= boundedLimit) {
                donorEntry = this.activeWorkers.entrySet().stream()
                        .filter(entry -> !entry.getKey().equals(receiverId))
                        .min(Comparator
                                .comparingInt((Map.Entry<UUID, ActiveWorker> entry) ->
                                        entry.getValue().runningGoals > 0 ? 1 : 0)
                                .thenComparingLong(entry -> entry.getValue().startedTick)
                                .thenComparing(entry -> entry.getKey().toString()))
                        .orElse(null);
                if (donorEntry == null) {
                    return new WorkerHandoffResult(
                            WorkerHandoffStatus.UNAVAILABLE,
                            receiver,
                            null,
                            boundedLimit,
                            this.workerShiftRemainingTicks()
                    );
                }
            }

            PlayerNpcEntity donor = donorEntry == null ? null : donorEntry.getValue().playerNpc;
            if (donorEntry != null) {
                UUID donorId = donorEntry.getKey();
                this.releaseWorker(donorId);
                if (isUsable(donor)) {
                    // Preserve current waiters and append the displaced holder behind them. Its
                    // running wrapper observes the lost lease on its next continuation check.
                    this.waiting.remove(donorId);
                    this.waiting.put(donorId, new Request(donor, tick));
                    this.markDenied(donorId, tick);
                }
            }

            this.waiting.remove(receiverId);
            this.probeOwners.remove(receiverId);
            this.removeExpensiveRequest(receiverId);
            this.expensiveAdmittedAtTick.remove(receiverId);
            this.activeWorkers.put(receiverId, new ActiveWorker(receiver, tick));
            this.probeDecisions.put(receiverId, true);
            this.clearDenied(receiverId);
            return new WorkerHandoffResult(
                    donor == null ? WorkerHandoffStatus.GRANTED : WorkerHandoffStatus.TRANSFERRED,
                    receiver,
                    donor,
                    boundedLimit,
                    this.workerShiftRemainingTicks()
            );
        }

        private void beginTick(long tick, int workerLimit) {
            if (this.schedulerTick != tick) {
                this.schedulerTick = tick;
                this.probeOwners.clear();
                this.probeDecisions.clear();
                this.prune(tick);
                if (this.workerShiftRotationPending) {
                    this.rotateWorkerShift(tick);
                    this.workerShiftRotationPending = false;
                }
                this.trimWorkers(workerLimit);
                if (workerLimit <= 0) {
                    this.probeOwners.clear();
                }
            }
        }

        private void updateWaiting(PlayerNpcEntity playerNpc, long tick) {
            UUID id = playerNpc.getUUID();
            if (this.activeWorkers.containsKey(id) || this.probeOwners.contains(id)) {
                return;
            }
            Request request = this.waiting.get(id);
            if (request == null) {
                this.waiting.put(id, new Request(playerNpc, tick));
            } else {
                request.playerNpc = playerNpc;
                request.lastRequestTick = tick;
            }
        }

        private int workerIndex(UUID id) {
            int index = 0;
            for (UUID workerId : this.activeWorkers.keySet()) {
                if (workerId.equals(id)) {
                    return index;
                }
                index++;
            }
            return Integer.MAX_VALUE;
        }

        private boolean isScheduled(UUID id) {
            return this.activeWorkers.containsKey(id) || this.probeOwners.contains(id);
        }

        private void prune(long tick) {
            this.waiting.entrySet().removeIf(entry -> !isUsable(entry.getValue().playerNpc)
                    || tick - entry.getValue().lastRequestTick > PROBE_REQUEST_STALE_TICKS);
            List<UUID> expiredWorkers = new ArrayList<>();
            for (Map.Entry<UUID, ActiveWorker> entry : this.activeWorkers.entrySet()) {
                ActiveWorker worker = entry.getValue();
                if (!isUsable(worker.playerNpc)) {
                    expiredWorkers.add(entry.getKey());
                }
            }
            expiredWorkers.forEach(this::releaseWorker);
            this.releaseOverloadedIdleWorkers(tick);
            this.deniedAtTick.entrySet().removeIf(entry -> tick - entry.getValue() > DENIAL_VISIBLE_TICKS);
            this.expensiveAdmittedAtTick.entrySet().removeIf(entry -> tick - entry.getValue() > 1);
            this.predicateSliceTicks.entrySet().removeIf(entry -> tick - entry.getValue() > DENIAL_VISIBLE_TICKS
                    && !this.activeWorkers.containsKey(entry.getKey())
                    && !this.waiting.containsKey(entry.getKey()));
            this.predicateSlices.keySet().removeIf(id -> !this.predicateSliceTicks.containsKey(id));
            this.pruneExpensiveQueue(tick);
        }

        private void releaseOverloadedIdleWorkers(long tick) {
            if (this.waiting.isEmpty() || !PlayerNpcPerformanceMonitor.isAiWorkOverloaded()) {
                return;
            }

            List<ActiveWorker> idleWorkers = new ArrayList<>();
            for (ActiveWorker worker : this.activeWorkers.values()) {
                if (worker.runningGoals <= 0
                        && worker.idleSinceTick != Long.MIN_VALUE
                        && tick - worker.idleSinceTick >= OVERLOAD_IDLE_WORKER_GRACE_TICKS) {
                    idleWorkers.add(worker);
                }
            }
            for (ActiveWorker worker : idleWorkers) {
                UUID id = worker.playerNpc.getUUID();
                this.releaseWorker(id);
                // Existing waiters stay at the front. The released holder rejoins behind them and
                // can earn a later probe instead of losing its daily work opportunity permanently.
                this.waiting.remove(id);
                this.waiting.put(id, new Request(worker.playerNpc, tick));
                this.markDenied(id, tick);
            }
        }

        private void pruneExpensiveQueue(long tick) {
            this.expensiveQueue.removeIf(id -> !this.isScheduled(id)
                    || !this.expensiveRequestedAtTick.containsKey(id)
                    || tick - this.expensiveRequestedAtTick.get(id) > EXPENSIVE_REQUEST_STALE_TICKS);
            this.expensiveRequestedAtTick.keySet().removeIf(id -> !this.expensiveQueue.contains(id));
        }

        private void removeExpensiveRequest(UUID id) {
            this.expensiveQueue.remove(id);
            this.expensiveRequestedAtTick.remove(id);
        }

        private void releaseWorker(UUID id) {
            this.activeWorkers.remove(id);
            this.removeExpensiveRequest(id);
        }

        private void rotateWorkerShift(long tick) {
            if (this.activeWorkers.isEmpty()) {
                return;
            }

            // Existing waiters retain the front of the round-robin queue. Previous holders are
            // appended behind them, so another eligible NPC gets the new day shift whenever one
            // is available. If no one else is eligible, the old holder may naturally reacquire.
            List<Map.Entry<UUID, ActiveWorker>> previousWorkers = new ArrayList<>(this.activeWorkers.entrySet());
            for (Map.Entry<UUID, ActiveWorker> entry : previousWorkers) {
                this.releaseWorker(entry.getKey());
            }
            for (Map.Entry<UUID, ActiveWorker> entry : previousWorkers) {
                UUID id = entry.getKey();
                PlayerNpcEntity playerNpc = entry.getValue().playerNpc;
                if (!isUsable(playerNpc)) {
                    continue;
                }
                this.waiting.remove(id);
                this.waiting.put(id, new Request(playerNpc, tick));
                this.markDenied(id, tick);
            }
        }

        private long workerShiftRemainingTicks() {
            if (this.observedOverworldDayTime == Long.MIN_VALUE
                    || this.nextWorkerShiftRotationDayTime == Long.MIN_VALUE) {
                return 0L;
            }
            return Math.max(0L, this.nextWorkerShiftRotationDayTime - this.observedOverworldDayTime);
        }

        private void trimWorkers(int workerLimit) {
            int limit = Math.max(0, workerLimit);
            int retained = 0;
            List<UUID> excess = new ArrayList<>();
            // When overload lowers the limit, preserve workers that are actually executing a
            // delegate before transition-idle leases, while keeping insertion order within each
            // group. Otherwise the controller can accidentally stop useful work and retain only
            // the idle holders that prompted the reduction.
            for (Map.Entry<UUID, ActiveWorker> entry : this.activeWorkers.entrySet()) {
                if (entry.getValue().runningGoals > 0) {
                    if (retained < limit) {
                        retained++;
                    } else {
                        excess.add(entry.getKey());
                    }
                }
            }
            for (Map.Entry<UUID, ActiveWorker> entry : this.activeWorkers.entrySet()) {
                if (entry.getValue().runningGoals <= 0) {
                    if (retained < limit) {
                        retained++;
                    } else {
                        excess.add(entry.getKey());
                    }
                }
            }
            excess.forEach(this::releaseWorker);
        }

        private void markDenied(UUID id, long tick) {
            this.deniedAtTick.put(id, tick);
        }

        private void clearDenied(UUID id) {
            this.deniedAtTick.remove(id);
        }

        private static boolean isUsable(PlayerNpcEntity playerNpc) {
            return playerNpc != null && playerNpc.isAlive() && !playerNpc.isRemoved();
        }
    }

    private static final class ActiveWorker {
        private PlayerNpcEntity playerNpc;
        private final long startedTick;
        private long lastRequestTick;
        private long idleSinceTick = Long.MIN_VALUE;
        private int runningGoals;

        private ActiveWorker(PlayerNpcEntity playerNpc, long tick) {
            this.playerNpc = playerNpc;
            this.startedTick = tick;
            this.lastRequestTick = tick;
        }
    }

    private static final class Request {
        private PlayerNpcEntity playerNpc;
        private long lastRequestTick;

        private Request(PlayerNpcEntity playerNpc, long tick) {
            this.playerNpc = playerNpc;
            this.lastRequestTick = tick;
        }
    }

    public record ResourceSnapshot(
            long serverTick,
            int configuredWorkerLimit,
            int effectiveWorkerLimit,
            int activeWorkerCount,
            int waitingNpcCount,
            List<ResourceHolder> holders
    ) {
        public boolean automatic() {
            return this.configuredWorkerLimit < 0;
        }

        public long probeCount() {
            return this.holders.stream().filter(ResourceHolder::probeTurn).count();
        }

        public long expensiveCount() {
            return this.holders.stream().filter(ResourceHolder::expensiveSlice).count();
        }

        public long runningWorkerCount() {
            return this.holders.stream()
                    .filter(holder -> holder.worker() && holder.runningGoals() > 0)
                    .count();
        }

        public long idleWorkerCount() {
            return this.holders.stream()
                    .filter(holder -> holder.worker() && holder.runningGoals() <= 0)
                    .count();
        }
    }

    public record ResourceHolder(
            UUID npcId,
            PlayerNpcEntity playerNpc,
            boolean worker,
            boolean probeTurn,
            boolean expensiveSlice,
            int runningGoals,
            long heldTicks,
            long shiftRemainingTicks
    ) {
    }

    public enum WorkerHandoffStatus {
        GRANTED,
        TRANSFERRED,
        ALREADY_HOLDER,
        DISABLED,
        UNAVAILABLE
    }

    public record WorkerHandoffResult(
            WorkerHandoffStatus status,
            PlayerNpcEntity receiver,
            PlayerNpcEntity donor,
            int workerLimit,
            long shiftRemainingTicks
    ) {
        public boolean changed() {
            return this.status == WorkerHandoffStatus.GRANTED
                    || this.status == WorkerHandoffStatus.TRANSFERRED;
        }
    }
}
