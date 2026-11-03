package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolKind;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolTier;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

public class CraftBasicGearGoal extends Goal {
    private static final String CRAFTING_GEAR_STATE = "ai.player_npc.crafting_gear";
    private static final String CRAFTING_FARM_SUPPLIES_STATE = "ai.player_npc.crafting_farm_supplies";
    public static final String TEMP_TABLE_X = "PlayerNpcTemporaryCraftingTableX";
    public static final String TEMP_TABLE_Y = "PlayerNpcTemporaryCraftingTableY";
    public static final String TEMP_TABLE_Z = "PlayerNpcTemporaryCraftingTableZ";
    private static final int COOLDOWN_TICKS = 20 * 4;
    private static final int CRITICAL_TOOL_COOLDOWN_TICKS = 5;
    private static final int FAILED_CRAFT_RETRY_TICKS = 20 * 15;
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;
    private static final int CRAFTING_TABLE_PLACEMENT_RADIUS = 3;
    private static final double HOME_CRAFTING_DISTANCE_SQR = 48.0D * 48.0D;
    private static final double TEMP_CRAFTING_TABLE_REUSE_DISTANCE_SQR = 32.0D * 32.0D;
    private static final double CRAFTING_TABLE_USE_DISTANCE_SQR = 2.25D * 2.25D;
    private static final int CRAFT_ACTION_DELAY_TICKS = 12;
    private static final int CRAFTING_REPATH_INTERVAL_TICKS = 20;
    private static final int CRAFTING_NO_PROGRESS_TICKS_BEFORE_RECOVERY = 20 * 4;
    private static final double CRAFTING_PROGRESS_DISTANCE_SQR = 0.25D * 0.25D;
    private static final int CRAFTING_ROUTE_FAILURES_BEFORE_RECOVERY = 3;
    private static final int CRAFTING_UPWARD_ESCAPE_TICKS = 20 * 30;
    private static final int MAX_ACTIVATION_STAND_PATH_CHECKS = 1;
    private static final int MAX_PLACEMENT_CANDIDATES_PER_ACTIVATION = 16;
    private static final double DIRECT_CRAFTING_STEP_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double DIRECT_CRAFTING_WALL_PROOF_DISTANCE_SQR = 5.0D * 5.0D;
    private static final double DIRECT_CRAFTING_WALL_SAMPLE_STEP = 0.20D;
    private static final int CRAFTING_ROUTE_CLEAR_TICKS = 24;
    private static final double CRAFTING_ROUTE_CLEAR_DISTANCE_SQR = 4.5D * 4.5D;
    private static final int CRAFTING_VERTICAL_HANDOFF_FAILURE_LIMIT = 2;
    private static final int CRAFTING_TABLE_AVOID_TICKS = 20 * 120;

    public static boolean hasTemporaryCraftingTable(PlayerNpcEntity playerNpc) {
        return getTemporaryCraftingTablePos(playerNpc) != null;
    }

    public static boolean hasValidTemporaryCraftingTable(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        BlockPos pos = getTemporaryCraftingTablePos(playerNpc);
        return pos != null
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    public static boolean isTemporaryCraftingTable(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos) {
        BlockPos tablePos = getTemporaryCraftingTablePos(playerNpc);
        return tablePos != null
                && tablePos.equals(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    /**
     * Read-only arbitration signal for the priority-1 exploration escape goal. Once this goal has
     * paid for a concrete crafting-table plan and started owning MOVE/LOOK, an old exploration
     * climb request must not repeatedly preempt it before the table/tool action can finish.
     */
    public static boolean isCraftingTableWorkActive(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return false;
        }
        String state = playerNpc.getCurrentAiState();
        return CRAFTING_GEAR_STATE.equals(state) || CRAFTING_FARM_SUPPLIES_STATE.equals(state);
    }

    public static boolean shouldKeepTemporaryCraftingTableForGear(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!hasValidTemporaryCraftingTable(playerNpc, serverLevel)) {
            return false;
        }

        CraftBasicGearGoal probe = new CraftBasicGearGoal(playerNpc);
        return probe.needsCriticalStarterTool()
                || probe.canCraftTool()
                || probe.canCraftFarmBoundary(serverLevel, 0);
    }

    public static boolean shouldPrioritizeGearCrafting(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !playerNpc.isAlive()
                || playerNpc.isNoAi()
                || playerNpc.isPassenger()
                || playerNpc.isHealing()
                || playerNpc.getTarget() != null
                || "ai.player_npc.gathering_materials".equals(playerNpc.getCurrentAiState())) {
            return false;
        }

        CraftBasicGearGoal probe = new CraftBasicGearGoal(playerNpc);
        boolean logGatheringEpisodeActive = GatherLogsGoal.isLogGatheringEpisodeActive(playerNpc);
        boolean missingPickaxe = !probe.hasTool(PickaxeItem.class);
        boolean blockedByVerticalEscape = isBlockedByVerticalEscape(playerNpc, logGatheringEpisodeActive);
        boolean emergencyPickaxeCraft = missingPickaxe && blockedByVerticalEscape;
        if (logGatheringEpisodeActive && !emergencyPickaxeCraft) {
            return false;
        }
        if (shouldYieldToResourceSupply(playerNpc, serverLevel) && !emergencyPickaxeCraft) {
            return false;
        }
        if (blockedByVerticalEscape && !missingPickaxe) {
            return false;
        }
        if (playerNpc.getCraftGearCooldown() > 0 && !probe.needsCriticalStarterToolForCooldown()) {
            return false;
        }
        // This predicate is consulted by several lower-priority goals. Keep it inventory/exact-
        // ownership based; the actual higher-priority CraftBasicGearGoal performs the admitted
        // nearby-table and placement/path plan once during canUse().
        if (hasValidTemporaryCraftingTable(playerNpc, serverLevel)
                || probe.hasCarriedCraftingTable()) {
            return probe.canCraftTool();
        }
        // Do not count the same planks once for a tool and again for the table needed to
        // craft it.  The real activation reserves four planks before testing the recipe;
        // lower-priority work must yield only when that same plan can actually start.
        return PlayerNpcCraftingUtil.canCraftCraftingTable(playerNpc.getInventory())
                && probe.canCraftToolAfterPlacedTable();
    }

    public static boolean needsFishingRodCraftingLogs(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!isFishingRodBootstrapActive(playerNpc) || serverLevel == null) {
            return false;
        }

