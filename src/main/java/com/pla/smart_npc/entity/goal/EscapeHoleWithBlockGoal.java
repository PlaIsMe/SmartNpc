package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.compat.epicfight.EpicFight;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.CautiousThreatAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcCollisionUtil;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import javax.annotation.Nullable;

public class EscapeHoleWithBlockGoal extends Goal {
    private static final int PASSIVE_TRAP_PROBE_INTERVAL_TICKS = 10;
    private static final String AI_STATE = "ai.player_npc.pillaring_up";
    private static final int COOLDOWN_TICKS = 40;
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 24;
    private static final int PILLAR_SETTLE_TICKS = 12;
    private static final int MAX_PILLAR_SETTLE_WAIT_TICKS = 30;
    private static final int MIN_ROUTE_ESCAPE_BLOCKS = 16;
    private static final int MAX_ROUTE_ESCAPE_BLOCKS = 96;
    private static final int MAX_GOAL_TICKS = 20 * 120;
    private static final int SEARCH_RADIUS = 5;
    private static final List<BlockPos> ESCAPE_MATERIAL_OFFSETS = createEscapeMaterialOffsets();
    private static final List<BlockPos> PILLAR_PLAN_OFFSETS = createPillarPlanOffsets();
    private static final double BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 3;
    private static final int MAX_GATHER_PATH_FALLBACK_ATTEMPTS = 3;
    private static final int GATHER_PATH_RECOVERY_MAX_TICKS = 20 * 30;
    private static final int GATHER_PATH_FALLBACK_FAILED_COOLDOWN_TICKS = 20 * 30;
    private static final int PILLAR_SEARCH_RADIUS = 5;
    private static final int MAX_PILLAR_PLAN_CANDIDATES = 2;
    private static final int LOCAL_TRAP_SEARCH_RADIUS = 2;
    private static final int MAX_LOCAL_TRAP_STANDS = 24;
    private static final int LOCAL_TRAP_CACHE_TICKS = 5;
    private static final int OPEN_SHAFT_COMPONENT_RADIUS = 3;
    private static final int MAX_OPEN_SHAFT_COMPONENT_STANDS = 6;
    private static final int MAX_OPEN_SHAFT_BOUNDARY_COLUMNS = 24;
    private static final int OPEN_SHAFT_MIN_RIM_GAIN = 3;
    private static final int OPEN_SHAFT_MAX_RIM_GAIN = 48;
    private static final int OPEN_SHAFT_PROBE_CACHE_TICKS = 10;
    private static final int OPEN_SHAFT_REQUEST_TICKS = 20 * 120;
    private static final int[] OPEN_SHAFT_STEP_OFFSETS = {0, 1, -1};
    private static final int TRAPPED_CAVE_MIN_SURFACE_GAIN = 3;
    private static final int PILLAR_PLAN_RETRY_TICKS = 20;
    private static final int PILLAR_SURFACE_SCAN_UP = 96;
    private static final double PILLAR_BASE_REACHED_SQR = 1.2D * 1.2D;
    private static final int PILLAR_STUCK_MIN_TICKS = 20 * 5;
    private static final int PILLAR_STUCK_RECHECK_TICKS = 20 * 5;
    private static final int FORCED_PILLAR_BASE_NO_PROGRESS_TICKS = 20;
    private static final int MAX_FORCED_PILLAR_BASE_RECOVERIES = 3;
    private static final int PILLAR_CLEAR_RETURN_NO_PROGRESS_TICKS = 20;
    private static final int PILLAR_CLEAR_RETURN_FAILED_PATH_WEIGHT = 4;
    private static final double PILLAR_CENTER_EPSILON = 0.05D;
    private static final double PILLAR_COLLISION_BLOCKER_PADDING = 0.08D;
    private static final double PILLAR_COLLISION_TOP_PADDING = 0.45D;
    private static final double PILLAR_CENTER_BLOCKER_PADDING = 0.04D;
    private static final int ROUTE_NAV_REPATH_TICKS = 10;
    private static final int ROUTE_NAV_MAX_FAILED_TICKS = 20 * 4;
    private static final int ROUTE_NAV_HORIZONTAL_RADIUS = 18;
    private static final int ROUTE_NAV_STEP = 4;
    private static final int ROUTE_NAV_VERTICAL_DOWN = 3;
    private static final int ROUTE_NAV_VERTICAL_UP = 12;
    private static final int ROUTE_NAV_MIN_UPWARD_GAIN = 2;
    private static final int ROUTE_NAV_MAX_PATH_CHECKS = 2;
    private static final float ESCAPE_DIAGNOSTIC_PATH_NODE_MULTIPLIER = 0.01F;
    private static final double ROUTE_NAV_REACHED_SQR = 2.0D * 2.0D;
    private static final int EXPLORATION_CLIMB_CLEAR_TICKS = 24;
    private static final int EXPLORATION_CLIMB_CLEAR_REQUEST_TICKS = 20 * 20;
    private static final int FAILED_EXPLORATION_CLIMB_RETRY_TICKS = 20 * 30;
    // More than one complete eight-slice StartupWorkGatedGoal selector sweep. This gives the
    // higher-priority crafting goal a bounded chance to acquire and validate its concrete table
    // plan before an exploration-owned climb starts changing blocks.
    private static final int CRAFTING_PRIORITY_HANDOFF_TICKS = 40;
    private static final double EXPLORATION_CLIMB_CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final int EXPLORATION_CLIMB_STEP_OFF_STUCK_TICKS = PILLAR_STUCK_MIN_TICKS;
    private static final int EXPLORATION_CLIMB_STEP_OFF_TICKS = 40;
    private static final int EXPLORATION_CLIMB_STEP_OFF_RADIUS = 5;
    private static final int EXPLORATION_CLIMB_STEP_OFF_MAX_FALL = 16;
    private static final double EXPLORATION_CLIMB_STEP_OFF_SPEED = 0.28D;
    private static final int FARM_EGRESS_PENDING_TICKS = 20 * 45;
    private static final int FARM_EGRESS_MAX_ACTION_TICKS = 20 * 12;
    private static final int FARM_EGRESS_RETRY_TICKS = 40;
    private static final int FARM_EGRESS_FAILED_COOLDOWN_TICKS = 20 * 30;
    private static final int FARM_EGRESS_MAX_ATTEMPTS = 3;
    private static final int FARM_EGRESS_REPATH_TICKS = 10;
    private static final int FARM_EGRESS_MAX_ROUTE_FAILURE_TICKS = 20 * 4;
    private static final int FARM_EGRESS_CLEAR_TICKS = 18;
    private static final double FARM_EGRESS_CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final double FARM_EGRESS_WAYPOINT_REACHED_SQR = 0.85D * 0.85D;
    private static final double FARM_GATE_USE_DISTANCE_SQR = 3.75D * 3.75D;
    private static final int FARM_EGRESS_NO_PROGRESS_TICKS = 20;
    private static final double FARM_EGRESS_PROGRESS_EPSILON_SQR = 0.25D;
    private static final int FARM_EGRESS_CORRIDOR_MAX_STEPS = 16;
    private static final int FARM_EGRESS_MAX_CLEAR_CANDIDATES = 48;
    private static final int FARM_EGRESS_FAILED_CLEAR_COOLDOWN_TICKS = 40;
    private static final int FARM_EGRESS_MAX_FAILED_CLEAR_TARGETS = 16;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final CanUseThrottle passiveTrapProbeThrottle = new CanUseThrottle(PASSIVE_TRAP_PROBE_INTERVAL_TICKS);
    private final CanUseThrottle combatTrapProbeThrottle = new CanUseThrottle();
    private final PathStuckFallbackAi gatherPathStuckFallbackAi;
    private final PathStuckFallbackAi protectedPillarPathStuckFallbackAi;
    private EscapeMode mode = EscapeMode.NONE;
    private BlockPos placePos;
    private BlockPos minePos;
    private BlockPos mineStandPos;
    private BlockPos routeNavigationTarget;
    private BlockPos climbTargetPos;
    private BlockPos pillarBasePos;
    private BlockPos pillarClearPos;
    private BlockPos settlingPillarSupportPos;
    private BlockPos lastConfirmedPillarSupportPos;
    private BlockPos pillarStuckWatchPos;
    private BlockPos forcedPillarBaseWatchFeet;
    private BlockPos forcedPillarBaseWatchTarget;
    private BlockPos pillarClearReturnWatchPos;
    private BlockPos exitClearPos;
    private BlockPos explorationClimbStepOffWatchPos;
    private BlockPos explorationClimbStepOffWatchTarget;
    private BlockPos explorationClimbStepOffWatchBase;
    private BlockPos explorationClimbStepOffStartPos;
    private BlockPos explorationClimbStepOffTargetPos;
    private BlockPos failedExplorationClimbTarget;
    private int failedExplorationClimbUntilTick;
    private BlockPos farmEgressOriginalTarget;
    private BlockPos farmEgressInsideFeet;
    private BlockPos farmEgressOutsideFeet;
    private BlockPos farmEgressWaypoint;
    private BlockPos farmEgressNavigationWaypoint;
    private BlockPos farmEgressProgressTarget;
    private BlockPos farmEgressClearRequestedTarget;
    private List<BlockPos> farmEgressActiveClearCorridor = List.of();
    private final Map<BlockPos, Integer> farmEgressFailedClearUntil = new HashMap<>();
    private double farmEgressBestNavigationDistanceSqr = Double.MAX_VALUE;
    private int farmEgressLastProgressTick;
    private boolean farmEgressNavigationBlockedThisTick;
    private int farmEgressOriginalMaxPillarBlocks;
    private boolean farmEgressOriginalForcedRequest;
    private boolean farmEgressOriginalExplorationRequest;
    private int farmEgressPendingUntilTick;
    private int farmEgressNextAttemptTick;
    private int farmEgressFailedUntilTick;
    private BlockPos farmEgressBypassTarget;
    private int farmEgressBypassUntilTick;
    private int farmEgressAttempts;
    private int farmEgressRouteFailureTicks;
    private int pillarExitY;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private ItemStack previousPillarMainHand = ItemStack.EMPTY;
    private int placeDelayTicks;
    private int placeWaitTicks;
    private int pillarSettleTicks;
    private int pillarSettleWaitTicks;
    private int displacedPillarRecoveries;
    private int nextDisplacedPillarRecoveryTick;
    private int mineTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int gatherPathFallbackAttempts;
    private int gatherPathRecoveryDeadlineTick;
    private int gatherPathFinalAttemptDeadlineTick;
    private int goalTicks;
    private int requiredEscapeBlocks;
    private int maxPillarBlocks;
    private int pillarsPlaced;
    private int nextPillarPlanTick;
    private int pillarPlanCandidateCursor;
    private int failedPillarPlaceAttempts;
    private int pillarStuckWatchStartTick;
    private int nextPillarStuckRecoveryTick;
    private int pillarStuckWatchPillarsPlaced;
    private int forcedPillarBaseWatchStartTick;
    private int forcedPillarBaseRecoveryAttempts;
    private int pillarClearReturnNoProgressTicks;
    private int explorationClimbStepOffWatchStartTick;
    private int explorationClimbStepOffTicks;
    private BlockPos localTrapCacheFeet;
    private int localTrapCacheUntilTick;
    private boolean localTrapCacheResult;
    private BlockPos openShaftProbeCacheFeet;
    private int openShaftProbeCacheUntilTick;
    private BlockPos openShaftProbeCacheTarget;
    private int openShaftProbeComponentSize;
    private int openShaftProbeRimGain;
    private BlockPos directOpenShaftRequestTarget;
    private BlockPos ownedPocketExitTarget;
    private int directOpenShaftRequestUntilTick;
    private BlockPos trappedCaveRequestTarget;
    private BlockPos craftingPriorityHandoffTarget;
    private int craftingPriorityHandoffUntilTick;
    private boolean explorationClimbStepOffCompleted;
    private final Set<BlockPos> placedPillarSupports = new LinkedHashSet<>();
    private boolean usingTemporaryPickaxe;
    private boolean protectedPillarReplanPending;
    private boolean usingTemporaryBlock;
    private boolean explorationClimbEpisode;
    private boolean teamFollowerRecoveryEpisode;
    private boolean combatRecoveryEpisode;
    private boolean heightDirectedCombatEpisode;
    private long teamRecoveryPathAdmissionTick = Long.MIN_VALUE;
    private boolean workerSlotPaused;
    private boolean finished;

    private final DigDownForStoneGoal digDownForStoneGoal;

    public EscapeHoleWithBlockGoal(PlayerNpcEntity playerNpc, DigDownForStoneGoal digDownForStoneGoal) {
        this.playerNpc = playerNpc;
        this.digDownForStoneGoal = digDownForStoneGoal;
        this.toolAi = new ToolAi(playerNpc);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.gatherPathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.protectedPillarPathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void setEscapeMode(EscapeMode inputMode) {
        if ((this.mode == EscapeMode.GATHER_BLOCKS || this.mode == EscapeMode.CLEAR_EXIT || this.mode == EscapeMode.CLEAR_ROUTE)
                && (inputMode != EscapeMode.GATHER_BLOCKS && inputMode != EscapeMode.CLEAR_EXIT && inputMode != EscapeMode.CLEAR_ROUTE)) {
            if (ModList.get().isLoaded("epicfight")) {
                EpicFight.stopDiggingAnimation(this.playerNpc);
            }
        }
        this.mode = inputMode;
    }

    @Override
    public boolean canUse() {
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            return this.canUseUnmeasured();
        } finally {
            PlayerNpcPerformanceMonitor.recordGoalWork(
                    this.playerNpc,
                    this.getClass().getSimpleName() + ".canUse",
                    timing
            );
        }
    }

