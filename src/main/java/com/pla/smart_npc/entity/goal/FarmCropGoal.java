package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Crop operation/foraging goal. Persistent geometry and setup belong to {@link FarmAi}. */
public final class FarmCropGoal extends Goal {
    private static final int LOCAL_SUPPLY_RADIUS = 14;
    private static final int LOCAL_SUPPLY_Y_RANGE = 3;
    private static final int MAX_LOCAL_PATH_CHECKS = 1;
    private static final int MAX_OWNED_CROP_PATH_CHECKS = 1;
    // Crop selection admits only one path per throttled goal probe, and createBoundedPath still
    // enforces a loaded corridor and a 48-block ceiling. Use the normal node allowance for that
    // one route: a farmer returning from daytime work can be 30-40 blocks from its owned plot,
    // where the former 0.05 allowance exhausted before reaching every otherwise valid stand.
    private static final float FARM_PATH_NODE_MULTIPLIER = 1.0F;
    private static final int MAX_ACTION_TICKS = 20 * 20;
    private static final int USE_ACTION_TICKS = 9;
    private static final int CLEAR_TICKS = 18;
    private static final int QUICK_COOLDOWN_TICKS = 6;
    private static final int FAILED_PLANT_RETRY_TICKS = 20;
    private static final int IDLE_COOLDOWN_TICKS = 20 * 8;
    private static final double INTERACTION_DISTANCE_SQR = 3.75D * 3.75D;
    private static final double CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final Map<PlayerNpcEntity, SupplyTargetCache> SUPPLY_TARGET_CACHE = new WeakHashMap<>();

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final PlacingBlockAi placingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();

    private Plan plan;
    private BlockPos targetPos;
    private BlockPos standPos;
    private int[] selectionPathBudget;
    private PlantingCrop plantingCrop;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private Action action = Action.NONE;
    private int actionTicks;
    private int useTicks;
    private int repathTicks;
    private int routeFailureTicks;
    private int ownedMatureCropCursor;
    private int localCropCursor;
    private int localForageCursor;
    private int boneMealCropCursor;
    private BlockPos lastApproachPos;
    private boolean showingActionItem;
    private boolean finished;
    private boolean completedAction;

