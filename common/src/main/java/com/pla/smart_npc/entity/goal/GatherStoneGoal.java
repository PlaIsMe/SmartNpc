package com.pla.smart_npc.entity.goal;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.StoneAi;
import com.pla.smart_npc.entity.ai.StoneAi.StoneCluster;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.entity.ai.WaterEscapeAi;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class GatherStoneGoal extends Goal implements GatheringGoal {
    private static final Set<PlayerNpcEntity> ACTIVE_STONE_GATHERERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<PlayerNpcEntity, NearbyStoneTargetCache> NEARBY_STONE_TARGET_CACHE = new WeakHashMap<>();
    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_GATHER_TICKS = 20 * 120;
    private static final int REQUIRED_BREAK_TICKS = 70;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final double BREAK_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MAX_STONE_PATH_CHECKS = 12;
    private static final int MAX_TARGET_SELECTION_CHECKS = 8;
    // Path construction dominates failed underground selection. Limit every admitted selector
    // slice to one navigation search; retries continue discovery on later ticks.
    private static final int MAX_TARGET_SELECTION_NAVIGATION_PATHS = 1;
    private static final int MAX_NEARBY_STONE_NAVIGATION_PATHS = 1;
    private static final int MAX_SINGLE_STAND_NAVIGATION_PATHS = 1;
    private static final int NEARBY_STONE_TARGET_CACHE_TICKS = 20 * 2;
    private static final double NEARBY_STONE_TARGET_CACHE_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int PHASE_RECHECK_INTERVAL_TICKS = 20;
    private static final int DETAIL_PROGRESS_REFRESH_INTERVAL_TICKS = 5;
    private static final int MAX_STAND_SAFE_DROP_BLOCKS = 3;
    private static final int DIG_SITE_STONE_SCAN_BELOW = 4;
    private static final int CLEAR_OBSTRUCTION_TICKS = 24;
    private static final double CLEAR_OBSTRUCTION_DISTANCE_SQR = 5.0D * 5.0D;
    private static final double STAND_REACHED_HORIZONTAL_SQR = 0.9D * 0.9D;
    private static final int MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR = 2;
    private static final int MAX_DESCENDING_ACCESS_STEPS = 12;
    private static final int ACCESS_RETRY_TICKS = 20;
    private static final int MAX_FAILED_ACCESS_ATTEMPTS_BEFORE_RESELECT = 8;
    private static final int FORCED_ACCESS_RADIUS = 4;
    private static final int FORCED_ACCESS_DOWN = 5;
    private static final int FORCED_ACCESS_UP = 2;
    private static final double FORCED_CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final int FAILED_TARGET_SKIP_TICKS = 20 * 12;
    private static final int STONE_ACCESS_CLEAR_GRACE_TICKS = 20 * 4;
    private static final int FAILED_ACCESS_CLEAR_SKIP_TICKS = 20 * 30;
    private static final int HOME_EGRESS_RADIUS = 4;
    private static final int HOME_EGRESS_VERTICAL_DOWN = 4;
    private static final int HOME_EGRESS_VERTICAL_UP = 3;
    private static final int HOME_EGRESS_RANDOM_POOL = 6;
    private static final int HOME_EGRESS_PATH_CHECKS = 1;
    private static final int HOME_STONE_TARGET_BUFFER = 4;
    private static final int HOME_STONE_TARGET_MAX_Y_OFFSET = 1;
    private static final double HOME_EGRESS_REACHED_DISTANCE_SQR = 1.1D * 1.1D;
    private static final int STONE_SAFE_STAND_EGRESS_MIN_RADIUS = 4;
    private static final int STONE_SAFE_STAND_EGRESS_MAX_RADIUS = 5;
    private static final int STONE_SAFE_STAND_EGRESS_VERTICAL_DOWN = 2;
    private static final int STONE_SAFE_STAND_EGRESS_VERTICAL_UP = 2;
    private static final int STONE_SAFE_STAND_EGRESS_RANDOM_POOL = 8;
    private static final int STONE_SAFE_STAND_EGRESS_PATH_CHECKS = 1;
    private static final int STONE_SAFE_STAND_REACH_TIMEOUT_TICKS = 20 * 10;
    private static final int STONE_EGRESS_REPATH_INTERVAL_TICKS = 20;
    private static final int FAILED_STONE_SAFE_STAND_SKIP_TICKS = 20 * 12;
    private static final int STONE_WORK_NO_PROGRESS_TICKS = 20 * 5;
    private static final double STONE_WORK_PROGRESS_EPSILON = 0.5D;
    private static final int IDLE_DIAGNOSTIC_TICKS = 20 * 8;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos targetPos;
    private BlockPos standPos;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final WaterEscapeAi waterEscapeAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final Deque<BlockPos> stoneQueue = new ArrayDeque<>();
    private int gatherTicks;
    private int repathTicks;
    private int standRouteAttempts;
    private int failedAccessAttempts;
    private int nextContinueEligibilityCheckTick;
    private boolean clearedAccessForTarget;
    private BlockPos stoneEgressPos;
    private BlockPos stoneEgressWatchPos;
    private int stoneEgressReachTicks;
    private int nextStoneEgressPathAttemptTick;
    private BlockPos temporarilyBlockedTarget;
    private BlockPos stoneWorkProgressTarget;
    private long temporarilyBlockedTargetUntilTick;
    private int lastStoneWorkProgressCount;
    private int stoneWorkNoProgressTicks;
    private double bestStoneWorkDistance = Double.MAX_VALUE;
    private String lastPhaseDiagnostic = "";
    private int lastDetailMode = -1;
    private int nextDetailProgressRefreshTick;
    private int lastExpensiveWorkAdmissionTick = Integer.MIN_VALUE;
    private BlockPos lastDetailSubject;
    private final Set<BlockPos> skippedAccessClearBlocks = new HashSet<>();
    private final Map<BlockPos, Long> temporarilyBlockedStoneEgress = new HashMap<>();
    private final Map<BlockPos, Long> temporarilyBlockedAccessClears = new HashMap<>();

    public GatherStoneGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.waterEscapeAi = new WaterEscapeAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasNearbyStoneTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!isStoneSupplyPhaseActive(playerNpc, serverLevel)) {
            return false;
        }
        BlockPos feet = playerNpc.blockPosition();
        String dimension = serverLevel.dimension().identifier().toString();
        NearbyStoneTargetCache cached = NEARBY_STONE_TARGET_CACHE.get(playerNpc);
        if (cached != null
                && playerNpc.tickCount < cached.expiresAtTick()
                && cached.dimension().equals(dimension)
                && cached.origin().distSqr(feet) <= NEARBY_STONE_TARGET_CACHE_DISTANCE_SQR) {
            return cached.result();
        }

        boolean result = findStoneTarget(playerNpc, serverLevel, SEARCH_RADIUS).isPresent();
        NEARBY_STONE_TARGET_CACHE.put(playerNpc, new NearbyStoneTargetCache(
                feet.immutable(),
                dimension,
                playerNpc.tickCount + NEARBY_STONE_TARGET_CACHE_TICKS,
                result
        ));
        return result;
    }

    /**
     * Read-only arbitration result published by the authoritative higher-priority stone goal.
     * DigDown uses this instead of launching a duplicate StoneAi scan in the same selector pass.
     */
    public static boolean hasCachedNearbyStoneTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return false;
        }
        NearbyStoneTargetCache cached = NEARBY_STONE_TARGET_CACHE.get(playerNpc);
        return cached != null
                && cached.result()
                && playerNpc.tickCount < cached.expiresAtTick()
                && cached.dimension().equals(serverLevel.dimension().identifier().toString())
                && cached.origin().distSqr(playerNpc.blockPosition()) <= NEARBY_STONE_TARGET_CACHE_DISTANCE_SQR;
    }

    private static void cacheNearbyStoneTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            boolean result
    ) {
        NEARBY_STONE_TARGET_CACHE.put(playerNpc, new NearbyStoneTargetCache(
                playerNpc.blockPosition().immutable(),
                serverLevel.dimension().identifier().toString(),
                playerNpc.tickCount + NEARBY_STONE_TARGET_CACHE_TICKS,
                result
        ));
    }

    public static boolean isStoneSupplyPhaseActive(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || !hasPickaxe(playerNpc)) {
            return false;
        }

        return isStoneSupplyPhaseActiveWithAvailablePickaxe(playerNpc, serverLevel);
    }

    private static boolean isStoneSupplyPhaseActiveWithAvailablePickaxe(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || !hasEnoughLogsForStonePhase(playerNpc)) {
            return false;
        }

        boolean fishingSupportJob = isFishingSupportJob(playerNpc);
        boolean farmingSupportJob = isFarmingSupportJob(playerNpc);
        boolean exploringSupplyJob = isExploringSupplyJob(playerNpc);
        if (!fishingSupportJob
                && !farmingSupportJob
                && !exploringSupplyJob
                && !hasPreparedBaseForStone(playerNpc, serverLevel)) {
            return false;
        }

        boolean miningJob = isMiningJobActive(playerNpc);
        boolean needsCurrentBuildStone = PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, playerNpc);
        boolean unresolvedActiveBuildNeed = isStoneGatheringEpisodeActive(playerNpc)
                && PlayerNpcBuildMaterialUtil.isMissingBuildMaterialSearchPending(playerNpc);
        return fishingSupportJob && playerNpc.shouldPrioritizeCobblestoneGathering()
                || farmingSupportJob && FarmAi.needsFarmStone(playerNpc, serverLevel)
                || exploringSupplyJob && playerNpc.shouldPrioritizeCobblestoneGathering()
                || miningJob && playerNpc.shouldPrioritizeCobblestoneGathering()
                || !miningJob
                && !fishingSupportJob
                && !farmingSupportJob
                && !exploringSupplyJob
                && (playerNpc.shouldPrioritizeCobblestoneGathering()
                || needsCurrentBuildStone
                || unresolvedActiveBuildNeed);
    }

    public static boolean isMiningJobActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null && playerNpc.isDailyJobActive(PlayerNpcInterest.MINING);
    }

    public static boolean isFishingSupportJob(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING)
                && !playerNpc.hasInterest(PlayerNpcInterest.BUILDING);
    }

    public static boolean isStoneGatheringEpisodeActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null && ACTIVE_STONE_GATHERERS.contains(playerNpc);
    }

    private static void setStoneGatheringEpisodeActive(PlayerNpcEntity playerNpc, boolean active) {
        if (playerNpc == null) {
            return;
        }
        if (active) {
            ACTIVE_STONE_GATHERERS.add(playerNpc);
        } else {
            ACTIVE_STONE_GATHERERS.remove(playerNpc);
        }
    }

    public static boolean isFarmingSupportJob(PlayerNpcEntity playerNpc) {
        return playerNpc != null && playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING);
    }

    public static boolean isExploringSupplyJob(PlayerNpcEntity playerNpc) {
        return playerNpc != null && playerNpc.isDailyJobActive(PlayerNpcInterest.EXPLORING);
    }

    private static boolean hasEnoughLogsForStonePhase(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && !playerNpc.shouldPrioritizeLogGathering()
                && (!(playerNpc.level() instanceof ServerLevel serverLevel)
                || !FarmAi.needsFarmLogs(playerNpc, serverLevel));
    }

    @Override
    public boolean canUse() {
        boolean continuingStoneAccess = this.playerNpc.isStoneAccessClearing();
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || (!continuingStoneAccess && this.playerNpc.getGatherCooldown() > 0)) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            return false;
        }
        if (this.shouldYieldToFarmCropWork(serverLevel)) {
            this.setStoneDiagnostic("stone deferred: actionable owned farm crop work");
            return false;
        }
        if (CraftBasicGearGoal.shouldPrioritizeGearCrafting(this.playerNpc, serverLevel)) {
            this.setStoneDiagnostic("stone blocked: gear crafting priority");
            return false;
        }
        boolean miningJob = isMiningJobActive(this.playerNpc);
        if (this.shouldStayHomeForWeather(serverLevel) && !miningJob
                || !isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                || (!this.playerNpc.isStoneAccessClearing()
                && BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel)
                && !PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, this.playerNpc))) {
            return false;
        }

        if (!this.tryAcquireExpensiveWork(serverLevel)) {
            return false;
        }

        boolean selected = this.selectTarget(serverLevel);
        cacheNearbyStoneTarget(this.playerNpc, serverLevel, selected);
        return selected;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.targetPos == null && this.stoneEgressPos == null) {
            return this.traceStoneStop("no target or safe stand");
        }
        if (this.gatherTicks >= MAX_GATHER_TICKS) {
            return this.traceStoneStop("max ticks target=" + posText(this.targetPos));
        }
        if (!this.playerNpc.isAlive()) {
            return this.traceStoneStop("npc dead");
        }
        if (this.playerNpc.getTarget() != null) {
            return this.traceStoneStop("combat target active");
        }
        if (this.playerNpc.getUpwardEscapeTarget() != null) {
            return this.traceStoneStop("upward escape target active");
        }
        if (this.playerNpc.getHoleEscapeCooldown() > 0) {
            return this.traceStoneStop("hole cooldown=" + this.playerNpc.getHoleEscapeCooldown());
        }
        if (!this.toolAi.hasTool(ItemTags.PICKAXES)) {
            return this.traceStoneStop("missing pickaxe");
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return this.traceStoneStop("not server level");
        }
        // GoalSelector evaluates continuation every tick. Owned-farm scans and supply/build phase
        // checks are cached for one second; immediate death, combat, tool and escape guards above
        // stay cheap and responsive.
        if (this.playerNpc.tickCount >= this.nextContinueEligibilityCheckTick) {
            this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                    + PHASE_RECHECK_INTERVAL_TICKS;
            if (this.shouldYieldToFarmCropWork(serverLevel)) {
                return this.traceStoneStop("actionable owned farm crop work");
            }
            if (MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
                return this.traceStoneStop("night camp pause night=" + serverLevel.isDarkOutside()
                        + " thunder=" + serverLevel.isThundering()
                        + " sky=" + serverLevel.canSeeSky(this.playerNpc.blockPosition().above()));
            }
            if (!this.canContinueStoneWork(serverLevel)) {
                return this.traceStoneStop("phase inactive " + this.lastPhaseDiagnostic);
            }
        }
        return true;
    }

    private boolean shouldYieldToFarmCropWork(ServerLevel serverLevel) {
        return isFarmingSupportJob(this.playerNpc)
                && FarmCropGoal.hasActionableOwnedFarmWork(this.playerNpc, serverLevel);
    }

    @Override
    public void start() {
        this.playerNpc.beginStoneSupplyGatheringEpisode();
        setStoneGatheringEpisodeActive(this.playerNpc, true);
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.standRouteAttempts = 0;
        this.failedAccessAttempts = 0;
        this.resetStoneWorkProgressMonitor();
        this.lastPhaseDiagnostic = "";
        this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                + PHASE_RECHECK_INTERVAL_TICKS;
        this.nextStoneEgressPathAttemptTick = this.playerNpc.tickCount;
        this.clearedAccessForTarget = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_stone");
        this.toolAi.equipTool(ItemTags.PICKAXES);
        this.resetDetailRefresh();
        this.updateDetail();
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            if (!this.handleUnsafeStandBeforeStone(serverLevel)) {
                this.moveToStandPos(serverLevel);
            }
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.gatherTicks++;
        if (this.tickWaterEscape(serverLevel)) {
            return;
        }

        this.tickSafeStoneStandReachTimeout(serverLevel);

        if (this.stoneEgressPos != null) {
            if (this.moveToSafeStoneStand(serverLevel, true)) {
                this.updateDetail();
                return;
            }
            this.resetStoneWorkProgressMonitor();
        }

        if (isUnsafeStoneWorkLocation(this.playerNpc, serverLevel, this.playerNpc.blockPosition())
                && !this.tryAcquireExpensiveWork(serverLevel)) {
            return;
        }
        if (this.handleUnsafeStandBeforeStone(serverLevel)) {
            return;
        }

        if (this.recoverIfStoneWorkStuck(serverLevel)) {
            return;
        }

        if (this.tickClearBlock(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.targetPos == null || !this.isValidTarget(serverLevel, this.targetPos)) {
            this.setStoneDiagnostic("stone target invalid target=" + posText(this.targetPos)
                    + " state=" + blockStateText(serverLevel, this.targetPos));
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            if (!this.tryAcquireExpensiveWork(serverLevel)) {
                this.setStoneDiagnostic("stone reselection queued for shared search slice");
                return;
            }
            if (!this.selectTarget(serverLevel)) {
                this.targetPos = null;
            }
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (!this.isAtMiningStand(serverLevel)) {
            this.breakingBlockAi.stop();
            if (this.shouldRetryPathWork() && this.tryAcquireExpensiveWork(serverLevel)) {
                boolean navigationEnded = this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck();
                if (navigationEnded
                        && this.standRouteAttempts >= MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR
                        && this.startClearingRoute(serverLevel)) {
                    this.standRouteAttempts = 0;
                    this.repathTicks = REPATH_INTERVAL_TICKS;
                    this.updateDetail();
                    return;
                }

                boolean moved = this.moveToStandPos(serverLevel);
                if (!moved) {
                    if (this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    if (this.recordAccessFailureAndShouldReselect(serverLevel)) {
                        this.recoverFromBlockedStoneAccess(serverLevel, "stone stand unreachable");
                        return;
                    }
                } else if (moved && navigationEnded) {
                    this.standRouteAttempts++;
                    if (this.standRouteAttempts >= MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR
                            && this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                } else if (moved) {
                    this.standRouteAttempts = 0;
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        if (this.distanceToTargetSqr() > BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            if (this.shouldRetryPathWork() && this.tryAcquireExpensiveWork(serverLevel)) {
                boolean moved = this.moveToStandPos(serverLevel);
                if (!moved) {
                    if (this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    if (this.recordAccessFailureAndShouldReselect(serverLevel)) {
                        this.recoverFromBlockedStoneAccess(serverLevel, "stone stand unreachable");
                        return;
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        this.mineTarget(serverLevel);
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.playerNpc.endStoneSupplyGatheringEpisode();
        setStoneGatheringEpisodeActive(this.playerNpc, false);
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.toolAi.restoreMainHand();
        BlockPos interruptedClearTarget = this.clearBlockAi.targetPos();
        if (this.clearBlockAi.isRunning() && interruptedClearTarget != null) {
            this.temporarilyBlockedAccessClears.put(
                    interruptedClearTarget.immutable(),
                    this.playerNpc.level().getGameTime() + FAILED_ACCESS_CLEAR_SKIP_TICKS
            );
        }
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.waterEscapeAi.stop();
        if (!this.playerNpc.level().isClientSide()) {
            this.playerNpc.setGatherCooldown(20);
        }
        this.targetPos = null;
        this.standPos = null;
        this.clearStoneEgressTarget();
        this.stoneQueue.clear();
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.standRouteAttempts = 0;
        this.failedAccessAttempts = 0;
        this.resetStoneWorkProgressMonitor();
        this.lastPhaseDiagnostic = "";
        this.skippedAccessClearBlocks.clear();
        this.temporarilyBlockedStoneEgress.clear();
        this.nextContinueEligibilityCheckTick = 0;
        this.nextStoneEgressPathAttemptTick = 0;
        this.clearedAccessForTarget = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean tickWaterEscape(ServerLevel serverLevel) {
        BlockPos workDestination = this.stoneEgressPos != null
                ? this.stoneEgressPos
                : this.standPos != null ? this.standPos : this.targetPos;
        WaterEscapeAi.TickResult result = this.waterEscapeAi.tick(
                serverLevel,
                Math.min(1.0D, Math.max(0.1D, this.speed)),
                workDestination
        );
        if (result != WaterEscapeAi.TickResult.RUNNING && result != WaterEscapeAi.TickResult.DONE) {
            return false;
        }

        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_stone");
        if (result == WaterEscapeAi.TickResult.RUNNING && !this.waterEscapeAi.detail().isBlank()) {
            this.playerNpc.setCurrentAiDetail(this.waterEscapeAi.detail());
        }
        return true;
    }

    private boolean selectTarget(ServerLevel serverLevel) {
        NavigationPathBudget pathBudget = new NavigationPathBudget(MAX_TARGET_SELECTION_NAVIGATION_PATHS);
        if (this.selectQueuedTarget(serverLevel, pathBudget)) {
            return true;
        }

        this.stoneQueue.clear();
        BlockPos center = this.playerNpc.blockPosition();
        for (StoneCluster cluster : StoneAi.findNearby(
                serverLevel,
                center,
                SEARCH_RADIUS,
                pos -> isAllowedStoneSearchPos(this.playerNpc, center, pos))) {
            this.stoneQueue.addAll(cluster.stonesNearestFirst(center));
            if (this.selectQueuedTarget(serverLevel, pathBudget)) {
                return true;
            }
            this.stoneQueue.clear();
        }

        return false;
    }

    private boolean selectQueuedTarget(ServerLevel serverLevel, NavigationPathBudget pathBudget) {
        int checks = 0;
        while (!this.stoneQueue.isEmpty()) {
            BlockPos candidate = this.stoneQueue.poll();
            if (this.isTemporarilyBlocked(serverLevel, candidate)) {
                continue;
            }
            if (checks++ >= MAX_TARGET_SELECTION_CHECKS) {
                break;
            }
            if (!serverLevel.hasChunkAt(candidate)
                    || !StoneAi.isStone(serverLevel.getBlockState(candidate))
                    || isCurrentFeetColumnTarget(this.playerNpc, candidate)
                    || isInsideProtectedStoneTarget(this.playerNpc, candidate)) {
                continue;
            }
            if (canMineFromCurrentPosition(serverLevel, this.playerNpc, candidate)) {
                this.targetPos = candidate.immutable();
                this.standPos = this.playerNpc.blockPosition().immutable();
                this.standRouteAttempts = 0;
                this.failedAccessAttempts = 0;
                this.skippedAccessClearBlocks.clear();
                this.clearedAccessForTarget = false;
                this.clearBlockAi.stop();
                this.breakingBlockAi.stop();
                this.toolAi.equipTool(ItemTags.PICKAXES);
                return true;
            }
            Optional<BlockPos> stand = findStandPos(
                    this.playerNpc,
                    serverLevel,
                    candidate,
                    this.pathNavigationAi,
                    pathBudget
            );
            if (stand.isEmpty()) {
                continue;
            }

            this.targetPos = candidate.immutable();
            this.standPos = stand.get();
            this.standRouteAttempts = 0;
            this.failedAccessAttempts = 0;
            this.skippedAccessClearBlocks.clear();
            this.clearedAccessForTarget = false;
            this.clearBlockAi.stop();
            this.breakingBlockAi.stop();
            this.toolAi.equipTool(ItemTags.PICKAXES);
            return true;
        }

        this.targetPos = null;
        this.standPos = null;
        this.clearStoneEgressTarget();
        this.failedAccessAttempts = 0;
        this.skippedAccessClearBlocks.clear();
        this.clearedAccessForTarget = false;
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        return false;
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (serverLevel.isDarkOutside() || serverLevel.isThundering());
    }

    private static Optional<BlockPos> findStoneTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel, int radius) {
        BlockPos center = playerNpc.blockPosition();
        PathNavigationAi pathNavigationAi = new PathNavigationAi(playerNpc);
        NavigationPathBudget pathBudget = new NavigationPathBudget(MAX_NEARBY_STONE_NAVIGATION_PATHS);
        int checks = 0;
        for (StoneCluster cluster : StoneAi.findNearby(
                serverLevel,
                center,
                radius,
                pos -> isAllowedStoneSearchPos(playerNpc, center, pos))) {
            for (BlockPos candidate : cluster.stonesNearestFirst(center)) {
                if (checks++ >= MAX_STONE_PATH_CHECKS) {
                    return Optional.empty();
                }
                if (serverLevel.hasChunkAt(candidate)
                        && StoneAi.isStone(serverLevel.getBlockState(candidate))
                        && !isInsideProtectedStoneTarget(playerNpc, candidate)
                        && (canMineFromCurrentPosition(serverLevel, playerNpc, candidate)
                        || findStandPos(playerNpc, serverLevel, candidate, pathNavigationAi, pathBudget).isPresent())) {
                    return Optional.of(candidate.immutable());
                }
            }
        }
        return Optional.empty();
    }

    private void mineTarget(ServerLevel serverLevel) {
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                this::isMineableTargetState,
                REQUIRED_BREAK_TICKS,
                "mining stone"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        if (result == BreakingBlockAi.TickResult.DONE) {
            this.queueConnectedStoneTargets(serverLevel, this.targetPos);
            // Destruction, drops, lighting and neighbor updates are synchronous work charged to
            // this entity tick. Let the normal invalid-target branch consume the queue next tick
            // instead of compounding the commit with StoneAi/path construction.
            this.targetPos = null;
            this.standPos = null;
            this.setStoneDiagnostic("stone broken; queued reselection for next tick");
            return;
        }
        if (!this.tryAcquireExpensiveWork(serverLevel)) {
            this.setStoneDiagnostic("stone post-break selection queued for shared search slice");
            return;
        }
        if (!this.selectTarget(serverLevel)) {
            this.targetPos = null;
        }
    }

    private void queueConnectedStoneTargets(ServerLevel serverLevel, BlockPos origin) {
        if (origin == null) {
            return;
        }

        List<BlockPos> connected = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            BlockPos candidate = origin.relative(direction);
            if (this.isValidTarget(serverLevel, candidate) && !this.stoneQueue.contains(candidate)) {
                connected.add(candidate.immutable());
            }
        }

        connected.sort(Comparator.comparingDouble(pos -> pos.distSqr(this.playerNpc.blockPosition())));
        for (int i = connected.size() - 1; i >= 0; i--) {
            this.stoneQueue.addFirst(connected.get(i));
        }
    }

    private static Optional<BlockPos> findStandPos(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target,
            PathNavigationAi pathNavigationAi
    ) {
        return findStandPos(
                playerNpc,
                serverLevel,
                target,
                pathNavigationAi,
                new NavigationPathBudget(MAX_SINGLE_STAND_NAVIGATION_PATHS)
        );
    }

    private static Optional<BlockPos> findStandPos(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target,
            PathNavigationAi pathNavigationAi,
            NavigationPathBudget pathBudget
    ) {
        List<BlockPos> candidates = Direction.Plane.HORIZONTAL.stream()
                .map(direction -> target.relative(direction).immutable())
                .filter(pos -> canUseStoneStandCandidate(playerNpc, serverLevel, pos))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(playerNpc.blockPosition())))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        BlockPos nearest = candidates.get(0);
        for (BlockPos candidate : candidates) {
            if (!isSafeStoneStandAt(playerNpc, serverLevel, candidate)) {
                continue;
            }
            // Activation only chooses a physically safe stand. The running goal owns route
            // creation/retry, so canUse cannot synchronously build a navigation region for each
            // nearby stone candidate. This preserves fallback/reselection on an unreachable stand.
            return Optional.of(candidate.immutable());
        }
        return Optional.of(nearest.immutable());
    }

    private boolean isValidTarget(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null || !serverLevel.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return StoneAi.isStone(state)
                && !isCurrentFeetColumnTarget(this.playerNpc, pos)
                && !isInsideProtectedStoneTarget(this.playerNpc, pos);
    }

    private boolean isMineableTargetState(BlockState state) {
        return StoneAi.isStone(state);
    }

    private static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private static boolean canStandAtOrCanClearStandAt(ServerLevel serverLevel, BlockPos pos) {
        return canStandAt(serverLevel, pos) || canClearStandAt(serverLevel, pos);
    }

    private static boolean canUseStoneStandCandidate(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos) {
        return canStandAtOrCanClearStandAt(serverLevel, pos)
                && !isInsideProtectedStoneWorkFootprint(playerNpc, pos)
                && !isWetStoneStand(serverLevel, pos);
    }

    private static boolean isSafeStoneStandAt(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos) {
        return canStandAt(serverLevel, pos)
                && !isInsideProtectedStoneWorkFootprint(playerNpc, pos)
                && !isWetStoneStand(serverLevel, pos);
    }

    private static boolean isSafeCurrentStoneStand(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && isSafeStoneStandAt(playerNpc, serverLevel, playerNpc.blockPosition())
                && !playerNpc.isInWater();
    }

    private static boolean isUnsafeStoneWorkLocation(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos) {
        return playerNpc != null
                && (playerNpc.isInWater()
                || isWetStoneStand(serverLevel, pos)
                || isInsideProtectedStoneWorkFootprint(playerNpc, pos));
    }

    private static boolean isWetStoneStand(ServerLevel serverLevel, BlockPos pos) {
        return pos != null
                && serverLevel.hasChunkAt(pos)
                && (serverLevel.getFluidState(pos).is(FluidTags.WATER)
                || serverLevel.getFluidState(pos.above()).is(FluidTags.WATER)
                || serverLevel.getFluidState(pos.below()).is(FluidTags.WATER));
    }

    private static boolean canClearStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.isInWorldBounds(pos.above())
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos.above())
                || !serverLevel.hasChunkAt(pos)) {
            return false;
        }

        return serverLevel.getBlockState(pos.below()).isSolidRender()
                && canClearBodySpace(serverLevel, pos)
                && canClearBodySpace(serverLevel, pos.above());
    }

    private static boolean canClearBodySpace(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.getCollisionShape(serverLevel, pos).isEmpty()) {
            return serverLevel.getFluidState(pos).isEmpty();
        }

        return ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state);
    }

    private static boolean isActionableStoneTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos pos,
            PathNavigationAi pathNavigationAi
    ) {
        if (pos == null || !serverLevel.hasChunkAt(pos)) {
            return false;
        }
        Optional<BlockPos> stand = findStandPos(playerNpc, serverLevel, pos, pathNavigationAi);
        if (!StoneAi.isStone(serverLevel.getBlockState(pos))
                || isCurrentFeetColumnTarget(playerNpc, pos)
                || isInsideProtectedStoneTarget(playerNpc, pos)) {
            return false;
        }
        if (canMineFromCurrentPosition(serverLevel, playerNpc, pos)) {
            return true;
        }
        return stand.isPresent()
                && (canReachStand(playerNpc, serverLevel, stand.get(), pathNavigationAi)
                || hasImmediateAccessClearCandidate(playerNpc, serverLevel, pos, stand.get()));
    }

    private static boolean isAllowedStoneSearchPos(PlayerNpcEntity playerNpc, BlockPos center, BlockPos pos) {
        return pos.getY() >= center.getY() - DIG_SITE_STONE_SCAN_BELOW
                && !isSameColumn(center, pos)
                && !isInsideProtectedStoneTarget(playerNpc, pos);
    }

    private static boolean hasPreparedBaseForStone(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null) {
            return false;
        }
        if (isMiningJobActive(playerNpc)
                && !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || isFarmingSupportJob(playerNpc)
                || isExploringSupplyJob(playerNpc)) {
            return true;
        }

        return PlayerNpcHomeUtil.getHome(playerNpc).isPresent()
                && (playerNpc.isStoneAccessClearing()
                || !TerraformBuildSiteGoal.hasActionablePrepWork(playerNpc, serverLevel));
    }

    private static boolean hasPickaxe(PlayerNpcEntity playerNpc) {
        return playerNpc != null && playerNpc.hasCarriedTool(ItemTags.PICKAXES);
    }

    private static boolean isInsideProtectedStoneTarget(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || pos == null) {
            return false;
        }
        if (FarmAi.isProtectedFarmlandBlock(playerNpc, pos)
                || FarmAi.isBelowOwnedFarmFootprint(playerNpc, pos)) {
            return true;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        return home.isPresent()
                && (PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos)
                || PlayerNpcHomeUtil.isInside(home.get(), pos)
                || isBelowHomeFootprint(home.get(), pos)
                || isInsideHomeStoneBuffer(home.get(), pos));
    }

    private static boolean isCurrentFeetColumnTarget(PlayerNpcEntity playerNpc, BlockPos pos) {
        return playerNpc != null && isSameColumn(playerNpc.blockPosition(), pos);
    }

    private static boolean isSameColumn(BlockPos first, BlockPos second) {
        return first != null
                && second != null
                && first.getX() == second.getX()
                && first.getZ() == second.getZ();
    }

    private static boolean isInsideHomeFootprint(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || pos == null) {
            return false;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        return home.isPresent()
                && (PlayerNpcHomeUtil.isInsideFootprint(home.get(), pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos));
    }

    private static boolean isInsideProtectedStoneWorkFootprint(PlayerNpcEntity playerNpc, BlockPos pos) {
        return isInsideHomeFootprint(playerNpc, pos)
                || FarmAi.isInsideOwnedFarmWorkOrEntranceFootprint(playerNpc, pos);
    }

    private static boolean isBelowHomeFootprint(PlayerNpcHomeUtil.HomeArea homeArea, BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideFootprint(homeArea, pos)
                && pos.getY() < homeArea.origin().getY();
    }

    private static boolean isInsideHomeStoneBuffer(PlayerNpcHomeUtil.HomeArea homeArea, BlockPos pos) {
        return outsideFootprintDistance(homeArea, pos.getX(), pos.getZ()) <= HOME_STONE_TARGET_BUFFER
                && pos.getY() <= homeArea.origin().getY() + HOME_STONE_TARGET_MAX_Y_OFFSET;
    }

    private static int outsideFootprintDistance(PlayerNpcHomeUtil.HomeArea homeArea, int x, int z) {
        int minX = homeArea.origin().getX();
        int maxX = homeArea.origin().getX() + homeArea.width() - 1;
        int minZ = homeArea.origin().getZ();
        int maxZ = homeArea.origin().getZ() + homeArea.depth() - 1;
        int dx = x < minX ? minX - x : Math.max(0, x - maxX);
        int dz = z < minZ ? minZ - z : Math.max(0, z - maxZ);
        return Math.max(dx, dz);
    }

    private boolean moveToStandPos(ServerLevel serverLevel) {
        if (serverLevel == null || this.targetPos == null) {
            return false;
        }
        if (this.standPos == null || !canStandAt(serverLevel, this.standPos)) {
            this.standPos = findStandPos(this.playerNpc, serverLevel, this.targetPos, this.pathNavigationAi).orElse(null);
        }
        if (this.standPos == null) {
            return false;
        }

        return this.pathNavigationAi.moveToExact(serverLevel, this.standPos, this.speed, MAX_STAND_SAFE_DROP_BLOCKS);
    }

    private boolean handleUnsafeStandBeforeStone(ServerLevel serverLevel) {
        if (!isUnsafeStoneWorkLocation(this.playerNpc, serverLevel, this.playerNpc.blockPosition())) {
            this.clearStoneEgressTarget();
            return false;
        }

        this.breakingBlockAi.stop();
        this.clearBlockAi.stop();
        if (this.moveToSafeStoneStand(serverLevel)) {
            this.updateDetail();
            return true;
        }

        this.clearStoneEgressTarget();
        if (isInsideProtectedStoneWorkFootprint(this.playerNpc, this.playerNpc.blockPosition())) {
            this.playerNpc.getNavigation().stop();
            this.targetPos = null;
            this.standPos = null;
            this.clearStoneEgressTarget();
            this.stoneQueue.clear();
            this.clearedAccessForTarget = false;
            this.playerNpc.setCurrentAiDetail("leaving home before stone blocked "
                    + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
            return true;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiDetail("moving to safe stone stand blocked "
                + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
        if (this.targetPos != null
                && this.repathTicks-- <= 0) {
            if (this.recordAccessFailureAndShouldReselect(serverLevel)) {
                this.recoverFromBlockedStoneAccess(serverLevel, "stone stand blocked");
            }
        }
        return true;
    }

    private boolean moveToSafeStoneStand(ServerLevel serverLevel) {
        return this.moveToSafeStoneStand(serverLevel, false);
    }

    private boolean moveToSafeStoneStand(ServerLevel serverLevel, boolean force) {
        this.expireTemporarilyBlockedStoneEgress(serverLevel);
        if (!force && !isUnsafeStoneWorkLocation(this.playerNpc, serverLevel, this.playerNpc.blockPosition())) {
            this.clearStoneEgressTarget();
            return false;
        }

        if (this.stoneEgressPos != null
                && (this.isStoneEgressTemporarilyBlocked(serverLevel, this.stoneEgressPos)
                || !isSafeStoneStandAt(this.playerNpc, serverLevel, this.stoneEgressPos))) {
            this.clearStoneEgressTarget();
        }

        if (this.stoneEgressPos == null) {
            if (this.playerNpc.tickCount < this.nextStoneEgressPathAttemptTick) {
                return true;
            }
            if (!this.tryAcquireExpensiveWork(serverLevel)) {
                this.nextStoneEgressPathAttemptTick = this.playerNpc.tickCount
                        + 1 + this.playerNpc.getRandom().nextInt(4);
                return true;
            }
            Optional<BlockPos> selected = this.findSafeStoneStandEgressPos(serverLevel);
            this.setStoneEgressTarget(selected.orElse(null));
            if (this.stoneEgressPos != null) {
                // Selection already consumed this tick's bounded navigation search. Build the
                // movement path in a later admitted tick instead of doing two paths back-to-back.
                this.nextStoneEgressPathAttemptTick = this.playerNpc.tickCount + 1;
                return true;
            }
        }
        if (this.stoneEgressPos == null) {
            return false;
        }

        this.markStoneAccessClearing();
        if (this.playerNpc.distanceToSqr(
                this.stoneEgressPos.getX() + 0.5D,
                this.stoneEgressPos.getY(),
                this.stoneEgressPos.getZ() + 0.5D
        ) <= HOME_EGRESS_REACHED_DISTANCE_SQR) {
            this.clearStoneEgressTarget();
            return false;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.stoneEgressPos.getX() + 0.5D,
                this.stoneEgressPos.getY(),
                this.stoneEgressPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (this.playerNpc.tickCount < this.nextStoneEgressPathAttemptTick) {
            return true;
        }
        if (!this.tryAcquireExpensiveWork(serverLevel)) {
            this.nextStoneEgressPathAttemptTick = this.playerNpc.tickCount
                    + 1 + this.playerNpc.getRandom().nextInt(4);
            return true;
        }
        this.nextStoneEgressPathAttemptTick = this.playerNpc.tickCount
                + STONE_EGRESS_REPATH_INTERVAL_TICKS;
        boolean moved = this.pathNavigationAi.moveTo(
                serverLevel,
                this.stoneEgressPos,
                this.speed,
                MAX_STAND_SAFE_DROP_BLOCKS
        );
        if (!moved) {
            boolean retry = this.shouldRetrySafeStoneStandEgress();
            if (retry) {
                this.markStoneEgressTemporarilyBlocked(serverLevel, this.stoneEgressPos, "safe stone stand path failed");
            }
            this.clearStoneEgressTarget();
            return retry;
        }
        return true;
    }

    private void tickSafeStoneStandReachTimeout(ServerLevel serverLevel) {
        this.expireTemporarilyBlockedStoneEgress(serverLevel);
        if (!this.shouldRetrySafeStoneStandEgress()) {
            this.resetStoneEgressProgress();
            return;
        }
        if (this.stoneEgressPos == null) {
            this.resetStoneEgressProgress();
            return;
        }

        if (this.playerNpc.distanceToSqr(
                this.stoneEgressPos.getX() + 0.5D,
                this.stoneEgressPos.getY(),
                this.stoneEgressPos.getZ() + 0.5D
        ) <= HOME_EGRESS_REACHED_DISTANCE_SQR) {
            return;
        }

        if (this.stoneEgressWatchPos == null || !this.stoneEgressWatchPos.equals(this.stoneEgressPos)) {
            this.stoneEgressWatchPos = this.stoneEgressPos.immutable();
            this.stoneEgressReachTicks = 0;
            return;
        }

        if (++this.stoneEgressReachTicks < STONE_SAFE_STAND_REACH_TIMEOUT_TICKS) {
            return;
        }

        BlockPos timedOut = this.stoneEgressPos.immutable();
        this.markStoneEgressTemporarilyBlocked(serverLevel, timedOut, "safe stone stand timeout");
        this.clearStoneEgressTarget();
        this.playerNpc.getNavigation().stop();
        this.moveToSafeStoneStand(serverLevel, true);
    }

    private void setStoneEgressTarget(BlockPos pos) {
        this.stoneEgressPos = pos == null ? null : pos.immutable();
        this.resetStoneEgressProgress();
    }

    private void clearStoneEgressTarget() {
        this.stoneEgressPos = null;
        this.resetStoneEgressProgress();
    }

    private void resetStoneEgressProgress() {
        this.stoneEgressWatchPos = null;
        this.stoneEgressReachTicks = 0;
    }

    private Optional<BlockPos> findSafeStoneStandEgressPos(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (isInsideHomeFootprint(this.playerNpc, feet)) {
            return this.findHomeStoneEgressPos(serverLevel);
        }
        if (FarmAi.isInsideOwnedFarmWorkOrEntranceFootprint(this.playerNpc, feet)) {
            return this.findFarmStoneEgressPos(serverLevel);
        }
        return this.findNearbySafeStoneStand(serverLevel);
    }

    private Optional<BlockPos> findFarmStoneEgressPos(ServerLevel serverLevel) {
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (plan == null) {
            return Optional.empty();
        }
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos routeTarget = this.standPos == null ? this.targetPos : this.standPos;
        if (routeTarget == null) {
            routeTarget = feet;
        }
        int protectedMinX = plan.origin().getX() - 2;
        int protectedMaxX = plan.origin().getX() + plan.width() + 1;
        int protectedMinZ = plan.origin().getZ() - 2;
        int protectedMaxZ = plan.origin().getZ() + plan.depth() + 1;
        // The farm column is protected at every depth. Search a bounded vertical band
        // around the worker instead of allocating candidates all the way to surface.
        int minY = feet.getY() - HOME_EGRESS_VERTICAL_DOWN;
        int maxY = feet.getY() + HOME_EGRESS_VERTICAL_UP;
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = protectedMinX - HOME_EGRESS_RADIUS; x <= protectedMaxX + HOME_EGRESS_RADIUS; x++) {
            for (int z = protectedMinZ - HOME_EGRESS_RADIUS; z <= protectedMaxZ + HOME_EGRESS_RADIUS; z++) {
                int ring = outsideRectangleDistance(
                        protectedMinX,
                        protectedMaxX,
                        protectedMinZ,
                        protectedMaxZ,
                        x,
                        z
                );
                if (ring <= 0 || ring > HOME_EGRESS_RADIUS) {
                    continue;
                }
                for (int y = maxY; y >= minY; y--) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (isSafeStoneStandAt(this.playerNpc, serverLevel, candidate)
                            && !this.isStoneEgressTemporarilyBlocked(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        BlockPos target = routeTarget;
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(target) + pos.distSqr(feet) * 0.2D));
        return this.pathNavigationAi.findReachableRandomizedCandidate(
                serverLevel,
                candidates,
                HOME_EGRESS_RANDOM_POOL,
                HOME_EGRESS_PATH_CHECKS,
                MAX_STAND_SAFE_DROP_BLOCKS
        );
    }

    private static int outsideRectangleDistance(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int x,
            int z
    ) {
        int dx = x < minX ? minX - x : Math.max(0, x - maxX);
        int dz = z < minZ ? minZ - z : Math.max(0, z - maxZ);
        return Math.max(dx, dz);
    }

    private Optional<BlockPos> findHomeStoneEgressPos(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return Optional.empty();
        }
        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos routeTarget = this.standPos == null ? this.targetPos : this.standPos;
        if (routeTarget == null) {
            routeTarget = feet;
        }

        List<BlockPos> candidates = new ArrayList<>();
        int minX = homeArea.origin().getX() - HOME_EGRESS_RADIUS;
        int maxX = homeArea.origin().getX() + homeArea.width() - 1 + HOME_EGRESS_RADIUS;
        int minZ = homeArea.origin().getZ() - HOME_EGRESS_RADIUS;
        int maxZ = homeArea.origin().getZ() + homeArea.depth() - 1 + HOME_EGRESS_RADIUS;
        int minY = Math.min(feet.getY(), homeArea.origin().getY()) - HOME_EGRESS_VERTICAL_DOWN;
        int maxY = Math.max(feet.getY(), homeArea.origin().getY()) + HOME_EGRESS_VERTICAL_UP;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int ringDistance = outsideFootprintDistance(homeArea, x, z);
                if (ringDistance <= 0 || ringDistance > HOME_EGRESS_RADIUS) {
                    continue;
                }
                for (int y = maxY; y >= minY; y--) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (isSafeStoneStandAt(this.playerNpc, serverLevel, candidate)
                            && !this.isStoneEgressTemporarilyBlocked(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }

        BlockPos target = routeTarget;
        candidates.sort(Comparator.comparingDouble(pos ->
                pos.distSqr(target) + pos.distSqr(feet) * 0.2D));
        return this.pathNavigationAi.findReachableRandomizedCandidate(
                serverLevel,
                candidates,
                HOME_EGRESS_RANDOM_POOL,
                HOME_EGRESS_PATH_CHECKS,
                MAX_STAND_SAFE_DROP_BLOCKS
        );
    }

    private Optional<BlockPos> findNearbySafeStoneStand(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int minRadiusSqr = STONE_SAFE_STAND_EGRESS_MIN_RADIUS * STONE_SAFE_STAND_EGRESS_MIN_RADIUS;
        int maxRadius = STONE_SAFE_STAND_EGRESS_MAX_RADIUS;
        int maxRadiusSqr = maxRadius * maxRadius;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr < minRadiusSqr || distanceSqr > maxRadiusSqr) {
                    continue;
                }
                for (int dy = -STONE_SAFE_STAND_EGRESS_VERTICAL_DOWN; dy <= STONE_SAFE_STAND_EGRESS_VERTICAL_UP; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (isSafeStoneStandAt(this.playerNpc, serverLevel, candidate)
                            && !this.isStoneEgressTemporarilyBlocked(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        BlockPos routeTarget = this.standPos == null ? this.targetPos : this.standPos;
        if (routeTarget == null) {
            routeTarget = feet;
        }
        BlockPos target = routeTarget;
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(target) + pos.distSqr(feet) * 0.2D)
                .thenComparingInt(BlockPos::getY));
        return this.pathNavigationAi.findReachableRandomizedCandidate(
                serverLevel,
                candidates,
                STONE_SAFE_STAND_EGRESS_RANDOM_POOL,
                STONE_SAFE_STAND_EGRESS_PATH_CHECKS,
                MAX_STAND_SAFE_DROP_BLOCKS
        );
    }

    private boolean isAtMiningStand(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (canMineFromCurrentPosition(serverLevel, this.playerNpc, this.targetPos)) {
            this.standPos = feet.immutable();
            this.standRouteAttempts = 0;
            return true;
        }
        if (isStandAdjacentToTarget(this.playerNpc, serverLevel, feet, this.targetPos)) {
            this.standPos = feet.immutable();
            this.standRouteAttempts = 0;
            return true;
        }

        if (this.standPos == null || feet.getY() != this.standPos.getY()) {
            return false;
        }

        double dx = this.playerNpc.getX() - (this.standPos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (this.standPos.getZ() + 0.5D);
        return dx * dx + dz * dz <= STAND_REACHED_HORIZONTAL_SQR
                && isStandAdjacentToTarget(this.playerNpc, serverLevel, this.standPos, this.targetPos);
    }

    private static boolean isStandAdjacentToTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos stand, BlockPos target) {
        if (stand == null || target == null || stand.getY() != target.getY()
                || !isSafeStoneStandAt(playerNpc, serverLevel, stand)) {
            return false;
        }

        int dx = Math.abs(stand.getX() - target.getX());
        int dz = Math.abs(stand.getZ() - target.getZ());
        return dx + dz == 1;
    }

    private static boolean canMineFromCurrentPosition(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos target) {
        return target != null
                && isSafeCurrentStoneStand(playerNpc, serverLevel)
                && playerNpc.distanceToSqr(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D
        ) <= BREAK_DISTANCE_SQR
                && hasClearMiningRay(serverLevel, playerNpc, target);
    }

    private static boolean hasClearMiningRay(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos target) {
        Vec3 eye = new Vec3(playerNpc.getX(), playerNpc.getEyeY(), playerNpc.getZ());
        BlockHitResult hit = serverLevel.clip(new ClipContext(
                eye,
                Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                playerNpc
        ));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D);
    }

    private void updateDetail() {
        int mode;
        BlockPos subject;
        if (this.clearBlockAi.isRunning()) {
            mode = 1;
            subject = this.clearBlockAi.targetPos();
        } else if (this.breakingBlockAi.isRunning()) {
            mode = 2;
            subject = this.breakingBlockAi.targetPos();
        } else if (this.stoneEgressPos != null) {
            mode = 3;
            subject = this.stoneEgressPos;
        } else if (this.targetPos == null) {
            mode = 4;
            subject = null;
        } else {
            mode = 5;
            subject = this.targetPos;
        }
        if (!this.shouldRefreshDetail(mode, subject)) {
            return;
        }

        if (this.clearBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.clearBlockAi.detail()
                    + " " + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
            return;
        }
        if (this.breakingBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.breakingBlockAi.detail()
                    + " " + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
            return;
        }
        if (this.stoneEgressPos != null) {
            this.playerNpc.setCurrentAiDetail("moving to safe stone stand @ "
                    + this.stoneEgressPos.getX() + " "
                    + this.stoneEgressPos.getY() + " "
                    + this.stoneEgressPos.getZ()
                    + this.safeStoneStandTimeoutDetail()
                    + " " + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
            return;
        }
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("searching for stone");
            return;
        }
        this.playerNpc.setCurrentAiDetail("stone @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ()
                + " " + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
    }

    private boolean shouldRefreshDetail(int mode, BlockPos subject) {
        boolean structureChanged = mode != this.lastDetailMode || !Objects.equals(subject, this.lastDetailSubject);
        if (!structureChanged && this.playerNpc.tickCount < this.nextDetailProgressRefreshTick) {
            return false;
        }
        this.lastDetailMode = mode;
        this.lastDetailSubject = subject == null ? null : subject.immutable();
        this.nextDetailProgressRefreshTick = this.playerNpc.tickCount + DETAIL_PROGRESS_REFRESH_INTERVAL_TICKS;
        return true;
    }

    private void resetDetailRefresh() {
        this.lastDetailMode = -1;
        this.lastDetailSubject = null;
        this.nextDetailProgressRefreshTick = 0;
    }

    private String safeStoneStandTimeoutDetail() {
        return this.shouldRetrySafeStoneStandEgress()
                ? " timeout=" + this.safeStoneStandTimeoutSecondsRemaining() + "s"
                : "";
    }

    private int safeStoneStandTimeoutSecondsRemaining() {
        if (this.stoneEgressPos == null) {
            return 0;
        }
        int remainingTicks = Math.max(0, STONE_SAFE_STAND_REACH_TIMEOUT_TICKS - this.stoneEgressReachTicks);
        return (remainingTicks + 19) / 20;
    }

    private boolean shouldRetrySafeStoneStandEgress() {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                || GatherStoneGoal.isFarmingSupportJob(this.playerNpc);
    }

    private boolean recoverIfStoneWorkStuck(ServerLevel serverLevel) {
        if (this.targetPos == null
                || this.stoneEgressPos != null
                || this.breakingBlockAi.isRunning()
                || this.clearBlockAi.isRunning()) {
            this.resetStoneWorkProgressMonitor();
            return false;
        }

        int stoneCount = ResourceAi.countStone(this.playerNpc);
        BlockPos progressTarget = this.standPos == null ? this.targetPos : this.standPos;
        double currentDistance = Math.sqrt(this.playerNpc.distanceToSqr(
                progressTarget.getX() + 0.5D,
                progressTarget.getY(),
                progressTarget.getZ() + 0.5D
        ));
        if (this.stoneWorkProgressTarget == null
                || !this.stoneWorkProgressTarget.equals(progressTarget)
                || this.lastStoneWorkProgressCount != stoneCount) {
            this.stoneWorkProgressTarget = progressTarget.immutable();
            this.lastStoneWorkProgressCount = stoneCount;
            this.stoneWorkNoProgressTicks = 0;
            this.bestStoneWorkDistance = currentDistance;
            return false;
        }
        if (currentDistance + STONE_WORK_PROGRESS_EPSILON < this.bestStoneWorkDistance) {
            this.bestStoneWorkDistance = currentDistance;
            this.stoneWorkNoProgressTicks = 0;
            return false;
        }

        boolean navigationEnded = this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck();
        if (!navigationEnded && !this.clearBlockAi.isRunning()) {
            return false;
        }

        if (++this.stoneWorkNoProgressTicks < STONE_WORK_NO_PROGRESS_TICKS) {
            return false;
        }

        if (!this.tryAcquireExpensiveWork(serverLevel)) {
            this.setStoneDiagnostic("stone access stuck; clear recovery queued for shared search slice");
            return true;
        }
        if (this.startClearingRoute(serverLevel)) {
            this.resetStoneWorkProgressMonitor();
            return true;
        }
        return this.recoverFromBlockedStoneAccess(serverLevel, "stone access stuck");
    }

    private void resetStoneWorkProgressMonitor() {
        this.stoneWorkProgressTarget = null;
        this.lastStoneWorkProgressCount = 0;
        this.stoneWorkNoProgressTicks = 0;
        this.bestStoneWorkDistance = Double.MAX_VALUE;
    }

    private boolean recoverFromBlockedStoneAccess(ServerLevel serverLevel, String reason) {
        if (!this.tryAcquireExpensiveWork(serverLevel)) {
            this.setStoneDiagnostic(reason + "; recovery queued for shared search slice");
            return true;
        }
        this.markTargetTemporarilyBlocked(serverLevel);
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.playerNpc.getNavigation().stop();
        this.targetPos = null;
        this.standPos = null;
        this.stoneQueue.clear();
        this.repathTicks = 0;
        this.standRouteAttempts = 0;
        this.failedAccessAttempts = 0;
        this.skippedAccessClearBlocks.clear();
        this.clearedAccessForTarget = false;
        this.resetStoneWorkProgressMonitor();
        if (this.moveToSafeStoneStand(serverLevel, true)) {
            this.updateDetail();
            return true;
        }

        this.playerNpc.setCurrentAiDetail(reason + "; searching for safer stone "
                + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
        if (!this.selectTarget(serverLevel)) {
            this.targetPos = null;
        }
        return true;
    }

    private boolean tickClearBlock(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            return false;
        }

        BlockPos clearTarget = this.clearBlockAi.targetPos();
        if (isInsideProtectedStoneTarget(this.playerNpc, clearTarget)) {
            this.clearBlockAi.stop();
            return this.recoverFromBlockedStoneAccess(serverLevel, "stone route reached protected farm");
        }
        this.markStoneAccessClearing();
        if (this.clearBlockAi.needsPathWork(serverLevel)) {
            // Stone-route clearing is optional recovery. Runtime still measured a single retained
            // clear stand path at 118.8 ms even with a 0.03 node multiplier. Do not pathfind while
            // this auxiliary clear owns GatherStone.tick: quarantine the inaccessible obstruction
            // and let normal stone target/stand selection choose another route.
            this.clearBlockAi.stop();
            if (clearTarget != null) {
                this.skippedAccessClearBlocks.add(clearTarget.immutable());
                this.temporarilyBlockedAccessClears.put(
                        clearTarget.immutable(),
                        serverLevel.getGameTime() + FAILED_ACCESS_CLEAR_SKIP_TICKS
                );
            }
            return this.recoverFromBlockedStoneAccess(serverLevel, "stone clear requires costly route; reselecting");
        }
        boolean pathWorkAllowed = !this.clearBlockAi.needsPathWork(serverLevel)
                || this.tryAcquireExpensiveWork(serverLevel);
        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel, pathWorkAllowed);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            if (isInsideProtectedStoneTarget(this.playerNpc, this.clearBlockAi.targetPos())) {
                this.clearBlockAi.stop();
                return this.recoverFromBlockedStoneAccess(serverLevel, "stone route retargeted protected farm");
            }
            return true;
        }

        this.toolAi.restoreMainHand();
        if (result == ClearBlockAi.TickResult.DONE) {
            this.setStoneDiagnostic("stone route clear done clear=" + posText(clearTarget)
                    + " target=" + posText(this.targetPos));
            this.failedAccessAttempts = 0;
            this.repathTicks = 0;
            // Give the next retained obstruction/stand route its own bounded progress window.
            // The completed clear is real progress even though it does not increase stone count.
            this.resetStoneWorkProgressMonitor();
        } else if (result == ClearBlockAi.TickResult.FAILED) {
            this.setStoneDiagnostic("stone route clear failed clear=" + posText(clearTarget)
                    + " state=" + blockStateText(serverLevel, clearTarget)
                    + " target=" + posText(this.targetPos)
                    + " failures=" + (this.failedAccessAttempts + 1));
            if (clearTarget != null) {
                this.skippedAccessClearBlocks.add(clearTarget.immutable());
                this.temporarilyBlockedAccessClears.put(
                        clearTarget.immutable(),
                        serverLevel.getGameTime() + FAILED_ACCESS_CLEAR_SKIP_TICKS
                );
            }
            if (this.recordAccessFailureAndShouldReselect(serverLevel)) {
                return this.recoverFromBlockedStoneAccess(serverLevel, "stone route clear failed");
            }
            return false;
        }
        return false;
    }

    private boolean startClearingRoute(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            this.setStoneDiagnostic("stone clear blocked: no target");
            return false;
        }

        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                this.standPos,
                this.targetPos
        ));
        addLocalRouteCandidates(candidates, this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        addDescendingAccessCandidates(candidates, this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        addForcedNearbyAccessCandidates(candidates, this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        long now = serverLevel.getGameTime();
        this.temporarilyBlockedAccessClears.entrySet().removeIf(entry -> entry.getValue() <= now);
        BlockPos nearest = candidates.stream()
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> !pos.equals(this.targetPos))
                .filter(pos -> !this.skippedAccessClearBlocks.contains(pos))
                .filter(pos -> !this.temporarilyBlockedAccessClears.containsKey(pos))
                // Body/route generation contributes many nearby air cells. Discard those before
                // bounding the expensive checks, otherwise the first sixteen slots can all be
                // empty space and hide the actual dirt/grass obstruction just beyond them.
                .filter(pos -> serverLevel.hasChunkAt(pos)
                        && !serverLevel.getBlockState(pos).isAir())
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(this.playerNpc.blockPosition())))
                // Forced recovery can still contribute hundreds of solid positions. Only the
                // nearest small prefix may run protection/shape/raycast checks in one tick.
                .limit(16)
                .filter(pos -> !isInsideProtectedStoneTarget(this.playerNpc, pos))
                .filter(pos -> ClearBlockAi.isBreakablePathObstruction(
                        serverLevel,
                        pos,
                        serverLevel.getBlockState(pos)
                ))
                .filter(pos -> ClearBlockAi.canBreakFromCurrentStand(serverLevel, this.playerNpc, pos))
                .findFirst()
                .orElse(null);
        boolean started = nearest != null && this.clearBlockAi.start(
                serverLevel,
                nearest,
                this::isClearablePathState,
                "clearing stone path",
                CLEAR_OBSTRUCTION_TICKS,
                FORCED_CLEAR_DISTANCE_SQR
        );
        boolean selectionPending = false;
        if (started) {
            this.clearedAccessForTarget = true;
            this.markStoneAccessClearing();
            BlockPos clearTarget = this.clearBlockAi.targetPos();
            this.setStoneDiagnostic("stone route clear started clear=" + posText(clearTarget)
                    + " state=" + blockStateText(serverLevel, clearTarget)
                    + " target=" + posText(this.targetPos)
                    + " stand=" + posText(this.standPos)
                    + " candidates=" + candidates.size());
        } else if (selectionPending) {
            this.setStoneDiagnostic("stone route clear selection queued for next admitted slice target="
                    + posText(this.targetPos)
                    + " stand=" + posText(this.standPos)
                    + " candidates=" + candidates.size());
        } else {
            this.setStoneDiagnostic("stone route clear not started target=" + posText(this.targetPos)
                    + " stand=" + posText(this.standPos)
                    + " candidates=" + candidates.size()
                    + " feet=" + posText(this.playerNpc.blockPosition()));
        }
        return started || selectionPending;
    }

    private boolean canContinueStoneWork(ServerLevel serverLevel) {
        boolean miningJob = isMiningJobActive(this.playerNpc);
        boolean fishingSupportJob = isFishingSupportJob(this.playerNpc);
        boolean farmingSupportJob = isFarmingSupportJob(this.playerNpc);
        boolean exploringSupplyJob = isExploringSupplyJob(this.playerNpc);
        boolean supplyPhaseActive = isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                || (this.toolAi.hasTool(ItemTags.PICKAXES)
                && isStoneSupplyPhaseActiveWithAvailablePickaxe(this.playerNpc, serverLevel));
        boolean stayHomeForWeather = this.shouldStayHomeForWeather(serverLevel);
        boolean preparedBase = hasPreparedBaseForStone(this.playerNpc, serverLevel);
        boolean accessAllowed = this.clearedAccessForTarget
                || fishingSupportJob
                || farmingSupportJob
                || exploringSupplyJob
                || preparedBase;
        boolean phaseStillActive = supplyPhaseActive
                && (!stayHomeForWeather || miningJob)
                && accessAllowed;
        if (!phaseStillActive) {
            // This text is consumed only when continuation stops. Building it on every successful
            // 20-tick recheck forced a full blueprint missing-material refresh even when the cheap
            // cobblestone-demand branch had already kept the phase active.
            this.lastPhaseDiagnostic = "supply=" + supplyPhaseActive
                    + ",cobbleNeed=" + this.playerNpc.shouldPrioritizeCobblestoneGathering()
                    + ",buildStoneNeed=" + PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, this.playerNpc)
                    + ",prepared=" + preparedBase
                    + ",clearedAccess=" + this.clearedAccessForTarget
                    + ",stoneAccess=" + this.playerNpc.getStoneAccessClearCooldown()
                    + ",stayHome=" + stayHomeForWeather
                    + ",miningJob=" + miningJob
                    + ",night=" + serverLevel.isDarkOutside()
                    + ",thunder=" + serverLevel.isThundering();
        }
        return phaseStillActive;
    }

    private boolean isClearablePathState(BlockState state) {
        return isClearablePathStateStatic(state);
    }

    private void markStoneAccessClearing() {
        this.playerNpc.markStoneAccessClearing(STONE_ACCESS_CLEAR_GRACE_TICKS);
    }

    private static void addLocalRouteCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos stand, BlockPos target) {
        if (feet == null) {
            return;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addBodyColumn(candidates, feet.relative(direction));
        }

        BlockPos routeTarget = stand == null ? target : stand;
        if (routeTarget == null) {
            return;
        }

        int stepX = Integer.compare(routeTarget.getX(), feet.getX());
        int stepZ = Integer.compare(routeTarget.getZ(), feet.getZ());
        if (stepX != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, 0));
        }
        if (stepZ != 0) {
            addBodyColumn(candidates, feet.offset(0, 0, stepZ));
        }
        if (stepX != 0 && stepZ != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, stepZ));
        }
    }

    private static void addDescendingAccessCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos stand, BlockPos target) {
        if (feet == null || target == null) {
            return;
        }

        BlockPos routeTarget = stand == null ? nearestSideTarget(feet, target) : stand;
        BlockPos cursor = feet;
        for (int step = 0; step < MAX_DESCENDING_ACCESS_STEPS; step++) {
            if (cursor.equals(routeTarget)) {
                break;
            }

            int stepX = Integer.compare(routeTarget.getX(), cursor.getX());
            int stepZ = Integer.compare(routeTarget.getZ(), cursor.getZ());
            int nextY = cursor.getY();
            if (cursor.getY() > routeTarget.getY()) {
                nextY--;
                candidates.add(cursor.below());
                candidates.add(cursor.below(2));
            } else if (cursor.getY() < routeTarget.getY()) {
                nextY++;
            }

            BlockPos nextFeet = new BlockPos(cursor.getX() + stepX, nextY, cursor.getZ() + stepZ);
            addBodyColumn(candidates, nextFeet);
            cursor = nextFeet;
        }

        addBodyColumn(candidates, routeTarget);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addBodyColumn(candidates, target.relative(direction));
        }
    }

    private static void addForcedNearbyAccessCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos stand, BlockPos target) {
        if (feet == null || target == null) {
            return;
        }

        BlockPos routeTarget = stand == null ? nearestSideTarget(feet, target) : stand;
        int targetDx = routeTarget.getX() - feet.getX();
        int targetDz = routeTarget.getZ() - feet.getZ();
        int currentHorizontalDistance = horizontalDistanceSqr(feet, routeTarget);
        for (int dx = -FORCED_ACCESS_RADIUS; dx <= FORCED_ACCESS_RADIUS; dx++) {
            for (int dz = -FORCED_ACCESS_RADIUS; dz <= FORCED_ACCESS_RADIUS; dz++) {
                if (dx * dx + dz * dz > FORCED_ACCESS_RADIUS * FORCED_ACCESS_RADIUS) {
                    continue;
                }
                if ((targetDx != 0 || targetDz != 0) && dx * targetDx + dz * targetDz < 0) {
                    continue;
                }

                BlockPos column = feet.offset(dx, 0, dz);
                if (!column.equals(feet)
                        && horizontalDistanceSqr(column, routeTarget) > currentHorizontalDistance + 4) {
                    continue;
                }

                for (int yOffset = FORCED_ACCESS_UP; yOffset >= -FORCED_ACCESS_DOWN; yOffset--) {
                    candidates.add(column.offset(0, yOffset, 0));
                }
            }
        }
    }

    private static boolean hasImmediateAccessClearCandidate(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos target, BlockPos stand) {
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                playerNpc.blockPosition(),
                stand,
                target
        ));
        addLocalRouteCandidates(candidates, playerNpc.blockPosition(), stand, target);
        addDescendingAccessCandidates(candidates, playerNpc.blockPosition(), stand, target);
        addForcedNearbyAccessCandidates(candidates, playerNpc.blockPosition(), stand, target);
        candidates.removeIf(pos -> pos.equals(target)
                || isInsideProtectedStoneTarget(playerNpc, pos));
        Optional<BlockPos> requested = ClearBlockAi.findNearestAccessibleClearable(
                serverLevel,
                playerNpc,
                candidates,
                GatherStoneGoal::isClearablePathStateStatic,
                FORCED_CLEAR_DISTANCE_SQR
        );
        return requested.flatMap(pos -> ClearBlockAi.resolveInitialClearTarget(
                        serverLevel,
                        playerNpc,
                        pos,
                        GatherStoneGoal::isClearablePathStateStatic,
                        FORCED_CLEAR_DISTANCE_SQR,
                        false
                ))
                .filter(pos -> !isInsideProtectedStoneTarget(playerNpc, pos))
                .isPresent();
    }

    private static boolean canReachStand(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos stand,
            PathNavigationAi pathNavigationAi
    ) {
        return isSafeStoneStandAt(playerNpc, serverLevel, stand)
                && pathNavigationAi.canReachOrSafelyDropTo(serverLevel, stand, MAX_STAND_SAFE_DROP_BLOCKS);
    }

    private static boolean isClearablePathStateStatic(BlockState state) {
        return state != null && !state.isAir();
    }

    private static BlockPos nearestSideTarget(BlockPos feet, BlockPos target) {
        return Direction.Plane.HORIZONTAL.stream()
                .map(target::relative)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
                .orElse(target);
    }

    private boolean shouldRetryPathWork() {
        if (this.repathTicks > 0 && !this.playerNpc.getNavigation().isStuck()) {
            this.repathTicks--;
            return false;
        }
        return true;
    }

    private boolean tryAcquireExpensiveWork(ServerLevel serverLevel) {
        if (this.lastExpensiveWorkAdmissionTick == this.playerNpc.tickCount) {
            return true;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            return false;
        }
        this.lastExpensiveWorkAdmissionTick = this.playerNpc.tickCount;
        return true;
    }

    private boolean recordAccessFailureAndShouldReselect(ServerLevel serverLevel) {
        this.failedAccessAttempts++;
        this.repathTicks = ACCESS_RETRY_TICKS;
        if (this.failedAccessAttempts < MAX_FAILED_ACCESS_ATTEMPTS_BEFORE_RESELECT) {
            return false;
        }

        this.markTargetTemporarilyBlocked(serverLevel);
        return true;
    }

    private boolean isTemporarilyBlocked(ServerLevel serverLevel, BlockPos pos) {
        if (this.temporarilyBlockedTarget == null || pos == null) {
            return false;
        }
        if (serverLevel.getGameTime() >= this.temporarilyBlockedTargetUntilTick) {
            this.temporarilyBlockedTarget = null;
            this.temporarilyBlockedTargetUntilTick = 0L;
            return false;
        }
        return this.temporarilyBlockedTarget.equals(pos);
    }

    private void markTargetTemporarilyBlocked(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return;
        }
        this.temporarilyBlockedTarget = this.targetPos.immutable();
        this.temporarilyBlockedTargetUntilTick = serverLevel.getGameTime() + FAILED_TARGET_SKIP_TICKS;
    }

    private void markStoneEgressTemporarilyBlocked(ServerLevel serverLevel, BlockPos pos, String reason) {
        if (pos == null) {
            return;
        }
        BlockPos blocked = pos.immutable();
        this.temporarilyBlockedStoneEgress.put(
                blocked,
                serverLevel.getGameTime() + FAILED_STONE_SAFE_STAND_SKIP_TICKS
        );
        this.setStoneDiagnostic(reason + " stand=" + posText(blocked)
                + " retryAfter=" + (FAILED_STONE_SAFE_STAND_SKIP_TICKS / 20) + "s"
                + " target=" + posText(this.targetPos));
    }

    private boolean isStoneEgressTemporarilyBlocked(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        this.expireTemporarilyBlockedStoneEgress(serverLevel);
        return this.temporarilyBlockedStoneEgress.containsKey(pos.immutable());
    }

    private void expireTemporarilyBlockedStoneEgress(ServerLevel serverLevel) {
        if (this.temporarilyBlockedStoneEgress.isEmpty()) {
            return;
        }

        long now = serverLevel.getGameTime();
        this.temporarilyBlockedStoneEgress.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private static int horizontalDistanceSqr(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static void addBodyColumn(List<BlockPos> candidates, BlockPos feet) {
        if (feet == null) {
            return;
        }
        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
    }

    private boolean traceStoneStop(String reason) {
        this.setStoneDiagnostic("stone stop: " + reason
                + " target=" + posText(this.targetPos)
                + " stand=" + posText(this.standPos)
                + " clear=" + posText(this.clearBlockAi.targetPos())
                + " clearRunning=" + this.clearBlockAi.isRunning()
                + " gatherTicks=" + this.gatherTicks);
        return false;
    }

    private void setStoneDiagnostic(String detail) {
        if (detail == null || detail.isBlank()) {
            return;
        }
        this.playerNpc.setIdleTraceDetail(detail, IDLE_DIAGNOSTIC_TICKS);
    }

    private static String posText(BlockPos pos) {
        if (pos == null) {
            return "none";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String blockStateText(ServerLevel serverLevel, BlockPos pos) {
        if (serverLevel == null
                || pos == null
                || !serverLevel.isInWorldBounds(pos)
                || !serverLevel.hasChunkAt(pos)) {
            return "none";
        }

        BlockState state = serverLevel.getBlockState(pos);
        String blockId = Optional.ofNullable(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                .map(Object::toString)
                .orElse("unknown");
        return blockId
                + ",air=" + state.isAir()
                + ",fluid=" + !state.getFluidState().isEmpty()
                + ",hardness=" + state.getDestroySpeed(serverLevel, pos)
                + ",collisionEmpty=" + state.getCollisionShape(serverLevel, pos).isEmpty()
                + ",blockEntity=" + (serverLevel.getBlockEntity(pos) != null);
    }

    private record NearbyStoneTargetCache(
            BlockPos origin,
            String dimension,
            int expiresAtTick,
            boolean result
    ) {
    }

    private static final class NavigationPathBudget {
        private int remaining;

        private NavigationPathBudget(int remaining) {
            this.remaining = Math.max(0, remaining);
        }

        private boolean tryConsume() {
            if (this.remaining <= 0) {
                return false;
            }
            this.remaining--;
            return true;
        }
    }
}
