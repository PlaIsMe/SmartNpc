package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public class ExploreAroundGoal extends Goal {
    private static final String LOG_EXPLORATION_DETAIL = "exploring for logs";
    private static final String FARM_AREA_EXPLORATION_DETAIL = "exploring for a farm area";
    private static final String STONE_EXPLORATION_DETAIL = "exploring for stone";
    private static final int[][] SEARCH_DISTANCE_BANDS = {
            {12, 18},
            {9, 12},
            {6, 9},
            {3, 6},
            {0, 3}
    };
    private static final int ATTEMPTS_PER_RADIUS = 1;
    private static final int MAX_EXPLORE_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20 * 2;
    private static final int RADIUS_RETRY_COOLDOWN_TICKS = 20;
    private static final int OVERLOADED_RADIUS_RETRY_COOLDOWN_TICKS = 20 * 3;
    private static final int WATER_ESCAPE_RETRY_MIN_TICKS = 20 * 8;
    private static final int WATER_ESCAPE_RETRY_RANDOM_TICKS = 20 * 8;
    private static final int UPWARD_ESCAPE_REQUEST_TICKS = 20 * 8;
    private static final int MAX_EXPLORE_PILLAR_BLOCKS = 10;
    private static final int MAX_EXPLORE_SAFE_DROP_BLOCKS = 5;
    private static final int EXPLORE_CAN_USE_INTERVAL_TICKS = 20;
    private static final int CONTINUE_PREDICATE_INTERVAL_TICKS = 20;
    private static final int OVERLOADED_CONTINUE_PREDICATE_INTERVAL_TICKS = 20 * 2;
    private static final int FAILED_CLIMB_FALLBACK_REQUEST_TICKS = 20 * 15;
    private static final int EXPLORATION_CLIMB_OWNER_TICKS = 20 * 60;
    private static final int FAILED_CLIMB_FALLBACK_RETRY_TICKS = 20;
    private static final int FAILED_CLIMB_FALLBACK_RADIUS = 8;
    private static final int FAILED_CLIMB_FALLBACK_MIN_DISTANCE = 4;
    private static final int FAILED_CLIMB_FALLBACK_VERTICAL_RANGE = 2;
    private static final int FAILED_CLIMB_FALLBACK_PATH_CHECKS = 1;
    private static final int BUILDING_LOG_LOCAL_SURFACE_RADIUS = 18;
    private static final int BUILDING_LOG_LOCAL_SURFACE_MIN_RADIUS = 4;
    private static final int BUILDING_LOG_LOCAL_SURFACE_COLUMN_CHECKS = 64;
    private static final int BUILDING_LOG_LOCAL_SURFACE_SCAN_STRIDE = 67;
    private static final int BUILDING_LOG_LOCAL_SURFACE_RANDOM_POOL = 10;
    private static final int BUILDING_LOG_LOCAL_SURFACE_PATH_CHECKS = 1;
    private static final float EXPLORE_SELECTION_PATH_NODE_MULTIPLIER = 0.03F;
    private static final float ACTIVE_EXPLORATION_PATH_NODE_MULTIPLIER = 0.05F;
    private static final float OVERLOADED_EXPLORATION_PATH_NODE_MULTIPLIER = 0.01F;
    private static final double BUILDING_LOG_SCAN_RESET_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int RETURN_HOME_REQUEST_TICKS = 20 * 120;
    private static final int RETURN_HOME_RETRY_COOLDOWN_TICKS = 20 * 15;
    private static final int MIN_LOCAL_SURFACE_NEIGHBORS = 2;
    private static final int MAINLAND_SCAN_RADIUS = 18;
    private static final int MAINLAND_COLUMNS_PER_SLICE = 192;
    private static final int MAINLAND_SCAN_STRIDE = 73;
    private static final int MIN_MAINLAND_SURFACE_NEIGHBORS = 3;
    private static final int[][] MAINLAND_COLUMN_OFFSETS = createMainlandColumnOffsets();
    private static final int RECENT_ROUTE_MEMORY_TICKS = 20;
    private static final int ROUTE_FOLIAGE_NODE_LOOKAHEAD = 3;
    private static final int MAX_ROUTE_FOLIAGE_CLEAR_ATTEMPTS = 4;
    private static final double ROUTE_FOLIAGE_CLEAR_DISTANCE_SQR = 4.5D * 4.5D;
    private static final int MINING_LOG_COLUMN_DROP_RADIUS = 8;
    private static final int MINING_LOG_COLUMN_DROP_MAX_FALL = 6;
    private static final int MINING_LOG_COLUMN_DROP_TICKS = 20 * 2;
    private static final int MINING_LOG_COLUMN_DROP_MAX_SOLID_SIDE_SUPPORTS = 1;
    private static final int LOG_RETRY_RELOCATION_MAX_ATTEMPTS = 3;
    private static final int LOG_RETRY_RELOCATION_MAX_TICKS = 20 * 6;
    private static final int LOG_RETRY_RELOCATION_MAX_SAFE_FALL = 2;
    private static final int LOG_RETRY_RECENT_STAND_TICKS = 20 * 30;
    private static final int LOG_RETRY_MAX_RECENT_STANDS = 12;
    // Two admitted path failures from the exact same feet are enough to begin the bounded,
    // loaded-only surface validation. The geometric validation below still rejects ordinary
    // exposed slopes/cliffs; requiring a third failure kept confined NPCs idle for another full
    // progressive-band cycle (about twenty seconds in the ShyNieke trace).
    private static final int STALLED_UPWARD_FAILURES_REQUIRED = 2;
    private static final int STALLED_UPWARD_EVIDENCE_TICKS = 20 * 30;
    private static final int STALLED_SURFACE_SCAN_RADIUS = 6;
    private static final int STALLED_SURFACE_COLUMNS_PER_SLICE = 8;
    private static final int STALLED_SURFACE_MIN_GAIN = 4;
    private static final int STALLED_SHELTER_CEILING_SCAN_UP = 6;
    private static final int STALLED_WALKOUT_RADIUS = 3;
    private static final int MAX_STALLED_WALKOUT_STANDS = 48;
    private static final int[][] STALLED_SURFACE_COLUMN_OFFSETS = createStalledSurfaceColumnOffsets();
    private static final float INITIAL_SPRINT_CHANCE = 0.35F;
    private static final float WALK_TO_SPRINT_CHANCE = 0.55F;
    private static final int MIN_WALK_PACE_TICKS = 20 * 3;
    private static final int MAX_WALK_PACE_TICKS = 20 * 8;
    private static final int MIN_SPRINT_PACE_TICKS = 20 * 2;
    private static final int MAX_SPRINT_PACE_TICKS = 20 * 5;
    private static final double MIN_SPRINT_DISTANCE_SQR = 10.0D * 10.0D;
    private static final double ARRIVAL_DISTANCE_SQR = 3.0D * 3.0D;
    private static final Map<PlayerNpcEntity, FailedClimbFallbackRequest> FAILED_CLIMB_FALLBACK_REQUESTS = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, ExplorationClimbOwner> EXPLORATION_CLIMB_OWNERS = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, StalledSurfaceEscapeState> STALLED_SURFACE_ESCAPE_STATES = new WeakHashMap<>();
    private static final Set<PlayerNpcEntity> ACTIVE_SUPPLY_EXPLORERS = Collections.newSetFromMap(new WeakHashMap<>());

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final PathStuckFallbackAi pathStuckFallbackAi;
    private final ToolAi routeFoliageToolAi;
    private final BreakingBlockAi routeFoliageBreakingBlockAi;
    private final ClearBlockAi routeFoliageClearBlockAi;
    private final double speed;
    private final String detail;
    private final Predicate<ServerLevel> shouldExplore;
    private final Predicate<ServerLevel> shouldYieldToSubGoal;
    private final boolean stopForHomeNow;
    private final boolean continueAcrossReachedTargets;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(EXPLORE_CAN_USE_INTERVAL_TICKS);
    private BlockPos targetPos;
    private Path plannedTargetPath;
    private int exploreTicks;
    private int repathTicks;
    private int searchRadiusIndex;
    private int retryWaitTicks;
    private int nextSearchTick;
    private int nextContinuePredicateCheckTick;
    private int subGoalProbeReadyTick = Integer.MIN_VALUE;
    private boolean initialRoutePending;
    private boolean continuePredicatesAllowed = true;
    private boolean waitingForRetry;
    private BlockPos forcedDropTargetPos;
    private BlockPos forcedDropStartPos;
    private int forcedDropTicks;
    private int movementPaceTicks;
    private boolean explorationSprinting;
    private boolean failedClimbFallbackWalk;
    private boolean localWaterEscape;
    private boolean mainlandWaterRecovery;
    private BlockPos mainlandScanOrigin;
    private int mainlandScanCursor;
    private final Set<BlockPos> skippedRouteFoliage = new HashSet<>();
    private List<BlockPos> recentRouteNodes = List.of();
    private BlockPos recentRouteTarget;
    private Path recentRouteSourcePath;
    private int recentRouteSourceNextNode = -1;
    private BlockPos requestedRouteFoliageClear;
    private BlockPos buildingLogSurfaceScanOrigin;
    private int recentRouteUntilTick;
    private int routeFoliageClearAttempts;
    private int buildingLogSurfaceScanCursor = -1;
    private final Map<BlockPos, Integer> recentFailedLogRetryStands = new HashMap<>();
    private boolean logRetryRelocationPending;
    private boolean logRetryRelocationStarted;
    private boolean logRetryRelocationMoved;
    private boolean logRetryCompletedFullSearch;
    private int logRetryRelocationAttempts;
    private int logRetryRelocationDeadlineTick;
    private BlockPos logRetryRelocationOrigin;
    private BlockPos logRetryDirectionHint;

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal
    ) {
        this(playerNpc, speed, detail, shouldExplore, shouldYieldToSubGoal, true, true, false);
    }

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal,
            boolean allowUpwardEscapeRequest
    ) {
        this(playerNpc, speed, detail, shouldExplore, shouldYieldToSubGoal, allowUpwardEscapeRequest, true, false);
    }

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal,
            boolean allowUpwardEscapeRequest,
            boolean stopForHomeNow
    ) {
        this(
                playerNpc,
                speed,
                detail,
                shouldExplore,
                shouldYieldToSubGoal,
                allowUpwardEscapeRequest,
                stopForHomeNow,
                false
        );
    }

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal,
            boolean allowUpwardEscapeRequest,
            boolean stopForHomeNow,
            boolean continueAcrossReachedTargets
    ) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.pathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.routeFoliageToolAi = new ToolAi(playerNpc);
        this.routeFoliageBreakingBlockAi = new BreakingBlockAi(playerNpc, this.routeFoliageToolAi);
        this.routeFoliageClearBlockAi = new ClearBlockAi(playerNpc, this.routeFoliageBreakingBlockAi);
        this.speed = speed;
        this.detail = detail;
        this.shouldExplore = shouldExplore;
        this.shouldYieldToSubGoal = shouldYieldToSubGoal;
        this.stopForHomeNow = stopForHomeNow;
        this.continueAcrossReachedTargets = continueAcrossReachedTargets;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean isSupplyExplorationActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null && ACTIVE_SUPPLY_EXPLORERS.contains(playerNpc);
    }

    public static boolean isRetainedStallEscapeRequest(PlayerNpcEntity playerNpc, BlockPos target) {
        if (playerNpc == null || target == null) {
            return false;
        }
        ExplorationClimbOwner owner = EXPLORATION_CLIMB_OWNERS.get(playerNpc);
        if (owner == null || playerNpc.tickCount >= owner.expiresAtTick()) {
            EXPLORATION_CLIMB_OWNERS.remove(playerNpc);
            return false;
        }
        return owner.retainedStallEvidence() && owner.target().equals(target);
    }

    public static void requestSafeWalkAfterFailedClimb(PlayerNpcEntity playerNpc, BlockPos failedTarget) {
        if (playerNpc == null || failedTarget == null || playerNpc.level().isClientSide()) {
            return;
        }
        ExplorationClimbOwner owner = EXPLORATION_CLIMB_OWNERS.get(playerNpc);
        if (owner == null
                || playerNpc.tickCount >= owner.expiresAtTick()
                || !owner.target().equals(failedTarget)) {
            EXPLORATION_CLIMB_OWNERS.remove(playerNpc);
            return;
        }
        EXPLORATION_CLIMB_OWNERS.remove(playerNpc);
        FAILED_CLIMB_FALLBACK_REQUESTS.put(playerNpc, new FailedClimbFallbackRequest(
                failedTarget.immutable(),
                owner.detail(),
                playerNpc.tickCount + FAILED_CLIMB_FALLBACK_REQUEST_TICKS,
                playerNpc.tickCount
        ));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null) {
            return false;
        }
        if (this.shouldStopForHomeNow(serverLevel)) {
            return false;
        }
        if (this.playerNpc.getUpwardEscapeTarget() != null) {
            return false;
        }
        if (this.tryUseFailedClimbFallback(serverLevel)) {
            return true;
        }
        if (this.playerNpc.getHoleEscapeCooldown() > 0) {
            return false;
        }

        if (this.playerNpc.tickCount < this.nextSearchTick) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (!this.shouldExplore.test(serverLevel)) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.nextSearchTick = this.playerNpc.tickCount + 1 + this.playerNpc.getRandom().nextInt(4);
            return false;
        }
        // Survival escape must not run the resource predicate/tree scan first. A wet NPC has no
        // usable land work route, and the local escape state below owns this admitted batch.
        if (!this.isInWater(serverLevel)) {
            if (this.shouldYieldToSubGoal.test(serverLevel)) {
                this.subGoalProbeReadyTick = Integer.MIN_VALUE;
                return false;
            }
            if (this.subGoalProbeReadyTick == Integer.MIN_VALUE) {
                // Separate the bounded local supply probe from exploration target selection.
                this.subGoalProbeReadyTick = this.playerNpc.tickCount + 1;
                this.nextSearchTick = this.playerNpc.tickCount + 1;
                this.canUseThrottle.retryIn(this.playerNpc, 1);
                return false;
            }
            if (this.playerNpc.tickCount < this.subGoalProbeReadyTick) {
                return false;
            }
            // GoalSelector may next evaluate several ticks after the deadline. Readiness is a
            // lower bound, never an exact-tick rendezvous that can be missed forever.
            this.subGoalProbeReadyTick = Integer.MIN_VALUE;
        } else {
            this.subGoalProbeReadyTick = Integer.MIN_VALUE;
        }

        this.clearForcedDrop();
        this.waitingForRetry = false;
        this.retryWaitTicks = 0;
        this.targetPos = this.findReachableSurfaceTarget(serverLevel);
        if (this.targetPos == null) {
            if (this.isInWater(serverLevel)) {
                this.scheduleWaterEscapeRetry();
                return false;
            }
            if (this.usesLogRetryRelocation()) {
                // The initial miss happens before GoalSelector has admitted this goal. Return true
                // in retry mode so it owns MOVE while the bounded relocation runs.
                this.scheduleRetry(serverLevel);
                return this.waitingForRetry;
            }
            if (this.tryStartMiningLogColumnDrop(serverLevel)) {
                return true;
            }
            this.scheduleRetry(serverLevel);
            return false;
        }
        this.initialRoutePending = true;
        return this.targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.playerNpc.isAlive()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || !this.failedClimbFallbackWalk && this.playerNpc.getHoleEscapeCooldown() > 0
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (this.shouldStopForHomeNow(serverLevel)) {
            return false;
        }

        if (this.localWaterEscape) {
            return this.targetPos != null
                    && this.exploreTicks < MAX_EXPLORE_TICKS
                    && this.isInWater(serverLevel);
        }

        if (this.forcedDropTargetPos != null) {
            return this.pathStuckFallbackAi.isRunning()
                    || (this.forcedDropTicks > 0
                    && this.forcedDropStartPos != null
                    && (this.playerNpc.blockPosition().equals(this.forcedDropStartPos)
                    || !this.playerNpc.onGround()));
        }

        if (this.playerNpc.tickCount >= this.nextContinuePredicateCheckTick) {
            if (PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                int continuePredicateInterval = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                        ? OVERLOADED_CONTINUE_PREDICATE_INTERVAL_TICKS
                        : CONTINUE_PREDICATE_INTERVAL_TICKS;
                this.nextContinuePredicateCheckTick = this.playerNpc.tickCount
                        + continuePredicateInterval
                        + this.playerNpc.getRandom().nextInt(5);
                this.continuePredicatesAllowed = this.shouldExplore.test(serverLevel)
                        && !this.shouldYieldToSubGoal.test(serverLevel);
            } else {
                this.nextContinuePredicateCheckTick = this.playerNpc.tickCount
                        + 1
                        + this.playerNpc.getRandom().nextInt(4);
            }
        }
        if (!this.continuePredicatesAllowed) {
            return false;
        }

        // A failed ordinary route owns its short bounded retry window. Releasing MOVE here made
        // stop() erase waitingForRetry, leaving no goal responsible for selecting the next target
        // until a later activation happened to win arbitration.
        if (this.waitingForRetry) {
            return this.exploreTicks < MAX_EXPLORE_TICKS;
        }

        if (this.routeFoliageClearBlockAi.isRunning()) {
            return this.targetPos != null && this.exploreTicks < MAX_EXPLORE_TICKS;
        }

        return this.targetPos != null
                && this.exploreTicks < MAX_EXPLORE_TICKS
                && (this.continueAcrossReachedTargets
                || this.distanceToTargetSqr() > ARRIVAL_DISTANCE_SQR);
    }

    @Override
    public void start() {
        try {
            if (LOG_EXPLORATION_DETAIL.equals(this.detail) || STONE_EXPLORATION_DETAIL.equals(this.detail)) {
                ACTIVE_SUPPLY_EXPLORERS.add(this.playerNpc);
            }
            this.exploreTicks = 0;
            this.repathTicks = 0;
            this.routeFoliageClearBlockAi.stop();
            this.routeFoliageToolAi.restoreMainHand();
            this.skippedRouteFoliage.clear();
            this.recentRouteNodes = List.of();
            this.recentRouteTarget = null;
            this.recentRouteSourcePath = null;
            this.recentRouteSourceNextNode = -1;
            this.requestedRouteFoliageClear = null;
            this.recentRouteUntilTick = 0;
            this.routeFoliageClearAttempts = 0;
            this.stopExplorationSprint();
            this.continuePredicatesAllowed = true;
            this.nextContinuePredicateCheckTick = this.playerNpc.tickCount
                    + 1
                    + Math.floorMod(this.playerNpc.getUUID().hashCode(), CONTINUE_PREDICATE_INTERVAL_TICKS);
            this.playerNpc.setCurrentAiState("ai.player_npc.exploring");
            if (this.waitingForRetry) {
                this.playerNpc.setCurrentAiDetail(this.retryDetail());
                return;
            }
            if (this.forcedDropTargetPos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
                this.forcedDropTicks = Math.max(this.forcedDropTicks, MINING_LOG_COLUMN_DROP_TICKS);
                this.tickMiningLogColumnDrop(serverLevel);
                return;
            }
            this.startRandomMovementPace();
            this.playerNpc.setCurrentAiDetail(this.failedClimbFallbackWalk
                    ? "walking after blocked exploration climb"
                    : this.detail);
            if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
                if (this.localWaterEscape) {
                    this.pathNavigationAi.tickLocalWaterEscape(serverLevel, this.speed);
                    return;
                }
                if (!this.initialRoutePending) {
                    this.moveToTarget(serverLevel);
                }
            }
        } finally {
            this.applyActiveNavigationBudget();
        }
    }

    @Override
    public void tick() {
        try {
            this.exploreTicks++;
            if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
                this.targetPos = null;
                return;
            }
            this.resetStalledSurfaceEscapeEvidenceAfterMovement();
            if (this.targetPos == null) {
                if (this.waitingForRetry) {
                    this.tickRetryWait(serverLevel);
                }
                return;
            }

            if (this.localWaterEscape) {
                if (this.pathNavigationAi.tickLocalWaterEscape(serverLevel, this.speed)) {
                    return;
                }
                this.targetPos = null;
                this.localWaterEscape = false;
                this.scheduleWaterEscapeRetry();
                this.playerNpc.setCurrentAiDetail("water escape unavailable; backing off");
                return;
            }

            if (this.mainlandWaterRecovery && this.isStrandedOverWater(serverLevel)) {
                this.playerNpc.getNavigation().stop();
                this.playerNpc.getJumpControl().jump();
                this.playerNpc.getMoveControl().setWantedPosition(
                        this.targetPos.getX() + 0.5D,
                        this.playerNpc.getY(),
                        this.targetPos.getZ() + 0.5D,
                        Math.min(1.0D, this.speed)
                );
                this.playerNpc.setCurrentAiDetail("leaving water support toward mainland");
                return;
            }
            if (this.mainlandWaterRecovery
                    && this.pathNavigationAi.tickWaterTravel(serverLevel, this.targetPos, this.speed)) {
                this.initialRoutePending = false;
                return;
            }

            if (this.initialRoutePending) {
                this.initialRoutePending = false;
                this.moveToTarget(serverLevel);
                this.repathTicks = REPATH_INTERVAL_TICKS;
                return;
            }

            if (this.pathNavigationAi.tickWaterTravel(serverLevel, this.targetPos, this.speed)) {
                return;
            }

            if (this.forcedDropTargetPos != null) {
                this.tickMiningLogColumnDrop(serverLevel);
                return;
            }

            if (this.routeFoliageClearBlockAi.isRunning()) {
                this.tickRouteFoliageClear(serverLevel);
                return;
            }

            if (this.continueAcrossReachedTargets && this.distanceToTargetSqr() <= ARRIVAL_DISTANCE_SQR) {
                this.continueFromReachedTarget(serverLevel);
                return;
            }

            this.rememberCurrentNavigationRoute();
            boolean shouldRepath = this.repathTicks-- <= 0;
            boolean navigationEnded = this.playerNpc.getNavigation().isDone()
                    || this.playerNpc.getNavigation().isStuck();
            if (shouldRepath
                    && navigationEnded
                    && this.tryStartRouteFoliageClear(serverLevel, true)) {
                this.repathTicks = REPATH_INTERVAL_TICKS;
                return;
            }
            if (shouldRepath
                    && navigationEnded
                    && (this.playerNpc.getNavigation().getPath() == null || this.playerNpc.getNavigation().isStuck())
                    && !this.isGenuinelyLocalDescent(this.playerNpc.blockPosition(), this.targetPos)) {
                this.playerNpc.setCurrentAiDetail("exploration route ended; choosing another target");
                BlockPos failedRouteTarget = this.targetPos;
                this.targetPos = null;
                this.scheduleRetry(
                        serverLevel,
                        failedRouteTarget,
                        this.playerNpc.getNavigation().getPath() == null
                );
                return;
            }

            this.tickRandomMovementPace();
            this.playerNpc.getLookControl().setLookAt(
                    this.targetPos.getX() + 0.5D,
                    this.targetPos.getY(),
                    this.targetPos.getZ() + 0.5D,
                    30.0F,
                    30.0F
            );
            if (shouldRepath && navigationEnded) {
                this.moveToTarget(serverLevel);
                this.repathTicks = REPATH_INTERVAL_TICKS;
            } else if (shouldRepath) {
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
        } finally {
            // createBoundedPath deliberately restores the navigation default. Exploration keeps a
            // smaller budget installed while it owns MOVE so vanilla delayed recomputation during
            // PathfinderMob.super.tick cannot expand into a full 48-block synchronous search.
            this.applyActiveNavigationBudget();
        }
    }

    @Override
    public void stop() {
        ACTIVE_SUPPLY_EXPLORERS.remove(this.playerNpc);
        this.targetPos = null;
        this.plannedTargetPath = null;
        this.initialRoutePending = false;
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.retryWaitTicks = 0;
        this.nextContinuePredicateCheckTick = 0;
        this.continuePredicatesAllowed = true;
        this.waitingForRetry = false;
        this.cancelLogRetryRelocation();
        this.failedClimbFallbackWalk = false;
        this.localWaterEscape = false;
        this.mainlandWaterRecovery = false;
        this.routeFoliageClearBlockAi.stop();
        this.routeFoliageToolAi.restoreMainHand();
        this.skippedRouteFoliage.clear();
        this.recentRouteNodes = List.of();
        this.recentRouteTarget = null;
        this.recentRouteSourcePath = null;
        this.recentRouteSourceNextNode = -1;
        this.requestedRouteFoliageClear = null;
        this.recentRouteUntilTick = 0;
        this.routeFoliageClearAttempts = 0;
        this.stopExplorationSprint();
        this.clearForcedDrop();
        this.pathStuckFallbackAi.stop();
        this.pathNavigationAi.stopWaterTravel();
        // GoalSelector releases MOVE ownership without clearing PathNavigation's retained path.
        // Stop the exploration route before restoring the default node budget, otherwise a later
        // idle super.tick may synchronously recompute that stale long-range path at full cost.
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getNavigation().resetMaxVisitedNodesMultiplier();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void applyActiveNavigationBudget() {
        float multiplier = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                ? OVERLOADED_EXPLORATION_PATH_NODE_MULTIPLIER
                : ACTIVE_EXPLORATION_PATH_NODE_MULTIPLIER;
        this.playerNpc.getNavigation().setMaxVisitedNodesMultiplier(multiplier);
    }

    private BlockPos findReachableSurfaceTarget(ServerLevel serverLevel) {
        this.plannedTargetPath = null;
        BlockPos center = this.playerNpc.blockPosition();
        boolean waterTravel = this.isInWater(serverLevel);
        boolean strandedOverWater = this.isStrandedOverWater(serverLevel);
        this.localWaterEscape = false;
        this.mainlandWaterRecovery = false;
        if ((waterTravel || strandedOverWater) && this.shouldSeekMainlandFirst()) {
            BlockPos mainland = this.findDryMainlandTarget(serverLevel, center);
            if (mainland != null) {
                this.mainlandWaterRecovery = true;
                this.searchRadiusIndex = 0;
                return mainland;
            }
        }
        if (waterTravel) {
            if (this.pathNavigationAi.canStartLocalWaterEscape(serverLevel)) {
                this.localWaterEscape = true;
                this.searchRadiusIndex = 0;
                return center.immutable();
            }
            return null;
        }
        int[] band = SEARCH_DISTANCE_BANDS[Math.max(0, Math.min(this.searchRadiusIndex, SEARCH_DISTANCE_BANDS.length - 1))];
        for (int attempt = 0; attempt < ATTEMPTS_PER_RADIUS; attempt++) {
            double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
            int distance = this.randomDistanceInBand(band[0], band[1]);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!this.isSafeExploreTarget(serverLevel, center, candidate)) {
                continue;
            }

            if (this.shouldUseBuildingSupplyLocalSurfaceFallback()) {
                Path path = PathNavigationAi.createBoundedPath(
                        this.playerNpc,
                        candidate,
                        EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
                );
                if (!this.pathNavigationAi.isExactPathTo(candidate, path)) {
                    continue;
                }
                this.plannedTargetPath = path;
            }

            return candidate.immutable();
        }
        if (this.shouldUseBuildingSupplyLocalSurfaceFallback()) {
            return this.findBuildingLogLocalSurfaceTarget(serverLevel, center);
        }
        return null;
    }

    private boolean shouldSeekMainlandFirst() {
        return LOG_EXPLORATION_DETAIL.equals(this.detail)
                || FARM_AREA_EXPLORATION_DETAIL.equals(this.detail);
    }

    @Nullable
    private BlockPos findDryMainlandTarget(ServerLevel serverLevel, BlockPos center) {
        if (this.mainlandScanOrigin == null
                || this.mainlandScanOrigin.distSqr(center) > 16.0D) {
            this.mainlandScanOrigin = center.immutable();
            this.mainlandScanCursor = 0;
        }
        List<BlockPos> candidates = new ArrayList<>();
        int total = MAINLAND_COLUMN_OFFSETS.length;
        int examined = Math.min(MAINLAND_COLUMNS_PER_SLICE, total);
        for (int attempt = 0; attempt < examined; attempt++) {
            int index = Math.floorMod(this.mainlandScanCursor + attempt * MAINLAND_SCAN_STRIDE, total);
            int[] offset = MAINLAND_COLUMN_OFFSETS[index];
            int x = this.mainlandScanOrigin.getX() + offset[0];
            int z = this.mainlandScanOrigin.getZ() + offset[1];
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (this.isSafeExploreTarget(serverLevel, center, candidate)
                    && this.hasMainlandSurfaceRoom(serverLevel, candidate)) {
                candidates.add(candidate.immutable());
            }
        }
        this.mainlandScanCursor = Math.floorMod(
                this.mainlandScanCursor + examined * MAINLAND_SCAN_STRIDE,
                total
        );
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> horizontalDistanceSqr(center, pos))
                .thenComparingInt(pos -> Math.abs(pos.getY() - center.getY())));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean isStrandedOverWater(ServerLevel serverLevel) {
        if (this.isInWater(serverLevel)) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        int waterSides = 0;
        int connectedDrySides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = feet.relative(direction);
            if (!serverLevel.hasChunkAt(adjacent)) {
                continue;
            }
            if (serverLevel.getFluidState(adjacent).is(FluidTags.WATER)
                    || serverLevel.getFluidState(adjacent.below()).is(FluidTags.WATER)) {
                waterSides++;
            }
            for (int dy = -1; dy <= 1; dy++) {
                if (this.canStandAt(serverLevel, adjacent.offset(0, dy, 0))) {
                    connectedDrySides++;
                    break;
                }
            }
        }
        return connectedDrySides == 0 && waterSides >= 2;
    }

    private boolean hasMainlandSurfaceRoom(ServerLevel serverLevel, BlockPos pos) {
        int neighbors = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos adjacent = pos.relative(direction).offset(0, dy, 0);
                if (this.isTerrainSupportedStand(serverLevel, adjacent)
                        && serverLevel.canSeeSky(adjacent.above())) {
                    neighbors++;
                    break;
                }
            }
        }
        return neighbors >= MIN_MAINLAND_SURFACE_NEIGHBORS;
    }

    private boolean shouldUseBuildingSupplyLocalSurfaceFallback() {
        if (!this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)) {
            return false;
        }
        return (LOG_EXPLORATION_DETAIL.equals(this.detail)
                && this.playerNpc.shouldPrioritizeLogGathering())
                || (STONE_EXPLORATION_DETAIL.equals(this.detail)
                && this.playerNpc.shouldPrioritizeCobblestoneGathering());
    }

    private BlockPos findBuildingLogLocalSurfaceTarget(ServerLevel serverLevel, BlockPos center) {
        if (this.buildingLogSurfaceScanOrigin == null
                || this.buildingLogSurfaceScanOrigin.distSqr(center) > BUILDING_LOG_SCAN_RESET_DISTANCE_SQR) {
            this.buildingLogSurfaceScanOrigin = center.immutable();
            this.buildingLogSurfaceScanCursor = -1;
        }
        BlockPos scanCenter = this.buildingLogSurfaceScanOrigin;
        List<BlockPos> candidates = new ArrayList<>();
        int radius = BUILDING_LOG_LOCAL_SURFACE_RADIUS;
        int minRadiusSqr = BUILDING_LOG_LOCAL_SURFACE_MIN_RADIUS * BUILDING_LOG_LOCAL_SURFACE_MIN_RADIUS;
        int maxRadiusSqr = radius * radius;
        int diameter = radius * 2 + 1;
        int totalOffsets = diameter * diameter;
        int startIndex = this.buildingLogSurfaceScanCursor < 0
                ? this.playerNpc.getRandom().nextInt(totalOffsets)
                : this.buildingLogSurfaceScanCursor;
        int checkedColumns = 0;
        int attemptedOffsets = 0;
        for (;
             attemptedOffsets < totalOffsets && checkedColumns < BUILDING_LOG_LOCAL_SURFACE_COLUMN_CHECKS;
             attemptedOffsets++) {
            // The stride is coprime with the 37x37 search grid, so a bounded pass samples the
            // whole area instead of repeatedly favoring one edge or one narrow distance band.
            int index = (startIndex + attemptedOffsets * BUILDING_LOG_LOCAL_SURFACE_SCAN_STRIDE) % totalOffsets;
            int dx = index / diameter - radius;
            int dz = index % diameter - radius;
            int distanceSqr = dx * dx + dz * dz;
            if (distanceSqr < minRadiusSqr || distanceSqr > maxRadiusSqr) {
                continue;
            }

            int x = scanCenter.getX() + dx;
            int z = scanCenter.getZ() + dz;
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }
            checkedColumns++;
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                    || !this.isSafeExploreTarget(serverLevel, center, candidate)) {
                continue;
            }
            candidates.add(candidate.immutable());
        }
        this.buildingLogSurfaceScanCursor = Math.floorMod(
                startIndex + attemptedOffsets * BUILDING_LOG_LOCAL_SURFACE_SCAN_STRIDE,
                totalOffsets
        );
        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(scanCenter))
                .thenComparingInt(BlockPos::getY));
        int preferredCount = Math.min(BUILDING_LOG_LOCAL_SURFACE_RANDOM_POOL, candidates.size());
        int start = preferredCount > 1 ? this.playerNpc.getRandom().nextInt(preferredCount) : 0;
        int pathChecks = Math.min(BUILDING_LOG_LOCAL_SURFACE_PATH_CHECKS, preferredCount);
        for (int offset = 0; offset < pathChecks; offset++) {
            BlockPos candidate = candidates.get((start + offset) % preferredCount);
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
            if (this.pathNavigationAi.isExactPathTo(candidate, path)) {
                this.plannedTargetPath = path;
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean isSafeExploreTarget(ServerLevel serverLevel, BlockPos center, BlockPos pos) {
        if (!this.canStandAt(serverLevel, pos) || !serverLevel.canSeeSky(pos.above())) {
            return false;
        }
        return this.hasLocalSurfaceRoom(serverLevel, pos);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private void moveToTarget(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return;
        }

        this.rememberCurrentNavigationRoute();

        Path plannedPath = this.plannedTargetPath;
        this.plannedTargetPath = null;
        boolean moved;
        if (plannedPath != null && this.pathNavigationAi.isValidPathTo(this.targetPos, plannedPath)) {
            moved = this.playerNpc.getNavigation().moveTo(plannedPath, Math.min(this.speed, 1.0D));
        } else if (this.failedClimbFallbackWalk) {
            moved = this.pathNavigationAi.moveToExact(
                    serverLevel,
                    this.targetPos,
                    Math.min(this.speed, 1.0D),
                    0,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
        } else {
            moved = this.pathNavigationAi.moveTo(
                    serverLevel,
                    this.targetPos,
                    this.speed,
                    this.isGenuinelyLocalDescent(this.playerNpc.blockPosition(), this.targetPos)
                            ? MAX_EXPLORE_SAFE_DROP_BLOCKS
                            : 0,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
        }
        if (moved) {
            // A geometric candidate is not a successful search until navigation actually accepts
            // it. Resetting the band during candidate selection made every failed route restart
            // at the outer bands, so the nearer fallback bands were never attempted.
            // Do not clear retained stall proof merely because Navigation accepted a path: in the
            // reported shaft such paths ended without moving a block. tick() clears the proof as
            // soon as the NPC actually changes position, and arrival clears it below.
            this.searchRadiusIndex = 0;
            this.nextSearchTick = 0;
            this.rememberCurrentNavigationRoute();
            return;
        }

        if (this.tryStartRouteFoliageClear(serverLevel, true)) {
            return;
        }

        if (this.isInWater(serverLevel)) {
            this.playerNpc.getJumpControl().jump();
            BlockPos failedRouteTarget = this.targetPos;
            this.targetPos = null;
            this.playerNpc.setCurrentAiDetail("water route stalled; choosing another exploration target");
            this.scheduleRetry(serverLevel, failedRouteTarget);
            return;
        }

        if (this.failedClimbFallbackWalk) {
            this.targetPos = null;
            this.waitingForRetry = false;
            this.nextSearchTick = this.playerNpc.tickCount + RADIUS_RETRY_COOLDOWN_TICKS;
            return;
        }

        BlockPos failedRouteTarget = this.targetPos;
        this.targetPos = null;
        // moveTo returned false: this is direct path-admission evidence. Inspecting Navigation's
        // retained Path here was racy because a rejected/partial path is cleared only by the
        // scheduleRetry stop below, after evidence collection.
        this.scheduleRetry(serverLevel, failedRouteTarget, true);
    }

    private boolean isInWater(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isInWater()
                || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER)
                || !this.playerNpc.onGround()
                && serverLevel.getFluidState(feet.below()).is(FluidTags.WATER);
    }

    private void scheduleWaterEscapeRetry() {
        this.nextSearchTick = this.playerNpc.tickCount
                + WATER_ESCAPE_RETRY_MIN_TICKS
                + this.playerNpc.getRandom().nextInt(WATER_ESCAPE_RETRY_RANDOM_TICKS + 1);
    }

    /**
     * Keep only the next few nodes of the path that belongs to this exploration target. If a
     * repath fails immediately after reaching one of those nodes, the saved corridor lets us
     * identify the physical foliage collision without guessing toward an unrelated block.
     */
    private void rememberCurrentNavigationRoute() {
        if (this.targetPos == null) {
            return;
        }

        Path path = this.playerNpc.getNavigation().getPath();
        if (path == null
                || path.getNodeCount() <= 0
                || path.getEndNode() == null
                || path.getEndNode().asBlockPos().distSqr(this.targetPos) > ARRIVAL_DISTANCE_SQR) {
            return;
        }

        int firstNode = Math.min(Math.max(0, path.getNextNodeIndex()), path.getNodeCount() - 1);
        if (path == this.recentRouteSourcePath
                && firstNode == this.recentRouteSourceNextNode
                && this.targetPos.equals(this.recentRouteTarget)) {
            this.recentRouteUntilTick = this.playerNpc.tickCount + RECENT_ROUTE_MEMORY_TICKS;
            return;
        }
        int endNode = Math.min(path.getNodeCount(), firstNode + ROUTE_FOLIAGE_NODE_LOOKAHEAD);
        List<BlockPos> nodes = new ArrayList<>(endNode - firstNode);
        for (int index = firstNode; index < endNode; index++) {
            nodes.add(path.getNode(index).asBlockPos().immutable());
        }
        if (nodes.isEmpty()) {
            return;
        }

        this.recentRouteNodes = List.copyOf(nodes);
        this.recentRouteTarget = this.targetPos.immutable();
        this.recentRouteSourcePath = path;
        this.recentRouteSourceNextNode = firstNode;
        this.recentRouteUntilTick = this.playerNpc.tickCount + RECENT_ROUTE_MEMORY_TICKS;
    }

    private boolean tryStartRouteFoliageClear(ServerLevel serverLevel, boolean routeFailureConfirmed) {
        if (!routeFailureConfirmed
                || this.routeFoliageClearBlockAi.isRunning()
                || this.targetPos == null
                || this.routeFoliageClearAttempts >= MAX_ROUTE_FOLIAGE_CLEAR_ATTEMPTS) {
            return false;
        }

        BlockPos blocker = this.findConfirmedRouteFoliageBlocker(serverLevel);
        if (blocker == null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(blocker);
        if (!this.isSafeRouteFoliage(serverLevel, blocker, state)) {
            this.skippedRouteFoliage.add(blocker.immutable());
            return false;
        }

        int requiredTicks = BreakingBlockAi.requiredBreakTicks(serverLevel, blocker, state, this.playerNpc);
        boolean started = this.routeFoliageClearBlockAi.start(
                serverLevel,
                blocker,
                ExploreAroundGoal::isFoliageState,
                this.detail + " clearing foliage",
                requiredTicks,
                ROUTE_FOLIAGE_CLEAR_DISTANCE_SQR,
                true
        );
        if (!started) {
            this.skippedRouteFoliage.add(blocker.immutable());
            return false;
        }

        this.routeFoliageClearAttempts++;
        this.requestedRouteFoliageClear = blocker.immutable();
        this.stopExplorationSprint();
        this.playerNpc.setCurrentAiDetail(this.routeFoliageClearBlockAi.detail());
        return true;
    }

    private void tickRouteFoliageClear(ServerLevel serverLevel) {
        BlockPos activeTarget = this.routeFoliageClearBlockAi.targetPos();
        if (activeTarget == null
                || !this.isSafeRouteFoliage(serverLevel, activeTarget, serverLevel.getBlockState(activeTarget))) {
            if (activeTarget != null) {
                this.skippedRouteFoliage.add(activeTarget.immutable());
            }
            this.finishRouteFoliageClear(serverLevel, false);
            return;
        }

        ClearBlockAi.TickResult result = this.routeFoliageClearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            this.playerNpc.setCurrentAiDetail(this.routeFoliageClearBlockAi.detail());
            return;
        }

        this.finishRouteFoliageClear(serverLevel, result == ClearBlockAi.TickResult.DONE);
    }

    private void finishRouteFoliageClear(ServerLevel serverLevel, boolean cleared) {
        if (!cleared && this.requestedRouteFoliageClear != null) {
            this.skippedRouteFoliage.add(this.requestedRouteFoliageClear.immutable());
        }
        this.routeFoliageClearBlockAi.stop();
        this.routeFoliageToolAi.restoreMainHand();
        this.requestedRouteFoliageClear = null;
        this.playerNpc.getNavigation().stop();
        if (this.targetPos == null) {
            return;
        }

        this.playerNpc.setCurrentAiDetail(this.failedClimbFallbackWalk
                ? "walking after blocked exploration climb"
                : this.detail);
        this.moveToTarget(serverLevel);
        this.repathTicks = REPATH_INTERVAL_TICKS;
    }

    private BlockPos findConfirmedRouteFoliageBlocker(ServerLevel serverLevel) {
        if (this.targetPos == null
                || this.recentRouteTarget == null
                || !this.recentRouteTarget.equals(this.targetPos)
                || this.playerNpc.tickCount > this.recentRouteUntilTick
                || this.recentRouteNodes.isEmpty()) {
            return null;
        }

        Set<BlockPos> routeSupports = new HashSet<>();
        routeSupports.add(this.playerNpc.blockPosition().below().immutable());
        for (BlockPos node : this.recentRouteNodes) {
            routeSupports.add(node.below().immutable());
        }

        AABB segmentStart = this.playerNpc.getBoundingBox();
        for (BlockPos node : this.recentRouteNodes) {
            AABB segmentEnd = this.playerNpc.getBoundingBox().move(
                    node.getX() + 0.5D - this.playerNpc.getX(),
                    node.getY() - this.playerNpc.getY(),
                    node.getZ() + 0.5D - this.playerNpc.getZ()
            );
            BlockPos blocker = this.findSweptRouteFoliageCollision(serverLevel, segmentStart, segmentEnd, routeSupports);
            if (blocker != null) {
                return blocker;
            }
            segmentStart = segmentEnd;
        }
        return null;
    }

    private BlockPos findSweptRouteFoliageCollision(
            ServerLevel serverLevel,
            AABB segmentStart,
            AABB segmentEnd,
            Set<BlockPos> routeSupports
    ) {
        AABB sweptBody = new AABB(
                Math.min(segmentStart.minX, segmentEnd.minX) - 0.04D,
                Math.min(segmentStart.minY, segmentEnd.minY) + 0.02D,
                Math.min(segmentStart.minZ, segmentEnd.minZ) - 0.04D,
                Math.max(segmentStart.maxX, segmentEnd.maxX) + 0.04D,
                Math.max(segmentStart.maxY, segmentEnd.maxY) + 0.04D,
                Math.max(segmentStart.maxZ, segmentEnd.maxZ) + 0.04D
        );
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.betweenClosed(
                Mth.floor(sweptBody.minX),
                Mth.floor(sweptBody.minY),
                Mth.floor(sweptBody.minZ),
                Mth.floor(sweptBody.maxX),
                Mth.floor(sweptBody.maxY),
                Mth.floor(sweptBody.maxZ))) {
            BlockPos pos = mutable.immutable();
            if (routeSupports.contains(pos) || this.skippedRouteFoliage.contains(pos)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (!this.isSafeRouteFoliage(serverLevel, pos, state)
                    || state.getCollisionShape(serverLevel, pos).toAabbs().stream()
                    .map(box -> box.move(pos))
                    .noneMatch(box -> box.intersects(sweptBody))) {
                continue;
            }
            double distance = this.playerNpc.distanceToSqr(
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D
            );
            if (distance <= ROUTE_FOLIAGE_CLEAR_DISTANCE_SQR && distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best == null ? null : best.immutable();
    }

    private boolean isSafeRouteFoliage(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return pos != null
                && isFoliageState(state)
                && PlayerNpcHomeUtil.getHome(this.playerNpc)
                .map(home -> !PlayerNpcHomeUtil.isInside(home, pos))
                .orElse(true)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                && !this.playerNpc.isTemporaryPillarSupport(pos)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private static boolean isFoliageState(BlockState state) {
        return state != null
                && (state.is(BlockTags.LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT));
    }

    private boolean tryUseFailedClimbFallback(ServerLevel serverLevel) {
        FailedClimbFallbackRequest request = FAILED_CLIMB_FALLBACK_REQUESTS.get(this.playerNpc);
        if (request == null) {
            return false;
        }
        if (this.playerNpc.tickCount >= request.expiresAtTick()) {
            FAILED_CLIMB_FALLBACK_REQUESTS.remove(this.playerNpc);
            return false;
        }
        if (this.playerNpc.tickCount < request.nextAttemptTick()
                || !this.detail.equals(request.ownerDetail())) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            FAILED_CLIMB_FALLBACK_REQUESTS.put(this.playerNpc, new FailedClimbFallbackRequest(
                    request.failedTarget(),
                    request.ownerDetail(),
                    request.expiresAtTick(),
                    this.playerNpc.tickCount + 1 + this.playerNpc.getRandom().nextInt(4)
            ));
            return false;
        }
        if (!this.shouldExplore.test(serverLevel) || this.shouldYieldToSubGoal.test(serverLevel)) {
            FAILED_CLIMB_FALLBACK_REQUESTS.put(this.playerNpc, new FailedClimbFallbackRequest(
                    request.failedTarget(),
                    request.ownerDetail(),
                    request.expiresAtTick(),
                    this.playerNpc.tickCount + FAILED_CLIMB_FALLBACK_RETRY_TICKS
            ));
            return false;
        }

        BlockPos fallback = this.findSafeFailedClimbWalkTarget(serverLevel, request.failedTarget());
        if (fallback == null) {
            FAILED_CLIMB_FALLBACK_REQUESTS.put(this.playerNpc, new FailedClimbFallbackRequest(
                    request.failedTarget(),
                    request.ownerDetail(),
                    request.expiresAtTick(),
                    this.playerNpc.tickCount + FAILED_CLIMB_FALLBACK_RETRY_TICKS
            ));
            return false;
        }

        FAILED_CLIMB_FALLBACK_REQUESTS.remove(this.playerNpc);
        this.clearForcedDrop();
        this.waitingForRetry = false;
        this.retryWaitTicks = 0;
        this.failedClimbFallbackWalk = true;
        this.targetPos = fallback;
        this.nextSearchTick = this.playerNpc.tickCount + RADIUS_RETRY_COOLDOWN_TICKS;
        return true;
    }

    private BlockPos findSafeFailedClimbWalkTarget(ServerLevel serverLevel, BlockPos failedTarget) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int minDistanceSqr = FAILED_CLIMB_FALLBACK_MIN_DISTANCE * FAILED_CLIMB_FALLBACK_MIN_DISTANCE;
        int maxDistanceSqr = FAILED_CLIMB_FALLBACK_RADIUS * FAILED_CLIMB_FALLBACK_RADIUS;
        for (int dx = -FAILED_CLIMB_FALLBACK_RADIUS; dx <= FAILED_CLIMB_FALLBACK_RADIUS; dx++) {
            for (int dz = -FAILED_CLIMB_FALLBACK_RADIUS; dz <= FAILED_CLIMB_FALLBACK_RADIUS; dz++) {
                int horizontalDistanceSqr = dx * dx + dz * dz;
                if (horizontalDistanceSqr < minDistanceSqr || horizontalDistanceSqr > maxDistanceSqr) {
                    continue;
                }
                for (int dy = -FAILED_CLIMB_FALLBACK_VERTICAL_RANGE; dy <= FAILED_CLIMB_FALLBACK_VERTICAL_RANGE; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz).immutable();
                    if (blockDistanceSqr(candidate, failedTarget) <= minDistanceSqr
                            || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                            || FarmAi.isProtectedFarmlandBlock(this.playerNpc, candidate)
                            || !this.canStandAt(serverLevel, candidate)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }

        int checks = 0;
        while (!candidates.isEmpty() && checks++ < FAILED_CLIMB_FALLBACK_PATH_CHECKS) {
            BlockPos candidate = candidates.remove(this.playerNpc.getRandom().nextInt(candidates.size()));
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
            if (this.pathNavigationAi.isExactPathTo(candidate, path)) {
                this.plannedTargetPath = path;
                return candidate.immutable();
            }
        }
        return null;
    }

    private void startRandomMovementPace() {
        boolean sprint = this.canSprintTowardTarget()
                && this.playerNpc.getRandom().nextFloat() < INITIAL_SPRINT_CHANCE;
        this.setExplorationSprinting(sprint);
        this.movementPaceTicks = sprint ? this.nextSprintPaceTicks() : this.nextWalkPaceTicks();
    }

    private void tickRandomMovementPace() {
        if (!this.canSprintTowardTarget()) {
            if (this.explorationSprinting) {
                this.setExplorationSprinting(false);
                this.movementPaceTicks = this.nextWalkPaceTicks();
            } else if (this.movementPaceTicks > 0) {
                this.movementPaceTicks--;
            }
            return;
        }

        this.setExplorationSprinting(this.explorationSprinting);
        if (this.movementPaceTicks-- > 0) {
            return;
        }

        if (this.explorationSprinting) {
            this.setExplorationSprinting(false);
            this.movementPaceTicks = this.nextWalkPaceTicks();
            return;
        }

        boolean sprint = this.playerNpc.getRandom().nextFloat() < WALK_TO_SPRINT_CHANCE;
        this.setExplorationSprinting(sprint);
        this.movementPaceTicks = sprint ? this.nextSprintPaceTicks() : this.nextWalkPaceTicks();
    }

    private boolean canSprintTowardTarget() {
        return this.targetPos != null
                && this.forcedDropTargetPos == null
                && !this.playerNpc.isShiftKeyDown()
                && !this.playerNpc.isCrouching()
                && !this.playerNpc.isInWater()
                && !this.playerNpc.isInLava()
                && this.distanceToTargetSqr() >= MIN_SPRINT_DISTANCE_SQR;
    }

    private void setExplorationSprinting(boolean sprinting) {
        this.explorationSprinting = sprinting;
        if (this.playerNpc.isSprinting() != sprinting) {
            this.playerNpc.setSprinting(sprinting);
        }
    }

    private void continueFromReachedTarget(ServerLevel serverLevel) {
        this.resetStalledSurfaceEscapeEvidence();
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setCurrentAiDetail("waiting to choose next exploration target");
            return;
        }
        if (!this.shouldExplore.test(serverLevel) || this.shouldYieldToSubGoal.test(serverLevel)) {
            this.continuePredicatesAllowed = false;
            this.targetPos = null;
            this.playerNpc.getNavigation().stop();
            return;
        }

        BlockPos reachedTarget = this.targetPos;
        BlockPos nextTarget = this.findReachableSurfaceTarget(serverLevel);
        if (nextTarget == null) {
            this.targetPos = null;
            this.scheduleRetry(serverLevel, reachedTarget);
            return;
        }

        this.targetPos = nextTarget;
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.skippedRouteFoliage.clear();
        this.recentRouteNodes = List.of();
        this.recentRouteTarget = null;
        this.recentRouteSourcePath = null;
        this.recentRouteSourceNextNode = -1;
        this.requestedRouteFoliageClear = null;
        this.recentRouteUntilTick = 0;
        this.routeFoliageClearAttempts = 0;
        this.startRandomMovementPace();
        this.playerNpc.setCurrentAiDetail(this.detail);
        this.moveToTarget(serverLevel);
    }

    private void stopExplorationSprint() {
        this.setExplorationSprinting(false);
        this.movementPaceTicks = 0;
    }

    private int nextWalkPaceTicks() {
        return this.randomTicksBetween(MIN_WALK_PACE_TICKS, MAX_WALK_PACE_TICKS);
    }

    private int nextSprintPaceTicks() {
        return this.randomTicksBetween(MIN_SPRINT_PACE_TICKS, MAX_SPRINT_PACE_TICKS);
    }

    private int randomTicksBetween(int minInclusive, int maxInclusive) {
        return minInclusive + this.playerNpc.getRandom().nextInt(maxInclusive - minInclusive + 1);
    }

    /**
     * Retains concrete exploration failure evidence instead of guessing from a single route. A
     * surface climb is considered only after two higher, otherwise-safe exploration targets both
     * ended with no path while the NPC remained in the exact same block.
     */
    private void recordStalledUpwardRouteFailure(ServerLevel serverLevel, BlockPos failedRouteTarget) {
        if (failedRouteTarget == null) {
            return;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (failedRouteTarget.getY() < feet.getY() + STALLED_SURFACE_MIN_GAIN) {
            // A lower/level miss is irrelevant to upward recovery, but is not contrary evidence:
            // only actual movement or successful path admission disproves the retained stall.
            return;
        }
        if (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, feet)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, feet)) {
            this.resetStalledSurfaceEscapeEvidence();
            return;
        }

        StalledSurfaceEscapeState state = STALLED_SURFACE_ESCAPE_STATES.computeIfAbsent(
                this.playerNpc,
                ignored -> new StalledSurfaceEscapeState()
        );
        if (state.failureOrigin == null
                || !state.failureOrigin.equals(feet)
                || this.playerNpc.tickCount >= state.evidenceUntilTick) {
            state.failureOrigin = feet.immutable();
            state.failureCount = 0;
        }
        state.routeHint = failedRouteTarget.immutable();
        state.evidenceUntilTick = this.playerNpc.tickCount + STALLED_UPWARD_EVIDENCE_TICKS;
        state.failureCount++;
        if (state.failureCount >= STALLED_UPWARD_FAILURES_REQUIRED && !state.scanPending) {
            state.scanPending = true;
            state.scanCursor = 0;
            state.bestSurface = null;
            this.playerNpc.setCurrentAiDetail(this.detail + " checking higher surface escape");
        }
    }

    /**
     * Consumes at most one globally scheduled slice and a fixed number of loaded height columns.
     * Returning true means this tick belongs to the retained scan, even when the final slice found
     * no candidate, so relocation/path work cannot pile onto the same server tick.
     */
    private boolean tickStalledSurfaceEscapeScan(ServerLevel serverLevel) {
        StalledSurfaceEscapeState state = STALLED_SURFACE_ESCAPE_STATES.get(this.playerNpc);
        if (state == null || !state.scanPending) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (state.failureOrigin == null
                || !feet.equals(state.failureOrigin)
                || this.playerNpc.tickCount >= state.evidenceUntilTick) {
            this.resetStalledSurfaceEscapeEvidence();
            return true;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.playerNpc.setCurrentAiDetail(this.detail + " waiting for surface escape scan");
            return true;
        }

        if (state.scanCursor == 0
                && state.routeHint != null
                && horizontalDistanceSqr(state.failureOrigin, state.routeHint)
                <= STALLED_SURFACE_SCAN_RADIUS * STALLED_SURFACE_SCAN_RADIUS
                && this.isValidStalledSurfaceCandidate(serverLevel, feet, state.routeHint)) {
            // Explore already selected this as a safe surface destination; revalidate it and keep
            // it as the first known exit even when the shaft opening is offset from our column.
            state.bestSurface = state.routeHint.immutable();
        }

        int checked = 0;
        while (state.scanCursor < STALLED_SURFACE_COLUMN_OFFSETS.length
                && checked < STALLED_SURFACE_COLUMNS_PER_SLICE) {
            int[] offset = STALLED_SURFACE_COLUMN_OFFSETS[state.scanCursor++];
            int x = state.failureOrigin.getX() + offset[0];
            int z = state.failureOrigin.getZ() + offset[1];
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }
            checked++;

            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!this.isValidStalledSurfaceCandidate(serverLevel, feet, candidate)) {
                continue;
            }
            if (state.bestSurface == null
                    || this.isBetterStalledSurfaceCandidate(state, candidate, state.bestSurface)) {
                state.bestSurface = candidate.immutable();
            }
        }

        if (state.scanCursor < STALLED_SURFACE_COLUMN_OFFSETS.length) {
            this.playerNpc.setCurrentAiDetail(this.detail + " checking higher surface escape");
            return true;
        }

        BlockPos escapeTarget = state.bestSurface;
        if (escapeTarget == null) {
            String rejection = "surface escape scan rejected: no safe loaded target within "
                    + STALLED_SURFACE_SCAN_RADIUS + " blocks";
            this.playerNpc.setCurrentAiDetail(this.detail + " " + rejection);
            this.playerNpc.setIdleTraceDetail(rejection + " from " + posText(feet), 40);
            this.resetStalledSurfaceEscapeEvidence();
            return true;
        }

        String originRejection = this.stalledSurfaceOriginRejection(serverLevel, feet);
        if (originRejection != null) {
            String rejection = "surface escape rejected: " + originRejection;
            this.playerNpc.setCurrentAiDetail(this.detail + " " + rejection);
            this.playerNpc.setIdleTraceDetail(rejection + " from " + posText(feet), 40);
            this.resetStalledSurfaceEscapeEvidence();
            return true;
        }

        this.playerNpc.getNavigation().stop();
        EXPLORATION_CLIMB_OWNERS.put(this.playerNpc, new ExplorationClimbOwner(
                escapeTarget.immutable(),
                this.detail,
                this.playerNpc.tickCount + EXPLORATION_CLIMB_OWNER_TICKS,
                true
        ));
        this.playerNpc.requestExplorationUpwardEscapeTo(
                escapeTarget,
                UPWARD_ESCAPE_REQUEST_TICKS,
                MAX_EXPLORE_PILLAR_BLOCKS
        );
        this.cancelLogRetryRelocation();
        this.waitingForRetry = false;
        this.retryWaitTicks = 0;
        this.playerNpc.setCurrentAiDetail("stalled exploration surface escape @ " + posText(escapeTarget));
        this.resetStalledSurfaceEscapeEvidence();
        return true;
    }

    private boolean hasImmediateOpenSkyWalkout(ServerLevel serverLevel, BlockPos feet) {
        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        open.add(feet.immutable());
        seen.add(feet.immutable());

        while (!open.isEmpty()) {
            BlockPos stand = open.poll();
            if (!stand.equals(feet)
                    && serverLevel.canSeeSky(stand.above())
                    && (Math.abs(stand.getX() - feet.getX()) >= STALLED_WALKOUT_RADIUS
                    || Math.abs(stand.getZ() - feet.getZ()) >= STALLED_WALKOUT_RADIUS)) {
                return true;
            }
            if (seen.size() >= MAX_STALLED_WALKOUT_STANDS) {
                // A component this open is ordinary surface/cave navigation, not a tiny shaft.
                return true;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = stand.relative(direction);
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = adjacent.offset(0, dy, 0);
                    if (Math.abs(candidate.getX() - feet.getX()) > STALLED_WALKOUT_RADIUS
                            || Math.abs(candidate.getZ() - feet.getZ()) > STALLED_WALKOUT_RADIUS) {
                        continue;
                    }
                    if (!serverLevel.hasChunkAt(candidate)) {
                        // Unknown terrain must never weaken the cliff-side safeguard or force-load
                        // a neighbor merely to authorize a vertical escape.
                        return true;
                    }
                    BlockPos immutable = candidate.immutable();
                    if (seen.contains(immutable)
                            || !this.isTerrainSupportedStand(serverLevel, immutable)) {
                        continue;
                    }
                    seen.add(immutable);
                    open.add(immutable);
                }
            }
        }
        // A sky-visible cell immediately beside the NPC can merely be another floor cell inside
        // an offset shaft. Only a continuous walk reaching the local boundary is a real walkout.
        return false;
    }

    @Nullable
    private String stalledSurfaceOriginRejection(
            ServerLevel serverLevel,
            BlockPos feet
    ) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!serverLevel.hasChunkAt(feet.relative(direction))) {
                return "adjacent origin chunk is not loaded";
            }
        }
        if (this.hasObviousNonNaturalCeiling(serverLevel, feet)) {
            return "constructed/foliage ceiling";
        }
        if (this.hasImmediateOpenSkyWalkout(serverLevel, feet)) {
            return "walkable exterior beside origin";
        }
        return null;
    }

    private boolean isValidStalledSurfaceCandidate(
            ServerLevel serverLevel,
            BlockPos feet,
            BlockPos candidate
    ) {
        int gain = candidate.getY() - feet.getY();
        return gain >= STALLED_SURFACE_MIN_GAIN
                && gain <= MAX_EXPLORE_PILLAR_BLOCKS
                && this.isLoadedSurfaceNeighborhood(serverLevel, candidate)
                && serverLevel.canSeeSky(candidate.above())
                && this.isTerrainSupportedStand(serverLevel, candidate)
                && this.hasLocalSurfaceRoom(serverLevel, candidate)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, candidate)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, candidate.below());
    }

    private boolean hasObviousNonNaturalCeiling(ServerLevel serverLevel, BlockPos feet) {
        for (int dy = 2; dy <= STALLED_SHELTER_CEILING_SCAN_UP; dy++) {
            BlockPos ceilingPos = feet.above(dy);
            BlockState ceiling = serverLevel.getBlockState(ceilingPos);
            if (ceiling.getCollisionShape(serverLevel, ceilingPos).isEmpty()) {
                continue;
            }
            if (serverLevel.getBlockEntity(ceilingPos) != null) {
                return true;
            }
            return !(ceiling.is(BlockTags.BASE_STONE_OVERWORLD)
                    || ceiling.is(BlockTags.BASE_STONE_NETHER)
                    || ceiling.is(BlockTags.DIRT)
                    || ceiling.is(BlockTags.SAND)
                    || ceiling.is(Blocks.GRAVEL)
                    || ceiling.is(Blocks.CLAY));
        }
        // An open/offset shaft intentionally has no ceiling in this column. Its repeated no-path
        // evidence and the higher-surface scan, not a fabricated roof requirement, decide escape.
        return false;
    }

    private boolean isLoadedSurfaceNeighborhood(ServerLevel serverLevel, BlockPos candidate) {
        if (!serverLevel.hasChunkAt(candidate)) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!serverLevel.hasChunkAt(candidate.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private boolean isBetterStalledSurfaceCandidate(
            StalledSurfaceEscapeState state,
            BlockPos candidate,
            BlockPos currentBest
    ) {
        double candidateHintDistance = state.routeHint == null
                ? 0.0D
                : horizontalDistanceSqr(candidate, state.routeHint);
        double bestHintDistance = state.routeHint == null
                ? 0.0D
                : horizontalDistanceSqr(currentBest, state.routeHint);
        if (candidateHintDistance != bestHintDistance) {
            return candidateHintDistance < bestHintDistance;
        }
        return blockDistanceSqr(state.failureOrigin, candidate)
                < blockDistanceSqr(state.failureOrigin, currentBest);
    }

    private void resetStalledSurfaceEscapeEvidence() {
        STALLED_SURFACE_ESCAPE_STATES.remove(this.playerNpc);
    }

    private void resetStalledSurfaceEscapeEvidenceAfterMovement() {
        StalledSurfaceEscapeState state = STALLED_SURFACE_ESCAPE_STATES.get(this.playerNpc);
        if (state != null
                && state.failureOrigin != null
                && !state.failureOrigin.equals(this.playerNpc.blockPosition())) {
            this.resetStalledSurfaceEscapeEvidence();
        }
    }

    private static int[][] createStalledSurfaceColumnOffsets() {
        List<int[]> offsets = new ArrayList<>();
        int radiusSqr = STALLED_SURFACE_SCAN_RADIUS * STALLED_SURFACE_SCAN_RADIUS;
        for (int dx = -STALLED_SURFACE_SCAN_RADIUS; dx <= STALLED_SURFACE_SCAN_RADIUS; dx++) {
            for (int dz = -STALLED_SURFACE_SCAN_RADIUS; dz <= STALLED_SURFACE_SCAN_RADIUS; dz++) {
                if (dx * dx + dz * dz <= radiusSqr) {
                    offsets.add(new int[]{dx, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
        return offsets.toArray(new int[0][]);
    }

    private static int[][] createMainlandColumnOffsets() {
        List<int[]> offsets = new ArrayList<>();
        int radiusSqr = MAINLAND_SCAN_RADIUS * MAINLAND_SCAN_RADIUS;
        for (int dx = -MAINLAND_SCAN_RADIUS; dx <= MAINLAND_SCAN_RADIUS; dx++) {
            for (int dz = -MAINLAND_SCAN_RADIUS; dz <= MAINLAND_SCAN_RADIUS; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr > 0 && distanceSqr <= radiusSqr) {
                    offsets.add(new int[]{dx, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
        return offsets.toArray(new int[0][]);
    }

    private void scheduleRetry(ServerLevel serverLevel) {
        this.scheduleRetry(serverLevel, null);
    }

    private void scheduleRetry(ServerLevel serverLevel, BlockPos failedRouteTarget) {
        this.scheduleRetry(serverLevel, failedRouteTarget, false);
    }

    private void scheduleRetry(
            ServerLevel serverLevel,
            BlockPos failedRouteTarget,
            boolean pathAdmissionFailed
    ) {
        if (pathAdmissionFailed) {
            this.recordStalledUpwardRouteFailure(serverLevel, failedRouteTarget);
        }
        StalledSurfaceEscapeState stalledEscapeState = STALLED_SURFACE_ESCAPE_STATES.get(this.playerNpc);
        boolean retainedEscapeScanPending = stalledEscapeState != null && stalledEscapeState.scanPending;
        boolean completedFullSearch = this.searchRadiusIndex >= SEARCH_DISTANCE_BANDS.length - 1;
        boolean relocateLogRetry = this.usesLogRetryRelocation();
        if (completedFullSearch
                && !relocateLogRetry
                && !retainedEscapeScanPending
                && this.requestReturnHomeAfterFailedExploration()) {
            this.searchRadiusIndex = 0;
            this.retryWaitTicks = 0;
            this.nextSearchTick = this.playerNpc.tickCount + RETURN_HOME_RETRY_COOLDOWN_TICKS;
            this.waitingForRetry = false;
            this.playerNpc.getNavigation().stop();
            return;
        }

        this.searchRadiusIndex = completedFullSearch ? 0 : this.searchRadiusIndex + 1;
        int retryCooldown = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                ? OVERLOADED_RADIUS_RETRY_COOLDOWN_TICKS
                : RADIUS_RETRY_COOLDOWN_TICKS;
        this.retryWaitTicks = retryCooldown + this.playerNpc.getRandom().nextInt(retryCooldown + 1);
        this.nextSearchTick = this.playerNpc.tickCount + this.retryWaitTicks;
        this.waitingForRetry = true;
        this.playerNpc.getNavigation().stop();
        if (relocateLogRetry) {
            this.beginLogRetryRelocation(failedRouteTarget, completedFullSearch);
        }
    }

    private boolean isGenuinelyLocalDescent(BlockPos from, BlockPos target) {
        if (from == null || target == null || target.getY() >= from.getY()) {
            return false;
        }
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        return dx * dx + dz * dz <= 2 * 2;
    }

    private boolean tryStartMiningLogColumnDrop(ServerLevel serverLevel) {
        if (!this.isMiningOnlyLogExploration()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.isOnNarrowColumnTop(serverLevel, feet)) {
            return false;
        }

        BlockPos dropTarget = this.findMiningLogColumnDropTarget(serverLevel, feet);
        if (dropTarget == null) {
            return false;
        }

        this.targetPos = dropTarget;
        this.forcedDropTargetPos = dropTarget;
        this.forcedDropStartPos = feet.immutable();
        this.forcedDropTicks = MINING_LOG_COLUMN_DROP_TICKS;
        this.waitingForRetry = false;
        this.retryWaitTicks = 0;
        this.nextSearchTick = this.playerNpc.tickCount + RADIUS_RETRY_COOLDOWN_TICKS;
        if (!this.startMiningLogColumnPathFallback(serverLevel)) {
            this.targetPos = null;
            this.clearForcedDrop();
            return false;
        }
        return true;
    }

    private boolean isMiningOnlyLogExploration() {
        return LOG_EXPLORATION_DETAIL.equals(this.detail)
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && this.playerNpc.shouldPrioritizeLogGathering();
    }

    private boolean isOnNarrowColumnTop(ServerLevel serverLevel, BlockPos feet) {
        if (!this.playerNpc.onGround()) {
            return false;
        }

        BlockPos floor = feet.below();
        if (!serverLevel.isInWorldBounds(floor)
                || !serverLevel.hasChunkAt(floor)
                || serverLevel.getBlockState(floor).getCollisionShape(serverLevel, floor).isEmpty()) {
            return false;
        }

        int solidSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = floor.relative(direction);
            if (serverLevel.hasChunkAt(side)
                    && serverLevel.getBlockState(side).isSolidRender()) {
                solidSides++;
            }
        }
        return solidSides <= MINING_LOG_COLUMN_DROP_MAX_SOLID_SIDE_SUPPORTS;
    }

    private BlockPos findMiningLogColumnDropTarget(ServerLevel serverLevel, BlockPos feet) {
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> relaxedCandidates = new ArrayList<>();
        int radiusSqr = MINING_LOG_COLUMN_DROP_RADIUS * MINING_LOG_COLUMN_DROP_RADIUS;
        for (int dx = -MINING_LOG_COLUMN_DROP_RADIUS; dx <= MINING_LOG_COLUMN_DROP_RADIUS; dx++) {
            for (int dz = -MINING_LOG_COLUMN_DROP_RADIUS; dz <= MINING_LOG_COLUMN_DROP_RADIUS; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                if (!isColumnLoaded(serverLevel, x, z)) {
                    continue;
                }
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int fall = feet.getY() - y;
                if (fall <= 0 || fall > MINING_LOG_COLUMN_DROP_MAX_FALL) {
                    continue;
                }

                BlockPos candidate = new BlockPos(x, y, z);
                if (!this.canStandAt(serverLevel, candidate)
                        || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)) {
                    continue;
                }

                if (serverLevel.canSeeSky(candidate.above()) && this.hasLocalSurfaceRoom(serverLevel, candidate)) {
                    candidates.add(candidate.immutable());
                } else {
                    relaxedCandidates.add(candidate.immutable());
                }
            }
        }

        BlockPos selected = this.selectMiningLogColumnDropTarget(candidates, feet);
        if (selected != null) {
            return selected;
        }
        return this.selectMiningLogColumnDropTarget(relaxedCandidates, feet);
    }

    private BlockPos selectMiningLogColumnDropTarget(List<BlockPos> candidates, BlockPos feet) {
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> horizontalDistanceSqr(feet, pos))
                .thenComparingInt(pos -> Math.abs(feet.getY() - pos.getY())));
        return candidates.isEmpty() ? null : candidates.get(0).immutable();
    }

    private void tickMiningLogColumnDrop(ServerLevel serverLevel) {
        if (this.forcedDropStartPos == null || this.forcedDropTargetPos == null) {
            this.targetPos = null;
            this.clearForcedDrop();
            return;
        }

        this.forcedDropTicks--;
        if (this.pathStuckFallbackAi.tick(serverLevel, this.detail)) {
            this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(this.detail));
            return;
        }

        if (!this.playerNpc.blockPosition().equals(this.forcedDropStartPos) && this.playerNpc.onGround()) {
            this.targetPos = null;
            this.clearForcedDrop();
            return;
        }

        if (this.forcedDropTicks <= 0 || !this.startMiningLogColumnPathFallback(serverLevel)) {
            this.targetPos = null;
            this.clearForcedDrop();
        }
    }

    private boolean startMiningLogColumnPathFallback(ServerLevel serverLevel) {
        if (this.forcedDropTargetPos == null) {
            return false;
        }
        boolean started = this.pathStuckFallbackAi.start(
                serverLevel,
                this.forcedDropTargetPos,
                this.detail,
                pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
        );
        this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(this.detail));
        return started;
    }

    private void clearForcedDrop() {
        this.forcedDropTargetPos = null;
        this.forcedDropStartPos = null;
        this.forcedDropTicks = 0;
        this.pathStuckFallbackAi.stop();
    }

    private boolean usesLogRetryRelocation() {
        return LOG_EXPLORATION_DETAIL.equals(this.detail);
    }

    private void beginLogRetryRelocation(BlockPos failedRouteTarget, boolean completedFullSearch) {
        this.pathStuckFallbackAi.stop();
        this.rememberFailedLogRetryStand(this.playerNpc.blockPosition());
        this.logRetryRelocationPending = true;
        this.logRetryRelocationStarted = false;
        this.logRetryRelocationMoved = false;
        this.logRetryCompletedFullSearch = completedFullSearch;
        this.logRetryRelocationAttempts = 0;
        this.logRetryRelocationDeadlineTick = this.playerNpc.tickCount + LOG_RETRY_RELOCATION_MAX_TICKS;
        this.logRetryRelocationOrigin = null;
        this.logRetryDirectionHint = failedRouteTarget == null ? null : failedRouteTarget.immutable();
    }

    private void tickLogRetryRelocation(ServerLevel serverLevel) {
        if (!this.logRetryRelocationPending) {
            return;
        }
        if (this.playerNpc.tickCount >= this.logRetryRelocationDeadlineTick) {
            this.finishLogRetryRelocation(serverLevel);
            return;
        }

        if (this.logRetryRelocationStarted) {
            boolean stillRunning = this.pathStuckFallbackAi.tick(
                    serverLevel,
                    this.detail + " retry relocation"
            );
            this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(this.retryDetail()));
            if (stillRunning) {
                return;
            }

            boolean moved = this.logRetryRelocationOrigin != null
                    && !this.playerNpc.blockPosition().equals(this.logRetryRelocationOrigin)
                    && this.playerNpc.onGround();
            if (moved) {
                this.logRetryRelocationMoved = true;
                this.rememberFailedLogRetryStand(this.logRetryRelocationOrigin);
            }
            this.logRetryRelocationStarted = false;
            this.logRetryRelocationOrigin = null;
            if (!moved) {
                this.finishLogRetryRelocation(serverLevel);
                return;
            }
        }

        if (this.logRetryRelocationAttempts >= LOG_RETRY_RELOCATION_MAX_ATTEMPTS) {
            this.finishLogRetryRelocation(serverLevel);
            return;
        }

        this.pruneRecentFailedLogRetryStands();
        this.logRetryRelocationOrigin = this.playerNpc.blockPosition().immutable();
        this.logRetryRelocationAttempts++;
        boolean started = this.pathStuckFallbackAi.startValidatedNearby(
                serverLevel,
                this.logRetryDirectionHint,
                this.detail + " retry relocation",
                pos -> this.isRejectedLogRetryStand(pos),
                LOG_RETRY_RELOCATION_MAX_SAFE_FALL
        );
        this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(this.retryDetail()));
        if (!started) {
            this.logRetryRelocationOrigin = null;
            this.finishLogRetryRelocation(serverLevel);
            return;
        }
        this.logRetryRelocationStarted = true;
    }

    private void finishLogRetryRelocation(ServerLevel serverLevel) {
        boolean moved = this.logRetryRelocationMoved;
        boolean completedFullSearch = this.logRetryCompletedFullSearch;
        this.pathStuckFallbackAi.stop();
        this.logRetryRelocationPending = false;
        this.logRetryRelocationStarted = false;
        this.logRetryRelocationMoved = false;
        this.logRetryCompletedFullSearch = false;
        this.logRetryRelocationAttempts = 0;
        this.logRetryRelocationDeadlineTick = 0;
        this.logRetryRelocationOrigin = null;
        this.logRetryDirectionHint = null;

        if (moved) {
            // Force the bounded building-supply sampler to recenter even when relocation moved less
            // than its ordinary four-block drift threshold. The next distance band now genuinely
            // searches from the new local geometry.
            this.buildingLogSurfaceScanOrigin = null;
            this.buildingLogSurfaceScanCursor = -1;
        }

        if (!moved && completedFullSearch && this.requestReturnHomeAfterFailedExploration()) {
            // A blocked fallback at the end of all bands hands ownership to ReturnHome instead of
            // starting an unbounded sequence of identical local relocation failures.
            this.waitingForRetry = false;
            this.retryWaitTicks = 0;
            this.nextSearchTick = this.playerNpc.tickCount + RETURN_HOME_RETRY_COOLDOWN_TICKS;
            return;
        }

        this.retryWaitTicks = Math.max(1, this.retryWaitTicks);
        this.nextSearchTick = this.playerNpc.tickCount + this.retryWaitTicks;
        this.playerNpc.setCurrentAiDetail(this.retryDetail());
    }

    private boolean isRejectedLogRetryStand(BlockPos pos) {
        if (pos == null || this.isRecentFailedLogRetryStand(pos)) {
            return true;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return home.map(homeArea -> PlayerNpcHomeUtil.isInsideFootprint(homeArea, pos)).orElse(false)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isProtectedFarmlandBlock(this.playerNpc, pos)
                || FarmAi.isProtectedFarmlandBlock(this.playerNpc, pos.below());
    }

    private void rememberFailedLogRetryStand(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.pruneRecentFailedLogRetryStands();
        this.recentFailedLogRetryStands.put(
                pos.immutable(),
                this.playerNpc.tickCount + LOG_RETRY_RECENT_STAND_TICKS
        );
        while (this.recentFailedLogRetryStands.size() > LOG_RETRY_MAX_RECENT_STANDS) {
            BlockPos oldest = this.recentFailedLogRetryStands.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            this.recentFailedLogRetryStands.remove(oldest);
        }
    }

    private boolean isRecentFailedLogRetryStand(BlockPos pos) {
        Integer expiresAtTick = this.recentFailedLogRetryStands.get(pos);
        if (expiresAtTick == null) {
            return false;
        }
        if (expiresAtTick <= this.playerNpc.tickCount) {
            this.recentFailedLogRetryStands.remove(pos);
            return false;
        }
        return true;
    }

    private void pruneRecentFailedLogRetryStands() {
        this.recentFailedLogRetryStands.entrySet().removeIf(
                entry -> entry.getValue() <= this.playerNpc.tickCount
        );
    }

    private void cancelLogRetryRelocation() {
        this.logRetryRelocationPending = false;
        this.logRetryRelocationStarted = false;
        this.logRetryRelocationMoved = false;
        this.logRetryCompletedFullSearch = false;
        this.logRetryRelocationAttempts = 0;
        this.logRetryRelocationDeadlineTick = 0;
        this.logRetryRelocationOrigin = null;
        this.logRetryDirectionHint = null;
    }

    private boolean requestReturnHomeAfterFailedExploration() {
        return this.playerNpc.requestReturnHomeAfterExplorationFailure(
                this.detail + " failed all distance bands; returning home",
                RETURN_HOME_REQUEST_TICKS
        );
    }

    private void tickRetryWait(ServerLevel serverLevel) {
        if (this.tickStalledSurfaceEscapeScan(serverLevel)) {
            return;
        }
        if (this.logRetryRelocationPending) {
            this.tickLogRetryRelocation(serverLevel);
            return;
        }

        this.retryWaitTicks--;
        if (this.retryWaitTicks > 0) {
            if (this.retryWaitTicks % 20 == 0) {
                float yaw = this.playerNpc.getYRot() + 45.0F + this.playerNpc.getRandom().nextFloat() * 90.0F;
                double x = this.playerNpc.getX() + Math.cos(Math.toRadians(yaw)) * 4.0D;
                double z = this.playerNpc.getZ() + Math.sin(Math.toRadians(yaw)) * 4.0D;
                this.playerNpc.getLookControl().setLookAt(x, this.playerNpc.getEyeY(), z, 20.0F, 20.0F);
            }
            this.playerNpc.setCurrentAiDetail(this.retryDetail());
            return;
        }

        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.retryWaitTicks = 1 + this.playerNpc.getRandom().nextInt(4);
            this.playerNpc.setCurrentAiDetail("waiting to retry exploration target selection");
            return;
        }

        this.waitingForRetry = false;
        this.targetPos = this.findReachableSurfaceTarget(serverLevel);
        if (this.targetPos == null) {
            if (this.isInWater(serverLevel)) {
                this.scheduleWaterEscapeRetry();
                this.continuePredicatesAllowed = false;
                this.playerNpc.setCurrentAiDetail("water escape unavailable; backing off");
                return;
            }
            if (this.usesLogRetryRelocation()) {
                this.scheduleRetry(serverLevel);
                this.playerNpc.setCurrentAiDetail(this.retryDetail());
                return;
            }
            if (this.tryStartMiningLogColumnDrop(serverLevel)) {
                return;
            }
            this.scheduleRetry(serverLevel);
            if (this.waitingForRetry) {
                this.playerNpc.setCurrentAiDetail(this.retryDetail());
            }
            return;
        }

        this.initialRoutePending = false;
        this.repathTicks = REPATH_INTERVAL_TICKS;
        this.skippedRouteFoliage.clear();
        this.recentRouteNodes = List.of();
        this.recentRouteTarget = null;
        this.recentRouteSourcePath = null;
        this.recentRouteSourceNextNode = -1;
        this.requestedRouteFoliageClear = null;
        this.recentRouteUntilTick = 0;
        this.routeFoliageClearAttempts = 0;
        this.startRandomMovementPace();
        this.playerNpc.setCurrentAiDetail(this.detail);
        this.moveToTarget(serverLevel);
    }

    private String retryDetail() {
        int[] nextBand = SEARCH_DISTANCE_BANDS[Math.max(0, Math.min(this.searchRadiusIndex, SEARCH_DISTANCE_BANDS.length - 1))];
        int seconds = Math.max(1, (this.retryWaitTicks + 19) / 20);
        String retry = this.detail + " retry range=" + nextBand[0] + "-" + nextBand[1] + " in " + seconds + "s";
        StalledSurfaceEscapeState state = STALLED_SURFACE_ESCAPE_STATES.get(this.playerNpc);
        if (state != null && state.failureCount > 0) {
            return retry + " escapeEvidence=" + Math.min(state.failureCount, STALLED_UPWARD_FAILURES_REQUIRED)
                    + "/" + STALLED_UPWARD_FAILURES_REQUIRED;
        }
        return retry;
    }

    private boolean hasLocalSurfaceRoom(ServerLevel serverLevel, BlockPos pos) {
        if (!this.isTerrainSupportedStand(serverLevel, pos)) {
            return false;
        }

        int neighbors = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos adjacent = pos.relative(direction).offset(0, dy, 0);
                if (this.isTerrainSupportedStand(serverLevel, adjacent)
                        && serverLevel.canSeeSky(adjacent.above())) {
                    neighbors++;
                    break;
                }
            }
        }
        return neighbors >= MIN_LOCAL_SURFACE_NEIGHBORS;
    }

    private boolean isTerrainSupportedStand(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canStandAt(serverLevel, pos)) {
            return false;
        }

        BlockState support = serverLevel.getBlockState(pos.below());
        return !support.is(BlockTags.LOGS) && !support.is(BlockTags.LEAVES);
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY(), this.targetPos.getZ() + 0.5D);
    }

    private static double blockDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static boolean isColumnLoaded(ServerLevel serverLevel, int blockX, int blockZ) {
        return serverLevel.hasChunk(blockX >> 4, blockZ >> 4);
    }

    private int randomDistanceInBand(int minInclusive, int maxInclusive) {
        int min = Math.max(0, Math.min(minInclusive, maxInclusive));
        int max = Math.max(min, Math.max(minInclusive, maxInclusive));
        return min + this.playerNpc.getRandom().nextInt(max - min + 1);
    }

    private boolean shouldStopForHomeNow(ServerLevel serverLevel) {
        return this.stopForHomeNow
                && PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (this.playerNpc.hasExplorationReturnHomeRequest()
                || serverLevel.isDarkOutside()
                || serverLevel.isThundering());
    }

    /** Shared per NPC so a change from one exploration purpose to another cannot erase proof. */
    private static final class StalledSurfaceEscapeState {
        private BlockPos failureOrigin;
        private BlockPos routeHint;
        private int failureCount;
        private int evidenceUntilTick;
        private boolean scanPending;
        private int scanCursor;
        private BlockPos bestSurface;
    }

    private record FailedClimbFallbackRequest(
            BlockPos failedTarget,
            String ownerDetail,
            int expiresAtTick,
            int nextAttemptTick
    ) {
    }

    private record ExplorationClimbOwner(
            BlockPos target,
            String detail,
            int expiresAtTick,
            boolean retainedStallEvidence
    ) {
    }
}
