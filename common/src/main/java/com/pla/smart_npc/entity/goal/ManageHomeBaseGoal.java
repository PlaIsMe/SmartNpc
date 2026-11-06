package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.util.SmartNpcItemUtil;

import net.minecraft.core.component.DataComponents;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ChestAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcBuildStatusUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcBaseUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ManageHomeBaseGoal extends Goal {
    private static final String LAST_HOME_CHEST_DEPOSIT_NIGHT = "PlayerNpcLastHomeChestDepositNight";
    private static final int COOLDOWN_TICKS = 20 * 8;
    private static final double HOME_ACTION_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double RECOVER_TABLE_BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double RECOVER_TABLE_MOVE_SPEED = 1.0D;
    private static final int MAX_RECOVER_TABLE_TICKS = 20 * 8;
    private static final int MOVEMENT_REPATH_TICKS = 20;
    private static final int DEPOSIT_INTERVAL_TICKS = 6;
    private static final int BUILD_SITE_CRAFTING_TABLE_MARGIN = 5;
    private static final int BUILD_SITE_CRAFTING_TABLE_SCAN_BELOW = 2;
    private static final int BUILD_SITE_CRAFTING_TABLE_SCAN_ABOVE = 3;
    private static final int NON_BUILDER_CHEST_CANDIDATES_PER_PASS = 24;
    private static final int NON_BUILDER_CHEST_PATHS_PER_PASS = 4;
    private static final int NON_BUILDER_CHEST_NEGATIVE_BACKOFF_TICKS = 20 * 15;
    private static final List<ChestPlacementOffset> NON_BUILDER_CHEST_OFFSETS = createNonBuilderChestOffsets();

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final PlacingBlockAi placingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final boolean nightlyDepositOnly;
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos baseAnchor;
    private boolean nonBuilderBase;
    private BlockPos recoveryTablePos;
    private BlockPos depositChestPos;
    private BlockPos depositChestStandPos;
    private BlockPos pendingCraftingTablePos;
    private BlockPos pendingCraftingTableStandPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int depositDelayTicks;
    private int movementRepathTicks;
    private boolean usingTemporaryTool;
    private boolean returnTemporaryMainHandOnRestore;
    private boolean depositChestOpen;
    private boolean depositFinished;
    private boolean depositMovedAny;
    private boolean nonBuilderChestPlacementPlanned;
    private int nonBuilderChestSearchCursor;
    private String planDetail = "";

    public ManageHomeBaseGoal(PlayerNpcEntity playerNpc) {
        this(playerNpc, false);
    }

    public ManageHomeBaseGoal(PlayerNpcEntity playerNpc, boolean nightlyDepositOnly) {
        this.playerNpc = playerNpc;
        this.nightlyDepositOnly = nightlyDepositOnly;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * Lets the higher-priority night-camp goal yield while a non-builder has one real
     * storage action to perform at its persistent camp base. The same predicate used by
     * the deposit goal keeps camp from yielding merely because the inventory is full when
     * the chest cannot accept any of its disposable stacks.
     */
    public static boolean hasPendingCampBaseNightlyDeposit(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !serverLevel.isDarkOutside()
                || playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || hasAttemptedDepositThisNight(playerNpc, serverLevel)
                || !inventoryMoreThanHalfFull(playerNpc)) {
            return false;
        }

        BlockPos baseAnchor = PlayerNpcBaseUtil.getNonBuilderBase(playerNpc, serverLevel).orElse(null);
        BlockPos chestPos = ChestAi.findOwnedSupplyChest(playerNpc, serverLevel);
        if (baseAnchor == null
                || chestPos == null
                || baseAnchor.distSqr(chestPos) > HOME_ACTION_DISTANCE_SQR
                || playerNpc.distanceToSqr(
                baseAnchor.getX() + 0.5D,
                baseAnchor.getY(),
                baseAnchor.getZ() + 0.5D
        ) > HOME_ACTION_DISTANCE_SQR
                || !(serverLevel.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
            return false;
        }
        return hasDepositCandidate(playerNpc, serverLevel, chest);
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || !this.nightlyDepositOnly && this.playerNpc.getManageHomeCooldown() > 0) {
            return false;
        }
        if (GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc)) {
            return false;
        }
        if (this.playerNpc.isStoneAccessClearing()) {
            if (!this.playerNpc.getIdleTraceDetail().startsWith("stone ")) {
                this.playerNpc.setIdleTraceDetail(
                        "manage home blocked: stone access clearing "
                                + this.playerNpc.getStoneAccessClearCooldown() + "t",
                        20 * 4
                );
            }
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.nonBuilderBase = !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING);
        Optional<PlayerNpcHomeUtil.HomeArea> savedHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        this.homeArea = this.nonBuilderBase ? null : savedHome.orElse(null);
        this.baseAnchor = this.homeArea == null
                ? PlayerNpcBaseUtil.getNonBuilderBase(this.playerNpc, serverLevel).orElse(null)
                : PlayerNpcHomeUtil.center(this.homeArea);
        if (this.nightlyDepositOnly) {
            if (this.baseAnchor == null
                    || !this.nonBuilderBase && this.homeArea == null
                    || !this.isNearHome()) {
                return false;
            }
            boolean deposit = this.shouldDepositToChest(serverLevel);
            if (deposit) {
                this.planDetail = this.nonBuilderBase
                        ? "nightly deposit to base chest"
                        : "nightly deposit to home chest";
            }
            return deposit;
        }
        if (this.nonBuilderBase && this.baseAnchor == null) {
            return false;
        }
        if (this.canRecoverTemporaryCraftingTable(serverLevel)) {
            this.planDetail = "recovering temporary crafting table";
            return true;
        }
        if (!this.nonBuilderBase && this.homeArea == null) {
            return false;
        }
        if (!this.nonBuilderBase
                && this.playerNpc.shouldPrioritizeLogGathering()
                && !this.inventoryMoreThanHalfFull()) {
            return false;
        }
        if (!this.isNearHome()) {
            return false;
        }

        if (!this.nonBuilderBase && this.shouldYieldToBuildMaterialGathering(serverLevel)) {
            return false;
        }

        if (this.nonBuilderBase) {
            boolean needsChest = this.needsChest(serverLevel);
            if (needsChest) {
                if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                    return false;
                }
                this.nonBuilderChestPlacementPlanned = true;
                this.planDetail = "placing base supply chest";
                return true;
            }
            this.nonBuilderChestPlacementPlanned = false;
            boolean deposit = this.shouldDepositToChest(serverLevel);
            if (deposit) {
                this.planDetail = "depositing inventory to base chest";
            }
            return deposit;
        }

        boolean needsCraftingTable = this.needsCraftingTable(serverLevel);
        boolean needsBed = this.needsBed(serverLevel);
        boolean needsChest = this.needsChest(serverLevel);
        if (needsCraftingTable || needsBed || needsChest) {
            this.planDetail = "home supply placement craftingTable=" + needsCraftingTable
                    + ",bed=" + needsBed
                    + ",chest=" + needsChest;
            return true;
        }
        if (TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.hasContinuableHomeBuildWork(this.playerNpc, serverLevel)) {
            return false;
        }

        boolean deposit = this.shouldDepositToChest(serverLevel);
        if (deposit) {
            this.planDetail = "depositing inventory to home chest";
        }
        return deposit;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.getTarget() != null
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (this.recoveryTablePos != null) {
            return serverLevel.getBlockState(this.recoveryTablePos).is(Blocks.CRAFTING_TABLE);
        }
        return this.pendingCraftingTablePos != null
                || serverLevel.isDarkOutside()
                && this.depositChestPos != null
                && !this.depositFinished
                && serverLevel.getBlockState(this.depositChestPos).is(Blocks.CHEST);
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.managing_home");
        if (!this.planDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(this.planDetail);
        }
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.recoveryTablePos = null;
        this.depositChestPos = null;
        this.depositChestStandPos = null;
        this.pendingCraftingTablePos = null;
        this.pendingCraftingTableStandPos = null;
        this.depositDelayTicks = 0;
        this.movementRepathTicks = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
        this.depositChestOpen = false;
        this.depositFinished = false;
        this.depositMovedAny = false;

        if (this.nightlyDepositOnly) {
            if (this.beginDepositToChest(serverLevel)) {
                return;
            }
            this.finishHomeAction(false);
            return;
        }

        if (this.canRecoverTemporaryCraftingTable(serverLevel)) {
            this.recoveryTablePos = this.getTemporaryCraftingTablePos();
            this.updateRecoveryDetail(serverLevel);
            return;
        }

        if (this.nonBuilderBase) {
            boolean acted = this.placeChest(serverLevel);
            if (acted) {
                this.finishHomeAction(true);
                return;
            }
            if (this.nonBuilderChestPlacementPlanned) {
                this.finishHomeAction(false);
                this.playerNpc.setManageHomeCooldown(
                        NON_BUILDER_CHEST_NEGATIVE_BACKOFF_TICKS
                                + this.playerNpc.getRandom().nextInt(20 * 10)
                );
                return;
            }
            if (this.beginDepositToChest(serverLevel)) {
                return;
            }
            this.finishHomeAction(false);
            return;
        }

        if (this.homeArea == null) {
            this.finishHomeAction(false);
            return;
        }

        if (this.beginPlaceCraftingTable(serverLevel)) {
            return;
        }

        boolean acted = this.placeBed(serverLevel)
                || this.placeChest(serverLevel);
        if (acted) {
            this.finishHomeAction(true);
            return;
        }

        if (this.beginDepositToChest(serverLevel)) {
            return;
        }

        this.finishHomeAction(false);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.recoveryTablePos == null) {
            if (this.pendingCraftingTablePos != null) {
                this.tickPlaceCraftingTable(serverLevel);
                return;
            }
            if (this.depositChestPos != null) {
                this.tickDepositToChest(serverLevel);
            }
            return;
        }

        BlockState state = serverLevel.getBlockState(this.recoveryTablePos);
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.clearTemporaryCraftingTable();
            this.finishHomeAction(true);
            return;
        }

        if (this.playerNpc.distanceToSqr(
                this.recoveryTablePos.getX() + 0.5D,
                this.recoveryTablePos.getY() + 0.5D,
                this.recoveryTablePos.getZ() + 0.5D
        ) > RECOVER_TABLE_BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            if (this.movementRepathTicks-- <= 0) {
                this.playerNpc.getNavigation().moveTo(
                        this.recoveryTablePos.getX() + 0.5D,
                        this.recoveryTablePos.getY(),
                        this.recoveryTablePos.getZ() + 0.5D,
                        RECOVER_TABLE_MOVE_SPEED
                );
                this.movementRepathTicks = MOVEMENT_REPATH_TICKS;
            }
            this.updateRecoveryDetail(serverLevel);
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.toolAi.equipBestToolFor(state);
        boolean dropRecoveredBlock = PlayerNpcBlockBreakUtil.shouldDropResources(state, this.playerNpc.getMainHandItem());
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.recoveryTablePos,
                candidate -> candidate.is(Blocks.CRAFTING_TABLE),
                MAX_RECOVER_TABLE_TICKS,
                "recovering crafting table"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        BlockPos recoveredPos = this.recoveryTablePos;
        if (result == BreakingBlockAi.TickResult.DONE) {
            if (!dropRecoveredBlock) {
                this.returnStack(new ItemStack(Items.CRAFTING_TABLE));
            }
            this.clearTemporaryCraftingTable();
            this.finishHomeAction(true);
            return;
        }

        this.playerNpc.clearBlockBreakProgress(recoveredPos);
        this.clearTemporaryCraftingTable();
        this.finishHomeAction(false);
    }

    @Override
    public void stop() {
        if (this.depositChestOpen
                && this.depositChestPos != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            ChestAi.closeChest(serverLevel, this.depositChestPos);
        }
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.homeArea = null;
        this.baseAnchor = null;
        this.nonBuilderBase = false;
        this.recoveryTablePos = null;
        this.depositChestPos = null;
        this.depositChestStandPos = null;
        this.pendingCraftingTablePos = null;
        this.pendingCraftingTableStandPos = null;
        this.depositDelayTicks = 0;
        this.movementRepathTicks = 0;
        this.depositChestOpen = false;
        this.depositFinished = false;
        this.depositMovedAny = false;
        this.nonBuilderChestPlacementPlanned = false;
        this.planDetail = "";
        this.playerNpc.setCurrentAiDetail("");
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void finishHomeAction(boolean acted) {
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        int cooldown = acted
                ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 8)
                : 20 + this.playerNpc.getRandom().nextInt(20);
        this.playerNpc.setManageHomeCooldown(cooldown);
        this.recoveryTablePos = null;
        this.pendingCraftingTablePos = null;
        this.pendingCraftingTableStandPos = null;
        this.depositFinished = true;
    }

    private boolean canRecoverTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null) {
            return false;
        }

        if (this.isInsideBuildSiteCraftingTableSearchArea(pos)) {
            return false;
        }
        if (CraftBasicGearGoal.shouldKeepTemporaryCraftingTableForGear(this.playerNpc, serverLevel)) {
            return false;
        }

        return (this.homeArea == null || !PlayerNpcHomeUtil.isInside(this.homeArea, pos))
                && this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 6.0D * 6.0D
                && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    private BlockPos getTemporaryCraftingTablePos() {
        return CraftBasicGearGoal.getTemporaryCraftingTablePos(this.playerNpc);
    }

    private void clearTemporaryCraftingTable() {
        CraftBasicGearGoal.clearTemporaryCraftingTable(this.playerNpc);
    }

    private boolean needsCraftingTable(ServerLevel serverLevel) {
        return this.findCraftingTableForHomeUse(serverLevel) == null
                && (InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE)
                || PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()));
    }

    private boolean needsBed(ServerLevel serverLevel) {
        return this.findBed(serverLevel) == null
                && (this.hasBedItem() || PlayerNpcCraftingUtil.canCraftBed(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()));
    }

    private boolean needsChest(ServerLevel serverLevel) {
        // Builder blueprints own their chest placement.  A separate utility chest here
        // consumes materials and modifies the footprint before terraforming/construction.
        // Non-builders still bootstrap their normal shared base chest.
        if (!this.nonBuilderBase) {
            return false;
        }
        int logReserve = 0;
        return this.findHomeChest(serverLevel) == null
                && (InventoryUtils.hasItem(this.playerNpc, Items.CHEST)
                || PlayerNpcCraftingUtil.canCraftChest(this.playerNpc.getInventory(), logReserve));
    }

    private boolean beginPlaceCraftingTable(ServerLevel serverLevel) {
        if (!this.needsCraftingTable(serverLevel)) {
            return false;
        }

        BlockPos pos = this.findCraftingTablePlacement(serverLevel);
        if (pos == null) {
            return false;
        }
        BlockPos standPos = this.findCraftingTableStand(serverLevel, pos);
        if (standPos == null) {
            return false;
        }

        this.pendingCraftingTablePos = pos;
        this.pendingCraftingTableStandPos = standPos;
        this.playerNpc.setCurrentAiDetail("walking to crafting table spot");
        return true;
    }

    private void tickPlaceCraftingTable(ServerLevel serverLevel) {
        if (this.pendingCraftingTablePos == null || this.pendingCraftingTableStandPos == null) {
            this.finishHomeAction(false);
            return;
        }

        if (!this.isAtCraftingTableStand()) {
            this.playerNpc.setCurrentAiDetail("walking to crafting table spot");
            if (!this.moveToCraftingTableStand()) {
                this.finishHomeAction(false);
            }
            return;
        }

        if (!this.canPlaceBuildSiteCraftingTableAt(serverLevel, this.pendingCraftingTablePos)
                && !this.canPlaceUtilityAt(serverLevel, this.pendingCraftingTablePos)) {
            this.finishHomeAction(false);
            return;
        }

        ItemStack table = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        if (table.isEmpty() && !PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4, this.playerNpc.getRawLogReserveTarget())) {
            this.finishHomeAction(false);
            return;
        }

        this.showPlacementItem(table.isEmpty() ? new ItemStack(Items.CRAFTING_TABLE) : table);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.pendingCraftingTablePos, Blocks.CRAFTING_TABLE.defaultBlockState())) {
            this.returnStack(table.isEmpty() ? new ItemStack(Items.CRAFTING_TABLE) : table);
            this.finishHomeAction(false);
            return;
        }
        this.finishPlacementMainHand();
        this.finishHomeAction(true);
    }

    private boolean isNearHome() {
        if (this.baseAnchor == null) {
            return false;
        }
        return this.playerNpc.distanceToSqr(
                this.baseAnchor.getX() + 0.5D,
                this.baseAnchor.getY(),
                this.baseAnchor.getZ() + 0.5D
        ) <= HOME_ACTION_DISTANCE_SQR;
    }

    private boolean placeChest(ServerLevel serverLevel) {
        if (!this.needsChest(serverLevel)) {
            return false;
        }

        BlockPos pos = this.nonBuilderBase
                ? this.findNonBuilderChestPlacement(serverLevel)
                : this.findUtilityPlacement(serverLevel, this.homeArea.width() - 2, 1);
        if (pos == null) {
            return false;
        }

        ItemStack chest = this.playerNpc.consumeInventoryItem(Items.CHEST, 1).orElse(ItemStack.EMPTY);
        int logReserve = this.nonBuilderBase ? 0 : this.playerNpc.getRawLogReserveTarget();
        if (chest.isEmpty() && !PlayerNpcCraftingUtil.tryCraftChest(serverLevel, this.playerNpc.getInventory(), logReserve)) {
            return false;
        }
        if (chest.isEmpty()) {
            chest = this.playerNpc.consumeInventoryItem(Items.CHEST, 1).orElse(ItemStack.EMPTY);
        }
        if (chest.isEmpty()) {
            return false;
        }

        this.showPlacementItem(chest);
        if (!this.placingBlockAi.placeBlock(serverLevel, pos, Blocks.CHEST.defaultBlockState())) {
            this.returnStack(chest);
            return false;
        }
        this.finishPlacementMainHand();
        this.playerNpc.setOwnedChestPos(pos);
        return true;
    }

    private boolean placeBed(ServerLevel serverLevel) {
        if (!this.needsBed(serverLevel)) {
            return false;
        }

        if (!this.hasBedItem() && !PlayerNpcCraftingUtil.tryCraftBed(serverLevel, this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return false;
        }

        ItemStack bedStack = this.playerNpc.consumeInventoryItem(stack -> stack.getItem().builtInRegistryHolder().is(ItemTags.BEDS), 1)
                .orElse(ItemStack.EMPTY);
        if (bedStack.isEmpty() || !(bedStack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof BedBlock bedBlock)) {
            return false;
        }

        BlockPos foot = this.findUtilityPlacement(serverLevel, 1, this.homeArea.depth() - 2);
        if (foot == null) {
            this.returnStack(bedStack);
            return false;
        }

        Direction facing = this.playerNpc.getDirection();
        BlockPos head = foot.relative(facing);
        if (!this.canPlaceUtilityAt(serverLevel, head)) {
            this.returnStack(bedStack);
            return false;
        }

        BlockState footState = bedBlock.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT);
        BlockState headState = bedBlock.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.HEAD);
        this.showPlacementItem(bedStack);
        if (!this.placingBlockAi.placeBlock(serverLevel, foot, footState, false)) {
            this.returnStack(bedStack);
            return false;
        }
        if (!this.placingBlockAi.placeBlock(serverLevel, head, headState, false)) {
            serverLevel.setBlockAndUpdate(foot, Blocks.AIR.defaultBlockState());
            this.returnStack(bedStack);
            return false;
        }
        this.placingBlockAi.playPlaceEffects(serverLevel, foot, footState);
        this.finishPlacementMainHand();
        return true;
    }

    private boolean shouldDepositToChest(ServerLevel serverLevel) {
        if (!serverLevel.isDarkOutside()
                || hasAttemptedDepositThisNight(this.playerNpc, serverLevel)
                || !this.inventoryMoreThanHalfFull()) {
            return false;
        }
        BlockPos chestPos = this.findHomeChest(serverLevel);
        return chestPos != null
                && serverLevel.getBlockEntity(chestPos) instanceof ChestBlockEntity chest
                && this.hasDepositCandidate(chest);
    }

    private boolean shouldYieldToBuildMaterialGathering(ServerLevel serverLevel) {
        return !serverLevel.isDarkOutside()
                && !serverLevel.isThundering()
                && (PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, this.playerNpc)
                || PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, this.playerNpc)
                || GatherMissingBuildMaterialGoal.needsMissingBuildMaterial(this.playerNpc, serverLevel));
    }

    private boolean beginDepositToChest(ServerLevel serverLevel) {
        if (!this.shouldDepositToChest(serverLevel)) {
            return false;
        }
        // One activation is the nightly storage pass. Mark it before path setup so an
        // unreachable stand cannot make night camp and home management retry each other
        // for the rest of the night.
        markDepositAttemptedThisNight(this.playerNpc, serverLevel);
        BlockPos chestPos = this.findHomeChest(serverLevel);
        if (chestPos == null || !(serverLevel.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
            return false;
        }
        BlockPos standPos = ChestAi.findAdjacentStand(this.playerNpc, serverLevel, chestPos);
        if (standPos == null) {
            return false;
        }
        if (!this.hasDepositCandidate(chest)) {
            return false;
        }

        this.depositChestPos = chestPos.immutable();
        this.depositChestStandPos = standPos.immutable();
        this.depositDelayTicks = 0;
        this.depositChestOpen = false;
        this.depositFinished = false;
        this.depositMovedAny = false;
        this.updateDepositDetail();
        return true;
    }

    private void tickDepositToChest(ServerLevel serverLevel) {
        if (!serverLevel.isDarkOutside()) {
            if (this.depositChestOpen && this.depositChestPos != null) {
                ChestAi.closeChest(serverLevel, this.depositChestPos);
                this.depositChestOpen = false;
            }
            this.finishHomeAction(this.depositMovedAny);
            return;
        }
        if (this.depositChestPos == null
                || !(serverLevel.getBlockEntity(this.depositChestPos) instanceof ChestBlockEntity chest)) {
            this.finishHomeAction(this.depositMovedAny);
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.depositChestPos.getX() + 0.5D,
                this.depositChestPos.getY() + 0.5D,
                this.depositChestPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (this.depositChestStandPos == null || !ChestAi.canStandAt(serverLevel, this.depositChestStandPos)) {
            this.depositChestStandPos = ChestAi.findAdjacentStand(this.playerNpc, serverLevel, this.depositChestPos);
            if (this.depositChestStandPos == null) {
                this.finishHomeAction(this.depositMovedAny);
                return;
            }
        }
        if (!ChestAi.isAtStand(this.playerNpc, this.depositChestStandPos)) {
            this.playerNpc.setCurrentAiDetail("walking to chest @ "
                    + this.depositChestPos.getX() + " "
                    + this.depositChestPos.getY() + " "
                    + this.depositChestPos.getZ());
            if (this.movementRepathTicks-- <= 0) {
                if (!ChestAi.moveToStand(this.playerNpc, this.depositChestStandPos, 1.0D)) {
                    this.finishHomeAction(this.depositMovedAny);
                }
                this.movementRepathTicks = MOVEMENT_REPATH_TICKS;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (!this.depositChestOpen) {
            ChestAi.openChest(serverLevel, this.depositChestPos, this.playerNpc);
            this.depositChestOpen = true;
            this.depositDelayTicks = DEPOSIT_INTERVAL_TICKS;
            this.updateDepositDetail();
            return;
        }

        if (this.depositDelayTicks > 0) {
            this.depositDelayTicks--;
            return;
        }

        if (!this.inventoryMoreThanHalfFull()) {
            ChestAi.closeChest(serverLevel, this.depositChestPos);
            this.depositChestOpen = false;
            this.finishHomeAction(this.depositMovedAny);
            return;
        }

        int moved = this.depositNextStack(chest);
        if (moved > 0) {
            this.depositMovedAny = true;
            this.depositDelayTicks = DEPOSIT_INTERVAL_TICKS;
            this.playerNpc.swing(InteractionHand.MAIN_HAND, this.playerNpc.getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation(), true);
            serverLevel.playSound(null, this.depositChestPos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
            this.updateDepositDetail();
            return;
        }

        ChestAi.closeChest(serverLevel, this.depositChestPos);
        this.depositChestOpen = false;
        this.finishHomeAction(this.depositMovedAny);
    }

    private int depositNextStack(Container chest) {
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && this.inventoryMoreThanHalfFull(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || this.shouldKeepStack(stack)) {
                continue;
            }

            ItemStack toMove = stack.copy();
            toMove.setCount(Math.max(1, stack.getCount() / 2));
            ItemStack remaining = this.addToContainer(chest, toMove);
            int moved = toMove.getCount() - remaining.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
                inventory.setChanged();
                return moved;
            }
        }
        return 0;
    }

    private boolean hasDepositCandidate(Container chest) {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && hasDepositCandidate(this.playerNpc, serverLevel, chest);
    }

    private static boolean hasDepositCandidate(PlayerNpcEntity playerNpc, ServerLevel serverLevel, Container chest) {
        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || shouldKeepStack(playerNpc, serverLevel, stack)) {
                continue;
            }
            ItemStack toMove = stack.copy();
            toMove.setCount(Math.max(1, stack.getCount() / 2));
            if (toMove.getCount() > addToContainerPreview(chest, toMove).getCount()) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack addToContainerPreview(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()
                    || !ItemStack.isSameItemSameComponents(existing, remaining)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }

            int transferable = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            remaining.shrink(transferable);
        }

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (container.getItem(i).isEmpty()) {
                remaining.shrink(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
            }
        }
        return remaining;
    }

    private ItemStack addToContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()
                    || !ItemStack.isSameItemSameComponents(existing, remaining)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }

            int transferable = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            existing.grow(transferable);
            remaining.shrink(transferable);
        }

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty()) {
                continue;
            }

            ItemStack inserted = remaining.copy();
            inserted.setCount(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
            container.setItem(i, inserted);
            remaining.shrink(inserted.getCount());
        }
        container.setChanged();
        return remaining;
    }

    private boolean shouldKeepStack(ItemStack stack) {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && shouldKeepStack(this.playerNpc, serverLevel, stack);
    }

    private static boolean shouldKeepStack(PlayerNpcEntity playerNpc, ServerLevel serverLevel, ItemStack stack) {
        if (PlayerNpcBuildStatusUtil.shouldKeepForCurrentBuild(serverLevel, playerNpc, stack)) {
            return true;
        }

        return stack.is(ItemTags.SWORDS)
                || stack.has(DataComponents.TOOL)
                || SmartNpcItemUtil.isArmor(stack)
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof ShieldItem
                || (playerNpc.hasInterest(PlayerNpcInterest.FISHING)
                && (stack.getItem() instanceof FishingRodItem || stack.is(Items.STRING)))
                || stack.has(net.minecraft.core.component.DataComponents.FOOD)
                || stack.is(Items.ARROW)
                || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.WATER_BUCKET)
                || stack.is(Items.LAVA_BUCKET)
                || stack.is(Items.BUCKET)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.getItem().builtInRegistryHolder().is(ItemTags.BEDS);
    }

    private BlockPos findCraftingTableForHomeUse(ServerLevel serverLevel) {
        if (this.homeArea == null) {
            return null;
        }

        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin().offset(-BUILD_SITE_CRAFTING_TABLE_MARGIN, -BUILD_SITE_CRAFTING_TABLE_SCAN_BELOW, -BUILD_SITE_CRAFTING_TABLE_MARGIN),
                this.homeArea.origin().offset(this.homeArea.width() + BUILD_SITE_CRAFTING_TABLE_MARGIN - 1, BUILD_SITE_CRAFTING_TABLE_SCAN_ABOVE, this.homeArea.depth() + BUILD_SITE_CRAFTING_TABLE_MARGIN - 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        return this.findBuildSiteCraftingTablePlacement(serverLevel);
    }

    private BlockPos findBuildSiteCraftingTablePlacement(ServerLevel serverLevel) {
        if (this.homeArea == null) {
            return null;
        }

        for (int margin = 1; margin <= BUILD_SITE_CRAFTING_TABLE_MARGIN; margin++) {
            List<BlockPos> candidates = this.buildSiteOuterRing(margin);
            this.shuffleCandidates(candidates);
            for (BlockPos candidate : candidates) {
                if (this.canPlaceBuildSiteCraftingTableAt(serverLevel, candidate)
                        && this.findCraftingTableStand(serverLevel, candidate) != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void shuffleCandidates(List<BlockPos> candidates) {
        for (int i = candidates.size() - 1; i > 0; i--) {
            Collections.swap(candidates, i, this.playerNpc.getRandom().nextInt(i + 1));
        }
    }

    private List<BlockPos> buildSiteOuterRing(int margin) {
        List<BlockPos> candidates = new ArrayList<>();
        int minX = -margin;
        int maxX = this.homeArea.width() + margin - 1;
        int minZ = -margin;
        int maxZ = this.homeArea.depth() + margin - 1;
        for (int x = minX; x <= maxX; x++) {
            candidates.add(this.homeArea.origin().offset(x, 0, minZ));
            candidates.add(this.homeArea.origin().offset(x, 0, maxZ));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            candidates.add(this.homeArea.origin().offset(minX, 0, z));
            candidates.add(this.homeArea.origin().offset(maxX, 0, z));
        }
        return candidates;
    }

    private boolean canPlaceBuildSiteCraftingTableAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !this.isInsideBuildFootprint(pos)
                && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private boolean isInsideBuildSiteCraftingTableSearchArea(BlockPos pos) {
        if (this.homeArea == null) {
            return false;
        }

        return pos.getX() >= this.homeArea.origin().getX() - BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getX() < this.homeArea.origin().getX() + this.homeArea.width() + BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getZ() >= this.homeArea.origin().getZ() - BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getZ() < this.homeArea.origin().getZ() + this.homeArea.depth() + BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getY() >= this.homeArea.origin().getY() - BUILD_SITE_CRAFTING_TABLE_SCAN_BELOW
                && pos.getY() <= this.homeArea.origin().getY() + BUILD_SITE_CRAFTING_TABLE_SCAN_ABOVE;
    }

    private boolean isInsideBuildFootprint(BlockPos pos) {
        if (this.homeArea == null) {
            return false;
        }

        return pos.getX() >= this.homeArea.origin().getX()
                && pos.getX() < this.homeArea.origin().getX() + this.homeArea.width()
                && pos.getZ() >= this.homeArea.origin().getZ()
                && pos.getZ() < this.homeArea.origin().getZ() + this.homeArea.depth();
    }

    private BlockPos findCraftingTableStand(ServerLevel serverLevel, BlockPos tablePos) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(tablePos.relative(direction));
        }

        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)
                    || this.distanceToTableSqr(immutable, tablePos) > 2.25D * 2.25D) {
                continue;
            }
            if (immutable.equals(this.playerNpc.blockPosition())) {
                return immutable;
            }
            Path path = this.playerNpc.getNavigation().createPath(immutable, 0);
            if (path != null && path.canReach()) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private boolean isAtCraftingTableStand() {
        return this.pendingCraftingTableStandPos != null
                && this.pendingCraftingTablePos != null
                && this.playerNpc.distanceToSqr(this.pendingCraftingTableStandPos.getX() + 0.5D, this.pendingCraftingTableStandPos.getY(), this.pendingCraftingTableStandPos.getZ() + 0.5D) <= 1.25D * 1.25D
                && this.distanceToTableSqr(this.playerNpc.blockPosition(), this.pendingCraftingTablePos) <= 2.25D * 2.25D + 1.0D;
    }

    private boolean moveToCraftingTableStand() {
        if (this.pendingCraftingTableStandPos == null) {
            return false;
        }
        if (this.movementRepathTicks-- > 0) {
            return true;
        }
        this.movementRepathTicks = MOVEMENT_REPATH_TICKS;
        Path path = this.playerNpc.getNavigation().createPath(this.pendingCraftingTableStandPos, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private double distanceToTableSqr(BlockPos standPos, BlockPos tablePos) {
        if (tablePos == null) {
            return Double.MAX_VALUE;
        }
        double dx = standPos.getX() + 0.5D - (tablePos.getX() + 0.5D);
        double dy = standPos.getY() + 0.5D - (tablePos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (tablePos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private BlockPos findUtilityPlacement(ServerLevel serverLevel, int preferredX, int preferredZ) {
        BlockPos preferred = PlayerNpcHomeUtil.interiorPos(this.homeArea, preferredX, preferredZ);
        if (this.canPlaceUtilityAt(serverLevel, preferred)) {
            return preferred;
        }

        for (int x = 1; x < this.homeArea.width() - 1; x++) {
            for (int z = 1; z < this.homeArea.depth() - 1; z++) {
                BlockPos pos = PlayerNpcHomeUtil.interiorPos(this.homeArea, x, z);
                if (this.canPlaceUtilityAt(serverLevel, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private BlockPos findNonBuilderChestPlacement(ServerLevel serverLevel) {
        if (this.baseAnchor == null || NON_BUILDER_CHEST_OFFSETS.isEmpty()) {
            return null;
        }
        ChestAi.NavigationPathBudget pathBudget = new ChestAi.NavigationPathBudget(NON_BUILDER_CHEST_PATHS_PER_PASS);
        int checked = 0;
        while (checked++ < NON_BUILDER_CHEST_CANDIDATES_PER_PASS) {
            ChestPlacementOffset offset = NON_BUILDER_CHEST_OFFSETS.get(this.nonBuilderChestSearchCursor);
            this.nonBuilderChestSearchCursor = (this.nonBuilderChestSearchCursor + 1) % NON_BUILDER_CHEST_OFFSETS.size();
            BlockPos candidate = this.baseAnchor.offset(offset.dx(), offset.dy(), offset.dz()).immutable();
            if (this.canPlaceNonBuilderChestAt(serverLevel, candidate)
                    && ChestAi.findAdjacentStand(this.playerNpc, serverLevel, candidate, pathBudget) != null) {
                this.nonBuilderChestSearchCursor = 0;
                return candidate;
            }
            if (pathBudget.exhausted()) {
                break;
            }
        }
        return null;
    }

    private boolean canPlaceNonBuilderChestAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                && serverLevel.getBlockState(pos.below()).isSolidRender()
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !FarmAi.isProtectedFarmlandBlock(this.playerNpc, pos)
                && !FarmAi.isInsideOwnedFarmWorkOrEntranceFootprint(this.playerNpc, pos);
    }

    private static List<ChestPlacementOffset> createNonBuilderChestOffsets() {
        List<ChestPlacementOffset> offsets = new ArrayList<>();
        for (int radius = 1; radius <= 5; radius++) {
            for (int dy : new int[]{0, 1, -1, 2, -2}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    offsets.add(new ChestPlacementOffset(dx, dy, -radius));
                    offsets.add(new ChestPlacementOffset(dx, dy, radius));
                }
                for (int dz = -radius + 1; dz < radius; dz++) {
                    offsets.add(new ChestPlacementOffset(-radius, dy, dz));
                    offsets.add(new ChestPlacementOffset(radius, dy, dz));
                }
            }
        }
        offsets.sort(Comparator.comparingInt(ChestPlacementOffset::distanceSqr));
        return List.copyOf(offsets);
    }

    private record ChestPlacementOffset(int dx, int dy, int dz) {
        private int distanceSqr() {
            return this.dx * this.dx + this.dz * this.dz + this.dy * this.dy * 4;
        }
    }

    private boolean canPlaceUtilityAt(ServerLevel serverLevel, BlockPos pos) {
        return PlayerNpcHomeUtil.isInside(this.homeArea, pos)
                && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private BlockPos findBlock(ServerLevel serverLevel, Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin(),
                this.homeArea.origin().offset(this.homeArea.width() - 1, 3, this.homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findHomeChest(ServerLevel serverLevel) {
        if (!this.nonBuilderBase) {
            return ChestAi.findHomeSupplyChest(this.playerNpc, serverLevel, this.homeArea);
        }
        BlockPos ownedChest = ChestAi.findOwnedSupplyChest(this.playerNpc, serverLevel);
        if (ownedChest == null || this.baseAnchor == null) {
            return null;
        }
        if (this.baseAnchor.distSqr(ownedChest) <= HOME_ACTION_DISTANCE_SQR) {
            return ownedChest;
        }
        this.playerNpc.setOwnedChestPos(null);
        return null;
    }

    private BlockPos findBed(ServerLevel serverLevel) {
        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin(),
                this.homeArea.origin().offset(this.homeArea.width() - 1, 3, this.homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).getBlock() instanceof BedBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean hasBedItem() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem().builtInRegistryHolder().is(ItemTags.BEDS));
    }

    private int usedInventorySlots() {
        return usedInventorySlots(this.playerNpc);
    }

    private static int usedInventorySlots(PlayerNpcEntity playerNpc) {
        int used = 0;
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            if (!playerNpc.getInventory().getItem(i).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    private boolean inventoryMoreThanHalfFull() {
        return inventoryMoreThanHalfFull(this.playerNpc);
    }

    private static boolean inventoryMoreThanHalfFull(PlayerNpcEntity playerNpc) {
        return usedInventorySlots(playerNpc) > playerNpc.getInventory().getContainerSize() / 2;
    }

    private static boolean hasAttemptedDepositThisNight(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).contains(LAST_HOME_CHEST_DEPOSIT_NIGHT)
                && com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).getLongOr(LAST_HOME_CHEST_DEPOSIT_NIGHT, 0L) == currentNight(serverLevel);
    }

    private static void markDepositAttemptedThisNight(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).putLong(LAST_HOME_CHEST_DEPOSIT_NIGHT, currentNight(serverLevel));
    }

    private static long currentNight(ServerLevel serverLevel) {
        return serverLevel.getOverworldClockTime() / 24000L;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void setTemporaryMainHand(ItemStack stack) {
        this.setTemporaryMainHand(stack, true);
    }

    private void showPlacementItem(ItemStack stack) {
        this.setTemporaryMainHand(stack, false);
    }

    private void setTemporaryMainHand(ItemStack stack, boolean returnCurrentOnRestore) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryTool) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryTool = true;
            this.returnTemporaryMainHandOnRestore = returnCurrentOnRestore;
        } else if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        ItemStack held = stack.copy();
        held.setCount(Math.min(1, held.getCount()));
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, held);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private void finishPlacementMainHand() {
        if (!this.usingTemporaryTool) {
            return;
        }

        this.placingBlockAi.finishHeldPlacement(this.previousMainHand);
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private void updateRecoveryDetail(ServerLevel serverLevel) {
        if (this.recoveryTablePos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        String breakingDetail = this.breakingBlockAi.detail();
        if (!breakingDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(breakingDetail);
            return;
        }

        boolean inBreakRange = this.playerNpc.distanceToSqr(
                this.recoveryTablePos.getX() + 0.5D,
                this.recoveryTablePos.getY() + 0.5D,
                this.recoveryTablePos.getZ() + 0.5D
        ) <= RECOVER_TABLE_BREAK_DISTANCE_SQR;
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "minecraft:crafting_table @ %d %d %d %s",
                this.recoveryTablePos.getX(),
                this.recoveryTablePos.getY(),
                this.recoveryTablePos.getZ(),
                inBreakRange ? "recovering" : "walking"
        ));
    }

    private void updateDepositDetail() {
        if (this.depositChestPos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "depositing to chest @ %d %d %d",
                this.depositChestPos.getX(),
                this.depositChestPos.getY(),
                this.depositChestPos.getZ()
        ));
    }

    private void lookAndSound(ServerLevel serverLevel, BlockPos pos, net.minecraft.sounds.SoundEvent soundEvent) {
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        serverLevel.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 0.8F, 1.0F);
    }
}