    public FarmCropGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean shouldExploreForFarmSupplies(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !FarmAi.isFarmingJobActive(playerNpc)
                || !FarmAi.isReadyForCropWork(playerNpc, serverLevel)
                || GatherLogsGoal.isLogGatheringEpisodeActive(playerNpc)
                || serverLevel.isDarkOutside()
                || serverLevel.isThundering()) {
            return false;
        }
        return needsPlantingSupplies(playerNpc, serverLevel);
    }

    private static boolean needsPlantingSupplies(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !FarmAi.isReadyForCropWork(playerNpc, serverLevel)
                || FarmAi.hasFarmSeedOrCrop(playerNpc)) {
            return false;
        }
        Plan plan = FarmAi.getPlan(playerNpc, serverLevel).orElse(null);
        return plan != null
                && hasEmptyCropPosition(serverLevel, plan);
    }

    /**
     * Returns whether the ready owned farm has crop work that can be done with the
     * NPC's current inventory.  This deliberately ignores the farm cooldown: idle
     * behaviors must not take ownership of movement while actionable crop cells are
     * only waiting for their short retry delay.
     */
    public static boolean hasActionableOwnedFarmWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !FarmAi.isFarmingJobActive(playerNpc)
                || !FarmAi.isReadyForCropWork(playerNpc, serverLevel)) {
            return false;
        }
        Plan plan = FarmAi.getPlan(playerNpc, serverLevel).orElse(null);
        if (plan == null) {
            return false;
        }
        if (plan.cropPositions().stream().anyMatch(pos -> FarmAi.isHarvestableCrop(serverLevel.getBlockState(pos)))) {
            return true;
        }
        if (hasPlantingItem(playerNpc) && hasEmptyCropPosition(serverLevel, plan)) {
            return true;
        }
        boolean canUseBoneMeal = InventoryUtils.hasItem(playerNpc, Items.BONE_MEAL)
                || PlayerNpcCraftingUtil.canCraftBoneMeal(playerNpc.getInventory());
        return canUseBoneMeal && plan.cropPositions().stream().anyMatch(pos -> {
            BlockState state = serverLevel.getBlockState(pos);
            return state.getBlock() instanceof CropBlock crop
                    && !crop.isMaxAge(state)
                    && state.getBlock() instanceof BonemealableBlock bonemealable
                    && bonemealable.isValidBonemealTarget(serverLevel, pos, state);
        });
    }

    public static boolean isFullyPlantedOwnedFarm(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !FarmAi.isReadyForCropWork(playerNpc, serverLevel)) {
            return false;
        }
        Plan plan = FarmAi.getPlan(playerNpc, serverLevel).orElse(null);
        return plan != null
                && !plan.cropPositions().isEmpty()
                && plan.cropPositions().stream()
                .allMatch(pos -> serverLevel.getBlockState(pos).getBlock() instanceof CropBlock);
    }

    public static boolean hasNearbyFarmSupplyTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !FarmAi.isReadyForCropWork(playerNpc, serverLevel)) {
            return false;
        }
        BlockPos center = playerNpc.blockPosition();
        SupplyTargetCache cached = SUPPLY_TARGET_CACHE.get(playerNpc);
        if (cached != null && cached.matches(playerNpc.tickCount, serverLevel.dimension().identifier(), center)) {
            return cached.actionable();
        }
        boolean actionable = hasActionableNearbySupplyTarget(playerNpc, serverLevel, center);
        SUPPLY_TARGET_CACHE.put(playerNpc, new SupplyTargetCache(
                playerNpc.tickCount,
                serverLevel.dimension().identifier(),
                center.immutable(),
                actionable
        ));
        return actionable;
    }

    private static boolean hasActionableNearbySupplyTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos center) {
        Plan plan = FarmAi.getPlan(playerNpc, serverLevel).orElse(null);
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(
                center.offset(-LOCAL_SUPPLY_RADIUS, -LOCAL_SUPPLY_Y_RANGE, -LOCAL_SUPPLY_RADIUS),
                center.offset(LOCAL_SUPPLY_RADIUS, LOCAL_SUPPLY_Y_RANGE, LOCAL_SUPPLY_RADIUS))) {
            BlockState state = serverLevel.getBlockState(cursor);
            boolean ownedMatureCrop = plan != null
                    && plan.owns(cursor)
                    && FarmAi.isHarvestableCrop(state);
            boolean externalSupply = (plan == null || !plan.owns(cursor))
                    && !PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, cursor)
                    && (FarmAi.isForagePlant(state) || FarmAi.isHarvestableCrop(state));
            if (ownedMatureCrop || externalSupply) {
                candidates.add(cursor.immutable());
            }
        }
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        int[] remainingPathChecks = {MAX_LOCAL_PATH_CHECKS};
        for (BlockPos candidate : candidates) {
            if (hasReachableInteractionStand(playerNpc, serverLevel, candidate, remainingPathChecks)) {
                return true;
            }
            if (remainingPathChecks[0] <= 0) {
                break;
            }
        }
        return false;
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
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.playerNpc.getFarmCooldown() > 0
                || serverLevel.isDarkOutside()
                || serverLevel.isThundering()) {
            return false;
        }
        // Readiness and planting-demand checks walk the owned plot. Keep them behind the same
        // staggered activation cadence as target selection instead of repeating on every goal
        // selector pass while several farmers are idle or exploring for supplies.
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        boolean activeLogRoute = GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc);
        boolean deferredForLogs = !activeLogRoute
                && GatherLogsGoal.shouldDeferFarmCropWork(this.playerNpc, serverLevel);
        // The planting-supply inventory/plot walk is only relevant to the log-defer exception.
        // Avoid paying for it on every ordinary FarmCrop canUse probe.
        boolean deferFarmWork = deferredForLogs
                && !hasActionableOwnedFarmWork(this.playerNpc, serverLevel)
                && !needsPlantingSupplies(this.playerNpc, serverLevel);
        if (activeLogRoute || deferFarmWork) {
            this.playerNpc.setIdleTraceDetail(activeLogRoute
                    ? "farm crop deferred: active log gathering route"
                    : "farm crop deferred: required log supply", 40);
            return false;
        }
        this.resetPlan();
        if (!FarmAi.isReadyForCropWork(this.playerNpc, serverLevel)) {
            this.playerNpc.setIdleTraceDetail("farm crop deferred: setup repair/till pending", 40);
            return false;
        }
        this.plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (this.plan == null) {
            this.playerNpc.setIdleTraceDetail("farm crop blocked: READY plan unavailable", 40);
            return false;
        }
        this.selectionPathBudget = new int[]{1};
        if (this.selectAction(serverLevel)) {
            return true;
        }
        this.traceNoSelectedAction(serverLevel);
        return false;
    }

    private void traceNoSelectedAction(ServerLevel serverLevel) {
        if (hasEmptyCropPosition(serverLevel, this.plan)) {
            this.playerNpc.setIdleTraceDetail(
                    hasPlantingItem(this.playerNpc)
                            ? "farm planting blocked: no exact reachable crop stand"
                            : "farm waiting for planting supplies; exploring for seeds and crops",
                    40
            );
            return;
        }
        this.playerNpc.setIdleTraceDetail("farm crop idle: no actionable crop work", 40);
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.action != Action.NONE
                && this.actionTicks < MAX_ACTION_TICKS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && FarmAi.isReadyForCropWork(this.playerNpc, serverLevel);
    }

    @Override
    public void start() {
        this.actionTicks = 0;
        this.useTicks = 0;
        this.repathTicks = 0;
        this.routeFailureTicks = 0;
        this.lastApproachPos = this.playerNpc.blockPosition();
        this.finished = false;
        this.completedAction = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.farming");
        this.updateDetail();
        if (this.action != Action.CRAFT_BONE_MEAL) {
            this.moveToStand(serverLevel());
        }
        this.selectionPathBudget = null;
    }

    @Override
    public void tick() {
        ServerLevel serverLevel = serverLevel();
        if (serverLevel == null || this.plan == null) {
            this.finished = true;
            return;
        }
        this.actionTicks++;

        if (this.action == Action.CRAFT_BONE_MEAL) {
            this.completedAction = this.craftBoneMeal(serverLevel);
            this.finished = true;
            return;
        }
        if (this.targetPos == null) {
            this.finished = true;
            return;
        }

        this.lookAtTarget();
        if (this.action == Action.CLEAR_ENTRANCE) {
            this.tickEntranceClear(serverLevel);
            return;
        }
        if (!this.inInteractionRange()) {
            if (this.useTicks > 0) {
                this.useTicks = 0;
                this.restoreActionItem();
            }
            this.tickApproach(serverLevel);
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.routeFailureTicks = 0;
        switch (this.action) {
            case HARVEST -> this.tickBreak(serverLevel, true);
            case FORAGE -> this.tickBreak(serverLevel, false);
            case PLANT -> this.tickPlant(serverLevel);
            case BONE_MEAL -> this.tickBoneMeal(serverLevel);
            default -> this.finished = true;
        }
    }

    @Override
    public void stop() {
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restoreActionItem();
        // Epic Fight compatibility is disabled.
        this.playerNpc.getNavigation().stop();
        if (!this.playerNpc.level().isClientSide()) {
            int cooldown = this.completedAction
                    ? QUICK_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(8)
                    : this.action == Action.PLANT
                    ? FAILED_PLANT_RETRY_TICKS + this.playerNpc.getRandom().nextInt(FAILED_PLANT_RETRY_TICKS + 1)
                    : IDLE_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 4);
            this.playerNpc.setFarmCooldown(cooldown);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
    }

    private boolean selectAction(ServerLevel serverLevel) {
        BlockPos ownMature = this.findOwnMatureCrop(serverLevel);
        if (ownMature != null && this.selectWorldAction(serverLevel, Action.HARVEST, ownMature)) {
            return true;
        }

        this.plantingCrop = this.findPlantingCrop();
        WorldTarget plantingTarget = this.plantingCrop == null ? null : this.findReachablePlantingTarget(serverLevel);
        if (plantingTarget != null) {
            this.action = Action.PLANT;
            this.targetPos = plantingTarget.pos();
            this.standPos = plantingTarget.stand();
            return true;
        }
        // If no exact interaction stand was found, preserve the existing entrance
        // obstruction recovery for a genuinely blocked ready farm.
        BlockPos plantingPos = this.plantingCrop == null ? null : this.findPlantingPos(serverLevel);
        if (plantingPos != null && this.selectWorldAction(serverLevel, Action.PLANT, plantingPos)) {
            return true;
        }

        if (!FarmAi.hasFarmSeedOrCrop(this.playerNpc)) {
            BlockPos forage = this.findNearbyForage(serverLevel);
            if (forage != null && this.selectWorldAction(serverLevel, Action.FORAGE, forage)) {
                return true;
            }
        }

        BlockPos villageCrop = this.findNearbyMatureCrop(serverLevel);
        if (villageCrop != null && this.selectWorldAction(serverLevel, Action.HARVEST, villageCrop)) {
            return true;
        }

        WorldTarget bonemeal = this.findBonemealableOwnCrop(serverLevel);
        if (bonemeal != null) {
            if (InventoryUtils.hasItem(this.playerNpc, Items.BONE_MEAL)) {
                this.action = Action.BONE_MEAL;
                this.targetPos = bonemeal.pos();
                this.standPos = bonemeal.stand();
                return true;
            }
            if (!InventoryUtils.hasItem(this.playerNpc, Items.BONE_MEAL)
                    && PlayerNpcCraftingUtil.canCraftBoneMeal(this.playerNpc.getInventory())) {
                this.action = Action.CRAFT_BONE_MEAL;
                this.targetPos = bonemeal.pos();
                return true;
            }
        }
        return false;
    }

    private boolean selectWorldAction(ServerLevel serverLevel, Action nextAction, BlockPos target) {
        BlockPos nextStand = this.findStandNear(serverLevel, target);
        if (nextStand == null) {
            BlockPos obstruction = this.findNearbyEntranceObstruction(serverLevel);
            if (obstruction == null) {
                return false;
            }
            this.action = Action.CLEAR_ENTRANCE;
            this.targetPos = obstruction;
            this.standPos = this.findStandNear(serverLevel, obstruction);
            return true;
        }
        this.action = nextAction;
        this.targetPos = target.immutable();
        this.standPos = nextStand;
        return true;
    }

    private void tickBreak(ServerLevel serverLevel, boolean crop) {
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                crop ? FarmAi::isHarvestableCrop : FarmAi::isForagePlant,
                12,
                crop ? "harvesting crop" : "breaking grass for seeds",
                false,
                true
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }
        this.completedAction = result == BreakingBlockAi.TickResult.DONE;
        this.finished = true;
    }

    private void tickPlant(ServerLevel serverLevel) {
        this.useTicks++;
        if (this.useTicks == 1) {
            this.showActionItem(this.plantingCrop.item().getDefaultInstance());
        }
        if (this.useTicks < USE_ACTION_TICKS) {
            return;
        }
        this.completedAction = this.plantCrop(serverLevel);
        this.finished = true;
    }

    private void tickBoneMeal(ServerLevel serverLevel) {
        this.useTicks++;
        if (this.useTicks == 1) {
            this.showActionItem(new ItemStack(Items.BONE_MEAL));
        }
        if (this.useTicks < USE_ACTION_TICKS) {
            return;
        }
        this.completedAction = this.useBoneMeal(serverLevel);
        this.finished = true;
    }

    private void tickEntranceClear(ServerLevel serverLevel) {
        if (this.clearBlockAi.isRunning()) {
            if (!this.isSafeEntranceObstruction(serverLevel, this.clearBlockAi.targetPos())) {
                this.clearBlockAi.stop();
                this.finished = true;
                return;
            }
            ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
            if (result == ClearBlockAi.TickResult.RUNNING) {
                if (!this.isSafeEntranceObstruction(serverLevel, this.clearBlockAi.targetPos())) {
                    this.clearBlockAi.stop();
                    this.finished = true;
                }
                return;
            }
            this.completedAction = result == ClearBlockAi.TickResult.DONE;
            this.finished = true;
            return;
        }
        if (!this.isSafeEntranceObstruction(serverLevel, this.targetPos)) {
            this.finished = true;
            return;
        }
        if (this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D) <= CLEAR_DISTANCE_SQR
                && this.clearBlockAi.start(
                serverLevel,
                this.targetPos,
                state -> this.isEntranceObstructionState(serverLevel, this.targetPos, state),
                "clearing blocked farm entrance",
                CLEAR_TICKS,
                CLEAR_DISTANCE_SQR,
                true,
                true)) {
            return;
        }
        this.standPos = this.findStandNear(serverLevel, this.targetPos);
        if (this.standPos == null) {
            this.finished = true;
            return;
        }
        this.tickApproach(serverLevel);
    }

    private boolean plantCrop(ServerLevel serverLevel) {
        if (this.plantingCrop == null
                || !this.plan.cropPositions().contains(this.targetPos)
                || !serverLevel.getBlockState(this.targetPos).isAir()
                || !serverLevel.getBlockState(this.targetPos.below()).is(Blocks.FARMLAND)
                || !this.plantingCrop.state().canSurvive(serverLevel, this.targetPos)) {
            return false;
        }
        ItemStack seed = this.playerNpc.consumeInventoryItem(this.plantingCrop.item(), 1).orElse(ItemStack.EMPTY);
        if (seed.isEmpty()) {
            return false;
        }
        if (!serverLevel.setBlockAndUpdate(this.targetPos, this.plantingCrop.state())) {
            this.returnStack(seed);
            return false;
        }
        this.showActionItem(seed);
        this.placingBlockAi.playMainHandAction();
        serverLevel.playSound(null, this.targetPos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    private boolean useBoneMeal(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!this.plan.cropPositions().contains(this.targetPos)
                || !(state.getBlock() instanceof BonemealableBlock bonemealable)
                || !(state.getBlock() instanceof CropBlock crop)
                || crop.isMaxAge(state)
                || !bonemealable.isValidBonemealTarget(serverLevel, this.targetPos, state)) {
            return false;
        }
        ItemStack boneMeal = this.playerNpc.consumeInventoryItem(Items.BONE_MEAL, 1).orElse(ItemStack.EMPTY);
        if (boneMeal.isEmpty()) {
            return false;
        }
        this.showActionItem(boneMeal);
        bonemealable.performBonemeal(serverLevel, this.playerNpc.getRandom(), this.targetPos, state);
        if (serverLevel.getBlockState(this.targetPos).equals(state)) {
            this.returnStack(boneMeal);
            return false;
        }
        this.placingBlockAi.playMainHandAction();
        serverLevel.levelEvent(1505, this.targetPos, 0);
        return true;
    }

    private boolean craftBoneMeal(ServerLevel serverLevel) {
        if (!this.isBonemealableOwnedCrop(serverLevel, this.targetPos)) {
            return false;
        }
        boolean crafted = PlayerNpcCraftingUtil.tryCraftBoneMeal(serverLevel, this.playerNpc.getInventory());
        if (crafted) {
            this.showActionItem(new ItemStack(Items.BONE_MEAL));
            this.placingBlockAi.playMainHandAction();
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.BONE_BLOCK_PLACE, SoundSource.PLAYERS, 0.35F, 1.3F);
        }
        return crafted;
    }

    private BlockPos findOwnMatureCrop(ServerLevel serverLevel) {
        List<BlockPos> candidates = this.plan.cropPositions().stream()
                .filter(pos -> FarmAi.isHarvestableCrop(serverLevel.getBlockState(pos)))
                .sorted(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .map(BlockPos::immutable)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        int index = Math.floorMod(this.ownedMatureCropCursor++, candidates.size());
        return candidates.get(index);
    }

    private BlockPos findNearbyMatureCrop(ServerLevel serverLevel) {
        return this.findNearestLocal(serverLevel, true);
    }

    private BlockPos findNearbyForage(ServerLevel serverLevel) {
        return this.findNearestLocal(serverLevel, false);
    }

    private BlockPos findNearestLocal(ServerLevel serverLevel, boolean crop) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos cursor : BlockPos.betweenClosed(
                center.offset(-LOCAL_SUPPLY_RADIUS, -LOCAL_SUPPLY_Y_RANGE, -LOCAL_SUPPLY_RADIUS),
                center.offset(LOCAL_SUPPLY_RADIUS, LOCAL_SUPPLY_Y_RANGE, LOCAL_SUPPLY_RADIUS))) {
            BlockPos pos = cursor.immutable();
            if (this.plan.owns(pos)
                    || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (crop ? FarmAi.isHarvestableCrop(state) : FarmAi.isForagePlant(state)) {
                candidates.add(pos);
            }
        }
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        int start = candidates.isEmpty()
                ? 0
                : Math.floorMod(crop ? this.localCropCursor++ : this.localForageCursor++, candidates.size());
        int[] remainingPathChecks = this.selectionPathBudget();
        for (int offset = 0; offset < candidates.size(); offset++) {
            BlockPos candidate = candidates.get((start + offset) % candidates.size());
            if (this.findStandNear(serverLevel, candidate, remainingPathChecks, 2) != null) {
                return candidate;
            }
            if (remainingPathChecks[0] <= 0) {
                break;
            }
        }
        return null;
    }

    private BlockPos findPlantingPos(ServerLevel serverLevel) {
        return this.plan.cropPositions().stream()
                .filter(pos -> serverLevel.getBlockState(pos).isAir())
                .filter(pos -> serverLevel.getBlockState(pos.below()).is(Blocks.FARMLAND))
                .min(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    private WorldTarget findReachablePlantingTarget(ServerLevel serverLevel) {
        List<BlockPos> candidates = this.plan.cropPositions().stream()
                .filter(pos -> serverLevel.getBlockState(pos).isAir())
                .filter(pos -> serverLevel.getBlockState(pos.below()).is(Blocks.FARMLAND))
                .sorted(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .map(BlockPos::immutable)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        // Rotate the bounded path-check window so one unreachable nearest cell can
        // never starve the rest of the owned plot forever.
        int start = candidates.size() == 1 ? 0 : this.playerNpc.getRandom().nextInt(candidates.size());
        int[] remainingPathChecks = this.selectionPathBudget();
        for (int offset = 0; offset < candidates.size() && remainingPathChecks[0] > 0; offset++) {
            BlockPos candidate = candidates.get((start + offset) % candidates.size());
            BlockPos stand = this.findStandNear(serverLevel, candidate, remainingPathChecks, 2);
            if (stand != null) {
                return new WorldTarget(candidate, stand);
            }
        }
        return null;
    }

    private WorldTarget findBonemealableOwnCrop(ServerLevel serverLevel) {
        List<BlockPos> candidates = this.plan.cropPositions().stream()
                .filter(pos -> this.isBonemealableOwnedCrop(serverLevel, pos))
                .sorted(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .toList();
        int start = candidates.isEmpty() ? 0 : Math.floorMod(this.boneMealCropCursor++, candidates.size());
        int[] remainingPathChecks = this.selectionPathBudget();
        for (int offset = 0; offset < candidates.size(); offset++) {
            BlockPos candidate = candidates.get((start + offset) % candidates.size());
            BlockPos stand = this.findStandNear(serverLevel, candidate, remainingPathChecks, 2);
            if (stand != null) {
                return new WorldTarget(candidate.immutable(), stand.immutable());
            }
            if (remainingPathChecks[0] <= 0) {
                break;
            }
        }
        return null;
    }

    private boolean isBonemealableOwnedCrop(ServerLevel serverLevel, BlockPos pos) {
        if (serverLevel == null
                || pos == null
                || this.plan == null
                || !this.plan.cropPositions().contains(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop
                && !crop.isMaxAge(state)
                && state.getBlock() instanceof BonemealableBlock bonemealable
                && bonemealable.isValidBonemealTarget(serverLevel, pos, state);
    }

    private PlantingCrop findPlantingCrop() {
        if (InventoryUtils.hasItem(this.playerNpc, Items.WHEAT_SEEDS)) {
            return new PlantingCrop(Items.WHEAT_SEEDS, Blocks.WHEAT.defaultBlockState());
        }
        if (InventoryUtils.hasItem(this.playerNpc, Items.CARROT)) {
            return new PlantingCrop(Items.CARROT, Blocks.CARROTS.defaultBlockState());
        }
        if (InventoryUtils.hasItem(this.playerNpc, Items.POTATO)) {
            return new PlantingCrop(Items.POTATO, Blocks.POTATOES.defaultBlockState());
        }
        if (InventoryUtils.hasItem(this.playerNpc, Items.BEETROOT_SEEDS)) {
            return new PlantingCrop(Items.BEETROOT_SEEDS, Blocks.BEETROOTS.defaultBlockState());
        }
        return null;
    }

    private static boolean hasPlantingItem(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, Items.WHEAT_SEEDS)
                || InventoryUtils.hasItem(playerNpc, Items.CARROT)
                || InventoryUtils.hasItem(playerNpc, Items.POTATO)
                || InventoryUtils.hasItem(playerNpc, Items.BEETROOT_SEEDS);
    }

    private BlockPos findNearbyEntranceObstruction(ServerLevel serverLevel) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.plan.gatePos());
        candidates.add(this.plan.gatePos().above());
        for (BlockPos ground : this.plan.pathPositions()) {
            candidates.add(ground.above());
            candidates.add(ground.above(2));
        }
        return candidates.stream()
                .filter(pos -> this.isSafeEntranceObstruction(serverLevel, pos))
                .filter(pos -> this.findStandNear(serverLevel, pos) != null)
                .min(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .map(BlockPos::immutable)
                .orElse(null);
    }

    private boolean isSafeEntranceObstruction(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || !FarmAi.isEntranceCorridor(this.plan, pos)
                || FarmAi.isGrowingCrop(serverLevel, this.plan, pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || serverLevel.getBlockEntity(pos) != null) {
            return false;
        }
        return this.isEntranceObstructionState(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    private boolean isEntranceObstructionState(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state != null
                && !state.isAir()
                && !state.is(Blocks.WATER)
                && !(state.getBlock() instanceof CropBlock)
                && !(state.getBlock() instanceof FenceBlock)
                && !(state.getBlock() instanceof FenceGateBlock)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private void tickApproach(ServerLevel serverLevel) {
        BlockPos current = this.playerNpc.blockPosition();
        if (this.lastApproachPos == null || this.lastApproachPos.distSqr(current) > 0.25D) {
            this.lastApproachPos = current.immutable();
            this.routeFailureTicks = 0;
        } else {
            this.routeFailureTicks++;
        }
        if (this.standPos == null || !canOccupyInteractionStand(serverLevel, this.standPos)) {
            this.standPos = this.findStandNear(serverLevel, this.targetPos);
        }
        if (this.standPos == null) {
            if (!this.trySwitchToEntranceClear(serverLevel)) {
                this.finished = true;
            }
            return;
        }
        if (this.repathTicks-- <= 0) {
            if (!this.moveToStand(serverLevel)) {
                this.routeFailureTicks++;
            }
            this.repathTicks = 16;
        }
        if (this.action != Action.CLEAR_ENTRANCE && this.routeFailureTicks >= 20) {
            if (!this.trySwitchToEntranceClear(serverLevel) && this.routeFailureTicks >= 60) {
                this.finished = true;
            }
        } else if (this.action == Action.CLEAR_ENTRANCE && this.routeFailureTicks >= 60) {
            this.finished = true;
        }
    }

    private boolean trySwitchToEntranceClear(ServerLevel serverLevel) {
        BlockPos obstruction = this.findNearbyEntranceObstruction(serverLevel);
        if (obstruction == null) {
            return false;
        }
        BlockPos clearStand = this.findStandNear(serverLevel, obstruction);
        if (clearStand == null) {
            return false;
        }
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        // Epic Fight compatibility is disabled.
        this.restoreActionItem();
        this.action = Action.CLEAR_ENTRANCE;
        this.targetPos = obstruction;
        this.standPos = clearStand;
        this.useTicks = 0;
        this.routeFailureTicks = 0;
        this.lastApproachPos = this.playerNpc.blockPosition();
        this.updateDetail();
        this.moveToStand(serverLevel);
        return true;
    }

    private BlockPos findStandNear(ServerLevel serverLevel, BlockPos target) {
        return this.findStandNear(
                serverLevel,
                target,
                this.selectionPathBudget(),
                MAX_LOCAL_PATH_CHECKS
        );
    }

    private int[] selectionPathBudget() {
        return this.selectionPathBudget != null
                ? this.selectionPathBudget
                : new int[]{MAX_LOCAL_PATH_CHECKS};
    }

    private BlockPos findStandNear(
            ServerLevel serverLevel,
            BlockPos target,
            int[] remainingPathChecks,
            int maxChecksForTarget
    ) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (isPlayerInInteractionRange(this.playerNpc, target)) {
            return feet.immutable();
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).below());
            candidates.add(target.relative(direction, 2));
            candidates.add(target.relative(direction, 2).below());
        }
        candidates = new ArrayList<>(candidates.stream().distinct().toList());
        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        int checksForTarget = 0;
        for (BlockPos candidate : candidates) {
            if (!canOccupyInteractionStand(serverLevel, candidate)
                    || distanceToTargetSqr(candidate, target) > INTERACTION_DISTANCE_SQR) {
                continue;
            }
            if (remainingPathChecks == null
                    || remainingPathChecks.length == 0
                    || remainingPathChecks[0] <= 0
                    || checksForTarget >= maxChecksForTarget) {
                break;
            }
            remainingPathChecks[0]--;
            checksForTarget++;
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    FARM_PATH_NODE_MULTIPLIER
            );
            if (path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean moveToStand(ServerLevel serverLevel) {
        return serverLevel != null
                && this.standPos != null
                && this.pathNavigationAi.moveToExact(
                serverLevel,
                this.standPos,
                1.0D,
                0,
                FARM_PATH_NODE_MULTIPLIER
        );
    }

    private static boolean hasEmptyCropPosition(ServerLevel serverLevel, Plan plan) {
        return plan.cropPositions().stream().anyMatch(pos -> serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.below()).is(Blocks.FARMLAND));
    }

    private static double distanceToTargetSqr(BlockPos stand, BlockPos target) {
        double dx = stand.getX() + 0.5D - (target.getX() + 0.5D);
        double dy = stand.getY() + 1.5D - (target.getY() + 0.5D);
        double dz = stand.getZ() + 0.5D - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean inInteractionRange() {
        return this.targetPos != null && this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D) <= INTERACTION_DISTANCE_SQR;
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

    private void showActionItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
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
        ItemStack remainder = InventoryUtils.addItemAndReturnRemainder(this.playerNpc, stack);
        if (!remainder.isEmpty()) {
            this.playerNpc.spawnAtLocation(remainder);
        }
    }

    private void updateDetail() {
        String detail = switch (this.action) {
            case HARVEST -> "harvesting crop";
            case FORAGE -> "breaking grass for seeds";
            case PLANT -> "planting crop";
            case BONE_MEAL -> "using bone meal";
            case CRAFT_BONE_MEAL -> "crafting bone meal";
            case CLEAR_ENTRANCE -> "clearing blocked farm entrance";
            default -> "tending farm";
        };
        if (this.targetPos != null) {
            detail += " @ " + this.targetPos.getX() + " " + this.targetPos.getY() + " " + this.targetPos.getZ();
        }
        this.playerNpc.setCurrentAiDetail(detail);
    }

    private ServerLevel serverLevel() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    private void resetPlan() {
        this.plan = null;
        this.targetPos = null;
        this.standPos = null;
        this.selectionPathBudget = null;
        this.plantingCrop = null;
        this.action = Action.NONE;
        this.actionTicks = 0;
        this.useTicks = 0;
        this.repathTicks = 0;
        this.routeFailureTicks = 0;
        this.lastApproachPos = null;
        this.finished = false;
        this.completedAction = false;
    }

    private enum Action {
        NONE,
        HARVEST,
        FORAGE,
        PLANT,
        BONE_MEAL,
        CRAFT_BONE_MEAL,
        CLEAR_ENTRANCE
    }

    private record PlantingCrop(Item item, BlockState state) {
    }

    private record WorldTarget(BlockPos pos, BlockPos stand) {
    }

    private record SupplyTargetCache(
            int tick,
            Identifier dimension,
            BlockPos feet,
            boolean actionable
    ) {
        boolean matches(int currentTick, Identifier currentDimension, BlockPos currentFeet) {
            return currentTick - this.tick <= 20
                    && this.dimension.equals(currentDimension)
                    && this.feet.distSqr(currentFeet) <= 4.0D;
        }
    }

    private static boolean hasReachableInteractionStand(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target,
            int[] remainingPathChecks
    ) {
        BlockPos feet = playerNpc.blockPosition();
        if (isPlayerInInteractionRange(playerNpc, target)) {
            return true;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).below());
            candidates.add(target.relative(direction, 2));
            candidates.add(target.relative(direction, 2).below());
        }
        candidates = new ArrayList<>(candidates.stream().distinct().toList());
        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        int checksForTarget = 0;
        for (BlockPos candidate : candidates) {
            if (!canOccupyInteractionStand(serverLevel, candidate)
                    || distanceToTargetSqr(candidate, target) > INTERACTION_DISTANCE_SQR) {
                continue;
            }
            if (remainingPathChecks == null
                    || remainingPathChecks.length == 0
                    || remainingPathChecks[0] <= 0
                    || checksForTarget >= 2) {
                break;
            }
            remainingPathChecks[0]--;
            checksForTarget++;
            Path path = PathNavigationAi.createBoundedPath(
                    playerNpc,
                    candidate,
                    FARM_PATH_NODE_MULTIPLIER
            );
            if (path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayerInInteractionRange(PlayerNpcEntity playerNpc, BlockPos target) {
        return playerNpc != null
                && target != null
                && playerNpc.distanceToSqr(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D
        ) <= INTERACTION_DISTANCE_SQR;
    }

    private static boolean canOccupyInteractionStand(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel != null
                && pos != null
                && serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && !serverLevel.getBlockState(pos.below()).getCollisionShape(serverLevel, pos.below()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }
}
