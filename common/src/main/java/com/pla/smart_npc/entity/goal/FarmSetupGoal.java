package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.ReturnPositionAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcFarmPlan;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Phase;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FarmSetupGoal extends Goal {
    private static final int MAX_ACTION_TICKS = 20 * 20;
    private static final int REPATH_INTERVAL_TICKS = 16;
    private static final int ROUTE_CLEAR_TRIGGER_TICKS = 20;
    private static final int CLEAR_TICKS = 18;
    private static final int CLEAR_RETRY_COOLDOWN_TICKS = 20 * 10;
    private static final int PLACEMENT_RETRY_COOLDOWN_TICKS = 20 * 5;
    private static final int MAX_PLACEMENT_STAND_PATH_CHECKS = 12;
    private static final int MAX_FARM_CLEAR_APPROACH_STANDS = 12;
    private static final int REPAIR_DIRT_SEARCH_RADIUS = 12;
    private static final int MAX_REPAIR_DIRT_PATH_CHECKS = 12;
    // The activation-wide budget is shared by all nested farm target/stand helpers. Keep the
    // admitted slice to one path and let the existing cursor/cadence continue discovery later.
    private static final int MAX_SETUP_SELECTION_PATHS = 1;
    private static final int MAX_REPAIR_DIRT_COLUMNS_PER_PASS = 96;
    private static final double REPAIR_DIRT_SEARCH_RESET_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MAX_PLACEMENT_RECOVERY_ATTEMPTS = 3;
    private static final int MAX_UNREACHABLE_RECOVERY_ATTEMPTS = 3;
    private static final int FARM_UPWARD_RECOVERY_TICKS = 20 * 8;
    private static final int FARM_UPWARD_RECOVERY_EXTRA_BLOCKS = 3;
    private static final int RETURN_TO_FARM_TICKS = 20 * 25;
    private static final int FAILED_FARM_RETURN_RETRY_TICKS = 20 * 20;
    private static final int FAILED_FARM_RETURN_RETRY_JITTER_TICKS = 20 * 10;
    private static final int LOCAL_ROUTE_ESCAPE_RISE = 3;
    private static final double FARM_STAND_REACHED_SQR = 0.95D * 0.95D;
    private static final double FARM_CLEAR_STAND_CENTERED_SQR = 0.35D * 0.35D;
    private static final double FARM_CLEAR_STAND_EYE_HEIGHT = 1.5D;
    private static final double MAX_NON_FULL_SUPPORT_STAND_OFFSET = 0.55D;
    private static final double INTERACTION_DISTANCE_SQR = 3.75D * 3.75D;
    private static final double CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final List<ColumnOffset> REPAIR_DIRT_COLUMN_OFFSETS = createRepairDirtColumnOffsets();

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final PlacingBlockAi placingBlockAi;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final ReturnPositionAi returnPositionAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final Map<BlockPos, Integer> clearRetryAfter = new HashMap<>();
    private final Map<BlockPos, Integer> placementRetryAfter = new HashMap<>();
    private final Set<BlockPos> failedFarmClearApproachStands = new HashSet<>();

    private Plan plan;
    private BlockPos targetPos;
    private BlockPos standPos;
    private BlockPos clearRequestedTargetPos;
    private BlockPos clearResolvedTargetPos;
    private BlockPos pendingPlacementTargetPos;
    private BlockPos pendingPlacementStandPos;
    private BlockPos lastApproachPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private Action action = Action.NONE;
    private Action pendingPlacementAction = Action.NONE;
    private int actionTicks;
    private int repathTicks;
    private int routeFailureTicks;
    private int routeClearRetryTicks;
    private int placementRecoveryAttempts;
    private boolean showingActionItem;
    private boolean finished;
    private BlockPos recoveryPlanOrigin;
    private BlockPos repairDirtSearchOrigin;
    private BlockPos failedFarmReturnPlanOrigin;
    private BlockPos failedFarmReturnTarget;
    private Path plannedStandPath;
    private int unreachableRecoveryAttempts;
    private int repairDirtSearchColumnCursor;
    // Persist bounded TILL selection across canUse() attempts. With a one-path activation
    // budget, restarting both searches at their nearest entry can retry one unreachable
    // soil/stand pair forever even though other owned dirt cells are workable.
    private int untilledGroundCursor;
    private int interactionStandCursor;
    private int failedFarmReturnRetryAfterTick;
    private long lastExpensiveWorkAdmissionTick = Long.MIN_VALUE;
    private NavigationPathBudget activationPathBudget;
    private boolean usingLocalWaterRecovery;

    public FarmSetupGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.returnPositionAi = new ReturnPositionAi(playerNpc, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean shouldExploreForFarmArea(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && serverLevel != null
                && FarmAi.isFarmingJobActive(playerNpc)
                && PlayerNpcFarmPlan.get(playerNpc).isEmpty()
                && !FarmAi.isPlanSearchPending(playerNpc, serverLevel)
                && !playerNpc.shouldPrioritizeLogGathering()
                && playerNpc.getGatherCooldown() <= 0
                && !serverLevel.isDarkOutside()
                && !serverLevel.isThundering();
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !FarmAi.isFarmingJobActive(this.playerNpc)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc)
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || serverLevel.isDarkOutside()
                || serverLevel.isThundering()
                || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.resetPlanState();
        this.activationPathBudget = new NavigationPathBudget(MAX_SETUP_SELECTION_PATHS);
        if (PlayerNpcFarmPlan.get(this.playerNpc).isEmpty()
                && this.playerNpc.shouldPrioritizeLogGathering()) {
            return false;
        }
        this.plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        boolean admissionHeld = false;
        if (this.plan == null) {
            this.plan = FarmAi.getOrCreatePlan(this.playerNpc, serverLevel).orElse(null);
            admissionHeld = this.plan != null;
        }
        if (this.plan == null) {
            this.playerNpc.setIdleTraceDetail(
                    FarmAi.isPlanSearchPending(this.playerNpc, serverLevel)
                            ? "farm setup: checking nearby dirt area in bounded passes"
                            : "farm setup blocked: no suitable dirt area",
                    40
            );
            return false;
        }
        if (!admissionHeld && !PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.playerNpc.setIdleTraceDetail("farm setup queued for shared search slice", 20);
            return false;
        }
        this.lastExpensiveWorkAdmissionTick = serverLevel.getServer().getTickCount();
        this.trackRecoveryPlan();

        if (!this.advanceMaterialPhases()) {
            return false;
        }
        this.rewindDamagedReadyPhase(serverLevel);

        // FarmCropGoal requires an exact path to an interior interaction stand before it can own
        // MOVE. If a ready farmer has wandered beyond the farm work bounds, that exact probe can
        // fail for every crop and leave no goal responsible for returning to the claimed plot.
        // Reuse setup's obstruction-aware return handoff; FarmCropGoal takes over at the entry.
        if (this.plan.phase() == Phase.READY
                && !FarmAi.isWithinWorkBounds(this.plan, this.playerNpc.blockPosition())
                && FarmCropGoal.hasActionableOwnedFarmWork(this.playerNpc, serverLevel)) {
            if (this.selectReturnToFarmAction()) {
                return true;
            }
            return false;
        }

        // Scheduling invariant: once the owned gate is valid, opening it must run
        // before selecting interior repair/clear work. Otherwise an interior soft
        // obstruction can fail its approach through the closed fence opening, enter
        // the clear retry map, and starve the later phase switch that used to open it.
        if (this.hasClosedValidGate(serverLevel)) {
            if (this.selectAction(serverLevel, Action.OPEN_GATE, this.plan.gatePos())) {
                return true;
            }
            this.playerNpc.setIdleTraceDetail("farm gate opening blocked before interior maintenance @ "
                    + posText(this.plan.gatePos()), 40);
            return false;
        }

        BlockPos damagedGround = this.findNearestRepairableGround(serverLevel);
        if (damagedGround != null) {
            if (this.hasGroundRepairMaterial()) {
                this.resetRepairDirtSearch();
                return this.selectAction(serverLevel, Action.REPAIR_GROUND, damagedGround);
            }
            RepairDirtSearchResult repairDirtSearch = this.selectRepairDirtGatheringAction(serverLevel);
            if (repairDirtSearch == RepairDirtSearchResult.FOUND) {
                return true;
            }
            if (repairDirtSearch == RepairDirtSearchResult.PENDING) {
                this.playerNpc.setIdleTraceDetail(
                        "farm ground repair: checking nearby dirt in bounded passes",
                        40
                );
                return false;
            }
            this.playerNpc.setIdleTraceDetail("farm ground repair blocked: needs dirt @ "
                    + posText(damagedGround), 40);
            return false;
        }
        this.resetRepairDirtSearch();

        if (this.plan.phase() != Phase.READY && !FarmAi.hasReachableEntry(this.playerNpc, serverLevel, this.plan)) {
            if (this.trySelectFarmRouteRecoveryClear(serverLevel)) {
                return true;
            }
            if (this.tryRequestFarmUpwardRecovery()) {
                return false;
            }
            if (this.unreachableRecoveryAttempts >= MAX_UNREACHABLE_RECOVERY_ATTEMPTS
                    && FarmAi.canReplaceUnreachableUnpreparedPlan(this.playerNpc, serverLevel, this.plan)) {
                PlayerNpcFarmPlan.clear(this.playerNpc);
                this.resetRecoveryPlan();
                this.plan = FarmAi.getOrCreatePlan(this.playerNpc, serverLevel, true).orElse(null);
                if (this.plan == null) {
                    this.playerNpc.setIdleTraceDetail(
                            FarmAi.isPlanSearchPending(this.playerNpc, serverLevel)
                                    ? "farm setup: checking replacement dirt area in bounded passes"
                                    : "farm setup blocked: no reachable dirt area",
                            40
                    );
                    return false;
                }
                this.trackRecoveryPlan();
                this.playerNpc.setIdleTraceDetail("replanned farm at reachable dirt area", 40);
                return false;
            }
            return this.selectReturnToFarmAction();
        } else {
            this.unreachableRecoveryAttempts = 0;
            this.clearFarmReturnRetry();
        }

        BlockPos obstruction = this.findNearestMaintenanceObstruction(serverLevel);
        if (obstruction != null) {
            if (this.isClearOnCooldown(obstruction)) {
                this.playerNpc.setIdleTraceDetail("farm obstruction retry cooling down @ "
                        + obstruction.getX() + " " + obstruction.getY() + " " + obstruction.getZ(), 40);
                return false;
            }
            if (this.selectAction(serverLevel, Action.CLEAR, obstruction)) {
                return true;
            }
            return false;
        }

        while (this.plan != null) {
            switch (this.plan.phase()) {
                case CLEAR -> this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.WATER);
                case WATER -> {
                    if (FarmAi.hasIrrigationWater(serverLevel, this.plan)) {
                        this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.FENCE);
                        continue;
                    }
                    BlockState waterState = serverLevel.getBlockState(this.plan.waterPos());
                    if (!waterState.isAir()) {
                        if (this.selectAction(serverLevel, Action.CLEAR, this.plan.waterPos())) {
                            return true;
                        }
                        return false;
                    }
                    if (!InventoryUtils.hasItem(this.playerNpc, Items.WATER_BUCKET)) {
                        this.playerNpc.setIdleTraceDetail("farm setup waiting for water bucket", 40);
                        return false;
                    }
                    return this.selectAction(serverLevel, Action.PLACE_WATER, this.plan.waterPos());
                }
                case FENCE -> {
                    BlockPos missingFence = this.findMissingFence(serverLevel);
                    if (missingFence == null) {
                        int missingCount = FarmAi.missingFenceCount(serverLevel, this.plan);
                        if (missingCount > 0) {
                            BlockPos coolingTarget = this.findAnyMissingFence(serverLevel);
                            this.playerNpc.setIdleTraceDetail("farm fence retry cooling: phase=FENCE missing="
                                    + missingCount + " target=" + posText(coolingTarget)
                                    + " wait=" + this.placementRetryTicks(coolingTarget) + "t", 40);
                            return false;
                        }
                        this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.GATE);
                        continue;
                    }
                    if (!this.canProvideFence()) {
                        if (PlayerNpcCraftingUtil.canCraftFences(this.playerNpc.getInventory(), 0)) {
                            this.playerNpc.setIdleTraceDetail("farm fence awaiting crafting-table recipe", 40);
                            return false;
                        }
                        this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.GATHER_LOGS);
                        return false;
                    }
                    return this.selectAction(serverLevel, Action.PLACE_FENCE, missingFence);
                }
                case GATE -> {
                    if (FarmAi.hasGate(serverLevel, this.plan)) {
                        this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.TILL);
                        continue;
                    }
                    if (FarmAi.hasGateBlock(serverLevel, this.plan)) {
                        return this.selectAction(serverLevel, Action.REPAIR_GATE, this.plan.gatePos());
                    }
                    if (!this.canProvideGate()) {
                        if (PlayerNpcCraftingUtil.canCraftFenceGate(this.playerNpc.getInventory(), 0)) {
                            this.playerNpc.setIdleTraceDetail("farm gate awaiting crafting-table recipe", 40);
                            return false;
                        }
                        this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.GATHER_LOGS);
                        return false;
                    }
                    return this.selectAction(serverLevel, Action.PLACE_GATE, this.plan.gatePos());
                }
                case TILL -> {
                    BlockPos untilled = this.findUntilledGround(serverLevel);
                    if (untilled == null) {
                        this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.READY);
                        return false;
                    }
                    if (!FarmAi.hasHoe(this.playerNpc)) {
                        this.playerNpc.setIdleTraceDetail("farm crop ground awaiting till: needs hoe @ "
                                + posText(untilled), 40);
                        return false;
                    }
                    return this.selectAction(serverLevel, Action.TILL, untilled);
                }
                case READY -> {
                    BlockState gateState = serverLevel.getBlockState(this.plan.gatePos());
                    if (gateState.getBlock() instanceof FenceGateBlock
                            && gateState.hasProperty(FenceGateBlock.OPEN)
                            && !gateState.getValue(FenceGateBlock.OPEN)) {
                        return this.selectAction(serverLevel, Action.OPEN_GATE, this.plan.gatePos());
                    }
                    return false;
                }
                case GATHER_LOGS, GATHER_STONE -> {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.action != Action.NONE
                && this.targetPos != null
                && this.actionTicks < (this.action == Action.RETURN_TO_FARM
                || this.action == Action.APPROACH_FARM_WORK
                || this.action == Action.APPROACH_PLACEMENT_WORK ? RETURN_TO_FARM_TICKS : MAX_ACTION_TICKS)
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null;
    }

    @Override
    public void start() {
        this.activationPathBudget = null;
        this.actionTicks = 0;
        this.repathTicks = 0;
        this.routeFailureTicks = 0;
        this.lastApproachPos = this.playerNpc.blockPosition().immutable();
        this.finished = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.setting_up_farm");
        this.updateDetail();
        if (this.action == Action.TILL) {
            this.toolAi.equipTool(HoeItem.class);
        }
        if (this.isFarmReturnAction()) {
            Path selectedPath = this.plannedStandPath;
            this.plannedStandPath = null;
            this.returnPositionAi.start(this.targetPos, selectedPath);
        } else if (this.action != Action.CLEAR) {
            ServerLevel serverLevel = serverLevel();
            if (this.tryAcquireExpensiveWork(serverLevel) && this.moveToStand(serverLevel)) {
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
        }
    }

    @Override
    public void tick() {
        ServerLevel serverLevel = serverLevel();
        if (serverLevel == null || this.targetPos == null || this.plan == null) {
            this.finished = true;
            return;
        }
        this.actionTicks++;
        this.lookAtTarget();

        // The farmer can fall into its own one-block irrigation cell while repairing or
        // tilling the plot. FloatGoal only owns JUMP, so keep this MOVE goal active and let
        // the existing destination-aware water recovery carry it toward the selected dry
        // interaction stand. If that destination is quarantined after a bounded stall,
        // fall back to WaterEscapeAi's local dry-exit search instead of returning to idle.
        if (this.tickWaterRecovery(serverLevel)) {
            return;
        }

        if (this.isFarmReturnAction()) {
            this.tickReturnToFarm(serverLevel);
            return;
        }
        if (this.action == Action.CLEAR) {
            this.tickClear(serverLevel);
            return;
        }
        if (!this.isReadyAtInteractionStand()) {
            this.placingBlockAi.resetDelay();
            this.tickApproach(serverLevel);
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.routeFailureTicks = 0;
        if (this.action == Action.GATHER_REPAIR_DIRT) {
            this.tickGatherRepairDirt(serverLevel);
            return;
        }
        if (this.placingBlockAi.tickDelay(this.action == Action.TILL
                ? PlacingBlockAi.QUICK_UTILITY_DELAY
                : PlacingBlockAi.PLAYER_LIKE_BUILD_DELAY)) {
            return;
        }

        boolean success = switch (this.action) {
            case PLACE_WATER -> this.placeWater(serverLevel);
            case PLACE_FENCE -> this.placeFence(serverLevel);
            case PLACE_GATE -> this.placeGate(serverLevel);
            case REPAIR_GATE -> this.repairGate(serverLevel);
            case REPAIR_GROUND -> this.repairGround(serverLevel);
            case TILL -> this.tillGround(serverLevel);
            case OPEN_GATE -> this.openGate(serverLevel);
            default -> false;
        };
        this.finished = true;
        if (!success) {
            if (this.isPlacementAction()) {
                this.markPlacementRetry(this.targetPos);
            }
            this.playerNpc.setCurrentAiDetail(this.describeAction() + " failed");
        }
    }

    @Override
    public void stop() {
        if (this.isPlacementAction() && !this.finished) {
            this.markPlacementRetry(this.targetPos);
        }
        this.playerNpc.clearBlockBreakProgress(this.clearBlockAi.targetPos());
        this.clearBlockAi.stop();
        this.pathNavigationAi.stopWaterTravel();
        this.usingLocalWaterRecovery = false;
        this.returnPositionAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restoreActionItem();
        this.placingBlockAi.resetDelay();

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlanState();
    }

    private boolean advanceMaterialPhases() {
        if (this.plan.phase() == Phase.GATHER_LOGS) {
            if (ResourceAi.countLogs(this.playerNpc) < FarmAi.requiredRawLogs(this.plan)) {
                this.playerNpc.setIdleTraceDetail(
                        "farm needs logs " + ResourceAi.countLogs(this.playerNpc) + "/" + FarmAi.requiredRawLogs(this.plan),
                        40
                );
                return false;
            }
            this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.GATHER_STONE);
        }
        if (this.plan.phase() == Phase.GATHER_STONE) {
            if (ResourceAi.countStone(this.playerNpc) < FarmAi.REQUIRED_STONE) {
                this.playerNpc.setIdleTraceDetail(
                        "farm needs stone " + ResourceAi.countStone(this.playerNpc) + "/" + FarmAi.REQUIRED_STONE,
                        40
                );
                return false;
            }
            this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.CLEAR);
        }
        return true;
    }

    private void rewindDamagedReadyPhase(ServerLevel serverLevel) {
        if (this.plan.phase() != Phase.READY) {
            return;
        }
        if (!FarmAi.hasIrrigationWater(serverLevel, this.plan)) {
            this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.WATER);
        } else if (FarmAi.missingFenceCount(serverLevel, this.plan) > 0) {
            this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.FENCE);
        } else if (!FarmAi.hasGate(serverLevel, this.plan)) {
            this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.GATE);
        } else {
            BlockPos untilled = this.findUntilledGround(serverLevel);
            if (untilled == null) {
                return;
            }
            this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.TILL);
            this.playerNpc.setIdleTraceDetail("farm READY rewound to TILL @ " + posText(untilled), 40);
        }
    }

    private boolean selectAction(ServerLevel serverLevel, Action action, BlockPos target) {
        if (action == Action.CLEAR && this.isClearOnCooldown(target)) {
            return false;
        }
        if (isPlacementAction(action) && this.isPlacementOnCooldown(target)) {
            return false;
        }
        this.action = action;
        this.targetPos = target.immutable();
        this.clearRequestedTargetPos = action == Action.CLEAR ? target.immutable() : null;
        this.clearResolvedTargetPos = null;
        this.plannedStandPath = null;
        if (action != Action.CLEAR) {
            this.standPos = this.findInteractionStand(serverLevel, target, action == Action.REPAIR_GROUND);
            if (this.standPos != null) {
                if (isPlacementAction(action)) {
                    this.clearPendingPlacementRecovery();
                }
                return true;
            }
            if (isPlacementAction(action)) {
                if (this.selectPlacementRecoveryAction(serverLevel, action, target)) {
                    return true;
                }
                this.markPlacementRetry(target);
            }
            this.action = Action.NONE;
            this.targetPos = null;
            return false;
        }
        this.standPos = null;
        BlockPos resolvedTarget = ClearBlockAi.resolveInitialClearTarget(
                serverLevel,
                this.playerNpc,
                target,
                this::isActiveClearObstructionState,
                CLEAR_DISTANCE_SQR,
                true
        ).orElse(null);
        this.clearResolvedTargetPos = resolvedTarget == null ? null : resolvedTarget.immutable();
        // resolveInitialClearTarget also returns the requested block when the current ray is
        // blocked by a protected/non-clearable block. Do not mistake that fallback for proof
        // that the farmer can actually hit it: doing so hands a low surface obstruction to the
        // generic ClearBlock stand search, which rejects farmland-supported stands and can keep
        // rotating local path probes without ever starting BreakingBlockAi.
        if (resolvedTarget != null
                && this.isSafeClearTarget(serverLevel, resolvedTarget)
                && !this.isInWater(serverLevel)
                && ClearBlockAi.canBreakFromCurrentStand(
                        serverLevel,
                        this.playerNpc,
                        resolvedTarget,
                        true
                )) {
            return true;
        }
        if (this.selectFarmWorkApproachAction(serverLevel, target)) {
            return true;
        }
        this.markClearRetry(target);
        this.markClearRetry(resolvedTarget);
        this.action = Action.NONE;
        this.targetPos = null;
        this.standPos = null;
        return false;
    }

    private boolean selectPlacementRecoveryAction(ServerLevel serverLevel, Action placementAction, BlockPos placementTarget) {
        if (!isPlacementAction(placementAction)
                || placementTarget == null
                || this.plan == null
                || this.placementRecoveryAttempts >= MAX_PLACEMENT_RECOVERY_ATTEMPTS) {
            return false;
        }

        BlockPos placementStand = this.pendingPlacementStandPos;
        if (placementStand == null
                || !this.canOccupyPlacementStand(serverLevel, placementStand)
                || distanceFromStandToTargetSqr(placementStand, placementTarget) > INTERACTION_DISTANCE_SQR) {
            placementStand = this.findPotentialInteractionStand(
                    serverLevel,
                    placementTarget,
                    placementAction == Action.REPAIR_GROUND
            );
        }
        BlockPos recoveryTarget = placementStand;
        if (recoveryTarget == null && !this.plan.pathPositions().isEmpty()) {
            recoveryTarget = this.plan.pathPositions().get(0).above().immutable();
        }
        if (recoveryTarget == null) {
            this.playerNpc.setIdleTraceDetail("farm placement blocked: phase=" + this.plan.phase()
                    + " target=" + posText(placementTarget) + " reason=no interaction or entry stand", 40);
            return false;
        }
        this.pendingPlacementAction = placementAction;
        this.pendingPlacementTargetPos = placementTarget.immutable();
        this.pendingPlacementStandPos = placementStand == null ? null : placementStand.immutable();
        this.placementRecoveryAttempts++;
        this.action = Action.APPROACH_PLACEMENT_WORK;
        this.targetPos = recoveryTarget.immutable();
        this.standPos = recoveryTarget.immutable();
        this.clearRequestedTargetPos = null;
        this.clearResolvedTargetPos = null;
        this.returnPositionAi.start(recoveryTarget);
        return true;
    }

    private boolean selectReturnToFarmAction() {
        if (this.plan == null || this.plan.pathPositions().isEmpty()) {
            this.playerNpc.setIdleTraceDetail("farm return blocked: claimed farm has no entry path", 40);
            return false;
        }
        BlockPos returnTarget = this.plan.pathPositions().get(0).above().immutable();
        if (this.isFarmReturnOnCooldown(this.plan.origin(), returnTarget)) {
            this.playerNpc.setIdleTraceDetail("farm return retry cooling down @ " + posText(returnTarget), 40);
            return false;
        }
        this.action = Action.RETURN_TO_FARM;
        this.targetPos = returnTarget;
        this.standPos = this.targetPos;
        return true;
    }

    private boolean selectFarmWorkApproachAction(ServerLevel serverLevel, BlockPos workTarget) {
        // Farm soil has a non-full collision shape, so the generic clear helper's
        // solid-render stand test rejects every otherwise valid interior stand.
        // Use the farm placement stand/path rules here; ClearBlockAi takes over once
        // the NPC reaches this normal interaction-range position.
        BlockPos approach = this.findFarmClearApproachStand(serverLevel, workTarget);
        if (approach == null) {
            return false;
        }
        this.action = Action.APPROACH_FARM_WORK;
        this.targetPos = approach;
        this.standPos = approach;
        this.clearRequestedTargetPos = workTarget == null ? null : workTarget.immutable();
        return true;
    }

    private boolean hasClosedValidGate(ServerLevel serverLevel) {
        if (serverLevel == null
                || this.plan == null
                || !FarmAi.hasGate(serverLevel, this.plan)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(this.plan.gatePos());
        return state.hasProperty(FenceGateBlock.OPEN)
                && !state.getValue(FenceGateBlock.OPEN);
    }

    private void tickReturnToFarm(ServerLevel serverLevel) {
        double targetX = this.targetPos.getX() + 0.5D;
        double targetZ = this.targetPos.getZ() + 0.5D;
        boolean exactClearApproach = this.action == Action.APPROACH_FARM_WORK;
        boolean exactPlacementApproach = this.action == Action.APPROACH_PLACEMENT_WORK;
        boolean reachedTarget = exactClearApproach || exactPlacementApproach
                ? this.isOccupyingFarmStand(
                        this.targetPos,
                        exactClearApproach ? FARM_CLEAR_STAND_CENTERED_SQR : FARM_STAND_REACHED_SQR
                )
                : this.playerNpc.distanceToSqr(targetX, this.targetPos.getY(), targetZ)
                <= INTERACTION_DISTANCE_SQR;
        if (reachedTarget) {
            this.unreachableRecoveryAttempts = 0;
            if (this.action == Action.RETURN_TO_FARM) {
                this.clearFarmReturnRetry();
            }
            if (this.action == Action.APPROACH_FARM_WORK && this.clearRequestedTargetPos != null) {
                BlockPos requestedTarget = this.clearRequestedTargetPos.immutable();
                BlockPos failedStand = this.targetPos.immutable();
                int approachEpisodeTicks = this.actionTicks;
                this.returnPositionAi.stop();
                this.failedFarmClearApproachStands.add(failedStand);
                if (this.selectAction(serverLevel, Action.CLEAR, requestedTarget)) {
                    if (this.action == Action.CLEAR) {
                        this.actionTicks = 0;
                    } else if (approachEpisodeTicks < RETURN_TO_FARM_TICKS - 1) {
                        this.actionTicks = approachEpisodeTicks;
                    } else {
                        this.startFarmClearRouteRecovery(requestedTarget);
                        return;
                    }
                    this.updateDetail();
                    return;
                }
                this.startFarmClearRouteRecovery(requestedTarget);
                return;
            }
            if (exactPlacementApproach
                    && isPlacementAction(this.pendingPlacementAction)
                    && this.pendingPlacementTargetPos != null) {
                Action placementAction = this.pendingPlacementAction;
                BlockPos placementTarget = this.pendingPlacementTargetPos.immutable();
                this.returnPositionAi.stop();
                this.actionTicks = 0;
                if (this.pendingPlacementStandPos == null) {
                    BlockPos resolvedStand = this.isInInteractionRange(placementTarget)
                            ? this.playerNpc.blockPosition().immutable()
                            : this.findPotentialInteractionStand(
                            serverLevel,
                            placementTarget,
                            placementAction == Action.REPAIR_GROUND
                    );
                    if (placementAction == Action.REPAIR_GROUND
                            && resolvedStand != null
                            && resolvedStand.equals(placementTarget.above())) {
                        resolvedStand = this.findPotentialInteractionStand(serverLevel, placementTarget, true);
                    }
                    if (resolvedStand != null) {
                        this.pendingPlacementStandPos = resolvedStand.immutable();
                        this.targetPos = resolvedStand.immutable();
                        this.standPos = resolvedStand.immutable();
                        this.returnPositionAi.start(resolvedStand);
                        this.updateDetail();
                        return;
                    }
                    this.playerNpc.setCurrentAiDetail("farm placement blocked: phase=" + this.plan.phase()
                            + " target=" + posText(placementTarget)
                            + " reason=no usable stand after entry recovery");
                    this.markPlacementRetry(placementTarget);
                    this.finished = true;
                    return;
                }
                BlockPos placementStand = this.pendingPlacementStandPos.immutable();
                if (this.isOccupyingFarmStand(placementStand, FARM_STAND_REACHED_SQR)
                        && this.isInInteractionRange(placementTarget)) {
                    this.action = placementAction;
                    this.targetPos = placementTarget;
                    this.standPos = placementStand;
                    this.clearPendingPlacementRecovery();
                    this.updateDetail();
                    return;
                }
                this.markPlacementRetry(placementTarget);
            }
            this.finished = true;
            return;
        }
        if (exactClearApproach
                && this.isOccupyingFarmStand(this.targetPos, FARM_STAND_REACHED_SQR)) {
            this.playerNpc.getNavigation().stop();
            this.playerNpc.getMoveControl().setWantedPosition(targetX, this.targetPos.getY(), targetZ, 0.65D);
            this.playerNpc.setCurrentAiDetail("centering at farm clear stand @ "
                    + this.targetPos.getX() + " " + this.targetPos.getY() + " " + this.targetPos.getZ());
            return;
        }
        String moveDetail = this.action == Action.APPROACH_FARM_WORK
                ? "approaching farm work area"
                : "returning to claimed farm";
        this.returnPositionAi.tick(
                serverLevel,
                this.targetPos,
                this::isProtectedFarmReturnBlock,
                moveDetail,
                "clearing claimed farm route",
                false
        );
        this.playerNpc.setCurrentAiDetail(this.returnPositionAi.detail(moveDetail));
        if (this.actionTicks < RETURN_TO_FARM_TICKS - 1) {
            return;
        }
        if (this.action == Action.APPROACH_FARM_WORK) {
            this.markClearRetry(this.clearRequestedTargetPos);
            this.markClearRetry(this.clearResolvedTargetPos);
        } else if (this.action == Action.APPROACH_PLACEMENT_WORK) {
            this.markPlacementRetry(this.pendingPlacementTargetPos);
        } else if (this.action == Action.RETURN_TO_FARM) {
            this.markFarmReturnRetry(this.plan.origin(), this.targetPos);
        }
        this.unreachableRecoveryAttempts++;
        this.requestLocalRouteEscape();
        this.finished = true;
    }

    private boolean isProtectedFarmReturnBlock(BlockPos pos) {
        return pos == null
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isProtectedFarmlandBlock(this.playerNpc, pos);
    }

    private void requestLocalRouteEscape() {
        if (this.playerNpc.getUpwardEscapeTarget() != null) {
            return;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos escape = feet.above(LOCAL_ROUTE_ESCAPE_RISE);
        this.playerNpc.requestForcedUpwardEscapeTo(
                escape,
                FARM_UPWARD_RECOVERY_TICKS,
                LOCAL_ROUTE_ESCAPE_RISE + FARM_UPWARD_RECOVERY_EXTRA_BLOCKS
        );
        this.playerNpc.setCurrentAiDetail("farm return route escape @ "
                + escape.getX() + " " + escape.getY() + " " + escape.getZ());
    }

    private void startFarmClearRouteRecovery(BlockPos requestedTarget) {
        this.markClearRetry(requestedTarget);
        this.markClearRetry(this.clearResolvedTargetPos);
        this.unreachableRecoveryAttempts++;
        this.requestLocalRouteEscape();
        this.finished = true;
    }

    private void tickClear(ServerLevel serverLevel) {
        if (this.clearBlockAi.isRunning()) {
            if (!this.isSafeClearTarget(serverLevel, this.clearBlockAi.targetPos())) {
                if (this.clearBlockAi.targetPos() != null) {
                    this.markClearRetry(this.clearBlockAi.targetPos());
                }
                this.markClearRetry(this.clearRequestedTargetPos);
                this.markClearRetry(this.clearResolvedTargetPos);
                this.clearBlockAi.stop();
                this.finished = true;
                return;
            }
            BlockPos attemptedTarget = this.clearBlockAi.targetPos() == null
                    ? null
                    : this.clearBlockAi.targetPos().immutable();
            ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
            if (result == ClearBlockAi.TickResult.RUNNING) {
                if (!this.isSafeClearTarget(serverLevel, this.clearBlockAi.targetPos())) {
                    if (this.clearBlockAi.targetPos() != null) {
                        this.markClearRetry(this.clearBlockAi.targetPos());
                    }
                    this.markClearRetry(this.clearRequestedTargetPos);
                    this.markClearRetry(this.clearResolvedTargetPos);
                    this.clearBlockAi.stop();
                    this.finished = true;
                    return;
                }
                this.updateDetail();
                return;
            }
            if (result == ClearBlockAi.TickResult.FAILED && attemptedTarget != null) {
                this.markClearRetry(attemptedTarget);
                this.markClearRetry(this.clearRequestedTargetPos);
                this.markClearRetry(this.clearResolvedTargetPos);
                this.unreachableRecoveryAttempts++;
            }
            this.finished = true;
            return;
        }

        if (!this.isSafeClearTarget(serverLevel, this.targetPos)) {
            this.markClearRetry(this.targetPos);
            this.markClearRetry(this.clearRequestedTargetPos);
            this.markClearRetry(this.clearResolvedTargetPos);
            this.finished = true;
            return;
        }
        if (this.clearBlockAi.start(
                serverLevel,
                this.targetPos,
                this::isActiveClearObstructionState,
                "clearing farm obstruction",
                CLEAR_TICKS,
                CLEAR_DISTANCE_SQR,
                true,
                true
        )) {
            return;
        }
        this.tickApproach(serverLevel);
    }

    private void tickApproach(ServerLevel serverLevel) {
        if (this.standPos == null || !PathNavigationAi.canStandAt(serverLevel, this.standPos)) {
            if (!this.tryAcquireExpensiveWork(serverLevel)) {
                this.deferApproachForSharedBudget();
                return;
            }
            this.activationPathBudget = new NavigationPathBudget(MAX_SETUP_SELECTION_PATHS);
            this.standPos = this.findInteractionStand(
                    serverLevel,
                    this.targetPos,
                    this.action == Action.REPAIR_GROUND
            );
            this.activationPathBudget = null;
            if (this.standPos != null) {
                this.repathTicks = 0;
            }
        }
        BlockPos feet = this.playerNpc.blockPosition();
        if (!feet.equals(this.lastApproachPos)) {
            this.lastApproachPos = feet.immutable();
            this.routeFailureTicks = 0;
            this.routeClearRetryTicks = 0;
        } else {
            this.routeFailureTicks++;
        }
        if (this.standPos != null && this.repathTicks-- <= 0) {
            if (!this.tryAcquireExpensiveWork(serverLevel)) {
                this.deferApproachForSharedBudget();
                return;
            }
            this.moveToStand(serverLevel);
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
        if (this.routeFailureTicks >= ROUTE_CLEAR_TRIGGER_TICKS && this.routeClearRetryTicks-- <= 0) {
            if (!this.tryAcquireExpensiveWork(serverLevel)) {
                this.deferRouteClearForSharedBudget();
                return;
            }
            if (this.tryStartRouteClear(serverLevel)) {
                this.action = Action.CLEAR;
                this.routeFailureTicks = 0;
                this.routeClearRetryTicks = 0;
                return;
            }
            this.routeClearRetryTicks = REPATH_INTERVAL_TICKS
                    + this.playerNpc.getRandom().nextInt(REPATH_INTERVAL_TICKS / 2 + 1);
        }
        if (this.routeFailureTicks > ROUTE_CLEAR_TRIGGER_TICKS * 3) {
            if (this.isPlacementAction()) {
                this.markPlacementRetry(this.targetPos);
            }
            this.finished = true;
        }
    }

    private boolean tryStartRouteClear(ServerLevel serverLevel) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos routeTarget = this.standPos == null ? this.targetPos : this.standPos;
        addRouteCandidates(candidates, feet, routeTarget, 10);
        candidates.addAll(this.plan.pathPositions().stream().map(BlockPos::above).toList());
        candidates.addAll(PlayerNpcFarmPlan.get(this.playerNpc)
                .map(value -> List.of(value.gatePos(), value.gatePos().above()))
                .orElse(List.of()));
        candidates.removeIf(pos -> this.isClearOnCooldown(pos)
                || !this.isSafeClearTarget(serverLevel, pos));
        boolean started = this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                this::isActiveClearObstructionState,
                "clearing blocked farm entrance",
                CLEAR_TICKS,
                CLEAR_DISTANCE_SQR,
                true,
                true
        );
        if (started && !this.isSafeClearTarget(serverLevel, this.clearBlockAi.targetPos())) {
            this.clearBlockAi.stop();
            return false;
        }
        return started;
    }

    private boolean trySelectFarmRouteRecoveryClear(ServerLevel serverLevel) {
        if (this.plan.pathPositions().isEmpty()) {
            return false;
        }
        BlockPos entryStand = this.plan.pathPositions().get(0).above();
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                entryStand,
                entryStand
        ));
        candidates.add(this.plan.gatePos());
        candidates.add(this.plan.gatePos().above());
        candidates.addAll(this.plan.pathPositions().stream().map(BlockPos::above).toList());
        candidates.removeIf(pos -> this.isClearOnCooldown(pos) || !this.isSafeClearTarget(serverLevel, pos));
        return ClearBlockAi.findNearestAccessibleClearable(
                serverLevel,
                this.playerNpc,
                candidates,
                this::isActiveClearObstructionState,
                CLEAR_DISTANCE_SQR,
                true
        ).map(target -> this.selectAction(serverLevel, Action.CLEAR, target)).orElse(false);
    }

    private boolean tryRequestFarmUpwardRecovery() {
        if (this.unreachableRecoveryAttempts >= MAX_UNREACHABLE_RECOVERY_ATTEMPTS
                || this.plan.pathPositions().isEmpty()) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos entryStand = this.plan.pathPositions().get(0).above();
        int verticalGap = entryStand.getY() - feet.getY();
        if (verticalGap <= 2) {
            return false;
        }
        int maxPillarBlocks = Math.min(12, verticalGap + FARM_UPWARD_RECOVERY_EXTRA_BLOCKS);
        this.unreachableRecoveryAttempts++;
        this.playerNpc.requestForcedUpwardEscapeTo(
                entryStand,
                FARM_UPWARD_RECOVERY_TICKS,
                maxPillarBlocks
        );
        this.playerNpc.setCurrentAiDetail("pillaring to farm entrance @ "
                + entryStand.getX() + " " + entryStand.getY() + " " + entryStand.getZ());
        return true;
    }

    private void trackRecoveryPlan() {
        if (this.plan == null) {
            return;
        }
        if (this.recoveryPlanOrigin == null || !this.recoveryPlanOrigin.equals(this.plan.origin())) {
            this.recoveryPlanOrigin = this.plan.origin().immutable();
            this.unreachableRecoveryAttempts = 0;
            this.clearFarmReturnRetry();
            this.resetRepairDirtSearch();
            this.clearRetryAfter.clear();
            this.placementRetryAfter.clear();
        }
    }

    private void resetRecoveryPlan() {
        this.recoveryPlanOrigin = null;
        this.unreachableRecoveryAttempts = 0;
        this.clearFarmReturnRetry();
        this.clearRetryAfter.clear();
        this.placementRetryAfter.clear();
    }

    private boolean tryAcquireExpensiveWork(ServerLevel serverLevel) {
        if (serverLevel == null || serverLevel.getServer() == null) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        if (this.lastExpensiveWorkAdmissionTick == tick) {
            return true;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            return false;
        }
        this.lastExpensiveWorkAdmissionTick = tick;
        return true;
    }

    private void deferApproachForSharedBudget() {
        this.repathTicks = 1 + this.playerNpc.getRandom().nextInt(4);
        this.playerNpc.setCurrentAiDetail(this.describeAction() + " (queued for shared search slice)");
    }

    private void deferRouteClearForSharedBudget() {
        this.routeClearRetryTicks = 1 + this.playerNpc.getRandom().nextInt(4);
        this.playerNpc.setCurrentAiDetail(this.describeAction() + " (queued for shared search slice)");
    }

    private void markFarmReturnRetry(BlockPos planOrigin, BlockPos target) {
        if (planOrigin == null || target == null) {
            return;
        }
        this.failedFarmReturnPlanOrigin = planOrigin.immutable();
        this.failedFarmReturnTarget = target.immutable();
        this.failedFarmReturnRetryAfterTick = this.playerNpc.tickCount
                + FAILED_FARM_RETURN_RETRY_TICKS
                + this.playerNpc.getRandom().nextInt(FAILED_FARM_RETURN_RETRY_JITTER_TICKS + 1);
    }

    private boolean isFarmReturnOnCooldown(BlockPos planOrigin, BlockPos target) {
        if (planOrigin == null
                || target == null
                || this.failedFarmReturnPlanOrigin == null
                || this.failedFarmReturnTarget == null
                || !this.failedFarmReturnPlanOrigin.equals(planOrigin)
                || !this.failedFarmReturnTarget.equals(target)) {
            return false;
        }
        if (this.playerNpc.tickCount >= this.failedFarmReturnRetryAfterTick) {
            this.clearFarmReturnRetry();
            return false;
        }
        return true;
    }

    private void clearFarmReturnRetry() {
        this.failedFarmReturnPlanOrigin = null;
        this.failedFarmReturnTarget = null;
        this.failedFarmReturnRetryAfterTick = 0;
    }

    private void markClearRetry(BlockPos pos) {
        if (pos != null) {
            this.clearRetryAfter.put(pos.immutable(), this.playerNpc.tickCount + CLEAR_RETRY_COOLDOWN_TICKS);
        }
    }

    private boolean isClearOnCooldown(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Integer retryAfter = this.clearRetryAfter.get(pos);
        if (retryAfter == null) {
            return false;
        }
        if (this.playerNpc.tickCount >= retryAfter) {
            this.clearRetryAfter.remove(pos);
            return false;
        }
        return true;
    }

    private void markPlacementRetry(BlockPos pos) {
        if (pos != null) {
            this.placementRetryAfter.put(
                    pos.immutable(),
                    this.playerNpc.tickCount + PLACEMENT_RETRY_COOLDOWN_TICKS
            );
        }
    }

    private boolean isPlacementOnCooldown(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Integer retryAfter = this.placementRetryAfter.get(pos);
        if (retryAfter == null) {
            return false;
        }
        if (this.playerNpc.tickCount >= retryAfter) {
            this.placementRetryAfter.remove(pos);
            return false;
        }
        return true;
    }

    private boolean isSafeClearTarget(ServerLevel serverLevel, BlockPos pos) {
        boolean irrigationGround = pos != null
                && pos.equals(this.plan.waterPos())
                && !FarmAi.hasIrrigationWater(serverLevel, this.plan);
        BlockState state = pos == null ? Blocks.AIR.defaultBlockState() : serverLevel.getBlockState(pos);
        boolean replacedFarmGround = pos != null
                && this.plan.containsGround(pos)
                && !pos.equals(this.plan.waterPos())
                && !FarmAi.isTillableGround(state);
        if (pos == null
                || !FarmAi.isWithinWorkBounds(this.plan, pos)
                || pos.getY() <= this.plan.origin().getY() && !irrigationGround && !replacedFarmGround
                || pos.equals(this.plan.waterPos()) && FarmAi.hasIrrigationWater(serverLevel, this.plan)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isGrowingCrop(serverLevel, this.plan, pos)) {
            return false;
        }
        if (this.plan.containsGround(pos) && FarmAi.isTillableGround(state) && !irrigationGround) {
            return false;
        }
        boolean expectedFence = this.plan.isFencePosition(pos)
                && state.getBlock() instanceof FenceBlock;
        boolean expectedGate = pos.equals(this.plan.gatePos())
                && state.getBlock() instanceof FenceGateBlock;
        if (expectedFence || expectedGate) {
            return false;
        }
        boolean misplacedGateFence = pos.equals(this.plan.gatePos())
                && state.getBlock() instanceof FenceBlock;
        return (misplacedGateFence || this.isFarmObstructionState(state))
                && serverLevel.getBlockEntity(pos) == null
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F;
    }

    private boolean isActiveClearObstructionState(BlockState state) {
        return this.isFarmObstructionState(state)
                || this.targetPos != null
                && this.targetPos.equals(this.plan.gatePos())
                && state != null
                && state.getBlock() instanceof FenceBlock;
    }

    private boolean isFarmObstructionState(BlockState state) {
        return state != null
                && !state.isAir()
                && !(state.getBlock() instanceof CropBlock)
                && !state.is(Blocks.WATER);
    }

    private BlockPos findNearestMaintenanceObstruction(ServerLevel serverLevel) {
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> fencePositions = this.plan.fencePositions();
        candidates.addAll(this.plan.allGroundPositions());
        candidates.addAll(this.plan.cropPositions());
        candidates.addAll(this.plan.cropPositions().stream().map(BlockPos::above).toList());
        candidates.addAll(this.plan.pathPositions().stream().map(BlockPos::above).toList());
        candidates.addAll(this.plan.pathPositions().stream().map(pos -> pos.above(2)).toList());
        candidates.addAll(fencePositions);
        candidates.add(this.plan.gatePos());
        candidates.add(this.plan.gatePos().above());
        candidates.sort(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr));
        for (BlockPos candidate : candidates) {
            BlockState state = serverLevel.getBlockState(candidate);
            boolean expectedFence = fencePositions.contains(candidate)
                    && state.getBlock() instanceof FenceBlock;
            boolean expectedGate = candidate.equals(this.plan.gatePos())
                    && state.getBlock() instanceof FenceGateBlock;
            if (expectedFence || expectedGate || !this.isSafeClearTarget(serverLevel, candidate)) {
                continue;
            }
            return candidate.immutable();
        }
        return null;
    }

    private BlockPos findMissingFence(ServerLevel serverLevel) {
        return this.plan.fencePositions().stream()
                .filter(pos -> !this.isPlacementOnCooldown(pos))
                .filter(pos -> {
                    BlockState state = serverLevel.getBlockState(pos);
                    return !(state.getBlock() instanceof FenceBlock);
                })
                .min(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    private BlockPos findAnyMissingFence(ServerLevel serverLevel) {
        return this.plan.fencePositions().stream()
                .filter(pos -> {
                    BlockState state = serverLevel.getBlockState(pos);
                    return !(state.getBlock() instanceof FenceBlock);
                })
                .min(Comparator.comparingInt(this::placementRetryTicks)
                        .thenComparingDouble(this.playerNpc.blockPosition()::distSqr))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    private int placementRetryTicks(BlockPos pos) {
        if (pos == null) {
            return 0;
        }
        Integer retryAfter = this.placementRetryAfter.get(pos);
        return retryAfter == null ? 0 : Math.max(0, retryAfter - this.playerNpc.tickCount);
    }

    private BlockPos findUntilledGround(ServerLevel serverLevel) {
        List<BlockPos> cropGround = this.plan.cropGroundPositions();
        if (cropGround.isEmpty()) {
            this.untilledGroundCursor = 0;
            return null;
        }
        int start = Math.floorMod(this.untilledGroundCursor, cropGround.size());
        for (int offset = 0; offset < cropGround.size(); offset++) {
            int index = (start + offset) % cropGround.size();
            BlockPos ground = cropGround.get(index);
            BlockState cropState = serverLevel.getBlockState(ground.above());
            if (cropState.getBlock() instanceof CropBlock) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(ground);
            if (!state.is(Blocks.FARMLAND) && FarmAi.isTillableGround(state)) {
                this.untilledGroundCursor = (index + 1) % cropGround.size();
                return ground.immutable();
            }
        }
        this.untilledGroundCursor = 0;
        return null;
    }

    private BlockPos findNearestRepairableGround(ServerLevel serverLevel) {
        return this.plan.allGroundPositions().stream()
                .filter(pos -> !pos.equals(this.plan.waterPos()))
                .filter(pos -> !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos))
                .filter(pos -> !(serverLevel.getBlockState(pos.above()).getBlock() instanceof CropBlock))
                .filter(pos -> this.isRepairableGroundState(serverLevel.getBlockState(pos)))
                .min(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    private boolean isRepairableGroundState(BlockState state) {
        if (state == null || FarmAi.isTillableGround(state)) {
            return false;
        }
        return state.isAir()
                || state.canBeReplaced()
                || state.getFluidState().is(FluidTags.WATER);
    }

    private boolean hasGroundRepairMaterial() {
        return InventoryUtils.hasItem(this.playerNpc, FarmSetupGoal::isGroundRepairItem);
    }

    private RepairDirtSearchResult selectRepairDirtGatheringAction(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.repairDirtSearchOrigin == null
                || this.repairDirtSearchOrigin.distSqr(feet) > REPAIR_DIRT_SEARCH_RESET_DISTANCE_SQR) {
            this.repairDirtSearchOrigin = feet.immutable();
            this.repairDirtSearchColumnCursor = 0;
        }

        List<BlockPos> candidates = new ArrayList<>();
        int endCursor = Math.min(
                REPAIR_DIRT_COLUMN_OFFSETS.size(),
                this.repairDirtSearchColumnCursor + MAX_REPAIR_DIRT_COLUMNS_PER_PASS
        );
        for (int index = this.repairDirtSearchColumnCursor; index < endCursor; index++) {
            ColumnOffset offset = REPAIR_DIRT_COLUMN_OFFSETS.get(index);
            int x = this.repairDirtSearchOrigin.getX() + offset.dx();
            int z = this.repairDirtSearchOrigin.getZ() + offset.dz();
            if (!serverLevel.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos candidate = new BlockPos(x, y, z);
            if (this.isSafeRepairDirtTarget(serverLevel, candidate)) {
                candidates.add(candidate);
            }
        }
        this.repairDirtSearchColumnCursor = endCursor;
        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        int checks = 0;
        for (BlockPos candidate : candidates) {
            if (checks++ >= MAX_REPAIR_DIRT_PATH_CHECKS
                    || this.activationPathBudget != null && this.activationPathBudget.exhausted()) {
                break;
            }
            BlockPos candidateStand = this.findInteractionStand(serverLevel, candidate);
            if (candidateStand == null) {
                continue;
            }
            this.action = Action.GATHER_REPAIR_DIRT;
            this.targetPos = candidate.immutable();
            this.standPos = candidateStand.immutable();
            this.resetRepairDirtSearch();
            return RepairDirtSearchResult.FOUND;
        }

        boolean complete = this.repairDirtSearchColumnCursor >= REPAIR_DIRT_COLUMN_OFFSETS.size();
        if (complete) {
            this.resetRepairDirtSearch();
        }
        return complete ? RepairDirtSearchResult.EXHAUSTED : RepairDirtSearchResult.PENDING;
    }

    private boolean isSafeRepairDirtTarget(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || !serverLevel.hasChunkAt(pos)
                || pos.equals(this.playerNpc.blockPosition().below())
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || serverLevel.getBlockEntity(pos) != null) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK);
    }

    private void resetRepairDirtSearch() {
        this.repairDirtSearchOrigin = null;
        this.repairDirtSearchColumnCursor = 0;
    }

    private static List<ColumnOffset> createRepairDirtColumnOffsets() {
        List<ColumnOffset> offsets = new ArrayList<>();
        for (int dx = -REPAIR_DIRT_SEARCH_RADIUS; dx <= REPAIR_DIRT_SEARCH_RADIUS; dx++) {
            for (int dz = -REPAIR_DIRT_SEARCH_RADIUS; dz <= REPAIR_DIRT_SEARCH_RADIUS; dz++) {
                if (dx * dx + dz * dz <= REPAIR_DIRT_SEARCH_RADIUS * REPAIR_DIRT_SEARCH_RADIUS) {
                    offsets.add(new ColumnOffset(dx, dz));
                }
            }
        }
        offsets.sort(Comparator
                .comparingInt((ColumnOffset offset) -> offset.dx() * offset.dx() + offset.dz() * offset.dz())
                .thenComparingInt(ColumnOffset::dx)
                .thenComparingInt(ColumnOffset::dz));
        return List.copyOf(offsets);
    }

    private void tickGatherRepairDirt(ServerLevel serverLevel) {
        if (!this.isSafeRepairDirtTarget(serverLevel, this.targetPos)) {
            this.finished = true;
            return;
        }
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                state -> state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK),
                12,
                "gathering dirt for farm repair"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }
        this.finished = true;
        if (result == BreakingBlockAi.TickResult.FAILED) {
            this.playerNpc.setIdleTraceDetail("farm ground repair dirt target failed @ "
                    + posText(this.targetPos), 40);
        }
    }

    private boolean repairGround(ServerLevel serverLevel) {
        if (this.targetPos == null
                || !this.plan.containsGround(this.targetPos)
                || this.targetPos.equals(this.plan.waterPos())
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, this.targetPos)
                || serverLevel.getBlockEntity(this.targetPos) != null
                || serverLevel.getBlockState(this.targetPos.above()).getBlock() instanceof CropBlock
                || !this.isRepairableGroundState(serverLevel.getBlockState(this.targetPos))) {
            return false;
        }
        ItemStack material = this.playerNpc.consumeInventoryItem(FarmSetupGoal::isGroundRepairItem, 1)
                .orElse(ItemStack.EMPTY);
        if (material.isEmpty() || !(material.getItem() instanceof BlockItem blockItem)) {
            this.returnStack(material);
            return false;
        }
        BlockState repairState = blockItem.getBlock().defaultBlockState();
        BlockState onePassTilledState = FarmAi.tilledState(repairState);
        if (onePassTilledState == null || !onePassTilledState.is(Blocks.FARMLAND)) {
            repairState = Blocks.DIRT.defaultBlockState();
        }
        if (!FarmAi.isTillableGround(repairState)
                || !this.placingBlockAi.canPlaceWithoutClipping(serverLevel, this.targetPos, repairState)) {
            this.returnStack(material);
            return false;
        }
        this.showActionItem(material);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.targetPos, repairState)) {
            this.returnStack(material);
            return false;
        }
        boolean cropGround = this.plan.cropGroundPositions().contains(this.targetPos);
        if (cropGround) {
            if (this.plan.phase() == Phase.READY) {
                this.plan = FarmAi.advancePhase(this.playerNpc, this.plan, Phase.TILL);
            }
            this.playerNpc.setIdleTraceDetail("farm ground repaired; awaiting till @ "
                    + posText(this.targetPos), 60);
        } else {
            this.playerNpc.setIdleTraceDetail("farm ground repaired; path remains untilled @ "
                    + posText(this.targetPos), 60);
        }
        return true;
    }

    private static boolean isGroundRepairItem(ItemStack stack) {
        return stack != null
                && stack.getItem() instanceof BlockItem blockItem
                && FarmAi.isTillableGround(blockItem.getBlock().defaultBlockState());
    }

    private boolean placeWater(ServerLevel serverLevel) {
        if (!serverLevel.getBlockState(this.targetPos).isAir()) {
            return false;
        }
        ItemStack bucket = this.playerNpc.consumeInventoryItem(Items.WATER_BUCKET, 1).orElse(ItemStack.EMPTY);
        if (bucket.isEmpty() || !serverLevel.setBlockAndUpdate(this.targetPos, Blocks.WATER.defaultBlockState())) {
            this.returnStack(bucket);
            return false;
        }
        this.showActionItem(bucket);
        this.placingBlockAi.playMainHandAction();
        serverLevel.playSound(null, this.targetPos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        this.returnStack(new ItemStack(Items.BUCKET));
        return true;
    }

    private boolean placeFence(ServerLevel serverLevel) {
        ItemStack fence = this.consumeFence();
        if (fence.isEmpty()
                || !(fence.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof FenceBlock)
                || !serverLevel.getBlockState(this.targetPos).canBeReplaced()
                || !serverLevel.getBlockState(this.targetPos.below()).isSolidRender()) {
            this.returnStack(fence);
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        this.showActionItem(fence);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.targetPos, state)) {
            this.returnStack(fence);
            return false;
        }
        return true;
    }

    private boolean placeGate(ServerLevel serverLevel) {
        Direction facing = this.gateFacing();
        if (facing == null || !this.gateSidesReady(serverLevel)) {
            return false;
        }
        ItemStack gate = this.consumeGate();
        if (gate.isEmpty()
                || !(gate.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof FenceGateBlock gateBlock)
                || !serverLevel.getBlockState(this.targetPos).canBeReplaced()
                || !serverLevel.getBlockState(this.targetPos.below()).isSolidRender()) {
            this.returnStack(gate);
            return false;
        }
        BlockState state = gateBlock.defaultBlockState()
                .setValue(FenceGateBlock.FACING, facing)
                .setValue(FenceGateBlock.OPEN, true);
        this.showActionItem(gate);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.targetPos, state)) {
            this.returnStack(gate);
            return false;
        }
        return true;
    }

    private boolean repairGate(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.plan.gatePos());
        Direction facing = this.gateFacing();
        if (!(state.getBlock() instanceof FenceGateBlock)
                || facing == null
                || !this.gateSidesReady(serverLevel)
                || !serverLevel.getBlockState(this.plan.gatePos().below())
                .isSolidRender()) {
            return false;
        }
        BlockState repaired = state
                .setValue(FenceGateBlock.FACING, facing)
                .setValue(FenceGateBlock.OPEN, true);
        if (repaired.equals(state) || !serverLevel.setBlockAndUpdate(this.plan.gatePos(), repaired)) {
            return repaired.equals(state);
        }
        this.placingBlockAi.playMainHandAction();
        serverLevel.playSound(null, this.plan.gatePos(), SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private boolean tillGround(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!FarmAi.isTillableGround(state)
                || state.is(Blocks.FARMLAND)
                || serverLevel.getBlockState(this.targetPos.above()).getBlock() instanceof CropBlock
                || !this.toolAi.equipTool(HoeItem.class)) {
            return false;
        }
        BlockState tilled = FarmAi.tilledState(state);
        if (tilled == null || !serverLevel.setBlockAndUpdate(this.targetPos, tilled)) {
            return false;
        }
        this.placingBlockAi.playMainHandAction();
        this.playerNpc.hurtMainHandItem(1);
        serverLevel.playSound(null, this.targetPos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.9F, 1.0F);
        return true;
    }

    private boolean openGate(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!(state.getBlock() instanceof FenceGateBlock)
                || !state.hasProperty(FenceGateBlock.OPEN)) {
            return false;
        }
        if (!state.getValue(FenceGateBlock.OPEN)) {
            serverLevel.setBlockAndUpdate(this.targetPos, state.setValue(FenceGateBlock.OPEN, true));
            this.placingBlockAi.playMainHandAction();
            serverLevel.playSound(null, this.targetPos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        // Opening the perimeter changes reachability for every interior maintenance
        // target, so failures recorded while it was closed are no longer meaningful.
        this.clearRetryAfter.clear();
        return true;
    }

    private boolean canProvideFence() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof FenceBlock);
    }

    private boolean canProvideGate() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof FenceGateBlock);
    }

    private ItemStack consumeFence() {
        ItemStack stack = this.playerNpc.consumeInventoryItem(item -> item.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof FenceBlock, 1).orElse(ItemStack.EMPTY);
        if (!stack.isEmpty()) {
            return stack;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeGate() {
        ItemStack stack = this.playerNpc.consumeInventoryItem(item -> item.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof FenceGateBlock, 1).orElse(ItemStack.EMPTY);
        if (!stack.isEmpty()) {
            return stack;
        }
        return ItemStack.EMPTY;
    }

    private BlockPos findInteractionStand(ServerLevel serverLevel, BlockPos target) {
        return this.findInteractionStand(serverLevel, target, false);
    }

    private BlockPos findInteractionStand(ServerLevel serverLevel, BlockPos target, boolean avoidStandingAboveTarget) {
        this.plannedStandPath = null;
        if (target == null) {
            return null;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        boolean selectingWaterExit = this.isInWater(serverLevel);
        if (!selectingWaterExit
                && this.isInInteractionRange(target)
                && (!avoidStandingAboveTarget || !feet.equals(target.above()))) {
            return feet.immutable();
        }
        List<BlockPos> candidates = this.interactionStandCandidates(target);
        candidates.sort(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr));
        NavigationPathBudget pathBudget = this.pathBudgetForSelection();
        int start = candidates.isEmpty() ? 0 : Math.floorMod(this.interactionStandCursor, candidates.size());
        for (int offset = 0; offset < candidates.size(); offset++) {
            int index = (start + offset) % candidates.size();
            BlockPos candidate = candidates.get(index);
            if (candidate.equals(feet)
                    || avoidStandingAboveTarget && candidate.equals(target.above())
                    || !this.canOccupyPlacementStand(serverLevel, candidate)
                    || distanceFromStandToTargetSqr(candidate, target) > INTERACTION_DISTANCE_SQR) {
                continue;
            }
            this.interactionStandCursor = (index + 1) % candidates.size();
            if (selectingWaterExit) {
                // Destination-aware water recovery owns admission from the wet start;
                // ordinary ground navigation resumes once this dry stand is reached.
                return candidate.immutable();
            }
            if (!pathBudget.tryConsume()) {
                break;
            }
            Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
            if (path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(candidate)) {
                this.plannedStandPath = path;
                return candidate.immutable();
            }
        }
        return null;
    }

    private BlockPos findFarmClearApproachStand(ServerLevel serverLevel, BlockPos workTarget) {
        this.plannedStandPath = null;
        if (workTarget == null
                || this.failedFarmClearApproachStands.size() >= MAX_FARM_CLEAR_APPROACH_STANDS
                || !this.isSafeClearTarget(serverLevel, workTarget)) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = this.interactionStandCandidates(workTarget);
        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        boolean selectingWaterExit = this.isInWater(serverLevel);
        NavigationPathBudget pathBudget = this.pathBudgetForSelection();
        for (BlockPos candidate : candidates) {
            if (this.failedFarmClearApproachStands.contains(candidate)
                    || !this.canOccupyPlacementStand(serverLevel, candidate)
                    || distanceFromStandToTargetSqr(candidate, workTarget) > INTERACTION_DISTANCE_SQR
                    || this.resolveSafeFarmClearTargetFromStand(serverLevel, candidate, workTarget) == null) {
                continue;
            }
            if (candidate.equals(feet)) {
                return candidate.immutable();
            }
            if (selectingWaterExit) {
                // Ground navigation may not admit a path whose start is the irrigation
                // water cell. WaterEscapeAi can still swim/step to this already-validated
                // dry stand, so retain it as the goal's recovery destination.
                return candidate.immutable();
            }
            if (!pathBudget.tryConsume()) {
                break;
            }
            Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
            if (path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(candidate)) {
                this.plannedStandPath = path;
                return candidate.immutable();
            }
        }
        return null;
    }

    private NavigationPathBudget pathBudgetForSelection() {
        return this.activationPathBudget == null
                ? new NavigationPathBudget(MAX_PLACEMENT_STAND_PATH_CHECKS)
                : this.activationPathBudget;
    }

    private BlockPos resolveSafeFarmClearTargetFromStand(
            ServerLevel serverLevel,
            BlockPos stand,
            BlockPos requestedTarget
    ) {
        Vec3 eye = new Vec3(
                stand.getX() + 0.5D,
                stand.getY() + FARM_CLEAR_STAND_EYE_HEIGHT,
                stand.getZ() + 0.5D
        );
        BlockHitResult hit = serverLevel.clip(new ClipContext(
                eye,
                Vec3.atCenterOf(requestedTarget),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()
        ));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos resolvedTarget = hit.getBlockPos();
        return this.isSafeClearTarget(serverLevel, resolvedTarget)
                ? resolvedTarget.immutable()
                : null;
    }

    private BlockPos findPotentialInteractionStand(
            ServerLevel serverLevel,
            BlockPos target,
            boolean avoidStandingAboveTarget
    ) {
        if (target == null) {
            return null;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = this.interactionStandCandidates(target);
        candidates.sort(Comparator
                .comparingDouble((BlockPos candidate) -> feet.distSqr(candidate))
                .thenComparingDouble(candidate -> candidate.distSqr(this.plan.gatePos())));
        for (BlockPos candidate : candidates) {
            if (!candidate.equals(feet)
                    && (!avoidStandingAboveTarget || !candidate.equals(target.above()))
                    && this.canOccupyPlacementStand(serverLevel, candidate)
                    && distanceFromStandToTargetSqr(candidate, target) <= INTERACTION_DISTANCE_SQR) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private List<BlockPos> interactionStandCandidates(BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = target.offset(dx, dy, dz);
                        if (distanceFromStandToTargetSqr(candidate, target) <= INTERACTION_DISTANCE_SQR) {
                            candidates.add(candidate.immutable());
                        }
                    }
                }
            }
        }
        return new ArrayList<>(candidates.stream().distinct().toList());
    }

    private boolean canOccupyPlacementStand(ServerLevel serverLevel, BlockPos pos) {
        return pos != null
                && serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && !serverLevel.getBlockState(pos.below()).getCollisionShape(serverLevel, pos.below()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private boolean moveToStand(ServerLevel serverLevel) {
        if (serverLevel == null || this.standPos == null) {
            return false;
        }
        Path selectedPath = this.plannedStandPath;
        this.plannedStandPath = null;
        if (isExactPathTo(selectedPath, this.standPos)) {
            return this.playerNpc.getNavigation().moveTo(selectedPath, 1.0D);
        }
        return this.pathNavigationAi.moveToExact(serverLevel, this.standPos, 1.0D, 0);
    }

    private boolean tickWaterRecovery(ServerLevel serverLevel) {
        boolean inWater = this.isInWater(serverLevel);
        BlockPos destination = this.standPos == null ? this.targetPos : this.standPos;

        if (!this.usingLocalWaterRecovery
                && this.pathNavigationAi.tickWaterTravel(serverLevel, destination, 1.0D)) {
            return true;
        }
        if (!inWater) {
            this.usingLocalWaterRecovery = false;
            return false;
        }

        this.usingLocalWaterRecovery = true;
        if (this.pathNavigationAi.tickLocalWaterEscape(serverLevel, 1.0D)) {
            return true;
        }

        // Both destination and local recovery are bounded internally. Yield this setup
        // episode when neither can start so other worker/safety goals get a scheduling turn.
        this.playerNpc.setCurrentAiDetail(this.describeAction() + " (water recovery unavailable; yielding)");
        this.finished = true;
        return true;
    }

    private boolean isInWater(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isInWater()
                || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER)
                || !this.playerNpc.onGround() && serverLevel.getFluidState(feet.below()).is(FluidTags.WATER);
    }

    private static boolean isExactPathTo(Path path, BlockPos target) {
        return path != null
                && target != null
                && path.canReach()
                && path.getEndNode() != null
                && path.getEndNode().asBlockPos().equals(target);
    }

    private static String posText(BlockPos pos) {
        return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static double distanceFromStandToTargetSqr(BlockPos stand, BlockPos target) {
        double dx = stand.getX() + 0.5D - (target.getX() + 0.5D);
        double dy = stand.getY() - (target.getY() + 0.5D);
        double dz = stand.getZ() + 0.5D - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean isInInteractionRange() {
        return this.isInInteractionRange(this.targetPos);
    }

    private boolean isInInteractionRange(BlockPos target) {
        return target != null && this.playerNpc.distanceToSqr(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D
        ) <= INTERACTION_DISTANCE_SQR;
    }

    private boolean isReadyAtInteractionStand() {
        return this.standPos != null
                && this.isOccupyingFarmStand(this.standPos, FARM_STAND_REACHED_SQR)
                && this.isInInteractionRange();
    }

    /**
     * Logical path stands are the air cell above their support. Farmland and other
     * non-full support shapes leave a grounded entity's physical Y slightly below
     * that cell, so {@code blockPosition()} can be {@code stand.below()} even when
     * X/Z are centered and navigation reports the exact stand reached.
     */
    private boolean isOccupyingFarmStand(BlockPos stand, double horizontalToleranceSqr) {
        if (stand == null || !this.playerNpc.onGround()) {
            return false;
        }
        double dx = this.playerNpc.getX() - (stand.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (stand.getZ() + 0.5D);
        if (dx * dx + dz * dz > horizontalToleranceSqr) {
            return false;
        }
        BlockPos physicalFeet = this.playerNpc.blockPosition();
        if (physicalFeet.equals(stand)) {
            return true;
        }
        double supportOffset = stand.getY() - this.playerNpc.getY();
        return physicalFeet.equals(stand.below())
                && supportOffset >= 0.0D
                && supportOffset <= MAX_NON_FULL_SUPPORT_STAND_OFFSET;
    }

    private boolean isPlacementAction() {
        return isPlacementAction(this.action);
    }

    private boolean isFarmReturnAction() {
        return this.action == Action.RETURN_TO_FARM
                || this.action == Action.APPROACH_FARM_WORK
                || this.action == Action.APPROACH_PLACEMENT_WORK;
    }

    private static boolean isPlacementAction(Action action) {
        return action == Action.PLACE_WATER
                || action == Action.PLACE_FENCE
                || action == Action.PLACE_GATE
                || action == Action.REPAIR_GATE
                || action == Action.REPAIR_GROUND;
    }

    private void clearPendingPlacementRecovery() {
        this.pendingPlacementAction = Action.NONE;
        this.pendingPlacementTargetPos = null;
        this.pendingPlacementStandPos = null;
        this.placementRecoveryAttempts = 0;
    }

    private void lookAtTarget() {
        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
    }

    private Direction gateFacing() {
        return FarmAi.expectedGateFacing(this.plan);
    }

    private boolean gateSidesReady(ServerLevel serverLevel) {
        Direction facing = this.gateFacing();
        if (facing == null) {
            return false;
        }
        BlockPos firstSide = this.plan.gatePos().relative(facing.getClockWise());
        BlockPos secondSide = this.plan.gatePos().relative(facing.getCounterClockWise());
        return (!this.plan.isFencePosition(firstSide)
                || serverLevel.getBlockState(firstSide).getBlock() instanceof FenceBlock)
                && (!this.plan.isFencePosition(secondSide)
                || serverLevel.getBlockState(secondSide).getBlock() instanceof FenceBlock);
    }

    private void showActionItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!this.showingActionItem) {
            this.previousMainHand = this.playerNpc.getMainHandItem().copy();
            this.showingActionItem = true;
        }
        ItemStack held = stack.copy();
        held.setCount(1);
        this.playerNpc.setMainHandItemForAi(held);
    }

    private void restoreActionItem() {
        if (!this.showingActionItem) {
            return;
        }
        this.playerNpc.setMainHandItemForAi(this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.showingActionItem = false;
    }

    private void returnStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remainder = InventoryUtils.addItemAndReturnRemainder(this.playerNpc, stack);
        if (!remainder.isEmpty()) {
            this.playerNpc.spawnAtLocation(remainder);
        }
    }

    private void updateDetail() {
        String suffix = this.targetPos == null
                ? ""
                : " @ " + this.targetPos.getX() + " " + this.targetPos.getY() + " " + this.targetPos.getZ();
        this.playerNpc.setCurrentAiDetail(this.describeAction() + suffix);
    }

    private String describeAction() {
        return switch (this.action) {
            case RETURN_TO_FARM -> "returning to claimed farm";
            case APPROACH_FARM_WORK -> "approaching farm work area";
            case APPROACH_PLACEMENT_WORK -> "approaching farm placement area";
            case CLEAR -> "clearing farm obstruction";
            case PLACE_WATER -> "placing farm irrigation";
            case PLACE_FENCE -> "placing farm fence";
            case PLACE_GATE -> "placing farm gate";
            case REPAIR_GATE -> "repairing farm gate";
            case REPAIR_GROUND -> "repairing farm ground";
            case GATHER_REPAIR_DIRT -> "gathering dirt for farm repair";
            case TILL -> "tilling farm soil";
            case OPEN_GATE -> "opening farm gate";
            default -> "setting up farm";
        };
    }

    private void resetPlanState() {
        this.plan = null;
        this.targetPos = null;
        this.standPos = null;
        this.clearRequestedTargetPos = null;
        this.clearResolvedTargetPos = null;
        this.pendingPlacementTargetPos = null;
        this.pendingPlacementStandPos = null;
        this.lastApproachPos = null;
        this.action = Action.NONE;
        this.pendingPlacementAction = Action.NONE;
        this.actionTicks = 0;
        this.repathTicks = 0;
        this.routeFailureTicks = 0;
        this.routeClearRetryTicks = 0;
        this.placementRecoveryAttempts = 0;
        this.failedFarmClearApproachStands.clear();
        this.finished = false;
        this.activationPathBudget = null;
        this.plannedStandPath = null;
        this.usingLocalWaterRecovery = false;
    }

    private ServerLevel serverLevel() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    private static void addRouteCandidates(List<BlockPos> candidates, BlockPos start, BlockPos target, int maxSteps) {
        if (start == null || target == null) {
            return;
        }
        double dx = target.getX() - start.getX();
        double dy = target.getY() - start.getY();
        double dz = target.getZ() - start.getZ();
        int steps = Math.max(1, Math.min(maxSteps, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)))));
        for (int index = 1; index <= steps; index++) {
            double progress = index / (double) steps;
            BlockPos feet = new BlockPos(
                    start.getX() + (int) Math.round(dx * progress),
                    start.getY() + (int) Math.round(dy * progress),
                    start.getZ() + (int) Math.round(dz * progress)
            );
            candidates.add(feet);
            candidates.add(feet.above());
        }
    }

    private enum Action {
        NONE,
        RETURN_TO_FARM,
        APPROACH_FARM_WORK,
        APPROACH_PLACEMENT_WORK,
        CLEAR,
        PLACE_WATER,
        PLACE_FENCE,
        PLACE_GATE,
        REPAIR_GATE,
        REPAIR_GROUND,
        GATHER_REPAIR_DIRT,
        TILL,
        OPEN_GATE
    }

    private enum RepairDirtSearchResult {
        FOUND,
        PENDING,
        EXHAUSTED
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
}
