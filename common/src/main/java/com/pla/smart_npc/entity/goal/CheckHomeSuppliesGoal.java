package com.pla.smart_npc.entity.goal;
import com.pla.smart_npc.platform.NpcPlatform;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.ChestAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcBaseUtil;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

public class CheckHomeSuppliesGoal extends Goal {
    private static final String LAST_CHEST_CHECK_DAY = "PlayerNpcLastHomeChestCheckDay";
    private static final String LAST_FURNACE_CHECK_DAY = "PlayerNpcLastHomeFurnaceCheckDay";
    private static final String LAST_TOOL_CHEST_CHECK_TIME = "PlayerNpcLastToolChestCheckTime";
    private static final double HOME_ACTION_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double CONTAINER_USE_DISTANCE_SQR = 2.25D * 2.25D;
    private static final double CONTAINER_STAND_REACHED_SQR = 1.25D * 1.25D;
    private static final int ACTION_DELAY_TICKS = 8;
    private static final int STORAGE_REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_WITHDRAW_STACKS = 6;
    private static final int TOOL_CHEST_RECHECK_TICKS = 20 * 10;
    private static final int FOOD_RESERVE = 6;
    private static final int TORCH_FUEL_RESERVE = 8;
    private static final int GENERAL_FUEL_RESERVE = 4;
    private static final int ARROW_RESERVE = 16;
    private static final int BLOCK_RESERVE = 24;
    private static final int FISHING_STRING_RESERVE = 2;
    private static final float STORAGE_PATH_NODE_MULTIPLIER = 0.05F;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos baseAnchor;
    private boolean nonBuilderBase;
    private BlockPos targetPos;
    private BlockPos standPos;
    private Mode mode;
    private int actionDelayTicks;
    private int repathTicks;
    private boolean finished;
    private boolean acted;
    private boolean chestOpen;
    private SupplyNeedSnapshot supplyNeed;
    private PlayerNpcBuildMaterialUtil.MissingBuildMaterialNeed missingBuildMaterialNeed;
    private Path plannedStandPath;
    private BlockPos standSearchTarget;
    private Mode standSearchMode;
    private List<BlockPos> standSearchCandidates = List.of();
    private int standSearchCursor;
    private int lastExpensiveWorkAdmissionTick = Integer.MIN_VALUE;

