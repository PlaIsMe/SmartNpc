package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.PillarUpAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.entity.ai.TreeAi;
import com.pla.smart_npc.entity.ai.TreeAi.Tree;
import com.pla.smart_npc.entity.ai.WaterEscapeAi;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcAdaptiveSearchScope;
import com.pla.smart_npc.util.PlayerNpcFarmPlan;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public class GatherLogsGoal extends Goal implements GatheringGoal {
    private static final Set<PlayerNpcEntity> ACTIVE_LOG_GATHERERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final int TREE_SEARCH_RADIUS = 6;
    private static final int NEARBY_LOG_TARGET_SEARCH_RADIUS = 16;
    private static final int NEARBY_LOG_TARGET_CACHE_TICKS = 20 * 2;
    private static final int CONTINUE_ELIGIBILITY_INTERVAL_TICKS = 20;
    private static final int DETAIL_PROGRESS_REFRESH_INTERVAL_TICKS = 5;
    private static final int DIRT_SEARCH_RADIUS = 10;
    private static final int MAX_LOG_PATH_CHECKS = 1;
    private static final int MAX_NEARBY_LOG_PATH_CHECKS = 1;
    private static final int MAX_DIRT_COLUMNS_PER_SEARCH_PASS = 16;
    private static final double DIRT_SEARCH_RESET_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MAX_STAND_PATH_CHECKS = 1;
    private static final int MAX_DESCENT_PATH_CHECKS = 1;
    private static final int PILLAR_RECOVERY_SCAN_INTERVAL_TICKS = 20;
    // Eligibility only needs a cheap reachability signal. A successful retained path is still
    // followed normally, but a hostile distant chunk must not spend a full tick proving a miss.
    private static final float LOCAL_SELECTION_PATH_NODE_MULTIPLIER = 0.03F;
    private static final float ACTIVE_LOG_PATH_NODE_MULTIPLIER = 0.05F;
    private static final float OVERLOADED_LOG_PATH_NODE_MULTIPLIER = 0.01F;
    private static final int MAX_GATHER_TICKS = 20 * 30;
    private static final int REQUIRED_BREAK_TICKS = 60;
    private static final int LEAF_CLEAR_TICKS = 12;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_PROTECTED_PILLAR_BLOCKS = 64;
    private static final int MAX_REQUIRED_DIRT_FOR_LOG_PILLAR = 8;
    // Relocation usually needs only an adjacent usable column. Inspect near rings first and stop
    // on success, while retaining the original radius-three recovery reach for difficult terrain.
    private static final int PILLAR_BASE_SEARCH_RADIUS = 3;
    private static final int MAX_PILLAR_BASE_COLUMNS_PER_SEARCH_PASS = 8;
    private static final int DESCENT_SEARCH_RADIUS = 8;
    private static final int MAX_DESCENT_TICKS = 20 * 10;
    private static final int MAX_PILLAR_SAFE_DROP_BLOCKS = 5;
    private static final int STAND_SCAN_BELOW_TARGET = 12;
    private static final int STAND_SCAN_ABOVE_TARGET = 2;
    private static final int MAX_IGNORED_CLEAR_BLOCKS = 32;
    private static final int MAX_IGNORED_LOG_TARGETS = 32;
    private static final int MAX_SAME_LEAF_CLEAR_TICKS = 40;
    private static final int MAX_STAND_ROUTE_FAILURES_PER_TARGET = 3;
    private static final int MAX_STAND_FALLBACK_ATTEMPTS_PER_TARGET = 1;
    private static final double BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final double STAND_REACHED_DISTANCE_SQR = 1.5D * 1.5D;
    private static final double STAND_CENTER_CORRECTION_DISTANCE_SQR = 0.35D * 0.35D;
    private static final double PILLAR_APPROACH_HORIZONTAL_DISTANCE_SQR = 2.5D * 2.5D;
    private static final List<ColumnOffset> DIRT_SEARCH_COLUMN_OFFSETS = createDirtSearchColumnOffsets();
    private static final List<ColumnOffset> PILLAR_BASE_COLUMN_OFFSETS = createPillarBaseColumnOffsets();

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final Queue<BlockPos> logQueue = new ArrayDeque<>();
    private final Set<BlockPos> protectedPillarBlocks = new LinkedHashSet<>();
    private final Set<BlockPos> ignoredClearBlocks = new LinkedHashSet<>();
    private final Set<BlockPos> ignoredLogTargets = new LinkedHashSet<>();
    private final Set<BlockPos> failedStandRoutes = new LinkedHashSet<>();
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final PathStuckFallbackAi pathStuckFallbackAi;
    private final PillarUpAi pillarUpAi;
    private final WaterEscapeAi waterEscapeAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private BlockPos targetPos;
    private BlockPos standPos;
    private BlockPos dirtTargetPos;
    private BlockPos dirtSearchOrigin;
    private BlockPos descentTargetPos;
    private Path plannedStandPath;
    private Path plannedDescentPath;
    private BlockPos lastClearTargetPos;
    private int gatherTicks;
    private int repathTicks;
    private int descentTicks;
    private int sameClearTargetTicks;
    private int nearbyUsableLogTargetCacheUntilTick;
    private int nextLogQueueScanTick;
    private int logQueueScanCompletedTick = Integer.MIN_VALUE;
    private int nextContinueEligibilityCheckTick;
    private int nextDirtSearchTick;
    private int dirtSearchColumnCursor;
    private int nextPillarBaseSearchTick;
    private int pillarBaseSearchColumnCursor;
    private BlockPos pillarBaseSearchTarget;
    private boolean continueEligibilityAllowed = true;
    private boolean nearbyUsableLogTargetCacheResult;
    private BlockPos nearbyUsableLogTargetCachePos;
    private List<BlockPos> nearbyLogCandidates = List.of();
    private boolean gatheringDirt;
    private boolean searchingDirtForPillar;
    private boolean descendingFromPillar;
    private boolean clearingPillarCollision;
    private boolean foliageRelocationAttempted;
    private boolean logQueueScanDeferred;
    private boolean logQueueSearchPending;
    private boolean targetSelectionDeferred;
    private boolean workerSlotPaused;
    private BlockPos logQueueSearchOrigin;
    private int logQueueSearchColumnIndex;
    private int logQueueSearchRadius;
    private String pillarTraceDetail = "";
    private String pathFallbackDetailPrefix = "gathering logs";
    private int lastDetailMode = -1;
    private int nextDetailProgressRefreshTick;
    private int lastExpensiveWorkAdmissionTick = Integer.MIN_VALUE;
    private int standPathNotBeforeTick;
    private int standFallbackAttempts;
    private BlockPos lastDetailSubject;
    private String lastDetailStructuralText = "";
    private BlockPos pendingFailedStandPos;
    private boolean pendingPillarBaseRecovery;
    private boolean pillarBaseSearchExhaustedForTarget;
    private boolean pillarBaseSearchPending;
    private boolean pillarRouteAuthorized;
    private boolean clearingOutboundFarmFence;

    public GatherLogsGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.pathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.pillarUpAi = new PillarUpAi(
                playerNpc,
                this.toolAi,
                Items.DIRT,
                Blocks.DIRT.defaultBlockState(),
                true
        );
        this.waterEscapeAi = new WaterEscapeAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public boolean hasNearbyUsableLogTarget(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.targetPos != null
                && this.isValidLog(serverLevel, this.targetPos)
                && (canMineFromCurrentPosition(this.playerNpc, this.targetPos)
                || this.hasAuthorizedPillarPlanForTarget()
                || this.standPos != null && canMineFromStandPosition(this.standPos, this.targetPos))) {
            return true;
        }
        if (this.nearbyUsableLogTargetCachePos != null
                && this.playerNpc.tickCount < this.nearbyUsableLogTargetCacheUntilTick
                && this.nearbyUsableLogTargetCachePos.distSqr(feet) <= 4.0D * 4.0D) {
            return this.nearbyUsableLogTargetCacheResult;
        }
        // Do not launch a second full TreeAi scan from an arbitration proxy. The authoritative
        // canUse selector owns the incremental cursor. An unfinished scan is not a usable
        // target: treating it as one repeatedly cancels exploration without starting GatherLogs.
        // The higher-priority goal pre-empts exploration when it actually selects a log.
        return false;
    }

    public static boolean hasNearbyLogTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return hasNearbyLogTarget(playerNpc, serverLevel, pos -> false);
    }

    private static boolean hasNearbyLogTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel, Predicate<BlockPos> ignoredLogPos) {
        return hasNearbyLogTarget(playerNpc, serverLevel, ignoredLogPos, TREE_SEARCH_RADIUS, MAX_LOG_PATH_CHECKS);
    }

    private static boolean hasNearbyLogTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            Predicate<BlockPos> ignoredLogPos,
            int searchRadius,
            int maxPathChecks
    ) {
        return findNearbyLogTarget(playerNpc, serverLevel, ignoredLogPos, searchRadius, maxPathChecks).usable();
    }

    private static LogSearchResult findNearbyLogTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            Predicate<BlockPos> ignoredLogPos,
            int searchRadius,
            int maxPathChecks
    ) {
        Optional<Tree> tree = TreeAi.findNearestLocalFootprint(
                serverLevel,
                playerNpc.blockPosition(),
                pos -> !isProtectedHomeLogTarget(playerNpc, pos)
                        && !ignoredLogPos.test(pos)
        );
        if (tree.isEmpty()) {
            return new LogSearchResult(false, List.of());
        }

        List<BlockPos> logs = tree.get().logsNearestFirst(playerNpc.blockPosition());
        for (BlockPos candidate : logs) {
            if (!serverLevel.hasChunkAt(candidate)
                    || !serverLevel.getBlockState(candidate).is(BlockTags.LOGS)
                    || isProtectedHomeLogTarget(playerNpc, candidate)
                    || ignoredLogPos.test(candidate)) {
                continue;
            }
            boolean currentStandProtected = isProtectedHomeStandPos(playerNpc, playerNpc.blockPosition());
            if (!currentStandProtected && canMineFromCurrentPosition(playerNpc, candidate)) {
                return new LogSearchResult(true, logs);
            }
            if (!currentStandProtected && canPillarTowardFrom(playerNpc.blockPosition(), candidate)) {
                return new LogSearchResult(true, logs);
            }
            // Eligibility asks only whether a safe physical stand exists. PathFinder belongs to
            // the later running movement phase, never this nested activation predicate.
            if (findGeometricStandPos(playerNpc, serverLevel, candidate).isPresent()) {
                return new LogSearchResult(true, logs);
            }
        }
        return new LogSearchResult(false, logs);
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !PlayerNpcAiWorkBudget.hasWorkerSlot(this.playerNpc)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.playerNpc.getGatherCooldown() > 0) {
            return false;
        }
        if (this.workerSlotPaused
                && (this.pillarUpAi.isRunning()
                || this.descendingFromPillar
                || this.isStandingOnProtectedPillar())) {
            return true;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            return false;
        }
        if (this.shouldStayHomeForWeather(serverLevel)) {
            this.traceCanUseBlocked("gather logs blocked: weather/home shelter");
            return false;
        }
        if (ReturnHomeGoal.shouldSuppressExplorationForHome(this.playerNpc, serverLevel)) {
            this.traceCanUseBlocked("gather logs blocked: return home priority");
            return false;
        }
        if (GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel)) {
            this.traceCanUseBlocked("gather logs blocked: stone supply phase");
            return false;
        }
        if (!this.needsLogs(serverLevel)) {
            this.traceCanUseBlocked("gather logs blocked: logs not needed");
            return false;
        }
        if (!this.isSupplyLogJob()) {
            if (TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)) {
                this.traceCanUseBlocked("gather logs blocked: terraform work ready");
                return false;
            }
            if (BuildHouseGoal.shouldYieldSupplyWorkForBuild(this.playerNpc, serverLevel)) {
                this.traceCanUseBlocked("gather logs blocked: build work ready");
                return false;
            }
        }
        if (!this.tryAcquireExpensiveWork(serverLevel)) {
            return false;
        }
        // GatherLogs owns only immediately local actionable work. A bounded geometric probe that
        // misses must return false without acquiring MOVE so ExploreAround can relocate for logs
        // under the same retained NPC-level worker day shift.
        this.logQueue.clear();
        BlockPos searchFeet = this.playerNpc.blockPosition();
        if (this.logQueueSearchOrigin == null || this.logQueueSearchOrigin.distSqr(searchFeet) > 16.0D) {
            this.logQueueSearchOrigin = searchFeet.immutable();
            this.logQueueSearchColumnIndex = 0;
            this.logQueueSearchRadius = PlayerNpcAdaptiveSearchScope.coverageRadius(serverLevel);
        }
        TreeAi.SearchSlice localSlice = TreeAi.findNearestLocalFootprintSlice(
                serverLevel,
                this.logQueueSearchOrigin,
                this.logQueueSearchRadius,
                pos -> !this.isProtectedHomeLogTarget(pos) && !this.isIgnoredLogTarget(pos),
                this.logQueueSearchColumnIndex
        );
        this.logQueueSearchColumnIndex = localSlice.nextColumnIndex();
        this.logQueueSearchPending = localSlice.tree().isEmpty() && !localSlice.complete();
        localSlice.tree().ifPresent(tree -> this.logQueue.addAll(tree.logsNearestFirst(searchFeet)));
        if (this.logQueueSearchPending) {
            this.canUseThrottle.retryIn(this.playerNpc, 1);
            this.traceCanUseBlocked("gather logs search " + this.logQueueSearchColumnIndex
                    + "/" + localSlice.totalColumns() + "; continuing bounded pass");
            return false;
        }
        this.logQueueScanCompletedTick = this.playerNpc.tickCount;
        boolean selected = this.selectNextTarget(serverLevel, true);
        this.logQueueSearchOrigin = null;
        this.logQueueSearchColumnIndex = 0;
        this.logQueueSearchRadius = 0;
        this.logQueueSearchPending = false;
        if (!selected) {
            this.targetPos = null;
            this.standPos = null;
            this.cacheNearbyUsableLogTarget(false);
            this.resetLogQueueSearch();
            this.traceCanUseBlocked("gather logs blocked: no immediate local log; explore for logs");
        } else {
            this.cacheNearbyUsableLogTarget(true);
        }
        return selected;
    }

    @Override
    public boolean canContinueToUse() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            return false;
        }
        if ((this.targetPos == null && !this.descendingFromPillar && !this.targetSelectionDeferred)
                || !this.descendingFromPillar
                && this.gatherTicks >= MAX_GATHER_TICKS
                && !this.isStandingOnProtectedPillar()
                || this.descendingFromPillar && this.descentTicks >= MAX_DESCENT_TICKS
                || !this.playerNpc.isAlive()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0) {
            return false;
        }
        if (this.descendingFromPillar) {
            return true;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        // Building prep wins before this route starts in canUse(). Once an actionable log route
        // has been admitted, however, let it finish. Terraform's short-lived footprint cache can
        // expire while foliage is being cleared; treating the conservative cache miss as new prep
        // work here stopped GatherLogs, while Terraform itself correctly refused to start until
        // the active log episode ended. That produced a clear/cooldown/reselect loop on one leaf.
        // Safety guards remain immediate; weather, return-home, and live material-demand checks
        // retain their existing bounded continuation cadence below.

        // GoalSelector asks this every server tick. Farm/home/material predicates can inspect
        // persisted plans and many world blocks, so retain the last decision between one-second
        // checks while cheap death/combat/target guards above remain immediate.
        if (this.playerNpc.tickCount >= this.nextContinueEligibilityCheckTick) {
            this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                    + CONTINUE_ELIGIBILITY_INTERVAL_TICKS;
            this.continueEligibilityAllowed = !MiningNightCampGoal.shouldPauseMiningForNightCamp(
                    this.playerNpc,
                    serverLevel
            )
                    && !this.shouldStayHomeForWeather(serverLevel)
                    && !ReturnHomeGoal.shouldSuppressExplorationForHome(this.playerNpc, serverLevel)
                    && !BuildHouseGoal.shouldYieldSupplyWorkForBuild(this.playerNpc, serverLevel)
                    && this.needsLogs(serverLevel);
        }
        return this.continueEligibilityAllowed;
    }

    @Override
    public void start() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.playerNpc.getNavigation().stop();
            return;
        }
        this.workerSlotPaused = false;
        this.playerNpc.beginLogSupplyGatheringEpisode();
        setLogGatheringEpisodeActive(this.playerNpc, true);
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                + CONTINUE_ELIGIBILITY_INTERVAL_TICKS;
        this.continueEligibilityAllowed = true;
        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_logs");
        this.toolAi.equipTool(AxeItem.class);
        this.resetDetailRefresh();
        this.updateDetail();
        this.applyActiveNavigationBudget();
        // canUse may just have consumed this tick's bounded TreeAi slice. Route construction is
        // deferred to tick() so search and synchronous PathFinder work never stack in one tick.
    }

    @Override
    public void tick() {
        try {
            this.tickActive();
        } finally {
            // Pillar placement and foliage clearing invalidate nodes beside retained routes.
            // Keep vanilla's delayed recomputation bounded for the entire MOVE-owning episode,
            // including ticks after a helper temporarily restores PathNavigation's default.
            this.applyActiveNavigationBudget();
        }
    }

    private void tickActive() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.playerNpc.getNavigation().stop();
            this.breakingBlockAi.stop();
            this.clearBlockAi.stop();
            this.clearingOutboundFarmFence = false;
            return;
        }

        this.gatherTicks++;
        if (this.tickWaterEscape(serverLevel)) {
            return;
        }

        if (this.tickPathStuckFallback(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.tickHelperAi(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.descendingFromPillar) {
            this.tickDescendFromPillar(serverLevel);
            this.updateDetail();
            return;
        }

        if (this.gatherTicks >= MAX_GATHER_TICKS && this.isStandingOnProtectedPillar()) {
            this.breakingBlockAi.stop();
            this.clearBlockAi.stop();
            this.pathStuckFallbackAi.stop();
            this.pillarUpAi.clear();
            if (this.tryStartPillarDescent(serverLevel)) {
                this.updateDetail();
                return;
            }
            this.pillarTraceDetail = "pillar descent failed after gather timeout @ "
                    + posText(this.playerNpc.blockPosition());
            this.targetPos = null;
            this.updateDetail();
            return;
        }

        if (this.targetPos == null || !this.isValidTarget(serverLevel, this.targetPos)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            this.selectNextTargetOrDescendFromPillar(serverLevel);
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (this.pendingPillarBaseRecovery) {
            this.tickPendingPillarBaseRecovery(serverLevel);
            this.updateDetail();
            return;
        }

        boolean pillarRouteNeeded = this.shouldPillarTowardLog(serverLevel);
        // Dirt is a prerequisite for this route. Give its bounded search the NPC's expensive-work
        // slice before foliage probing; otherwise an unsuccessful obstruction probe can consume
        // the admission every tick and permanently starve dirt recovery.
        if (pillarRouteNeeded
                && ResourceAi.countDirt(this.playerNpc) < this.requiredDirtForCurrentPillarPlan()
                && this.tryPillarStep(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (!this.gatheringDirt && this.tryStartClearBlock(serverLevel)) {
            this.updateDetail();
            return;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.gatheringDirt
                && this.targetPos != null
                && this.isValidLog(serverLevel, this.targetPos)
                && isProtectedHomeStandPos(this.playerNpc, feet)
                && distanceFromStandToTargetSqr(feet, this.targetPos) > BREAK_DISTANCE_SQR
                && canPillarTowardFrom(feet, this.targetPos)
                && this.tryRecoverProtectedLogStand(serverLevel, feet)) {
            this.updateDetail();
            return;
        }

        if (pillarRouteNeeded && this.tryPillarStep(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.distanceToTargetSqr() > BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            if (this.watchStandPathFallback(serverLevel)) {
                this.updateDetail();
                return;
            }
            if (this.repathTicks-- <= 0) {
                if (!this.tryAcquireExpensiveWork(serverLevel)) {
                    this.repathTicks = 1 + this.playerNpc.getRandom().nextInt(4);
                    this.pillarTraceDetail = "log stand route queued for shared expensive-work slice";
                    this.updateDetail();
                    return;
                }
                if (!this.moveToStandPos()) {
                    if (!this.gatheringDirt && this.isValidLog(serverLevel, this.targetPos)) {
                        BlockPos failedStand = this.standPos == null
                                ? this.playerNpc.blockPosition().immutable()
                                : this.standPos.immutable();
                        this.rememberFailedStandRoute(failedStand);
                        this.pendingFailedStandPos = failedStand;
                        this.pendingPillarBaseRecovery = true;
                        this.pillarBaseSearchExhaustedForTarget = false;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.pillarTraceDetail = "normal log stand route failed; evaluating pillar last resort @ "
                                + posText(failedStand);
                        this.updateDetail();
                        return;
                    }
                    if (this.tryStartPathStuckFallback(
                            serverLevel,
                            this.standPos == null ? this.targetPos : this.standPos,
                            "gathering logs")) {
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    this.selectNextTargetOrDescendFromPillar(serverLevel);
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
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && (this.workerSlotPaused
                || this.pillarUpAi.isRunning()
                || this.descendingFromPillar
                || this.isStandingOnProtectedPillar())) {
            this.playerNpc.endLogSupplyGatheringEpisode();
            setLogGatheringEpisodeActive(this.playerNpc, false);
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.toolAi.restoreMainHand();
            this.clearBlockAi.stop();
            this.clearingOutboundFarmFence = false;
            this.breakingBlockAi.stop();
            this.pathStuckFallbackAi.stop();
            this.waterEscapeAi.stop();
            this.playerNpc.getNavigation().stop();
            this.playerNpc.getNavigation().resetMaxVisitedNodesMultiplier();
            this.workerSlotPaused = true;
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            this.playerNpc.setCurrentAiDetail("");
            // Keep the selected target, PillarUpAi placement/settlement state, descent target,
            // and exact entity-owned support ledger for the next worker holder.
            return;
        }
        this.playerNpc.endLogSupplyGatheringEpisode();
        setLogGatheringEpisodeActive(this.playerNpc, false);
        if (!this.gatheringDirt
                && this.gatherTicks >= MAX_GATHER_TICKS
                && this.targetPos != null
                && !this.isStandingOnProtectedPillar()
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.isValidLog(serverLevel, this.targetPos)
                && !this.hasAuthorizedPillarPlanForTarget()) {
            this.ignoreLogTarget(this.targetPos);
        }
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.toolAi.restoreMainHand();
        this.clearBlockAi.stop();
        this.clearingOutboundFarmFence = false;
        this.breakingBlockAi.stop();
        this.pathStuckFallbackAi.stop();
        this.pillarUpAi.clear();
        this.waterEscapeAi.stop();
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setGatherCooldown(20);
        }
        this.logQueue.clear();
        this.resetLogQueueSearch();
        this.targetPos = null;
        this.standPos = null;
        this.dirtTargetPos = null;
        this.resetDirtSearch();
        this.descentTargetPos = null;
        this.plannedStandPath = null;
        this.plannedDescentPath = null;
        this.lastClearTargetPos = null;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.descentTicks = 0;
        this.sameClearTargetTicks = 0;
        this.nearbyUsableLogTargetCacheUntilTick = 0;
        this.nearbyUsableLogTargetCachePos = null;
        this.nextContinueEligibilityCheckTick = 0;
        this.continueEligibilityAllowed = true;
        this.gatheringDirt = false;
        this.searchingDirtForPillar = false;
        this.descendingFromPillar = false;
        this.clearingPillarCollision = false;
        this.foliageRelocationAttempted = false;
        this.targetSelectionDeferred = false;
        this.resetTargetRouteRecovery();
        this.pillarTraceDetail = "";
        this.pathFallbackDetailPrefix = "gathering logs";
        // Never leave a route dirtied by pillar/foliage block updates for a later goal to
        // recompute at the default 48-block budget.
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getNavigation().resetMaxVisitedNodesMultiplier();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void applyActiveNavigationBudget() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.playerNpc.getNavigation().resetMaxVisitedNodesMultiplier();
            return;
        }
        float multiplier = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                ? OVERLOADED_LOG_PATH_NODE_MULTIPLIER
                : ACTIVE_LOG_PATH_NODE_MULTIPLIER;
        this.playerNpc.getNavigation().setMaxVisitedNodesMultiplier(multiplier);
    }

    private void resetLogQueueSearch() {
        this.logQueueScanDeferred = false;
        this.logQueueSearchPending = false;
        this.logQueueSearchOrigin = null;
        this.logQueueSearchColumnIndex = 0;
        this.logQueueSearchRadius = 0;
        this.nextLogQueueScanTick = 0;
        this.logQueueScanCompletedTick = Integer.MIN_VALUE;
    }

    private boolean tickWaterEscape(ServerLevel serverLevel) {
        BlockPos workDestination = this.standPos != null ? this.standPos : this.targetPos;
        WaterEscapeAi.TickResult result = this.waterEscapeAi.tick(
                serverLevel,
                Math.min(1.0D, Math.max(0.1D, this.speed)),
                workDestination
        );
        if (result == WaterEscapeAi.TickResult.FAILED) {
            BlockPos failedLog = this.targetPos;
            this.waterEscapeAi.stop();
            if (failedLog != null && this.isValidLog(serverLevel, failedLog)) {
                this.ignoreLogTarget(failedLog);
            }
            this.pillarTraceDetail = "water route failed; selecting another log";
            this.selectNextTargetOrDescendFromPillar(serverLevel);
            this.updateDetail();
            return true;
        }
        if (result != WaterEscapeAi.TickResult.RUNNING && result != WaterEscapeAi.TickResult.DONE) {
            return false;
        }

        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_logs");
        if (result == WaterEscapeAi.TickResult.RUNNING && !this.waterEscapeAi.detail().isBlank()) {
            this.playerNpc.setCurrentAiDetail(this.waterEscapeAi.detail());
        }
        return true;
    }

    private boolean tickPathStuckFallback(ServerLevel serverLevel) {
        if (this.tryStartBlockedOutboundFarmFenceClear(serverLevel)) {
            return true;
        }
        if (!this.pathStuckFallbackAi.tick(serverLevel, this.pathFallbackDetailPrefix)) {
            return false;
        }
        this.pillarTraceDetail = this.pathStuckFallbackAi.detail(this.pathFallbackDetailPrefix);
        return true;
    }

    private boolean tryStartBlockedOutboundFarmFenceClear(ServerLevel serverLevel) {
        if (!this.pathStuckFallbackAi.isRunning()
                || !"gathering logs".equals(this.pathFallbackDetailPrefix)
                || !this.playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING)
                || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                || this.targetPos == null
                || this.gatheringDirt
                || !this.isValidLog(serverLevel, this.targetPos)) {
            return false;
        }

        BlockPos fencePos = this.playerNpc.blockPosition();
        BlockState fenceState = serverLevel.getBlockState(fencePos);
        Optional<PlayerNpcFarmPlan.Plan> plan = FarmAi.getPlan(this.playerNpc, serverLevel);
        if (!(fenceState.getBlock() instanceof FenceBlock)
                || plan.isEmpty()
                || !plan.get().isFencePosition(fencePos)) {
            return false;
        }

        // Only an outside log route may temporarily open the owned perimeter. A stuck fallback is
        // retained proof that ordinary navigation made no progress; the active GatherLogs episode
        // keeps FarmSetup from immediately replacing the fence before the NPC steps through.
        BlockPos outboundDestination = this.standPos == null ? this.targetPos : this.standPos;
        if (!isOutsideFarmGround(plan.get(), this.targetPos)
                || !isOutsideFarmGround(plan.get(), outboundDestination)) {
            return false;
        }

        if (!this.clearBlockAi.start(
                serverLevel,
                fencePos,
                state -> state.getBlock() instanceof FenceBlock,
                "clearing farm fence for outbound log route",
                REQUIRED_BREAK_TICKS,
                BREAK_DISTANCE_SQR,
                false,
                true
        )) {
            return false;
        }
        this.pathStuckFallbackAi.stop();
        this.clearingOutboundFarmFence = true;
        this.pillarTraceDetail = "clearing owned farm fence for outbound log route @ "
                + posText(fencePos);
        return true;
    }

    private boolean isStillOwnedOutboundFarmFence(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || this.targetPos == null
                || !this.isValidLog(serverLevel, this.targetPos)
                || !this.playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING)
                || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            return false;
        }
        Optional<PlayerNpcFarmPlan.Plan> plan = FarmAi.getPlan(this.playerNpc, serverLevel);
        if (plan.isEmpty()
                || !plan.get().isFencePosition(pos)
                || !(serverLevel.getBlockState(pos).getBlock() instanceof FenceBlock)) {
            return false;
        }
        BlockPos outboundDestination = this.standPos == null ? this.targetPos : this.standPos;
        return isOutsideFarmGround(plan.get(), this.targetPos)
                && isOutsideFarmGround(plan.get(), outboundDestination);
    }

    private static boolean isOutsideFarmGround(PlayerNpcFarmPlan.Plan plan, BlockPos pos) {
        if (plan == null || pos == null) {
            return false;
        }
        return !plan.containsGround(new BlockPos(pos.getX(), plan.origin().getY(), pos.getZ()));
    }

    private boolean watchStandPathFallback(ServerLevel serverLevel) {
        if (this.standPos == null
                || this.gatheringDirt
                || this.standFallbackAttempts >= MAX_STAND_FALLBACK_ATTEMPTS_PER_TARGET) {
            return false;
        }
        boolean started = this.watchPathStuckFallback(
                serverLevel,
                this.standPos,
                this.standPos,
                "gathering logs"
        );
        if (started) {
            this.standFallbackAttempts++;
        }
        return started;
    }

    private boolean watchPathStuckFallback(
            ServerLevel serverLevel,
            BlockPos routeTarget,
            BlockPos directionTarget,
            String detailPrefix
    ) {
        if (routeTarget == null) {
            return false;
        }

        this.pathFallbackDetailPrefix = detailPrefix;
        if (!this.pathStuckFallbackAi.watchAndStart(
                serverLevel,
                routeTarget,
                directionTarget == null ? routeTarget : directionTarget,
                detailPrefix,
                pos -> isProtectedHomeStandPos(this.playerNpc, pos)
        )) {
            return false;
        }

        this.pillarTraceDetail = this.pathStuckFallbackAi.detail(detailPrefix);
        return true;
    }

    private boolean tryStartPathStuckFallback(ServerLevel serverLevel, BlockPos directionTarget, String detailPrefix) {
        if (directionTarget == null) {
            return false;
        }
        boolean standFallback = "gathering logs".equals(detailPrefix);
        if (standFallback && this.standFallbackAttempts >= MAX_STAND_FALLBACK_ATTEMPTS_PER_TARGET) {
            return false;
        }

        this.pathFallbackDetailPrefix = detailPrefix;
        if (!this.pathStuckFallbackAi.start(
                serverLevel,
                directionTarget,
                detailPrefix,
                pos -> isProtectedHomeStandPos(this.playerNpc, pos)
        )) {
            return false;
        }

        this.pillarTraceDetail = this.pathStuckFallbackAi.detail(detailPrefix);
        if (standFallback) {
            this.standFallbackAttempts++;
        }
        return true;
    }

    private boolean tickHelperAi(ServerLevel serverLevel) {
        if (this.clearBlockAi.isRunning()) {
            BlockPos clearTarget = this.clearBlockAi.targetPos();
            boolean clearingFoliage = this.clearBlockAi.detail().startsWith("clearing foliage");
            if (this.clearingOutboundFarmFence
                    && !this.isStillOwnedOutboundFarmFence(serverLevel, clearTarget)) {
                this.breakingBlockAi.stop();
                this.clearBlockAi.stop();
                this.clearingOutboundFarmFence = false;
                this.pillarTraceDetail = "outbound farm fence clear no longer valid @ "
                        + posText(clearTarget);
                return true;
            }
            if (this.clearingPillarCollision
                    && !this.isSafePillarCollisionClearTarget(serverLevel, clearTarget)) {
                this.breakingBlockAi.stop();
                this.clearBlockAi.stop();
                this.clearingPillarCollision = false;
                this.pillarTraceDetail = "pillar blocker became protected @ " + posText(clearTarget);
                return true;
            }
            if (clearingFoliage && this.didClearPreviousFoliageTarget(serverLevel, clearTarget)) {
                this.pathStuckFallbackAi.stop();
                this.foliageRelocationAttempted = false;
            }
            this.trackClearTarget(clearTarget);
            if (clearingFoliage
                    && !this.foliageRelocationAttempted
                    && this.watchPathStuckFallback(
                    serverLevel,
                    this.targetPos,
                    clearTarget,
                    "clearing foliage")) {
                this.foliageRelocationAttempted = true;
                this.clearBlockAi.retryFromCurrentPosition();
                return true;
            }
            if (!this.gatheringDirt
                    && !this.clearingPillarCollision
                    && !this.clearingOutboundFarmFence
                    && this.sameClearTargetTicks > MAX_SAME_LEAF_CLEAR_TICKS) {
                if (clearingFoliage
                        && !this.foliageRelocationAttempted
                        && this.tryStartPathStuckFallback(serverLevel, clearTarget, "clearing foliage")) {
                    this.foliageRelocationAttempted = true;
                    this.sameClearTargetTicks = 0;
                    this.clearBlockAi.retryFromCurrentPosition();
                    return true;
                }
                this.breakingBlockAi.stop();
                this.clearBlockAi.stop();
                this.ignoreClearBlock(clearTarget);
                if (this.forceClearLeaf(serverLevel, clearTarget)) {
                    this.pathStuckFallbackAi.stop();
                    this.pillarTraceDetail = "force cleared stuck leaf @ " + posText(clearTarget);
                } else {
                    this.pillarTraceDetail = "skipped stuck leaf @ " + posText(clearTarget);
                }
                if (clearingFoliage) {
                    this.foliageRelocationAttempted = false;
                }
                this.prepareLogQueue(serverLevel);
                this.lastClearTargetPos = null;
                this.sameClearTargetTicks = 0;
                return true;
            }
            ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
            if (result == ClearBlockAi.TickResult.DONE || result == ClearBlockAi.TickResult.FAILED) {
                this.breakingBlockAi.stop();
                this.clearingOutboundFarmFence = false;
                this.clearingPillarCollision = false;
                if (clearingFoliage && result == ClearBlockAi.TickResult.DONE) {
                    this.pathStuckFallbackAi.stop();
                }
                if (clearingFoliage) {
                    this.foliageRelocationAttempted = false;
                }
                if (result == ClearBlockAi.TickResult.FAILED && !this.gatheringDirt) {
                    this.ignoreClearBlock(clearTarget);
                }
                if (!this.gatheringDirt) {
                    this.prepareLogQueue(serverLevel);
                }
                this.pillarTraceDetail = "";
                this.lastClearTargetPos = null;
                this.sameClearTargetTicks = 0;
            }
            return result == ClearBlockAi.TickResult.RUNNING;
        }

        if (this.pillarUpAi.isRunning()) {
            PillarUpAi.TickResult result = this.pillarUpAi.tick(serverLevel);
            if (result == PillarUpAi.TickResult.PLACED) {
                BlockPos placed = this.pillarUpAi.consumeLastPlacedPos();
                this.protectPillarBlock(placed);
                this.pillarTraceDetail = "pillar placed @ "
                        + posText(placed)
                        + "; protected="
                        + this.protectedPillarBlocks.size()
                        + "; retrying log @ "
                        + posText(this.targetPos);
            } else if (result == PillarUpAi.TickResult.FAILED) {
                BlockPos blocker = this.pillarUpAi.consumeLastFailureBlockerPos();
                String failureDetail = this.pillarUpAi.consumeLastFailureDetail();
                if (this.tryStartPillarFailureClear(serverLevel, blocker, failureDetail)) {
                    return true;
                }
                this.pillarTraceDetail = "pillar failed after start"
                        + (failureDetail == null || failureDetail.isBlank() ? "" : ": " + failureDetail)
                        + " @ "
                        + posText(this.playerNpc.blockPosition());
                if (this.tryMoveToBetterPillarBase(serverLevel, this.playerNpc.blockPosition(), this.pillarTraceDetail)) {
                    return true;
                }
            }
            return result == PillarUpAi.TickResult.RUNNING || result == PillarUpAi.TickResult.PLACED;
        }
        return false;
    }

    private void prepareLogQueue(ServerLevel serverLevel) {
        this.prepareLogQueue(serverLevel, false);
    }

    private void prepareLogQueue(ServerLevel serverLevel, boolean admissionHeld) {
        if (this.playerNpc.tickCount < this.nextLogQueueScanTick) {
            return;
        }
        // Active gather routes can empty or invalidate their queue after breaking a log, clearing
        // foliage, or relocating a pillar. Those rescans do not pass through canUse(), so admit
        // them explicitly rather than letting a TreeAi batch overlap another NPC's search.
        if (!admissionHeld && !PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.logQueueScanDeferred = true;
            return;
        }
        this.logQueueScanDeferred = false;
        this.logQueueScanCompletedTick = this.playerNpc.tickCount;
        this.nextLogQueueScanTick = this.playerNpc.tickCount + CanUseThrottle.DEFAULT_INTERVAL_TICKS;
        this.pruneIgnoredClearBlocks(serverLevel);
        this.pruneIgnoredLogTargets(serverLevel);
        this.logQueue.clear();
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.nearbyUsableLogTargetCacheResult
                && this.nearbyUsableLogTargetCachePos != null
                && this.playerNpc.tickCount < this.nearbyUsableLogTargetCacheUntilTick
                && this.nearbyUsableLogTargetCachePos.distSqr(feet) <= 4.0D * 4.0D
                && !this.nearbyLogCandidates.isEmpty()) {
            this.logQueue.addAll(this.nearbyLogCandidates);
            this.logQueueSearchOrigin = null;
            this.logQueueSearchColumnIndex = 0;
            this.logQueueSearchPending = false;
            return;
        }
        if (this.logQueueSearchOrigin == null || this.logQueueSearchOrigin.distSqr(feet) > 16.0D) {
            this.logQueueSearchOrigin = feet.immutable();
            this.logQueueSearchColumnIndex = 0;
            this.logQueueSearchRadius = PlayerNpcAdaptiveSearchScope.coverageRadius(serverLevel);
        }
        TreeAi.SearchSlice treeSlice = TreeAi.findNearestLocalFootprintSlice(
                serverLevel,
                this.logQueueSearchOrigin,
                this.logQueueSearchRadius,
                pos -> !this.isProtectedHomeLogTarget(pos) && !this.isIgnoredLogTarget(pos),
                this.logQueueSearchColumnIndex
        );
        this.logQueueSearchColumnIndex = treeSlice.nextColumnIndex();
        this.logQueueSearchPending = treeSlice.tree().isEmpty() && !treeSlice.complete();
        if (this.logQueueSearchPending) {
            // A partial cursor pass is not the old completed full scan. Retry as soon as this NPC
            // next wins the fair expensive queue instead of sleeping another full second.
            this.nextLogQueueScanTick = this.playerNpc.tickCount + 1;
            this.pillarTraceDetail = "log search pass "
                    + treeSlice.nextColumnIndex() + "/" + treeSlice.totalColumns()
                    + "; queued for next shared expensive-work slice";
        }
        treeSlice.tree().ifPresent(value -> {
            this.logQueue.addAll(value.logsNearestFirst(feet));
            this.logQueueSearchOrigin = null;
            this.logQueueSearchColumnIndex = 0;
            this.logQueueSearchRadius = 0;
            this.logQueueSearchPending = false;
        });
        if (treeSlice.complete()) {
            this.logQueueSearchOrigin = null;
            this.logQueueSearchColumnIndex = 0;
            this.logQueueSearchRadius = 0;
        }
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (serverLevel.isNight() || serverLevel.isThundering());
    }

    private void traceCanUseBlocked(String detail) {
        this.playerNpc.setIdleTraceDetail(detail, 20 * 2);
    }

    private void cacheNearbyUsableLogTarget(boolean result) {
        this.nearbyUsableLogTargetCachePos = this.playerNpc.blockPosition().immutable();
        this.nearbyUsableLogTargetCacheResult = result;
        this.nearbyUsableLogTargetCacheUntilTick = this.playerNpc.tickCount + NEARBY_LOG_TARGET_CACHE_TICKS;
        if (!result) {
            this.nearbyLogCandidates = List.of();
        }
    }

    private boolean selectNextTarget(ServerLevel serverLevel, boolean admissionHeld) {
        this.gatheringDirt = false;
        this.searchingDirtForPillar = false;
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.pathStuckFallbackAi.stop();
        this.pillarUpAi.clear();
        this.clearingPillarCollision = false;
        this.foliageRelocationAttempted = false;
        this.logQueueScanDeferred = false;
        this.standPos = null;
        this.plannedStandPath = null;
        this.descentTargetPos = null;
        this.plannedDescentPath = null;
        this.descendingFromPillar = false;
        this.descentTicks = 0;
        this.pillarTraceDetail = "";
        boolean rescanned = false;
        while (true) {
            while (!this.logQueue.isEmpty()) {
                BlockPos candidate = this.logQueue.poll();
                if (this.isIgnoredLogTarget(candidate) || !this.isValidLog(serverLevel, candidate)) {
                    continue;
                }

                BlockPos feet = this.playerNpc.blockPosition();
                boolean currentStandProtected = isProtectedHomeStandPos(this.playerNpc, feet);
                boolean canMineHere = !currentStandProtected && canMineFromCurrentPosition(this.playerNpc, candidate);
                Optional<BlockPos> geometricStand = Optional.empty();
                if (!canMineHere) {
                    geometricStand = findGeometricStandPos(this.playerNpc, serverLevel, candidate);
                }
                if (geometricStand.isEmpty() && !canMineHere) {
                    continue;
                }

                this.targetPos = candidate.immutable();
                this.resetTargetRouteRecovery();
                if (geometricStand.isPresent()) {
                    this.standPos = geometricStand.get();
                    this.plannedStandPath = null;
                } else if (canMineHere) {
                    this.standPos = this.playerNpc.blockPosition().immutable();
                }
                this.pillarRouteAuthorized = this.standPos != null
                        && !canMineFromStandPosition(this.standPos, this.targetPos)
                        && canPillarTowardFrom(this.standPos, this.targetPos);
                this.toolAi.equipTool(AxeItem.class);
                return true;
            }

            if (rescanned) {
                return false;
            }
            rescanned = true;
            if (this.logQueueScanCompletedTick == this.playerNpc.tickCount) {
                return false;
            }
            this.prepareLogQueue(serverLevel, admissionHeld);
        }
    }

    private boolean tryStartClearBlock(ServerLevel serverLevel) {
        if (this.targetPos == null || !this.isValidLog(serverLevel, this.targetPos)) {
            return false;
        }

        List<BlockPos> candidates = ClearBlockAi.gatherObstructionCandidates(this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        candidates.removeIf(pos -> this.isIgnoredClearBlock(pos)
                || !serverLevel.hasChunkAt(pos)
                || !isLogClearBlock(serverLevel.getBlockState(pos)));
        if (candidates.isEmpty() || !this.tryAcquireExpensiveWork(serverLevel)) {
            return false;
        }
        return this.clearBlockAi.startNearest(serverLevel, candidates, GatherLogsGoal::isLogClearBlock, "clearing foliage", LEAF_CLEAR_TICKS);
    }

    private void trackClearTarget(BlockPos clearTarget) {
        if (clearTarget == null) {
            this.lastClearTargetPos = null;
            this.sameClearTargetTicks = 0;
            return;
        }
        if (!clearTarget.equals(this.lastClearTargetPos)) {
            this.lastClearTargetPos = clearTarget.immutable();
            this.sameClearTargetTicks = 0;
        }
        this.sameClearTargetTicks++;
    }

    private boolean didClearPreviousFoliageTarget(ServerLevel serverLevel, BlockPos clearTarget) {
        return this.lastClearTargetPos != null
                && !this.lastClearTargetPos.equals(clearTarget)
                && (!serverLevel.hasChunkAt(this.lastClearTargetPos)
                || !isLogClearBlock(serverLevel.getBlockState(this.lastClearTargetPos)));
    }

    private boolean forceClearLeaf(ServerLevel serverLevel, BlockPos clearTarget) {
        if (clearTarget == null
                || !serverLevel.hasChunkAt(clearTarget)
                || !isLogClearBlock(serverLevel.getBlockState(clearTarget))) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(clearTarget);
        boolean cleared = PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, clearTarget, state, this.playerNpc);
        if (cleared) {
            this.playerNpc.clearBlockBreakProgress(clearTarget);
        }
        return cleared || !isLogClearBlock(serverLevel.getBlockState(clearTarget));
    }

    private void ignoreClearBlock(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.ignoredClearBlocks.add(pos.immutable());
        while (this.ignoredClearBlocks.size() > MAX_IGNORED_CLEAR_BLOCKS) {
            Iterator<BlockPos> iterator = this.ignoredClearBlocks.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private boolean isIgnoredClearBlock(BlockPos pos) {
        return pos != null && this.ignoredClearBlocks.contains(pos.immutable());
    }

    private void pruneIgnoredClearBlocks(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        this.ignoredClearBlocks.removeIf(pos ->
                pos.distSqr(feet) > TREE_SEARCH_RADIUS * TREE_SEARCH_RADIUS
                        || !serverLevel.hasChunkAt(pos)
                        || !isLogClearBlock(serverLevel.getBlockState(pos)));
    }

    private void ignoreLogTarget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.ignoredLogTargets.add(pos.immutable());
        while (this.ignoredLogTargets.size() > MAX_IGNORED_LOG_TARGETS) {
            Iterator<BlockPos> iterator = this.ignoredLogTargets.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private boolean isIgnoredLogTarget(BlockPos pos) {
        return pos != null && this.ignoredLogTargets.contains(pos.immutable());
    }

    private void pruneIgnoredLogTargets(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        this.ignoredLogTargets.removeIf(pos ->
                pos.distSqr(feet) > TREE_SEARCH_RADIUS * TREE_SEARCH_RADIUS
                        || !this.isValidLog(serverLevel, pos));
    }

    private static boolean isLogClearBlock(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT);
    }

    private static boolean isPillarClearBlock(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.PINK_PETALS)
                || state.is(Blocks.SNOW);
    }

    private void tickPendingPillarBaseRecovery(ServerLevel serverLevel) {
        if (this.gatheringDirt || this.targetPos == null || !this.isValidLog(serverLevel, this.targetPos)) {
            this.pendingPillarBaseRecovery = false;
            this.pendingFailedStandPos = null;
            return;
        }

        BlockPos failedStand = this.pendingFailedStandPos == null
                ? this.playerNpc.blockPosition().immutable()
                : this.pendingFailedStandPos;
        if (this.authorizeCurrentPillarBaseAfterRouteFailure(serverLevel, failedStand)) {
            this.pendingPillarBaseRecovery = false;
            this.pendingFailedStandPos = null;
            return;
        }

        if (!this.pillarBaseSearchExhaustedForTarget
                && this.failedStandRoutes.size() < MAX_STAND_ROUTE_FAILURES_PER_TARGET) {
            if (this.playerNpc.tickCount < this.nextPillarBaseSearchTick) {
                this.pillarTraceDetail = "pillar base recovery waiting "
                        + (this.nextPillarBaseSearchTick - this.playerNpc.tickCount)
                        + "t before next bounded scan";
                return;
            }
            if (!this.tryAcquireExpensiveWork(serverLevel)) {
                this.pillarTraceDetail = "pillar base recovery queued for shared expensive-work slice";
                return;
            }
            if (this.tryMoveToBetterPillarBase(serverLevel, failedStand, "normal stand route failed", true)) {
                if (!this.pillarBaseSearchPending) {
                    this.pendingPillarBaseRecovery = false;
                    this.pendingFailedStandPos = null;
                }
                return;
            }
            // Do not stack PathStuckFallbackAi's radius scan behind the pillar-base heightmap scan
            // in this same server tick. The next tick performs the single capped fallback attempt.
            this.pillarBaseSearchExhaustedForTarget = true;
            this.pillarTraceDetail = "no reachable pillar base; path fallback deferred to next tick";
            return;
        } else {
            this.pendingPillarBaseRecovery = false;
            this.pendingFailedStandPos = null;
        }

        if (this.tryStartPathStuckFallback(serverLevel, failedStand, "gathering logs")) {
            this.repathTicks = REPATH_INTERVAL_TICKS;
            return;
        }

        BlockPos exhaustedTarget = this.targetPos.immutable();
        this.ignoreLogTarget(exhaustedTarget);
        this.pillarTraceDetail = "log route exhausted after normal stand and pillar-base retries @ "
                + posText(exhaustedTarget);
        this.targetPos = null;
        this.standPos = null;
        this.selectNextTargetOrDescendFromPillar(serverLevel);
    }

    private boolean authorizeCurrentPillarBaseAfterRouteFailure(ServerLevel serverLevel, BlockPos failedStand) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (isProtectedHomeStandPos(this.playerNpc, feet)
                || !canStandAt(serverLevel, feet)
                || !canPillarTowardFrom(feet, this.targetPos)
                || distanceFromStandToTargetSqr(feet, this.targetPos) <= BREAK_DISTANCE_SQR) {
            return false;
        }

        this.standPos = feet.immutable();
        this.plannedStandPath = null;
        this.pillarRouteAuthorized = true;
        this.pathStuckFallbackAi.stop();
        this.playerNpc.getNavigation().stop();
        this.pillarTraceDetail = "normal stand unreachable; pillar last resort authorized @ "
                + posText(feet)
                + " after failed stand "
                + posText(failedStand);
        return true;
    }

    private void rememberFailedStandRoute(BlockPos stand) {
        if (stand != null) {
            this.failedStandRoutes.add(stand.immutable());
        }
    }

    private boolean hasAuthorizedPillarPlanForTarget() {
        if (!this.pillarRouteAuthorized || this.targetPos == null) {
            return false;
        }
        BlockPos base = this.standPos == null ? this.playerNpc.blockPosition() : this.standPos;
        return canPillarTowardFrom(base, this.targetPos);
    }

    private void resetTargetRouteRecovery() {
        this.failedStandRoutes.clear();
        this.standFallbackAttempts = 0;
        this.pendingFailedStandPos = null;
        this.pendingPillarBaseRecovery = false;
        this.pillarBaseSearchExhaustedForTarget = false;
        this.pillarBaseSearchPending = false;
        this.pillarBaseSearchTarget = null;
        this.pillarBaseSearchColumnCursor = 0;
        this.pillarRouteAuthorized = false;
    }

    private boolean shouldPillarTowardLog(ServerLevel serverLevel) {
        if (this.gatheringDirt
                || !this.pillarRouteAuthorized
                || this.targetPos == null
                || !this.isValidLog(serverLevel, this.targetPos)) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.standPos != null
                && this.playerNpc.distanceToSqr(
                this.standPos.getX() + 0.5D,
                this.standPos.getY(),
                this.standPos.getZ() + 0.5D
        ) > STAND_REACHED_DISTANCE_SQR) {
            // Finish the selected normal walking route before considering a vertical shortcut.
            // Without this guard an NPC merely passing beneath a tall log began a dirt tower even
            // though a usable side stand had already been selected.
            return false;
        }
        return distanceFromStandToTargetSqr(feet, this.targetPos) > BREAK_DISTANCE_SQR
                && canPillarTowardFrom(feet, this.targetPos)
                && !isProtectedHomeStandPos(this.playerNpc, feet);
    }

    private boolean tryPillarStep(ServerLevel serverLevel) {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.pillarTraceDetail = "pillar paused: worker slot not held";
            return true;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.tryRecoverProtectedLogStand(serverLevel, feet)) {
            return true;
        }

        int dirtCount = ResourceAi.countDirt(this.playerNpc);
        int dirtNeeded = this.requiredDirtForCurrentPillarPlan();
        if (dirtCount < dirtNeeded) {
            this.searchingDirtForPillar = true;
            if (this.playerNpc.tickCount < this.nextDirtSearchTick) {
                this.pillarTraceDetail = "pillar needs dirt but no nearby dirt target; retry in "
                        + Math.max(0, this.nextDirtSearchTick - this.playerNpc.tickCount)
                        + "t";
                return true;
            }
            // This recovery runs inside an already-active goal, so it does not pass through
            // canUse(). Admit each bounded slice explicitly to avoid overlapping a tree, farm,
            // stone, or exploration path batch from another force-ticked NPC.
            if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                this.nextDirtSearchTick = this.playerNpc.tickCount
                        + 1
                        + this.playerNpc.getRandom().nextInt(4);
                this.pillarTraceDetail = "pillar needs dirt "
                        + dirtCount
                        + "/"
                        + dirtNeeded
                        + "; dirt search queued for shared expensive-work slice";
                return true;
            }
            this.pillarTraceDetail = "pillar needs dirt "
                    + dirtCount
                    + "/"
                    + dirtNeeded
                    + "; searching around target "
                    + posText(this.targetPos);
            DirtSearchResult dirtSearch = this.findNearestDirt(serverLevel);
            this.dirtTargetPos = dirtSearch.target();
            if (this.dirtTargetPos != null && dirtSearch.stand() != null) {
                this.targetPos = this.dirtTargetPos;
                this.standPos = dirtSearch.stand();
                this.plannedStandPath = dirtSearch.path();
                this.gatheringDirt = true;
                this.searchingDirtForPillar = false;
                this.pillarTraceDetail = "pillar collecting dirt "
                        + dirtCount
                        + "/"
                        + dirtNeeded
                        + " @ "
                        + posText(this.dirtTargetPos);
                this.breakingBlockAi.stop();
                this.toolAi.equipTool(ShovelItem.class);
                return true;
            }
            if (!dirtSearch.complete()) {
                this.nextDirtSearchTick = this.playerNpc.tickCount
                        + 1
                        + this.playerNpc.getRandom().nextInt(4);
                this.pillarTraceDetail = "pillar needs dirt; checking nearby area in bounded passes";
                return true;
            }
            this.nextDirtSearchTick = this.playerNpc.tickCount
                    + PILLAR_RECOVERY_SCAN_INTERVAL_TICKS
                    + this.playerNpc.getRandom().nextInt(11);
            this.pillarTraceDetail = "pillar needs dirt but no nearby dirt target";
            this.tryStartClearBlock(serverLevel);
            return true;
        }

        this.searchingDirtForPillar = false;
        this.resetDirtSearch();
        if (!this.playerNpc.onGround()) {
            this.pillarTraceDetail = "pillar waiting for ground @ " + posText(feet);
            this.lookDownAt(feet);
            return true;
        }

        String blocker = this.pillarUpAi.startBlocker(serverLevel, feet);
        if (!blocker.isBlank()) {
            this.pillarTraceDetail = "pillar blocked: "
                    + blocker
                    + " dirt="
                    + dirtCount
                    + " "
                    + targetMetricsText(feet, this.targetPos);
            if (this.tryStartPillarSpaceClear(serverLevel, feet) || this.tryStartClearBlock(serverLevel)) {
                return true;
            }
            if (this.tryMoveToBetterPillarBase(serverLevel, feet, blocker)) {
                return true;
            }
            return true;
        }
        if (!this.pillarUpAi.start(serverLevel, feet)) {
            this.pillarTraceDetail = "pillar start failed dirt="
                    + dirtCount
                    + " "
                    + targetMetricsText(feet, this.targetPos);
            return true;
        }
        this.pillarTraceDetail = "";
        return true;
    }

    private boolean tryRecoverProtectedLogStand(ServerLevel serverLevel, BlockPos feet) {
        if (!isProtectedHomeStandPos(this.playerNpc, feet)) {
            return false;
        }
        if (this.tryMoveToBetterPillarBase(serverLevel, feet, "protected home pillar base")) {
            return true;
        }
        if (this.targetPos != null) {
            BlockPos skippedTarget = this.targetPos.immutable();
            this.ignoreLogTarget(skippedTarget);
            this.breakingBlockAi.stop();
            this.clearBlockAi.stop();
            this.pillarUpAi.clear();
            this.targetPos = null;
            this.standPos = null;
            this.pillarTraceDetail = "skipping protected home log stand @ "
                    + posText(feet)
                    + " for log @ "
                    + posText(skippedTarget);
            this.selectNextTargetOrDescendFromPillar(serverLevel);
        }
        return true;
    }

    private int requiredDirtForCurrentPillarPlan() {
        if (this.targetPos == null) {
            return 1;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        for (int blocks = 0; blocks <= MAX_REQUIRED_DIRT_FOR_LOG_PILLAR; blocks++) {
            BlockPos simulatedFeet = feet.above(blocks);
            if (distanceFromStandToTargetSqr(simulatedFeet, this.targetPos) <= BREAK_DISTANCE_SQR
                    || !canPillarTowardFrom(simulatedFeet, this.targetPos)) {
                return Math.max(0, blocks);
            }
        }
        return MAX_REQUIRED_DIRT_FOR_LOG_PILLAR;
    }

    private boolean tryStartPillarSpaceClear(ServerLevel serverLevel, BlockPos feet) {
        BlockPos blockerPos = this.pillarUpAi.startBlockerPos(serverLevel, feet);
        if (blockerPos != null) {
            if (this.tryStartPillarFailureClear(serverLevel, blockerPos, "pillar start blocked")) {
                return true;
            }
        }

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
        if (this.targetPos != null) {
            candidates.addAll(ClearBlockAi.gatherObstructionCandidates(feet, this.standPos, this.targetPos));
        }
        return this.clearBlockAi.startNearest(serverLevel, candidates, GatherLogsGoal::isPillarClearBlock, "clearing pillar space", LEAF_CLEAR_TICKS);
    }

    private boolean tryStartPillarFailureClear(ServerLevel serverLevel, BlockPos blockerPos, String failureDetail) {
        if (blockerPos == null || !serverLevel.hasChunkAt(blockerPos)) {
            return false;
        }

        BlockState blockerState = serverLevel.getBlockState(blockerPos);
        if (blockerState.is(BlockTags.LOGS) && !this.isProtectedHomeLogTarget(blockerPos)) {
            this.targetPos = blockerPos.immutable();
            this.standPos = this.playerNpc.blockPosition().immutable();
            this.resetTargetRouteRecovery();
            this.breakingBlockAi.stop();
            this.clearBlockAi.stop();
            this.toolAi.equipTool(AxeItem.class);
            this.pillarTraceDetail = "pillar blocked by log; mining blocker @ "
                    + posText(blockerPos)
                    + detailSuffix(failureDetail);
            return true;
        }

        if (this.isSafePillarCollisionClearTarget(serverLevel, blockerPos)
                && this.clearBlockAi.start(
                serverLevel,
                blockerPos,
                GatherLogsGoal::isPillarCollisionState,
                "clearing pillar collision",
                REQUIRED_BREAK_TICKS,
                6.0D * 6.0D,
                true
        )) {
            this.clearingPillarCollision = true;
            this.pillarTraceDetail = "clearing pillar collision @ "
                    + posText(blockerPos)
                    + detailSuffix(failureDetail);
            return true;
        }

        if (this.clearBlockAi.start(
                serverLevel,
                blockerPos,
                GatherLogsGoal::isPillarClearBlock,
                "clearing pillar blocker",
                LEAF_CLEAR_TICKS
        )) {
            this.pillarTraceDetail = "clearing pillar blocker @ "
                    + posText(blockerPos)
                    + detailSuffix(failureDetail);
            return true;
        }
        return false;
    }

    private boolean isSafePillarCollisionClearTarget(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || !serverLevel.hasChunkAt(pos)
                || this.playerNpc.isTemporaryPillarSupport(pos)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                || this.isProtectedHomeLogTarget(pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)) {
            return false;
        }
        return ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, serverLevel.getBlockState(pos), true);
    }

    private static boolean isPillarCollisionState(BlockState state) {
        return state != null && !state.isAir();
    }

    private boolean tryMoveToBetterPillarBase(ServerLevel serverLevel, BlockPos blockedFeet, String blocker) {
        return this.tryMoveToBetterPillarBase(serverLevel, blockedFeet, blocker, false);
    }

    private boolean tryMoveToBetterPillarBase(
            ServerLevel serverLevel,
            BlockPos blockedFeet,
            String blocker,
            boolean admissionHeld
    ) {
        this.pillarBaseSearchPending = false;
        if (this.standPos != null
                && this.pillarTraceDetail.startsWith("pillar relocating from blocked base")) {
            if (this.playerNpc.tickCount < this.standPathNotBeforeTick) {
                return true;
            }
            this.rememberFailedStandRoute(this.standPos);
            this.standPos = null;
        }
        if (this.playerNpc.tickCount < this.nextPillarBaseSearchTick) {
            return false;
        }
        if (!admissionHeld && !this.tryAcquireExpensiveWork(serverLevel)) {
            this.nextPillarBaseSearchTick = this.playerNpc.tickCount
                    + 1
                    + this.playerNpc.getRandom().nextInt(4);
            this.pillarTraceDetail = "pillar base search queued for shared expensive-work slice";
            return false;
        }
        PillarBaseSearchResult search = this.findBetterPillarBase(serverLevel, blockedFeet);
        if (!search.complete()) {
            this.pillarBaseSearchPending = true;
            this.nextPillarBaseSearchTick = this.playerNpc.tickCount
                    + 1
                    + this.playerNpc.getRandom().nextInt(3);
            this.pillarTraceDetail = "pillar base scan "
                    + this.pillarBaseSearchColumnCursor + "/" + PILLAR_BASE_COLUMN_OFFSETS.size()
                    + "; continuing in bounded passes";
            return true;
        }
        this.nextPillarBaseSearchTick = this.playerNpc.tickCount
                + PILLAR_RECOVERY_SCAN_INTERVAL_TICKS
                + this.playerNpc.getRandom().nextInt(11);
        if (search.stand().isEmpty()) {
            return false;
        }

        this.standPos = search.stand().get().stand();
        this.plannedStandPath = search.stand().get().path();
        this.pillarRouteAuthorized = true;
        this.standPathNotBeforeTick = this.playerNpc.tickCount + 1;
        this.breakingBlockAi.stop();
        this.clearBlockAi.stop();
        this.pillarUpAi.clear();
        this.pillarTraceDetail = "pillar relocating from blocked base "
                + posText(blockedFeet)
                + " -> "
                + posText(this.standPos)
                + " because "
                + blocker
                + " "
                + targetMetricsText(this.standPos, this.targetPos);
        return true;
    }

    private PillarBaseSearchResult findBetterPillarBase(ServerLevel serverLevel, BlockPos blockedFeet) {
        if (this.targetPos == null) {
            return new PillarBaseSearchResult(Optional.empty(), true);
        }

        if (this.pillarBaseSearchTarget == null || !this.pillarBaseSearchTarget.equals(this.targetPos)) {
            this.pillarBaseSearchTarget = this.targetPos.immutable();
            this.pillarBaseSearchColumnCursor = 0;
        }

        int end = Math.min(
                PILLAR_BASE_COLUMN_OFFSETS.size(),
                this.pillarBaseSearchColumnCursor + MAX_PILLAR_BASE_COLUMNS_PER_SEARCH_PASS
        );
        BlockPos npcFeet = this.playerNpc.blockPosition();
        for (; this.pillarBaseSearchColumnCursor < end; this.pillarBaseSearchColumnCursor++) {
            ColumnOffset offset = PILLAR_BASE_COLUMN_OFFSETS.get(this.pillarBaseSearchColumnCursor);
            int x = this.targetPos.getX() + offset.dx();
            int z = this.targetPos.getZ() + offset.dz();
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }
            LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
            int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            candidates.add(new BlockPos(x, surfaceY, z));
            candidates.add(new BlockPos(x, surfaceY - 1, z));
            candidates.add(new BlockPos(x, surfaceY + 1, z));
            addVerticalStandCandidates(
                    this.playerNpc,
                    serverLevel,
                    candidates,
                    new BlockPos(x, npcFeet.getY(), z)
            );
            for (BlockPos candidate : candidates) {
                if (candidate.equals(blockedFeet)
                        || this.failedStandRoutes.contains(candidate)
                        || isProtectedHomeStandPos(this.playerNpc, candidate)
                        || distanceFromStandToTargetSqr(candidate, this.targetPos) <= BREAK_DISTANCE_SQR
                        || !canPillarTowardFrom(candidate, this.targetPos)
                        || !canStandAt(serverLevel, candidate)
                        || !this.pillarUpAi.startBlocker(serverLevel, candidate).isBlank()) {
                    continue;
                }
                this.pillarBaseSearchColumnCursor = 0;
                this.pillarBaseSearchTarget = null;
                // Selection remains geometric; path construction is deferred to a later tick.
                return new PillarBaseSearchResult(
                        Optional.of(new StandSearchResult(candidate.immutable(), null)),
                        true
                );
            }
        }
        boolean complete = this.pillarBaseSearchColumnCursor >= PILLAR_BASE_COLUMN_OFFSETS.size();
        if (complete) {
            this.pillarBaseSearchColumnCursor = 0;
            this.pillarBaseSearchTarget = null;
        }
        return new PillarBaseSearchResult(Optional.empty(), complete);
    }

    private static List<ColumnOffset> createPillarBaseColumnOffsets() {
        List<ColumnOffset> offsets = new ArrayList<>();
        for (int dx = -PILLAR_BASE_SEARCH_RADIUS; dx <= PILLAR_BASE_SEARCH_RADIUS; dx++) {
            for (int dz = -PILLAR_BASE_SEARCH_RADIUS; dz <= PILLAR_BASE_SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                offsets.add(new ColumnOffset(dx, dz));
            }
        }
        offsets.sort(Comparator
                .comparingInt((ColumnOffset offset) -> offset.dx() * offset.dx() + offset.dz() * offset.dz())
                .thenComparingInt(ColumnOffset::dx)
                .thenComparingInt(ColumnOffset::dz));
        return List.copyOf(offsets);
    }

    private void lookDownAt(BlockPos pos) {
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() - 0.5D,
                pos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
    }

    private void mineTarget(ServerLevel serverLevel) {
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                state -> this.isMineableTargetState(serverLevel, this.targetPos, state),
                REQUIRED_BREAK_TICKS,
                this.gatheringDirt ? "mining dirt for pillar" : "mining log"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        if (result == BreakingBlockAi.TickResult.DONE && this.gatheringDirt) {
            this.gatheringDirt = false;
            this.pillarTraceDetail = "pillar dirt collected; dirt=" + ResourceAi.countDirt(this.playerNpc);
            this.prepareLogQueue(serverLevel);
        } else if (result == BreakingBlockAi.TickResult.DONE
                && !this.needsLogs(serverLevel)
                && this.tryStartPillarDescent(serverLevel)) {
            return;
        } else if (result == BreakingBlockAi.TickResult.FAILED
                && !this.gatheringDirt
                && this.targetPos != null
                && this.isValidLog(serverLevel, this.targetPos)) {
            this.ignoreLogTarget(this.targetPos);
        }
        this.selectNextTargetOrDescendFromPillar(serverLevel);
    }

    private boolean selectNextTargetOrDescendFromPillar(ServerLevel serverLevel) {
        // Existing queued logs are cheap to validate. Only a queue refill consumes the shared
        // expensive-work admission, and an unresolved bounded refill retains this active route.
        if (this.selectNextTarget(serverLevel, false)) {
            this.targetSelectionDeferred = false;
            return true;
        }
        if (this.logQueueScanDeferred || this.logQueueSearchPending) {
            this.targetPos = null;
            this.targetSelectionDeferred = true;
            this.pillarTraceDetail = "log search continuing in bounded admitted passes";
            return true;
        }
        this.targetSelectionDeferred = false;
        if (this.tryStartPillarDescent(serverLevel)) {
            return true;
        }
        this.targetPos = null;
        return false;
    }

    private boolean needsLogs(ServerLevel serverLevel) {
        return hasLogSupplyDemand(this.playerNpc, serverLevel);
    }

    /** Pure demand predicate shared with farming arbitration to prevent predicate drift. */
    public static boolean hasLogSupplyDemand(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return false;
        }
        boolean supplyLogJob = (playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !playerNpc.hasInterest(PlayerNpcInterest.BUILDING))
                || playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING)
                || playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING)
                || playerNpc.isDailyJobActive(PlayerNpcInterest.EXPLORING);
        if (supplyLogJob) {
            return playerNpc.shouldPrioritizeLogGathering()
                    || FarmAi.needsFarmLogs(playerNpc, serverLevel)
                    || CraftBasicGearGoal.needsFishingRodCraftingLogs(playerNpc, serverLevel);
        }
        boolean needsCurrentBuildLogs = PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, playerNpc);
        boolean unresolvedActiveBuildNeed = isLogGatheringEpisodeActive(playerNpc)
                && PlayerNpcBuildMaterialUtil.isMissingBuildMaterialSearchPending(playerNpc);
        return playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                && (playerNpc.shouldPrioritizeLogGathering()
                || needsCurrentBuildLogs
                || unresolvedActiveBuildNeed);
    }

    /**
     * Crop work yields for the whole selected log route. A failed/exhausted route opens a short
     * cooldown window so ordinary farm work can resume instead of idling on unmet remote demand.
     */
    public static boolean shouldDeferFarmCropWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && serverLevel != null
                && (isLogGatheringEpisodeActive(playerNpc)
                || playerNpc.getGatherCooldown() <= 0 && hasLogSupplyDemand(playerNpc, serverLevel));
    }

    public static boolean isLogGatheringEpisodeActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null && ACTIVE_LOG_GATHERERS.contains(playerNpc);
    }

    private static void setLogGatheringEpisodeActive(PlayerNpcEntity playerNpc, boolean active) {
        if (playerNpc == null) {
            return;
        }
        if (active) {
            ACTIVE_LOG_GATHERERS.add(playerNpc);
        } else {
            ACTIVE_LOG_GATHERERS.remove(playerNpc);
        }
    }

    private boolean isMiningOnlyLogSupply() {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING);
    }

    private boolean isSupplyLogJob() {
        return this.isMiningOnlyLogSupply()
                || this.playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING)
                || this.playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING)
                || this.playerNpc.isDailyJobActive(PlayerNpcInterest.EXPLORING);
    }

    private boolean tryStartPillarDescent(ServerLevel serverLevel) {
        return this.tryStartPillarDescent(serverLevel, false);
    }

    private boolean tryStartPillarDescent(ServerLevel serverLevel, boolean admissionHeld) {
        if (!this.isStandingOnProtectedPillar()) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.pillarTraceDetail = "pillar descent paused: worker slot not held";
            return true;
        }
        if (!admissionHeld && !PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            // Returning true keeps the active gather episode alive without pretending that the
            // bounded descent search was exhausted. The next goal tick retries admission.
            this.pillarTraceDetail = "pillar descent queued for shared expensive-work slice";
            return true;
        }

        Optional<BlockPos> descentTarget = this.findDescentTarget(serverLevel);
        if (descentTarget.isEmpty()) {
            this.pillarTraceDetail = "pillar descent needed but no safe lower ground @ "
                    + posText(this.playerNpc.blockPosition());
            return false;
        }

        this.targetPos = null;
        this.standPos = null;
        this.dirtTargetPos = null;
        this.descentTargetPos = descentTarget.get();
        this.descendingFromPillar = true;
        this.descentTicks = 0;
        this.breakingBlockAi.stop();
        this.pillarUpAi.clear();
        this.pillarTraceDetail = "descending from pillar @ "
                + posText(this.playerNpc.blockPosition())
                + " -> "
                + posText(this.descentTargetPos);
        Path selectedDescentPath = this.plannedDescentPath;
        this.plannedDescentPath = null;
        if (selectedDescentPath != null
                && this.pathNavigationAi.isValidPathTo(this.descentTargetPos, selectedDescentPath)) {
            this.playerNpc.getNavigation().moveTo(selectedDescentPath, this.speed);
        } else {
            this.pathNavigationAi.moveTo(
                    serverLevel,
                    this.descentTargetPos,
                    this.speed,
                    MAX_PILLAR_SAFE_DROP_BLOCKS
            );
        }
        this.repathTicks = REPATH_INTERVAL_TICKS;
        return true;
    }

    private void tickDescendFromPillar(ServerLevel serverLevel) {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.clearBlockAi.stop();
            this.pillarTraceDetail = "pillar descent paused: worker slot not held";
            return;
        }
        this.descentTicks++;
        if (this.descentTargetPos == null
                || this.descentTicks > MAX_DESCENT_TICKS
                || !this.isStandingOnProtectedPillar()
                || this.playerNpc.distanceToSqr(
                this.descentTargetPos.getX() + 0.5D,
                this.descentTargetPos.getY(),
                this.descentTargetPos.getZ() + 0.5D
        ) <= STAND_REACHED_DISTANCE_SQR) {
            this.descendingFromPillar = false;
            this.descentTargetPos = null;
            this.pillarTraceDetail = "";
            return;
        }

        if (this.repathTicks-- <= 0) {
            if (!this.pathNavigationAi.moveTo(serverLevel, this.descentTargetPos, this.speed, MAX_PILLAR_SAFE_DROP_BLOCKS)) {
                if (!this.tryStartDescentClear(serverLevel)) {
                    this.descendingFromPillar = false;
                    this.descentTargetPos = null;
                    this.pillarTraceDetail = "";
                }
            }
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
    }

    private boolean tryStartDescentClear(ServerLevel serverLevel) {
        if (this.descentTargetPos == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : directionsToward(feet, this.descentTargetPos)) {
            BlockPos step = feet.relative(direction);
            candidates.add(step);
            candidates.add(step.above());
        }
        return this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                GatherLogsGoal::isPillarClearBlock,
                "clearing descent path",
                LEAF_CLEAR_TICKS
        );
    }

    private Optional<BlockPos> findDescentTarget(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        this.plannedDescentPath = null;
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -DESCENT_SEARCH_RADIUS; dx <= DESCENT_SEARCH_RADIUS; dx++) {
            for (int dz = -DESCENT_SEARCH_RADIUS; dz <= DESCENT_SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                if (!isColumnLoaded(serverLevel, x, z)) {
                    continue;
                }
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (candidate.getY() >= feet.getY()
                        || feet.getY() - candidate.getY() > MAX_PILLAR_SAFE_DROP_BLOCKS
                        || !canStandAt(serverLevel, candidate)) {
                    continue;
                }
                candidates.add(candidate.immutable());
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(feet))
                .thenComparingInt(BlockPos::getY));
        int pathChecks = 0;
        for (BlockPos candidate : candidates) {
            if (pathChecks++ >= MAX_DESCENT_PATH_CHECKS) {
                break;
            }
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    LOCAL_SELECTION_PATH_NODE_MULTIPLIER
            );
            if (this.pathNavigationAi.isValidPathTo(candidate, path)) {
                this.plannedDescentPath = path;
                return Optional.of(candidate.immutable());
            }
            if (this.pathNavigationAi.canSafelyDropTo(serverLevel, candidate, MAX_PILLAR_SAFE_DROP_BLOCKS)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private boolean isStandingOnProtectedPillar() {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.isProtectedPillarBlock(feet)
                || this.isProtectedPillarBlock(feet.below())
                || this.isProtectedPillarBlock(feet.below(2));
    }

    private static List<Direction> directionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            directions.add(direction);
        }
        directions.sort(Comparator.comparingDouble(direction ->
                horizontalDistanceToTargetColumnSqr(from.relative(direction), to)));
        return directions;
    }

    private DirtSearchResult findNearestDirt(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        DirtSearchResult immediate = this.findImmediatelyReachableDirt(serverLevel, center);
        if (immediate != null) {
            this.resetDirtSearch();
            return immediate;
        }
        if (this.dirtSearchOrigin == null
                || this.dirtSearchOrigin.distSqr(center) > DIRT_SEARCH_RESET_DISTANCE_SQR) {
            this.dirtSearchOrigin = center.immutable();
            this.dirtSearchColumnCursor = 0;
        }
        if (this.dirtSearchColumnCursor == 0) {
            this.pruneProtectedPillarBlocks(serverLevel, center);
        }

        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        int endCursor = Math.min(
                DIRT_SEARCH_COLUMN_OFFSETS.size(),
                this.dirtSearchColumnCursor + MAX_DIRT_COLUMNS_PER_SEARCH_PASS
        );
        for (int index = this.dirtSearchColumnCursor; index < endCursor; index++) {
            ColumnOffset offset = DIRT_SEARCH_COLUMN_OFFSETS.get(index);
            int x = this.dirtSearchOrigin.getX() + offset.dx();
            int z = this.dirtSearchOrigin.getZ() + offset.dz();
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }

            int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            this.addDirtCandidate(serverLevel, candidates, new BlockPos(x, surfaceY, z));
            for (int dy = -2; dy <= 2; dy++) {
                this.addDirtCandidate(
                        serverLevel,
                        candidates,
                        new BlockPos(x, this.dirtSearchOrigin.getY() + dy, z)
                );
            }
        }
        this.dirtSearchColumnCursor = endCursor;

        List<BlockPos> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        for (BlockPos pos : sorted) {
            Optional<BlockPos> stand = findGeometricStandPos(this.playerNpc, serverLevel, pos);
            if (stand.isPresent()) {
                DirtSearchResult result = new DirtSearchResult(
                        pos.immutable(),
                        stand.get(),
                        null,
                        true
                );
                this.resetDirtSearch();
                return result;
            }
        }

        boolean complete = this.dirtSearchColumnCursor >= DIRT_SEARCH_COLUMN_OFFSETS.size();
        if (complete) {
            this.resetDirtSearch();
        }
        return new DirtSearchResult(null, null, null, complete);
    }

    private DirtSearchResult findImmediatelyReachableDirt(ServerLevel serverLevel, BlockPos feet) {
        if (isProtectedHomeStandPos(this.playerNpc, feet)
                || !this.playerNpc.onGround()
                || !canStandAt(serverLevel, feet)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (this.isValidDirtTarget(serverLevel, candidate)
                            && canMineFromCurrentPosition(this.playerNpc, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
                .map(target -> new DirtSearchResult(
                        target,
                        feet.immutable(),
                        null,
                        true
                ))
                .orElse(null);
    }

    private void resetDirtSearch() {
        this.dirtSearchOrigin = null;
        this.dirtSearchColumnCursor = 0;
    }

    private static List<ColumnOffset> createDirtSearchColumnOffsets() {
        List<ColumnOffset> offsets = new ArrayList<>();
        for (int dx = -DIRT_SEARCH_RADIUS; dx <= DIRT_SEARCH_RADIUS; dx++) {
            for (int dz = -DIRT_SEARCH_RADIUS; dz <= DIRT_SEARCH_RADIUS; dz++) {
                offsets.add(new ColumnOffset(dx, dz));
            }
        }
        offsets.sort(Comparator
                .comparingInt((ColumnOffset offset) -> offset.dx() * offset.dx() + offset.dz() * offset.dz())
                .thenComparingInt(ColumnOffset::dx)
                .thenComparingInt(ColumnOffset::dz));
        return List.copyOf(offsets);
    }

    private void addDirtCandidate(ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos pos) {
        if (this.isValidDirtTarget(serverLevel, pos)) {
            candidates.add(pos.immutable());
        }
    }

    private boolean isValidDirtTarget(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || !serverLevel.hasChunkAt(pos)
                || this.isProtectedPillarBlock(pos)
                || this.isCurrentSupportBlock(pos)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK);
    }

    private boolean isProtectedPillarBlock(BlockPos pos) {
        return pos != null && this.protectedPillarBlocks.contains(pos.immutable());
    }

    private boolean isCurrentSupportBlock(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        return pos.equals(feet)
                || pos.equals(feet.below())
                || pos.equals(feet.below(2));
    }

    private void protectPillarBlock(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.protectedPillarBlocks.add(pos.immutable());
        while (this.protectedPillarBlocks.size() > MAX_PROTECTED_PILLAR_BLOCKS) {
            Iterator<BlockPos> iterator = this.protectedPillarBlocks.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private void pruneProtectedPillarBlocks(ServerLevel serverLevel, BlockPos center) {
        this.protectedPillarBlocks.removeIf(pos ->
                pos.distSqr(center) > TREE_SEARCH_RADIUS * TREE_SEARCH_RADIUS
                        || !serverLevel.hasChunkAt(pos)
                        || !serverLevel.getBlockState(pos).is(Blocks.DIRT));
    }

    private static boolean canMineFromCurrentPosition(PlayerNpcEntity playerNpc, BlockPos target) {
        return playerNpc.distanceToSqr(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D
        ) <= BREAK_DISTANCE_SQR;
    }

    private static boolean canMineFromStandPosition(BlockPos standPos, BlockPos target) {
        return distanceFromStandToTargetSqr(standPos, target) <= BREAK_DISTANCE_SQR;
    }

    private static boolean canPillarTowardFrom(BlockPos feet, BlockPos target) {
        int verticalGap = target.getY() - feet.getY();
        return verticalGap >= 3 && horizontalDistanceToTargetColumnSqr(feet, target) <= PILLAR_APPROACH_HORIZONTAL_DISTANCE_SQR;
    }

    private Optional<BlockPos> findStandPos(ServerLevel serverLevel, BlockPos target) {
        return findStandPos(this.playerNpc, serverLevel, target, new NavigationPathBudget(MAX_STAND_PATH_CHECKS));
    }

    private static Optional<BlockPos> findStandPos(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos target) {
        return findStandPos(playerNpc, serverLevel, target, new NavigationPathBudget(MAX_STAND_PATH_CHECKS));
    }

    private Optional<BlockPos> findStandPos(ServerLevel serverLevel, BlockPos target, NavigationPathBudget pathBudget) {
        return findStandPos(this.playerNpc, serverLevel, target, pathBudget);
    }

    private Optional<StandSearchResult> findStandTarget(
            ServerLevel serverLevel,
            BlockPos target,
            NavigationPathBudget pathBudget
    ) {
        return findStandTarget(this.playerNpc, serverLevel, target, pathBudget);
    }

    private Optional<StandSearchResult> findStandTarget(
            ServerLevel serverLevel,
            BlockPos target,
            NavigationPathBudget pathBudget,
            float pathNodeMultiplier
    ) {
        return findStandTarget(this.playerNpc, serverLevel, target, pathBudget, pathNodeMultiplier);
    }

    private static Optional<BlockPos> findStandPos(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target,
            NavigationPathBudget pathBudget
    ) {
        return findStandTarget(playerNpc, serverLevel, target, pathBudget).map(StandSearchResult::stand);
    }

    private static Optional<StandSearchResult> findStandTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target,
            NavigationPathBudget pathBudget
    ) {
        return findStandTarget(
                playerNpc,
                serverLevel,
                target,
                pathBudget,
                LOCAL_SELECTION_PATH_NODE_MULTIPLIER
        );
    }

    private static Optional<StandSearchResult> findStandTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target,
            NavigationPathBudget pathBudget,
            float pathNodeMultiplier
    ) {
        BlockPos feet = playerNpc.blockPosition();
        if (!isProtectedHomeStandPos(playerNpc, feet)
                && canStandAt(serverLevel, feet)
                && canMineFromStandPosition(feet, target)) {
            return Optional.of(new StandSearchResult(feet.immutable(), null));
        }

        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = target.relative(direction);
            addSurfaceStandCandidate(serverLevel, candidates, side);
            addVerticalStandCandidates(playerNpc, serverLevel, candidates, side);
        }
        addNearbySurfaceStandCandidates(serverLevel, candidates, target);
        if (!isProtectedHomeStandPos(playerNpc, feet)
                && canStandAt(serverLevel, feet)
                && canPillarTowardFrom(feet, target)) {
            candidates.add(feet.immutable());
        }

        List<BlockPos> viableCandidates = candidates.stream()
                .filter(pos -> !isProtectedHomeStandPos(playerNpc, pos))
                .filter(pos -> canStandAt(serverLevel, pos))
                .filter(pos -> canMineFromStandPosition(pos, target) || canPillarTowardFrom(pos, target))
                .sorted(Comparator
                        .comparingInt((BlockPos pos) -> canMineFromStandPosition(pos, target) ? 0 : 1)
                        .thenComparingDouble(pos -> horizontalDistanceToTargetColumnSqr(pos, target))
                        .thenComparingDouble(pos -> pos.distSqr(playerNpc.blockPosition())))
                .toList();
        for (BlockPos candidate : viableCandidates) {
            if (playerNpc.distanceToSqr(
                    candidate.getX() + 0.5D,
                    candidate.getY(),
                    candidate.getZ() + 0.5D
            ) <= STAND_REACHED_DISTANCE_SQR) {
                return Optional.of(new StandSearchResult(candidate.immutable(), null));
            }
            if (!pathBudget.tryConsume()) {
                break;
            }
            Path path = PathNavigationAi.createBoundedPath(
                    playerNpc,
                    candidate,
                    pathNodeMultiplier
            );
            if (isUsablePathToStand(path, candidate)) {
                return Optional.of(new StandSearchResult(candidate.immutable(), path));
            }
        }
        return Optional.empty();
    }

    /** Selects a physically valid stand without synchronously proving a navigation route. */
    private static Optional<BlockPos> findGeometricStandPos(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target
    ) {
        BlockPos feet = playerNpc.blockPosition();
        if (!isProtectedHomeStandPos(playerNpc, feet)
                && canStandAt(serverLevel, feet)
                && canMineFromStandPosition(feet, target)) {
            return Optional.of(feet.immutable());
        }
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = target.relative(direction);
            addSurfaceStandCandidate(serverLevel, candidates, side);
            addVerticalStandCandidates(playerNpc, serverLevel, candidates, side);
        }
        addNearbySurfaceStandCandidates(serverLevel, candidates, target);
        if (!isProtectedHomeStandPos(playerNpc, feet)
                && canStandAt(serverLevel, feet)
                && canPillarTowardFrom(feet, target)) {
            candidates.add(feet.immutable());
        }
        return candidates.stream()
                .filter(pos -> !isProtectedHomeStandPos(playerNpc, pos))
                .filter(pos -> canStandAt(serverLevel, pos))
                .filter(pos -> canMineFromStandPosition(pos, target) || canPillarTowardFrom(pos, target))
                .min(Comparator
                        .comparingInt((BlockPos pos) -> canMineFromStandPosition(pos, target) ? 0 : 1)
                        .thenComparingDouble(pos -> horizontalDistanceToTargetColumnSqr(pos, target))
                        .thenComparingDouble(pos -> pos.distSqr(feet)))
                .map(BlockPos::immutable);
    }

    private record LogSearchResult(boolean usable, List<BlockPos> logs) {
        private LogSearchResult {
            logs = List.copyOf(logs);
        }
    }

    private record StandSearchResult(BlockPos stand, Path path) {
    }

    private record PillarBaseSearchResult(Optional<StandSearchResult> stand, boolean complete) {
    }

    private record DirtSearchResult(BlockPos target, BlockPos stand, Path path, boolean complete) {
    }

    private record ColumnOffset(int dx, int dz) {
    }

    private static final class NavigationPathBudget {
        private int remaining;

        private NavigationPathBudget(int maximumPaths) {
            this.remaining = Math.max(0, maximumPaths);
        }

        private boolean tryConsume() {
            if (this.remaining <= 0) {
                return false;
            }
            this.remaining--;
            return true;
        }

        private boolean exhausted() {
            return this.remaining <= 0;
        }
    }

    private static void addSurfaceStandCandidate(ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos side) {
        if (!serverLevel.hasChunkAt(side)) {
            return;
        }
        int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, side.getX(), side.getZ());
        candidates.add(new BlockPos(side.getX(), surfaceY, side.getZ()));
        candidates.add(new BlockPos(side.getX(), surfaceY - 1, side.getZ()));
        candidates.add(new BlockPos(side.getX(), surfaceY + 1, side.getZ()));
    }

    private static void addNearbySurfaceStandCandidates(ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos target) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = target.getX() + dx;
                int z = target.getZ() + dz;
                if (!isColumnLoaded(serverLevel, x, z)) {
                    continue;
                }
                int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                candidates.add(new BlockPos(x, surfaceY, z));
            }
        }
    }

    private static void addVerticalStandCandidates(PlayerNpcEntity playerNpc, ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos side) {
        if (!serverLevel.hasChunkAt(side)) {
            return;
        }
        int playerY = playerNpc.blockPosition().getY();
        int minY = Math.max(
                serverLevel.getMinBuildHeight() + 1,
                Math.min(playerY, side.getY()) - STAND_SCAN_BELOW_TARGET
        );
        int maxY = Math.min(
                serverLevel.getMaxBuildHeight() - 2,
                Math.max(playerY, side.getY()) + STAND_SCAN_ABOVE_TARGET
        );
        for (int y = maxY; y >= minY; y--) {
            candidates.add(new BlockPos(side.getX(), y, side.getZ()));
        }
    }

    private static double horizontalDistanceToTargetColumnSqr(BlockPos standPos, BlockPos target) {
        int dx = standPos.getX() - target.getX();
        int dz = standPos.getZ() - target.getZ();
        return dx * dx + dz * dz;
    }

    private static double distanceFromStandToTargetSqr(BlockPos standPos, BlockPos target) {
        double dx = standPos.getX() - target.getX();
        double dy = standPos.getY() - (target.getY() + 0.5D);
        double dz = standPos.getZ() - target.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean isValidTarget(ServerLevel serverLevel, BlockPos pos) {
        return this.gatheringDirt
                ? this.isValidDirtTarget(serverLevel, pos)
                : this.isValidLog(serverLevel, pos);
    }

    private boolean isMineableTargetState(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.gatheringDirt) {
            return this.isValidDirtTarget(serverLevel, pos)
                    && (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK));
        }
        return this.isValidLog(serverLevel, pos) && state.is(BlockTags.LOGS);
    }

    private boolean isValidLog(ServerLevel serverLevel, BlockPos pos) {
        return pos != null
                && serverLevel.hasChunkAt(pos)
                && !this.isProtectedHomeLogTarget(pos)
                && serverLevel.getBlockState(pos).is(BlockTags.LOGS);
    }

    private boolean isProtectedHomeLogTarget(BlockPos pos) {
        return isProtectedHomeLogTarget(this.playerNpc, pos);
    }

    private static boolean isProtectedHomeStandPos(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || pos == null) {
            return false;
        }
        if (FarmAi.isProtectedFarmBlock(playerNpc, pos)
                || FarmAi.isProtectedFarmBlock(playerNpc, pos.below())) {
            return true;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        return PlayerNpcHomeUtil.isInside(home.get(), pos)
                || PlayerNpcHomeUtil.isInside(home.get(), pos.below())
                || PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos.below());
    }

    private static boolean isProtectedHomeLogTarget(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || pos == null) {
            return false;
        }
        if (FarmAi.isProtectedFarmBlock(playerNpc, pos)) {
            return true;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        return home.isPresent()
                && (PlayerNpcHomeUtil.isInside(home.get(), pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos));
    }

    private static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private static boolean isColumnLoaded(ServerLevel serverLevel, int blockX, int blockZ) {
        return serverLevel.hasChunk(blockX >> 4, blockZ >> 4);
    }

    private boolean moveToStandPos() {
        if (this.standPos == null) {
            return false;
        }

        double standDistanceSqr = this.playerNpc.distanceToSqr(
                this.standPos.getX() + 0.5D,
                this.standPos.getY(),
                this.standPos.getZ() + 0.5D
        );
        if (standDistanceSqr <= STAND_REACHED_DISTANCE_SQR) {
            this.plannedStandPath = null;
            if (this.targetPos == null || this.distanceToTargetSqr() <= BREAK_DISTANCE_SQR) {
                return true;
            }
            if (standDistanceSqr > STAND_CENTER_CORRECTION_DISTANCE_SQR) {
                this.playerNpc.getMoveControl().setWantedPosition(
                        this.standPos.getX() + 0.5D,
                        this.standPos.getY(),
                        this.standPos.getZ() + 0.5D,
                        this.speed
                );
                this.pillarTraceDetail = "closing on log stand @ "
                        + posText(this.standPos)
                        + " "
                        + targetMetricsText(this.playerNpc.blockPosition(), this.targetPos);
                return true;
            }
            return false;
        }

        Path path = this.plannedStandPath;
        this.plannedStandPath = null;
        if (!isUsablePathToStand(path, this.standPos)) {
            if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                    || !this.tryAcquireExpensiveWork(serverLevel)) {
                return false;
            }
            path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    this.standPos,
                    LOCAL_SELECTION_PATH_NODE_MULTIPLIER
            );
        }
        if (isUsablePathToStand(path, this.standPos)) {
            return this.playerNpc.getNavigation().moveTo(path, this.speed);
        }
        return false;
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

    private static boolean isUsablePathToStand(Path path, BlockPos standPos) {
        if (path == null || !path.canReach()) {
            return false;
        }
        Node endNode = path.getEndNode();
        return endNode != null && endNode.asBlockPos().equals(standPos);
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D);
    }

    private void updateDetail() {
        int mode;
        BlockPos subject = null;
        String structuralText = "";
        if (this.pathStuckFallbackAi.isRunning()) {
            mode = 1;
            structuralText = this.pathFallbackDetailPrefix;
        } else if (this.clearBlockAi.isRunning()) {
            mode = 2;
            subject = this.clearBlockAi.targetPos();
        } else if (this.breakingBlockAi.isRunning()) {
            mode = 3;
            subject = this.breakingBlockAi.targetPos();
        } else if (this.pillarUpAi.isRunning()) {
            mode = 4;
        } else if (this.descendingFromPillar) {
            mode = 5;
            structuralText = this.pillarTraceDetail;
        } else if (!this.pillarTraceDetail.isBlank()) {
            mode = 6;
            structuralText = this.pillarTraceDetail;
        } else if (this.targetPos == null) {
            mode = 7;
        } else if (this.searchingDirtForPillar) {
            mode = 8;
            subject = this.targetPos;
        } else if (this.gatheringDirt) {
            mode = 9;
            subject = this.targetPos;
        } else {
            mode = 10;
            subject = this.targetPos;
        }
        if (!this.shouldRefreshDetail(mode, subject, structuralText)) {
            return;
        }

        if (this.pathStuckFallbackAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(this.pathFallbackDetailPrefix));
            return;
        }
        if (this.clearBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.clearBlockAi.detail());
            return;
        }
        if (this.breakingBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.breakingBlockAi.detail());
            return;
        }
        if (this.pillarUpAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.pillarUpAi.detail());
            return;
        }
        if (this.descendingFromPillar) {
            this.playerNpc.setCurrentAiDetail(this.pillarTraceDetail.isBlank()
                    ? "descending from pillar"
                    : this.pillarTraceDetail);
            return;
        }
        if (!this.pillarTraceDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(this.pillarTraceDetail);
            return;
        }
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("searching for logs");
            return;
        }
        if (this.searchingDirtForPillar) {
            this.playerNpc.setCurrentAiDetail("searching dirt for pillar");
            return;
        }
        if (this.gatheringDirt) {
            this.playerNpc.setCurrentAiDetail("collecting dirt for pillar @ "
                    + this.targetPos.getX() + " "
                    + this.targetPos.getY() + " "
                    + this.targetPos.getZ());
            return;
        }
        this.playerNpc.setCurrentAiDetail("log @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ()
                + " logs="
                + ResourceAi.countLogs(this.playerNpc)
                + "/"
                + this.playerNpc.getLogSupplyGoal()
                + " dirt="
                + ResourceAi.countDirt(this.playerNpc)
                + " "
                + targetMetricsText(this.playerNpc.blockPosition(), this.targetPos));
    }

    private boolean shouldRefreshDetail(int mode, BlockPos subject, String structuralText) {
        boolean structureChanged = mode != this.lastDetailMode
                || !Objects.equals(subject, this.lastDetailSubject)
                || !Objects.equals(structuralText, this.lastDetailStructuralText);
        if (!structureChanged && this.playerNpc.tickCount < this.nextDetailProgressRefreshTick) {
            return false;
        }
        this.lastDetailMode = mode;
        this.lastDetailSubject = subject == null ? null : subject.immutable();
        this.lastDetailStructuralText = structuralText;
        this.nextDetailProgressRefreshTick = this.playerNpc.tickCount + DETAIL_PROGRESS_REFRESH_INTERVAL_TICKS;
        return true;
    }

    private void resetDetailRefresh() {
        this.lastDetailMode = -1;
        this.lastDetailSubject = null;
        this.lastDetailStructuralText = "";
        this.nextDetailProgressRefreshTick = 0;
    }

    private static String targetMetricsText(BlockPos feet, BlockPos target) {
        if (feet == null || target == null) {
            return "target=none";
        }
        int verticalGap = target.getY() - feet.getY();
        double horizontal = Math.sqrt(horizontalDistanceToTargetColumnSqr(feet, target));
        return String.format(Locale.ROOT, "feet=%s target=%s gap=%d horiz=%.1f", posText(feet), posText(target), verticalGap, horizontal);
    }

    private static String posText(BlockPos pos) {
        if (pos == null) {
            return "none";
        }
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String detailSuffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " (" + detail + ")";
    }
}
