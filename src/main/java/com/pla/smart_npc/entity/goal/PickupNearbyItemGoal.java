package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.PillarUpAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcTrashUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PickupNearbyItemGoal extends Goal implements GatheringGoal {
    private static final String AI_STATE = "ai.player_npc.collecting_item";
    private static final double SEARCH_RADIUS = 8.0D;
    private static final double SEARCH_VERTICAL_RADIUS = 5.0D;
    private static final double ANIMAL_LOOT_SEARCH_RADIUS = 10.0D;
    private static final double ANIMAL_LOOT_SEARCH_VERTICAL_RADIUS = 4.0D;
    private static final double PICKUP_DISTANCE_SQR = 1.45D * 1.45D;
    private static final int MAX_PICKUP_TICKS = 20 * 12;
    private static final int FAILED_PICKUP_COOLDOWN_TICKS = 20 * 8;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 5;
    private static final int MAX_CLOSE_PICKUP_WAIT_TICKS = 24;
    private static final int MAX_OBSTRUCTION_CLEAR_TICKS = 20 * 4;
    private static final double OBSTRUCTION_BREAK_DISTANCE_SQR = 3.2D * 3.2D;
    private static final double ACTIVE_APPROACH_HORIZONTAL_RANGE_SQR = 4.5D * 4.5D;
    private static final double ACTIVE_APPROACH_VERTICAL_RANGE = 3.0D;
    private static final double ACTIVE_APPROACH_PUSH_SPEED = 0.22D;
    private static final double ACTIVE_APPROACH_JUMP_Y = 0.42D;
    private static final int ACTIVE_APPROACH_JUMP_COOLDOWN_TICKS = 10;
    private static final int NO_PROGRESS_RECOVERY_TICKS = 20;
    private static final double PROGRESS_DISTANCE_EPSILON = 0.08D;
    private static final int CLOSE_OBSTRUCTION_CHECK_TICKS = 8;
    private static final int RECOVERY_RETRY_COOLDOWN_TICKS = 12;
    private static final int MAX_STUCK_RECOVERY_ATTEMPTS = 3;
    private static final int RECOVERY_DIRECTION_ATTEMPTS = 4;
    private static final int RECOVERY_DIRECTION_RADIUS = 4;
    private static final int HIGH_ITEM_VERTICAL_BLOCK_GAP = 2;
    private static final int PICKUP_PILLAR_SEARCH_RADIUS = 2;
    private static final int MAX_TARGET_CANDIDATES = 6;
    private static final float TARGET_SELECTION_PATH_NODE_MULTIPLIER = 0.03F;
    private static final float OVERLOADED_TARGET_SELECTION_PATH_NODE_MULTIPLIER = 0.01F;
    // One failed navigation build can consume most of the 50 ms server-tick budget in dense
    // terrain. Later throttled activations can inspect another candidate without batching paths.
    private static final int MAX_TARGET_SELECTION_PATHS = 1;
    private static final int MAX_RECOVERY_CANDIDATES = 2;
    private static final int FAILED_ITEM_AVOID_TICKS = 20 * 60;
    private static final int DETAIL_PROGRESS_REFRESH_INTERVAL_TICKS = 5;

    private final PlayerNpcEntity playerNpc;
    private final ToolAi helperToolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathStuckFallbackAi pathStuckFallbackAi;
    private final double speed;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final Set<BlockPos> skippedObstructions = new HashSet<>();
    private ItemEntity targetItem;
    private Path plannedPickupPath;
    private BlockPos plannedPickupObstruction;
    private boolean plannedPickupPillar;
    private UUID failedItemId;
    private int failedItemAvoidUntilTick;
    private int lastAdmittedPathBatchTick = Integer.MIN_VALUE;
    private BlockPos prioritySearchCenter;
    private BlockPos pickupPillarBasePos;
    private PillarUpAi pickupPillarAi;
    private BlockPos progressTargetPos;
    private double bestProgressDistance = Double.POSITIVE_INFINITY;
    private int pickupTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int closePickupWaitTicks;
    private int obstructionClearTicks;
    private int activeApproachTicks;
    private int activeApproachJumpCooldown;
    private int noProgressTicks;
    private int recoveryRetryCooldownTicks;
    private int stuckRecoveryAttempts;
    private int giveUpCooldownTicks;
    private int targetSelectionCursor;
    private int lastDetailMode = -1;
    private int nextDetailProgressRefreshTick;
    private UUID lastDetailTargetId;
    private BlockPos lastDetailPriorityCenter;
    private boolean workerSlotPaused;

    public PickupNearbyItemGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.helperToolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.helperToolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.isItemPickupSuppressed()) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.hasWorkerSlot(this.playerNpc)) {
            return false;
        }
        if (giveUpCooldownTicks > 0) {
            giveUpCooldownTicks--;
            return false;
        }

        if (!canCollectRightNow()) {
            return false;
        }
        if (this.workerSlotPaused
                && this.targetItem != null
                && this.isCollectable(this.targetItem)
                && (this.pickupPillarBasePos != null
                || this.pickupPillarAi != null && this.pickupPillarAi.isRunning())) {
            return true;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        // Incidental drops from clearing the build footprint must not steal MOVE from an
        // active builder preparation episode.  They remain available for pickup once the
        // bounded terraform scan reports that the site is ready.
        if (this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                && !this.playerNpc.hasAnimalLootPriority()
                && TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)) {
            return false;
        }
        if (!this.ensurePathBatchAdmission(serverLevel)) {
            return false;
        }

        boolean hasAnimalLootPriority = playerNpc.hasAnimalLootPriority();
        prioritySearchCenter = hasAnimalLootPriority ? playerNpc.getAnimalLootPriorityPos() : null;

        targetItem = findTargetItem();
        if (targetItem == null && prioritySearchCenter != null) {
            playerNpc.clearAnimalLootPriority();
            prioritySearchCenter = null;
        }
        return targetItem != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.playerNpc.isItemPickupSuppressed()) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            return false;
        }
        return canCollectRightNow()
                && targetItem != null
                && isCollectable(targetItem)
                && canAccept(targetItem.getItem())
                && pickupTicks < MAX_PICKUP_TICKS
                && failedPathTicks < MAX_FAILED_PATH_TICKS;
    }

    @Override
    public void start() {
        if (this.targetItem != null && !this.playerNpc.getItemApproachAi().canAttempt(this.targetItem)) {
            this.targetItem = null;
            this.playerNpc.getNavigation().stop();
            return;
        }
        if (this.playerNpc.isItemPickupSuppressed()) {
            this.playerNpc.getNavigation().stop();
            return;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.playerNpc.getNavigation().stop();
            return;
        }
        if (this.workerSlotPaused
                && this.targetItem != null
                && (this.pickupPillarBasePos != null
                || this.pickupPillarAi != null && this.pickupPillarAi.isRunning())) {
            this.workerSlotPaused = false;
            this.playerNpc.setCurrentAiState(AI_STATE);
            this.updateDetail();
            return;
        }
        this.workerSlotPaused = false;
        this.playerNpc.getItemApproachAi().begin(this.targetItem);
        pickupTicks = 0;
        repathTicks = 0;
        failedPathTicks = 0;
        closePickupWaitTicks = 0;
        obstructionClearTicks = 0;
        activeApproachTicks = 0;
        activeApproachJumpCooldown = 0;
        noProgressTicks = 0;
        recoveryRetryCooldownTicks = 0;
        stuckRecoveryAttempts = 0;
        resetProgressWatch();
        clearBlockAi.stop();
        breakingBlockAi.stop();
        pathStuckFallbackAi.stop();
        clearPickupPillar();
        skippedObstructions.clear();
        prioritySearchCenter = playerNpc.getAnimalLootPriorityPos();
        playerNpc.setCurrentAiState(AI_STATE);
        resetDetailRefresh();
        updateDetail();
        moveToTarget();
    }

    @Override
    public void tick() {
        if (this.playerNpc.isItemPickupSuppressed()) {
            this.targetItem = null;
            this.playerNpc.getNavigation().stop();
            return;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.playerNpc.getNavigation().stop();
            return;
        }
        if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
            // GoalSelector will stop this goal and the next canUse() performs an admitted,
            // bounded retarget. Never hide a second six-path selection batch inside running tick.
            targetItem = null;
            playerNpc.getNavigation().stop();
            return;
        }

        pickupTicks++;
        if (recoveryRetryCooldownTicks > 0) {
            recoveryRetryCooldownTicks--;
        }
        if (activeApproachJumpCooldown > 0) {
            activeApproachJumpCooldown--;
        }
        updateDetail();
        playerNpc.getLookControl().setLookAt(
                targetItem.getX(),
                targetItem.getY() + targetItem.getBbHeight() * 0.5D,
                targetItem.getZ(),
                30.0F,
                30.0F
        );

        double distanceSqr = playerNpc.distanceToSqr(targetItem);
        if (distanceSqr <= PICKUP_DISTANCE_SQR) {
            playerNpc.getNavigation().stop();
            if (playerNpc.tryPickupItemEntity(targetItem)) {
                closePickupWaitTicks = 0;
                activeApproachTicks = 0;
                if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
                    targetItem = null;
                    playerNpc.getNavigation().stop();
                }
            } else {
                closePickupWaitTicks++;
                if (playerNpc.level() instanceof ServerLevel serverLevel
                        && closePickupWaitTicks >= CLOSE_OBSTRUCTION_CHECK_TICKS
                        && tryStartPathObstructionMining(targetItem.blockPosition())) {
                    return;
                }
                if (playerNpc.level() instanceof ServerLevel serverLevel && tryActivePickupApproach(serverLevel)) {
                    return;
                }
                if (!targetItem.hasPickUpDelay() || closePickupWaitTicks >= MAX_CLOSE_PICKUP_WAIT_TICKS) {
                    targetItem = null;
                    if (prioritySearchCenter == null) {
                        failedPathTicks = MAX_FAILED_PATH_TICKS;
                    }
                }
            }
            return;
        }

        closePickupWaitTicks = 0;
        if (playerNpc.level() instanceof ServerLevel serverLevel
                && pathStuckFallbackAi.tick(serverLevel, pickupRecoveryDetailPrefix())) {
            playerNpc.setCurrentAiDetail(pathStuckFallbackAi.detail(pickupRecoveryDetailPrefix()));
            return;
        }
        if (playerNpc.level() instanceof ServerLevel serverLevel && tickPathObstruction(serverLevel)) {
            return;
        }
        if (playerNpc.level() instanceof ServerLevel serverLevel && tickRunningPickupPillar(serverLevel)) {
            return;
        }
        if (playerNpc.level() instanceof ServerLevel serverLevel && tickNoProgressRecovery(serverLevel)) {
            return;
        }
        if (playerNpc.level() instanceof ServerLevel serverLevel && tickPickupPillar(serverLevel)) {
            return;
        }
        if (playerNpc.level() instanceof ServerLevel serverLevel && tryActivePickupApproach(serverLevel)) {
            return;
        }
        if (repathTicks-- <= 0) {
            repathTicks = REPATH_INTERVAL_TICKS;
            if (playerNpc.getNavigation().isDone() || playerNpc.getNavigation().isStuck()) {
                moveToTarget();
            }
        }
    }

    @Override
    public void stop() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && (this.workerSlotPaused
                || this.pickupPillarBasePos != null
                || this.pickupPillarAi != null && this.pickupPillarAi.isRunning())) {
            this.playerNpc.getNavigation().stop();
            this.breakingBlockAi.stop();
            this.clearBlockAi.stop();
            this.pathStuckFallbackAi.stop();
            this.helperToolAi.restoreMainHand();
            this.workerSlotPaused = true;
            if (AI_STATE.equals(this.playerNpc.getCurrentAiState())) {
                this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            }
            return;
        }
        boolean gaveUp = pickupTicks >= MAX_PICKUP_TICKS || failedPathTicks >= MAX_FAILED_PATH_TICKS;
        if (gaveUp) {
            this.playerNpc.getItemApproachAi().failed(this.targetItem);
        } else {
            this.playerNpc.getItemApproachAi().interrupted(this.targetItem);
        }
        if (gaveUp) {
            giveUpCooldownTicks = FAILED_PICKUP_COOLDOWN_TICKS + playerNpc.getRandom().nextInt(20 * 4);
            if (targetItem != null) {
                failedItemId = targetItem.getUUID();
                failedItemAvoidUntilTick = playerNpc.tickCount + FAILED_ITEM_AVOID_TICKS;
            }
            playerNpc.clearAnimalLootPriority();
        }

        targetItem = null;
        plannedPickupPath = null;
        plannedPickupObstruction = null;
        plannedPickupPillar = false;
        prioritySearchCenter = null;
        pickupTicks = 0;
        repathTicks = 0;
        failedPathTicks = 0;
        closePickupWaitTicks = 0;
        obstructionClearTicks = 0;
        activeApproachTicks = 0;
        activeApproachJumpCooldown = 0;
        noProgressTicks = 0;
        recoveryRetryCooldownTicks = 0;
        stuckRecoveryAttempts = 0;
        progressTargetPos = null;
        bestProgressDistance = Double.POSITIVE_INFINITY;
        clearBlockAi.stop();
        breakingBlockAi.stop();
        pathStuckFallbackAi.stop();
        clearPickupPillar();
        skippedObstructions.clear();
        helperToolAi.restoreMainHand();
        playerNpc.getNavigation().stop();
        if (AI_STATE.equals(playerNpc.getCurrentAiState())) {
            playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        }
    }

    private boolean canCollectRightNow() {
        if (playerNpc.level().isClientSide() || playerNpc.isItemPickupSuppressed()) {
            return false;
        }
        if (!playerNpc.isAlive() || playerNpc.isRemoved() || playerNpc.isDeadOrDying()) {
            return false;
        }
        if (playerNpc.isPassenger() || playerNpc.isNoAi() || playerNpc.isHealing()) {
            return false;
        }
        if (shouldDeferPickupForHomeShelter()) {
            return false;
        }

        LivingEntity target = playerNpc.getTarget();
        return target == null || !target.isAlive() || playerNpc.hasAnimalLootPriority() && target instanceof Animal;
    }

    private boolean shouldDeferPickupForHomeShelter() {
        return !playerNpc.hasAnimalLootPriority()
                && playerNpc.level() instanceof ServerLevel serverLevel
                && (serverLevel.isDarkOutside() || serverLevel.isThundering())
                && PlayerNpcHomeUtil.getHome(playerNpc).isPresent();
    }

    private ItemEntity findTargetItem() {
        if (this.playerNpc.isItemPickupSuppressed()) {
            return null;
        }
        this.plannedPickupPath = null;
        this.plannedPickupObstruction = null;
        this.plannedPickupPillar = false;
        BlockPos searchCenter = playerNpc.getAnimalLootPriorityPos();
        if (searchCenter != null) {
            prioritySearchCenter = searchCenter;
        }
        AABB searchBox = prioritySearchCenter == null
                ? playerNpc.getBoundingBox().inflate(SEARCH_RADIUS, SEARCH_VERTICAL_RADIUS, SEARCH_RADIUS)
                : new AABB(prioritySearchCenter).inflate(ANIMAL_LOOT_SEARCH_RADIUS, ANIMAL_LOOT_SEARCH_VERTICAL_RADIUS, ANIMAL_LOOT_SEARCH_RADIUS);
        List<ItemEntity> items = playerNpc.level().getEntitiesOfClass(
                ItemEntity.class,
                searchBox,
                item -> isCollectable(item) && canAccept(item.getItem())
        );

        items.sort(Comparator.comparingDouble(this::targetSortDistance));
        NavigationPathBudget pathBudget = new NavigationPathBudget(MAX_TARGET_SELECTION_PATHS);
        int candidateCount = Math.min(MAX_TARGET_CANDIDATES, items.size());
        if (candidateCount <= 0) {
            this.targetSelectionCursor = 0;
            this.plannedPickupPath = null;
            return null;
        }

        int startIndex = Math.floorMod(this.targetSelectionCursor, candidateCount);
        for (int offset = 0; offset < candidateCount; offset++) {
            if (pathBudget.exhausted()) {
                break;
            }
            int candidateIndex = (startIndex + offset) % candidateCount;
            ItemEntity item = items.get(candidateIndex);
            boolean allowRecovery = candidateIndex < MAX_RECOVERY_CANDIDATES;
            PickupRoute route = this.findPickupRoute(item, pathBudget, allowRecovery);
            this.targetSelectionCursor = (candidateIndex + 1) % candidateCount;
            if (route == null) {
                continue;
            }
            this.plannedPickupPath = route.path();
            this.plannedPickupObstruction = route.obstruction();
            this.plannedPickupPillar = route.pillar();
            return item;
        }
        this.plannedPickupPath = null;
        return null;
    }

    private PickupRoute findPickupRoute(
            ItemEntity item,
            NavigationPathBudget pathBudget,
            boolean allowRecovery
    ) {
        if (playerNpc.distanceToSqr(item) <= PICKUP_DISTANCE_SQR) {
            return new PickupRoute(null, null, false);
        }
        Path itemPath = pathBudget.createPath(item);
        if (itemPath != null && itemPath.canReach()) {
            return new PickupRoute(itemPath, null, false);
        }

        // An owned farm fence is intentionally protected from generic pickup obstruction mining.
        // If its owner is enclosed and the desired item is outside, hand the route to the dedicated
        // farm-egress safety goal. It may open the saved gate or clear only an owned boundary
        // fence in the bounded exit corridor; the immutable plan lets FarmSetup repair it later.
        if (this.requestOwnedFarmGateEgress(item)) {
            return null;
        }

        BlockPos stand = findStandNearItem(item);
        Path standPath = stand == null ? null : pathBudget.createPath(stand);
        if (standPath != null && standPath.canReach()) {
            return new PickupRoute(standPath, null, false);
        }
        if (allowRecovery) {
            BlockPos obstruction = stand == null ? null : findPathObstructionToward(stand);
            if (obstruction != null) {
                return new PickupRoute(null, obstruction.immutable(), false);
            }
            if (isHighPickupTarget(item) && canUsePickupPillarFromCurrentFeet(item)) {
                return new PickupRoute(null, null, true);
            }
        }
        return null;
    }

    private double targetSortDistance(ItemEntity item) {
        if (prioritySearchCenter == null) {
            return playerNpc.distanceToSqr(item);
        }

        double dx = item.getX() - (prioritySearchCenter.getX() + 0.5D);
        double dy = item.getY() - prioritySearchCenter.getY();
        double dz = item.getZ() - (prioritySearchCenter.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz + playerNpc.distanceToSqr(item) * 0.1D;
    }

    private boolean isCollectable(ItemEntity item) {
        return item != null
                && item.isAlive()
                && !item.isRemoved()
                && !item.hasPickUpDelay()
                && !item.getItem().isEmpty()
                && !PlayerNpcTrashUtil.isDiscarded(item.getItem())
                && this.playerNpc.getItemApproachAi().canAttempt(item)
                && (failedItemId == null
                || playerNpc.tickCount >= failedItemAvoidUntilTick
                || !failedItemId.equals(item.getUUID()))
                && InventoryUtils.isInventoryBackedSupplyDrop(item.getItem());
    }

    private boolean canAccept(ItemStack incoming) {
        if (incoming.isEmpty()) {
            return false;
        }

        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (slotStack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(slotStack, incoming)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private boolean canReach(BlockPos pos) {
        Path path = playerNpc.getNavigation().createPath(pos, 0);
        return path != null && path.canReach();
    }

    private void moveToTarget() {
        if (targetItem == null) {
            failedPathTicks = MAX_FAILED_PATH_TICKS;
            playerNpc.getNavigation().stop();
            return;
        }
        if (playerNpc.distanceToSqr(targetItem) <= PICKUP_DISTANCE_SQR) {
            failedPathTicks = 0;
            return;
        }

        Path selectedPath = this.plannedPickupPath;
        BlockPos selectedObstruction = this.plannedPickupObstruction;
        boolean selectedPillar = this.plannedPickupPillar;
        this.plannedPickupPath = null;
        this.plannedPickupObstruction = null;
        this.plannedPickupPillar = false;
        if (selectedPath != null
                && selectedPath.canReach()
                && playerNpc.getNavigation().moveTo(selectedPath, speed)) {
            failedPathTicks = 0;
            return;
        }
        if (selectedObstruction != null
                && playerNpc.level() instanceof ServerLevel serverLevel
                && startPickupPathClear(serverLevel, selectedObstruction)) {
            failedPathTicks = 0;
            return;
        }
        if (selectedPillar && tryMoveToPickupPillarBase()) {
            failedPathTicks = 0;
            return;
        }

        if (!(playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.ensurePathBatchAdmission(serverLevel)) {
            repathTicks = 1 + playerNpc.getRandom().nextInt(4);
            return;
        }

        if (isHighPickupTarget(targetItem) && tryMoveToPickupPillarBase()) {
            failedPathTicks = 0;
            return;
        }

        Path itemPath = playerNpc.getNavigation().createPath(targetItem, 0);
        if (itemPath != null && itemPath.canReach() && playerNpc.getNavigation().moveTo(itemPath, speed)) {
            failedPathTicks = 0;
        } else {
            BlockPos stand = findStandNearItem(targetItem);
            Path standPath = stand == null ? null : playerNpc.getNavigation().createPath(stand, 0);
            if (standPath != null && standPath.canReach() && playerNpc.getNavigation().moveTo(standPath, speed)) {
                failedPathTicks = 0;
            } else if (tryStartPathObstructionMining(stand) || tryStartPathObstructionMining(targetItem.blockPosition())) {
                failedPathTicks = 0;
            } else if (tryStartStuckRecovery(
                    serverLevel,
                    stand == null ? targetItem.blockPosition() : stand,
                    "no usable pickup path"
            )) {
                if (pathStuckFallbackAi.isRunning()) {
                    failedPathTicks = 0;
                }
            } else {
                failedPathTicks += REPATH_INTERVAL_TICKS;
            }
        }
    }

    private BlockPos findStandNearItem(ItemEntity item) {
        if (item == null || !(playerNpc.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }

        BlockPos itemPos = item.blockPosition();
        BlockPos bestStand = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(itemPos.offset(-2, -2, -2), itemPos.offset(2, 2, 2))) {
            BlockPos stand = pos.immutable();
            if (!canStandAt(serverLevel, stand)) {
                continue;
            }
            double distance = playerNpc.distanceToSqr(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStand = stand;
            }
        }
        return bestStand;
    }

    private boolean tryActivePickupApproach(ServerLevel serverLevel) {
        if (targetItem == null || !targetItem.isAlive()) {
            return false;
        }

        double dx = targetItem.getX() - playerNpc.getX();
        double dy = targetItem.getY() - playerNpc.getY();
        double dz = targetItem.getZ() - playerNpc.getZ();
        double horizontalSqr = dx * dx + dz * dz;
        if (horizontalSqr > ACTIVE_APPROACH_HORIZONTAL_RANGE_SQR
                || Math.abs(dy) > ACTIVE_APPROACH_VERTICAL_RANGE) {
            activeApproachTicks = 0;
            return false;
        }

        activeApproachTicks++;
        double approachSpeed = Math.min(1.0D, Math.max(speed, 0.95D));
        if (repathTicks-- <= 0) {
            if (playerNpc.getNavigation().isDone() || playerNpc.getNavigation().isStuck()) {
                moveToTarget();
            }
            if (repathTicks <= 0) {
                repathTicks = REPATH_INTERVAL_TICKS;
            }
        }
        playerNpc.getMoveControl().setWantedPosition(
                targetItem.getX(),
                targetItem.getY(),
                targetItem.getZ(),
                approachSpeed
        );

        if (activeApproachTicks >= 12
                && (playerNpc.getNavigation().isDone() || playerNpc.getNavigation().isStuck())
                && tryStartPathObstructionMining(targetItem.blockPosition())) {
            activeApproachTicks = 0;
            return true;
        }

        if (horizontalSqr > 1.0E-4D) {
            double horizontal = Math.sqrt(horizontalSqr);
            double pushSpeed = Math.min(ACTIVE_APPROACH_PUSH_SPEED, 0.09D + horizontal * 0.05D);
            Vec3 motion = playerNpc.getDeltaMovement();
            double pushX = dx / horizontal * pushSpeed;
            double pushZ = dz / horizontal * pushSpeed;
            playerNpc.setDeltaMovement(
                    motion.x * 0.35D + pushX,
                    motion.y,
                    motion.z * 0.35D + pushZ
            );
            playerNpc.hurtMarked = true;

            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            playerNpc.setYRot(yaw);
            playerNpc.setYHeadRot(yaw);
        }

        if (shouldJumpTowardPickup(dy, horizontalSqr)) {
            Vec3 motion = playerNpc.getDeltaMovement();
            playerNpc.setDeltaMovement(motion.x, Math.max(motion.y, ACTIVE_APPROACH_JUMP_Y), motion.z);
            playerNpc.hurtMarked = true;
            activeApproachJumpCooldown = ACTIVE_APPROACH_JUMP_COOLDOWN_TICKS;
        }

        playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "moving into %s\ngiving up in %ds",
                targetItem.getItem().getHoverName().getString(),
                getRemainingPickupSeconds()
        ));
        if (playerNpc.tryPickupItemEntity(targetItem)) {
            activeApproachTicks = 0;
            failedPathTicks = 0;
            repathTicks = 0;
        }
        return true;
    }

    private boolean shouldJumpTowardPickup(double dy, double horizontalSqr) {
        if (!playerNpc.onGround() || activeApproachJumpCooldown > 0) {
            return false;
        }

        return dy > 0.15D
                || horizontalSqr > 0.85D * 0.85D && (playerNpc.getNavigation().isDone() || activeApproachTicks >= 4)
                || activeApproachTicks >= 8;
    }

    private boolean tickPickupPillar(ServerLevel serverLevel) {
        if (!isHighPickupTarget(targetItem) || countPickupPillarBlocks() <= 0) {
            clearPickupPillar();
            return false;
        }

        if (pickupPillarBasePos == null || !canUsePickupPillarBase(serverLevel, pickupPillarBasePos, targetItem)) {
            pickupPillarBasePos = findPickupPillarBase(serverLevel, targetItem);
            if (pickupPillarBasePos == null) {
                return false;
            }
        }

        if (!isAtPickupPillarBase()) {
            moveToPickupPillarBase(pickupPillarBasePos);
            return true;
        }

        if (!playerNpc.onGround()) {
            return true;
        }

        BlockPos feet = playerNpc.blockPosition();
        if (tryClearPillarHeadroom(serverLevel, feet)) {
            return true;
        }

        PillarUpAi pillarAi = preparePickupPillarAi();
        if (pillarAi == null) {
            return false;
        }
        if (!pillarAi.start(serverLevel, feet)) {
            BlockPos blocker = pillarAi.startBlockerPos(serverLevel, feet);
            String failure = pillarAi.startBlocker(serverLevel, feet);
            if (tryStartPillarFailureObstruction(serverLevel, blocker)) {
                return true;
            }
            return tryStartStuckRecovery(serverLevel, pickupPillarBasePos, "pillar start failed: " + failure);
        }

        updatePickupPillarDetail();
        return true;
    }

    private boolean canUsePickupPillarFromCurrentFeet(ItemEntity item) {
        return playerNpc.level() instanceof ServerLevel serverLevel
                && isHighPickupTarget(item)
                && countPickupPillarBlocks() > 0
                && canUsePickupPillarBase(serverLevel, playerNpc.blockPosition(), item);
    }

    private boolean tickRunningPickupPillar(ServerLevel serverLevel) {
        if (pickupPillarAi == null || !pickupPillarAi.isRunning()) {
            return false;
        }

        PillarUpAi.TickResult result = pickupPillarAi.tick(serverLevel);
        if (result == PillarUpAi.TickResult.RUNNING) {
            updatePickupPillarDetail();
            return true;
        }
        if (result == PillarUpAi.TickResult.PLACED) {
            pickupPillarAi.consumeLastPlacedPos();
            failedPathTicks = 0;
            repathTicks = 0;
            resetProgressWatch();
            updatePickupPillarDetail();
            return true;
        }
        if (result == PillarUpAi.TickResult.FAILED) {
            BlockPos blocker = pickupPillarAi.consumeLastFailureBlockerPos();
            String failure = pickupPillarAi.consumeLastFailureDetail();
            pickupPillarAi = null;
            helperToolAi.restoreMainHand();
            if (tryStartPillarFailureObstruction(serverLevel, blocker)) {
                return true;
            }
            return tryStartStuckRecovery(serverLevel, pickupPillarBasePos, failure);
        }
        return false;
    }

    private PillarUpAi preparePickupPillarAi() {
        ItemStack stack = findPickupPillarStack();
        if (stack.isEmpty() && !shouldPreserveWoodForPillar()) {
            PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(playerNpc.getInventory(), 0);
            stack = findPickupPillarStack();
        }
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            helperToolAi.restoreMainHand();
            pickupPillarAi = null;
            return null;
        }

        if (pickupPillarAi != null && playerNpc.getMainHandItem().is(stack.getItem())) {
            return pickupPillarAi;
        }

        helperToolAi.restoreMainHand();
        pickupPillarAi = new PillarUpAi(
                playerNpc,
                helperToolAi,
                stack.getItem(),
                blockItem.getBlock().defaultBlockState()
        );
        return pickupPillarAi;
    }

    private ItemStack findPickupPillarStack() {
        ItemStack mainHand = playerNpc.getMainHandItem();
        if (isDirtPillarBlock(mainHand)) {
            return mainHand;
        }
        ItemStack inventoryStack = findInventoryPickupPillarStack(PickupNearbyItemGoal::isDirtPillarBlock);
        if (!inventoryStack.isEmpty()) {
            return inventoryStack;
        }

        if (mainHand.getItem() instanceof BlockItem mainBlockItem
                && isStonePillarBlock(mainBlockItem.getBlock().defaultBlockState())) {
            return mainHand;
        }
        inventoryStack = findInventoryPickupPillarStack(stack -> stack.getItem() instanceof BlockItem blockItem
                && isStonePillarBlock(blockItem.getBlock().defaultBlockState()));
        if (!inventoryStack.isEmpty()) {
            return inventoryStack;
        }

        if (isUsablePlankPillarBlock(mainHand)) {
            return mainHand;
        }
        return findInventoryPickupPillarStack(this::isUsablePlankPillarBlock);
    }

    private ItemStack findInventoryPickupPillarStack(java.util.function.Predicate<ItemStack> predicate) {
        SimpleContainer inventory = playerNpc.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean tryStartPillarFailureObstruction(ServerLevel serverLevel, BlockPos blocker) {
        if (blocker == null || !serverLevel.isInWorldBounds(blocker)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(blocker);
        if (!isPathObstructionBlock(serverLevel, blocker, state)) {
            return false;
        }
        return startPickupPathClear(serverLevel, blocker);
    }

    private boolean tryMoveToPickupPillarBase() {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel) || !isHighPickupTarget(targetItem)) {
            return false;
        }

        BlockPos base = pickupPillarBasePos;
        if (base == null || !canUsePickupPillarBase(serverLevel, base, targetItem)) {
            base = findPickupPillarBase(serverLevel, targetItem);
            pickupPillarBasePos = base;
        }
        if (base == null) {
            return false;
        }

        moveToPickupPillarBase(base);
        return true;
    }

    private boolean isHighPickupTarget(ItemEntity item) {
        return item != null
                && item.isAlive()
                && item.blockPosition().getY() - playerNpc.blockPosition().getY() >= HIGH_ITEM_VERTICAL_BLOCK_GAP
                && horizontalDistanceSqrToItem(item, playerNpc.blockPosition()) <= 4.0D * 4.0D;
    }

    private boolean canPillarToItem(ItemEntity item) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)
                || !isHighPickupTarget(item)
                || countPickupPillarBlocks() <= 0) {
            return false;
        }

        return findPickupPillarBase(serverLevel, item) != null;
    }

    private BlockPos findPickupPillarBase(ServerLevel serverLevel, ItemEntity item) {
        if (item == null) {
            return null;
        }

        BlockPos currentFeet = playerNpc.blockPosition();
        if (canUsePickupPillarBase(serverLevel, currentFeet, item)) {
            return currentFeet.immutable();
        }

        BlockPos itemPos = item.blockPosition();
        BlockPos bestBase = null;
        double bestDistance = Double.MAX_VALUE;
        int minY = Math.max(serverLevel.getMinY() + 1, currentFeet.getY() - 2);
        int maxY = Math.min(itemPos.getY(), currentFeet.getY() + 2);
        for (int y = maxY; y >= minY; y--) {
            for (int x = itemPos.getX() - PICKUP_PILLAR_SEARCH_RADIUS; x <= itemPos.getX() + PICKUP_PILLAR_SEARCH_RADIUS; x++) {
                for (int z = itemPos.getZ() - PICKUP_PILLAR_SEARCH_RADIUS; z <= itemPos.getZ() + PICKUP_PILLAR_SEARCH_RADIUS; z++) {
                    BlockPos base = new BlockPos(x, y, z);
                    if (!canUsePickupPillarBase(serverLevel, base, item)) {
                        continue;
                    }
                    double distance = playerNpc.distanceToSqr(base.getX() + 0.5D, base.getY(), base.getZ() + 0.5D);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestBase = base.immutable();
                    }
                }
            }
        }
        return bestBase;
    }

    private boolean canUsePickupPillarBase(ServerLevel serverLevel, BlockPos base, ItemEntity item) {
        if (base == null || item == null) {
            return false;
        }
        BlockPos feet = playerNpc.blockPosition();
        boolean currentFeetBase = feet.getX() == base.getX()
                && feet.getY() == base.getY()
                && feet.getZ() == base.getZ();
        if (!canStandAt(serverLevel, base)
                && !(currentFeetBase && canUseCurrentFeetAsPillarBase(serverLevel, base))) {
            return false;
        }
        if (!(feet.getX() == base.getX() && feet.getZ() == base.getZ())
                && !canReach(base)
                && findPathObstructionToward(base) == null) {
            return false;
        }
        if (horizontalDistanceSqrToItem(item, base) > 1.85D * 1.85D) {
            return false;
        }

        int blocksNeeded = blocksNeededToReachItem(base, item);
        if (blocksNeeded > Math.max(1, countPickupPillarBlocks())) {
            return false;
        }

        int maxClimb = Math.min(Math.max(1, blocksNeeded), countPickupPillarBlocks());
        for (int placed = 1; placed <= maxClimb; placed++) {
            if (!hasOpenOrClearableBodySpace(serverLevel, base.above(placed))) {
                return false;
            }
        }
        return true;
    }

    private int blocksNeededToReachItem(BlockPos base, ItemEntity item) {
        int targetFeetY = Math.max(base.getY() + 1, item.blockPosition().getY() - 1);
        return Math.max(1, targetFeetY - base.getY());
    }

    private boolean canUseCurrentFeetAsPillarBase(ServerLevel serverLevel, BlockPos feet) {
        if (!playerNpc.onGround() || feet == null) {
            return false;
        }
        BlockState feetState = serverLevel.getBlockState(feet);
        BlockState headState = serverLevel.getBlockState(feet.above());
        BlockState floorState = serverLevel.getBlockState(feet.below());
        return feetState.getCollisionShape(serverLevel, feet).isEmpty()
                && headState.getCollisionShape(serverLevel, feet.above()).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && !floorState.getCollisionShape(serverLevel, feet.below()).isEmpty();
    }

    private double horizontalDistanceSqrToItem(ItemEntity item, BlockPos pos) {
        double dx = item.getX() - (pos.getX() + 0.5D);
        double dz = item.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private boolean isAtPickupPillarBase() {
        if (pickupPillarBasePos == null) {
            return false;
        }

        BlockPos feet = playerNpc.blockPosition();
        return feet.getY() >= pickupPillarBasePos.getY()
                && feet.getX() == pickupPillarBasePos.getX()
                && feet.getZ() == pickupPillarBasePos.getZ();
    }

    private void moveToPickupPillarBase(BlockPos base) {
        if (base == null) {
            return;
        }

        Path path = playerNpc.getNavigation().createPath(base, 0);
        if (path != null && path.canReach() && playerNpc.getNavigation().moveTo(path, speed)) {
            failedPathTicks = 0;
            return;
        }

        if (!tryStartPathObstructionMining(base)) {
            failedPathTicks += REPATH_INTERVAL_TICKS;
        }
    }

    private boolean tryClearPillarHeadroom(ServerLevel serverLevel, BlockPos feet) {
        BlockPos[] headroom = new BlockPos[]{feet.above(), feet.above(2)};
        for (BlockPos pos : headroom) {
            BlockState state = serverLevel.getBlockState(pos);
            if (!isPathObstructionBlock(serverLevel, pos, state)) {
                continue;
            }
            return startPickupPathClear(serverLevel, pos);
        }
        return false;
    }

    private boolean hasOpenOrClearableBodySpace(ServerLevel serverLevel, BlockPos feet) {
        return isOpenOrClearableBodyBlock(serverLevel, feet)
                && isOpenOrClearableBodyBlock(serverLevel, feet.above());
    }

    private boolean isOpenOrClearableBodyBlock(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.getCollisionShape(serverLevel, pos).isEmpty() && state.getFluidState().isEmpty()) {
            return true;
        }
        return isPathObstructionBlock(serverLevel, pos, state);
    }

    private boolean isPickupPillarBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || stack.is(ItemTags.LOGS)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && !state.canBeReplaced()
                && (isDirtPillarBlock(stack)
                        || this.isUsablePlankPillarBlock(stack)
                        || isStonePillarBlock(state));
    }

    private boolean isUsablePlankPillarBlock(ItemStack stack) {
        return stack.is(ItemTags.PLANKS) && !this.shouldPreserveWoodForPillar();
    }

    private boolean shouldPreserveWoodForPillar() {
        return this.playerNpc.shouldPrioritizeLogGathering();
    }

    private static boolean isDirtPillarBlock(ItemStack stack) {
        return stack.is(Items.DIRT)
                || stack.is(Items.GRASS_BLOCK)
                || stack.is(Items.COARSE_DIRT)
                || stack.is(Items.ROOTED_DIRT)
                || stack.is(Items.PODZOL);
    }

    private static boolean isStonePillarBlock(BlockState state) {
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

    private int countPickupPillarBlocks() {
        int count = isPickupPillarBlock(playerNpc.getMainHandItem()) ? playerNpc.getMainHandItem().getCount() : 0;
        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isPickupPillarBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count + (this.shouldPreserveWoodForPillar()
                ? 0
                : PlayerNpcCraftingUtil.countLogs(playerNpc.getInventory()) * 4);
    }

    private boolean tickNoProgressRecovery(ServerLevel serverLevel) {
        if (targetItem == null || !targetItem.isAlive()) {
            resetProgressWatch();
            return false;
        }
        if (!playerNpc.onGround()) {
            return false;
        }

        BlockPos targetPos = targetItem.blockPosition();
        BlockPos routeTarget = pickupPillarBasePos == null
                ? targetPos
                : pickupPillarBasePos;
        if (progressTargetPos == null || !progressTargetPos.equals(routeTarget)) {
            resetProgressWatch();
            return false;
        }

        double currentDistance = progressDistanceTo(routeTarget);
        if (currentDistance + PROGRESS_DISTANCE_EPSILON < bestProgressDistance) {
            bestProgressDistance = currentDistance;
            noProgressTicks = 0;
            return false;
        }

        noProgressTicks++;
        if (noProgressTicks < NO_PROGRESS_RECOVERY_TICKS || recoveryRetryCooldownTicks > 0) {
            return false;
        }

        if (tryStartPathObstructionMining(routeTarget)) {
            resetProgressWatch();
            return true;
        }
        if (isHighPickupTarget(targetItem)
                && pickupPillarBasePos != null
                && isAtPickupPillarBase()) {
            return false;
        }
        return tryStartStuckRecovery(serverLevel, routeTarget, "no movement progress");
    }

    private boolean tryStartStuckRecovery(ServerLevel serverLevel, BlockPos routeTarget, String reason) {
        if (targetItem == null || !targetItem.isAlive() || !playerNpc.onGround()) {
            return false;
        }
        if (recoveryRetryCooldownTicks > 0) {
            return false;
        }
        if (!this.ensurePathBatchAdmission(serverLevel)) {
            recoveryRetryCooldownTicks = 1 + playerNpc.getRandom().nextInt(4);
            return false;
        }
        if (stuckRecoveryAttempts >= MAX_STUCK_RECOVERY_ATTEMPTS) {
            failedPathTicks = MAX_FAILED_PATH_TICKS;
            playerNpc.getNavigation().stop();
            playerNpc.setCurrentAiDetail(pickupRecoveryDetailPrefix() + " recovery exhausted: " + reason);
            return true;
        }

        stuckRecoveryAttempts++;
        recoveryRetryCooldownTicks = RECOVERY_RETRY_COOLDOWN_TICKS;
        resetProgressWatch();
        BlockPos directionTarget = findRandomSafeRecoveryDirection(serverLevel, routeTarget);
        if (directionTarget == null) {
            directionTarget = routeTarget == null ? targetItem.blockPosition() : routeTarget;
        }
        if (pathStuckFallbackAi.start(
                serverLevel,
                directionTarget,
                pickupRecoveryDetailPrefix(),
                this::isProtectedHomeBlock
        )) {
            playerNpc.setCurrentAiDetail(pathStuckFallbackAi.detail(pickupRecoveryDetailPrefix()));
            return true;
        }

        failedPathTicks += REPATH_INTERVAL_TICKS;
        playerNpc.setCurrentAiDetail(pathStuckFallbackAi.detail(
                pickupRecoveryDetailPrefix() + " recovery blocked: " + reason
        ));
        return true;
    }

    private BlockPos findRandomSafeRecoveryDirection(ServerLevel serverLevel, BlockPos routeTarget) {
        BlockPos feet = playerNpc.blockPosition();
        BlockPos fallback = null;
        for (int attempt = 0; attempt < RECOVERY_DIRECTION_ATTEMPTS; attempt++) {
            int dx = playerNpc.getRandom().nextInt(RECOVERY_DIRECTION_RADIUS * 2 + 1) - RECOVERY_DIRECTION_RADIUS;
            int dz = playerNpc.getRandom().nextInt(RECOVERY_DIRECTION_RADIUS * 2 + 1) - RECOVERY_DIRECTION_RADIUS;
            int dy = playerNpc.getRandom().nextInt(5) - 2;
            if (dx == 0 && dz == 0) {
                continue;
            }

            BlockPos candidate = feet.offset(dx, dy, dz);
            if (isProtectedHomeBlock(candidate)
                    || !canStandAt(serverLevel, candidate)
                    || !canReachExact(candidate)) {
                continue;
            }
            if (fallback == null) {
                fallback = candidate.immutable();
            }
            if (routeTarget == null || candidate.distSqr(routeTarget) < feet.distSqr(routeTarget)) {
                return candidate.immutable();
            }
        }
        return fallback;
    }

    private boolean canReachExact(BlockPos pos) {
        Path path = playerNpc.getNavigation().createPath(pos, 0);
        return path != null
                && path.canReach()
                && path.getEndNode() != null
                && path.getEndNode().asBlockPos().equals(pos);
    }

    private void resetProgressWatch() {
        progressTargetPos = targetItem == null
                ? null
                : (pickupPillarBasePos == null
                ? targetItem.blockPosition().immutable()
                : pickupPillarBasePos.immutable());
        bestProgressDistance = progressTargetPos == null
                ? Double.POSITIVE_INFINITY
                : progressDistanceTo(progressTargetPos);
        noProgressTicks = 0;
    }

    private double progressDistanceTo(BlockPos routeTarget) {
        if (routeTarget == null) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = routeTarget.getX() + 0.5D - playerNpc.getX();
        double dy = routeTarget.getY() - playerNpc.getY();
        double dz = routeTarget.getZ() + 0.5D - playerNpc.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void resetForNewPickupTarget() {
        stopPickupPathClear();
        pathStuckFallbackAi.stop();
        clearPickupPillar();
        skippedObstructions.clear();
        playerNpc.getNavigation().stop();
        repathTicks = 0;
        activeApproachTicks = 0;
        recoveryRetryCooldownTicks = 0;
        stuckRecoveryAttempts = 0;
        resetProgressWatch();
    }

    private String pickupRecoveryDetailPrefix() {
        if (targetItem == null || targetItem.getItem().isEmpty()) {
            return "pickup";
        }
        return "pickup " + targetItem.getItem().getHoverName().getString();
    }

    private void clearPickupPillar() {
        pickupPillarBasePos = null;
        if (pickupPillarAi != null) {
            pickupPillarAi.clear();
            pickupPillarAi = null;
        }
        helperToolAi.restoreMainHand();
    }

    private void updatePickupPillarDetail() {
        if (targetItem == null || pickupPillarBasePos == null) {
            return;
        }
        playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "pillaring to %s\ngiving up in %ds",
                targetItem.getItem().getHoverName().getString(),
                getRemainingPickupSeconds()
        ));
    }

    private boolean tryStartPathObstructionMining(BlockPos destination) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel) || destination == null) {
            return false;
        }
        if (clearBlockAi.isRunning()) {
            return true;
        }

        BlockPos obstruction = findPathObstructionToward(destination);
        return obstruction != null && startPickupPathClear(serverLevel, obstruction);
    }

    private boolean startPickupPathClear(ServerLevel serverLevel, BlockPos obstruction) {
        if (!isSafePickupClearTarget(serverLevel, obstruction)) {
            return false;
        }

        helperToolAi.restoreMainHand();
        obstructionClearTicks = 0;
        return clearBlockAi.start(
                serverLevel,
                obstruction,
                this::isPickupPathObstructionState,
                "clearing pickup path",
                1,
                OBSTRUCTION_BREAK_DISTANCE_SQR
        );
    }

    private boolean tickPathObstruction(ServerLevel serverLevel) {
        if (!clearBlockAi.isRunning()) {
            return false;
        }

        BlockPos attempted = clearBlockAi.targetPos();
        if (!isSafePickupClearTarget(serverLevel, attempted)
                || ++obstructionClearTicks > MAX_OBSTRUCTION_CLEAR_TICKS) {
            skipPickupObstruction(attempted);
            stopPickupPathClear();
            failedPathTicks += REPATH_INTERVAL_TICKS;
            resetProgressWatch();
            return true;
        }

        ClearBlockAi.TickResult result = clearBlockAi.tick(serverLevel);
        BlockPos resolvedTarget = clearBlockAi.targetPos();
        if (clearBlockAi.isRunning() && !isSafePickupClearTarget(serverLevel, resolvedTarget)) {
            skipPickupObstruction(resolvedTarget);
            stopPickupPathClear();
            failedPathTicks += REPATH_INTERVAL_TICKS;
            resetProgressWatch();
            return true;
        }

        String detail = clearBlockAi.detail();
        if (!detail.isBlank()) {
            playerNpc.setCurrentAiDetail(detail + "\ngiving up in " + getRemainingPickupSeconds() + "s");
        }
        if (result == ClearBlockAi.TickResult.RUNNING) {
            return true;
        }

        if (result == ClearBlockAi.TickResult.DONE) {
            failedPathTicks = 0;
            repathTicks = 0;
            stopPickupPathClear();
            resetProgressWatch();
            moveToTarget();
            return true;
        } else {
            skipPickupObstruction(attempted);
            failedPathTicks += REPATH_INTERVAL_TICKS;
        }
        stopPickupPathClear();
        resetProgressWatch();
        return true;
    }

    private void stopPickupPathClear() {
        clearBlockAi.stop();
        breakingBlockAi.stop();
        helperToolAi.restoreMainHand();
        obstructionClearTicks = 0;
    }

    private void skipPickupObstruction(BlockPos pos) {
        if (pos != null) {
            skippedObstructions.add(pos.immutable());
        }
    }

    private BlockPos findPathObstructionToward(BlockPos destination) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel) || destination == null) {
            return null;
        }

        BlockPos navigationCollision = findNavigationPathCollision(serverLevel, destination);
        if (navigationCollision != null) {
            return navigationCollision;
        }

        BlockPos feet = playerNpc.blockPosition();
        List<BlockPos> candidates = new java.util.ArrayList<>();
        candidates.add(feet.above());
        candidates.add(feet.above(2));
        candidates.add(destination);
        candidates.add(destination.above());
        addLineObstructionCandidates(candidates, feet, destination);

        Set<BlockPos> seen = new HashSet<>();
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))
                .thenComparingDouble(pos -> pos.distSqr(destination)));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || skippedObstructions.contains(immutable)
                    || !serverLevel.isInWorldBounds(immutable)
                    || !serverLevel.getWorldBorder().isWithinBounds(immutable)
                    || playerNpc.distanceToSqr(
                    immutable.getX() + 0.5D,
                    immutable.getY() + 0.5D,
                    immutable.getZ() + 0.5D
            ) > OBSTRUCTION_BREAK_DISTANCE_SQR) {
                continue;
            }

            BlockState state = serverLevel.getBlockState(immutable);
            if (isPathObstructionBlock(serverLevel, immutable, state)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean requestOwnedFarmGateEgress(ItemEntity item) {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || item == null) {
            return false;
        }
        var plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos itemPos = item.blockPosition();
        if (plan == null
                || !FarmAi.isInsideFarmFootprint(plan, feet)
                || FarmAi.isInsideFarmFootprint(plan, itemPos)) {
            return false;
        }
        this.playerNpc.requestUpwardEscapeTo(itemPos, 20 * 20, 0);
        this.playerNpc.setIdleTraceDetail("pickup waiting for owned farm gate egress @ "
                + plan.gatePos().getX() + " " + plan.gatePos().getY() + " " + plan.gatePos().getZ(), 40);
        return true;
    }

    /**
     * Minecraft can keep an accepted path in the running/not-stuck state even when the NPC's body
     * makes no useful progress toward its next node. Inspect the live collision volume swept toward
     * the next two nodes so recovery clears the real body/head blocker instead of guessing from the
     * dropped item's block position.
     */
    private BlockPos findNavigationPathCollision(ServerLevel serverLevel, BlockPos destination) {
        Path path = playerNpc.getNavigation().getPath();
        if (path == null
                || path.isDone()
                || path.getNodeCount() <= 0
                || path.getEndNode() == null
                || path.getEndNode().asBlockPos().distSqr(destination) > 2.0D) {
            return null;
        }

        int firstNode = Math.max(0, path.getNextNodeIndex());
        int endNode = Math.min(path.getNodeCount(), firstNode + 2);
        Set<BlockPos> routeSupports = new HashSet<>();
        routeSupports.add(playerNpc.blockPosition().below().immutable());
        for (int index = firstNode; index < endNode; index++) {
            BlockPos node = path.getNode(index).asBlockPos();
            routeSupports.add(node.below().immutable());
            BlockPos blocker = findSweptBodyCollision(serverLevel, node, routeSupports);
            if (blocker != null) {
                return blocker;
            }
        }
        return null;
    }

    private BlockPos findSweptBodyCollision(
            ServerLevel serverLevel,
            BlockPos node,
            Set<BlockPos> routeSupports
    ) {
        AABB currentBox = playerNpc.getBoundingBox();
        AABB nodeBox = currentBox.move(
                node.getX() + 0.5D - playerNpc.getX(),
                node.getY() - playerNpc.getY(),
                node.getZ() + 0.5D - playerNpc.getZ()
        );
        AABB sweptBody = new AABB(
                Math.min(currentBox.minX, nodeBox.minX) - 0.04D,
                Math.min(currentBox.minY, nodeBox.minY) + 0.02D,
                Math.min(currentBox.minZ, nodeBox.minZ) - 0.04D,
                Math.max(currentBox.maxX, nodeBox.maxX) + 0.04D,
                Math.max(currentBox.maxY, nodeBox.maxY) + 0.04D,
                Math.max(currentBox.maxZ, nodeBox.maxZ) + 0.04D
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
            if (routeSupports.contains(pos)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (!isPathObstructionBlock(serverLevel, pos, state)
                    || state.getCollisionShape(serverLevel, pos).toAabbs().stream()
                    .map(box -> box.move(pos))
                    .noneMatch(box -> box.intersects(sweptBody))) {
                continue;
            }
            double distance = playerNpc.distanceToSqr(
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D
            );
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best == null ? null : best.immutable();
    }

    private void addLineObstructionCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos destination) {
        double dx = destination.getX() - feet.getX();
        double dy = destination.getY() - feet.getY();
        double dz = destination.getZ() - feet.getZ();
        double steps = Math.max(1.0D, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
        int maxSteps = Math.min(8, (int) Math.ceil(steps));
        for (int i = 1; i <= maxSteps; i++) {
            double progress = i / (double) maxSteps;
            int x = feet.getX() + (int) Math.round(dx * progress);
            int y = feet.getY() + (int) Math.round(dy * progress);
            int z = feet.getZ() + (int) Math.round(dz * progress);
            BlockPos routeFeet = new BlockPos(x, y, z);
            candidates.add(routeFeet);
            candidates.add(routeFeet.above());
            candidates.add(routeFeet.above(2));
        }
    }

    private boolean isPathObstructionBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return isPickupPathObstructionState(state)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state)
                && !playerNpc.isTemporaryPillarSupport(pos)
                && !FarmAi.isOwnedFarmDestructionProtected(playerNpc, pos)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(playerNpc, serverLevel, pos)
                && !isProtectedHomeBlock(pos)
                && !skippedObstructions.contains(pos);
    }

    private boolean isSafePickupClearTarget(ServerLevel serverLevel, BlockPos pos) {
        return pos != null
                && serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                <= OBSTRUCTION_BREAK_DISTANCE_SQR
                && isPathObstructionBlock(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    private boolean isPickupPathObstructionState(BlockState state) {
        return state != null
                && !state.isAir()
                && state.getFluidState().isEmpty()
                && hasRequiredToolFor(state);
    }

    private boolean canStandAt(net.minecraft.server.level.ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)
                || !serverLevel.hasChunkAt(pos.above())
                || !serverLevel.hasChunkAt(pos.below())) {
            return false;
        }

        var feet = serverLevel.getBlockState(pos);
        var head = serverLevel.getBlockState(pos.above());
        BlockPos floorPos = pos.below();
        return feet.getCollisionShape(serverLevel, pos).isEmpty()
                && head.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && serverLevel.getBlockState(floorPos).isSolidRender();
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(playerNpc);
        return PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos)
                || homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean hasRequiredToolFor(BlockState state) {
        return !isPickaxeBlock(state) || helperToolAi.hasTool(ItemTags.PICKAXES);
    }

    private boolean isPickaxeBlock(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    private void updateDetail() {
        int mode = targetItem != null && !targetItem.getItem().isEmpty()
                ? 3
                : prioritySearchCenter != null ? 1 : 2;
        UUID targetId = mode == 3 ? targetItem.getUUID() : null;
        BlockPos priorityCenter = mode == 1 ? prioritySearchCenter : null;
        boolean structureChanged = mode != lastDetailMode
                || !java.util.Objects.equals(targetId, lastDetailTargetId)
                || !java.util.Objects.equals(priorityCenter, lastDetailPriorityCenter);
        if (!structureChanged && playerNpc.tickCount < nextDetailProgressRefreshTick) {
            return;
        }
        lastDetailMode = mode;
        lastDetailTargetId = targetId;
        lastDetailPriorityCenter = priorityCenter == null ? null : priorityCenter.immutable();
        nextDetailProgressRefreshTick = playerNpc.tickCount + DETAIL_PROGRESS_REFRESH_INTERVAL_TICKS;

        if (targetItem == null || targetItem.getItem().isEmpty()) {
            if (prioritySearchCenter != null) {
                playerNpc.setCurrentAiDetail(String.format(
                        java.util.Locale.ROOT,
                        "animal drops\n@ %d %d %d\ngiving up in %ds",
                        prioritySearchCenter.getX(),
                        prioritySearchCenter.getY(),
                        prioritySearchCenter.getZ(),
                        getRemainingPickupSeconds()
                ));
                return;
            }
            playerNpc.setCurrentAiDetail(String.format(
                    java.util.Locale.ROOT,
                    "nearby drops\ngiving up in %ds",
                    getRemainingPickupSeconds()
            ));
            return;
        }

        playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "%s\n@ %d %d %d\ngiving up in %ds",
                targetItem.getItem().getHoverName().getString(),
                targetItem.blockPosition().getX(),
                targetItem.blockPosition().getY(),
                targetItem.blockPosition().getZ(),
                getRemainingPickupSeconds()
        ));
    }

    private void resetDetailRefresh() {
        lastDetailMode = -1;
        lastDetailTargetId = null;
        lastDetailPriorityCenter = null;
        nextDetailProgressRefreshTick = 0;
    }

    private int getRemainingPickupSeconds() {
        int remainingTicks = Math.max(0, MAX_PICKUP_TICKS - pickupTicks);
        return Math.max(0, (remainingTicks + 19) / 20);
    }

    private boolean ensurePathBatchAdmission(ServerLevel serverLevel) {
        if (this.lastAdmittedPathBatchTick == this.playerNpc.tickCount) {
            return true;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            return false;
        }
        this.lastAdmittedPathBatchTick = this.playerNpc.tickCount;
        return true;
    }

    private record PickupRoute(Path path, BlockPos obstruction, boolean pillar) {
    }

    private final class NavigationPathBudget {
        private int remaining;

        private NavigationPathBudget(int maximum) {
            this.remaining = Math.max(0, maximum);
        }

        private boolean exhausted() {
            return this.remaining <= 0;
        }

        private Path createPath(ItemEntity item) {
            if (item == null || this.exhausted()) {
                return null;
            }
            this.remaining--;
            return PathNavigationAi.createBoundedPath(
                    playerNpc,
                    item.blockPosition(),
                    targetSelectionPathNodeMultiplier()
            );
        }

        private Path createPath(BlockPos pos) {
            if (pos == null || this.exhausted()) {
                return null;
            }
            this.remaining--;
            return PathNavigationAi.createBoundedPath(
                    playerNpc,
                    pos,
                    targetSelectionPathNodeMultiplier()
            );
        }

        private float targetSelectionPathNodeMultiplier() {
            return PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                    ? OVERLOADED_TARGET_SELECTION_PATH_NODE_MULTIPLIER
                    : TARGET_SELECTION_PATH_NODE_MULTIPLIER;
        }
    }

}