    public CheckHomeSuppliesGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || serverLevel.isNight()) {
            return false;
        }
        if (this.playerNpc.isStoneAccessClearing()) {
            if (!this.playerNpc.getIdleTraceDetail().startsWith("stone ")) {
                this.playerNpc.setIdleTraceDetail(
                        "home supplies blocked: stone access clearing "
                                + this.playerNpc.getStoneAccessClearCooldown() + "t",
                        20 * 4
                );
            }
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.resetPlan();
        this.nonBuilderBase = !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING);
        this.homeArea = this.nonBuilderBase ? null : PlayerNpcHomeUtil.getHome(this.playerNpc).orElse(null);
        this.baseAnchor = this.homeArea == null
                ? PlayerNpcBaseUtil.getNonBuilderBase(this.playerNpc, serverLevel).orElse(null)
                : PlayerNpcHomeUtil.center(this.homeArea);
        if (this.baseAnchor == null || !this.isNearHome()) {
            this.clearStandSearch();
            return false;
        }

        long day = this.currentDay(serverLevel);
        boolean toolChestDue = this.needsToolSupply()
                && this.canRetryToolChestCheck(serverLevel);
        boolean chestDue = toolChestDue || !this.checkedToday(LAST_CHEST_CHECK_DAY, day);
        boolean furnaceDue = !this.nonBuilderBase && !this.checkedToday(LAST_FURNACE_CHECK_DAY, day);
        if (!chestDue && !furnaceDue) {
            this.clearStandSearch();
            return false;
        }

        this.supplyNeed = this.createSupplyNeedSnapshot(serverLevel, furnaceDue);
        if (PlayerNpcBuildMaterialUtil.isMissingBuildMaterialSearchPending(this.playerNpc)) {
            this.deferCanUseForSharedWork("home supply build-material check continuing in bounded passes");
            return false;
        }
        if (!this.supplyNeed.hasAnyNeed()) {
            this.clearStandSearch();
            return false;
        }

        boolean urgentChestNeed = this.supplyNeed.needsToolSupply();
        if (chestDue) {
            BlockPos chest = this.retainedStandSearchTarget(serverLevel, Mode.CHEST);
            if (chest == null) {
                chest = this.findHomeChest(
                        serverLevel,
                        toolChestDue && this.checkedToday(LAST_CHEST_CHECK_DAY, day)
                );
            }
            if (chest != null
                    && serverLevel.getBlockEntity(chest) instanceof Container container
                    && this.hasWithdrawCandidate(serverLevel, container)) {
                PlanResult result = this.plan(serverLevel, chest, Mode.CHEST);
                if (result == PlanResult.FOUND) {
                    return true;
                }
                if (result == PlanResult.PENDING) {
                    this.deferCanUseForSharedWork("home chest stand selection continuing in bounded passes");
                    return false;
                }
            }
            if (urgentChestNeed) {
                this.markToolChestChecked(serverLevel);
            }
            this.markChecked(LAST_CHEST_CHECK_DAY, day);
            this.clearStandSearch();
            // Chest discovery and stand planning own this activation batch. A due furnace is
            // evaluated separately so the two storage modes cannot compound their path work.
            this.canUseThrottle.retryIn(this.playerNpc, 1);
            return false;
        }

        if (furnaceDue) {
            BlockPos furnace = this.retainedStandSearchTarget(serverLevel, Mode.FURNACE);
            if (furnace == null) {
                furnace = this.findHomeFurnace(serverLevel);
            }
            if (furnace != null) {
                PlanResult result = this.plan(serverLevel, furnace, Mode.FURNACE);
                if (result == PlanResult.FOUND) {
                    return true;
                }
                if (result == PlanResult.PENDING) {
                    this.deferCanUseForSharedWork("home furnace stand selection continuing in bounded passes");
                    return false;
                }
            }
            this.markChecked(LAST_FURNACE_CHECK_DAY, day);
            this.clearStandSearch();
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.targetPos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && !serverLevel.isNight();
    }

    @Override
    public void start() {
        this.actionDelayTicks = 0;
        this.repathTicks = 0;
        this.finished = false;
        this.acted = false;
        this.chestOpen = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.checking_home_supplies");
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null || this.mode == null) {
            this.finished = true;
            return;
        }

        PlanResult standResult = this.ensureStand(serverLevel);
        if (standResult == PlanResult.PENDING) {
            this.playerNpc.setCurrentAiDetail("home storage stand recovery continuing in bounded passes");
            return;
        }
        if (standResult == PlanResult.FAILED) {
            this.markModeChecked(serverLevel);
            this.finished = true;
            return;
        }

        this.lookAtTarget();
        if (!this.isAtStand()) {
            this.playerNpc.setCurrentAiDetail("walking to home storage");
            if (!this.moveToStand()) {
                this.markModeChecked(serverLevel);
                this.finished = true;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.mode == Mode.CHEST && !this.chestOpen) {
            ChestAi.openChest(serverLevel, this.targetPos, this.playerNpc);
            this.chestOpen = true;
            this.actionDelayTicks = ACTION_DELAY_TICKS;
            this.updateDetail();
            return;
        }

        if (this.actionDelayTicks-- > 0) {
            return;
        }

        if (this.mode == Mode.CHEST) {
            this.acted = this.withdrawFromChest(serverLevel);
            if (this.chestOpen) {
                ChestAi.closeChest(serverLevel, this.targetPos);
                this.chestOpen = false;
            }
        } else {
            this.acted = this.takeFurnaceOutput(serverLevel);
        }

        this.markModeChecked(serverLevel);
        this.finished = true;
    }

    @Override
    public void stop() {
        if (this.chestOpen
                && this.targetPos != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && serverLevel.getBlockState(this.targetPos).is(Blocks.CHEST)) {
            ChestAi.closeChest(serverLevel, this.targetPos);
        }

        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
    }

    private PlanResult plan(ServerLevel serverLevel, BlockPos pos, Mode mode) {
        if (!pos.equals(this.standSearchTarget) || mode != this.standSearchMode) {
            this.standSearchTarget = pos.immutable();
            this.standSearchMode = mode;
            this.standSearchCandidates = this.createStandCandidates(pos, mode);
            this.standSearchCursor = 0;
        }

        BlockPos center = this.playerNpc.blockPosition();
        while (this.standSearchCursor < this.standSearchCandidates.size()) {
            BlockPos candidate = this.standSearchCandidates.get(this.standSearchCursor);
            boolean usable = mode == Mode.CHEST
                    ? ChestAi.canStandAt(serverLevel, candidate)
                    : this.canStandAt(serverLevel, candidate)
                    && this.distanceToTargetSqr(candidate, pos) <= CONTAINER_USE_DISTANCE_SQR;
            if (!usable) {
                this.standSearchCursor++;
                continue;
            }
            if (candidate.equals(center)) {
                this.acceptPlan(pos, candidate, mode, null);
                return PlanResult.FOUND;
            }
            if (!this.tryAcquireExpensiveWork(serverLevel)) {
                return PlanResult.PENDING;
            }

            this.standSearchCursor++;
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    STORAGE_PATH_NODE_MULTIPLIER
            );
            if (path != null && path.canReach()) {
                this.acceptPlan(pos, candidate, mode, path);
                return PlanResult.FOUND;
            }
            return this.standSearchCursor < this.standSearchCandidates.size()
                    ? PlanResult.PENDING
                    : this.completeStandSearchMiss();
        }
        return this.completeStandSearchMiss();
    }

    private List<BlockPos> createStandCandidates(BlockPos pos, Mode mode) {
        List<BlockPos> candidates = new ArrayList<>();
        if (mode == Mode.FURNACE) {
            candidates.add(this.playerNpc.blockPosition().immutable());
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(pos.relative(direction).immutable());
        }
        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        return List.copyOf(candidates);
    }

    private void acceptPlan(BlockPos pos, BlockPos stand, Mode mode, Path path) {
        this.targetPos = pos.immutable();
        this.standPos = stand;
        this.mode = mode;
        this.plannedStandPath = path;
        this.clearStandSearch();
    }

    private PlanResult completeStandSearchMiss() {
        this.clearStandSearch();
        return PlanResult.FAILED;
    }

    private boolean withdrawFromChest(ServerLevel serverLevel) {
        if (!(serverLevel.getBlockEntity(this.targetPos) instanceof ChestBlockEntity chest)) {
            return false;
        }

        boolean movedAny = false;
        int movedStacks = 0;
        for (int slot = 0; slot < chest.getContainerSize() && movedStacks < MAX_WITHDRAW_STACKS; slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty() || !this.shouldWithdrawStack(serverLevel, stack)) {
                continue;
            }

            int moved = this.moveFromContainerToInventory(chest, slot, this.desiredWithdrawCount(serverLevel, stack));
            if (moved <= 0) {
                continue;
            }

            movedAny = true;
            movedStacks++;
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.playSound(null, this.targetPos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
        }
        chest.setChanged();
        return movedAny;
    }

    private boolean hasWithdrawCandidate(ServerLevel serverLevel, Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && this.shouldWithdrawStack(serverLevel, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean takeFurnaceOutput(ServerLevel serverLevel) {
        if (!(serverLevel.getBlockEntity(this.targetPos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) {
            return false;
        }

        ItemStack moved = output.copy();
        furnace.setItem(2, ItemStack.EMPTY);
        furnace.setChanged();
        if (!this.addToInventory(moved)) {
            this.playerNpc.spawnAtLocation(moved);
        }
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        serverLevel.playSound(null, this.targetPos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
        return true;
    }

    private int moveFromContainerToInventory(Container container, int slot, int maxCount) {
        if (maxCount <= 0) {
            return 0;
        }

        ItemStack source = container.getItem(slot);
        if (source.isEmpty()) {
            return 0;
        }

        ItemStack moving = source.copy();
        moving.setCount(Math.min(maxCount, source.getCount()));
        int original = moving.getCount();
        this.addToInventory(moving);
        int moved = original - moving.getCount();
        if (moved <= 0) {
            return 0;
        }

        source.shrink(moved);
        if (source.isEmpty()) {
            container.setItem(slot, ItemStack.EMPTY);
        }
        container.setChanged();
        return moved;
    }

    private boolean addToInventory(ItemStack stack) {
        if (stack.isEmpty()) {
            return true;
        }

        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (slotStack.isEmpty()
                    || !ItemStack.isSameItemSameTags(slotStack, stack)
                    || slotStack.getCount() >= slotStack.getMaxStackSize()) {
                continue;
            }

            int transferable = Math.min(stack.getCount(), slotStack.getMaxStackSize() - slotStack.getCount());
            slotStack.grow(transferable);
            stack.shrink(transferable);
        }

        for (int i = 0; i < inventory.getContainerSize() && !stack.isEmpty(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                continue;
            }

            ItemStack inserted = stack.copy();
            inserted.setCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
            inventory.setItem(i, inserted);
            stack.shrink(inserted.getCount());
        }
        inventory.setChanged();
        return stack.isEmpty();
    }

    private boolean shouldWithdrawStack(ServerLevel serverLevel, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        SupplyNeedSnapshot need = this.currentSupplyNeed(serverLevel);
        return this.needsMissingTool(need, stack)
                || need.fishingRod() && stack.getItem() instanceof FishingRodItem
                || need.fishingString() && stack.is(Items.STRING)
                || need.food() && stack.isEdible()
                || need.wood() && this.isWoodSupply(stack)
                || need.toolCraftingMaterials() && this.isToolCraftingSupply(stack)
                || need.fuel() && (this.isTorchFuel(stack) || this.isFuel(stack))
                || need.arrows() && stack.getItem() instanceof ArrowItem
                || need.buildingBlocks() && this.isCurrentBuildSupply(serverLevel, stack)
                || this.isAlwaysUsefulSupply(stack);
    }

    private int desiredWithdrawCount(ServerLevel serverLevel, ItemStack stack) {
        if (this.isToolStack(stack)) {
            return 1;
        }
        if (stack.is(Items.STRING)) {
            int missingString = FISHING_STRING_RESERVE
                    - this.countInventory(candidate -> candidate.is(Items.STRING));
            return Math.min(stack.getCount(), Math.max(1, missingString));
        }
        if (stack.isEdible()) {
            return Math.min(stack.getCount(), 8);
        }
        if (this.isTorchFuel(stack) || this.isFuel(stack)) {
            return Math.min(stack.getCount(), 16);
        }
        if (stack.getItem() instanceof ArrowItem) {
            return Math.min(stack.getCount(), 32);
        }
        SupplyNeedSnapshot need = this.currentSupplyNeed(serverLevel);
        if (this.isWoodSupply(stack) || need.buildingBlocks() && this.isCurrentBuildSupply(serverLevel, stack)) {
            return Math.min(stack.getCount(), 32);
        }
        return Math.min(stack.getCount(), 16);
    }

    private boolean needsMissingTool(SupplyNeedSnapshot need, ItemStack stack) {
        return need.axe() && stack.getItem() instanceof AxeItem
                || need.pickaxe() && stack.getItem() instanceof PickaxeItem
                || need.shovel() && stack.getItem() instanceof ShovelItem
                || need.sword() && stack.getItem() instanceof SwordItem;
    }

    private boolean isToolStack(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof PickaxeItem
                || stack.getItem() instanceof ShovelItem
                || stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof FishingRodItem);
    }

    private boolean needsToolSupply() {
        return this.needsTool(AxeItem.class)
                || this.needsTool(PickaxeItem.class)
                || this.needsTool(ShovelItem.class)
                || this.needsFishingRod()
                || this.needsFishingString();
    }

    private boolean needsToolCraftingMaterials() {
        return this.needsTool(AxeItem.class) || this.needsTool(PickaxeItem.class);
    }

    private boolean needsTool(Class<?> toolClass) {
        return !toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())
                && !toolClass.isInstance(this.playerNpc.getOffhandItem().getItem())
                && !InventoryUtils.hasItem(this.playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private boolean needsFood() {
        return this.countInventory(ItemStack::isEdible) < FOOD_RESERVE;
    }

    private boolean needsWood() {
        return this.playerNpc.shouldPrioritizeLogGathering();
    }

    private boolean needsFuel() {
        return this.countInventory(this::isTorchFuel) < TORCH_FUEL_RESERVE
                || this.countInventory(this::isFuel) < GENERAL_FUEL_RESERVE;
    }

    private boolean needsArrows() {
        return this.hasRangedWeapon() && this.countInventory(stack -> stack.getItem() instanceof ArrowItem) < ARROW_RESERVE;
    }

    private boolean needsBuildingBlocks(ServerLevel serverLevel) {
        this.missingBuildMaterialNeed = PlayerNpcBuildMaterialUtil
                .findMissingBuildMaterialNeed(serverLevel, this.playerNpc)
                .orElse(null);
        return this.missingBuildMaterialNeed != null;
    }

    private boolean needsFishingRod() {
        return this.playerNpc.hasInterest(PlayerNpcInterest.FISHING)
                && !PlayerNpcFishingGoal.hasFishingRod(this.playerNpc);
    }

    private boolean needsFishingString() {
        return this.playerNpc.hasInterest(PlayerNpcInterest.FISHING)
                && this.countInventory(stack -> stack.is(Items.STRING)) < FISHING_STRING_RESERVE;
    }

    private SupplyNeedSnapshot currentSupplyNeed(ServerLevel serverLevel) {
        if (this.supplyNeed == null) {
            boolean furnaceDue = !this.nonBuilderBase
                    && !this.checkedToday(LAST_FURNACE_CHECK_DAY, this.currentDay(serverLevel));
            this.supplyNeed = this.createSupplyNeedSnapshot(serverLevel, furnaceDue);
        }
        return this.supplyNeed;
    }

    private SupplyNeedSnapshot createSupplyNeedSnapshot(ServerLevel serverLevel, boolean includeFurnaceOutput) {
        boolean axe = this.needsTool(AxeItem.class);
        boolean pickaxe = this.needsTool(PickaxeItem.class);
        boolean shovel = this.needsTool(ShovelItem.class);
        boolean sword = this.needsTool(SwordItem.class);
        boolean fishingRod = this.needsFishingRod();
        boolean fishingString = this.needsFishingString();
        boolean food = this.needsFood();
        boolean wood = this.needsWood();
        boolean toolMaterials = axe || pickaxe;
        boolean fuel = this.needsFuel();
        boolean arrows = this.needsArrows();
        boolean buildingBlocks = this.needsBuildingBlocks(serverLevel);
        boolean furnaceOutput = includeFurnaceOutput && this.hasHomeFurnaceOutput(serverLevel);
        return new SupplyNeedSnapshot(
                axe,
                pickaxe,
                shovel,
                sword,
                fishingRod,
                fishingString,
                food,
                wood,
                toolMaterials,
                fuel,
                arrows,
                buildingBlocks,
                furnaceOutput
        );
    }

    private boolean hasRangedWeapon() {
        if (this.isRangedWeapon(this.playerNpc.getMainHandItem()) || this.isRangedWeapon(this.playerNpc.getOffhandItem())) {
            return true;
        }

        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (this.isRangedWeapon(inventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isRangedWeapon(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof ProjectileWeaponItem);
    }

    private boolean hasHomeFurnaceOutput(ServerLevel serverLevel) {
        BlockPos furnacePos = this.findHomeFurnace(serverLevel);
        return furnacePos != null
                && serverLevel.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace
                && !furnace.getItem(2).isEmpty();
    }

    private int countInventory(Predicate<ItemStack> matcher) {
        int count = 0;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isWoodSupply(ItemStack stack) {
        return stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.PLANKS)
                || stack.is(Items.STICK);
    }

    private boolean isToolCraftingSupply(ItemStack stack) {
        return this.isWoodSupply(stack)
                || stack.is(Items.COBBLESTONE)
                || stack.is(Items.COBBLED_DEEPSLATE)
                || stack.is(Items.IRON_INGOT)
                || stack.is(Items.DIAMOND);
    }

    private boolean isTorchFuel(ItemStack stack) {
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
    }

    private boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack);
    }

    private boolean isCurrentBuildSupply(ServerLevel serverLevel, ItemStack stack) {
        return this.missingBuildMaterialNeed != null
                && PlayerNpcBuildMaterialUtil.isCurrentBuildInputForTarget(
                stack,
                this.missingBuildMaterialNeed.targetState()
        );
    }

    private boolean isAlwaysUsefulSupply(ItemStack stack) {
        return stack.is(Items.TORCH)
                || stack.is(Items.FURNACE)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.is(Items.WATER_BUCKET)
                || stack.is(Items.LAVA_BUCKET)
                || stack.is(Items.BUCKET)
                || stack.is(Items.IRON_INGOT)
                || stack.is(Items.GOLD_INGOT)
                || stack.is(Items.COPPER_INGOT)
                || stack.is(Items.RAW_IRON)
                || stack.is(Items.RAW_GOLD)
                || stack.is(Items.RAW_COPPER);
    }

    private BlockPos findHomeChest(ServerLevel serverLevel, boolean preferKnownOwnedChest) {
        if (!this.nonBuilderBase) {
            if (preferKnownOwnedChest) {
                BlockPos ownedChest = ChestAi.findOwnedSupplyChest(this.playerNpc, serverLevel);
                if (ownedChest != null) {
                    return ownedChest;
                }
            }
            return ChestAi.findHomeSupplyChest(this.playerNpc, serverLevel, this.homeArea);
        }
        BlockPos ownedChest = ChestAi.findOwnedSupplyChest(this.playerNpc, serverLevel);
        return ownedChest != null
                && this.baseAnchor != null
                && this.baseAnchor.distSqr(ownedChest) <= HOME_ACTION_DISTANCE_SQR
                ? ownedChest
                : null;
    }

    private BlockPos findHomeFurnace(ServerLevel serverLevel) {
        if (this.homeArea == null) {
            return null;
        }
        return this.findBlock(serverLevel, Blocks.FURNACE);
    }

    private BlockPos retainedStandSearchTarget(ServerLevel serverLevel, Mode expectedMode) {
        if (this.standSearchMode != expectedMode
                || this.standSearchTarget == null
                || !serverLevel.hasChunkAt(this.standSearchTarget)) {
            return null;
        }
        boolean stillValid = expectedMode == Mode.CHEST
                ? serverLevel.getBlockEntity(this.standSearchTarget) instanceof Container
                : serverLevel.getBlockState(this.standSearchTarget).is(Blocks.FURNACE);
        if (!stillValid) {
            this.clearStandSearch();
            return null;
        }
        return this.standSearchTarget;
    }

    private BlockPos findBlock(ServerLevel serverLevel, net.minecraft.world.level.block.Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin(),
                this.homeArea.origin().offset(this.homeArea.width() - 1, 3, this.homeArea.depth() - 1))) {
            if (serverLevel.hasChunkAt(pos) && serverLevel.getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private PlanResult ensureStand(ServerLevel serverLevel) {
        if (this.standPos != null && this.canStandAt(serverLevel, this.standPos)) {
            return PlanResult.FOUND;
        }
        this.standPos = null;
        this.plannedStandPath = null;
        return this.targetPos == null || this.mode == null
                ? PlanResult.FAILED
                : this.plan(serverLevel, this.targetPos, this.mode);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean isAtStand() {
        return this.standPos != null
                && this.playerNpc.distanceToSqr(this.standPos.getX() + 0.5D, this.standPos.getY(), this.standPos.getZ() + 0.5D) <= CONTAINER_STAND_REACHED_SQR
                && this.distanceToTargetSqr(this.playerNpc.blockPosition(), this.targetPos) <= CONTAINER_USE_DISTANCE_SQR + 1.0D;
    }

    private boolean moveToStand() {
        if (this.standPos == null) {
            return false;
        }
        if (this.repathTicks > 0
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck()) {
            this.repathTicks--;
            return true;
        }
        this.repathTicks = STORAGE_REPATH_INTERVAL_TICKS;
        Path path = this.plannedStandPath;
        this.plannedStandPath = null;
        if (path == null || !path.canReach()) {
            if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                    || !this.tryAcquireExpensiveWork(serverLevel)) {
                this.repathTicks = 1 + this.playerNpc.getRandom().nextInt(4);
                this.playerNpc.setCurrentAiDetail("home storage path queued for shared expensive-work slice");
                return true;
            }
            path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    this.standPos,
                    STORAGE_PATH_NODE_MULTIPLIER
            );
        }
        if (path == null || !path.canReach()) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private double distanceToTargetSqr(BlockPos stand, BlockPos target) {
        double dx = stand.getX() + 0.5D - (target.getX() + 0.5D);
        double dy = stand.getY() + 0.5D - (target.getY() + 0.5D);
        double dz = stand.getZ() + 0.5D - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean isNearHome() {
        return this.baseAnchor != null
                && this.playerNpc.distanceToSqr(
                this.baseAnchor.getX() + 0.5D,
                this.baseAnchor.getY(),
                this.baseAnchor.getZ() + 0.5D
        ) <= HOME_ACTION_DISTANCE_SQR;
    }

    private long currentDay(ServerLevel serverLevel) {
        return serverLevel.getDayTime() / 24000L;
    }

    private boolean canRetryToolChestCheck(ServerLevel serverLevel) {
        return !NpcPlatform.persistentData(this.playerNpc).contains(LAST_TOOL_CHEST_CHECK_TIME, Tag.TAG_LONG)
                || serverLevel.getGameTime() - NpcPlatform.persistentData(this.playerNpc).getLong(LAST_TOOL_CHEST_CHECK_TIME) >= TOOL_CHEST_RECHECK_TICKS;
    }

    private void markToolChestChecked(ServerLevel serverLevel) {
        NpcPlatform.persistentData(this.playerNpc).putLong(LAST_TOOL_CHEST_CHECK_TIME, serverLevel.getGameTime());
    }

    private boolean checkedToday(String key, long day) {
        return NpcPlatform.persistentData(this.playerNpc).contains(key, Tag.TAG_LONG)
                && NpcPlatform.persistentData(this.playerNpc).getLong(key) == day;
    }

    private void markModeChecked(ServerLevel serverLevel) {
        if (this.mode == Mode.CHEST) {
            this.markChecked(LAST_CHEST_CHECK_DAY, this.currentDay(serverLevel));
        } else if (this.mode == Mode.FURNACE) {
            this.markChecked(LAST_FURNACE_CHECK_DAY, this.currentDay(serverLevel));
        }
    }

    private void markChecked(String key, long day) {
        NpcPlatform.persistentData(this.playerNpc).putLong(key, day);
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

    private void updateDetail() {
        if (this.targetPos == null || this.mode == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        String type = this.mode == Mode.CHEST ? "chest" : "furnace";
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "checking %s @ %d %d %d",
                type,
                this.targetPos.getX(),
                this.targetPos.getY(),
                this.targetPos.getZ()
        ));
    }

    private void resetPlan() {
        this.homeArea = null;
        this.baseAnchor = null;
        this.nonBuilderBase = false;
        this.targetPos = null;
        this.standPos = null;
        this.plannedStandPath = null;
        this.mode = null;
        this.actionDelayTicks = 0;
        this.repathTicks = 0;
        this.finished = false;
        this.acted = false;
        this.chestOpen = false;
        this.supplyNeed = null;
        this.missingBuildMaterialNeed = null;
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

    private void deferCanUseForSharedWork(String detail) {
        this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(4));
        this.playerNpc.setIdleTraceDetail(detail, 20 * 4);
    }

    private void clearStandSearch() {
        this.standSearchTarget = null;
        this.standSearchMode = null;
        this.standSearchCandidates = List.of();
        this.standSearchCursor = 0;
    }

    private enum PlanResult {
        FOUND,
        PENDING,
        FAILED
    }

    private enum Mode {
        CHEST,
        FURNACE
    }

    private record SupplyNeedSnapshot(
            boolean axe,
            boolean pickaxe,
            boolean shovel,
            boolean sword,
            boolean fishingRod,
            boolean fishingString,
            boolean food,
            boolean wood,
            boolean toolCraftingMaterials,
            boolean fuel,
            boolean arrows,
            boolean buildingBlocks,
            boolean furnaceOutput
    ) {
        private boolean needsToolSupply() {
            return this.axe || this.pickaxe || this.shovel || this.fishingRod || this.fishingString;
        }

        private boolean hasAnyNeed() {
            return this.needsToolSupply()
                    || this.food
                    || this.wood
                    || this.toolCraftingMaterials
                    || this.fuel
                    || this.arrows
                    || this.buildingBlocks
                    || this.furnaceOutput;
        }
    }
}