        CraftBasicGearGoal probe = new CraftBasicGearGoal(playerNpc);
        ToolRecipe recipe = probe.nextFishingRodRecipe();
        if (recipe == null) {
            return false;
        }
        // This method participates in log-supply arbitration and must remain a pure inventory /
        // exact-owned-table predicate. Nearby station, placement-volume, stand and path discovery
        // belong to the admitted CraftBasicGearGoal activation.
        int reservedTablePlanks = hasValidTemporaryCraftingTable(playerNpc, serverLevel)
                || probe.hasCarriedCraftingTable()
                ? 0
                : 4;
        return !probe.canProvideRecipeMaterials(
                recipe,
                reservedTablePlanks,
                probe.rawLogReserveForRecipe(recipe)
        );
    }

    public static BlockPos getTemporaryCraftingTablePos(PlayerNpcEntity playerNpc) {
        if (playerNpc == null || !com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).contains(TEMP_TABLE_X)) {
            return null;
        }

        return new BlockPos(
                com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).getInt(TEMP_TABLE_X),
                com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).getInt(TEMP_TABLE_Y),
                com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).getInt(TEMP_TABLE_Z)
        );
    }

    public static void clearTemporaryCraftingTable(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return;
        }
        com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).remove(TEMP_TABLE_X);
        com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).remove(TEMP_TABLE_Y);
        com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).remove(TEMP_TABLE_Z);
    }

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final PathStuckFallbackAi pathStuckFallbackAi;
    private final ToolAi routeToolAi;
    private final BreakingBlockAi routeBreakingBlockAi;
    private final ClearBlockAi routeClearBlockAi;
    private final Set<BlockPos> skippedRouteClearTargets = new HashSet<>();
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private BlockPos craftingTablePos;
    private BlockPos craftingStandPos;
    private int actionDelayTicks;
    private boolean finished;
    private boolean craftedTool;
    private boolean craftedFarmBoundary;
    private boolean emergencyPickaxeCraft;
    private boolean craftingTableInteracted;
    private int failedCraftRetryAfterTick;
    private int nextCraftingPathAttemptTick;
    private int craftingRouteFailures;
    private int craftingNoProgressTicks;
    private double bestCraftingStandDistanceSqr;
    private int activationStandPathChecksRemaining;
    private boolean activationPlanning;
    private BlockPos plannedPlacementStand;
    private int placementCandidateCursor;
    private int craftingEscapeHandoffUntilTick;
    private BlockPos verticalRecoveryTablePos;
    private int verticalRecoveryAttempts;
    private int verticalRecoveryBestFeetY = Integer.MIN_VALUE;
    private BlockPos avoidedCraftingTablePos;
    private int avoidedCraftingTableUntilTick;

    public CraftBasicGearGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.pathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.routeToolAi = new ToolAi(playerNpc);
        this.routeBreakingBlockAi = new BreakingBlockAi(playerNpc, this.routeToolAi);
        this.routeClearBlockAi = new ClearBlockAi(playerNpc, this.routeBreakingBlockAi);
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
                || this.playerNpc.tickCount < this.failedCraftRetryAfterTick
                || this.playerNpc.tickCount < this.craftingEscapeHandoffUntilTick
                    && this.playerNpc.getUpwardEscapeTarget() != null
                || "ai.player_npc.gathering_materials".equals(this.playerNpc.getCurrentAiState())) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        boolean logGatheringEpisodeActive = GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc);
        boolean missingPickaxe = !this.hasTool(PickaxeItem.class);
        boolean blockedByVerticalEscape = isBlockedByVerticalEscape(this.playerNpc, logGatheringEpisodeActive);
        boolean emergencyPickaxeCraft = missingPickaxe && blockedByVerticalEscape;
        if (logGatheringEpisodeActive && !emergencyPickaxeCraft) {
            return false;
        }
        if (shouldYieldToResourceSupply(this.playerNpc, serverLevel) && !emergencyPickaxeCraft) {
            return false;
        }
        if (blockedByVerticalEscape && !missingPickaxe) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            return false;
        }
        boolean needsTerraformShovel = this.needsTerraformShovel(serverLevel);
        boolean needsFarmHoe = FarmAi.needsHoe(this.playerNpc, serverLevel);
        boolean needsFarmBoundary = this.nextFarmBoundaryCraft(serverLevel) != null;
        boolean needsCriticalStarterTool = this.needsCriticalStarterToolForCooldown();
        boolean priorityCrafting = this.playerNpc.isStoneAccessClearing() && this.canCraftTool();
        if (this.playerNpc.getCraftGearCooldown() > 0
                && !priorityCrafting
                && !needsCriticalStarterTool
                && !needsTerraformShovel
                && !needsFarmHoe
                && !needsFarmBoundary) {
            return false;
        }

        this.resetPlan();
        this.activationPlanning = true;
        this.activationStandPathChecksRemaining = MAX_ACTIVATION_STAND_PATH_CHECKS;
        this.emergencyPickaxeCraft = emergencyPickaxeCraft;
        if (!this.canCraftTool() && !this.canCraftFarmBoundary(serverLevel, 0)) {
            this.resetPlan();
            return false;
        }

        CraftingTableUse nearbyTable = this.findNearbyCraftingTableUse(serverLevel);
        if (nearbyTable != null) {
            this.craftingTablePos = nearbyTable.tablePos();
            this.craftingStandPos = nearbyTable.standPos();
            return true;
        }

        CraftingTableUse temporaryTable = this.findReusableTemporaryCraftingTable(serverLevel);
        if (temporaryTable != null) {
            this.craftingTablePos = temporaryTable.tablePos();
            this.craftingStandPos = temporaryTable.standPos();
            return true;
        }
        this.clearUnusableTemporaryCraftingTable(serverLevel);

        if (this.canPlanCraftingTablePlacement(serverLevel)) {
            BlockPos placement = this.findCraftingTablePlacement(serverLevel);
            if (placement != null) {
                BlockPos stand = this.plannedPlacementStand;
                if (stand == null) {
                    return false;
                }
                this.craftingTablePos = placement;
                this.craftingStandPos = stand;
                return true;
            }
            this.playerNpc.setCraftGearCooldown(20 * 2);
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.craftingTablePos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && (this.emergencyPickaxeCraft || this.playerNpc.getUpwardEscapeTarget() == null)
                && (this.emergencyPickaxeCraft || this.playerNpc.getHoleEscapeCooldown() <= 0)
                && this.playerNpc.getTarget() == null;
    }

    private static boolean shouldYieldToResourceSupply(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return GatherLogsGoal.hasLogSupplyDemand(playerNpc, serverLevel)
                || GatherStoneGoal.isStoneSupplyPhaseActive(playerNpc, serverLevel);
    }

    private static boolean isBlockedByVerticalEscape(PlayerNpcEntity playerNpc, boolean logGatheringEpisodeActive) {
        return playerNpc.getUpwardEscapeTarget() != null
                || playerNpc.getHoleEscapeCooldown() > 0
                || "ai.player_npc.digging_down_for_stone".equals(playerNpc.getCurrentAiState())
                || (!logGatheringEpisodeActive
                    && "ai.player_npc.pillaring_up".equals(playerNpc.getCurrentAiState()));
    }

    @Override
    public void start() {
        this.activationPlanning = false;
        this.actionDelayTicks = 0;
        this.finished = false;
        this.craftedTool = false;
        this.craftedFarmBoundary = false;
        this.craftingTableInteracted = false;
        this.nextCraftingPathAttemptTick = this.playerNpc.tickCount;
        this.craftingRouteFailures = 0;
        this.resetCraftingProgressWatchdog();
        this.pathStuckFallbackAi.stop();
        this.routeClearBlockAi.stop();
        this.routeToolAi.restoreMainHand();
        this.skippedRouteClearTargets.clear();
        this.playerNpc.setCurrentAiState(this.nextFarmBoundaryCraft(this.playerNpc.level() instanceof ServerLevel level ? level : null) != null
                ? CRAFTING_FARM_SUPPLIES_STATE
                : CRAFTING_GEAR_STATE);
        this.playerNpc.setCurrentAiDetail("moving to crafting table");
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.craftingTablePos == null) {
            this.finished = true;
            return;
        }

        if (this.tickCraftingRouteClear(serverLevel)) {
            return;
        }

        if (this.pathStuckFallbackAi.tick(serverLevel, "crafting table recovery")) {
            this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail("crafting table recovery"));
            return;
        }

        BlockState tableState = serverLevel.getBlockState(this.craftingTablePos);
        if (!tableState.is(Blocks.CRAFTING_TABLE)) {
            this.tickPlaceCraftingTable(serverLevel);
            return;
        }

        if (this.craftingStandPos == null || !this.canStandAt(serverLevel, this.craftingStandPos)) {
            this.craftingStandPos = this.findCraftingStand(serverLevel, this.craftingTablePos);
            if (this.craftingStandPos == null) {
                this.finished = true;
                return;
            }
        }

        this.playerNpc.getLookControl().setLookAt(
                this.craftingTablePos.getX() + 0.5D,
                this.craftingTablePos.getY() + 0.5D,
                this.craftingTablePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (!this.isAtCraftingStand()) {
            this.playerNpc.setCurrentAiDetail("walking to crafting table");
            if (this.hasCraftingRouteStalled()) {
                this.recoverFailedCraftingRoute(serverLevel, true);
                return;
            }
            if (this.playerNpc.tickCount >= this.nextCraftingPathAttemptTick
                    && !this.moveToCraftingStandOnCadence()) {
                this.recoverFailedCraftingRoute(serverLevel, false);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.clearVerticalRecoveryFailures(this.craftingTablePos);
        this.interactWithCraftingTableOnce();
        if (this.actionDelayTicks++ < CRAFT_ACTION_DELAY_TICKS) {
            return;
        }
        this.actionDelayTicks = 0;
        this.tickCraftAtTable(serverLevel);
    }

    @Override
    public void stop() {
        this.routeClearBlockAi.stop();
        this.routeToolAi.restoreMainHand();
        this.skippedRouteClearTargets.clear();
        if (!this.playerNpc.level().isClientSide) {
            boolean canStillCraftUsefulGear = this.playerNpc.level() instanceof ServerLevel serverLevel
                    && this.canCraftUsefulGear(serverLevel);
            boolean escapeHandoff = this.playerNpc.tickCount < this.craftingEscapeHandoffUntilTick
                    && this.playerNpc.getUpwardEscapeTarget() != null;
            boolean failedCraftAttempt = this.finished
                    && !this.craftedTool
                    && !this.craftedFarmBoundary
                    && canStillCraftUsefulGear
                    && !escapeHandoff;
            int cooldown = failedCraftAttempt
                    ? FAILED_CRAFT_RETRY_TICKS + this.playerNpc.getRandom().nextInt(20 * 10)
                    : this.craftedTool && this.needsCriticalStarterTool() && canStillCraftUsefulGear
                    ? CRITICAL_TOOL_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10)
                    : this.craftedTool
                    ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 15)
                    : this.craftedFarmBoundary
                    ? 5 + this.playerNpc.getRandom().nextInt(10)
                    : canStillCraftUsefulGear
                    ? CRITICAL_TOOL_COOLDOWN_TICKS
                    : 20 + this.playerNpc.getRandom().nextInt(20);
            this.failedCraftRetryAfterTick = failedCraftAttempt
                    ? this.playerNpc.tickCount + cooldown
                    : 0;
            this.playerNpc.setCraftGearCooldown(cooldown);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.nextCraftingPathAttemptTick = 0;
        this.craftingRouteFailures = 0;
        this.craftingNoProgressTicks = 0;
        this.bestCraftingStandDistanceSqr = Double.MAX_VALUE;
        this.pathStuckFallbackAi.stop();
        this.resetPlan();
    }

    private void tickPlaceCraftingTable(ServerLevel serverLevel) {
        if (this.craftingStandPos == null || !this.canStandAt(serverLevel, this.craftingStandPos)) {
            this.craftingStandPos = this.findCraftingStand(serverLevel, this.craftingTablePos);
            if (this.craftingStandPos == null) {
                this.finished = true;
                return;
            }
        }

        this.playerNpc.getLookControl().setLookAt(
                this.craftingTablePos.getX() + 0.5D,
                this.craftingTablePos.getY() + 0.5D,
                this.craftingTablePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (!this.isAtCraftingStand()) {
            this.actionDelayTicks = 0;
            this.playerNpc.setCurrentAiDetail("walking to crafting table placement");
            if (this.hasCraftingRouteStalled()) {
                this.recoverFailedCraftingRoute(serverLevel, true);
                return;
            }
            if (this.playerNpc.tickCount >= this.nextCraftingPathAttemptTick
                    && !this.moveToCraftingStandOnCadence()) {
                this.recoverFailedCraftingRoute(serverLevel, false);
            }
            return;
        }

        if (!this.canPlaceCraftingTableAt(serverLevel, this.craftingTablePos)) {
            this.finished = true;
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < CRAFT_ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail("preparing crafting table");
            return;
        }
        this.actionDelayTicks = 0;

        int rawLogReserve = this.rawLogReserveForCurrentNeed();
        if (!this.hasCarriedCraftingTable() && PlayerNpcCraftingUtil.countPlanks(this.playerNpc.getInventory()) < 4) {
            if (PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) > rawLogReserve
                    && PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting logs into planks");
                return;
            }
            this.finished = true;
            return;
        }

        ItemStack tableStack = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        if (tableStack.isEmpty() && !PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4, rawLogReserve)) {
            this.finished = true;
            return;
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, this.craftingTablePos, Blocks.CRAFTING_TABLE.defaultBlockState())) {
            InventoryUtils.addItem(this.playerNpc, tableStack.isEmpty() ? new ItemStack(Items.CRAFTING_TABLE) : tableStack);
            this.finished = true;
            return;
        }
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).putInt(TEMP_TABLE_X, this.craftingTablePos.getX());
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).putInt(TEMP_TABLE_Y, this.craftingTablePos.getY());
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).putInt(TEMP_TABLE_Z, this.craftingTablePos.getZ());
        this.craftingStandPos = this.findCraftingStand(serverLevel, this.craftingTablePos);
        this.playerNpc.setCurrentAiDetail("placed crafting table");
    }

    private void tickCraftAtTable(ServerLevel serverLevel) {
        ToolRecipe recipe = this.nextToolRecipe();
        if (recipe == null) {
            this.tickCraftFarmBoundaryAtTable(serverLevel);
            return;
        }

        int rawLogReserve = this.rawLogReserveForRecipe(recipe);
        boolean canCraftRecipe = PlayerNpcCraftingUtil.canCraft(
                serverLevel,
                this.playerNpc.getInventory(),
                recipe.result().getItem(),
                true
        );
        if (!canCraftRecipe
                && PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < recipe.sticksNeeded()) {
            if (PlayerNpcCraftingUtil.tryCraftSticks(serverLevel, this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting planks into sticks");
                return;
            }
            this.finished = true;
            return;
        }

        if (!canCraftRecipe
                && PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) > rawLogReserve) {
            if (PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting logs into planks");
                return;
            }
            this.finished = true;
            return;
        }

        if (!canCraftRecipe) {
            this.finished = true;
            return;
        }

        if (this.tryCraftRecipe(serverLevel, recipe)) {
            this.craftedTool = true;
            this.playCraftStep(serverLevel, "crafted " + recipe.result().getHoverName().getString());
        }
        this.finished = true;
    }

    private void playCraftStep(ServerLevel serverLevel, String detail) {
        this.playerNpc.setCurrentAiDetail(detail);
        serverLevel.playSound(null, this.craftingTablePos == null ? this.playerNpc.blockPosition() : this.craftingTablePos, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    private void interactWithCraftingTableOnce() {
        if (this.craftingTableInteracted) {
            return;
        }
        this.playerNpc.triggerMainHandUseAnimation();
        this.craftingTableInteracted = true;
    }

    private boolean canCraftUsefulGear(ServerLevel serverLevel) {
        if (!this.canCraftTool() && !this.canCraftFarmBoundary(serverLevel, 0)) {
            return false;
        }
        return this.hasNearbyCraftingTable(serverLevel)
                || this.hasReusableTemporaryCraftingTable(serverLevel)
                || this.shouldPlaceCraftingTable(serverLevel);
    }

    private boolean canCraftTool() {
        ToolRecipe recipe = this.nextToolRecipe();
        return recipe != null && this.canProvideRecipeMaterials(recipe, 0, this.rawLogReserveForRecipe(recipe));
    }

    private boolean canCraftToolAfterPlacedTable() {
        int reservedPlanks = this.hasCarriedCraftingTable() ? 0 : 4;
        ToolRecipe recipe = this.nextToolRecipe();
        return recipe != null && this.canProvideRecipeMaterials(recipe, reservedPlanks, this.rawLogReserveForRecipe(recipe));
    }

    private boolean shouldPlaceCraftingTable(ServerLevel serverLevel) {
        boolean needsTerraformShovel = this.needsTerraformShovel(serverLevel);
        if (!this.needsBasicGear()
                || this.hasNearbyCraftingTable(serverLevel)
                || (this.isNearSavedHome(serverLevel) && !this.needsCriticalStarterTool() && !needsTerraformShovel)
                || this.hasReusableTemporaryCraftingTable(serverLevel)
                || !this.canCraftToolAfterPlacedTable()) {
            return false;
        }

        return (this.hasCarriedCraftingTable()
                || this.countCarriedCraftingTables() == 0 && PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory()))
                && this.findCraftingTablePlacement(serverLevel) != null;
    }

    private boolean canCraftFarmBoundary(ServerLevel serverLevel, int reservedPlanks) {
        FarmBoundaryCraft craft = this.nextFarmBoundaryCraft(serverLevel);
        return craft != null
                && this.canProvidePlanksAndSticks(craft.planksNeeded(), craft.sticksNeeded(), reservedPlanks, 0);
    }

    private boolean canCraftFarmBoundaryAfterPlacedTable(ServerLevel serverLevel) {
        return this.canCraftFarmBoundary(serverLevel, this.hasCarriedCraftingTable() ? 0 : 4);
    }

    private boolean canPlanCraftingTablePlacement(ServerLevel serverLevel) {
        boolean needsTerraformShovel = this.needsTerraformShovel(serverLevel);
        boolean toolCraft = this.needsBasicGear()
                && (!this.isNearSavedHome(serverLevel) || this.needsCriticalStarterTool() || needsTerraformShovel)
                && this.canCraftToolAfterPlacedTable();
        return (toolCraft || this.canCraftFarmBoundaryAfterPlacedTable(serverLevel))
                && (this.hasCarriedCraftingTable()
                || this.countCarriedCraftingTables() == 0
                && PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory()));
    }

    private boolean needsBasicGear() {
        return this.nextToolRecipe() != null;
    }

    private boolean needsCriticalStarterTool() {
        return !this.hasTool(AxeItem.class)
                || !this.hasTool(PickaxeItem.class)
                || !GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class);
    }

    private boolean needsCriticalStarterToolForCooldown() {
        if (this.shouldLimitMiningProspectingGear()) {
            return !this.hasTool(PickaxeItem.class);
        }
        return this.needsCriticalStarterTool();
    }

    private ToolRecipe nextToolRecipe() {
        if (this.shouldLimitMiningProspectingGear()) {
            return this.nextMiningProspectingGearRecipe();
        }

        ToolRecipe stoneGatheringRecipe = this.nextStoneGatheringRecipe();
        if (stoneGatheringRecipe != null) {
            return stoneGatheringRecipe;
        }
        if (this.isActiveStoneGatheringPhase()) {
            return null;
        }

        ToolRecipe farmHoeRecipe = this.nextFarmHoeRecipe();
        if (farmHoeRecipe != null) {
            return farmHoeRecipe;
        }

        if (this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.isReadyFarmingCycle(serverLevel)) {
            return this.nextReadyFarmSupportRecipe(serverLevel);
        }

        ToolRecipe criticalRecipe = this.nextCriticalStarterRecipe();
        if (criticalRecipe != null) {
            return criticalRecipe;
        }
        if (this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING)
                && FarmAi.getPlan(this.playerNpc, serverLevel)
                .map(plan -> !plan.phase().isReady())
                .orElse(false)) {
            return null;
        }

        ToolRecipe fishingRodRecipe = this.nextFishingRodRecipe();
        if (fishingRodRecipe != null && this.shouldPrioritizeFishingRod()) {
            return fishingRodRecipe;
        }

        ToolRecipe terraformRecipe = this.nextTerraformShovelRecipe();
        if (terraformRecipe != null) {
            return terraformRecipe;
        }

        ToolRecipe materialUpgradeRecipe = this.nextMaterialUpgradeRecipe();
        if (materialUpgradeRecipe != null) {
            return materialUpgradeRecipe;
        }

        if (!this.hasTool(AxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.AXE);
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!this.hasTool(SwordItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SWORD);
        }
        if (!GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
        }
        return fishingRodRecipe;
    }

    private ToolRecipe nextMiningProspectingGearRecipe() {
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (this.isMiningProspectingState()) {
            return null;
        }

        ToolRecipe pickaxeUpgradeRecipe = this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        if (pickaxeUpgradeRecipe != null && this.bestToolTier(ToolKind.PICKAXE).isBelow(pickaxeUpgradeRecipe.tier())) {
            return pickaxeUpgradeRecipe;
        }
        return null;
    }

    private ToolRecipe nextStoneGatheringRecipe() {
        if (!this.isActiveStoneGatheringPhase()) {
            return null;
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
        }
        return null;
    }

    private ToolRecipe nextTerraformShovelRecipe() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.needsTerraformShovel(serverLevel)) {
            return null;
        }

        return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
    }

    private FarmBoundaryCraft nextFarmBoundaryCraft(ServerLevel serverLevel) {
        if (serverLevel == null || !FarmAi.isFarmingJobActive(this.playerNpc)) {
            return null;
        }
        return FarmAi.getPlan(this.playerNpc, serverLevel).map(plan -> {
            if (plan.phase() == com.pla.smart_npc.util.PlayerNpcFarmPlan.Phase.FENCE
                    && FarmAi.missingFenceCount(serverLevel, plan) > 0
                    && !InventoryUtils.hasItem(this.playerNpc, CraftBasicGearGoal::isFenceItem)) {
                return FarmBoundaryCraft.FENCE;
            }
            if (plan.phase() == com.pla.smart_npc.util.PlayerNpcFarmPlan.Phase.GATE
                    && !FarmAi.hasGate(serverLevel, plan)
                    && !InventoryUtils.hasItem(this.playerNpc, CraftBasicGearGoal::isFenceGateItem)) {
                return FarmBoundaryCraft.GATE;
            }
            return null;
        }).orElse(null);
    }

    private void tickCraftFarmBoundaryAtTable(ServerLevel serverLevel) {
        FarmBoundaryCraft craft = this.nextFarmBoundaryCraft(serverLevel);
        if (craft == null) {
            this.finished = true;
            return;
        }

        boolean recipeReady = PlayerNpcCraftingUtil.canCraftMatching(
                serverLevel,
                this.playerNpc.getInventory(),
                craft.resultMatcher(),
                true
        );
        if (!recipeReady
                && PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < craft.sticksNeeded()) {
            if (PlayerNpcCraftingUtil.countPlanks(this.playerNpc.getInventory()) < 2
                    && PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), 0)) {
                this.playCraftStep(serverLevel, "crafted logs into planks for farm boundary");
                return;
            }
            if (PlayerNpcCraftingUtil.tryCraftSticks(serverLevel, this.playerNpc.getInventory(), 0)) {
                this.playCraftStep(serverLevel, "crafted sticks for farm boundary");
                return;
            }
        }
        if (!recipeReady && PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), 0)) {
            this.playCraftStep(serverLevel, "crafted logs into planks for farm boundary");
            return;
        }

        ItemStack crafted = PlayerNpcCraftingUtil.craftItemMatching(
                serverLevel,
                this.playerNpc.getInventory(),
                craft.resultMatcher(),
                true
        ).orElse(ItemStack.EMPTY);
        if (!crafted.isEmpty() && InventoryUtils.addItem(this.playerNpc, crafted)) {
            this.craftedFarmBoundary = true;
            this.playCraftStep(serverLevel, "crafted " + crafted.getHoverName().getString());
        }
        this.finished = true;
    }

    private static boolean isFenceItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof FenceBlock;
    }

    private static boolean isFenceGateItem(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof FenceGateBlock;
    }

    private ToolRecipe nextFarmHoeRecipe() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !FarmAi.needsHoe(this.playerNpc, serverLevel)
                || this.hasTool(HoeItem.class)) {
            return null;
        }
        return this.bestCraftableToolRecipe(ToolKind.HOE);
    }

    private boolean isReadyFarmingCycle(ServerLevel serverLevel) {
        return FarmAi.isFarmingJobActive(this.playerNpc)
                && FarmAi.getPlan(this.playerNpc, serverLevel)
                .map(plan -> plan.phase().isReady())
                .orElse(false);
    }

    private ToolRecipe nextReadyFarmSupportRecipe(ServerLevel serverLevel) {
        if (this.emergencyPickaxeCraft && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (FarmAi.needsFarmLogs(this.playerNpc, serverLevel) && !this.hasTool(AxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.AXE);
        }
        if (FarmAi.needsFarmStone(this.playerNpc, serverLevel) && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        return null;
    }

    private ToolRecipe nextCriticalStarterRecipe() {
        if (this.emergencyPickaxeCraft && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (this.shouldPrioritizeMiningStarterPickaxe() && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!this.hasTool(AxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.AXE);
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
        }
        return null;
    }

    private ToolRecipe nextMaterialUpgradeRecipe() {
        for (ToolKind kind : List.of(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.SWORD, ToolKind.SHOVEL)) {
            ToolRecipe recipe = this.bestCraftableToolRecipe(kind);
            if (recipe != null && this.bestToolTier(kind).isBelow(recipe.tier())) {
                return recipe;
            }
        }
        return null;
    }

    private ToolRecipe bestCraftableToolRecipe(ToolKind kind) {
        ToolTier currentTier = this.bestToolTier(kind);
        boolean hasBetterPrimaryMaterial = false;
        for (ToolTier tier : List.of(ToolTier.DIAMOND, ToolTier.IRON, ToolTier.STONE, ToolTier.WOOD)) {
            ToolRecipe recipe = this.toolRecipe(kind, tier);
            if (!currentTier.isBelow(tier)) {
                continue;
            }

            if (tier != ToolTier.WOOD && this.hasPrimaryToolMaterial(recipe)) {
                hasBetterPrimaryMaterial = true;
            }

            if (this.canProvideRecipeMaterials(recipe, 0, this.rawLogReserveForRecipe(recipe))) {
                return recipe;
            }

            if (hasBetterPrimaryMaterial) {
                return null;
            }
        }
        return null;
    }

    private ToolRecipe toolRecipe(ToolKind kind, ToolTier tier) {
        return new ToolRecipe(PlayerNpcGearUtil.itemFor(kind, tier).getDefaultInstance(), kind, tier, kind.materialCost(), kind.stickCost());
    }

    private boolean tryCraftRecipe(ServerLevel serverLevel, ToolRecipe recipe) {
        boolean crafted = PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), recipe.result().getItem(), true)
                .map(stack -> InventoryUtils.addItem(this.playerNpc, stack))
                .orElse(false);
        if (crafted) {
            this.playerNpc.equipBetterGearFromInventory();
        }
        return crafted;
    }

    private boolean hasTool(Class<?> toolClass) {
        return this.playerNpc.hasCarriedTool(toolClass);
    }

    private boolean shouldPrioritizeFishingRod() {
        return isFishingRodBootstrapActive(this.playerNpc);
    }

    private ToolRecipe nextFishingRodRecipe() {
        if (!this.hasTool(FishingRodItem.class)
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2) {
            return new ToolRecipe(Items.FISHING_ROD.getDefaultInstance(), null, ToolTier.NONE, 0, 3);
        }
        return null;
    }

    private static boolean isFishingRodBootstrapActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING)
                && !playerNpc.shouldPrioritizeLogGathering()
                && !playerNpc.shouldPrioritizeCobblestoneGathering()
                && !PlayerNpcFishingGoal.hasFishingRod(playerNpc)
                && PlayerNpcCraftingUtil.countItem(playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2;
    }

    private int countStone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private int countToolMaterial(ToolTier tier) {
        return switch (tier) {
            case STONE -> this.countStone();
            case IRON -> PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.IRON_INGOT));
            case DIAMOND -> PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.DIAMOND));
            case WOOD, NONE, NETHERITE -> 0;
        };
    }

    private boolean hasPrimaryToolMaterial(ToolRecipe recipe) {
        return recipe.kind() != null
                && recipe.tier() != ToolTier.WOOD
                && recipe.tier() != ToolTier.NONE
                && this.countToolMaterial(recipe.tier()) >= recipe.materialNeeded();
    }

    private boolean canProvideRecipeMaterials(ToolRecipe recipe, int reservedPlanks, int rawLogReserve) {
        if (recipe.kind() == null || recipe.tier() == ToolTier.NONE || recipe.tier() == ToolTier.WOOD) {
            return this.canProvidePlanksAndSticks(recipe.materialNeeded(), recipe.sticksNeeded(), reservedPlanks, rawLogReserve);
        }

        if (recipe.tier() == ToolTier.STONE || recipe.tier() == ToolTier.IRON || recipe.tier() == ToolTier.DIAMOND) {
            return this.countToolMaterial(recipe.tier()) >= recipe.materialNeeded()
                    && this.canProvidePlanksAndSticks(0, recipe.sticksNeeded(), reservedPlanks, rawLogReserve);
        }

        return false;
    }

    private boolean canProvidePlanksAndSticks(int planksNeeded, int sticksNeeded, int reservedPlanks, int rawLogReserve) {
        int availablePlanks = PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), rawLogReserve) - reservedPlanks;
        if (availablePlanks < 0) {
            return false;
        }

        int availableSticks = PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory());
        int missingSticks = Math.max(0, sticksNeeded - availableSticks);
        int planksForSticks = ((missingSticks + 3) / 4) * 2;
        return availablePlanks >= planksNeeded + planksForSticks;
    }

    private int rawLogReserveForCurrentNeed() {
        return this.needsCriticalStarterTool()
                || this.needsTerraformShovel()
                || this.needsFarmHoe()
                || this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.nextFarmBoundaryCraft(serverLevel) != null
                ? 0
                : this.playerNpc.getRawLogReserveTarget();
    }

    private int rawLogReserveForRecipe(ToolRecipe recipe) {
        return this.isCriticalStarterRecipe(recipe)
                || recipe.kind() == ToolKind.HOE && this.needsFarmHoe()
                || (this.isTerraformShovelRecipe(recipe) && this.needsTerraformShovel())
                ? 0
                : this.playerNpc.getRawLogReserveTarget();
    }

    private boolean isCriticalStarterRecipe(ToolRecipe recipe) {
        return recipe.kind() == ToolKind.AXE && !this.hasTool(AxeItem.class)
                || recipe.kind() == ToolKind.PICKAXE && !this.hasTool(PickaxeItem.class)
                || recipe.kind() == ToolKind.SHOVEL && !this.hasTool(ShovelItem.class)
                || recipe.kind() == ToolKind.HOE && this.needsFarmHoe();
    }

    private boolean isTerraformShovelRecipe(ToolRecipe recipe) {
        return recipe.kind() == ToolKind.SHOVEL;
    }

    private boolean needsFarmHoe() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && FarmAi.needsHoe(this.playerNpc, serverLevel);
    }

    private boolean shouldLimitMiningProspectingGear() {
        return GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !this.playerNpc.shouldPrioritizeLogGathering()
                && !this.playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    private boolean isMiningProspectingState() {
        return "ai.player_npc.prospecting_ore".equals(this.playerNpc.getCurrentAiState())
                || "ai.player_npc.exploring_cave".equals(this.playerNpc.getCurrentAiState());
    }

    private boolean needsTerraformShovel() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.needsTerraformShovel(serverLevel);
    }

    private boolean needsTerraformShovel(ServerLevel serverLevel) {
        return TerraformBuildSiteGoal.needsShovelForPrep(this.playerNpc, serverLevel);
    }

    private boolean isActiveStoneGatheringPhase() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel);
    }

    private ToolTier bestToolTier(ToolKind kind) {
        ToolTier best = PlayerNpcGearUtil.bestToolTier(
                this.playerNpc.getMainHandItem(),
                this.playerNpc.getOffhandItem(),
                this.playerNpc.getInventory(),
                kind
        );
        ToolTier mainWeaponTier = PlayerNpcGearUtil.tierFor(this.playerNpc.getMainWeaponItem(), kind);
        if (best.isBelow(mainWeaponTier)) {
            best = mainWeaponTier;
        }
        ToolTier offWeaponTier = PlayerNpcGearUtil.tierFor(this.playerNpc.getOffWeaponItem(), kind);
        if (best.isBelow(offWeaponTier)) {
            best = offWeaponTier;
        }
        return best;
    }

    private boolean hasNearbyCraftingTable(ServerLevel serverLevel) {
        return this.findNearbyCraftingTableUse(serverLevel) != null;
    }

    private CraftingTableUse findNearbyCraftingTableUse(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-CRAFTING_TABLE_SCAN_RADIUS, -2, -CRAFTING_TABLE_SCAN_RADIUS),
                origin.offset(CRAFTING_TABLE_SCAN_RADIUS, 2, CRAFTING_TABLE_SCAN_RADIUS))) {
            if (serverLevel.hasChunkAt(pos)
                    && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                candidates.add(pos.immutable());
            }
        }

        candidates.sort(Comparator.comparingDouble(origin::distSqr));
        for (BlockPos candidate : candidates) {
            if (this.isAvoidedCraftingTable(candidate)) {
                continue;
            }
            BlockPos stand = this.findCraftingStand(serverLevel, candidate);
            if (stand != null) {
                return new CraftingTableUse(candidate, stand);
            }
        }
        return null;
    }

    private BlockPos findCraftingStand(ServerLevel serverLevel, BlockPos tablePos) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(tablePos.relative(direction));
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)
                    || this.distanceToTableSqr(immutable, tablePos) > CRAFTING_TABLE_USE_DISTANCE_SQR) {
                continue;
            }
            if (immutable.equals(center)) {
                return immutable;
            }
            // Activation is geometric only. The running tick performs one bounded route attempt;
            // failed movement then uses the existing retry/cooldown rather than making canUse pay
            // for a navigation region while examining crafting-table stands.
            return immutable;
        }
        return null;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private boolean isAtCraftingStand() {
        if (this.craftingTablePos != null
                && this.distanceToTableSqr(this.playerNpc.blockPosition(), this.craftingTablePos) <= CRAFTING_TABLE_USE_DISTANCE_SQR + 1.0D) {
            return true;
        }

        return this.craftingStandPos != null
                && this.playerNpc.distanceToSqr(this.craftingStandPos.getX() + 0.5D, this.craftingStandPos.getY(), this.craftingStandPos.getZ() + 0.5D) <= 1.25D * 1.25D
                && this.distanceToTableSqr(this.playerNpc.blockPosition(), this.craftingTablePos) <= CRAFTING_TABLE_USE_DISTANCE_SQR + 1.0D;
    }

    private boolean moveToCraftingStand() {
        if (this.craftingStandPos == null) {
            return false;
        }
        // Crafting-table travel is a committed route, not a speculative candidate probe.
        // Let vanilla GroundPathNavigation use its normal node budget so ordinary steps and
        // one-block jumps are represented before any destructive recovery is considered.
        Path path = this.playerNpc.getNavigation().createPath(this.craftingStandPos, 0);
        if (!this.isUsableCraftingPath(path)) {
            int verticalDelta = this.craftingStandPos.getY() - this.playerNpc.blockPosition().getY();
            if (this.playerNpc.distanceToSqr(
                    this.craftingStandPos.getX() + 0.5D,
                    this.playerNpc.getY(),
                    this.craftingStandPos.getZ() + 0.5D) <= DIRECT_CRAFTING_STEP_DISTANCE_SQR
                    && verticalDelta >= -1
                    && verticalDelta <= 1) {
                this.playerNpc.getNavigation().stop();
                this.playerNpc.getMoveControl().setWantedPosition(
                        this.craftingStandPos.getX() + 0.5D,
                        this.craftingStandPos.getY(),
                        this.craftingStandPos.getZ() + 0.5D,
                        1.0D);
                this.playerNpc.setCurrentAiDetail("stepping to crafting table");
                return true;
            }
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private boolean isUsableCraftingPath(Path path) {
        if (path == null || !path.canReach() || path.getEndNode() == null || this.craftingStandPos == null) {
            return false;
        }
        return path.getEndNode().asBlockPos().equals(this.craftingStandPos);
    }

    private boolean moveToCraftingStandOnCadence() {
        if (!this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck()) {
            return true;
        }
        if (this.playerNpc.tickCount < this.nextCraftingPathAttemptTick) {
            return true;
        }
        this.nextCraftingPathAttemptTick = this.playerNpc.tickCount + CRAFTING_REPATH_INTERVAL_TICKS;
        return this.moveToCraftingStand();
    }

    private void resetCraftingProgressWatchdog() {
        this.craftingNoProgressTicks = 0;
        this.bestCraftingStandDistanceSqr = this.distanceToCraftingStandSqr();
    }

    private boolean hasCraftingRouteStalled() {
        double distanceSqr = this.distanceToCraftingStandSqr();
        if (distanceSqr + CRAFTING_PROGRESS_DISTANCE_SQR < this.bestCraftingStandDistanceSqr) {
            this.bestCraftingStandDistanceSqr = distanceSqr;
            this.craftingNoProgressTicks = 0;
            return false;
        }
        return ++this.craftingNoProgressTicks >= CRAFTING_NO_PROGRESS_TICKS_BEFORE_RECOVERY;
    }

    private double distanceToCraftingStandSqr() {
        if (this.craftingStandPos == null) {
            return Double.MAX_VALUE;
        }
        return this.playerNpc.distanceToSqr(
                this.craftingStandPos.getX() + 0.5D,
                this.craftingStandPos.getY(),
                this.craftingStandPos.getZ() + 0.5D
        );
    }

    private void recoverFailedCraftingRoute(ServerLevel serverLevel, boolean navigationStalled) {
        this.resetCraftingProgressWatchdog();
        if (this.craftingStandPos == null || ++this.craftingRouteFailures < CRAFTING_ROUTE_FAILURES_BEFORE_RECOVERY) {
            return;
        }
        this.craftingRouteFailures = 0;

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.startCraftingRouteClear(serverLevel, !navigationStalled)) {
            return;
        }

        if (this.craftingStandPos.getY() > feet.getY()) {
            boolean wetForLandEscape = this.playerNpc.isInWaterOrBubble()
                    || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                    || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER);
            if (!wetForLandEscape) {
                if (this.recordFailedVerticalRecovery(feet)) {
                    this.avoidFailedCraftingTable();
                    this.playerNpc.setCurrentAiDetail("abandoning inaccessible crafting table after vertical cycle");
                    this.finished = true;
                    return;
                }
                // Hand off to the vertical-recovery goal at the NPC's local column. The old
                // implementation requested the remote crafting stand itself. A one-block-high
                // destination did not satisfy EscapeHoleWithBlockGoal's forced-climb contract,
                // so the request was discarded and this MOVE goal immediately repeated it.
                BlockPos localEscapeTarget = feet.above(2);
                this.playerNpc.getNavigation().stop();
                this.playerNpc.requestCraftingUpwardEscapeTo(
                        localEscapeTarget,
                        CRAFTING_UPWARD_ESCAPE_TICKS,
                        2
                );
                this.craftingEscapeHandoffUntilTick = this.playerNpc.tickCount + CRAFTING_UPWARD_ESCAPE_TICKS;
                this.playerNpc.setCurrentAiDetail("crafting route blocked below rim; handing off safe climb");
                this.finished = true;
                return;
            }
        }

        BlockPos directionTarget = this.craftingTablePos == null ? this.craftingStandPos : this.craftingTablePos;
        if (this.pathStuckFallbackAi.start(
                serverLevel,
                directionTarget,
                "crafting table recovery",
                pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                        || FarmAi.isProtectedFarmBlock(this.playerNpc, pos))) {
            this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail("crafting table recovery"));
            this.craftingStandPos = null;
            return;
        }

        // Do not hold MOVE forever when neither a route, climb, nor safe reposition exists.
        // The normal failed-craft cooldown allows other full worker routines to run before retry.
        this.finished = true;
    }

    private boolean tickCraftingRouteClear(ServerLevel serverLevel) {
        if (!this.routeClearBlockAi.isRunning()) {
            return false;
        }
        BlockPos target = this.routeClearBlockAi.targetPos();
        if (!this.isSafeCraftingRouteClearTarget(serverLevel, target)) {
            this.rejectCraftingRouteClearTarget(target);
            return false;
        }

        ClearBlockAi.TickResult result = this.routeClearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            BlockPos resolved = this.routeClearBlockAi.targetPos();
            if (!this.isSafeCraftingRouteClearTarget(serverLevel, resolved)) {
                this.rejectCraftingRouteClearTarget(resolved);
                return false;
            }
            this.playerNpc.setCurrentAiDetail(this.routeClearBlockAi.detail());
            return true;
        }

        this.routeToolAi.restoreMainHand();
        if (result == ClearBlockAi.TickResult.FAILED && target != null) {
            this.skippedRouteClearTargets.add(target.immutable());
        }
        if (result == ClearBlockAi.TickResult.DONE) {
            this.skippedRouteClearTargets.clear();
        }
        this.nextCraftingPathAttemptTick = this.playerNpc.tickCount;
        this.resetCraftingProgressWatchdog();
        this.playerNpc.setCurrentAiDetail(result == ClearBlockAi.TickResult.DONE
                ? "cleared crafting table route; retrying"
                : "crafting route clear failed; retrying");
        return true;
    }

    private boolean startCraftingRouteClear(ServerLevel serverLevel, boolean retryOrdinaryPath) {
        if (this.craftingStandPos == null || this.craftingTablePos == null) {
            return false;
        }
        BlockPos blocker = this.findConfirmedCraftingPathBlocker(serverLevel);
        // A failed path admission can be transient (for example an entity collision). Re-prove
        // it once with normal navigation. Also re-prove a watchdog stall when no accepted path
        // exists: the short direct MoveControl fallback can be stalled by a wall without ever
        // installing a Path, which is distinct from an accepted path stalling on a new obstacle.
        if (retryOrdinaryPath || blocker == null) {
            Path ordinaryPath = this.playerNpc.getNavigation().createPath(this.craftingStandPos, 0);
            if (this.isUsableCraftingPath(ordinaryPath)
                    && this.playerNpc.getNavigation().moveTo(ordinaryPath, 1.0D)) {
                this.nextCraftingPathAttemptTick = this.playerNpc.tickCount + CRAFTING_REPATH_INTERVAL_TICKS;
                this.resetCraftingProgressWatchdog();
                return true;
            }
        }

        // An accepted path supplies its own corridor proof. If path admission repeatedly fails,
        // prove a nearby wall separately by sweeping the NPC's actual body toward the valid stand.
        // This keeps ordinary terrain navigation first while allowing a real wall to be opened.
        if (blocker == null) {
            blocker = this.findConfirmedDirectCraftingWallBlocker(serverLevel);
        }
        BlockPos resolvedBlocker = blocker;
        if (resolvedBlocker != null && this.routeClearBlockAi.start(
                serverLevel,
                resolvedBlocker,
                state -> this.isSafeCraftingRouteObstruction(serverLevel, resolvedBlocker, state),
                "clearing crafting table route",
                CRAFTING_ROUTE_CLEAR_TICKS,
                CRAFTING_ROUTE_CLEAR_DISTANCE_SQR,
                false
        )) {
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setCurrentAiDetail(this.routeClearBlockAi.detail());
            return true;
        }
        return false;
    }

    private BlockPos findConfirmedCraftingPathBlocker(ServerLevel serverLevel) {
        Path path = this.playerNpc.getNavigation().getPath();
        if (path == null
                || path.isDone()
                || path.getNodeCount() <= 0
                || path.getEndNode() == null
                || this.craftingStandPos == null
                || !path.getEndNode().asBlockPos().equals(this.craftingStandPos)) {
            return null;
        }

        int firstNode = Math.max(0, path.getNextNodeIndex());
        int endNode = Math.min(path.getNodeCount(), firstNode + 3);
        Set<BlockPos> routeSupports = new HashSet<>();
        routeSupports.add(this.playerNpc.blockPosition().below().immutable());
        for (int index = firstNode; index < endNode; index++) {
            routeSupports.add(path.getNode(index).asBlockPos().below().immutable());
        }

        AABB segmentStart = this.playerNpc.getBoundingBox();
        for (int index = firstNode; index < endNode; index++) {
            BlockPos node = path.getNode(index).asBlockPos();
            AABB segmentEnd = this.playerNpc.getBoundingBox().move(
                    node.getX() + 0.5D - this.playerNpc.getX(),
                    node.getY() - this.playerNpc.getY(),
                    node.getZ() + 0.5D - this.playerNpc.getZ()
            );
            BlockPos blocker = this.findSweptCraftingPathCollision(
                    serverLevel,
                    segmentStart,
                    segmentEnd,
                    routeSupports
            );
            if (blocker != null) {
                return blocker;
            }
            segmentStart = segmentEnd;
        }
        return null;
    }

    /**
     * Proves the first solid voxel intersected by the NPC body on the short direct approach to a
     * valid crafting stand. This is only used after repeated full-budget path admission failures.
     * Sampling the translated body, rather than scanning a broad line box, prevents nearby floor
     * and diagonal terrain from being mistaken for the wall that actually blocks the approach.
     */
    private BlockPos findConfirmedDirectCraftingWallBlocker(ServerLevel serverLevel) {
        if (this.craftingStandPos == null
                || this.craftingTablePos == null
                || !this.playerNpc.onGround()
                || !serverLevel.getBlockState(this.craftingTablePos).is(Blocks.CRAFTING_TABLE)
                || !this.canStandAt(serverLevel, this.craftingStandPos)
                || this.distanceToCraftingStandSqr() > DIRECT_CRAFTING_WALL_PROOF_DISTANCE_SQR) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        int verticalDelta = this.craftingStandPos.getY() - feet.getY();
        if (verticalDelta < -1 || verticalDelta > 1) {
            return null;
        }

        double dx = this.craftingStandPos.getX() + 0.5D - this.playerNpc.getX();
        double dy = this.craftingStandPos.getY() - this.playerNpc.getY();
        double dz = this.craftingStandPos.getZ() + 0.5D - this.playerNpc.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int samples = Math.max(1, Mth.ceil(distance / DIRECT_CRAFTING_WALL_SAMPLE_STEP));
        AABB body = this.playerNpc.getBoundingBox();
        for (int sample = 1; sample <= samples; sample++) {
            double progress = (double) sample / samples;
            AABB movedBody = body.move(dx * progress, dy * progress, dz * progress);
            BlockPos collision = this.findFirstDirectBodyCollision(serverLevel, movedBody);
            if (collision == null) {
                continue;
            }
            // The first body collision is authoritative. If it is owned, structural, unsafe, or
            // an ordinary one-block rise, do not tunnel through it to a later clearable voxel.
            return this.isSafeCraftingRouteClearTarget(serverLevel, collision) ? collision : null;
        }
        return null;
    }

    private BlockPos findFirstDirectBodyCollision(ServerLevel serverLevel, AABB body) {
        BlockPos first = null;
        double firstDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.betweenClosed(
                Mth.floor(body.minX),
                Mth.floor(body.minY + 0.02D),
                Mth.floor(body.minZ),
                Mth.floor(body.maxX),
                Mth.floor(body.maxY),
                Mth.floor(body.maxZ))) {
            BlockPos pos = mutable.immutable();
            BlockState state = serverLevel.getBlockState(pos);
            boolean intersectsBody = state.getCollisionShape(serverLevel, pos).toAabbs().stream()
                    .map(box -> box.move(pos))
                    .anyMatch(box -> box.intersects(body));
            if (!intersectsBody) {
                continue;
            }
            double distance = this.playerNpc.distanceToSqr(
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D
            );
            if (distance < firstDistance
                    || distance == firstDistance && (first == null || pos.getY() < first.getY())) {
                firstDistance = distance;
                first = pos;
            }
        }
        return first;
    }

    private BlockPos findSweptCraftingPathCollision(
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
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.betweenClosed(
                Mth.floor(sweptBody.minX),
                Mth.floor(sweptBody.minY),
                Mth.floor(sweptBody.minZ),
                Mth.floor(sweptBody.maxX),
                Mth.floor(sweptBody.maxY),
                Mth.floor(sweptBody.maxZ))) {
            BlockPos pos = mutable.immutable();
            if (routeSupports.contains(pos) || !this.isSafeCraftingRouteClearTarget(serverLevel, pos)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            boolean intersectsBody = state.getCollisionShape(serverLevel, pos).toAabbs().stream()
                    .map(box -> box.move(pos))
                    .anyMatch(box -> box.intersects(sweptBody));
            if (!intersectsBody) {
                continue;
            }
            double distance = this.playerNpc.distanceToSqr(
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D
            );
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = pos;
            }
        }
        return nearest;
    }

    private boolean isSafeCraftingRouteClearTarget(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || !serverLevel.hasChunkAt(pos)
                || pos.equals(this.craftingTablePos)
                || pos.equals(this.craftingTablePos.below())
                || this.craftingStandPos != null && pos.equals(this.craftingStandPos.below())
                || pos.equals(this.playerNpc.blockPosition().below())
                || this.playerNpc.isTemporaryPillarSupport(pos)
                || this.skippedRouteClearTargets.contains(pos)
                || PlayerNpcHomeUtil.getHome(this.playerNpc)
                .map(home -> PlayerNpcHomeUtil.isInside(home, pos))
                .orElse(false)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isProtectedFarmBlock(this.playerNpc, pos)
                || serverLevel.getBlockEntity(pos) != null
                || this.isTraversableOneBlockRise(serverLevel, pos)) {
            return false;
        }
        return this.isSafeCraftingRouteObstruction(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    /**
     * A solid voxel with open standing room on top and an adjacent lower stand is a normal
     * one-block step. GroundPathNavigation can jump onto it, so it is terrain support rather
     * than proof of a blocked body corridor.
     */
    private boolean isTraversableOneBlockRise(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null || !serverLevel.hasChunkAt(pos) || !this.canStandAt(serverLevel, pos.above())) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.canStandAt(serverLevel, pos.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSafeCraftingRouteObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state != null
                && !state.getCollisionShape(serverLevel, pos).isEmpty()
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state);
    }

    private void rejectCraftingRouteClearTarget(BlockPos target) {
        if (target != null) {
            this.skippedRouteClearTargets.add(target.immutable());
        }
        this.routeClearBlockAi.stop();
        this.routeToolAi.restoreMainHand();
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

    private boolean hasCarriedCraftingTable() {
        return InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE);
    }

    private int countCarriedCraftingTables() {
        int count = PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.CRAFTING_TABLE));
        if (this.playerNpc.getMainHandItem().is(Items.CRAFTING_TABLE)) {
            count += this.playerNpc.getMainHandItem().getCount();
        }
        if (this.playerNpc.getOffhandItem().is(Items.CRAFTING_TABLE)) {
            count += this.playerNpc.getOffhandItem().getCount();
        }
        return count;
    }

    private boolean isNearSavedHome(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        return this.playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) <= HOME_CRAFTING_DISTANCE_SQR;
    }

    private boolean hasReusableTemporaryCraftingTable(ServerLevel serverLevel) {
        return this.findReusableTemporaryCraftingTable(serverLevel) != null;
    }

    private CraftingTableUse findReusableTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null
                || this.isAvoidedCraftingTable(pos)
                || this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > TEMP_CRAFTING_TABLE_REUSE_DISTANCE_SQR
                || !serverLevel.hasChunkAt(pos)
                || !serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            return null;
        }

        BlockPos stand = this.findCraftingStand(serverLevel, pos);
        return stand == null ? null : new CraftingTableUse(pos, stand);
    }

    private void clearUnusableTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null) {
            return;
        }
        if (this.isAvoidedCraftingTable(pos)) {
            this.clearTemporaryCraftingTable();
            return;
        }
        // An obstructed stand can make a nearby table temporarily unusable. Keep
        // ownership in that case so mining/path-clearing goals do not destroy it,
        // but release tables that are gone or genuinely outside the reuse area.
        if (this.playerNpc.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D
        ) > TEMP_CRAFTING_TABLE_REUSE_DISTANCE_SQR
                || serverLevel.hasChunkAt(pos)
                && !serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            this.clearTemporaryCraftingTable();
        }
    }

    private BlockPos getTemporaryCraftingTablePos() {
        return getTemporaryCraftingTablePos(this.playerNpc);
    }

    private void clearTemporaryCraftingTable() {
        clearTemporaryCraftingTable(this.playerNpc);
    }

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection()));
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection().getClockWise()));
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection().getCounterClockWise()));
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection().getOpposite()));

        for (int dy = -1; dy <= 1; dy++) {
            for (int radius = 1; radius <= CRAFTING_TABLE_PLACEMENT_RADIUS; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if ((dx == 0 && dz == 0) || Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        this.addCraftingTablePlacementCandidate(candidates, origin.offset(dx, dy, dz));
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(origin::distSqr));
        this.plannedPlacementStand = null;
        int start = candidates.isEmpty() ? 0 : Math.floorMod(this.placementCandidateCursor, candidates.size());
        int checked = 0;
        for (int offset = 0; offset < candidates.size()
                && checked < MAX_PLACEMENT_CANDIDATES_PER_ACTIVATION; offset++, checked++) {
            BlockPos candidate = candidates.get((start + offset) % candidates.size());
            BlockPos immutable = candidate.immutable();
            if (!this.canPlaceCraftingTableAt(serverLevel, immutable)) {
                continue;
            }
            BlockPos stand = this.findCraftingStand(serverLevel, immutable);
            if (stand != null) {
                this.plannedPlacementStand = stand.immutable();
                this.placementCandidateCursor = 0;
                return immutable;
            }
        }
        if (!candidates.isEmpty()) {
            this.placementCandidateCursor = (start + checked) % candidates.size();
        }
        return null;
    }

    private boolean shouldPrioritizeMiningStarterPickaxe() {
        return this.playerNpc.level() instanceof ServerLevel
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && !this.playerNpc.shouldPrioritizeLogGathering();
    }

    private void addCraftingTablePlacementCandidate(List<BlockPos> candidates, BlockPos candidate) {
        BlockPos immutable = candidate.immutable();
        if (!candidates.contains(immutable)) {
            candidates.add(immutable);
        }
    }

    private boolean canPlaceCraftingTableAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !FarmAi.isProtectedFarmBlock(this.playerNpc, pos)
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, Blocks.CRAFTING_TABLE.defaultBlockState());
    }

    /**
     * Counts only ineffective handoffs for the same table. A higher retry position proves that a
     * legitimate multi-step climb is progressing and starts a fresh count. Reappearing at the
     * same or a lower Y proves the pillar-up/descent-down cycle seen in the trace.
     */
    private boolean recordFailedVerticalRecovery(BlockPos feet) {
        if (this.craftingTablePos == null) {
            return false;
        }
        if (this.verticalRecoveryTablePos == null
                || !this.verticalRecoveryTablePos.equals(this.craftingTablePos)) {
            this.verticalRecoveryTablePos = this.craftingTablePos.immutable();
            this.verticalRecoveryAttempts = 0;
            this.verticalRecoveryBestFeetY = Integer.MIN_VALUE;
        }
        if (feet.getY() > this.verticalRecoveryBestFeetY) {
            this.verticalRecoveryBestFeetY = feet.getY();
            this.verticalRecoveryAttempts = 0;
        }
        return ++this.verticalRecoveryAttempts >= CRAFTING_VERTICAL_HANDOFF_FAILURE_LIMIT;
    }

    private void clearVerticalRecoveryFailures(BlockPos tablePos) {
        if (tablePos != null
                && this.verticalRecoveryTablePos != null
                && this.verticalRecoveryTablePos.equals(tablePos)) {
            this.verticalRecoveryTablePos = null;
            this.verticalRecoveryAttempts = 0;
            this.verticalRecoveryBestFeetY = Integer.MIN_VALUE;
        }
    }

    private void avoidFailedCraftingTable() {
        if (this.craftingTablePos == null) {
            return;
        }
        this.avoidedCraftingTablePos = this.craftingTablePos.immutable();
        this.avoidedCraftingTableUntilTick = this.playerNpc.tickCount + CRAFTING_TABLE_AVOID_TICKS;
        BlockPos temporary = this.getTemporaryCraftingTablePos();
        if (temporary != null && temporary.equals(this.craftingTablePos)) {
            // Releasing ownership lets the next activation plan a local replacement instead of
            // treating the unreachable station as this NPC's reusable table forever.
            this.clearTemporaryCraftingTable();
        }
        this.clearVerticalRecoveryFailures(this.craftingTablePos);
    }

    private boolean isAvoidedCraftingTable(BlockPos pos) {
        if (this.avoidedCraftingTablePos == null || pos == null) {
            return false;
        }
        if (this.playerNpc.tickCount >= this.avoidedCraftingTableUntilTick) {
            this.avoidedCraftingTablePos = null;
            this.avoidedCraftingTableUntilTick = 0;
            return false;
        }
        return this.avoidedCraftingTablePos.equals(pos);
    }

    private void resetPlan() {
        this.craftingTablePos = null;
        this.craftingStandPos = null;
        this.actionDelayTicks = 0;
        this.finished = false;
        this.craftedTool = false;
        this.craftedFarmBoundary = false;
        this.emergencyPickaxeCraft = false;
        this.craftingTableInteracted = false;
        this.activationStandPathChecksRemaining = 0;
        this.activationPlanning = false;
        this.plannedPlacementStand = null;
    }

    private record ToolRecipe(ItemStack result, ToolKind kind, ToolTier tier, int materialNeeded, int sticksNeeded) {}

    private enum FarmBoundaryCraft {
        FENCE(4, 2),
        GATE(2, 4);

        private final int planksNeeded;
        private final int sticksNeeded;

        FarmBoundaryCraft(int planksNeeded, int sticksNeeded) {
            this.planksNeeded = planksNeeded;
            this.sticksNeeded = sticksNeeded;
        }

        private int planksNeeded() {
            return this.planksNeeded;
        }

        private int sticksNeeded() {
            return this.sticksNeeded;
        }

        private java.util.function.Predicate<ItemStack> resultMatcher() {
            return this == FENCE ? CraftBasicGearGoal::isFenceItem : CraftBasicGearGoal::isFenceGateItem;
        }
    }

    private record CraftingTableUse(BlockPos tablePos, BlockPos standPos) {}
}