    private boolean canUseUnmeasured() {
        // Gathering deliberately opens/enters confined terrain. Check the actual running
        // delegate (including both work/interest wrappers), not its mutable inspector state,
        // before any trap probe can create an escape request or pre-empt the excavation.
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.isInWater(serverLevel)
                || this.playerNpc.isGatheringJobRunning()
                || this.digDownForStoneGoal.isMiningHandoffPending()
                || this.playerNpc.isStoneAccessClearing()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        LivingEntity combatTarget = this.playerNpc.getTarget();
        if (combatTarget != null && combatTarget.isAlive()) {
            return this.prepareCombatUpwardEscape(serverLevel, feet, combatTarget);
        }
        // Only borrow emergency access when normal work/team recovery cannot own it.
        // Cautious avoidance retains its threat proof; ordinary idle NPCs need a proven shaft.
        boolean cautiousRecovery = this.playerNpc.hasInterest(PlayerNpcInterest.CAUTIOUS)
                && !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && !this.playerNpc.isTeamFollowUpwardEscapeRequested();
        boolean passiveRecovery = !cautiousRecovery
                && !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && !this.playerNpc.isTeamFollowUpwardEscapeRequested();
        if (passiveRecovery) {
            // Gate egress already owns no-worker safety access and needs no carried blocks.
            // Preserve that handoff before the stricter passive pillar/material guards.
            this.combatRecoveryEpisode = false;
            this.teamFollowerRecoveryEpisode = false;
            FarmEgressDecision gateDecision = this.tryResumeOrStartFarmGateEgress(serverLevel, feet, requestedTarget);
            if (gateDecision != FarmEgressDecision.NONE) {
                return gateDecision == FarmEgressDecision.START;
            }
        }
        // This episode flag owns carried-only emergency execution, including no-target traps.
        this.combatRecoveryEpisode = cautiousRecovery || passiveRecovery;
        if (this.combatRecoveryEpisode) {
            // Combat is independent of the routine worker roster. Prove a local trap before
            // borrowing emergency execution access; a chase target above a cliff is not proof.
            if (this.playerNpc.tickCount < this.nextPillarPlanTick
                    || !this.combatTrapProbeThrottle.canCheck(this.playerNpc)
                    || !this.playerNpc.onGround()
                    || this.countEscapeBlocks() <= 0) {
                return false;
            }
            if (passiveRecovery && (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, feet)
                    || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, feet)
                    || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, feet.below()))) {
                return false;
            }
            if (!this.tryAcquireEmergencyRecoveryPathStart()) {
                return false;
            }
            BlockPos shaftTarget = this.findConfinedOpenShaftSurfaceTarget(serverLevel, feet);
            boolean trapped = this.hasOpenBodySpace(serverLevel, feet)
                    && this.isWalkableFloor(serverLevel, feet.below())
                    && this.isActuallyTrapped(serverLevel, feet);
            if (shaftTarget == null && (passiveRecovery || !trapped)) {
                return false;
            }
            if (cautiousRecovery) {
                LivingEntity attacker = this.playerNpc.getLastHurtByMob();
                boolean recentAttacker = this.playerNpc.tickCount - this.playerNpc.getLastHurtByMobTimestamp() <= 200
                        && CautiousThreatAi.isValidThreat(this.playerNpc, attacker)
                        && this.playerNpc.distanceToSqr(attacker) <= 28.0D * 28.0D;
                // Avoidance deliberately has no attack target. A proven trap may still borrow
                // emergency access while fleeing a recent attacker or a nearby cautious threat.
                // Threat acquisition happens only after trap proof, on the same staggered probe
                // and shared slice; ordinary open-ground avoidance never pays this extra scan.
                if (!recentAttacker && (!this.tryAcquireEmergencyRecoveryPathStart()
                        || CautiousThreatAi.findNearestThreat(this.playerNpc, 14.0D) == null)) {
                    return false;
                }
            }
            // Stale exploration/crafting/team requests must not turn combat recovery into a
            // journey toward the interrupted job. The local probes below own the escape route.
            requestedTarget = null;
        }
        if (this.ownedPocketExitTarget != null && !this.ownedPocketExitTarget.equals(requestedTarget)) {
            this.ownedPocketExitTarget = null;
        }
        this.teamFollowerRecoveryEpisode = !this.combatRecoveryEpisode
                && this.playerNpc.isTeamFollowUpwardEscapeRequested();
        if (this.teamFollowerRecoveryEpisode && this.playerNpc.tickCount < this.nextPillarPlanTick) {
            return false;
        }
        FarmEgressDecision farmEgressDecision = this.combatRecoveryEpisode
                ? FarmEgressDecision.NONE
                : this.tryResumeOrStartFarmGateEgress(serverLevel, feet, requestedTarget);
        if (farmEgressDecision == FarmEgressDecision.START) {
            return true;
        }
        if (farmEgressDecision == FarmEgressDecision.BLOCK) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && ((!this.teamFollowerRecoveryEpisode && !this.combatRecoveryEpisode)
                || !this.tryAcquireEmergencyRecoveryPathStart())) {
            // Farm-gate egress above is a non-pillar safety movement. Other modes require a
            // routine worker, except proven combat traps and provenance-scoped team recovery.
            // Both exceptions use shared path admission and never enable material gathering.
            return false;
        }
        if (this.combatRecoveryEpisode) {
            this.playerNpc.clearUpwardEscapeTarget();
        }
        if (requestedTarget == null && !this.combatRecoveryEpisode
                && !this.passiveTrapProbeThrottle.canCheck(this.playerNpc)) {
            // A goal-owned explicit climb request (including TEAMUP recovery) remains immediate.
            // Only the speculative confined-shaft/trap discovery below is staggered: polling its
            // collision/component probes every GoalSelector pass added measurable super.tick
            // work even for ordinary idle/exploring holders standing on open terrain.
            return false;
        }
        if (requestedTarget != null && !serverLevel.hasChunkAt(requestedTarget)) {
            // Escape requests are local recovery hints. Only their current worker holder may
            // invalidate one; a non-holder (especially immediately after join/load) is read-only.
            // Never synchronously load terrain through heightmap or path queries from canUse().
            this.playerNpc.clearUpwardEscapeTarget();
            this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);
            return false;
        }
        if (!this.combatRecoveryEpisode && this.shouldYieldExplorationClimbToCrafting(serverLevel, requestedTarget)) {
            return false;
        }
        this.workerSlotPaused = false;
        if (requestedTarget != null
                && this.playerNpc.isExplorationUpwardEscapeRequested()
                && requestedTarget.equals(this.failedExplorationClimbTarget)
                && this.playerNpc.tickCount < this.failedExplorationClimbUntilTick) {
            ExploreAroundGoal.requestSafeWalkAfterFailedClimb(this.playerNpc, requestedTarget);
            this.playerNpc.clearUpwardEscapeTarget();
            this.playerNpc.setHoleEscapeCooldown(Math.max(
                    this.playerNpc.getHoleEscapeCooldown(),
                    this.failedExplorationClimbUntilTick - this.playerNpc.tickCount
            ));
            this.playerNpc.setIdleTraceDetail("exploration climb route cooling down after failed clear @ "
                    + requestedTarget.getX() + " " + requestedTarget.getY() + " " + requestedTarget.getZ(), 40);
            return false;
        }
        if (this.playerNpc.tickCount >= this.failedExplorationClimbUntilTick) {
            this.failedExplorationClimbTarget = null;
            this.failedExplorationClimbUntilTick = 0;
        }
        if (requestedTarget != null && this.hasSatisfiedRequestedRoute(serverLevel, feet, requestedTarget)) {
            this.playerNpc.clearUpwardEscapeTarget();
            return false;
        }
        if (this.playerNpc.tickCount >= this.directOpenShaftRequestUntilTick) {
            this.directOpenShaftRequestTarget = null;
            this.directOpenShaftRequestUntilTick = 0;
        }
        if (this.trappedCaveRequestTarget != null
                && (requestedTarget == null || !requestedTarget.equals(this.trappedCaveRequestTarget))) {
            this.trappedCaveRequestTarget = null;
        }
        // CraftBasicGear only creates this exact two-block local request after three admitted
        // crafting-table route failures. Capture that provenance before the optional shaft probe
        // can replace the request with a rim target. It is a direct MOVE handoff, not a generic
        // surface-origin climb request, so an adjacent walkable cell inside the hole must not
        // cause the generic cliff guard to clear it.
        boolean boundedCraftingHandoff = this.isBoundedCraftingEscapeHandoff(feet, requestedTarget);
        // Terraform deliberately hands support filling to this goal when the builder is below the
        // build origin. Preserve that exact request through the generic exposed-side rejection;
        // the retained Terraform handoff resumes the fill after this bounded climb completes.
        boolean terraformSupportFillHandoff = requestedTarget != null
                && this.playerNpc.isTerraformSupportUpwardEscapeRequested();
        // Crafting-table travel may already have requested a short local climb. Still run the
        // cached confined-component proof for that explicit handoff: otherwise an adjacent cell
        // inside a 1x3 shaft is mistaken for a walkable surface side and the request is rejected.
        // Other existing forced requests retain the cheap surface/cliff guard without this scan.
        boolean probeGenericShaft = requestedTarget != null && !this.playerNpc.isForcedUpwardEscape()
                && this.passiveTrapProbeThrottle.canCheck(this.playerNpc);
        BlockPos openShaftTarget = requestedTarget == null || probeGenericShaft
                || this.playerNpc.isCraftingUpwardEscapeRequested()
                ? this.findConfinedOpenShaftSurfaceTarget(serverLevel, feet)
                : null;
        if (openShaftTarget != null) {
            int maxBlocks = Math.min(
                    MAX_ROUTE_ESCAPE_BLOCKS,
                    Math.max(1, openShaftTarget.getY() - feet.getY() + 2)
            );
            if (this.playerNpc.isTemporaryPillarSupport(feet.below())) {
                // An interrupted climb may leave a wide shallow pocket with no immediate body
                // walls. Keep ownership through the proven rim landing instead of another nudge.
                this.ownedPocketExitTarget = openShaftTarget.immutable();
                this.playerNpc.requestExplorationUpwardEscapeTo(openShaftTarget, OPEN_SHAFT_REQUEST_TICKS, maxBlocks);
            } else {
                this.ownedPocketExitTarget = null;
                this.playerNpc.requestForcedUpwardEscapeTo(openShaftTarget, OPEN_SHAFT_REQUEST_TICKS, maxBlocks);
            }
            this.retainTeamFollowerRecoveryProvenance();
            requestedTarget = this.playerNpc.getUpwardEscapeTarget();
            this.directOpenShaftRequestTarget = openShaftTarget.immutable();
            this.directOpenShaftRequestUntilTick = this.playerNpc.tickCount + OPEN_SHAFT_REQUEST_TICKS;
        }
        boolean directOpenShaftEscape = requestedTarget != null
                && requestedTarget.equals(this.directOpenShaftRequestTarget)
                && this.playerNpc.tickCount < this.directOpenShaftRequestUntilTick;
        boolean retainedExplorationStallEscape = requestedTarget != null
                && (ExploreAroundGoal.isRetainedStallEscapeRequest(this.playerNpc, requestedTarget)
                || this.teamFollowerRecoveryEpisode);
        boolean trapped = this.hasOpenBodySpace(serverLevel, feet)
                && this.isWalkableFloor(serverLevel, feet.below())
                && this.isActuallyTrapped(serverLevel, feet);
        if (requestedTarget == null && trapped) {
            BlockPos caveSurfaceTarget = this.findTrappedCaveSurfaceTarget(serverLevel, feet);
            if (caveSurfaceTarget != null) {
                int maxBlocks = Math.min(
                        MAX_ROUTE_ESCAPE_BLOCKS,
                        Math.max(1, caveSurfaceTarget.getY() - feet.getY() + 2)
                );
                // Treat the cave roof as a surface-directed escape episode rather than the
                // legacy one-block generic trap nudge. Exploration-style completion deliberately
                // keeps MOVE through the final step-off, so DescendHighColumn cannot dismantle the
                // unfinished column while the NPC is still beneath/on the cave opening.
                this.playerNpc.requestExplorationUpwardEscapeTo(
                        caveSurfaceTarget,
                        OPEN_SHAFT_REQUEST_TICKS,
                        maxBlocks
                );
                this.retainTeamFollowerRecoveryProvenance();
                requestedTarget = this.playerNpc.getUpwardEscapeTarget();
                this.trappedCaveRequestTarget = caveSurfaceTarget.immutable();
                this.playerNpc.setIdleTraceDetail("pillar request admitted: trapped cave surface gain="
                        + (caveSurfaceTarget.getY() - feet.getY())
                        + " -> " + posText(caveSurfaceTarget), 60);
            }
        }
        boolean trappedCaveEscape = requestedTarget != null
                && requestedTarget.equals(this.trappedCaveRequestTarget);
        String surfaceRequestRejection = requestedTarget != null
                && !trapped
                && !directOpenShaftEscape
                && !trappedCaveEscape
                && !retainedExplorationStallEscape
                && !boundedCraftingHandoff
                && !terraformSupportFillHandoff
                ? this.undergroundSurfaceRecoveryRejection(serverLevel, feet, requestedTarget)
                : null;
        if (surfaceRequestRejection != null) {
            // A high destination beside a cliff/mountain is a navigation/reselection problem, not
            // proof that the NPC is underground. Release the request so its owner can repath or
            // choose a different route without leaving a vertical column in open terrain.
            this.playerNpc.clearUpwardEscapeTarget();
            this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);
            this.playerNpc.setIdleTraceDetail("pillar request rejected: " + surfaceRequestRejection
                    + " @ " + posText(feet), 40);
            return false;
        }
        if (retainedExplorationStallEscape) {
            // This request carries three same-position, no-path failures plus a completed bounded
            // surface scan. It is the below-surface/open-shaft equivalent of legacy trapped
            // admission and deliberately bypasses surface-origin sky/overhang rejection.
            this.playerNpc.setIdleTraceDetail("pillar request admitted: retained exploration stall -> "
                    + posText(requestedTarget), 40);
        }
        if (directOpenShaftEscape) {
            this.playerNpc.setIdleTraceDetail("pillar request admitted: confined open shaft"
                    + " component=" + this.openShaftProbeComponentSize
                    + " rimGain=" + this.openShaftProbeRimGain
                    + " -> " + posText(requestedTarget), 60);
        } else if (boundedCraftingHandoff) {
            this.playerNpc.setIdleTraceDetail("pillar request admitted: crafting-table route handoff"
                    + " max=" + this.playerNpc.getUpwardEscapeMaxPillarBlocks()
                    + " -> " + posText(requestedTarget), 60);
        } else if (terraformSupportFillHandoff) {
            this.playerNpc.setIdleTraceDetail("pillar request admitted: terraform support-fill handoff"
                    + " max=" + this.playerNpc.getUpwardEscapeMaxPillarBlocks()
                    + " -> " + posText(requestedTarget), 60);
        }
        boolean hasRequestedEscape = requestedTarget != null;
        boolean forceRequestedClimb = this.playerNpc.isForcedUpwardEscape()
                && hasRequestedEscape
                && feet.getY() < this.minimumForcedRouteFeetY(requestedTarget);
        boolean explorationRequestedClimb = this.playerNpc.isExplorationUpwardEscapeRequested()
                && hasRequestedEscape
                && (requestedTarget.getY() > feet.getY()
                || requestedTarget.equals(this.ownedPocketExitTarget)
                && this.playerNpc.isTemporaryPillarSupport(feet.below()));
        boolean requestedClimb = forceRequestedClimb || explorationRequestedClimb;
        BlockPos routeTarget = this.getUpwardRouteTarget(serverLevel, feet);
        boolean hasUpwardRouteTarget = routeTarget != null && this.isUsableUpwardRouteTarget(serverLevel, feet, routeTarget);
        if (requestedClimb && !hasUpwardRouteTarget) {
            routeTarget = requestedTarget.immutable();
            hasUpwardRouteTarget = true;
        }
        if (!trapped && !hasUpwardRouteTarget && this.hasReachedOpenSky(serverLevel, feet) && !requestedClimb) {
            if (requestedTarget != null) {
                this.playerNpc.clearUpwardEscapeTarget();
            }
            return false;
        }
        if (this.playerNpc.getHoleEscapeCooldown() > 0 && requestedTarget == null) {
            return false;
        }

        if (!trapped && !hasUpwardRouteTarget) {
            return false;
        }

        // A retained requested climb is not a reason to bypass the failed-plan cooldown. The
        // request remains stored and is retried at the deadline; bypassing here rebuilt the full
        // pillar plan on every GoalSelector activation pass in freshly generated terrain.
        if (this.playerNpc.tickCount < this.nextPillarPlanTick) {
            return false;
        }

        boolean routeNeedsClimb = requestedClimb
                || hasUpwardRouteTarget && this.routeNeedsClimbWithoutPathProbe(serverLevel, feet, routeTarget);

        if (!trapped && !routeNeedsClimb) {
            return false;
        }

        this.resetPlan();
        this.explorationClimbEpisode = explorationRequestedClimb;
        this.climbTargetPos = routeNeedsClimb ? routeTarget : null;
        this.requiredEscapeBlocks = 1;

        BlockPos currentColumnTarget = this.findCurrentColumnClearTarget(serverLevel, feet);
        if (currentColumnTarget != null) {
            setEscapeMode(EscapeMode.CLEAR_EXIT);
            this.exitClearPos = currentColumnTarget;
            return true;
        }

        if (routeNeedsClimb
                && !trapped
                && this.hasReachedOpenSky(serverLevel, feet)
                && !requestedClimb
                && !this.isRequestedSurfaceRoute(serverLevel, feet, routeTarget)) {
            return false;
        }

        PillarPlan pillarPlan = this.findPillarPlan(serverLevel, feet, routeNeedsClimb ? routeTarget : null);
        if (pillarPlan == null) {
            if (routeNeedsClimb && explorationRequestedClimb) {
                if (this.tryStartExplorationClimbClear(serverLevel, feet, routeTarget, null)) {
                    return true;
                }
                this.abandonExplorationClimb(routeTarget, "no viable pillar or clear route");
            }
            this.nextPillarPlanTick = this.playerNpc.tickCount + PILLAR_PLAN_RETRY_TICKS;
            return false;
        }
        if (routeNeedsClimb && this.exceedsRequestedRouteMax(pillarPlan)) {
            if (explorationRequestedClimb && this.tryStartExplorationClimbClear(serverLevel, feet, routeTarget, pillarPlan)) {
                return true;
            }
            if (explorationRequestedClimb) {
                this.abandonExplorationClimb(routeTarget, "route exceeds pillar limit");
            } else {
                this.playerNpc.clearUpwardEscapeTarget();
            }
            this.nextPillarPlanTick = this.playerNpc.tickCount + PILLAR_PLAN_RETRY_TICKS;
            return false;
        }
        this.nextPillarPlanTick = 0;
        this.pillarBasePos = pillarPlan.basePos();
        this.pillarExitY = pillarPlan.exitY();
        if (routeNeedsClimb) {
            this.requiredEscapeBlocks = Math.max(1, pillarPlan.blocksNeeded());
        }

        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks < this.requiredEscapeBlocks && !this.isEmergencyRecoveryEpisode()) {
            EscapeMaterialTarget target = this.findEscapeMaterialTarget(serverLevel);
            if (target != null) {
                setEscapeMode(EscapeMode.GATHER_BLOCKS);
                this.minePos = target.targetPos();
                this.mineStandPos = target.standPos();
                return true;
            }
        }

        if (escapeBlocks <= 0) {
            // Keep a retained climb request, but do not rebuild the same pillar plan and rescan
            // its material neighborhood on the next GoalSelector activation. A newly acquired
            // block is still noticed after this short deadline, while first-world terrain does
            // not repeatedly pay the synchronous plan cost for a currently impossible climb.
            this.nextPillarPlanTick = this.playerNpc.tickCount + PILLAR_PLAN_RETRY_TICKS;
            return false;
        }

        setEscapeMode(EscapeMode.PILLAR);
        this.maxPillarBlocks = routeNeedsClimb
                ? Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, pillarPlan.blocksNeeded())))
                : 1;
        return true;
    }

    private boolean isBoundedCraftingEscapeHandoff(BlockPos feet, @Nullable BlockPos requestedTarget) {
        return requestedTarget != null
                && this.playerNpc.isCraftingUpwardEscapeRequested()
                && requestedTarget.getX() == feet.getX()
                && requestedTarget.getZ() == feet.getZ()
                && requestedTarget.getY() == feet.getY() + 2
                && this.playerNpc.getUpwardEscapeMaxPillarBlocks() == 2;
    }

    private boolean prepareCombatUpwardEscape(ServerLevel serverLevel, BlockPos feet, LivingEntity target) {
        this.combatRecoveryEpisode = true;
        this.teamFollowerRecoveryEpisode = false;
        if (target.getY() - this.playerNpc.getY() <= 2.0D
                || this.playerNpc.distanceToSqr(target) > 28.0D * 28.0D
                || !this.playerNpc.onGround()
                || this.playerNpc.tickCount < this.nextPillarPlanTick
                || !this.combatTrapProbeThrottle.canCheck(this.playerNpc)
                || this.countEscapeBlocks() <= 0
                || !serverLevel.hasChunkAt(target.blockPosition())
                || !this.canStandAt(serverLevel, feet)
                || !this.isActuallyTrapped(serverLevel, feet)) {
            return false;
        }
        Path currentPath = this.playerNpc.getNavigation().getPath();
        if (currentPath != null && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck() && currentPath.canReach()
                && currentPath.getTarget().distSqr(target.blockPosition()) <= 1.0D) {
            return false;
        }
        if (!PathNavigationAi.hasLoadedChunkCorridor(serverLevel, feet, target.blockPosition(), 1)
                || !this.tryAcquireEmergencyRecoveryPathStart()) {
            return false;
        }
        Path path = this.createBoundedDiagnosticPath(target.blockPosition());
        // The loaded corridor and admission were checked explicitly. A confined stand may
        // produce no path at all; that is a failed local route, not unknown unloaded terrain.
        if (path != null && path.canReach()) {
            return false;
        }
        BlockPos climbTarget = target.blockPosition().immutable();
        int limit = Math.min(MAX_ROUTE_ESCAPE_BLOCKS,
                Math.max(1, climbTarget.getY() - feet.getY() + 1));
        this.resetPlan();
        this.workerSlotPaused = false;
        this.climbTargetPos = climbTarget;
        this.heightDirectedCombatEpisode = true;
        this.playerNpc.requestForcedUpwardEscapeTo(climbTarget, OPEN_SHAFT_REQUEST_TICKS, limit);
        PillarPlan plan = this.createPillarPlan(serverLevel, feet, climbTarget);
        if (plan == null || this.exceedsRequestedRouteMax(plan)) {
            this.heightDirectedCombatEpisode = false;
            this.playerNpc.clearUpwardEscapeTarget();
            this.nextPillarPlanTick = this.playerNpc.tickCount + PILLAR_PLAN_RETRY_TICKS;
            return false;
        }
        this.pillarBasePos = plan.basePos();
        this.pillarExitY = plan.exitY();
        this.requiredEscapeBlocks = Math.max(1, plan.blocksNeeded());
        this.maxPillarBlocks = Math.min(this.countEscapeBlocks(), this.requiredEscapeBlocks);
        this.nextPillarPlanTick = 0;
        this.setEscapeMode(EscapeMode.PILLAR);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity liveCombatTarget = this.playerNpc.getTarget();
        if (this.combatRecoveryEpisode && liveCombatTarget != null && liveCombatTarget.isAlive()
                && liveCombatTarget.getY() <= this.playerNpc.getY()
                && this.playerNpc.onGround() && this.placePos == null
                && this.settlingPillarSupportPos == null && this.pillarClearPos == null) {
            return false;
        }
        if (this.teamFollowerRecoveryEpisode && !this.isValidTeamFollowerRecovery()) {
            return false;
        }
        if (this.mode != EscapeMode.FARM_GATE_EGRESS
                && !this.hasEscapeExecutionAccess()) {
            this.workerSlotPaused = this.mode != EscapeMode.NONE;
            return false;
        }
        if (this.mode == EscapeMode.NONE
                || this.finished
                || this.goalTicks >= MAX_GOAL_TICKS
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.isInWater(serverLevel)) {
            return false;
        }

        if (this.mode == EscapeMode.NAVIGATE_ROUTE) {
            return this.routeNavigationTarget != null
                    && this.failedPathTicks < ROUTE_NAV_MAX_FAILED_TICKS
                    && !this.isRouteNavigationComplete(serverLevel, this.playerNpc.blockPosition());
        }
        if (this.mode == EscapeMode.GATHER_BLOCKS) {
            // Target acquisition belongs to tickGatherBlocks. This continuation predicate is
            // polled every tick and must not rescan the nearby block volume after recovery clears
            // the stale target.
            return this.countEscapeBlocks() < this.requiredEscapeBlocks;
        }
        if (this.mode == EscapeMode.CLEAR_EXIT) {
            return this.exitClearPos != null || this.findExitClearTarget(serverLevel, this.playerNpc.blockPosition(), this.climbTargetPos) != null;
        }
        if (this.mode == EscapeMode.FARM_GATE_EGRESS) {
            return this.farmEgressPendingUntilTick > this.playerNpc.tickCount
                    && this.goalTicks < FARM_EGRESS_MAX_ACTION_TICKS;
        }
        if (this.mode == EscapeMode.CLEAR_ROUTE) {
            return this.clearBlockAi.isRunning();
        }

        if (this.mode == EscapeMode.PILLAR && this.pillarClearPos != null) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR && this.protectedPillarPathStuckFallbackAi.isRunning()) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR && this.protectedPillarReplanPending) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR && this.placePos != null) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR && this.settlingPillarSupportPos != null) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR && this.explorationClimbStepOffTargetPos != null) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR && this.needsCompletedExplorationClimbStepOff(serverLevel)) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR
                && this.climbTargetPos != null
                && this.shouldStopOpenSkyRouteClimb(serverLevel, this.playerNpc.blockPosition())) {
            return false;
        }

        if (this.mode == EscapeMode.PILLAR && this.hasUnfinishedRequestedRoute(serverLevel)) {
            return true;
        }

        return this.mode == EscapeMode.PILLAR
                && this.pillarsPlaced < this.maxPillarBlocks
                && this.countEscapeBlocks() > 0;
    }

    private boolean isInWater(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isInWaterOrBubble()
                || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER)
                || !this.playerNpc.onGround() && serverLevel.getFluidState(feet.below()).is(FluidTags.WATER);
    }

    private boolean hasEscapeExecutionAccess() {
        return PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                || this.combatRecoveryEpisode
                || this.teamFollowerRecoveryEpisode && this.isValidTeamFollowerRecovery();
    }

    private boolean isEmergencyRecoveryEpisode() {
        return this.teamFollowerRecoveryEpisode || this.combatRecoveryEpisode;
    }

    private boolean isValidTeamFollowerRecovery() {
        if (!this.playerNpc.isTeamFollower()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.getTarget() != null) {
            return false;
        }
        LivingEntity leader = PlayerNpcTeamUpManager.resolveLeader(this.playerNpc);
        if (leader == null || !leader.isAlive() || leader.isRemoved() || leader.level() != this.playerNpc.level()) {
            return false;
        }
        boolean finishingPillarStep = this.mode == EscapeMode.PILLAR
                && (!this.playerNpc.onGround()
                || this.placePos != null
                || this.settlingPillarSupportPos != null);
        return this.playerNpc.isTeamFollowUpwardEscapeRequested() || finishingPillarStep;
    }

    private boolean tryAcquireEmergencyRecoveryPathStart() {
        if (!this.isEmergencyRecoveryEpisode()) {
            return true;
        }
        long tick = this.playerNpc.level().getServer() == null
                ? this.playerNpc.tickCount
                : this.playerNpc.level().getServer().getTickCount();
        if (this.teamRecoveryPathAdmissionTick == tick) {
            return true;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
            return false;
        }
        this.teamRecoveryPathAdmissionTick = tick;
        return true;
    }

    private void retainTeamFollowerRecoveryProvenance() {
        if (this.teamFollowerRecoveryEpisode) {
            this.playerNpc.retainTeamFollowUpwardEscapeProvenance();
        }
    }

    @Override
    public void start() {
        if (this.mode != EscapeMode.FARM_GATE_EGRESS
                && !this.hasEscapeExecutionAccess()) {
            this.workerSlotPaused = true;
            this.playerNpc.getNavigation().stop();
            return;
        }
        this.workerSlotPaused = false;
        this.goalTicks = 0;
        this.displacedPillarRecoveries = 0;
        this.nextDisplacedPillarRecoveryTick = 0;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.pillarSettleTicks = 0;
        this.pillarSettleWaitTicks = 0;
        this.settlingPillarSupportPos = null;
        this.lastConfirmedPillarSupportPos = null;
        this.placedPillarSupports.clear();
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.gatherPathFallbackAttempts = 0;
        this.pillarsPlaced = 0;
        this.failedPillarPlaceAttempts = 0;
        this.resetPillarStuckWatch();
        this.resetForcedPillarBaseWatch();
        this.forcedPillarBaseRecoveryAttempts = 0;
        this.resetPillarClearReturnWatch();
        this.resetExplorationClimbStepOffWatch();
        this.clearExplorationClimbStepOff();
        this.explorationClimbStepOffCompleted = false;
        this.finished = false;
        this.previousMainHand = ItemStack.EMPTY;
        this.previousPillarMainHand = ItemStack.EMPTY;
        this.usingTemporaryPickaxe = false;
        this.usingTemporaryBlock = false;
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);

        if (this.mode == EscapeMode.GATHER_BLOCKS) {
            if (this.minePos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
                this.equipPreferredToolForPillarClear(serverLevel.getBlockState(this.minePos));
            }
            this.moveToMineTarget();
        } else if (this.mode == EscapeMode.NAVIGATE_ROUTE) {
            this.repathTicks = 0;
            this.failedPathTicks = 0;
            this.moveToRouteNavigationTarget();
            this.updateRouteNavigationDetail();
        } else if (this.mode == EscapeMode.PILLAR && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.beginPillarStep(serverLevel);
        } else if (this.mode == EscapeMode.CLEAR_EXIT && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.playerNpc.getNavigation().stop();
            if (this.exitClearPos == null) {
                this.exitClearPos = this.findExitClearTarget(serverLevel, this.playerNpc.blockPosition(), this.climbTargetPos);
            }
            if (this.exitClearPos != null) {
                this.updateExitClearDetail(serverLevel.getBlockState(this.exitClearPos));
            }
        } else if (this.mode == EscapeMode.CLEAR_ROUTE) {
            this.playerNpc.getNavigation().stop();
        } else if (this.mode == EscapeMode.FARM_GATE_EGRESS) {
            this.repathTicks = 0;
            this.farmEgressRouteFailureTicks = 0;
            this.playerNpc.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.teamFollowerRecoveryEpisode && !this.isValidTeamFollowerRecovery()) {
            this.finished = true;
            return;
        }
        if (this.mode != EscapeMode.FARM_GATE_EGRESS
                && !this.hasEscapeExecutionAccess()) {
            this.workerSlotPaused = true;
            this.pauseForWorkerSlotLoss();
            return;
        }

        this.goalTicks++;
        if (this.mode == EscapeMode.NAVIGATE_ROUTE) {
            this.tickRouteNavigation(serverLevel);
        } else if (this.mode == EscapeMode.GATHER_BLOCKS) {
            this.tickGatherBlocks(serverLevel);
        } else if (this.mode == EscapeMode.CLEAR_EXIT) {
            this.tickClearExit(serverLevel);
        } else if (this.mode == EscapeMode.CLEAR_ROUTE) {
            this.tickExplorationClimbClear(serverLevel);
        } else if (this.mode == EscapeMode.FARM_GATE_EGRESS) {
            this.tickFarmGateEgress(serverLevel);
        } else if (this.mode == EscapeMode.PILLAR) {
            this.tickPillar(serverLevel);
        }
    }

    @Override
    public void stop() {
        if (this.mode != EscapeMode.FARM_GATE_EGRESS
                && !this.isEmergencyRecoveryEpisode()
                && (this.workerSlotPaused || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc))) {
            this.pauseForWorkerSlotLoss();
            return;
        }
        BlockPos failedExplorationTarget = this.explorationClimbEpisode && this.climbTargetPos != null
                ? this.climbTargetPos.immutable()
                : null;
        boolean explorationClimbCompleted = this.explorationClimbStepOffCompleted
                || failedExplorationTarget != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.hasReachedRequestedSurfaceExit(serverLevel, this.playerNpc.blockPosition(), failedExplorationTarget)
                && this.hasReachedOpenSky(serverLevel, this.playerNpc.blockPosition())
                && !this.isActuallyTrapped(serverLevel, this.playerNpc.blockPosition());
        boolean explorationRequestEnded = this.playerNpc.getUpwardEscapeTarget() == null;
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
        this.playerNpc.clearBlockBreakProgress(this.exitClearPos);
        this.clearBlockAi.stop();
        this.gatherPathStuckFallbackAi.stop();
        this.protectedPillarPathStuckFallbackAi.stop();
        this.protectedPillarReplanPending = false;
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.restorePreviousPillarMainHand();
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setHoleEscapeCooldown(Math.max(this.playerNpc.getHoleEscapeCooldown(), COOLDOWN_TICKS));
        }
        if (this.shouldClearUpwardEscapeTargetOnStop()) {
            this.playerNpc.clearUpwardEscapeTarget();
        }
        if (this.playerNpc.getUpwardEscapeTarget() != null
                && this.mode != EscapeMode.FARM_GATE_EGRESS) {
            // A retained request used to bypass holeEscapeCooldown and immediately repeat the
            // complete activation plan after a short/failed pillar episode. Keep the request, but
            // bound retries to once per normal escape cooldown.
            this.nextPillarPlanTick = Math.max(
                    this.nextPillarPlanTick,
                    this.playerNpc.tickCount + COOLDOWN_TICKS
            );
        }
        if (failedExplorationTarget != null && explorationRequestEnded && !explorationClimbCompleted) {
            ExploreAroundGoal.requestSafeWalkAfterFailedClimb(this.playerNpc, failedExplorationTarget);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
        this.teamFollowerRecoveryEpisode = false;
        this.combatRecoveryEpisode = false;
        this.teamRecoveryPathAdmissionTick = Long.MIN_VALUE;
    }

    private void pauseForWorkerSlotLoss() {
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
        this.playerNpc.clearBlockBreakProgress(this.exitClearPos);
        this.playerNpc.getNavigation().stop();
        this.clearBlockAi.stop();
        this.gatherPathStuckFallbackAi.stop();
        this.protectedPillarPathStuckFallbackAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.restorePreviousPillarMainHand();
        this.workerSlotPaused = true;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        // Deliberately retain mode/plan, the upward request, and persisted support ownership. A
        // later holder revalidates/replans them; a non-holder never clears recovery evidence.
    }

    private boolean shouldClearUpwardEscapeTargetOnStop() {
        if (this.mode == EscapeMode.FARM_GATE_EGRESS && this.farmEgressPendingUntilTick > this.playerNpc.tickCount) {
            return false;
        }
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget == null) {
            return true;
        }
        LivingEntity target = this.playerNpc.getTarget();
        if (this.heightDirectedCombatEpisode && requestedTarget.equals(this.climbTargetPos)
                && (target == null || !target.isAlive() || target.getY() <= this.playerNpc.getY())) {
            return true;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return this.hasReachedRequestedRoute(serverLevel, this.playerNpc.blockPosition(), requestedTarget);
    }

    private FarmEgressDecision tryResumeOrStartFarmGateEgress(
            ServerLevel serverLevel,
            BlockPos feet,
            @Nullable BlockPos requestedTarget
    ) {
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (plan == null || !this.isInsideOwnedFarm(plan, feet)) {
            if (this.farmEgressPendingUntilTick > 0) {
                this.clearFarmEgressMemory();
            }
            return FarmEgressDecision.NONE;
        }

        boolean outsideRequest = requestedTarget != null
                && !FarmAi.isInsideFarmFootprint(plan, requestedTarget);
        if (requestedTarget != null
                && requestedTarget.equals(this.farmEgressBypassTarget)
                && this.playerNpc.tickCount < this.farmEgressBypassUntilTick) {
            return FarmEgressDecision.NONE;
        }
        if (this.playerNpc.tickCount >= this.farmEgressBypassUntilTick) {
            this.farmEgressBypassTarget = null;
            this.farmEgressBypassUntilTick = 0;
        }
        boolean pending = this.farmEgressPendingUntilTick > this.playerNpc.tickCount;
        if (!pending && requestedTarget == null) {
            // A fence is an intentional enclosure, not a hole.  Gate egress is only a
            // continuation of an explicit route request whose destination lies beyond
            // the owned farm; otherwise suppress generic hole-escape planning here.
            if (this.farmEgressPendingUntilTick > 0) {
                this.clearFarmEgressMemory();
            }
            return FarmEgressDecision.BLOCK;
        }
        if (!pending && !outsideRequest) {
            // A request aimed at the owned footprint may still need the normal local
            // pillar/clear logic, but it does not authorize crossing the farm gate.
            return FarmEgressDecision.NONE;
        }

        if (this.playerNpc.tickCount < this.farmEgressFailedUntilTick && !pending) {
            if (outsideRequest) {
                this.playerNpc.clearUpwardEscapeTarget();
            }
            int remaining = this.farmEgressFailedUntilTick - this.playerNpc.tickCount;
            this.playerNpc.setHoleEscapeCooldown(Math.max(this.playerNpc.getHoleEscapeCooldown(), remaining));
            this.playerNpc.setIdleTraceDetail("farm gate egress cooling down after bounded failure @ "
                    + posText(plan.gatePos()), 40);
            return FarmEgressDecision.BLOCK;
        }

        if (!pending) {
            this.farmEgressOriginalTarget = requestedTarget == null ? null : requestedTarget.immutable();
            this.farmEgressOriginalMaxPillarBlocks = this.playerNpc.getUpwardEscapeMaxPillarBlocks();
            this.farmEgressOriginalForcedRequest = this.playerNpc.isForcedUpwardEscape();
            this.farmEgressOriginalExplorationRequest = this.playerNpc.isExplorationUpwardEscapeRequested();
            this.farmEgressPendingUntilTick = this.playerNpc.tickCount + FARM_EGRESS_PENDING_TICKS;
            this.farmEgressNextAttemptTick = 0;
            this.farmEgressAttempts = 0;
        }
        if (outsideRequest) {
            this.playerNpc.clearUpwardEscapeTarget();
        }
        if (this.playerNpc.tickCount < this.farmEgressNextAttemptTick) {
            this.playerNpc.setIdleTraceDetail("farm gate egress retry cooling @ " + posText(plan.gatePos()), 20);
            return FarmEgressDecision.BLOCK;
        }
        this.resetPlan();
        if (!this.prepareFarmEgress(plan)) {
            this.failFarmEgressAttempt("saved gate has no interior/outside corridor");
            return FarmEgressDecision.BLOCK;
        }
        setEscapeMode(EscapeMode.FARM_GATE_EGRESS);
        return FarmEgressDecision.START;
    }

    private boolean prepareFarmEgress(Plan plan) {
        if (plan == null || plan.pathPositions().isEmpty()) {
            return false;
        }
        BlockPos inside = plan.pathPositions().get(0).above();
        int outwardX = Integer.compare(plan.gatePos().getX(), inside.getX());
        int outwardZ = Integer.compare(plan.gatePos().getZ(), inside.getZ());
        if (Math.abs(outwardX) + Math.abs(outwardZ) != 1) {
            return false;
        }
        this.farmEgressInsideFeet = inside.immutable();
        this.farmEgressOutsideFeet = plan.gatePos().offset(outwardX, 0, outwardZ).immutable();
        this.farmEgressWaypoint = null;
        this.resetFarmEgressNavigation();
        this.resetFarmEgressClearContext();
        this.farmEgressRouteFailureTicks = 0;
        return true;
    }

    private boolean shouldYieldExplorationClimbToCrafting(
            ServerLevel serverLevel,
            @Nullable BlockPos requestedTarget
    ) {
        if (requestedTarget == null || !this.playerNpc.isExplorationUpwardEscapeRequested()) {
            this.craftingPriorityHandoffTarget = null;
            this.craftingPriorityHandoffUntilTick = 0;
            return false;
        }

        if (CraftBasicGearGoal.isCraftingTableWorkActive(this.playerNpc)) {
            // CraftBasicGearGoal already owns MOVE/LOOK and has a concrete admitted table plan.
            // Let it finish before beginning a new exploration clear/pillar episode, especially
            // when it is preparing the pickaxe needed for the ceiling. This is intentionally a
            // canUse-only gate: an escape already in a jump/placement step is never interrupted.
            this.playerNpc.setIdleTraceDetail("exploration climb waiting for active crafting-table work", 40);
            return true;
        }

        if (!requestedTarget.equals(this.craftingPriorityHandoffTarget)) {
            this.craftingPriorityHandoffTarget = requestedTarget.immutable();
            this.craftingPriorityHandoffUntilTick = CraftBasicGearGoal.shouldPrioritizeGearCrafting(
                    this.playerNpc,
                    serverLevel
            )
                    ? this.playerNpc.tickCount + CRAFTING_PRIORITY_HANDOFF_TICKS
                    : 0;
        }
        if (this.playerNpc.tickCount >= this.craftingPriorityHandoffUntilTick) {
            return false;
        }

        int seconds = Math.max(1, (this.craftingPriorityHandoffUntilTick - this.playerNpc.tickCount + 19) / 20);
        this.playerNpc.setIdleTraceDetail(
                "exploration climb waiting " + seconds + "s for priority crafting-table admission",
                40
        );
        return true;
    }

    private void tickFarmGateEgress(ServerLevel serverLevel) {
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (plan == null || !this.prepareFarmEgressIfMissing(plan)) {
            this.failFarmEgressAttempt("saved gate corridor disappeared");
            return;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.isInsideOwnedFarm(plan, feet)
                || this.isAtFarmEgressWaypoint(this.farmEgressOutsideFeet)) {
            this.completeFarmEgress();
            return;
        }
        if (this.goalTicks >= FARM_EGRESS_MAX_ACTION_TICKS
                || this.farmEgressRouteFailureTicks >= FARM_EGRESS_MAX_ROUTE_FAILURE_TICKS) {
            this.failFarmEgressAttempt("gate corridor route did not converge");
            return;
        }

        if (this.clearBlockAi.isRunning()) {
            if (!this.isSafeFarmEgressClearTarget(serverLevel, plan, this.clearBlockAi.targetPos())) {
                BlockPos protectedTarget = this.clearBlockAi.targetPos();
                this.clearBlockAi.stop();
                this.coolFarmEgressClearTargets(this.farmEgressClearRequestedTarget, protectedTarget);
                this.resetFarmEgressClearContext();
                this.farmEgressRouteFailureTicks += FARM_EGRESS_REPATH_TICKS;
                this.playerNpc.setCurrentAiDetail("farm gate clear retarget protected @ " + posText(protectedTarget));
                return;
            }
            BlockPos activeTarget = this.clearBlockAi.targetPos();
            ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
            if (result == ClearBlockAi.TickResult.RUNNING) {
                if (!this.isSafeFarmEgressClearTarget(serverLevel, plan, this.clearBlockAi.targetPos())) {
                    BlockPos protectedTarget = this.clearBlockAi.targetPos();
                    this.clearBlockAi.stop();
                    this.coolFarmEgressClearTargets(this.farmEgressClearRequestedTarget, protectedTarget);
                    this.resetFarmEgressClearContext();
                    this.farmEgressRouteFailureTicks += FARM_EGRESS_REPATH_TICKS;
                    this.playerNpc.setCurrentAiDetail("farm gate clear blocker protected @ " + posText(protectedTarget));
                }
                return;
            }
            if (result == ClearBlockAi.TickResult.FAILED) {
                this.coolFarmEgressClearTargets(this.farmEgressClearRequestedTarget, activeTarget);
                this.farmEgressRouteFailureTicks += FARM_EGRESS_REPATH_TICKS;
            } else {
                this.farmEgressRouteFailureTicks = 0;
            }
            this.resetFarmEgressClearContext();
            this.repathTicks = 0;
            return;
        }

        BlockState gateState = serverLevel.getBlockState(plan.gatePos());
        boolean validGate = gateState.getBlock() instanceof FenceGateBlock;
        if (validGate && gateState.hasProperty(FenceGateBlock.OPEN) && !gateState.getValue(FenceGateBlock.OPEN)) {
            if (this.playerNpc.distanceToSqr(
                    plan.gatePos().getX() + 0.5D,
                    plan.gatePos().getY() + 0.5D,
                    plan.gatePos().getZ() + 0.5D
            ) <= FARM_GATE_USE_DISTANCE_SQR) {
                if (serverLevel.setBlockAndUpdate(plan.gatePos(), gateState.setValue(FenceGateBlock.OPEN, true))) {
                    this.placingBlockAi.playMainHandAction();
                    serverLevel.playSound(null, plan.gatePos(), SoundEvents.FENCE_GATE_OPEN,
                            SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                this.repathTicks = 0;
                this.playerNpc.setCurrentAiDetail("opened farm gate for egress @ " + posText(plan.gatePos()));
                return;
            }
            this.setFarmEgressDesiredWaypoint(this.farmEgressInsideFeet);
        } else {
            this.setFarmEgressDesiredWaypoint(this.farmEgressOutsideFeet);
        }

        if (!validGate && !gateState.isAir()) {
            if (this.tryStartFarmEgressClear(serverLevel, plan, this.farmEgressWaypoint)) {
                return;
            }
            this.farmEgressRouteFailureTicks += FARM_EGRESS_REPATH_TICKS;
        }

        if (this.moveTowardFarmEgressWaypoint()) {
            this.playerNpc.setCurrentAiDetail("leaving farm through gate @ "
                    + posText(this.farmEgressWaypoint));
            return;
        }
        if (this.tryStartFarmEgressClear(serverLevel, plan, this.farmEgressWaypoint)) {
            return;
        }
        if (this.moveTowardMonotonicFarmEgressIntermediate(plan, this.farmEgressWaypoint)) {
            this.playerNpc.setCurrentAiDetail("routing to farm gate via "
                    + posText(this.farmEgressNavigationWaypoint) + " -> " + posText(this.farmEgressWaypoint));
            return;
        }
        this.farmEgressRouteFailureTicks += FARM_EGRESS_REPATH_TICKS;
        this.playerNpc.setCurrentAiDetail("farm gate egress blocked; retry "
                + Math.min(FARM_EGRESS_MAX_ATTEMPTS, this.farmEgressAttempts + 1)
                + "/" + FARM_EGRESS_MAX_ATTEMPTS + " @ " + posText(plan.gatePos()));
    }

    private boolean prepareFarmEgressIfMissing(Plan plan) {
        return this.farmEgressInsideFeet != null
                && this.farmEgressOutsideFeet != null
                || this.prepareFarmEgress(plan);
    }

    private boolean moveTowardFarmEgressWaypoint() {
        if (this.farmEgressWaypoint == null) {
            return false;
        }
        if (this.isAtFarmEgressWaypoint(this.farmEgressWaypoint)) {
            this.resetFarmEgressNavigation();
            return true;
        }
        this.farmEgressNavigationBlockedThisTick = false;
        if (this.continueFarmEgressNavigation()) {
            return true;
        }
        if (this.farmEgressNavigationBlockedThisTick) {
            return false;
        }
        if (this.startFarmEgressNavigation(this.farmEgressWaypoint)) {
            return true;
        }
        return false;
    }

    private boolean moveTowardMonotonicFarmEgressIntermediate(Plan plan, @Nullable BlockPos desiredWaypoint) {
        if (desiredWaypoint == null) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        double currentDistance = feet.distSqr(desiredWaypoint);
        BlockPos intermediate = plan.pathPositions().stream()
                .map(BlockPos::above)
                .filter(pos -> !pos.equals(feet))
                .filter(pos -> pos.distSqr(desiredWaypoint) + FARM_EGRESS_PROGRESS_EPSILON_SQR < currentDistance)
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(desiredWaypoint)))
                .limit(2)
                .filter(this::startFarmEgressNavigation)
                .findFirst()
                .orElse(null);
        return intermediate != null;
    }

    private boolean startFarmEgressNavigation(BlockPos target) {
        if (target == null) {
            return false;
        }
        Path path = this.createBoundedDiagnosticPath(target);
        boolean started = path != null
                && path.canReach()
                && path.getEndNode() != null
                && path.getEndNode().asBlockPos().equals(target)
                && this.playerNpc.getNavigation().moveTo(path, 1.0D);
        if (!started) {
            return false;
        }
        this.farmEgressNavigationWaypoint = target.immutable();
        this.farmEgressProgressTarget = this.farmEgressNavigationWaypoint;
        this.farmEgressBestNavigationDistanceSqr = this.distanceToFarmEgressWaypointSqr(target);
        this.farmEgressLastProgressTick = this.playerNpc.tickCount;
        return true;
    }

    private boolean continueFarmEgressNavigation() {
        if (this.farmEgressNavigationWaypoint == null) {
            return false;
        }
        if (this.isAtFarmEgressWaypoint(this.farmEgressNavigationWaypoint)) {
            this.resetFarmEgressNavigation();
            return false;
        }
        if (this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
            this.resetFarmEgressNavigation();
            this.farmEgressNavigationBlockedThisTick = true;
            return false;
        }
        double distance = this.distanceToFarmEgressWaypointSqr(this.farmEgressNavigationWaypoint);
        if (!this.farmEgressNavigationWaypoint.equals(this.farmEgressProgressTarget)
                || distance + FARM_EGRESS_PROGRESS_EPSILON_SQR < this.farmEgressBestNavigationDistanceSqr) {
            this.farmEgressProgressTarget = this.farmEgressNavigationWaypoint;
            this.farmEgressBestNavigationDistanceSqr = distance;
            this.farmEgressLastProgressTick = this.playerNpc.tickCount;
        }
        if (this.playerNpc.tickCount - this.farmEgressLastProgressTick < FARM_EGRESS_NO_PROGRESS_TICKS) {
            return true;
        }
        this.playerNpc.getNavigation().stop();
        this.resetFarmEgressNavigation();
        this.farmEgressNavigationBlockedThisTick = true;
        return false;
    }

    private double distanceToFarmEgressWaypointSqr(BlockPos target) {
        return this.playerNpc.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
    }

    private void setFarmEgressDesiredWaypoint(@Nullable BlockPos desiredWaypoint) {
        BlockPos immutable = desiredWaypoint == null ? null : desiredWaypoint.immutable();
        if (java.util.Objects.equals(this.farmEgressWaypoint, immutable)) {
            return;
        }
        this.farmEgressWaypoint = immutable;
        this.playerNpc.getNavigation().stop();
        this.resetFarmEgressNavigation();
    }

    private void resetFarmEgressNavigation() {
        this.farmEgressNavigationWaypoint = null;
        this.farmEgressProgressTarget = null;
        this.farmEgressBestNavigationDistanceSqr = Double.MAX_VALUE;
        this.farmEgressLastProgressTick = 0;
    }

    private boolean tryStartFarmEgressClear(ServerLevel serverLevel, Plan plan, @Nullable BlockPos desiredWaypoint) {
        if (desiredWaypoint == null) {
            return false;
        }
        List<BlockPos> corridor = this.farmEgressCorridorPositions(
                this.playerNpc.blockPosition(),
                desiredWaypoint
        );
        Map<BlockPos, BlockPos> requestedByResolved = new HashMap<>();
        LinkedHashSet<BlockPos> resolvedCandidates = new LinkedHashSet<>();
        corridor.stream()
                .filter(pos -> this.isSafeFarmEgressClearTarget(serverLevel, plan, pos, corridor))
                .filter(pos -> !this.isFarmEgressClearTargetCooling(pos))
                .filter(pos -> !serverLevel.getBlockState(pos).isAir())
                .sorted(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .limit(FARM_EGRESS_MAX_CLEAR_CANDIDATES)
                .forEach(requested -> ClearBlockAi.resolveInitialClearTarget(
                                serverLevel,
                                this.playerNpc,
                                requested,
                                state -> state != null && !state.isAir(),
                                FARM_EGRESS_CLEAR_DISTANCE_SQR,
                                true
                        )
                        .filter(resolved -> this.isSafeFarmEgressClearTarget(serverLevel, plan, resolved, corridor))
                        .filter(resolved -> !this.isFarmEgressClearTargetCooling(resolved))
                        .ifPresent(resolved -> {
                            BlockPos immutable = resolved.immutable();
                            resolvedCandidates.add(immutable);
                            requestedByResolved.putIfAbsent(immutable, requested.immutable());
                        }));
        Optional<BlockPos> target = ClearBlockAi.findNearestAccessibleClearable(
                serverLevel,
                this.playerNpc,
                resolvedCandidates,
                state -> state != null && !state.isAir(),
                FARM_EGRESS_CLEAR_DISTANCE_SQR,
                true
        );
        if (target.isEmpty()
                || !this.isSafeFarmEgressClearTarget(serverLevel, plan, target.get(), corridor)
                || this.isFarmEgressClearTargetCooling(target.get())) {
            return false;
        }
        this.farmEgressClearRequestedTarget = requestedByResolved
                .getOrDefault(target.get(), target.get())
                .immutable();
        this.farmEgressActiveClearCorridor = List.copyOf(corridor);
        boolean started = this.clearBlockAi.start(
                serverLevel,
                target.get(),
                state -> state != null && !state.isAir(),
                "clearing farm gate egress",
                FARM_EGRESS_CLEAR_TICKS,
                FARM_EGRESS_CLEAR_DISTANCE_SQR,
                true,
                true
        );
        if (started && !this.isSafeFarmEgressClearTarget(serverLevel, plan, this.clearBlockAi.targetPos())) {
            this.coolFarmEgressClearTargets(this.farmEgressClearRequestedTarget, this.clearBlockAi.targetPos());
            this.clearBlockAi.stop();
            this.resetFarmEgressClearContext();
            return false;
        }
        if (!started) {
            this.coolFarmEgressClearTargets(this.farmEgressClearRequestedTarget, target.get());
            this.resetFarmEgressClearContext();
            return false;
        }
        return started;
    }

    private List<BlockPos> farmEgressCorridorPositions(BlockPos fromFeet, BlockPos desiredWaypoint) {
        if (fromFeet == null || desiredWaypoint == null) {
            return List.of();
        }
        int deltaX = desiredWaypoint.getX() - fromFeet.getX();
        int deltaY = desiredWaypoint.getY() - fromFeet.getY();
        int deltaZ = desiredWaypoint.getZ() - fromFeet.getZ();
        int rawSteps = Math.max(Math.abs(deltaX), Math.max(Math.abs(deltaY), Math.abs(deltaZ)));
        int steps = Math.min(FARM_EGRESS_CORRIDOR_MAX_STEPS, Math.max(1, rawSteps));
        boolean xMajor = Math.abs(deltaX) >= Math.abs(deltaZ);
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        for (int step = 0; step <= steps; step++) {
            double progress = (double) step / (double) steps;
            int x = Mth.floor(fromFeet.getX() + deltaX * progress + 0.5D);
            int y = Mth.floor(fromFeet.getY() + deltaY * progress + 0.5D);
            int z = Mth.floor(fromFeet.getZ() + deltaZ * progress + 0.5D);
            for (int lateral = -1; lateral <= 1; lateral++) {
                int corridorX = xMajor ? x : x + lateral;
                int corridorZ = xMajor ? z + lateral : z;
                positions.add(new BlockPos(corridorX, y, corridorZ));
                positions.add(new BlockPos(corridorX, y + 1, corridorZ));
            }
        }
        return List.copyOf(positions);
    }

    private boolean isSafeFarmEgressClearTarget(ServerLevel serverLevel, Plan plan, @Nullable BlockPos pos) {
        return this.isSafeFarmEgressClearTarget(
                serverLevel,
                plan,
                pos,
                this.farmEgressActiveClearCorridor
        );
    }

    private boolean isSafeFarmEgressClearTarget(
            ServerLevel serverLevel,
            Plan plan,
            @Nullable BlockPos pos,
            List<BlockPos> corridor
    ) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (pos == null
                || corridor == null
                || !corridor.contains(pos)
                || pos.equals(feet)
                || pos.equals(feet.below())
                || pos.getY() < plan.origin().getY() + 1
                || this.playerNpc.isTemporaryPillarSupport(pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                || serverLevel.getBlockEntity(pos) != null) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()
                || !state.getFluidState().isEmpty()
                || state.getDestroySpeed(serverLevel, pos) < 0.0F
                || state.getBlock() instanceof CropBlock
                || state.is(Blocks.FARMLAND)
                || pos.equals(plan.waterPos())
                || pos.equals(plan.waterPos().above())
                || plan.containsGround(pos)
                || plan.pathPositions().contains(pos)) {
            return false;
        }
        if (pos.equals(plan.gatePos())
                && (state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof FenceBlock)) {
            return true;
        }
        // The candidate is already restricted to this owner's active egress corridor. Permit an
        // expected fence there as a last-resort exit; the plan itself remains unchanged and farm
        // setup will count/replace the missing fence afterward.
        if (plan.isFencePosition(pos) && state.getBlock() instanceof FenceBlock) {
            return true;
        }
        return !plan.isFencePosition(pos);
    }

    private boolean isFarmEgressClearTargetCooling(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Integer until = this.farmEgressFailedClearUntil.get(pos);
        if (until == null) {
            return false;
        }
        if (until <= this.playerNpc.tickCount) {
            this.farmEgressFailedClearUntil.remove(pos);
            return false;
        }
        return true;
    }

    private void coolFarmEgressClearTargets(@Nullable BlockPos... positions) {
        this.farmEgressFailedClearUntil.entrySet().removeIf(entry -> entry.getValue() <= this.playerNpc.tickCount);
        for (BlockPos pos : positions) {
            if (pos == null) {
                continue;
            }
            if (this.farmEgressFailedClearUntil.size() >= FARM_EGRESS_MAX_FAILED_CLEAR_TARGETS
                    && !this.farmEgressFailedClearUntil.containsKey(pos)) {
                BlockPos oldest = this.farmEgressFailedClearUntil.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (oldest != null) {
                    this.farmEgressFailedClearUntil.remove(oldest);
                }
            }
            this.farmEgressFailedClearUntil.put(
                    pos.immutable(),
                    this.playerNpc.tickCount + FARM_EGRESS_FAILED_CLEAR_COOLDOWN_TICKS
            );
        }
    }

    private void resetFarmEgressClearContext() {
        this.farmEgressClearRequestedTarget = null;
        this.farmEgressActiveClearCorridor = List.of();
    }

    private boolean isAtFarmEgressWaypoint(@Nullable BlockPos target) {
        return target != null && this.playerNpc.distanceToSqr(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D
        ) <= FARM_EGRESS_WAYPOINT_REACHED_SQR;
    }

    private boolean isInsideOwnedFarm(Plan plan, BlockPos feet) {
        return plan != null
                && feet != null
                && FarmAi.isInsideFarmFootprint(plan, feet)
                && feet.getY() >= plan.origin().getY()
                && feet.getY() <= plan.origin().getY() + 2;
    }

    private void completeFarmEgress() {
        BlockPos outside = this.farmEgressOutsideFeet;
        this.playerNpc.clearUpwardEscapeTarget();
        this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);
        this.playerNpc.setIdleTraceDetail("farm gate egress complete @ " + posText(outside), 40);
        this.farmEgressFailedUntilTick = 0;
        this.farmEgressBypassTarget = null;
        this.farmEgressBypassUntilTick = 0;
        this.clearFarmEgressMemory();
        this.finished = true;
    }

    private void failFarmEgressAttempt(String reason) {
        this.clearBlockAi.stop();
        this.playerNpc.getNavigation().stop();
        this.farmEgressAttempts++;
        if (this.farmEgressAttempts < FARM_EGRESS_MAX_ATTEMPTS
                && this.playerNpc.tickCount < this.farmEgressPendingUntilTick) {
            this.farmEgressNextAttemptTick = this.playerNpc.tickCount + FARM_EGRESS_RETRY_TICKS;
            this.playerNpc.setIdleTraceDetail("farm gate egress paused: " + reason + " attempt="
                    + this.farmEgressAttempts + "/" + FARM_EGRESS_MAX_ATTEMPTS, 40);
            this.finished = true;
            return;
        }

        BlockPos failedTarget = this.farmEgressOriginalTarget == null
                ? null
                : this.farmEgressOriginalTarget.immutable();
        int failedTargetMaxPillarBlocks = this.farmEgressOriginalMaxPillarBlocks;
        boolean failedForcedRequest = this.farmEgressOriginalForcedRequest;
        boolean failedExplorationRequest = this.farmEgressOriginalExplorationRequest;
        this.farmEgressFailedUntilTick = this.playerNpc.tickCount + FARM_EGRESS_FAILED_COOLDOWN_TICKS;
        if (failedTarget != null) {
            this.farmEgressBypassTarget = failedTarget;
            this.farmEgressBypassUntilTick = this.farmEgressFailedUntilTick;
            if (failedTarget.equals(this.failedExplorationClimbTarget)) {
                this.failedExplorationClimbTarget = null;
                this.failedExplorationClimbUntilTick = 0;
            }
        }
        this.playerNpc.clearUpwardEscapeTarget();
        if (failedTarget != null) {
            if (failedForcedRequest) {
                this.playerNpc.requestForcedUpwardEscapeTo(
                        failedTarget,
                        EXPLORATION_CLIMB_CLEAR_REQUEST_TICKS,
                        failedTargetMaxPillarBlocks
                );
            } else if (failedExplorationRequest) {
                this.playerNpc.requestExplorationUpwardEscapeTo(
                        failedTarget,
                        EXPLORATION_CLIMB_CLEAR_REQUEST_TICKS,
                        failedTargetMaxPillarBlocks
                );
            } else {
                this.playerNpc.requestUpwardEscapeTo(
                        failedTarget,
                        EXPLORATION_CLIMB_CLEAR_REQUEST_TICKS,
                        failedTargetMaxPillarBlocks
                );
            }
            this.retainTeamFollowerRecoveryProvenance();
            this.playerNpc.setIdleTraceDetail("farm gate egress exhausted; trying protected upward route: "
                    + reason + " target=" + posText(failedTarget), 60);
        } else {
            this.playerNpc.setHoleEscapeCooldown(FARM_EGRESS_FAILED_COOLDOWN_TICKS);
            this.playerNpc.setIdleTraceDetail("farm gate egress abandoned after bounded retries: " + reason
                    + " gate=" + posText(this.farmEgressInsideFeet), 60);
        }
        this.clearFarmEgressMemory();
        this.finished = true;
    }

    private void clearFarmEgressMemory() {
        this.farmEgressOriginalTarget = null;
        this.farmEgressInsideFeet = null;
        this.farmEgressOutsideFeet = null;
        this.farmEgressWaypoint = null;
        this.resetFarmEgressNavigation();
        this.resetFarmEgressClearContext();
        this.farmEgressFailedClearUntil.clear();
        this.farmEgressOriginalMaxPillarBlocks = 0;
        this.farmEgressOriginalForcedRequest = false;
        this.farmEgressOriginalExplorationRequest = false;
        this.farmEgressPendingUntilTick = 0;
        this.farmEgressNextAttemptTick = 0;
        this.farmEgressAttempts = 0;
        this.farmEgressRouteFailureTicks = 0;
    }

    private void tickRouteNavigation(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isRouteNavigationComplete(serverLevel, feet)) {
            if (this.hasReachedOpenSky(serverLevel, feet)) {
                this.playerNpc.clearUpwardEscapeTarget();
            }
            this.finished = true;
            return;
        }

        if (this.routeNavigationTarget != null && this.isSameOrNearbyRouteBlock(feet, this.routeNavigationTarget)) {
            this.routeNavigationTarget = null;
            this.repathTicks = 0;
        }

        if (this.routeNavigationTarget == null
                || this.routeNavigationTarget.distSqr(feet) <= ROUTE_NAV_REACHED_SQR) {
            BlockPos nextTarget = this.findReachableRouteNavigationTarget(serverLevel, feet, this.climbTargetPos);
            if (nextTarget == null) {
                this.finished = true;
                return;
            }
            this.routeNavigationTarget = nextTarget;
            this.repathTicks = 0;
        }

        this.updateRouteNavigationDetail();
        if (this.playerNpc.getNavigation().isStuck()) {
            this.failedPathTicks += ROUTE_NAV_REPATH_TICKS;
        }

        if (this.repathTicks-- <= 0) {
            if (this.moveToRouteNavigationTarget()) {
                this.failedPathTicks = 0;
            } else {
                this.failedPathTicks += ROUTE_NAV_REPATH_TICKS;
            }
            this.repathTicks = ROUTE_NAV_REPATH_TICKS;
        }

        if (this.failedPathTicks >= ROUTE_NAV_MAX_FAILED_TICKS) {
            this.finished = true;
        }
    }

    private void tickGatherBlocks(ServerLevel serverLevel) {
        if (this.countEscapeBlocks() >= this.requiredEscapeBlocks) {
            this.gatherPathStuckFallbackAi.stop();
            this.switchToPillar(serverLevel);
            return;
        }

        if (this.tickGatherPathFallback(serverLevel)) {
            return;
        }

        if (this.minePos == null) {
            EscapeMaterialTarget nextTarget = this.findEscapeMaterialTarget(serverLevel);
            if (nextTarget == null) {
                if (this.countEscapeBlocks() > 0) {
                    this.switchToPillar(serverLevel);
                } else {
                    this.finished = true;
                }
                return;
            }
            this.minePos = nextTarget.targetPos();
            this.mineStandPos = nextTarget.standPos();
            this.mineTicks = 0;
            this.moveToMineTarget();
        }

        BlockState state = serverLevel.getBlockState(this.minePos);
        if (!this.canGatherEscapeMaterial(state)) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.restorePreviousMainHand();
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            this.minePos = null;
            this.mineTicks = 0;
            this.resetGatherPathRecovery();
            return;
        }
        if (!this.equipPreferredToolForPillarClear(state)) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.restorePreviousMainHand();
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            this.minePos = null;
            this.mineTicks = 0;
            this.resetGatherPathRecovery();
            return;
        }

        this.updateGatherDetail(state);
        this.playerNpc.getLookControl().setLookAt(
                this.minePos.getX() + 0.5D,
                this.minePos.getY() + 0.5D,
                this.minePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.playerNpc.distanceToSqr(this.minePos.getX() + 0.5D, this.minePos.getY() + 0.5D, this.minePos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            if (this.gatherPathRecoveryDeadlineTick <= 0) {
                this.gatherPathRecoveryDeadlineTick = this.playerNpc.tickCount + GATHER_PATH_RECOVERY_MAX_TICKS;
            }
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.restorePreviousMainHand();
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            if (this.repathTicks-- <= 0) {
                if (this.moveToMineTarget()) {
                    this.failedPathTicks = 0;
                } else {
                    this.failedPathTicks += REPATH_INTERVAL_TICKS;
                    if (this.failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                        this.minePos = null;
                        this.failedPathTicks = 0;
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            if (this.gatherPathFallbackAttempts < MAX_GATHER_PATH_FALLBACK_ATTEMPTS
                    && this.gatherPathStuckFallbackAi.watchAndStart(
                    serverLevel,
                    this.mineStandPos,
                    this.minePos,
                    "escape material recovery",
                    this::avoidGatherFallbackStand
            )) {
                this.beginGatherPathFallback();
                return;
            }
            if (this.playerNpc.tickCount >= this.gatherPathRecoveryDeadlineTick) {
                this.failGatherPathRecovery("could not enter mining reach before recovery deadline");
            } else if (this.gatherPathFallbackAttempts >= MAX_GATHER_PATH_FALLBACK_ATTEMPTS
                    && this.gatherPathFinalAttemptDeadlineTick > 0
                    && this.playerNpc.tickCount >= this.gatherPathFinalAttemptDeadlineTick) {
                this.failGatherPathRecovery("still outside mining reach after "
                        + this.gatherPathFallbackAttempts + " step-off attempts");
            }
            return;
        }

        this.resetGatherPathRecovery();

        this.playerNpc.getNavigation().stop();
        BlockPos minedPos = this.minePos.immutable();
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                minedPos,
                this::canGatherEscapeMaterial,
                this.getRequiredMineTicks(serverLevel, minedPos, state),
                String.format(
                        java.util.Locale.ROOT,
                        "escape blocks %d/%d mining %s",
                        this.countEscapeBlocks(),
                        this.requiredEscapeBlocks,
                        state.getBlock().getDescriptionId()
                )
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.playerNpc.clearBlockBreakProgress(minedPos);
        this.minePos = null;
        this.mineStandPos = null;
        this.mineTicks = 0;
    }

    private void tickClearExit(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.climbTargetPos == null && !this.isActuallyTrapped(serverLevel, feet)) {
            this.finished = true;
            return;
        }

        if (this.exitClearPos == null || !this.isClearableExitObstruction(serverLevel, this.exitClearPos, serverLevel.getBlockState(this.exitClearPos))) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.restorePreviousMainHand();
            this.playerNpc.clearBlockBreakProgress(this.exitClearPos);
            this.exitClearPos = this.findExitClearTarget(serverLevel, feet, this.climbTargetPos);
            this.mineTicks = 0;
            if (this.exitClearPos == null) {
                this.finished = true;
                return;
            }
        }

        BlockState state = serverLevel.getBlockState(this.exitClearPos);
        if (!this.equipPreferredToolForPillarClear(state)) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.restorePreviousMainHand();
            this.playerNpc.clearBlockBreakProgress(this.exitClearPos);
            this.exitClearPos = null;
            this.mineTicks = 0;
            return;
        }
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.exitClearPos.getX() + 0.5D,
                this.exitClearPos.getY() + 0.5D,
                this.exitClearPos.getZ() + 0.5D,
                60.0F,
                60.0F
        );

        BlockPos clearedPos = this.exitClearPos.immutable();
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                clearedPos,
                clearState -> this.isClearableExitObstruction(serverLevel, clearedPos, clearState),
                this.getRequiredMineTicks(serverLevel, clearedPos, state),
                String.format(
                        java.util.Locale.ROOT,
                        "clearing pillar path %s",
                        state.getBlock().getDescriptionId()
                )
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.playerNpc.clearBlockBreakProgress(clearedPos);
        this.exitClearPos = null;
        this.farmEgressInsideFeet = null;
        this.farmEgressOutsideFeet = null;
        this.farmEgressWaypoint = null;
        this.farmEgressRouteFailureTicks = 0;
        this.mineTicks = 0;
    }

    private boolean tickGatherPathFallback(ServerLevel serverLevel) {
        if (!this.gatherPathStuckFallbackAi.isRunning()) {
            return false;
        }

        boolean stillRunning = this.gatherPathStuckFallbackAi.tick(serverLevel, "escape material recovery");
        this.playerNpc.setCurrentAiDetail(this.gatherPathStuckFallbackAi.detail("escape material recovery"));
        if (stillRunning) {
            return true;
        }
        if (this.gatherPathFallbackAttempts >= MAX_GATHER_PATH_FALLBACK_ATTEMPTS) {
            // Give the final forced step enough time to reacquire and approach one fresh exact
            // stand. If that also cannot enter reach, terminate this escape episode instead of
            // starting an unbounded sequence of forced movement attempts.
            this.gatherPathFinalAttemptDeadlineTick = this.playerNpc.tickCount + 20 * 5;
        }
        return false;
    }

    private void beginGatherPathFallback() {
        this.gatherPathFallbackAttempts++;
        this.gatherPathFinalAttemptDeadlineTick = 0;
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.minePos = null;
        this.mineStandPos = null;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.playerNpc.setCurrentAiDetail(this.gatherPathStuckFallbackAi.detail("escape material recovery"));
    }

    private boolean avoidGatherFallbackStand(BlockPos stand) {
        return stand == null
                || stand.equals(this.mineStandPos)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, stand)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, stand);
    }

    private void resetGatherPathRecovery() {
        this.gatherPathStuckFallbackAi.stop();
        this.gatherPathFallbackAttempts = 0;
        this.gatherPathRecoveryDeadlineTick = 0;
        this.gatherPathFinalAttemptDeadlineTick = 0;
    }

    private void failGatherPathRecovery(String reason) {
        BlockPos failedRoute = this.climbTargetPos != null
                ? this.climbTargetPos.immutable()
                : this.playerNpc.getUpwardEscapeTarget();
        this.gatherPathStuckFallbackAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.minePos = null;
        this.mineStandPos = null;
        this.nextPillarPlanTick = this.playerNpc.tickCount + GATHER_PATH_FALLBACK_FAILED_COOLDOWN_TICKS;
        this.playerNpc.setHoleEscapeCooldown(Math.max(
                this.playerNpc.getHoleEscapeCooldown(),
                GATHER_PATH_FALLBACK_FAILED_COOLDOWN_TICKS
        ));
        if (this.explorationClimbEpisode) {
            this.abandonExplorationClimb(failedRoute, reason);
        } else if (this.playerNpc.getUpwardEscapeTarget() != null) {
            // Release the mining/exploration owner after the bounded escape episode. It may
            // choose a different target and issue a fresh request after the cooldown.
            this.playerNpc.clearUpwardEscapeTarget();
        }
        this.playerNpc.setIdleTraceDetail("escape material recovery abandoned: " + reason, 20 * 3);
        this.finished = true;
    }

    private void tickExplorationClimbClear(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            this.finished = true;
            return;
        }

        boolean pathWorkAllowed = !this.isEmergencyRecoveryEpisode()
                || !this.clearBlockAi.needsPathWork(serverLevel)
                || this.tryAcquireEmergencyRecoveryPathStart();
        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel, pathWorkAllowed);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            return;
        }

        if (result == ClearBlockAi.TickResult.DONE) {
            this.nextPillarPlanTick = 0;
            if (this.climbTargetPos != null && this.climbTargetPos.equals(this.failedExplorationClimbTarget)) {
                this.failedExplorationClimbTarget = null;
                this.failedExplorationClimbUntilTick = 0;
            }
        } else {
            BlockPos failedRoute = this.climbTargetPos != null
                    ? this.climbTargetPos.immutable()
                    : this.playerNpc.getUpwardEscapeTarget();
            this.abandonExplorationClimb(failedRoute, "clear failed");
            this.nextPillarPlanTick = this.playerNpc.tickCount + PILLAR_PLAN_RETRY_TICKS;
        }
        this.finished = true;
    }

    private void abandonExplorationClimb(@Nullable BlockPos failedRoute, String reason) {
        if (failedRoute == null || !this.playerNpc.isExplorationUpwardEscapeRequested()) {
            return;
        }

        this.failedExplorationClimbTarget = failedRoute.immutable();
        this.failedExplorationClimbUntilTick = this.playerNpc.tickCount + FAILED_EXPLORATION_CLIMB_RETRY_TICKS;
        ExploreAroundGoal.requestSafeWalkAfterFailedClimb(this.playerNpc, failedRoute);
        this.playerNpc.clearUpwardEscapeTarget();
        this.playerNpc.setHoleEscapeCooldown(FAILED_EXPLORATION_CLIMB_RETRY_TICKS);
        this.playerNpc.setIdleTraceDetail("exploration climb abandoned: " + reason + " @ "
                + failedRoute.getX() + " " + failedRoute.getY() + " " + failedRoute.getZ(), 40);
    }

    private void switchToPillar(ServerLevel serverLevel) {
        this.resetGatherPathRecovery();
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.restorePreviousMainHand();
        this.restorePreviousPillarMainHand();
        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks <= 0) {
            this.finished = true;
            return;
        }

        PillarPlan pillarPlan = this.findPillarPlan(serverLevel, this.playerNpc.blockPosition(), this.climbTargetPos);
        if (pillarPlan == null) {
            this.finished = true;
            return;
        }
        if (this.exceedsRequestedRouteMax(pillarPlan)) {
            this.playerNpc.clearUpwardEscapeTarget();
            this.finished = true;
            return;
        }
        this.pillarBasePos = pillarPlan.basePos();
        this.pillarExitY = pillarPlan.exitY();
        setEscapeMode(EscapeMode.PILLAR);
        this.minePos = null;
        this.mineStandPos = null;
        this.mineTicks = 0;
        this.maxPillarBlocks = Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, pillarPlan.blocksNeeded())));
        this.pillarsPlaced = 0;
        this.beginPillarStep(serverLevel);
    }

    private void tickPillar(ServerLevel serverLevel) {
        if (this.protectedPillarPathStuckFallbackAi.tick(serverLevel, "protected pillar reposition")) {
            this.playerNpc.setCurrentAiDetail(
                    this.protectedPillarPathStuckFallbackAi.detail("protected pillar reposition"));
            return;
        }
        if (this.protectedPillarReplanPending) {
            this.protectedPillarReplanPending = false;
            BlockPos feet = this.playerNpc.blockPosition();
            PillarPlan plan = this.findPillarPlan(serverLevel, feet, this.climbTargetPos);
            if (plan == null || this.exceedsRequestedRouteMax(plan)) {
                this.finished = true;
                this.playerNpc.setCurrentAiDetail("protected pillar reposition found no safe retry");
                return;
            }
            this.applyRecoveredPillarPlan(plan, "replanned pillar after protected obstruction");
            this.beginPillarStep(serverLevel);
            return;
        }
        if (this.explorationClimbStepOffTargetPos != null) {
            this.tickExplorationClimbStepOff(serverLevel);
            return;
        }

        if (this.tryStartCompletedExplorationClimbStepOff(serverLevel)) {
            return;
        }

        if (this.pillarClearPos != null) {
            this.tickPillarClearance(serverLevel);
            return;
        }

        if (this.settlingPillarSupportPos != null) {
            this.tickPillarSettlement(serverLevel);
            return;
        }

        BlockPos missingSupport = this.findMissingPlacedPillarSupport(serverLevel);
        if (missingSupport != null) {
            this.stopForMissingPillarSupport(missingSupport);
            return;
        }

        if (this.placePos == null) {
            this.resetPillarStuckWatch();
            if (!this.shouldContinuePillaring(serverLevel)) {
                if (this.tryContinueRequestedRoutePillar(serverLevel)) {
                    return;
                }
                this.finished = true;
                return;
            }
            if (!this.isAtPillarBase()) {
                if (this.tryStartStuckExplorationClimbStepOff(serverLevel)) {
                    return;
                }
                if (this.tryRecoverForcedPillarBase(serverLevel)) {
                    return;
                }
                this.moveToPillarBase();
                return;
            }
            this.resetForcedPillarBaseWatch();
            if (this.playerNpc.onGround()) {
                this.beginPillarStep(serverLevel);
            }
            return;
        }

        if (this.tryRecoverStuckPillar(serverLevel)) {
            return;
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return;
        }

        this.placeWaitTicks++;
        if (this.tryAcceptOccupiedPillarSupport(serverLevel)) {
            return;
        }

        if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
            BlockPos obstruction = this.findPillarRecoveryObstruction(serverLevel, this.playerNpc.blockPosition());
            if (obstruction != null) {
                this.recoverOrStartPillarClearance(serverLevel, this.playerNpc.blockPosition(), obstruction);
                return;
            }
            if (!this.playerNpc.onGround()) {
                this.placeWaitTicks = 0;
                this.lookDownAt(this.placePos);
                return;
            }
            this.placeWaitTicks = 0;
            this.failedPillarPlaceAttempts++;
            if (this.failedPillarPlaceAttempts >= 3) {
                this.finished = true;
                return;
            }
            this.lookDownAt(this.placePos);
            this.playerNpc.shortPillarJump();
            return;
        }

        if (!this.equipEscapeBlockForPlacement()) {
            this.finished = true;
            return;
        }

        ItemStack blockStack = this.playerNpc.getMainHandItem();
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            this.finished = true;
            return;
        }
        BlockState pillarState = blockItem.getBlock().defaultBlockState();

        if (!this.hasPillarPlacementClearance(serverLevel, this.placePos, pillarState)) {
            BlockPos obstruction = this.findPillarRecoveryObstruction(serverLevel, this.playerNpc.blockPosition());
            if (obstruction != null && this.placeWaitTicks >= PILLAR_STUCK_MIN_TICKS) {
                this.recoverOrStartPillarClearance(serverLevel, this.playerNpc.blockPosition(), obstruction);
                return;
            }
            this.lookDownAt(this.placePos);
            return;
        }

        if (!serverLevel.getBlockState(this.placePos).canBeReplaced()) {
            if (this.tryAcceptOccupiedPillarSupport(serverLevel)) {
                return;
            }
            this.placePos = null;
            return;
        }

        if (!this.canPlacePillarWithoutClipping(serverLevel, this.placePos, pillarState)) {
            BlockPos obstruction = this.findPillarRecoveryObstruction(serverLevel, this.playerNpc.blockPosition());
            if (obstruction != null) {
                this.recoverOrStartPillarClearance(serverLevel, this.playerNpc.blockPosition(), obstruction);
                return;
            }
            if (this.tryRecoverPillarPosition(serverLevel, this.playerNpc.blockPosition())) {
                return;
            }
            this.finished = true;
            return;
        }

        this.lookDownAt(this.placePos);
        BlockPos placedSupport = this.placePos.immutable();
        if (this.isGravityPillarBlock(blockStack)
                && !this.hasSolidGravityPillarSupport(serverLevel, placedSupport.below())) {
            this.finished = true;
            return;
        }
        if (!this.placingBlockAi.placeHeldBlock(serverLevel, placedSupport, pillarState)) {
            this.finished = true;
            return;
        }
        if (!this.isStablePillarSupport(serverLevel, placedSupport)) {
            this.stopForMissingPillarSupport(placedSupport);
            return;
        }
        this.playerNpc.markTemporaryPillarSupport(placedSupport);
        this.placedPillarSupports.add(placedSupport);
        this.pillarsPlaced++;
        this.failedPillarPlaceAttempts = 0;
        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.beginPillarSettlement(placedSupport);
        this.resetPillarStuckWatch();
        this.updatePillarDetail();
    }

    private boolean tryRecoverStuckPillar(ServerLevel serverLevel) {
        if (this.placePos == null || this.pillarClearPos != null) {
            this.resetPillarStuckWatch();
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.pillarStuckWatchPos == null
                || !this.pillarStuckWatchPos.equals(feet)
                || this.pillarStuckWatchPillarsPlaced != this.pillarsPlaced) {
            this.pillarStuckWatchPos = feet.immutable();
            this.pillarStuckWatchPillarsPlaced = this.pillarsPlaced;
            this.pillarStuckWatchStartTick = this.playerNpc.tickCount;
            this.nextPillarStuckRecoveryTick = this.playerNpc.tickCount + PILLAR_STUCK_MIN_TICKS;
            return false;
        }

        if (this.playerNpc.tickCount - this.pillarStuckWatchStartTick < PILLAR_STUCK_MIN_TICKS
                || this.playerNpc.tickCount < this.nextPillarStuckRecoveryTick) {
            return false;
        }

        this.nextPillarStuckRecoveryTick = this.playerNpc.tickCount + PILLAR_STUCK_RECHECK_TICKS;
        BlockPos obstruction = this.findPillarRecoveryObstruction(serverLevel, feet);
        if (obstruction != null) {
            this.recoverOrStartPillarClearance(serverLevel, feet, obstruction);
            return true;
        }

        return this.tryRecoverPillarPosition(serverLevel, feet);
    }

    private BlockPos findPillarRecoveryObstruction(ServerLevel serverLevel, BlockPos feet) {
        BlockPos obstruction = this.findPillarObstruction(serverLevel, feet);
        if (obstruction != null) {
            return obstruction;
        }

        // A combat escape owns the current pillar column. Nearby walls are the evidence that the
        // NPC is trapped, not clearance targets. Mining them can consume the entire escape while
        // an otherwise open vertical column is ready for a block placement.
        if (this.combatRecoveryEpisode) {
            return null;
        }

        obstruction = this.findCurrentPillarCollisionBlocker(serverLevel);
        if (obstruction != null) {
            return obstruction;
        }

        obstruction = this.findCenteredPillarCollisionBlocker(serverLevel);
        if (obstruction != null) {
            return obstruction;
        }

        return this.findAdjacentPillarRecoveryObstruction(serverLevel, feet);
    }

    private BlockPos findAdjacentPillarRecoveryObstruction(ServerLevel serverLevel, BlockPos feet) {
        BlockPos routeTarget = this.climbTargetPos == null ? feet : this.climbTargetPos;
        for (Direction direction : this.directionsToward(feet, routeTarget)) {
            BlockPos adjacentFeet = feet.relative(direction);
            BlockPos[] candidates = {
                    adjacentFeet,
                    adjacentFeet.above()
            };
            for (BlockPos candidate : candidates) {
                BlockState state = serverLevel.getBlockState(candidate);
                if (this.isClearablePillarObstruction(serverLevel, candidate, state)) {
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    private boolean tryRecoverPillarPosition(ServerLevel serverLevel, BlockPos feet) {
        if (this.tryCenterOnPillarBase(serverLevel)) {
            return true;
        }

        return this.tryMoveToAlternatePillarBase(serverLevel, feet);
    }

    /**
     * A forced home-surface request can select an adjacent pillar base whose navigation path ends
     * one cell short. Since {@code placePos} is still null, the normal placed-pillar stuck watcher
     * never runs and the goal used to retry that completed path indefinitely with an empty detail.
     * After one bounded no-progress interval, prefer the NPC's current valid column, otherwise
     * choose a different adjacent base instead of restarting the same failed route.
     */
    private boolean tryRecoverForcedPillarBase(ServerLevel serverLevel) {
        if (!this.playerNpc.isForcedUpwardEscape()
                || this.climbTargetPos == null
                || this.pillarBasePos == null
                || !this.playerNpc.onGround()) {
            this.resetForcedPillarBaseWatch();
            return false;
        }
        if (!this.playerNpc.getNavigation().isDone() && !this.playerNpc.getNavigation().isStuck()) {
            this.resetForcedPillarBaseWatch();
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!feet.equals(this.forcedPillarBaseWatchFeet)
                || !this.pillarBasePos.equals(this.forcedPillarBaseWatchTarget)) {
            this.forcedPillarBaseWatchFeet = feet.immutable();
            this.forcedPillarBaseWatchTarget = this.pillarBasePos.immutable();
            this.forcedPillarBaseWatchStartTick = this.playerNpc.tickCount;
            this.updatePillarRecoveryDetail("waiting for pillar base route");
            return true;
        }
        if (this.playerNpc.tickCount - this.forcedPillarBaseWatchStartTick
                < FORCED_PILLAR_BASE_NO_PROGRESS_TICKS) {
            this.updatePillarRecoveryDetail("waiting for pillar base route");
            return true;
        }

        BlockPos stalledBase = this.pillarBasePos.immutable();
        this.forcedPillarBaseRecoveryAttempts++;

        // A completed one-node path commonly means the requested adjacent base was rejected
        // because the NPC's current column has a body/head collider. Clear that collider through
        // the normal protected pillar-clearance path before searching for another base. Without
        // this handoff the route watcher can alternate adjacent bases for the whole goal lifetime.
        BlockPos obstruction = this.findPillarObstruction(serverLevel, feet);
        if (obstruction != null) {
            this.forcedPillarBaseRecoveryAttempts = 0;
            this.recoverOrStartPillarClearance(serverLevel, feet, obstruction);
            return true;
        }

        PillarPlan currentPlan = this.createPillarPlan(serverLevel, feet, this.climbTargetPos);
        if (currentPlan != null && !this.exceedsRequestedRouteMax(currentPlan)) {
            this.forcedPillarBaseRecoveryAttempts = 0;
            this.applyRecoveredPillarPlan(currentPlan, "using current pillar base");
            this.beginPillarStep(serverLevel);
            return true;
        }
        if (this.forcedPillarBaseRecoveryAttempts >= MAX_FORCED_PILLAR_BASE_RECOVERIES) {
            return this.startForcedPillarBaseFallback(serverLevel);
        }
        if (this.tryMoveToAlternatePillarBase(serverLevel, feet, stalledBase, false)) {
            this.resetForcedPillarBaseWatch();
            return true;
        }

        this.forcedPillarBaseWatchStartTick = this.playerNpc.tickCount;
        this.updatePillarRecoveryDetail("pillar base route blocked");
        return true;
    }

    private boolean startForcedPillarBaseFallback(ServerLevel serverLevel) {
        boolean repositioning = this.protectedPillarPathStuckFallbackAi.start(
                serverLevel,
                this.climbTargetPos,
                "pillar base recovery",
                pos -> FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                        || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
        );
        this.playerNpc.setCurrentAiDetail(
                this.protectedPillarPathStuckFallbackAi.detail("pillar base recovery"));
        this.forcedPillarBaseRecoveryAttempts = 0;
        this.resetForcedPillarBaseWatch();
        if (!repositioning) {
            this.finished = true;
            this.playerNpc.setCurrentAiDetail("pillar base recovery found no safe step-off");
            return true;
        }

        this.placePos = null;
        this.pillarBasePos = null;
        this.pillarClearPos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.resetPillarStuckWatch();
        this.protectedPillarReplanPending = true;
        return true;
    }

    private void applyRecoveredPillarPlan(PillarPlan plan, String detail) {
        int escapeBlocks = this.countEscapeBlocks();
        this.pillarBasePos = plan.basePos();
        this.pillarExitY = plan.exitY();
        this.requiredEscapeBlocks = Math.max(1, plan.blocksNeeded());
        this.maxPillarBlocks = Math.min(
                escapeBlocks,
                Math.max(1, Math.min(this.requiredEscapeBlocks, plan.blocksNeeded()))
        );
        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.resetForcedPillarBaseWatch();
        this.updatePillarRecoveryDetail(detail);
    }

    private void resetForcedPillarBaseWatch() {
        this.forcedPillarBaseWatchFeet = null;
        this.forcedPillarBaseWatchTarget = null;
        this.forcedPillarBaseWatchStartTick = 0;
    }

    private boolean tryCenterOnPillarBase(ServerLevel serverLevel) {
        if (this.placePos == null) {
            return false;
        }

        double targetX = this.placePos.getX() + 0.5D;
        double targetZ = this.placePos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        if (Math.abs(dx) <= PILLAR_CENTER_EPSILON && Math.abs(dz) <= PILLAR_CENTER_EPSILON) {
            return false;
        }

        AABB centeredBox = this.playerNpc.getBoundingBox().move(dx, 0.0D, dz);
        if (!PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, centeredBox)) {
            return false;
        }

        double motionY = Math.max(0.0D, this.playerNpc.getDeltaMovement().y);
        this.playerNpc.setPos(targetX, this.playerNpc.getY(), targetZ);
        this.playerNpc.setDeltaMovement(0.0D, motionY, 0.0D);
        this.playerNpc.fallDistance = 0.0F;
        this.placeDelayTicks = PLACE_DELAY_TICKS;
        this.placeWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.placePos);
        if (this.playerNpc.onGround()) {
            this.playerNpc.shortPillarJump();
        }
        this.resetPillarStuckWatch();
        this.updatePillarRecoveryDetail("re-centering pillar");
        return true;
    }

    private boolean tryMoveToAlternatePillarBase(ServerLevel serverLevel, BlockPos feet) {
        return this.tryMoveToAlternatePillarBase(serverLevel, feet, null, false);
    }

    private boolean tryMoveToAlternatePillarBase(
            ServerLevel serverLevel,
            BlockPos feet,
            @Nullable BlockPos avoidedBase,
            boolean avoidBuildFootprintObstruction
    ) {
        BlockPos routeTarget = this.climbTargetPos;
        for (Direction direction : this.directionsToward(feet, routeTarget == null ? feet.above() : routeTarget)) {
            BlockPos candidate = feet.relative(direction);
            if (candidate.equals(avoidedBase)) {
                continue;
            }
            PillarPlan plan = this.createPillarPlan(serverLevel, candidate, routeTarget);
            if (plan == null
                    || routeTarget != null && this.exceedsRequestedRouteMax(plan)
                    || avoidBuildFootprintObstruction
                    && this.hasBuildFootprintPillarObstruction(serverLevel, plan)) {
                continue;
            }

            int escapeBlocks = this.countEscapeBlocks();
            if (escapeBlocks <= 0) {
                return false;
            }

            this.pillarBasePos = plan.basePos();
            this.pillarExitY = plan.exitY();
            if (routeTarget != null) {
                this.requiredEscapeBlocks = Math.max(1, plan.blocksNeeded());
            }
            this.maxPillarBlocks = routeTarget == null
                    ? 1
                    : Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, plan.blocksNeeded())));
            this.pillarsPlaced = 0;
            this.placePos = null;
            this.placeDelayTicks = 0;
            this.placeWaitTicks = 0;
            this.settlingPillarSupportPos = null;
            this.lastConfirmedPillarSupportPos = null;
            this.pillarSettleTicks = 0;
            this.pillarSettleWaitTicks = 0;
            this.placedPillarSupports.clear();
            this.failedPillarPlaceAttempts = 0;
            this.resetPillarStuckWatch();
            this.resetForcedPillarBaseWatch();
            this.moveToEscapeTarget(plan.basePos(), 1.0D);
            this.playerNpc.getMoveControl().setWantedPosition(
                    plan.basePos().getX() + 0.5D,
                    plan.basePos().getY(),
                    plan.basePos().getZ() + 0.5D,
                    0.8D
            );
            this.updatePillarRecoveryDetail("shifting pillar base");
            return true;
        }

        return false;
    }

    private boolean hasBuildFootprintPillarObstruction(ServerLevel serverLevel, PillarPlan plan) {
        int topY = Math.min(plan.exitY() + 1, plan.basePos().getY() + 2);
        for (int y = plan.basePos().getY(); y <= topY; y++) {
            BlockPos pos = new BlockPos(plan.basePos().getX(), y, plan.basePos().getZ());
            if (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                    && this.blocksPillarSpace(serverLevel, pos, serverLevel.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private boolean tryStartStuckExplorationClimbStepOff(ServerLevel serverLevel) {
        if (!this.isExplorationClimbRequest() || this.pillarBasePos == null || !this.playerNpc.onGround()) {
            this.resetExplorationClimbStepOffWatch();
            return false;
        }
        if (!this.playerNpc.getNavigation().isDone() && !this.playerNpc.getNavigation().isStuck()) {
            this.explorationClimbStepOffWatchStartTick = this.playerNpc.tickCount;
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos routeTarget = this.climbTargetPos == null ? this.playerNpc.getUpwardEscapeTarget() : this.climbTargetPos;
        if (routeTarget == null) {
            this.resetExplorationClimbStepOffWatch();
            return false;
        }

        if (this.explorationClimbStepOffWatchPos == null
                || !this.explorationClimbStepOffWatchPos.equals(feet)
                || this.explorationClimbStepOffWatchTarget == null
                || !this.explorationClimbStepOffWatchTarget.equals(routeTarget)
                || this.explorationClimbStepOffWatchBase == null
                || !this.explorationClimbStepOffWatchBase.equals(this.pillarBasePos)) {
            this.explorationClimbStepOffWatchPos = feet.immutable();
            this.explorationClimbStepOffWatchTarget = routeTarget.immutable();
            this.explorationClimbStepOffWatchBase = this.pillarBasePos.immutable();
            this.explorationClimbStepOffWatchStartTick = this.playerNpc.tickCount;
            return false;
        }

        if (this.playerNpc.tickCount - this.explorationClimbStepOffWatchStartTick < EXPLORATION_CLIMB_STEP_OFF_STUCK_TICKS) {
            return false;
        }

        BlockPos stepOffTarget = this.findExplorationClimbStepOffTarget(serverLevel, feet, routeTarget);
        if (stepOffTarget == null) {
            this.explorationClimbStepOffWatchStartTick = this.playerNpc.tickCount;
            return false;
        }

        return this.startExplorationClimbStepOff(feet, stepOffTarget);
    }

    private boolean tryStartCompletedExplorationClimbStepOff(ServerLevel serverLevel) {
        if (!this.needsCompletedExplorationClimbStepOff(serverLevel)) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos routeTarget = this.climbTargetPos;
        BlockPos stepOffTarget = this.findExplorationClimbStepOffTarget(serverLevel, feet, routeTarget);
        if (stepOffTarget != null) {
            return this.startExplorationClimbStepOff(feet, stepOffTarget);
        }

        this.abandonExplorationClimb(routeTarget, "no safe landing away from completed pillar");
        this.finished = true;
        return true;
    }

    private boolean needsCompletedExplorationClimbStepOff(ServerLevel serverLevel) {
        if (this.explorationClimbStepOffTargetPos != null
                || this.explorationClimbStepOffCompleted
                || this.placePos != null
                || this.settlingPillarSupportPos != null
                || this.pillarClearPos != null
                || !this.isExplorationClimbRequest()
                || !this.playerNpc.onGround()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isTemporaryPillarSupport(feet.below())
                && this.hasReachedRequestedSurfaceExit(serverLevel, feet, this.climbTargetPos)
                && this.hasReachedOpenSky(serverLevel, feet)
                && !this.isActuallyTrapped(serverLevel, feet);
    }

    private boolean isExplorationClimbRequest() {
        return this.explorationClimbEpisode && this.climbTargetPos != null;
    }

    private boolean startExplorationClimbStepOff(BlockPos feet, BlockPos stepOffTarget) {
        this.explorationClimbStepOffStartPos = feet.immutable();
        this.explorationClimbStepOffTargetPos = stepOffTarget.immutable();
        this.explorationClimbStepOffTicks = EXPLORATION_CLIMB_STEP_OFF_TICKS;
        this.playerNpc.getNavigation().stop();
        if (stepOffTarget.equals(this.ownedPocketExitTarget)) {
            // The short, collision-checked crossing may span the one-cell lower pocket. A normal
            // jump keeps the NPC from falling back into it before reaching the proven rim.
            this.playerNpc.shortPillarJump();
        }
        this.forceExplorationClimbStepOff();
        return true;
    }

    private BlockPos findExplorationClimbStepOffTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (routeTarget != null && routeTarget.equals(this.ownedPocketExitTarget)) {
            return this.hasClearPocketRimCrossing(serverLevel, feet, routeTarget) ? routeTarget.immutable() : null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> relaxedCandidates = new ArrayList<>();
        int radiusSqr = EXPLORATION_CLIMB_STEP_OFF_RADIUS * EXPLORATION_CLIMB_STEP_OFF_RADIUS;
        for (int dx = -EXPLORATION_CLIMB_STEP_OFF_RADIUS; dx <= EXPLORATION_CLIMB_STEP_OFF_RADIUS; dx++) {
            for (int dz = -EXPLORATION_CLIMB_STEP_OFF_RADIUS; dz <= EXPLORATION_CLIMB_STEP_OFF_RADIUS; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int fall = feet.getY() - y;
                if (fall < 0 || fall > EXPLORATION_CLIMB_STEP_OFF_MAX_FALL) {
                    continue;
                }

                BlockPos candidate = new BlockPos(x, y, z);
                if (!this.canStandAt(serverLevel, candidate) || !this.canStepOffToward(serverLevel, feet, candidate)) {
                    continue;
                }

                if (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)) {
                    relaxedCandidates.add(candidate.immutable());
                } else {
                    candidates.add(candidate.immutable());
                }
            }
        }

        BlockPos selected = this.selectExplorationClimbStepOffTarget(candidates, feet, routeTarget);
        if (selected != null) {
            return selected;
        }
        selected = this.selectExplorationClimbStepOffTarget(relaxedCandidates, feet, routeTarget);
        return selected == null ? this.findOpenExplorationClimbPushTarget(serverLevel, feet, routeTarget) : selected;
    }

    private boolean hasClearPocketRimCrossing(ServerLevel serverLevel, BlockPos feet, BlockPos rim) {
        if (horizontalDistanceSqr(feet, rim) > OPEN_SHAFT_COMPONENT_RADIUS * OPEN_SHAFT_COMPONENT_RADIUS
                || rim.getY() > feet.getY() || feet.getY() - rim.getY() > 1
                || !this.isSafeOpenShaftRimTarget(serverLevel, rim)) {
            return false;
        }
        for (int step = 1; step <= 6; step++) {
            BlockPos crossing = BlockPos.containing(feet.getX() + 0.5D + (rim.getX() - feet.getX()) * step / 6.0D,
                    feet.getY(), feet.getZ() + 0.5D + (rim.getZ() - feet.getZ()) * step / 6.0D);
            if (!serverLevel.hasChunkAt(crossing) || !this.hasOpenBodySpace(serverLevel, crossing)
                    || !serverLevel.getFluidState(crossing).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private BlockPos selectExplorationClimbStepOffTarget(List<BlockPos> candidates, BlockPos feet, BlockPos routeTarget) {
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> serverLevelCanSeeSkySafe(pos) ? 0 : 1)
                .thenComparingDouble((BlockPos pos) -> horizontalDistanceSqr(feet, pos))
                .thenComparingInt(pos -> Math.abs(feet.getY() - pos.getY()))
                .thenComparingDouble(pos -> routeTarget == null ? 0.0D : routeTarget.distSqr(pos)));
        return candidates.isEmpty() ? null : candidates.get(0).immutable();
    }

    private boolean serverLevelCanSeeSkySafe(BlockPos pos) {
        return this.playerNpc.level() instanceof ServerLevel serverLevel && serverLevel.canSeeSky(pos.above());
    }

    @Nullable
    private BlockPos findOpenExplorationClimbPushTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        List<Direction> directions = this.directionsAwayFrom(feet, this.pillarBasePos == null ? routeTarget : this.pillarBasePos);
        for (Direction direction : directions) {
            BlockPos adjacent = feet.relative(direction);
            if (serverLevel.isInWorldBounds(adjacent)
                    && serverLevel.getWorldBorder().isWithinBounds(adjacent)
                    && this.hasOpenBodySpace(serverLevel, adjacent)) {
                return feet.relative(direction, EXPLORATION_CLIMB_STEP_OFF_RADIUS).immutable();
            }
        }
        return null;
    }

    private List<Direction> directionsAwayFrom(BlockPos from, BlockPos awayFrom) {
        List<Direction> directions = new ArrayList<>();
        if (awayFrom != null) {
            for (Direction direction : this.directionsToward(from, awayFrom)) {
                Direction opposite = direction.getOpposite();
                if (!directions.contains(opposite)) {
                    directions.add(opposite);
                }
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!directions.contains(direction)) {
                directions.add(direction);
            }
        }
        return directions;
    }

    private boolean canStepOffToward(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        Direction direction = this.firstStepDirection(feet, target);
        if (direction == null) {
            return false;
        }
        BlockPos adjacent = feet.relative(direction);
        return serverLevel.isInWorldBounds(adjacent)
                && serverLevel.getWorldBorder().isWithinBounds(adjacent)
                && this.hasOpenBodySpace(serverLevel, adjacent);
    }

    @Nullable
    private Direction firstStepDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void tickExplorationClimbStepOff(ServerLevel serverLevel) {
        if (this.explorationClimbStepOffStartPos == null || this.explorationClimbStepOffTargetPos == null) {
            this.finished = true;
            this.clearExplorationClimbStepOff();
            return;
        }

        this.forceExplorationClimbStepOff();

        BlockPos feet = this.playerNpc.blockPosition();
        boolean movedHorizontally = feet.getX() != this.explorationClimbStepOffStartPos.getX()
                || feet.getZ() != this.explorationClimbStepOffStartPos.getZ();
        boolean movedAwayAndLanded = movedHorizontally
                && this.playerNpc.onGround()
                && !this.playerNpc.isTemporaryPillarSupport(feet.below())
                && (this.ownedPocketExitTarget == null
                || feet.distSqr(this.ownedPocketExitTarget) <= 1.0D);
        if (movedAwayAndLanded) {
            this.explorationClimbStepOffCompleted = true;
            this.playerNpc.clearUpwardEscapeTarget();
            this.finished = true;
            this.clearExplorationClimbStepOff();
            return;
        }

        this.explorationClimbStepOffTicks--;
        if (this.explorationClimbStepOffTicks <= 0) {
            BlockPos failedRoute = this.climbTargetPos == null
                    ? this.playerNpc.getUpwardEscapeTarget()
                    : this.climbTargetPos.immutable();
            this.abandonExplorationClimb(failedRoute, "could not land away from completed pillar");
            this.finished = true;
            this.clearExplorationClimbStepOff();
        }
    }

    private void forceExplorationClimbStepOff() {
        if (this.explorationClimbStepOffStartPos == null || this.explorationClimbStepOffTargetPos == null) {
            return;
        }

        double targetX = this.explorationClimbStepOffTargetPos.getX() + 0.5D;
        double targetZ = this.explorationClimbStepOffTargetPos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                targetX,
                this.explorationClimbStepOffTargetPos.getY(),
                targetZ,
                30.0F,
                30.0F
        );
        this.playerNpc.getMoveControl().setWantedPosition(
                targetX,
                this.explorationClimbStepOffTargetPos.getY(),
                targetZ,
                1.15D
        );
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setDeltaMovement(
                dx / length * EXPLORATION_CLIMB_STEP_OFF_SPEED,
                motion.y,
                dz / length * EXPLORATION_CLIMB_STEP_OFF_SPEED
        );
        this.playerNpc.hasImpulse = true;
        this.playerNpc.setCurrentAiDetail("exploration climb forced step off @ "
                + posText(this.explorationClimbStepOffStartPos)
                + " -> "
                + posText(this.explorationClimbStepOffTargetPos));
    }

    private void clearExplorationClimbStepOff() {
        this.explorationClimbStepOffStartPos = null;
        this.explorationClimbStepOffTargetPos = null;
        this.explorationClimbStepOffTicks = 0;
    }

    private void resetExplorationClimbStepOffWatch() {
        this.explorationClimbStepOffWatchPos = null;
        this.explorationClimbStepOffWatchTarget = null;
        this.explorationClimbStepOffWatchBase = null;
        this.explorationClimbStepOffWatchStartTick = 0;
    }

    private static String posText(@Nullable BlockPos pos) {
        return pos == null ? "none" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private boolean canPlacePillarWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!this.placingBlockAi.findBlockingPlacementEntities(serverLevel, pos, state).isEmpty()) {
            return false;
        }

        List<AABB> boxes = this.placingBlockAi.placementCollisionBoxes(serverLevel, pos, state);
        return boxes.stream().noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)));
    }

    private boolean tryAcceptOccupiedPillarSupport(ServerLevel serverLevel) {
        if (this.placePos == null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(this.placePos);
        if (state.canBeReplaced()
                || state.getCollisionShape(serverLevel, this.placePos).isEmpty()
                || !state.getFluidState().isEmpty()
                || serverLevel.getBlockEntity(this.placePos) != null) {
            return false;
        }

        if (!this.isStandingOnPillarSupport(serverLevel, this.placePos)) {
            return false;
        }

        BlockPos occupiedSupport = this.placePos.immutable();
        this.placedPillarSupports.add(occupiedSupport);
        this.pillarsPlaced++;
        this.failedPillarPlaceAttempts = 0;
        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.beginPillarSettlement(occupiedSupport);
        this.resetPillarStuckWatch();
        this.playerNpc.getNavigation().stop();
        this.updatePillarRecoveryDetail("settling on existing support");
        return true;
    }

    private boolean tryContinueRequestedRoutePillar(ServerLevel serverLevel) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.hasSatisfiedRequestedRoute(serverLevel, feet, requestedTarget)) {
            this.playerNpc.clearUpwardEscapeTarget();
            return false;
        }

        if (!this.playerNpc.onGround()) {
            return true;
        }

        PillarPlan pillarPlan = this.findPillarPlan(serverLevel, feet, requestedTarget);
        if (pillarPlan == null) {
            return false;
        }
        if (this.exceedsRequestedRouteMax(pillarPlan)) {
            this.playerNpc.clearUpwardEscapeTarget();
            return false;
        }

        this.climbTargetPos = requestedTarget.immutable();
        this.pillarBasePos = pillarPlan.basePos();
        this.pillarExitY = pillarPlan.exitY();
        this.requiredEscapeBlocks = Math.max(1, pillarPlan.blocksNeeded());

        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks < this.requiredEscapeBlocks && !this.isEmergencyRecoveryEpisode()) {
            EscapeMaterialTarget target = this.findEscapeMaterialTarget(serverLevel);
            if (target != null) {
                setEscapeMode(EscapeMode.GATHER_BLOCKS);
                this.minePos = target.targetPos();
                this.mineStandPos = target.standPos();
                this.pillarsPlaced = 0;
                this.maxPillarBlocks = 0;
                this.moveToMineTarget();
                return true;
            }
        }
        if (escapeBlocks <= 0) {
            return false;
        }

        this.maxPillarBlocks = Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, pillarPlan.blocksNeeded())));
        this.pillarsPlaced = 0;
        this.beginPillarStep(serverLevel);
        return true;
    }

    private void beginPillarStep(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.playerNpc.onGround()) {
            return;
        }
        if (this.lastConfirmedPillarSupportPos != null
                && !this.isStandingOnPillarSupport(serverLevel, this.lastConfirmedPillarSupportPos)) {
            if (this.isStablePillarSupport(serverLevel, this.lastConfirmedPillarSupportPos)) {
                this.beginPillarSettlement(this.lastConfirmedPillarSupportPos);
            } else {
                this.stopForMissingPillarSupport(this.lastConfirmedPillarSupportPos);
            }
            return;
        }
        BlockPos missingSupport = this.findMissingPlacedPillarSupport(serverLevel);
        if (missingSupport != null) {
            this.stopForMissingPillarSupport(missingSupport);
            return;
        }
        if (!this.isAtPillarBase()) {
            this.moveToPillarBase();
            return;
        }

        BlockPos obstruction = this.findPillarObstruction(serverLevel, feet);
        if (obstruction != null) {
            this.recoverOrStartPillarClearance(serverLevel, feet, obstruction);
            return;
        }

        if (!this.canPillarFrom(serverLevel, feet)) {
            PillarPlan newPlan = this.findPillarPlan(serverLevel, feet, this.climbTargetPos);
            if (newPlan == null) {
                this.finished = true;
                return;
            }
            if (this.exceedsRequestedRouteMax(newPlan)) {
                this.playerNpc.clearUpwardEscapeTarget();
                this.finished = true;
                return;
            }
            this.pillarBasePos = newPlan.basePos();
            this.pillarExitY = newPlan.exitY();
            this.maxPillarBlocks = Math.min(this.countEscapeBlocks(), Math.max(1, Math.min(this.requiredEscapeBlocks, newPlan.blocksNeeded())));
            this.moveToPillarBase();
            return;
        }

        if (!this.equipEscapeBlockForPlacement()) {
            this.finished = true;
            return;
        }

        this.placePos = feet.immutable();
        this.placeDelayTicks = PLACE_DELAY_TICKS;
        this.placeWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.placePos);
        this.playerNpc.shortPillarJump();
        this.updatePillarDetail();
    }

    private void tickPillarClearance(ServerLevel serverLevel) {
        if (this.pillarClearPos == null) {
            return;
        }

        BlockState state = serverLevel.getBlockState(this.pillarClearPos);
        if (!this.isClearablePillarObstruction(serverLevel, this.pillarClearPos, state)) {
            this.clearPillarClearance();
            this.beginPillarStep(serverLevel);
            return;
        }

        if (this.shouldSkipStalePillarClearTarget()) {
            this.clearPillarClearance();
            this.pillarBasePos = this.playerNpc.blockPosition().immutable();
            this.beginPillarStep(serverLevel);
            return;
        }

        if (!this.isAtPillarBase()) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.updatePillarClearDetail(state);
            boolean moving = this.moveToPillarBase();
            if (this.tickPillarClearReturnWatch(moving)) {
                this.replanAfterUnreachablePillarClear(serverLevel);
            }
            return;
        }

        this.resetPillarClearReturnWatch();
        this.restorePreviousPillarMainHand();
        this.playerNpc.getNavigation().stop();
        BlockPos clearPos = this.pillarClearPos;
        boolean clearingBed = state.getBlock() instanceof BedBlock;
        BlockPos bedCompanionPos = clearingBed
                ? this.findMatchingBedCompanion(serverLevel, clearPos, state)
                : null;
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                clearPos,
                clearState -> this.isClearablePillarObstruction(serverLevel, clearPos, clearState),
                this.getRequiredMineTicks(serverLevel, clearPos, state),
                String.format(
                        java.util.Locale.ROOT,
                        "pillaring %d/%d clearing %s",
                        this.pillarsPlaced,
                        this.maxPillarBlocks,
                        state.getBlock().getDescriptionId()
                ),
                clearingBed
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        this.toolAi.restoreMainHand();
        if (result == BreakingBlockAi.TickResult.DONE) {
            if (bedCompanionPos != null) {
                this.removeRemainingBedCompanion(serverLevel, state, bedCompanionPos);
            }
            this.pillarClearPos = null;
            this.mineTicks = 0;
            this.beginPillarStep(serverLevel);
            return;
        }

        this.clearPillarClearance();
        this.beginPillarStep(serverLevel);
    }

    private boolean shouldSkipStalePillarClearTarget() {
        if (this.pillarClearPos == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.pillarClearPos.getY() <= feet.getY()
                && this.pillarClearPos.getX() == feet.getX()
                && this.pillarClearPos.getZ() == feet.getZ()) {
            return !this.pillarClearPos.equals(feet);
        }

        return this.pillarBasePos != null
                && this.pillarClearPos.getX() == this.pillarBasePos.getX()
                && this.pillarClearPos.getZ() == this.pillarBasePos.getZ()
                && this.pillarClearPos.getY() <= this.pillarBasePos.getY();
    }

    private void clearPillarClearance() {
        this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.pillarClearPos = null;
        this.mineTicks = 0;
        this.restorePreviousPillarMainHand();
        this.resetPillarClearReturnWatch();
    }

    private void recoverOrStartPillarClearance(
            ServerLevel serverLevel,
            BlockPos feet,
            BlockPos obstruction
    ) {
        // A forced surface recovery is allowed to clear its own vertical corridor as a last
        // resort, but first move to a genuinely open adjacent column so an existing house floor
        // is not destroyed merely because the original base was one cell out of reach.
        if (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, obstruction)) {
            if (this.tryMoveToAlternatePillarBase(
                    serverLevel,
                    feet,
                    this.pillarBasePos,
                    true)) {
                return;
            }
            // A blueprint/home obstruction is never the generic pillar clear fallback. Move the
            // NPC to a safe non-footprint surface stand, then let the retained forced request
            // replan its pillar column. If no safe step-off exists, end this bounded episode and
            // retry later instead of destroying the protected structure or holding MOVE forever.
            boolean repositioning = this.protectedPillarPathStuckFallbackAi.start(
                    serverLevel,
                    this.climbTargetPos == null ? obstruction : this.climbTargetPos,
                    "protected pillar reposition",
                    pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
            );
            this.playerNpc.setCurrentAiDetail(
                    this.protectedPillarPathStuckFallbackAi.detail("protected pillar reposition"));
            if (repositioning) {
                this.placePos = null;
                this.pillarBasePos = null;
                this.pillarClearPos = null;
                this.placeDelayTicks = 0;
                this.placeWaitTicks = 0;
                this.resetPillarStuckWatch();
                this.resetForcedPillarBaseWatch();
                this.protectedPillarReplanPending = true;
            } else {
                this.finished = true;
            }
            return;
        }
        this.startPillarClearance(serverLevel, obstruction);
    }

    private void startPillarClearance(ServerLevel serverLevel, BlockPos obstruction) {
        if (this.pillarClearPos != null && !this.pillarClearPos.equals(obstruction)) {
            this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
        }

        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.pillarClearPos = obstruction.immutable();
        this.mineTicks = 0;
        this.resetPillarStuckWatch();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousPillarMainHand();
        this.playerNpc.getNavigation().stop();
        this.resetPillarClearReturnWatch();
        this.updatePillarClearDetail(serverLevel.getBlockState(this.pillarClearPos));
    }

    private boolean tickPillarClearReturnWatch(boolean moving) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.pillarClearReturnWatchPos == null
                || !this.pillarClearReturnWatchPos.equals(feet)) {
            this.pillarClearReturnWatchPos = feet.immutable();
            this.pillarClearReturnNoProgressTicks = 0;
            return false;
        }

        boolean failedPath = !moving
                || this.playerNpc.getNavigation().isDone()
                || this.playerNpc.getNavigation().isStuck();
        this.pillarClearReturnNoProgressTicks += failedPath
                ? PILLAR_CLEAR_RETURN_FAILED_PATH_WEIGHT
                : 1;
        return this.pillarClearReturnNoProgressTicks >= PILLAR_CLEAR_RETURN_NO_PROGRESS_TICKS;
    }

    private void replanAfterUnreachablePillarClear(ServerLevel serverLevel) {
        BlockPos abandonedBase = this.pillarBasePos == null ? null : this.pillarBasePos.immutable();
        BlockPos abandonedClear = this.pillarClearPos == null ? null : this.pillarClearPos.immutable();
        this.clearPillarClearance();
        this.playerNpc.getNavigation().stop();
        this.placePos = null;
        this.settlingPillarSupportPos = null;
        this.lastConfirmedPillarSupportPos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.pillarSettleTicks = 0;
        this.pillarSettleWaitTicks = 0;
        this.placedPillarSupports.clear();
        this.pillarsPlaced = 0;
        this.failedPillarPlaceAttempts = 0;
        this.resetPillarStuckWatch();
        this.resetExplorationClimbStepOffWatch();
        this.clearExplorationClimbStepOff();

        BlockPos feet = this.playerNpc.blockPosition();
        PillarPlan newPlan = this.findPillarPlan(serverLevel, feet, this.climbTargetPos);
        if (newPlan == null
                || (this.climbTargetPos != null && this.exceedsRequestedRouteMax(newPlan))) {
            this.finished = true;
            this.playerNpc.setIdleTraceDetail(
                    "pillar clear return failed; no replan from " + posText(feet)
                            + " oldBase=" + posText(abandonedBase)
                            + " oldClear=" + posText(abandonedClear),
                    60
            );
            return;
        }

        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks <= 0) {
            this.finished = true;
            this.playerNpc.setIdleTraceDetail("pillar clear return failed; no blocks @ " + posText(feet), 60);
            return;
        }

        this.pillarBasePos = newPlan.basePos();
        this.pillarExitY = newPlan.exitY();
        this.requiredEscapeBlocks = this.climbTargetPos == null
                ? 1
                : Math.max(1, newPlan.blocksNeeded());
        this.maxPillarBlocks = this.climbTargetPos == null
                ? 1
                : Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, newPlan.blocksNeeded())));
        this.updatePillarRecoveryDetail("replanned after unreachable clear base from "
                + posText(abandonedBase) + " to " + posText(this.pillarBasePos));
        this.beginPillarStep(serverLevel);
    }

    private void beginPillarSettlement(BlockPos supportPos) {
        this.settlingPillarSupportPos = supportPos.immutable();
        this.pillarSettleTicks = 0;
        this.pillarSettleWaitTicks = 0;
    }

    private void tickPillarSettlement(ServerLevel serverLevel) {
        BlockPos supportPos = this.settlingPillarSupportPos;
        if (supportPos == null) {
            return;
        }

        BlockPos missingSupport = this.findMissingPlacedPillarSupport(serverLevel);
        if (missingSupport != null) {
            this.stopForMissingPillarSupport(missingSupport);
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.lookDownAt(supportPos);
        this.pillarSettleWaitTicks++;
        if (!this.isStandingOnPillarSupport(serverLevel, supportPos)) {
            this.pillarSettleTicks = 0;
            if (this.playerNpc.onGround() && this.pillarSettleWaitTicks >= 8
                    && !this.playerNpc.blockPosition().below().equals(supportPos)
                    && this.tryRecoverDisplacedPillar(serverLevel, supportPos)) {
                return;
            }
            if (this.pillarSettleWaitTicks > MAX_PILLAR_SETTLE_WAIT_TICKS) {
                // The log showed an intact mossy-cobblestone support after lateral knockback.
                // Losing the landing is a displacement, not missing world support.
                if (this.pillarSettleWaitTicks <= 60 && this.displacedPillarRecoveries < 3) {
                    this.updatePillarRecoveryDetail("recovering displaced landing @ " + posText(supportPos));
                    return;
                }
                this.finished = true;
                this.playerNpc.setIdleTraceDetail("pillar stopped: displaced landing recovery exhausted @ "
                        + posText(supportPos), 60);
                return;
            }
            this.updatePillarRecoveryDetail("waiting to land on support @ " + posText(supportPos));
            return;
        }

        if (this.pillarSettleTicks <= 0) {
            this.pillarSettleTicks = PILLAR_SETTLE_TICKS;
        }
        this.pillarSettleTicks--;
        if (this.pillarSettleTicks > 0) {
            this.updatePillarRecoveryDetail("settling on support "
                    + (PILLAR_SETTLE_TICKS - this.pillarSettleTicks)
                    + "/" + PILLAR_SETTLE_TICKS);
            return;
        }

        this.lastConfirmedPillarSupportPos = supportPos.immutable();
        this.settlingPillarSupportPos = null;
        this.pillarSettleWaitTicks = 0;
        this.updatePillarDetail();
    }

    private boolean isStandingOnPillarSupport(ServerLevel serverLevel, BlockPos supportPos) {
        if (!this.playerNpc.onGround() || !this.isStablePillarSupport(serverLevel, supportPos)) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        if (!feet.below().equals(supportPos)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(supportPos);
        double supportTop = supportPos.getY() + state.getCollisionShape(serverLevel, supportPos).bounds().maxY;
        return Math.abs(this.playerNpc.getBoundingBox().minY - supportTop) <= 0.12D;
    }

    private boolean tryRecoverDisplacedPillar(ServerLevel serverLevel, BlockPos previousSupport) {
        if (this.displacedPillarRecoveries >= 3
                || this.playerNpc.tickCount < this.nextDisplacedPillarRecoveryTick) {
            return false;
        }
        this.nextDisplacedPillarRecoveryTick = this.playerNpc.tickCount + 20
                + this.playerNpc.getRandom().nextInt(6);
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.countEscapeBlocks() <= 0 || !this.canStandAt(serverLevel, feet)) {
            return false;
        }
        // Rebuild only the actual grounded column. Never snap the NPC back onto the old
        // support, suppress knockback, or clear unrelated sides to recover a missed landing.
        PillarPlan plan = this.createPillarPlan(serverLevel, feet, this.climbTargetPos);
        if (plan == null || this.exceedsRequestedRouteMax(plan)) {
            return false;
        }
        this.displacedPillarRecoveries++;
        this.settlingPillarSupportPos = null;
        this.lastConfirmedPillarSupportPos = null;
        this.pillarSettleTicks = 0;
        this.pillarSettleWaitTicks = 0;
        this.pillarsPlaced = 0;
        this.applyRecoveredPillarPlan(plan, "replanned after displacement from " + posText(previousSupport));
        this.beginPillarStep(serverLevel);
        return true;
    }

    private boolean isStablePillarSupport(ServerLevel serverLevel, BlockPos supportPos) {
        if (supportPos == null
                || !serverLevel.hasChunkAt(supportPos)
                || !serverLevel.isInWorldBounds(supportPos)
                || !serverLevel.getWorldBorder().isWithinBounds(supportPos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(supportPos);
        return !state.canBeReplaced()
                && !state.getCollisionShape(serverLevel, supportPos).isEmpty()
                && state.getFluidState().isEmpty();
    }

    private boolean hasSolidGravityPillarSupport(ServerLevel serverLevel, BlockPos supportPos) {
        return this.isStablePillarSupport(serverLevel, supportPos)
                && serverLevel.getBlockState(supportPos).isCollisionShapeFullBlock(serverLevel, supportPos);
    }

    @Nullable
    private BlockPos findMissingPlacedPillarSupport(ServerLevel serverLevel) {
        for (BlockPos supportPos : this.placedPillarSupports) {
            if (!this.isStablePillarSupport(serverLevel, supportPos)) {
                return supportPos.immutable();
            }
        }

        BlockPos cursor = this.playerNpc.blockPosition().below();
        int checked = 0;
        while (this.playerNpc.isTemporaryPillarSupport(cursor) && checked++ < MAX_ROUTE_ESCAPE_BLOCKS) {
            if (!this.isStablePillarSupport(serverLevel, cursor)) {
                return cursor.immutable();
            }
            cursor = cursor.below();
        }
        if (checked > 0 && !this.isStablePillarSupport(serverLevel, cursor)) {
            return cursor.immutable();
        }
        return null;
    }

    private void stopForMissingPillarSupport(BlockPos supportPos) {
        this.placePos = null;
        this.settlingPillarSupportPos = null;
        this.pillarSettleTicks = 0;
        this.pillarSettleWaitTicks = 0;
        this.finished = true;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setIdleTraceDetail("pillar stopped: missing solid support @ " + posText(supportPos), 60);
    }

    private boolean hasPillarPlacementClearance(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (pos == null || this.playerNpc.onGround()) {
            return false;
        }
        return this.placingBlockAi.placementCollisionBoxes(serverLevel, pos, state)
                .stream()
                .noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)));
    }

    private boolean shouldContinuePillaring(ServerLevel serverLevel) {
        if (this.pillarsPlaced >= this.maxPillarBlocks || this.countEscapeBlocks() <= 0) {
            return false;
        }

        if (this.climbTargetPos == null) {
            return this.pillarsPlaced == 0;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.heightDirectedCombatEpisode) {
            return !this.hasReachedCombatClimbHeight(serverLevel, feet);
        }
        if (this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
            return !this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
        }

        if (this.hasReachedOpenSky(serverLevel, feet) && !this.isActuallyTrapped(serverLevel, feet)) {
            return false;
        }

        return feet.getY() < this.climbTargetPos.getY() - 1
                && !this.hasStepExitToward(serverLevel, feet, this.climbTargetPos);
    }

    private boolean hasUnfinishedRequestedRoute(ServerLevel serverLevel) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        return requestedTarget != null
                && !this.hasSatisfiedRequestedRoute(serverLevel, this.playerNpc.blockPosition(), requestedTarget);
    }

    private boolean moveToMineTarget() {
        if (this.mineStandPos == null) {
            return false;
        }
        return this.moveToEscapeTarget(this.mineStandPos, 1.0D);
    }

    private boolean moveToRouteNavigationTarget() {
        if (this.routeNavigationTarget == null) {
            return false;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.routeNavigationTarget.getX() + 0.5D,
                this.routeNavigationTarget.getY() + 0.5D,
                this.routeNavigationTarget.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        return this.moveToEscapeTarget(this.routeNavigationTarget, 1.0D);
    }

    private boolean isAtPillarBase() {
        if (this.pillarBasePos == null) {
            return true;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        return feet.getY() >= this.pillarBasePos.getY()
                && feet.getX() == this.pillarBasePos.getX()
                && feet.getZ() == this.pillarBasePos.getZ()
                && this.playerNpc.distanceToSqr(
                this.pillarBasePos.getX() + 0.5D,
                feet.getY(),
                this.pillarBasePos.getZ() + 0.5D
        ) <= PILLAR_BASE_REACHED_SQR;
    }

    private boolean moveToPillarBase() {
        if (this.pillarBasePos == null) {
            return false;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.pillarBasePos.getX() + 0.5D,
                this.pillarBasePos.getY(),
                this.pillarBasePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        return this.moveToEscapeTarget(this.pillarBasePos, 1.0D);
    }

    private boolean moveToEscapeTarget(BlockPos target, double speed) {
        if (target == null) {
            return false;
        }
        if (!this.isEmergencyRecoveryEpisode()) {
            return this.playerNpc.getNavigation().moveTo(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speed
            );
        }
        if (!this.tryAcquireEmergencyRecoveryPathStart()) {
            return false;
        }
        Path path = PathNavigationAi.createBoundedPath(
                this.playerNpc, target, ESCAPE_DIAGNOSTIC_PATH_NODE_MULTIPLIER
        );
        return path != null && path.canReach() && this.playerNpc.getNavigation().moveTo(path, speed);
    }

    private PillarPlan findPillarPlan(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (this.combatRecoveryEpisode) {
            // Combat escape is intentionally local and deterministic. Starting from a retained
            // cursor could select a neighboring wall cell, after which recovery tried to mine the
            // hole instead of placing below the NPC.
            this.pillarPlanCandidateCursor = 0;
            return this.createPillarPlan(serverLevel, feet, routeTarget);
        }

        // Offset order is immutable and cached. canUse() now allocates no 11x4x11 candidate list
        // and performs no sort; it still inspects only two retained candidates per activation.
        int start = Math.floorMod(this.pillarPlanCandidateCursor, PILLAR_PLAN_OFFSETS.size());
        int inspected = 0;
        for (int offset = 0; offset < PILLAR_PLAN_OFFSETS.size()
                && inspected < MAX_PILLAR_PLAN_CANDIDATES; offset++, inspected++) {
            BlockPos base = feet.offset(PILLAR_PLAN_OFFSETS.get((start + offset) % PILLAR_PLAN_OFFSETS.size()));
            PillarPlan plan = this.createPillarPlan(serverLevel, base, routeTarget);
            if (plan != null) {
                this.pillarPlanCandidateCursor = 0;
                return plan;
            }
        }
        this.pillarPlanCandidateCursor = (start + inspected) % PILLAR_PLAN_OFFSETS.size();

        return null;
    }

    private static List<BlockPos> createPillarPlanOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -PILLAR_SEARCH_RADIUS; dx <= PILLAR_SEARCH_RADIUS; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -PILLAR_SEARCH_RADIUS; dz <= PILLAR_SEARCH_RADIUS; dz++) {
                    offsets.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        offsets.sort(Comparator
                .comparingDouble((BlockPos offset) -> offset.distSqr(BlockPos.ZERO))
                .thenComparingInt(offset -> Math.abs(offset.getY()))
                .thenComparingInt(BlockPos::getY));
        return List.copyOf(offsets);
    }

    private PillarPlan createPillarPlan(ServerLevel serverLevel, BlockPos base, BlockPos routeTarget) {
        if (!serverLevel.hasChunkAt(base)) {
            return null;
        }
        BlockState baseState = serverLevel.getBlockState(base);
        if (!this.canStandAt(serverLevel, base) || !this.canUsePillarBaseState(serverLevel, base, baseState)) {
            return null;
        }
        if (routeTarget == null) {
            return this.hasOpenOrClearableBodySpace(serverLevel, base.above())
                    ? new PillarPlan(base, base.getY() + 1, 1)
                    : null;
        }

        boolean forcedRequestedRoute = this.isForcedRequestedRoute(routeTarget);
        int minimumRouteY = forcedRequestedRoute
                ? this.minimumForcedRouteFeetY(routeTarget)
                : routeTarget.getY() - 1;
        boolean exactTerraformHeight = forcedRequestedRoute
                && this.playerNpc.isTerraformSupportUpwardEscapeRequested();
        boolean requestedSurfaceRoute = !forcedRequestedRoute
                && this.isRequestedSurfaceRoute(serverLevel, this.playerNpc.blockPosition(), routeTarget);
        int requestedSurfaceY = requestedSurfaceRoute ? this.nearbySurfaceY(serverLevel, routeTarget) : base.getY() + 1;
        int requestedRouteMax = this.getRequestedRouteMaxPillarBlocks();
        int scanBlocks = requestedRouteMax > 0
                ? Math.min(PILLAR_SURFACE_SCAN_UP, requestedRouteMax)
                : PILLAR_SURFACE_SCAN_UP;
        int scanTop = Math.min(serverLevel.getMaxBuildHeight() - 3, base.getY() + scanBlocks);
        for (int y = base.getY(); y <= scanTop; y++) {
            BlockPos feetAtY = new BlockPos(base.getX(), y, base.getZ());
            if (!serverLevel.hasChunkAt(feetAtY)) {
                return null;
            }
            if (!this.hasOpenOrClearableBodySpace(serverLevel, feetAtY)) {
                return null;
            }

            boolean reachesRoute = !requestedSurfaceRoute
                    && routeTarget != null
                    && y >= minimumRouteY
                    && (forcedRequestedRoute || this.hasStepExitToward(serverLevel, feetAtY, routeTarget));
            boolean reachesSurface = !this.heightDirectedCombatEpisode
                    && serverLevel.canSeeSky(feetAtY.above())
                    && (requestedSurfaceRoute
                    ? y >= requestedSurfaceY
                    : y >= (exactTerraformHeight
                    ? minimumRouteY
                    : Math.min(minimumRouteY, base.getY() + MIN_ROUTE_ESCAPE_BLOCKS)));
            if (reachesRoute || reachesSurface) {
                int blocksNeeded = Math.max(1, y - base.getY());
                if (blocksNeeded <= MAX_ROUTE_ESCAPE_BLOCKS || routeTarget == null) {
                    return new PillarPlan(base, y, blocksNeeded);
                }
                return null;
            }
        }

        return null;
    }

    private boolean canPillarFrom(ServerLevel serverLevel, BlockPos feet) {
        if (!serverLevel.getBlockState(feet).canBeReplaced() || !this.hasOpenBodySpace(serverLevel, feet)) {
            return false;
        }

        int topY = this.pillarExitY > feet.getY()
                ? Math.min(this.pillarExitY, feet.getY() + 1)
                : feet.getY() + 1;
        for (int y = feet.getY(); y <= topY; y++) {
            if (!this.hasOpenBodySpace(serverLevel, new BlockPos(feet.getX(), y, feet.getZ()))) {
                return false;
            }
        }
        return true;
    }

    private boolean canUsePillarBaseState(ServerLevel serverLevel, BlockPos base, BlockState state) {
        return !FarmAi.isProtectedFarmBlock(this.playerNpc, base)
                && (state.canBeReplaced()
                || this.isClearablePillarObstruction(serverLevel, base, state));
    }

    private BlockPos findPillarObstruction(ServerLevel serverLevel, BlockPos feet) {
        int topY = this.pillarExitY > feet.getY()
                ? Math.min(this.pillarExitY, feet.getY() + 1)
                : feet.getY() + 1;
        for (int y = feet.getY(); y <= topY + 1; y++) {
            BlockPos pos = new BlockPos(feet.getX(), y, feet.getZ());
            BlockState state = serverLevel.getBlockState(pos);
            if (this.isClearablePillarObstruction(serverLevel, pos, state)) {
                return pos.immutable();
            }
            if (this.hasBlockingCollision(serverLevel, pos)) {
                return null;
            }
        }
        return null;
    }

    private BlockPos findCurrentPillarCollisionBlocker(ServerLevel serverLevel) {
        return this.findClearableCollisionBlocker(
                serverLevel,
                this.playerNpc.getBoundingBox(),
                PILLAR_COLLISION_BLOCKER_PADDING,
                PILLAR_COLLISION_TOP_PADDING
        );
    }

    private BlockPos findCenteredPillarCollisionBlocker(ServerLevel serverLevel) {
        if (this.placePos == null) {
            return null;
        }

        double targetX = this.placePos.getX() + 0.5D;
        double targetZ = this.placePos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        if (Math.abs(dx) <= PILLAR_CENTER_EPSILON && Math.abs(dz) <= PILLAR_CENTER_EPSILON) {
            return null;
        }

        return this.findClearableCollisionBlocker(
                serverLevel,
                this.playerNpc.getBoundingBox().move(dx, 0.0D, dz),
                PILLAR_CENTER_BLOCKER_PADDING,
                PILLAR_COLLISION_TOP_PADDING
        );
    }

    private BlockPos findClearableCollisionBlocker(ServerLevel serverLevel, AABB box, double horizontalPadding, double topPadding) {
        AABB checkBox = new AABB(
                box.minX - horizontalPadding,
                box.minY + 0.05D,
                box.minZ - horizontalPadding,
                box.maxX + horizontalPadding,
                box.maxY + topPadding,
                box.maxZ + horizontalPadding
        );

        int minX = Mth.floor(checkBox.minX);
        int minY = Mth.floor(checkBox.minY);
        int minZ = Mth.floor(checkBox.minZ);
        int maxX = Mth.floor(checkBox.maxX);
        int maxY = Mth.floor(checkBox.maxY);
        int maxZ = Mth.floor(checkBox.maxZ);
        for (BlockPos mutable : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockPos pos = mutable.immutable();
            BlockState state = serverLevel.getBlockState(pos);
            if (!this.isClearablePillarObstruction(serverLevel, pos, state)) {
                continue;
            }
            for (AABB collisionBox : state.getCollisionShape(serverLevel, pos).toAabbs()) {
                if (collisionBox.move(pos).intersects(checkBox)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private BlockPos findExitClearTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        BlockPos currentColumnTarget = this.findCurrentColumnClearTarget(serverLevel, feet);
        if (currentColumnTarget != null) {
            return currentColumnTarget;
        }
        if (routeTarget != null && !this.isActuallyTrapped(serverLevel, feet)) {
            return null;
        }

        List<Direction> directions = routeTarget == null ? new ArrayList<>() : this.directionsToward(feet, routeTarget);
        if (routeTarget == null) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                directions.add(direction);
            }
        }

        for (Direction direction : directions) {
            BlockPos adjacentFeet = feet.relative(direction);
            if (!serverLevel.isInWorldBounds(adjacentFeet)
                    || !serverLevel.getWorldBorder().isWithinBounds(adjacentFeet)
                    || !this.isWalkableFloor(serverLevel, adjacentFeet.below())
                    || this.hasOpenBodySpace(serverLevel, adjacentFeet)) {
                continue;
            }

            BlockPos lower = adjacentFeet.immutable();
            BlockState lowerState = serverLevel.getBlockState(lower);
            if (this.hasBlockingCollision(serverLevel, lower)) {
                if (this.isClearableExitObstruction(serverLevel, lower, lowerState)) {
                    return lower;
                }
                continue;
            }

            BlockPos upper = adjacentFeet.above();
            BlockState upperState = serverLevel.getBlockState(upper);
            if (this.hasBlockingCollision(serverLevel, upper)
                    && this.isClearableExitObstruction(serverLevel, upper, upperState)) {
                return upper.immutable();
            }
        }

        return null;
    }

    private boolean tryStartExplorationClimbClear(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget, PillarPlan pillarPlan) {
        if (routeTarget == null) {
            return false;
        }

        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                feet,
                pillarPlan == null ? routeTarget : pillarPlan.basePos(),
                routeTarget
        ));
        this.addRequestedRouteClearCandidates(candidates, feet, routeTarget, pillarPlan);

        List<BlockPos> ordered = candidates.stream()
                .filter(pos -> pos != null
                        && !pos.equals(feet.below())
                        && !this.playerNpc.isTemporaryPillarSupport(pos))
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> this.isClearableExplorationClimbObstruction(serverLevel, pos, serverLevel.getBlockState(pos)))
                .sorted(Comparator
                        .comparingDouble(feet::distSqr)
                        .thenComparingDouble(pos -> routeTarget.distSqr(pos)))
                .toList();
        Optional<BlockPos> clearTarget = ClearBlockAi.findNearestAccessibleClearable(
                serverLevel,
                this.playerNpc,
                ordered,
                this::isExplorationClimbObstacleState,
                EXPLORATION_CLIMB_CLEAR_DISTANCE_SQR,
                false
        );
        if (clearTarget.isEmpty()) {
            return false;
        }

        BlockPos target = clearTarget.get();
        boolean started = this.clearBlockAi.start(
                serverLevel,
                target,
                state -> this.isClearableExplorationClimbObstruction(serverLevel, target, state),
                "clearing exploration climb path",
                EXPLORATION_CLIMB_CLEAR_TICKS,
                EXPLORATION_CLIMB_CLEAR_DISTANCE_SQR,
                true
        );
        if (!started) {
            return false;
        }

        this.exitClearPos = null;
        this.pillarClearPos = null;
        this.routeNavigationTarget = null;
        this.mineTicks = 0;
        this.playerNpc.requestExplorationUpwardEscapeTo(
                routeTarget,
                EXPLORATION_CLIMB_CLEAR_REQUEST_TICKS,
                this.getRequestedRouteMaxPillarBlocks()
        );
        this.retainTeamFollowerRecoveryProvenance();
        setEscapeMode(EscapeMode.CLEAR_ROUTE);
        return true;
    }

    private void addRequestedRouteClearCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos routeTarget, PillarPlan pillarPlan) {
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> initialCandidates = new ArrayList<>(candidates);
        candidates.clear();
        for (BlockPos candidate : initialCandidates) {
            this.addRouteClearCandidate(candidates, seen, candidate);
        }

        this.addRouteClearCandidate(candidates, seen, feet);
        this.addRouteClearCandidate(candidates, seen, feet.above());
        this.addRouteClearCandidate(candidates, seen, feet.above(2));

        for (Direction direction : this.directionsToward(feet, routeTarget)) {
            BlockPos adjacentFeet = feet.relative(direction);
            this.addRouteClearCandidate(candidates, seen, adjacentFeet);
            this.addRouteClearCandidate(candidates, seen, adjacentFeet.above());
            this.addRouteClearCandidate(candidates, seen, adjacentFeet.above(2));

            BlockPos nextFeet = adjacentFeet.relative(direction);
            this.addRouteClearCandidate(candidates, seen, nextFeet);
            this.addRouteClearCandidate(candidates, seen, nextFeet.above());
            this.addRouteClearCandidate(candidates, seen, nextFeet.above(2));
        }

        if (pillarPlan != null) {
            int topY = Math.min(pillarPlan.exitY() + 1, feet.getY() + Math.max(2, this.getRequestedRouteMaxPillarBlocks()) + 2);
            for (int y = pillarPlan.basePos().getY(); y <= topY; y++) {
                this.addRouteClearCandidate(candidates, seen, new BlockPos(pillarPlan.basePos().getX(), y, pillarPlan.basePos().getZ()));
            }
        }
    }

    private void addRouteClearCandidate(List<BlockPos> candidates, Set<BlockPos> seen, BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos immutable = pos.immutable();
        if (seen.add(immutable)) {
            candidates.add(immutable);
        }
    }

    private boolean isClearableExplorationClimbObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return pos != null
                && !pos.equals(this.playerNpc.blockPosition().below())
                && !this.playerNpc.isTemporaryPillarSupport(pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private boolean isExplorationClimbObstacleState(BlockState state) {
        return state != null && !state.isAir();
    }

    private BlockPos findCurrentColumnClearTarget(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        if (this.blocksPillarSpace(serverLevel, feet, feetState)
                && this.isClearableExitObstruction(serverLevel, feet, feetState)) {
            return feet.immutable();
        }

        BlockPos head = feet.above();
        BlockState headState = serverLevel.getBlockState(head);
        if (this.blocksPillarSpace(serverLevel, head, headState)
                && this.isClearableExitObstruction(serverLevel, head, headState)) {
            return head.immutable();
        }

        return null;
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

    private EscapeMaterialTarget findEscapeMaterialTarget(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();

        // This used to inspect the entire 11x6x11 volume, allocate every valid target/stand pair,
        // sort the whole result, and only then choose the nearest block. Runtime warnings measured
        // 158-185 ms in this exact gather-block phase. Iterate a cached nearest-first offset list
        // and stop at the first usable target; the selected result is identical to the old primary
        // distance ordering without paying for farther candidates.
        for (BlockPos offset : ESCAPE_MATERIAL_OFFSETS) {
            BlockPos immutable = center.offset(offset).immutable();
            if (immutable.equals(center.below())
                    || !serverLevel.hasChunkAt(immutable)
                    || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, immutable)
                    || !this.canGatherEscapeMaterial(serverLevel.getBlockState(immutable))) {
                continue;
            }

            BlockPos stand = this.findStandPos(serverLevel, immutable);
            if (stand != null) {
                return new EscapeMaterialTarget(immutable, stand);
            }
        }
        return null;
    }

    private static List<BlockPos> createEscapeMaterialOffsets() {
        List<BlockPos> offsets = new ArrayList<>((SEARCH_RADIUS * 2 + 1) * 6 * (SEARCH_RADIUS * 2 + 1));
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    offsets.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        offsets.sort(Comparator
                .comparingDouble((BlockPos offset) -> offset.distSqr(BlockPos.ZERO))
                .thenComparingInt(offset -> Math.abs(offset.getY()))
                .thenComparingInt(BlockPos::getY));
        return List.copyOf(offsets);
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        candidates.add(target.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).above());
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canStandAt(serverLevel, immutable)
                    && this.isWithinMiningReachFromStand(immutable, target)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean isWithinMiningReachFromStand(BlockPos stand, BlockPos target) {
        // Navigation targets the centre of the stand block while block breaking measures from the
        // entity's feet to the target block centre. Use that same geometry here; BlockPos-to-
        // BlockPos distance plus slack can accept a stand that navigation reaches successfully but
        // from which the target is still physically outside BREAK_DISTANCE_SQR.
        double dx = stand.getX() + 0.5D - (target.getX() + 0.5D);
        double dy = stand.getY() - (target.getY() + 0.5D);
        double dz = stand.getZ() + 0.5D - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= BREAK_DISTANCE_SQR;
    }

    private BlockPos findReachableRouteNavigationTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        List<BlockPos> candidates = new ArrayList<>();
        this.addRouteNavigationColumn(serverLevel, candidates, feet, feet, routeTarget);
        if (routeTarget != null) {
            this.addRouteNavigationColumn(serverLevel, candidates, routeTarget, feet, routeTarget);
            for (Direction direction : this.directionsToward(feet, routeTarget)) {
                for (int distance = ROUTE_NAV_STEP; distance <= ROUTE_NAV_HORIZONTAL_RADIUS; distance += ROUTE_NAV_STEP) {
                    this.addRouteNavigationColumn(
                            serverLevel,
                            candidates,
                            feet.relative(direction, distance),
                            feet,
                            routeTarget
                    );
                }
            }
        }

        for (int dx = -ROUTE_NAV_HORIZONTAL_RADIUS; dx <= ROUTE_NAV_HORIZONTAL_RADIUS; dx += ROUTE_NAV_STEP) {
            for (int dz = -ROUTE_NAV_HORIZONTAL_RADIUS; dz <= ROUTE_NAV_HORIZONTAL_RADIUS; dz += ROUTE_NAV_STEP) {
                if (Math.abs(dx) + Math.abs(dz) < ROUTE_NAV_STEP) {
                    continue;
                }
                this.addRouteNavigationColumn(serverLevel, candidates, feet.offset(dx, 0, dz), feet, routeTarget);
            }
        }

        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> serverLevel.canSeeSky(pos.above()) ? 0 : 1)
                .thenComparingInt(pos -> -pos.getY())
                .thenComparingDouble(pos -> routeTarget == null ? feet.distSqr(pos) : routeTarget.distSqr(pos))
                .thenComparingDouble(feet::distSqr));

        Set<BlockPos> seen = new HashSet<>();
        int checked = 0;
        for (BlockPos candidate : candidates) {
            if (!seen.add(candidate) || candidate.distSqr(feet) <= ROUTE_NAV_REACHED_SQR) {
                continue;
            }
            Path path = this.createBoundedDiagnosticPath(candidate);
            checked++;
            if (path != null && path.canReach()) {
                return candidate;
            }
            if (checked >= ROUTE_NAV_MAX_PATH_CHECKS) {
                break;
            }
        }
        return null;
    }

    private void addRouteNavigationColumn(ServerLevel serverLevel, List<BlockPos> candidates, BlockPos column, BlockPos feet, BlockPos routeTarget) {
        int minY = Math.max(serverLevel.getMinBuildHeight() + 1, feet.getY() - ROUTE_NAV_VERTICAL_DOWN);
        int maxY = Math.min(serverLevel.getMaxBuildHeight() - 2, feet.getY() + ROUTE_NAV_VERTICAL_UP);

        for (int y = maxY; y >= minY; y--) {
            BlockPos candidate = new BlockPos(column.getX(), y, column.getZ());
            if (this.isRouteNavigationCandidate(serverLevel, feet, candidate)) {
                candidates.add(candidate.immutable());
                return;
            }
        }
    }

    private boolean isRouteNavigationCandidate(ServerLevel serverLevel, BlockPos feet, BlockPos candidate) {
        if (!serverLevel.isInWorldBounds(candidate)
                || !serverLevel.getWorldBorder().isWithinBounds(candidate)
                || !this.canStandAt(serverLevel, candidate)) {
            return false;
        }

        return candidate.getY() >= feet.getY() + ROUTE_NAV_MIN_UPWARD_GAIN
                || serverLevel.canSeeSky(candidate.above());
    }

    private boolean isRouteNavigationComplete(ServerLevel serverLevel, BlockPos feet) {
        if (this.hasReachedOpenSky(serverLevel, feet) && !this.isActuallyTrapped(serverLevel, feet)) {
            if (this.climbTargetPos != null && this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
                return this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
            }
            return true;
        }

        if (this.climbTargetPos != null && this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
            return this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
        }

        return this.climbTargetPos != null
                && (feet.getY() >= this.climbTargetPos.getY() - 1
                || this.hasStepExitToward(serverLevel, feet, this.climbTargetPos));
    }

    private boolean routeNeedsClimbWithoutPathProbe(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (routeTarget == null) {
            return false;
        }
        if (this.isSameOrNearbyRouteBlock(feet, routeTarget)) {
            return false;
        }

        boolean targetIsHigher = routeTarget.getY() > feet.getY() + 2;
        boolean requestedSurfaceRoute = this.isRequestedSurfaceRoute(serverLevel, feet, routeTarget);
        if (!targetIsHigher && !requestedSurfaceRoute) {
            return false;
        }

        if (targetIsHigher && this.hasStepExitToward(serverLevel, feet, routeTarget)) {
            return false;
        }

        if (requestedSurfaceRoute && !targetIsHigher) {
            return this.playerNpc.getNavigation().isStuck()
                    || this.playerNpc.getNavigation().isDone();
        }

        if (this.hasStepExitToward(serverLevel, feet, routeTarget)) {
            return false;
        }

        return this.playerNpc.getNavigation().isStuck()
                || this.playerNpc.getNavigation().isDone()
                || this.hasTallWallToward(serverLevel, feet, routeTarget);
    }

    private BlockPos getUpwardRouteTarget(ServerLevel serverLevel, BlockPos feet) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget != null && this.isUsableUpwardRouteTarget(serverLevel, feet, requestedTarget)) {
            return requestedTarget.immutable();
        }
        return null;
    }

    /**
     * Pillaring is a last-resort underground-to-surface recovery. Requiring sky occlusion in the
     * current cell and every cardinal neighbor rejects exposed cliff faces, mountain slopes and
     * shallow overhangs that normal navigation can walk around. The destination itself must be a
     * loaded open-sky surface cell; no path or broad world scan is performed from canUse().
     */
    @Nullable
    private String undergroundSurfaceRecoveryRejection(
            ServerLevel serverLevel,
            BlockPos feet,
            BlockPos routeTarget
    ) {
        if (routeTarget.getY() <= feet.getY() + 1) {
            return "target is not above origin";
        }
        if (!serverLevel.hasChunkAt(routeTarget)) {
            return "target chunk is not loaded";
        }
        if (!serverLevel.canSeeSky(routeTarget.above())) {
            return "target is not an exterior surface";
        }
        if (this.playerNpc.isTemporaryPillarSupport(feet.below())) {
            return "origin is already on a temporary pillar";
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos adjacent = feet.relative(direction).offset(0, dy, 0);
                if (!serverLevel.hasChunkAt(adjacent)) {
                    return "adjacent origin chunk is not loaded";
                }
                if (serverLevel.canSeeSky(adjacent.above()) && this.canStandAt(serverLevel, adjacent)) {
                    // This is a real walkable exterior beside the NPC, unlike an open vertical
                    // shaft whose sky-visible neighboring columns are solid walls. Only the real
                    // surface-origin case is rejected as a hill/cliff navigation problem.
                    return "surface-origin request has a walkable exposed side";
                }
            }
        }
        return null;
    }

    /** Bounds speculative route/reachability probes without changing normal navigation. */
    private Path createBoundedDiagnosticPath(BlockPos target) {
        if (!this.tryAcquireEmergencyRecoveryPathStart()) {
            return null;
        }
        return PathNavigationAi.createBoundedPath(
                this.playerNpc,
                target,
                ESCAPE_DIAGNOSTIC_PATH_NODE_MULTIPLIER
        );
    }

    private double horizontalDistanceSqr(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz;
    }

    private boolean isUsableUpwardRouteTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (this.isSameOrNearbyRouteBlock(feet, routeTarget)) {
            return false;
        }
        return routeTarget.getY() > feet.getY() + 2
                || this.isRequestedSurfaceRoute(serverLevel, feet, routeTarget);
    }

    private boolean isRequestedSurfaceRoute(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        return requestedTarget != null
                && requestedTarget.equals(routeTarget);
    }

    @Nullable
    private BlockPos findTrappedCaveSurfaceTarget(ServerLevel serverLevel, BlockPos feet) {
        if (!serverLevel.hasChunkAt(feet) || serverLevel.canSeeSky(feet.above())) {
            return null;
        }

        // MOTION_BLOCKING_NO_LEAVES is a chunk-local heightmap lookup. It gives the first feet
        // cell above the solid roof without a path search or a vertical world scan.
        int surfaceY = serverLevel.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                feet.getX(),
                feet.getZ()
        );
        int gain = surfaceY - feet.getY();
        if (gain < TRAPPED_CAVE_MIN_SURFACE_GAIN || gain > MAX_ROUTE_ESCAPE_BLOCKS) {
            return null;
        }

        BlockPos surfaceFeet = new BlockPos(feet.getX(), surfaceY, feet.getZ());
        BlockPos roof = surfaceFeet.below();
        if (!serverLevel.hasChunkAt(surfaceFeet)
                || !serverLevel.canSeeSky(surfaceFeet.above())
                || !this.canStandAt(serverLevel, surfaceFeet)
                || !this.isClearablePillarObstruction(serverLevel, roof, serverLevel.getBlockState(roof))) {
            return null;
        }
        return surfaceFeet;
    }

    private boolean hasReachedOpenSky(ServerLevel serverLevel, BlockPos feet) {
        return this.hasOpenBodySpace(serverLevel, feet)
                && serverLevel.canSeeSky(feet.above());
    }

    private boolean shouldStopOpenSkyRouteClimb(ServerLevel serverLevel, BlockPos feet) {
        if (this.heightDirectedCombatEpisode) {
            return this.hasReachedCombatClimbHeight(serverLevel, feet);
        }
        if (!this.hasReachedOpenSky(serverLevel, feet) || this.isActuallyTrapped(serverLevel, feet)) {
            return false;
        }
        if (this.climbTargetPos != null && this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
            return this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
        }
        return true;
    }

    private boolean hasReachedRequestedRoute(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (this.heightDirectedCombatEpisode && routeTarget != null && routeTarget.equals(this.climbTargetPos)) {
            return this.hasReachedCombatClimbHeight(serverLevel, feet);
        }
        if (this.isForcedRequestedRoute(routeTarget)) {
            return this.hasReachedForcedRequestedRoute(serverLevel, feet, routeTarget);
        }
        return this.hasReachedRequestedSurfaceExit(serverLevel, feet, routeTarget);
    }

    private boolean hasReachedCombatClimbHeight(ServerLevel serverLevel, BlockPos feet) {
        // Pursuit/obstruction clearing resumes from this safe height. Combat does not need
        // a surface exit, visible sky, or a step onto the enemy's occupied block to finish.
        return this.climbTargetPos != null && feet.getY() >= this.climbTargetPos.getY() - 1
                && this.playerNpc.onGround() && this.canStandAt(serverLevel, feet);
    }

    private boolean hasReachedForcedRequestedRoute(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (feet.getY() < this.minimumForcedRouteFeetY(routeTarget)
                || !this.playerNpc.onGround()
                || !this.canStandAt(serverLevel, feet)) {
            return false;
        }

        return this.isSameOrNearbyRouteBlock(feet, routeTarget)
                || this.hasStepExitToward(serverLevel, feet, routeTarget)
                || this.hasReachedOpenSky(serverLevel, feet) && !this.isActuallyTrapped(serverLevel, feet);
    }

    private int minimumForcedRouteFeetY(BlockPos routeTarget) {
        // Terraform needs the body above the support it is about to fill. The normal route's
        // one-block arrival tolerance leaves the builder inside that work level, so the resumed
        // fill asks for another climb and generic descent can undo the previous attempt.
        return routeTarget.getY() - (this.playerNpc.isTerraformSupportUpwardEscapeRequested() ? 0 : 1);
    }

    private boolean hasReachedRequestedSurfaceExit(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        int requestedSurfaceY = this.nearbySurfaceY(serverLevel, routeTarget);
        if (routeTarget != null && routeTarget.equals(this.ownedPocketExitTarget)) {
            return feet.getY() >= requestedSurfaceY;
        }
        if (feet.getY() < requestedSurfaceY - 1) {
            return false;
        }
        return feet.getY() >= requestedSurfaceY
                || this.hasStepExitToward(serverLevel, feet, routeTarget);
    }

    private boolean hasSatisfiedRequestedRoute(ServerLevel serverLevel, BlockPos feet, BlockPos requestedTarget) {
        if (this.isForcedRequestedRoute(requestedTarget)) {
            return this.hasReachedRequestedRoute(serverLevel, feet, requestedTarget);
        }
        if (this.playerNpc.isExplorationUpwardEscapeRequested()) {
            return this.hasReachedRequestedSurfaceExit(serverLevel, feet, requestedTarget)
                    && this.hasReachedOpenSky(serverLevel, feet)
                    && (!requestedTarget.equals(this.ownedPocketExitTarget)
                    || !this.playerNpc.isTemporaryPillarSupport(feet.below())
                    && feet.distSqr(requestedTarget) <= 1.0D)
                    && !this.isActuallyTrapped(serverLevel, feet);
        }
        if (requestedTarget.getY() > feet.getY() + 1) {
            return this.hasReachedRequestedSurfaceExit(serverLevel, feet, requestedTarget);
        }
        return this.isSameOrNearbyRouteBlock(feet, requestedTarget)
                || this.hasReachedRequestedSurfaceExit(serverLevel, feet, requestedTarget);
    }

    private boolean isSameOrNearbyRouteBlock(BlockPos feet, BlockPos routeTarget) {
        return routeTarget != null
                && Math.abs(routeTarget.getY() - feet.getY()) <= 1
                && routeTarget.distSqr(feet) <= ROUTE_NAV_REACHED_SQR;
    }

    private boolean isForcedRequestedRoute(BlockPos routeTarget) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        return routeTarget != null
                && requestedTarget != null
                && requestedTarget.equals(routeTarget)
                && this.playerNpc.isForcedUpwardEscape();
    }

    private int nearbySurfaceY(ServerLevel serverLevel, BlockPos center) {
        int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        return Math.max(center.getY(), surfaceY);
    }

    private boolean hasTallWallToward(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        int checked = 0;
        for (Direction direction : this.directionsToward(feet, target)) {
            if (this.hasTwoBlockBarrier(serverLevel, feet, direction)) {
                return true;
            }
            checked++;
            if (checked >= 2) {
                break;
            }
        }
        return false;
    }

    private boolean hasStepExitToward(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        int checked = 0;
        for (Direction direction : this.directionsToward(feet, target)) {
            BlockPos adjacentFeet = feet.relative(direction);
            if (this.hasOpenBodySpace(serverLevel, adjacentFeet)
                    && this.isWalkableFloor(serverLevel, adjacentFeet.below())) {
                return true;
            }

            BlockPos stepUpFeet = adjacentFeet.above();
            if (this.hasOpenBodySpace(serverLevel, stepUpFeet)
                    && this.isWalkableFloor(serverLevel, adjacentFeet)) {
                return true;
            }

            checked++;
            if (checked >= 2) {
                break;
            }
        }
        return false;
    }

    private List<Direction> directionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            directions.add(dx > 0 ? Direction.EAST : Direction.WEST);
        } else if (dz != 0) {
            directions.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }

        if (dx != 0) {
            Direction xDirection = dx > 0 ? Direction.EAST : Direction.WEST;
            if (!directions.contains(xDirection)) {
                directions.add(xDirection);
            }
        }
        if (dz != 0) {
            Direction zDirection = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            if (!directions.contains(zDirection)) {
                directions.add(zDirection);
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!directions.contains(direction)) {
                directions.add(direction);
            }
        }
        return directions;
    }

    private boolean isEscapeBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || stack.is(ItemTags.LOGS)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && !state.canBeReplaced()
                && (this.isDirtPillarBlock(stack)
                        || this.isGravityPillarBlock(stack)
                        || this.isUsablePlankPillarBlock(stack)
                        || this.isStonePillarBlock(state));
    }

    private boolean isDirtPillarBlock(ItemStack stack) {
        return stack.is(Items.DIRT)
                || stack.is(Items.GRASS_BLOCK)
                || stack.is(Items.COARSE_DIRT)
                || stack.is(Items.ROOTED_DIRT)
                || stack.is(Items.PODZOL);
    }

    private boolean isGravityPillarBlock(ItemStack stack) {
        // These ordinary full blocks are safe on a real supported column, not over air/fluid.
        return stack.is(Items.SAND) || stack.is(Items.RED_SAND) || stack.is(Items.GRAVEL);
    }

    private boolean isStonePillarBlock(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.CHISELED_STONE_BRICKS)
                || state.is(Blocks.POLISHED_DEEPSLATE)
                || state.is(Blocks.DEEPSLATE_BRICKS)
                || state.is(Blocks.CRACKED_DEEPSLATE_BRICKS)
                || state.is(Blocks.DEEPSLATE_TILES)
                || state.is(Blocks.CRACKED_DEEPSLATE_TILES)
                || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.POLISHED_BLACKSTONE)
                || state.is(Blocks.POLISHED_BLACKSTONE_BRICKS)
                || state.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)
                || state.is(Blocks.CHISELED_POLISHED_BLACKSTONE)
                || state.is(Blocks.BASALT)
                || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.END_STONE)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE);
    }

    private boolean isUsablePlankPillarBlock(ItemStack stack) {
        return stack.is(ItemTags.PLANKS) && !this.shouldPreserveWoodForPillar();
    }

    private boolean shouldPreserveWoodForPillar() {
        // Survival uses carried building material before asking routine supply work for more.
        // This must match both the activation count and the later hand/placement selection.
        return !this.isEmergencyRecoveryEpisode() && this.playerNpc.shouldPrioritizeLogGathering();
    }

    private boolean canGatherEscapeMaterial(BlockState state) {
        return this.isDirtEscapeMaterial(state)
                || this.isStoneEscapeMaterial(state) && this.hasPickaxe();
    }

    private boolean isEscapeMaterial(BlockState state) {
        return this.isDirtEscapeMaterial(state) || this.isStoneEscapeMaterial(state);
    }

    private boolean isStoneEscapeMaterial(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    private boolean isDirtEscapeMaterial(BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return BreakingBlockAi.requiredBreakTicks(serverLevel, pos, state, this.playerNpc);
    }

    private boolean hasPickaxe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof PickaxeItem);
    }

    private boolean equipPickaxe() {
        return this.equipTool(PickaxeItem.class);
    }

    private boolean equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }

        ItemStack tool = this.playerNpc.consumeInventoryItem(stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return false;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryPickaxe) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryPickaxe = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, tool);
        return true;
    }

    private boolean equipPreferredToolForPillarClear(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SAND)) {
            if (!this.equipTool(ShovelItem.class)) {
                this.equipEmptyHandForMining();
            }
            return true;
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(BlockTags.LOGS) || state.is(Blocks.CRAFTING_TABLE)) {
            return this.equipTool(AxeItem.class);
        } else if (state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.requiresCorrectToolForDrops()) {
            return this.equipTool(PickaxeItem.class);
        }
        return true;
    }

    private void equipEmptyHandForMining() {
        if (this.playerNpc.getMainHandItem().isEmpty()) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryPickaxe) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryPickaxe = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private boolean equipEscapeBlockForPlacement() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (this.isDirtPillarBlock(mainHand)) {
            return true;
        }

        ItemStack preferredBlock = InventoryUtils.consumeItem(this.playerNpc, this::isDirtPillarBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (!preferredBlock.isEmpty()) {
            this.equipTemporaryPillarBlock(preferredBlock);
            return true;
        }

        if (mainHand.getItem() instanceof BlockItem mainBlockItem
                && this.isStonePillarBlock(mainBlockItem.getBlock().defaultBlockState())) {
            return true;
        }

        preferredBlock = InventoryUtils.consumeItem(this.playerNpc, stack -> {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            return this.isStonePillarBlock(blockItem.getBlock().defaultBlockState());
        }, 1).orElse(ItemStack.EMPTY);
        if (!preferredBlock.isEmpty()) {
            this.equipTemporaryPillarBlock(preferredBlock);
            return true;
        }

        if (this.isUsablePlankPillarBlock(mainHand)) {
            return true;
        }

        preferredBlock = InventoryUtils.consumeItem(this.playerNpc, this::isUsablePlankPillarBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (!preferredBlock.isEmpty()) {
            this.equipTemporaryPillarBlock(preferredBlock);
            return true;
        }

        if (this.isGravityPillarBlock(mainHand)) {
            return true;
        }
        preferredBlock = InventoryUtils.consumeItem(this.playerNpc, this::isGravityPillarBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (!preferredBlock.isEmpty()) {
            this.equipTemporaryPillarBlock(preferredBlock);
            return true;
        }

        if (!this.shouldPreserveWoodForPillar() && !InventoryUtils.hasItem(this.playerNpc, this::isEscapeBlock)) {
            PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), 0);
            preferredBlock = InventoryUtils.consumeItem(this.playerNpc, this::isUsablePlankPillarBlock, 1)
                    .orElse(ItemStack.EMPTY);
            if (!preferredBlock.isEmpty()) {
                this.equipTemporaryPillarBlock(preferredBlock);
                return true;
            }
        }
        return false;
    }

    private void equipTemporaryPillarBlock(ItemStack block) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryBlock) {
            this.previousPillarMainHand = currentMainHand;
            this.usingTemporaryBlock = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousPillarMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, block);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryPickaxe) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryPickaxe = false;
    }

    private void restorePreviousPillarMainHand() {
        if (!this.usingTemporaryBlock) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousPillarMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousPillarMainHand.copy());
        this.previousPillarMainHand = ItemStack.EMPTY;
        this.usingTemporaryBlock = false;
    }

    private int countEscapeBlocks() {
        int count = 0;
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (this.isEscapeBlock(mainHand)) {
            count += mainHand.getCount();
        }
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && this.isEscapeBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count + this.countCraftablePillarPlanks();
    }

    private boolean exceedsRequestedRouteMax(PillarPlan plan) {
        int maxBlocks = this.getRequestedRouteMaxPillarBlocks();
        return plan != null && maxBlocks > 0 && plan.blocksNeeded() > maxBlocks;
    }

    private int getRequestedRouteMaxPillarBlocks() {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget == null
                || this.climbTargetPos == null
                || !requestedTarget.equals(this.climbTargetPos)) {
            return 0;
        }
        return this.playerNpc.getUpwardEscapeMaxPillarBlocks();
    }

    private int countCraftablePillarPlanks() {
        if (this.shouldPreserveWoodForPillar()) {
            return 0;
        }
        return PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) * 4;
    }

    private boolean hasTwoBlockBarrier(ServerLevel serverLevel, BlockPos feet, Direction direction) {
        BlockPos lower = feet.relative(direction);
        return this.hasBlockingCollision(serverLevel, lower)
                && this.hasBlockingCollision(serverLevel, lower.above());
    }

    /**
     * Recognizes the small open shafts that cannot produce exploration-stall evidence because a
     * job goal already owns movement. This is deliberately a tiny connected-component probe, not
     * a path query: internal one-block steps stay in the component, and only a continuous walking
     * route reaching its horizontal boundary counts as an exit. A valid shaft must be open to the
     * sky, collision-enclosed on every side, and have a materially higher loaded rim.
     */
    @Nullable
    private BlockPos findConfinedOpenShaftSurfaceTarget(ServerLevel serverLevel, BlockPos feet) {
        if (this.openShaftProbeCacheFeet != null
                && this.openShaftProbeCacheFeet.equals(feet)
                && this.playerNpc.tickCount < this.openShaftProbeCacheUntilTick) {
            return this.openShaftProbeCacheTarget;
        }

        this.openShaftProbeCacheFeet = feet.immutable();
        this.openShaftProbeCacheUntilTick = this.playerNpc.tickCount
                + (this.playerNpc.isTemporaryPillarSupport(feet.below()) ? 20 : OPEN_SHAFT_PROBE_CACHE_TICKS);
        this.openShaftProbeComponentSize = 0;
        this.openShaftProbeRimGain = 0;
        BlockPos target = this.computeConfinedOpenShaftSurfaceTarget(serverLevel, feet);
        this.openShaftProbeCacheTarget = target == null ? null : target.immutable();
        return this.openShaftProbeCacheTarget;
    }

    @Nullable
    private BlockPos computeConfinedOpenShaftSurfaceTarget(ServerLevel serverLevel, BlockPos feet) {
        boolean ownedSupport = this.playerNpc.isTemporaryPillarSupport(feet.below());
        if (!serverLevel.hasChunkAt(feet)
                || !this.canStandAt(serverLevel, feet)
                || !serverLevel.canSeeSky(feet.above())
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, feet)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, feet.below())) {
            return null;
        }
        int blockedSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.hasBlockingCollision(serverLevel, feet.relative(direction))) {
                blockedSides++;
            }
        }
        if (blockedSides < 2 && !ownedSupport
                && !this.hasNearbyShaftWalls(serverLevel, feet)) {
            // Ordinary open ground and a single cliff face never allocate the component scan.
            return null;
        }

        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> chamber = new HashSet<>();
        BlockPos start = feet.immutable();
        open.add(start);
        chamber.add(start);

        while (!open.isEmpty()) {
            BlockPos stand = open.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = stand.relative(direction);
                for (int dy : OPEN_SHAFT_STEP_OFFSETS) {
                    BlockPos candidate = adjacent.offset(0, dy, 0);
                    if (candidate.getY() < feet.getY() - 2
                            || candidate.getY() > feet.getY() + OPEN_SHAFT_COMPONENT_RADIUS
                            || !serverLevel.hasChunkAt(candidate)) {
                        return null;
                    }
                    if (!this.canStandAt(serverLevel, candidate)) {
                        continue;
                    }
                    if (Math.abs(candidate.getX() - feet.getX()) >= OPEN_SHAFT_COMPONENT_RADIUS
                            || Math.abs(candidate.getZ() - feet.getZ()) >= OPEN_SHAFT_COMPONENT_RADIUS) {
                        // A real sequence of level/one-step stands leaves the local chamber.
                        return null;
                    }
                    BlockPos immutable = candidate.immutable();
                    if (chamber.add(immutable)) {
                        if (chamber.size() > (ownedSupport || this.combatRecoveryEpisode
                                ? MAX_LOCAL_TRAP_STANDS : MAX_OPEN_SHAFT_COMPONENT_STANDS)) {
                            return null;
                        }
                        open.add(immutable);
                    }
                }
            }
        }

        Set<BlockPos> checkedBoundaryColumns = new HashSet<>();
        int elevatedWallDirections = 0;
        BlockPos bestTarget = null;
        int bestGain = Integer.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos stand : chamber) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (this.hasChamberNeighbor(chamber, stand, direction)) {
                    continue;
                }

                BlockPos wall = stand.relative(direction);
                if (!serverLevel.hasChunkAt(wall)) {
                    return null;
                }
                if (!this.hasBlockingCollision(serverLevel, wall)
                        && !this.hasBlockingCollision(serverLevel, wall.above())) {
                    // An open ledge/drop is not a shaft wall and must not authorize surface
                    // pillaring beside a cliff.
                    return null;
                }

                BlockPos column = new BlockPos(wall.getX(), 0, wall.getZ());
                if (!checkedBoundaryColumns.add(column)) {
                    continue;
                }
                if (checkedBoundaryColumns.size() > MAX_OPEN_SHAFT_BOUNDARY_COLUMNS) {
                    return null;
                }

                int surfaceY = serverLevel.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        wall.getX(),
                        wall.getZ()
                );
                int gain = surfaceY - feet.getY();
                if (gain < (ownedSupport ? 1 : OPEN_SHAFT_MIN_RIM_GAIN) || gain > OPEN_SHAFT_MAX_RIM_GAIN) {
                    continue;
                }
                elevatedWallDirections |= horizontalDirectionBit(direction);

                BlockPos candidate = new BlockPos(wall.getX(), surfaceY, wall.getZ());
                if (!this.isSafeOpenShaftRimTarget(serverLevel, candidate)) {
                    continue;
                }
                double distance = horizontalDistanceSqr(feet, candidate);
                if (bestTarget == null || gain < bestGain || gain == bestGain && distance < bestDistance) {
                    bestTarget = candidate.immutable();
                    bestGain = gain;
                    bestDistance = distance;
                }
            }
        }

        if (elevatedWallDirections != 0b1111 || bestTarget == null) {
            return null;
        }
        this.openShaftProbeComponentSize = chamber.size();
        this.openShaftProbeRimGain = bestGain;
        return bestTarget;
    }

    private boolean hasNearbyShaftWalls(ServerLevel serverLevel, BlockPos feet) {
        // The middle edge of a 2x3 shaft (or center of a 3x3) need not touch two walls. Before allocating its tiny
        // component flood, require a nearby body-height wall in all four cardinal directions.
        // The full flood still proves no walking exit and validates every elevated rim side.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            boolean wall = false;
            for (int distance = 1; distance <= OPEN_SHAFT_COMPONENT_RADIUS; distance++) {
                BlockPos candidate = feet.relative(direction, distance);
                if (!serverLevel.hasChunkAt(candidate)) {
                    return false;
                }
                if (this.hasBlockingCollision(serverLevel, candidate)
                        || this.hasBlockingCollision(serverLevel, candidate.above())) {
                    wall = true;
                    break;
                }
            }
            if (!wall) {
                return false;
            }
        }
        return true;
    }

    private boolean hasChamberNeighbor(Set<BlockPos> chamber, BlockPos stand, Direction direction) {
        BlockPos adjacent = stand.relative(direction);
        for (int dy = -1; dy <= 1; dy++) {
            if (chamber.contains(adjacent.offset(0, dy, 0))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafeOpenShaftRimTarget(ServerLevel serverLevel, BlockPos candidate) {
        if (!serverLevel.hasChunkAt(candidate)
                || !this.canStandAt(serverLevel, candidate)
                || !serverLevel.getFluidState(candidate).isEmpty()
                || !serverLevel.getFluidState(candidate.above()).isEmpty()
                || !serverLevel.canSeeSky(candidate.above())
                || serverLevel.getBlockEntity(candidate.below()) != null
                || serverLevel.getBlockState(candidate.below()).is(BlockTags.LOGS)
                || serverLevel.getBlockState(candidate.below()).is(BlockTags.LEAVES)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, candidate)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, candidate.below())) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos walkOff = candidate.relative(direction).offset(0, dy, 0);
                if (serverLevel.hasChunkAt(walkOff)
                        && this.canStandAt(serverLevel, walkOff)
                        && serverLevel.getFluidState(walkOff).isEmpty()
                        && serverLevel.getFluidState(walkOff.above()).isEmpty()
                        && serverLevel.canSeeSky(walkOff.above())
                        && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, walkOff)
                        && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, walkOff.below())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int horizontalDirectionBit(Direction direction) {
        return switch (direction) {
            case EAST -> 1;
            case WEST -> 1 << 1;
            case SOUTH -> 1 << 2;
            case NORTH -> 1 << 3;
            default -> 0;
        };
    }

    private boolean isActuallyTrapped(ServerLevel serverLevel, BlockPos feet) {
        if (this.localTrapCacheFeet != null
                && this.localTrapCacheFeet.equals(feet)
                && this.playerNpc.tickCount < this.localTrapCacheUntilTick) {
            return this.localTrapCacheResult;
        }
        this.localTrapCacheFeet = feet.immutable();
        this.localTrapCacheUntilTick = this.playerNpc.tickCount + LOCAL_TRAP_CACHE_TICKS;
        this.localTrapCacheResult = this.computeActuallyTrapped(serverLevel, feet);
        return this.localTrapCacheResult;
    }

    private boolean computeActuallyTrapped(ServerLevel serverLevel, BlockPos feet) {
        int blockedSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.hasBlockingCollision(serverLevel, feet.relative(direction))) {
                blockedSides++;
            }
        }
        if (blockedSides == 4) {
            return true;
        }
        // One exposed wall or cliff is ordinary navigation terrain. Two or more enclosing sides
        // admit a tiny loaded-only flood so 1x2/2x2 village shafts are recognized without the old
        // radius-four scan. Reaching the radius boundary or exhausting the cap is treated as a
        // walking escape (conservative no-pillar decision).
        return blockedSides >= 2 && !this.hasLocalWalkingEscape(serverLevel, feet);
    }

    private boolean hasLocalWalkingEscape(ServerLevel serverLevel, BlockPos feet) {
        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos start = feet.immutable();
        open.add(start);
        seen.add(start);

        while (!open.isEmpty()) {
            BlockPos pos = open.poll();
            if (!pos.equals(feet) && this.isLocalEscapeStandPos(feet, pos)) {
                return true;
            }
            if (seen.size() >= MAX_LOCAL_TRAP_STANDS) {
                return true;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = pos.relative(direction);
                this.queueReachableStand(serverLevel, feet, adjacent, open, seen);
                this.queueReachableStand(serverLevel, feet, adjacent.above(), open, seen);
                this.queueReachableStand(serverLevel, feet, adjacent.below(), open, seen);
            }
        }

        return false;
    }

    private boolean isLocalEscapeStandPos(BlockPos feet, BlockPos pos) {
        // A one-block rise inside a stepped shaft is still part of the same confined floor. Only
        // a connected horizontal route out of the local component proves that walking can escape.
        return Math.abs(pos.getX() - feet.getX()) >= LOCAL_TRAP_SEARCH_RADIUS
                || Math.abs(pos.getZ() - feet.getZ()) >= LOCAL_TRAP_SEARCH_RADIUS;
    }

    private void queueReachableStand(ServerLevel serverLevel, BlockPos origin, BlockPos candidate, Queue<BlockPos> open, Set<BlockPos> seen) {
        if (Math.abs(candidate.getX() - origin.getX()) > LOCAL_TRAP_SEARCH_RADIUS
                || Math.abs(candidate.getZ() - origin.getZ()) > LOCAL_TRAP_SEARCH_RADIUS
                || candidate.getY() < origin.getY() - 1
                || candidate.getY() > origin.getY() + 2
                || seen.contains(candidate)
                || !serverLevel.hasChunkAt(candidate)
                || !this.canStandAt(serverLevel, candidate)) {
            return;
        }

        BlockPos immutable = candidate.immutable();
        seen.add(immutable);
        open.add(immutable);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && this.hasOpenBodySpace(serverLevel, pos)
                && this.isWalkableFloor(serverLevel, pos.below());
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return !this.hasBlockingCollision(serverLevel, pos)
                && !this.hasBlockingCollision(serverLevel, pos.above());
    }

    private boolean hasOpenOrClearableBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return this.isOpenOrClearablePillarSpace(serverLevel, pos)
                && this.isOpenOrClearablePillarSpace(serverLevel, pos.above());
    }

    private boolean isOpenOrClearablePillarSpace(ServerLevel serverLevel, BlockPos pos) {
        return !this.hasBlockingCollision(serverLevel, pos)
                || this.isClearablePillarObstruction(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    private boolean isClearablePillarObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        boolean bedObstruction = state.getBlock() instanceof BedBlock;
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                && (bedObstruction
                || !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || this.isForcedHomeSurfaceRecoveryObstruction(pos))
                && !state.isAir()
                && this.blocksPillarSpace(serverLevel, pos, state)
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && (bedObstruction
                ? this.isSafePillarBedObstruction(serverLevel, pos, state)
                : serverLevel.getBlockEntity(pos) == null);
    }

    private boolean isForcedHomeSurfaceRecoveryObstruction(BlockPos pos) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        BlockPos feet = this.playerNpc.blockPosition();
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (pos == null
                || requestedTarget == null
                || !this.playerNpc.isForcedUpwardEscape()
                || home.isEmpty()
                || feet.getY() > home.get().origin().getY()
                || !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, feet)
                || !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, requestedTarget)) {
            return false;
        }

        return pos.getX() == feet.getX()
                && pos.getZ() == feet.getZ()
                && pos.getY() >= feet.getY()
                && pos.getY() <= requestedTarget.getY() + 1;
    }

    private boolean isSafePillarBedObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)
                || (state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED))) {
            return false;
        }

        BlockPos companionPos = getBedCompanionPos(pos, state);
        if (!serverLevel.isInWorldBounds(companionPos)
                || !serverLevel.getWorldBorder().isWithinBounds(companionPos)) {
            return false;
        }

        BlockState companionState = serverLevel.getBlockState(companionPos);
        if (!isMatchingBedCompanion(state, companionState)) {
            return true;
        }
        return !(companionState.hasProperty(BedBlock.OCCUPIED)
                && companionState.getValue(BedBlock.OCCUPIED))
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, companionPos);
    }

    @Nullable
    private BlockPos findMatchingBedCompanion(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)) {
            return null;
        }

        BlockPos companionPos = getBedCompanionPos(pos, state);
        return serverLevel.isInWorldBounds(companionPos)
                && serverLevel.getWorldBorder().isWithinBounds(companionPos)
                && isMatchingBedCompanion(state, serverLevel.getBlockState(companionPos))
                ? companionPos.immutable()
                : null;
    }

    private void removeRemainingBedCompanion(
            ServerLevel serverLevel,
            BlockState brokenBedState,
            BlockPos companionPos
    ) {
        BlockState companionState = serverLevel.getBlockState(companionPos);
        if (isMatchingBedCompanion(brokenBedState, companionState)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, companionPos)) {
            // Vanilla normally removes the other half during destroyBlock's neighbor update. This
            // no-drop fallback only repairs a surviving paired half; the broken half supplied the loot.
            serverLevel.removeBlock(companionPos, false);
        }
    }

    private static BlockPos getBedCompanionPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(BedBlock.FACING);
        return state.getValue(BedBlock.PART) == BedPart.FOOT
                ? pos.relative(facing)
                : pos.relative(facing.getOpposite());
    }

    private static boolean isMatchingBedCompanion(BlockState bedState, BlockState companionState) {
        return companionState.getBlock() == bedState.getBlock()
                && companionState.hasProperty(BedBlock.PART)
                && companionState.hasProperty(BedBlock.FACING)
                && companionState.getValue(BedBlock.PART) != bedState.getValue(BedBlock.PART)
                && companionState.getValue(BedBlock.FACING) == bedState.getValue(BedBlock.FACING);
    }

    private boolean blocksPillarSpace(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(serverLevel, pos).isEmpty()
                || !state.canBeReplaced();
    }

    private boolean isClearableExitObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.isClearablePillarObstruction(serverLevel, pos, state);
    }

    private boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean isWalkableFloor(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    private void updateGatherDetail(BlockState state) {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "escape blocks %d/%d mining %s @ %d %d %d",
                this.countEscapeBlocks(),
                this.requiredEscapeBlocks,
                state.getBlock().getDescriptionId(),
                this.minePos.getX(),
                this.minePos.getY(),
                this.minePos.getZ()
        ));
    }

    private void updatePillarDetail() {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "pillaring %d/%d\ngiving up in %ds",
                this.pillarsPlaced,
                this.maxPillarBlocks,
                this.getRemainingGoalSeconds()
        ));
    }

    private void updateRouteNavigationDetail() {
        if (this.routeNavigationTarget == null) {
            return;
        }

        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "routing cave exit @ %d %d %d\ngiving up in %ds",
                this.routeNavigationTarget.getX(),
                this.routeNavigationTarget.getY(),
                this.routeNavigationTarget.getZ(),
                this.getRemainingGoalSeconds()
        ));
    }

    private void updatePillarClearDetail(BlockState state) {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "pillaring %d/%d clearing %s @ %d %d %d\ngiving up in %ds",
                this.pillarsPlaced,
                this.maxPillarBlocks,
                state.getBlock().getDescriptionId(),
                this.pillarClearPos.getX(),
                this.pillarClearPos.getY(),
                this.pillarClearPos.getZ(),
                this.getRemainingGoalSeconds()
        ));
    }

    private void updatePillarRecoveryDetail(String action) {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "pillaring %d/%d %s\ngiving up in %ds",
                this.pillarsPlaced,
                this.maxPillarBlocks,
                action,
                this.getRemainingGoalSeconds()
        ));
    }

    private void updateExitClearDetail(BlockState state) {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "clearing pillar path %s @ %d %d %d",
                state.getBlock().getDescriptionId(),
                this.exitClearPos.getX(),
                this.exitClearPos.getY(),
                this.exitClearPos.getZ()
        ));
    }

    private void resetPlan() {
        setEscapeMode(EscapeMode.NONE);
        this.clearBlockAi.stop();
        this.gatherPathStuckFallbackAi.stop();
        this.protectedPillarPathStuckFallbackAi.stop();
        this.protectedPillarReplanPending = false;
        this.resetFarmEgressNavigation();
        this.resetFarmEgressClearContext();
        this.farmEgressNavigationBlockedThisTick = false;
        this.placePos = null;
        this.settlingPillarSupportPos = null;
        this.lastConfirmedPillarSupportPos = null;
        this.minePos = null;
        this.mineStandPos = null;
        this.routeNavigationTarget = null;
        this.climbTargetPos = null;
        this.pillarBasePos = null;
        this.pillarClearPos = null;
        this.pillarStuckWatchPos = null;
        this.forcedPillarBaseWatchFeet = null;
        this.forcedPillarBaseWatchTarget = null;
        this.pillarClearReturnWatchPos = null;
        this.exitClearPos = null;
        this.explorationClimbStepOffWatchPos = null;
        this.explorationClimbStepOffWatchTarget = null;
        this.explorationClimbStepOffWatchBase = null;
        this.explorationClimbStepOffStartPos = null;
        this.explorationClimbStepOffTargetPos = null;
        this.pillarExitY = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.previousPillarMainHand = ItemStack.EMPTY;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.pillarSettleTicks = 0;
        this.pillarSettleWaitTicks = 0;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.gatherPathFallbackAttempts = 0;
        this.gatherPathRecoveryDeadlineTick = 0;
        this.gatherPathFinalAttemptDeadlineTick = 0;
        this.goalTicks = 0;
        this.requiredEscapeBlocks = 0;
        this.maxPillarBlocks = 0;
        this.pillarsPlaced = 0;
        this.placedPillarSupports.clear();
        this.failedPillarPlaceAttempts = 0;
        this.pillarStuckWatchStartTick = 0;
        this.nextPillarStuckRecoveryTick = 0;
        this.pillarStuckWatchPillarsPlaced = 0;
        this.forcedPillarBaseWatchStartTick = 0;
        this.forcedPillarBaseRecoveryAttempts = 0;
        this.pillarClearReturnNoProgressTicks = 0;
        this.explorationClimbStepOffWatchStartTick = 0;
        this.explorationClimbStepOffTicks = 0;
        this.explorationClimbStepOffCompleted = false;
        this.usingTemporaryPickaxe = false;
        this.usingTemporaryBlock = false;
        this.explorationClimbEpisode = false;
        this.heightDirectedCombatEpisode = false;
        this.finished = false;
    }

    private void resetPillarStuckWatch() {
        this.pillarStuckWatchPos = null;
        this.pillarStuckWatchStartTick = 0;
        this.nextPillarStuckRecoveryTick = 0;
        this.pillarStuckWatchPillarsPlaced = this.pillarsPlaced;
    }

    private void resetPillarClearReturnWatch() {
        this.pillarClearReturnWatchPos = null;
        this.pillarClearReturnNoProgressTicks = 0;
    }

    private int getRemainingGoalSeconds() {
        int remainingTicks = Math.max(0, MAX_GOAL_TICKS - this.goalTicks);
        return (remainingTicks + 19) / 20;
    }

    private enum EscapeMode {
        NONE,
        FARM_GATE_EGRESS,
        NAVIGATE_ROUTE,
        GATHER_BLOCKS,
        CLEAR_EXIT,
        CLEAR_ROUTE,
        PILLAR
    }

    private enum FarmEgressDecision {
        NONE,
        START,
        BLOCK
    }

    private record EscapeMaterialTarget(BlockPos targetPos, BlockPos standPos) {}

    private record PillarPlan(BlockPos basePos, int exitY, int blocksNeeded) {}
}
