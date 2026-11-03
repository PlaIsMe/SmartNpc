package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.compat.EpicFightHooks;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.PlayerNpcBedUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCollisionUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import dev.architectury.platform.Platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class TerraformBuildSiteGoal extends Goal {
    private static final double WORK_DISTANCE_SQR = 5.5D * 5.5D;
    private static final int MINE_HIT_INTERVAL_TICKS = 8;
    private static final int MAX_MINE_TICKS = 20 * 20;
    private static final int PLACE_DELAY_TICKS = 8;
    private static final int SCAFFOLD_JUMP_WINDUP_TICKS = 2;
    private static final int SCAFFOLD_PLACE_DELAY_TICKS = 1;
    private static final int SCAFFOLD_MAX_PLACE_WAIT_TICKS = 32;
    private static final int SCAFFOLD_FORCE_PLACE_TICKS = 3;
    private static final int MAX_SUPPORT_FILL_FAILURES = 12;
    private static final int SUPPORT_FILL_RETRY_COOLDOWN_TICKS = 20 * 5;
    private static final int SUPPORT_CLEARANCE_RETRY_TICKS = 8;
    private static final int SUPPORT_CLEARANCE_JUMP_COOLDOWN_TICKS = 12;
    private static final int SUPPORT_FILL_ESCAPE_REQUEST_TICKS = 20 * 10;
    private static final int SUPPORT_FILL_ESCAPE_EXTRA_BLOCKS = 4;
    private static final int SUPPORT_FILL_ESCAPE_HANDOFF_TICKS = 20 * 30;
    private static final int TARGET_SEARCH_RETRY_COOLDOWN_TICKS = 10;
    private static final int ACTIONABLE_PREP_CACHE_TICKS = 20;
    private static final int NO_PREP_CACHE_TICKS = 20 * 60 * 5;
    private static final int MAX_TERRAFORM_CLEAR_BLOCKS_PER_SLICE = 4;
    private static final int MAX_TERRAFORM_SUPPORT_COLUMNS_PER_SLICE = 1;
    private static final int MAX_WORKER_CLEAR_BLOCKS_PER_SLICE = 64;
    private static final int MAX_WORKER_SUPPORT_COLUMNS_PER_SLICE = 16;
    private static final long WORKER_TARGET_SEARCH_BUDGET_NANOS = 1_000_000L;
    private static final int MAX_TERRAFORM_LOCAL_PATH_CHECKS = 1;
    private static final int DIRECT_CLEAR_FAILURE_RETRY_TICKS = 20 * 10;
    private static final double SCAFFOLD_PLACE_CLEARANCE_Y = 0.65D;
    private static final double SCAFFOLD_FALLBACK_PLACE_CLEARANCE_Y = 0.55D;
    private static final double SCAFFOLD_HORIZONTAL_REACHED_SQR = 1.5D * 1.5D;
    private static final double SCAFFOLD_CENTER_EPSILON = 0.05D;
    private static final int SUPPORT_SCAN_DEPTH = 3;
    private static final int MAX_DIRECT_CLEAR_VERTICAL_GAP = 3;
    private static final int MAX_TERRAFORM_SAFE_DROP_BLOCKS = 6;
    private static final int TERRAFORM_LOCAL_ROUTE_RADIUS = 4;
    private static final int TERRAFORM_LOCAL_ROUTE_DOWN = 6;
    private static final int TERRAFORM_LOCAL_ROUTE_UP = 2;
    private static final int TERRAFORM_CLEAR_OBSTRUCTION_TICKS = 24;
    private static final int TERRAFORM_ROUTE_CLEAR_NO_PROGRESS_TICKS = 20 * 5;
    private static final int TERRAFORM_ROUTE_CLEAR_RETRY_TICKS = 20;
    private static final double TERRAFORM_ROUTE_PROGRESS_EPSILON_SQR = 1.0D;
    private static final double TERRAFORM_ROUTE_CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final int TERRAFORM_ROUTE_CORRIDOR_STEPS = 10;
    private static final List<net.minecraft.world.item.Item> FILL_ITEMS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRASS_BLOCK,
            Items.PODZOL,
            Items.SAND,
            Items.RED_SAND,
            Items.GRAVEL,
            Items.MUD,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE
    );
    private static final Map<PlayerNpcEntity, ActionablePrepCache> ACTIONABLE_PREP_CACHE = new WeakHashMap<>();

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final PlacingBlockAi placingBlockAi;
    private final ToolAi routeToolAi;
    private final BreakingBlockAi routeBreakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final double speed;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final List<BlockPos> temporaryScaffold = new ArrayList<>();
    private final List<BlockPos> skippedSupportTargets = new ArrayList<>();
    private final Set<BlockPos> skippedRouteClearTargets = new HashSet<>();
    private final Map<BlockPos, Long> failedDirectClearTargets = new HashMap<>();
    private TerraformTarget target;
    private BlockPos approachWorkPos;
    private BlockPos lastMovementTarget;
    private BlockPos lastSupportFillFailurePos;
    private BlockPos scaffoldPlacePos;
    private BlockPos supportFillEscapeHandoffPos;
    private long supportFillEscapeHandoffUntilTick;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int workTicks;
    private int repathTicks;
    private int routeClearNoProgressTicks;
    private int routeClearRetryTicks;
    private double bestApproachDistanceSqr = Double.MAX_VALUE;
    private int supportFillFailures;
    private int supportFillRetryCooldownTicks;
    private int supportClearanceMoveTicks;
    private int supportClearanceJumpCooldownTicks;
    private int scaffoldJumpDelayTicks;
    private int scaffoldPlaceDelayTicks;
    private int scaffoldPlaceWaitTicks;
    private int targetSearchRetryCooldownTicks;
    private String targetSearchLayoutId = "";
    private BlockPos targetSearchOrigin;
    private int targetSearchClearBlockIndex;
    private int targetSearchSupportColumnIndex;
    private boolean targetSearchClearComplete;
    private boolean continuingTargetSearch;
    private boolean usingTemporaryMainHand;
    private boolean workerSlotPaused;

    public TerraformBuildSiteGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.routeToolAi = new ToolAi(playerNpc);
        this.routeBreakingBlockAi = new BreakingBlockAi(playerNpc, this.routeToolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.routeBreakingBlockAi);
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasActionablePrepWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!hasActiveBuildingJob(playerNpc, serverLevel)) {
            return false;
        }
        if (hasActiveVerticalEscape(playerNpc)
                || playerNpc.isStoneAccessClearing()
                || GatherStoneGoal.isStoneGatheringEpisodeActive(playerNpc)) {
            return false;
        }
        if (findBuildContext(playerNpc).isEmpty()) {
            return false;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        ActionablePrepCache cached = ACTIONABLE_PREP_CACHE.get(playerNpc);
        if (cached != null && cached.matches(playerNpc, serverLevel, home.orElse(null), layoutId)) {
            return cached.actionable();
        }
        // Arbitration predicates must not refresh the complete footprint. Conservatively retain
        // preparation priority until the admitted Terraform activation publishes a real result.
        return true;
    }

    public static boolean hasPrepWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!hasActiveBuildingJob(playerNpc, serverLevel)) {
            return false;
        }
        if (findBuildContext(playerNpc).isEmpty()) {
            return false;
        }
        ActionablePrepCache cached = matchingPrepCache(playerNpc, serverLevel);
        return cached == null || cached.hasPrep();
    }

    /** Completion arbitration consumes the last admitted footprint result. */
    public static boolean hasPrepWorkIgnoringActiveJob(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || findBuildContext(playerNpc).isEmpty()) {
            return false;
        }
        ActionablePrepCache cached = matchingPrepCache(playerNpc, serverLevel);
        // BeingAtHome and finished-home exploration are selector predicates. They may delay
        // completion until Terraform refreshes an expired result, but may never rescan the full
        // footprint synchronously from an otherwise idle entity tick.
        return cached == null || cached.hasPrep();
    }

    private static boolean hasActiveBuildingJob(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && serverLevel != null
                && playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING);
    }

    private static boolean hasActiveVerticalEscape(PlayerNpcEntity playerNpc) {
        return playerNpc.getUpwardEscapeTarget() != null
                || playerNpc.getHoleEscapeCooldown() > 0
                || "ai.player_npc.pillaring_up".equals(playerNpc.getCurrentAiState());
    }

    public static boolean needsShovelForPrep(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!hasActiveBuildingJob(playerNpc, serverLevel)) {
            return false;
        }
        ActionablePrepCache cached = matchingPrepCache(playerNpc, serverLevel);
        return cached != null && cached.needsShovel();
    }

    @Override
    public boolean canUse() {
        if (this.supportFillRetryCooldownTicks > 0) {
            this.supportFillRetryCooldownTicks--;
            return false;
        }
        if (this.targetSearchRetryCooldownTicks > 0) {
            this.targetSearchRetryCooldownTicks--;
            return false;
        }

        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.isStoneAccessClearing()
                || hasActiveVerticalEscape(this.playerNpc)
                || GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc)
                || GatherStoneGoal.isStoneGatheringEpisodeActive(this.playerNpc)
                || this.playerNpc.getTarget() != null) {
            return false;
        }
        if (findBuildContext(this.playerNpc).isEmpty()) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (this.shouldYieldToGearCrafting(serverLevel)) {
            return false;
        }
        if (!this.temporaryScaffold.isEmpty()) {
            if (this.target != null && this.isTargetStillValid(serverLevel, this.target)) {
                this.targetSearchRetryCooldownTicks = 0;
                return true;
            }
            this.target = null;
            this.targetSearchRetryCooldownTicks = 0;
            return true;
        }

        TerraformTarget supportFillHandoff = this.getValidSupportFillEscapeHandoff(serverLevel);
        if (supportFillHandoff != null) {
            this.target = supportFillHandoff;
            this.continuingTargetSearch = false;
            this.targetSearchRetryCooldownTicks = 0;
            return true;
        }

        // A completed negative scan is authoritative for the current construction phase. The
        // arbitration helpers already consume this cache, but canUse previously ignored it and
        // immediately restarted the full cursor scan. That pending rescan published prep=true,
        // retained priority 5 MOVE ownership, and starved missing-log/stone goals indefinitely.
        // Supply/fill phase changes invalidate the cache through ActionablePrepCache.matches;
        // an expired failed-clear cooldown also gets one immediate retry.
        if (this.shouldHonorCompletedNoPrepCache(serverLevel)) {
            return false;
        }

        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.targetSearchRetryCooldownTicks = 1 + this.playerNpc.getRandom().nextInt(4);
            return false;
        }
        TargetSearchResult nextTarget = this.findNextTarget(serverLevel, true);
        if (!nextTarget.complete()) {
            publishPendingPrepCache(this.playerNpc, serverLevel);
            this.target = null;
            this.continuingTargetSearch = true;
            // The goal is starting and now owns a worker turn. Keep the per-tick footprint slice
            // small, but do not add a second randomized delay between successful admitted slices.
            this.targetSearchRetryCooldownTicks = 0;
            return true;
        }
        publishPrepCache(this.playerNpc, serverLevel, nextTarget.target());
        if (nextTarget.target() == null) {
            this.continuingTargetSearch = false;
            this.targetSearchRetryCooldownTicks = TARGET_SEARCH_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10);
            return false;
        }

        if (nextTarget.target().phase() == TerraformPhase.CLEAR
                && serverLevel.getBlockState(nextTarget.target().pos()).is(BlockTags.MINEABLE_WITH_SHOVEL)
                && !hasTool(this.playerNpc, ShovelItem.class)) {
            return false;
        }
        this.target = nextTarget.target();
        this.continuingTargetSearch = false;
        this.targetSearchRetryCooldownTicks = 0;
        return true;
    }

    private boolean shouldHonorCompletedNoPrepCache(ServerLevel serverLevel) {
        ActionablePrepCache cached = matchingPrepCache(this.playerNpc, serverLevel);
        if (cached == null || cached.hasPrep()) {
            return false;
        }

        boolean failedClearRetryReady = this.failedDirectClearTargets.values().stream()
                .anyMatch(retryTick -> retryTick <= serverLevel.getGameTime());
        if (failedClearRetryReady) {
            ACTIONABLE_PREP_CACHE.remove(this.playerNpc);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return (this.target != null || !this.temporaryScaffold.isEmpty() || this.continuingTargetSearch)
                && this.playerNpc.isAlive()
                && !this.playerNpc.isStoneAccessClearing()
                && !hasActiveVerticalEscape(this.playerNpc)
                && !GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc)
                && !GatherStoneGoal.isStoneGatheringEpisodeActive(this.playerNpc)
                && this.playerNpc.getTarget() == null;
    }

    private boolean shouldYieldToGearCrafting() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.shouldYieldToGearCrafting(serverLevel);
    }

    private boolean shouldYieldToGearCrafting(ServerLevel serverLevel) {
        // Terraform only needs a crafting handoff when its admitted footprint scan found a
        // shovel-only clear target. Generic gear upgrades can be craftable while no usable
        // table/placement plan exists; yielding to that broad inventory probe here leaves the
        // prep cache actionable, keeps the stone phase closed, and strands an idle worker.
        return needsShovelForPrep(this.playerNpc, serverLevel)
                && CraftBasicGearGoal.shouldPrioritizeGearCrafting(this.playerNpc, serverLevel);
    }

    @Override
    public void start() {
        if (this.workerSlotPaused
                && PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && this.scaffoldPlacePos != null) {
            this.workerSlotPaused = false;
            this.playerNpc.setCurrentAiState("ai.player_npc.terraforming_build_site");
            this.updateTaskDetail();
            return;
        }
        this.workerSlotPaused = false;
        this.workTicks = 0;
        this.repathTicks = 0;
        this.scaffoldPlacePos = null;
        this.scaffoldJumpDelayTicks = 0;
        this.scaffoldPlaceDelayTicks = 0;
        this.scaffoldPlaceWaitTicks = 0;
        this.supportFillFailures = 0;
        this.supportClearanceMoveTicks = 0;
        this.supportClearanceJumpCooldownTicks = 0;
        this.lastSupportFillFailurePos = null;
        this.skippedSupportTargets.clear();
        this.skippedRouteClearTargets.clear();
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        this.resetRouteClearProgress();
        this.playerNpc.setCurrentAiState("ai.player_npc.terraforming_build_site");
        this.updateTaskDetail();
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            // canUse may run under a probe-only worker slot. Publish the forced escape request
            // only after StartupWorkGatedGoal has promoted this goal to an active worker;
            // otherwise EscapeHoleWithBlockGoal correctly refuses to pillar and the builder
            // remains idle with a request that no active goal owns.
            if (this.target != null
                    && this.target.phase() == TerraformPhase.FILL_SUPPORT
                    && this.deferSupportFillForVerticalEscape(serverLevel, this.target.pos())) {
                return;
            }
            this.moveToTarget(serverLevel);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.supportClearanceMoveTicks > 0) {
            this.supportClearanceMoveTicks--;
        }
        if (this.supportClearanceJumpCooldownTicks > 0) {
            this.supportClearanceJumpCooldownTicks--;
        }
        if (this.routeClearRetryTicks > 0) {
            this.routeClearRetryTicks--;
        }

        if (this.target == null) {
            this.tickScaffoldCleanup(serverLevel);
            if (this.temporaryScaffold.isEmpty() && this.continuingTargetSearch) {
                this.tickContinuingTargetSearch(serverLevel);
            }
            return;
        }

        if (this.scaffoldPlacePos != null) {
            this.stopEpicFightDiggingAnimation();
            this.tickScaffoldPlacement(serverLevel);
            return;
        }

        if (!this.isTargetStillValid(serverLevel, this.target)) {
            if (this.isSupportFillEscapeHandoffTarget(this.target)) {
                this.clearSupportFillEscapeHandoff(false);
            }
            this.playerNpc.clearBlockBreakProgress(this.target.pos());
            this.stopEpicFightDiggingAnimation();
            this.workTicks = 0;
            this.restorePreviousMainHand();
            if (!this.temporaryScaffold.isEmpty()) {
                this.target = null;
                this.clearBlockAi.stop();
                this.routeBreakingBlockAi.stop();
                this.routeToolAi.restoreMainHand();
                this.resetRouteClearProgress();
                this.skippedRouteClearTargets.clear();
                this.updateTaskDetail();
                return;
            }
            if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                this.target = null;
                this.continuingTargetSearch = true;
                this.targetSearchRetryCooldownTicks = 1 + this.playerNpc.getRandom().nextInt(4);
                return;
            }
            TargetSearchResult nextTarget = this.findNextTarget(serverLevel, true);
            if (!nextTarget.complete()) {
                publishPendingPrepCache(this.playerNpc, serverLevel);
                this.target = null;
                this.continuingTargetSearch = true;
                this.targetSearchRetryCooldownTicks = 0;
                return;
            }
            this.target = nextTarget.target();
            this.continuingTargetSearch = false;
            publishPrepCache(this.playerNpc, serverLevel, this.target);
            this.resetRouteClearProgress();
            this.skippedRouteClearTargets.clear();
            this.updateTaskDetail();
            if (this.target == null) {
                this.targetSearchRetryCooldownTicks = TARGET_SEARCH_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10);
                return;
            }
        }
        if (this.target.phase() == TerraformPhase.FILL_SUPPORT
                && this.deferSupportFillForVerticalEscape(serverLevel, this.target.pos())) {
            this.stopEpicFightDiggingAnimation();
            this.restorePreviousMainHand();
            this.target = null;
            this.workTicks = 0;
            this.resetRouteClearProgress();
            this.skippedRouteClearTargets.clear();
            return;
        }

        if (this.tickRouteClearBlock(serverLevel)) {
            return;
        }

        BlockPos pos = this.target.pos();
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.target.phase() == TerraformPhase.FILL_SUPPORT && this.supportClearanceMoveTicks > 0) {
            this.playerNpc.clearBlockBreakProgress(pos);
            this.stopEpicFightDiggingAnimation();
            return;
        }
        if (this.shouldScaffoldToward(serverLevel, pos)) {
            if (this.beginScaffoldStep(serverLevel, this.playerNpc.blockPosition())) {
                this.playerNpc.clearBlockBreakProgress(pos);
                this.stopEpicFightDiggingAnimation();
                return;
            }
            if (this.needsScaffoldForVerticalReach(pos)) {
                this.playerNpc.clearBlockBreakProgress(pos);
                this.stopEpicFightDiggingAnimation();
                this.updateTaskDetail("pillar blocked for clear @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
                return;
            }
        }

        if (this.isStandingOnClearTarget(pos) || !this.isWithinDirectClearReach(pos)) {
            this.playerNpc.clearBlockBreakProgress(pos);
            this.stopEpicFightDiggingAnimation();
            this.trackRouteClearProgress(pos);
            boolean navigationProblem = this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck();
            if (this.shouldStartRouteClear(navigationProblem)
                    && this.startClearingRoute(serverLevel, pos)) {
                return;
            }
            if (this.repathTicks-- <= 0) {
                this.repathTicks = 20;
                this.moveToTarget(serverLevel);
            }
            return;
        }

        this.resetRouteClearProgress();
        this.playerNpc.getNavigation().stop();
        switch (this.target.phase()) {
            case CLEAR, CLEAR_WATER -> this.tickClear(serverLevel);
            case FILL_SUPPORT -> {
                this.stopEpicFightDiggingAnimation();
                this.tickFillSupport(serverLevel);
            }
        }
    }

    @Override
    public void stop() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && (this.workerSlotPaused || this.scaffoldPlacePos != null)) {
            this.playerNpc.clearBlockBreakProgress(this.scaffoldPlacePos);
            this.restorePreviousMainHand();
            this.clearBlockAi.stop();
            this.routeBreakingBlockAi.stop();
            this.routeToolAi.restoreMainHand();
            this.playerNpc.getNavigation().stop();
            this.stopEpicFightDiggingAnimation();
            this.workerSlotPaused = true;
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            // Retain the unplaced scaffold step and exact persisted support ledger. A later
            // holder revalidates placement before mutating the world.
            return;
        }
        if (this.target != null) {
            this.playerNpc.clearBlockBreakProgress(this.target.pos());
        }
        this.playerNpc.clearBlockBreakProgress(this.scaffoldPlacePos);
        if (!this.temporaryScaffold.isEmpty()) {
            this.playerNpc.clearBlockBreakProgress(this.temporaryScaffold.get(this.temporaryScaffold.size() - 1));
        }
        this.restorePreviousMainHand();
        this.clearBlockAi.stop();
        this.routeBreakingBlockAi.stop();
        this.routeToolAi.restoreMainHand();
        this.playerNpc.getNavigation().stop();
        if (this.temporaryScaffold.isEmpty()) {
            this.target = null;
        }
        this.continuingTargetSearch = false;
        this.scaffoldPlacePos = null;
        this.skippedSupportTargets.clear();
        this.skippedRouteClearTargets.clear();
        this.lastSupportFillFailurePos = null;
        this.workTicks = 0;
        this.repathTicks = 0;
        this.resetRouteClearProgress();
        this.supportFillFailures = 0;
        this.supportClearanceMoveTicks = 0;
        this.supportClearanceJumpCooldownTicks = 0;
        this.scaffoldJumpDelayTicks = 0;
        this.scaffoldPlaceDelayTicks = 0;
        this.scaffoldPlaceWaitTicks = 0;
        this.stopEpicFightDiggingAnimation();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void tickClear(ServerLevel serverLevel) {
        BlockPos pos = this.target.pos();
        BlockState state = serverLevel.getBlockState(pos);
        if (this.target.phase() == TerraformPhase.CLEAR_WATER) {
            this.stopEpicFightDiggingAnimation();
            if (state.getFluidState().isEmpty()) {
                this.queueNextTargetSearch();
                return;
            }
            if (FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)) {
                this.playerNpc.setIdleTraceDetail("terraform clear protected by owned farm @ "
                        + pos.getX() + " " + pos.getY() + " " + pos.getZ(), 40);
                this.target = null;
                this.workTicks = 0;
                return;
            }
            if (this.workTicks++ < PLACE_DELAY_TICKS) {
                return;
            }
            serverLevel.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            this.playerNpc.triggerMainHandUseAnimation();
            this.queueNextTargetSearch();
            this.workTicks = 0;
            this.updateTaskDetail();
            return;
        }

        if (!this.canClearBlueprintTarget(serverLevel, pos, state)) {
            this.playerNpc.clearBlockBreakProgress(pos);
            this.stopEpicFightDiggingAnimation();
            this.target = null;
            this.workTicks = 0;
            return;
        }

        this.equipToolFor(state);
        this.keepEpicFightDiggingAnimation();
        if (this.workTicks % MINE_HIT_INTERVAL_TICKS == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            this.playEpicFightDiggingAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, pos, state, this.playerNpc);
        }

        this.workTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, pos, state);
        this.playerNpc.showBlockBreakProgress(pos, this.workTicks, requiredMineTicks);
        if (this.workTicks < requiredMineTicks) {
            return;
        }

        boolean temporaryCraftingTable = CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos);
        BlockPos bedCompanion = PlayerNpcBedUtil.findMatchingCompanion(serverLevel, pos, state).orElse(null);
        boolean destroyed = PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, pos, state, this.playerNpc);
        if (destroyed) {
            this.failedDirectClearTargets.remove(pos);
            PlayerNpcBedUtil.removeRemainingCompanion(serverLevel, this.playerNpc, state, bedCompanion);
            if (temporaryCraftingTable) {
                CraftBasicGearGoal.clearTemporaryCraftingTable(this.playerNpc);
            }
            this.playerNpc.hurtMainHandItem(1);
        } else {
            this.failedDirectClearTargets.put(
                    pos.immutable(),
                    serverLevel.getGameTime() + DIRECT_CLEAR_FAILURE_RETRY_TICKS
            );
            this.targetSearchRetryCooldownTicks = TARGET_SEARCH_RETRY_COOLDOWN_TICKS;
            this.playerNpc.setIdleTraceDetail("terraform clear failed; target cooling down @ "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ(), 40);
        }
        this.playerNpc.clearBlockBreakProgress(pos);
        this.stopEpicFightDiggingAnimation();
        this.queueNextTargetSearch(destroyed);
        this.workTicks = 0;
        this.restorePreviousMainHand();
        this.updateTaskDetail();
    }

    private void tickFillSupport(ServerLevel serverLevel) {
        BlockPos pos = this.target.pos();
        // Equip the exact support block before the placement windup so the visible hand matches
        // the state that will be placed. The selected one-item stack remains in main hand across
        // retriable clearance failures and is consumed only by a successful placeHeldBlock call.
        Optional<ItemStack> fillStack = this.useFillBlockInMainHand();
        if (fillStack.isEmpty()) {
            if (this.isSupportFillEscapeHandoffTarget(this.target)) {
                this.clearSupportFillEscapeHandoff(false);
            }
            this.restorePreviousMainHand();
            this.target = null;
            return;
        }
        BlockState fillState = ((BlockItem) fillStack.get().getItem()).getBlock().defaultBlockState();

        if (this.workTicks++ < PLACE_DELAY_TICKS) {
            return;
        }
        this.workTicks = 0;

        if (!PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
            this.noteSupportFillFailure(serverLevel, pos, "blocked");
            return;
        }
        if (!this.canPlaceSupportWithoutClipping(serverLevel, pos, fillState)) {
            if (this.handleSupportFillClipping(serverLevel, pos, fillState)) {
                return;
            }
            this.noteSupportFillFailure(serverLevel, pos, "clipping");
            return;
        }

        if (!this.placingBlockAi.placeHeldBlock(serverLevel, pos, fillState)) {
            this.noteSupportFillFailure(serverLevel, pos, "place_failed");
            return;
        }

        // This fill is part of the finished terrain/build foundation, not traversal scaffold.
        // Registering it as a temporary pillar would let the ownership recovery pass dismantle
        // valid foundation blocks after the builder walked away.
        this.supportFillFailures = 0;
        this.lastSupportFillFailurePos = null;
        this.supportClearanceMoveTicks = 0;
        this.supportClearanceJumpCooldownTicks = 0;
        if (this.isSupportFillEscapeHandoffTarget(this.target)) {
            this.clearSupportFillEscapeHandoff(false);
        }
        // placeHeldBlock consumed the selected one-item fill stack. Restore the original hand
        // before the cursor moves to another target; stop() remains the interruption fallback.
        this.restorePreviousMainHand();
        this.queueNextTargetSearch();
    }

    /**
     * Keep ownership of the builder work turn while the bounded footprint cursor finds the next
     * actionable block. Releasing the goal after every successful block let unrelated routine
     * goals win arbitration for seconds at a time even though terraform work remained.
     */
    private void tickContinuingTargetSearch(ServerLevel serverLevel) {
        if (this.targetSearchRetryCooldownTicks > 0) {
            this.targetSearchRetryCooldownTicks--;
            return;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.targetSearchRetryCooldownTicks = 1 + this.playerNpc.getRandom().nextInt(4);
            return;
        }

        TargetSearchResult nextTarget = this.findNextTarget(serverLevel, true);
        if (!nextTarget.complete()) {
            publishPendingPrepCache(this.playerNpc, serverLevel);
            // The retained pass is bounded by both entry count and elapsed time. Resume on the
            // next normal goal tick; admission denial remains the only randomized backoff.
            this.targetSearchRetryCooldownTicks = 0;
            this.updateTaskDetail();
            return;
        }

        this.target = nextTarget.target();
        publishPrepCache(this.playerNpc, serverLevel, this.target);
        this.continuingTargetSearch = false;
        if (this.target != null && !canRunTargetNow(serverLevel, this.playerNpc, this.target)) {
            this.target = null;
        }
        this.resetRouteClearProgress();
        this.skippedRouteClearTargets.clear();
        this.updateTaskDetail();
        if (this.target != null) {
            this.moveToTarget(serverLevel);
        }
    }

    private void queueNextTargetSearch() {
        this.queueNextTargetSearch(true);
    }

    private void queueNextTargetSearch(boolean immediate) {
        this.target = null;
        this.continuingTargetSearch = true;
        if (immediate) {
            this.targetSearchRetryCooldownTicks = 0;
        }
    }

    private boolean handleSupportFillClipping(ServerLevel serverLevel, BlockPos pos, BlockState fillState) {
        if (!this.placingBlockAi.hasSelfPlacementCollision(serverLevel, pos, fillState)) {
            return false;
        }

        if (this.tryJumpForSupportClearance(serverLevel, pos)) {
            this.supportClearanceMoveTicks = SUPPORT_CLEARANCE_RETRY_TICKS;
            this.workTicks = PLACE_DELAY_TICKS;
            this.playerNpc.setCurrentAiDetail("jumping clear of fill support @ "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            return true;
        }

        BlockPos standPos = this.findSupportClearanceStand(serverLevel, pos, fillState);
        if (standPos == null) {
            return false;
        }

        if (standPos.getY() > this.playerNpc.blockPosition().getY()) {
            this.tryJumpForSupportClearance(serverLevel, pos);
        }
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getMoveControl().setWantedPosition(
                standPos.getX() + 0.5D,
                standPos.getY(),
                standPos.getZ() + 0.5D,
                Math.max(0.85D, this.speed)
        );
        this.supportClearanceMoveTicks = SUPPORT_CLEARANCE_RETRY_TICKS;
        this.workTicks = PLACE_DELAY_TICKS;
        this.playerNpc.setCurrentAiDetail("moving clear of fill support @ "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        return true;
    }

    private void noteSupportFillFailure(ServerLevel serverLevel, BlockPos pos, String reason) {
        BlockPos immutable = pos.immutable();
        if (!immutable.equals(this.lastSupportFillFailurePos)) {
            this.lastSupportFillFailurePos = immutable;
            this.supportFillFailures = 0;
        }

        this.supportFillFailures++;
        this.playerNpc.setCurrentAiDetail("fill support " + reason + " "
                + this.supportFillFailures + "/" + MAX_SUPPORT_FILL_FAILURES
                + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        if (this.supportFillFailures < MAX_SUPPORT_FILL_FAILURES) {
            return;
        }

        if (!this.skippedSupportTargets.contains(immutable)) {
            this.skippedSupportTargets.add(immutable);
        }
        // This target has now failed permanently for the current pass. Return the unconsumed held
        // fill item to inventory and restore the original hand before selecting another target.
        this.restorePreviousMainHand();
        this.supportFillFailures = 0;
        this.lastSupportFillFailurePos = null;
        if (this.isSupportFillEscapeHandoffTarget(this.target)) {
            this.clearSupportFillEscapeHandoff(true);
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.target = null;
            this.continuingTargetSearch = true;
            this.targetSearchRetryCooldownTicks = 1 + this.playerNpc.getRandom().nextInt(4);
            this.updateTaskDetail();
            return;
        }
        TargetSearchResult nextTarget = this.findNextTarget(serverLevel, true);
        if (!nextTarget.complete()) {
            publishPendingPrepCache(this.playerNpc, serverLevel);
            this.target = null;
            this.continuingTargetSearch = true;
            this.targetSearchRetryCooldownTicks = 0;
            this.updateTaskDetail();
            return;
        }
        this.target = nextTarget.target();
        this.continuingTargetSearch = false;
        publishPrepCache(this.playerNpc, serverLevel, this.target);
        if (this.target == null) {
            this.supportFillRetryCooldownTicks = SUPPORT_FILL_RETRY_COOLDOWN_TICKS;
        }
        this.updateTaskDetail();
    }

    private boolean shouldScaffoldToward(ServerLevel serverLevel, BlockPos pos) {
        boolean verticalAssistNeeded = this.needsScaffoldForVerticalReach(pos);
        if (this.target == null
                || this.target.phase() != TerraformPhase.CLEAR
                || !verticalAssistNeeded
                && this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= WORK_DISTANCE_SQR
                || !hasScaffoldBlock(this.playerNpc)) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.isHorizontallyCloseForScaffold(feet, pos)) {
            return false;
        }

        if (pos.getY() <= feet.getY() + 2) {
            return false;
        }

        int availableBlocks = this.countScaffoldBlocks();
        if (availableBlocks <= 0) {
            return false;
        }

        int maxPlacements = Math.min(availableBlocks, Math.max(1, pos.getY() - feet.getY()));
        for (int placed = 0; placed <= maxPlacements; placed++) {
            BlockPos feetAtHeight = feet.above(placed);
            if (!this.hasOpenBodySpace(serverLevel, feetAtHeight)) {
                return !this.needsScaffoldForVerticalReach(feetAtHeight, pos)
                        || feetAtHeight.distSqr(pos) <= WORK_DISTANCE_SQR + 1.0D;
            }
            if (!this.needsScaffoldForVerticalReach(feetAtHeight, pos)
                    || !verticalAssistNeeded && feetAtHeight.distSqr(pos) <= WORK_DISTANCE_SQR) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithinDirectClearReach(BlockPos pos) {
        return !this.needsScaffoldForVerticalReach(pos)
                && this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= WORK_DISTANCE_SQR;
    }

    private boolean needsScaffoldForVerticalReach(BlockPos pos) {
        return this.needsScaffoldForVerticalReach(this.playerNpc.blockPosition(), pos);
    }

    private boolean needsScaffoldForVerticalReach(BlockPos feet, BlockPos pos) {
        return pos.getY() > feet.getY() + MAX_DIRECT_CLEAR_VERTICAL_GAP;
    }

    private boolean isHorizontallyCloseForScaffold(BlockPos feet, BlockPos pos) {
        double dx = this.playerNpc.getX() - (pos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz <= SCAFFOLD_HORIZONTAL_REACHED_SQR
                || this.horizontalDistanceToWorkSqr(feet, pos) <= SCAFFOLD_HORIZONTAL_REACHED_SQR;
    }

    private boolean beginScaffoldStep(ServerLevel serverLevel, BlockPos feet) {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.workerSlotPaused = true;
            return false;
        }
        if (!this.playerNpc.onGround()) {
            this.lookDownAt(feet);
            return true;
        }
        if (!this.centerOnScaffoldBase(serverLevel, feet)) {
            return false;
        }

        boolean replaceable = serverLevel.getBlockState(feet).canBeReplaced();
        boolean openBodySpace = this.hasOpenBodySpace(serverLevel, feet);
        boolean otherEntity = this.hasOtherEntityInBlock(serverLevel, feet);
        boolean equippedBlock = replaceable && openBodySpace && !otherEntity && this.useScaffoldBlockInMainHand().isPresent();
        if (!replaceable || !openBodySpace || otherEntity || !equippedBlock) {
            return false;
        }

        this.scaffoldPlacePos = feet.immutable();
        this.scaffoldJumpDelayTicks = SCAFFOLD_JUMP_WINDUP_TICKS;
        this.scaffoldPlaceDelayTicks = SCAFFOLD_PLACE_DELAY_TICKS;
        this.scaffoldPlaceWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.scaffoldPlacePos);
        this.updateTaskDetail();
        return true;
    }

    private void tickScaffoldPlacement(ServerLevel serverLevel) {
        if (this.scaffoldPlacePos == null) {
            return;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.stopEpicFightDiggingAnimation();
            this.workerSlotPaused = true;
            return;
        }

        if (this.scaffoldJumpDelayTicks > 0) {
            if (this.useScaffoldBlockInMainHand().isEmpty()) {
                this.clearScaffoldPlacement();
                return;
            }
            this.playerNpc.getNavigation().stop();
            this.lookDownAt(this.scaffoldPlacePos);
            this.scaffoldJumpDelayTicks--;
            if (this.scaffoldJumpDelayTicks <= 0) {
                this.playerNpc.shortPillarJump();
            }
            return;
        }

        if (this.scaffoldPlaceDelayTicks > 0) {
            this.scaffoldPlaceDelayTicks--;
            return;
        }

        this.scaffoldPlaceWaitTicks++;
        if (this.scaffoldPlaceWaitTicks > SCAFFOLD_MAX_PLACE_WAIT_TICKS) {
            this.clearScaffoldPlacement();
            return;
        }

        if (!this.hasScaffoldPlacementClearance()) {
            this.lookDownAt(this.scaffoldPlacePos);
            return;
        }

        Optional<ItemStack> scaffoldStack = this.useScaffoldBlockInMainHand();
        if (!serverLevel.getBlockState(this.scaffoldPlacePos).canBeReplaced() || scaffoldStack.isEmpty()) {
            this.clearScaffoldPlacement();
            return;
        }

        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof BlockItem blockItem)) {
            this.clearScaffoldPlacement();
            return;
        }

        BlockState placeState = blockItem.getBlock().defaultBlockState();
        if (!this.canPlaceScaffoldWithoutClipping(serverLevel, this.scaffoldPlacePos, placeState)) {
            this.lookDownAt(this.scaffoldPlacePos);
            return;
        }

        this.lookDownAt(this.scaffoldPlacePos);
        if (!this.placingBlockAi.placeHeldBlock(serverLevel, this.scaffoldPlacePos, placeState)) {
            this.clearScaffoldPlacement();
            return;
        }

        BlockPos placedScaffold = this.scaffoldPlacePos.immutable();
        this.snapAboveScaffoldIfNeeded(placedScaffold);
        this.temporaryScaffold.add(placedScaffold);
        this.playerNpc.markTemporaryPillarSupport(placedScaffold);
        this.clearScaffoldPlacement();
        this.updateTaskDetail();
    }

    private void tickScaffoldCleanup(ServerLevel serverLevel) {
        if (this.temporaryScaffold.isEmpty()) {
            this.stopEpicFightDiggingAnimation();
            return;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.stopEpicFightDiggingAnimation();
            this.workerSlotPaused = true;
            return;
        }

        if (!this.playerNpc.onGround()) {
            this.stopEpicFightDiggingAnimation();
            this.lookDownAt(this.temporaryScaffold.get(this.temporaryScaffold.size() - 1));
            this.updateTaskDetail();
            return;
        }

        BlockPos pos = this.temporaryScaffold.get(this.temporaryScaffold.size() - 1);
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) {
            this.playerNpc.clearBlockBreakProgress(pos);
            this.playerNpc.forgetTemporaryPillarSupport(pos);
            this.stopEpicFightDiggingAnimation();
            this.temporaryScaffold.remove(this.temporaryScaffold.size() - 1);
            this.workTicks = 0;
            this.updateTaskDetail();
            return;
        }
        if (!this.playerNpc.isTemporaryPillarSupport(pos)) {
            // The tracked block changed or ownership was otherwise invalidated. Never destroy a
            // player/natural replacement merely because the old scaffold position is remembered.
            this.playerNpc.clearBlockBreakProgress(pos);
            this.stopEpicFightDiggingAnimation();
            this.temporaryScaffold.remove(this.temporaryScaffold.size() - 1);
            this.workTicks = 0;
            this.updateTaskDetail();
            return;
        }

        this.equipToolFor(state);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        this.keepEpicFightDiggingAnimation();
        if (this.workTicks % MINE_HIT_INTERVAL_TICKS == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            this.playEpicFightDiggingAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, pos, state, this.playerNpc);
        }

        this.workTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, pos, state);
        this.playerNpc.showBlockBreakProgress(pos, this.workTicks, requiredMineTicks);
        this.updateTaskDetail();
        if (this.workTicks < requiredMineTicks) {
            return;
        }

        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, pos, state, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
        }
        this.playerNpc.clearBlockBreakProgress(pos);
        this.playerNpc.forgetTemporaryPillarSupport(pos);
        this.stopEpicFightDiggingAnimation();
        this.temporaryScaffold.remove(this.temporaryScaffold.size() - 1);
        this.workTicks = 0;
        if (this.temporaryScaffold.isEmpty()) {
            this.restorePreviousMainHand();
        }
        this.updateTaskDetail();
    }

    private void keepEpicFightDiggingAnimation() {
        if (Platform.isModLoaded("epicfight")) EpicFightHooks.keepDiggingState(this.playerNpc);
    }

    private void playEpicFightDiggingAnimation() {
        if (Platform.isModLoaded("epicfight")) EpicFightHooks.playDiggingAnimation(this.playerNpc);
    }

    private void stopEpicFightDiggingAnimation() {
        if (Platform.isModLoaded("epicfight")) EpicFightHooks.stopDiggingAnimation(this.playerNpc);
    }

    private void clearScaffoldPlacement() {
        this.scaffoldPlacePos = null;
        this.scaffoldJumpDelayTicks = 0;
        this.scaffoldPlaceDelayTicks = 0;
        this.scaffoldPlaceWaitTicks = 0;
    }

    private void moveToTarget(ServerLevel serverLevel) {
        if (this.target == null) {
            return;
        }

        BlockPos pos = this.findMovementTarget(serverLevel, this.target.pos());
        this.lastMovementTarget = pos.immutable();
        boolean moved = this.pathNavigationAi.moveToWithLocalFallback(
                serverLevel,
                pos,
                this.speed,
                MAX_TERRAFORM_SAFE_DROP_BLOCKS,
                TERRAFORM_LOCAL_ROUTE_RADIUS,
                TERRAFORM_LOCAL_ROUTE_DOWN,
                TERRAFORM_LOCAL_ROUTE_UP,
                MAX_TERRAFORM_LOCAL_PATH_CHECKS
        );
        if (moved) {
            this.updateTaskDetail();
            return;
        }

        if (this.startClearingRoute(serverLevel, this.target.pos())) {
            return;
        }

        this.updateTaskDetail("terraform path blocked @ "
                + this.target.pos().getX() + " "
                + this.target.pos().getY() + " "
                + this.target.pos().getZ()
                + " "
                + this.pathNavigationAi.lastMoveFailureDetail());
    }

    private boolean tickRouteClearBlock(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            return false;
        }

        BlockPos clearTarget = this.clearBlockAi.targetPos();
        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            this.updateTaskDetail();
            return true;
        }

        this.routeToolAi.restoreMainHand();
        if (result == ClearBlockAi.TickResult.DONE) {
            this.skippedRouteClearTargets.clear();
            this.repathTicks = 0;
            this.routeClearRetryTicks = 0;
            this.resetRouteClearProgress();
            this.moveToTarget(serverLevel);
            return true;
        }

        if (result == ClearBlockAi.TickResult.FAILED && clearTarget != null) {
            this.skippedRouteClearTargets.add(clearTarget.immutable());
            this.routeClearRetryTicks = TERRAFORM_ROUTE_CLEAR_RETRY_TICKS;
            this.repathTicks = 0;
        }
        return false;
    }

    private boolean shouldStartRouteClear(boolean navigationProblem) {
        return this.routeClearRetryTicks <= 0
                && (navigationProblem
                || this.routeClearNoProgressTicks >= TERRAFORM_ROUTE_CLEAR_NO_PROGRESS_TICKS);
    }

    private void trackRouteClearProgress(BlockPos workPos) {
        if (workPos == null) {
            this.resetRouteClearProgress();
            return;
        }

        double distanceSqr = this.playerNpc.distanceToSqr(
                workPos.getX() + 0.5D,
                workPos.getY() + 0.5D,
                workPos.getZ() + 0.5D
        );
        if (!workPos.equals(this.approachWorkPos)) {
            this.approachWorkPos = workPos.immutable();
            this.bestApproachDistanceSqr = distanceSqr;
            this.routeClearNoProgressTicks = 0;
            return;
        }

        if (distanceSqr + TERRAFORM_ROUTE_PROGRESS_EPSILON_SQR < this.bestApproachDistanceSqr) {
            this.bestApproachDistanceSqr = distanceSqr;
            this.routeClearNoProgressTicks = 0;
            return;
        }

        this.routeClearNoProgressTicks++;
    }

    private void resetRouteClearProgress() {
        this.approachWorkPos = null;
        this.lastMovementTarget = null;
        this.routeClearNoProgressTicks = 0;
        this.bestApproachDistanceSqr = Double.MAX_VALUE;
    }

    private boolean startClearingRoute(ServerLevel serverLevel, BlockPos workPos) {
        if (this.target == null || workPos == null || this.target.phase() != TerraformPhase.CLEAR) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos approachPos = this.lastMovementTarget != null
                ? this.lastMovementTarget
                : this.findMovementTarget(serverLevel, workPos);
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                feet,
                approachPos,
                workPos
        ));
        addTerraformRouteCandidates(candidates, feet, approachPos, workPos);

        List<BlockPos> ordered = candidates.stream()
                .filter(pos -> pos != null
                        && !pos.equals(workPos)
                        && !this.skippedRouteClearTargets.contains(pos)
                        && !this.temporaryScaffold.contains(pos)
                        && !this.playerNpc.isTemporaryPillarSupport(pos))
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> this.isClearableRouteCandidate(serverLevel, pos))
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
                .toList();
        for (BlockPos candidate : ordered) {
            if (this.startClearingRouteCandidate(serverLevel, candidate)) {
                this.playerNpc.clearBlockBreakProgress(workPos);
                this.stopEpicFightDiggingAnimation();
                this.repathTicks = 0;
                return true;
            }
        }

        this.routeClearRetryTicks = TERRAFORM_ROUTE_CLEAR_RETRY_TICKS;
        return false;
    }

    private boolean startClearingRouteCandidate(ServerLevel serverLevel, BlockPos candidate) {
        BlockState state = serverLevel.getBlockState(candidate);
        if (!this.isClearableRouteCandidate(serverLevel, candidate, state)) {
            return false;
        }

        this.restorePreviousMainHand();
        this.routeToolAi.restoreMainHand();
        return this.clearBlockAi.start(
                serverLevel,
                candidate,
                blockState -> this.isClearableRouteCandidate(serverLevel, candidate, blockState),
                "clearing terraform path",
                TERRAFORM_CLEAR_OBSTRUCTION_TICKS,
                TERRAFORM_ROUTE_CLEAR_DISTANCE_SQR,
                true
        );
    }

    private boolean isClearableRouteCandidate(ServerLevel serverLevel, BlockPos pos) {
        return this.isClearableRouteCandidate(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    private boolean isClearableRouteCandidate(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return canClearForBuild(serverLevel, this.playerNpc, pos, state)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private static void addTerraformRouteCandidates(
            List<BlockPos> candidates,
            BlockPos feet,
            BlockPos approachPos,
            BlockPos workPos
    ) {
        if (feet == null) {
            return;
        }

        addBodyColumn(candidates, feet);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addBodyColumn(candidates, feet.relative(direction));
        }

        BlockPos routeTarget = approachPos == null ? nearestSideTarget(feet, workPos) : approachPos;
        if (routeTarget != null) {
            addBodyColumn(candidates, routeTarget);
            addLineBodyColumns(candidates, feet, routeTarget, TERRAFORM_ROUTE_CORRIDOR_STEPS);
            addLineBodyColumns(candidates, feet.above(), routeTarget.above(), TERRAFORM_ROUTE_CORRIDOR_STEPS);
        }

        if (workPos == null) {
            return;
        }

        addLineBodyColumns(candidates, feet.above(), workPos.above(), TERRAFORM_ROUTE_CORRIDOR_STEPS);
        candidates.add(workPos.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addBodyColumn(candidates, workPos.relative(direction));
        }
    }

    private static void addLineBodyColumns(List<BlockPos> candidates, BlockPos from, BlockPos to, int steps) {
        if (from == null || to == null) {
            return;
        }

        int count = Math.max(1, steps);
        double dx = (to.getX() - from.getX()) / (double) count;
        double dy = (to.getY() - from.getY()) / (double) count;
        double dz = (to.getZ() - from.getZ()) / (double) count;
        for (int step = 1; step <= count; step++) {
            BlockPos column = new BlockPos(
                    (int) Math.round(from.getX() + dx * step),
                    (int) Math.round(from.getY() + dy * step),
                    (int) Math.round(from.getZ() + dz * step)
            );
            addBodyColumn(candidates, column);
        }
    }

    private static BlockPos nearestSideTarget(BlockPos feet, BlockPos target) {
        if (feet == null || target == null) {
            return target;
        }

        return Direction.Plane.HORIZONTAL.stream()
                .map(target::relative)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
                .orElse(target);
    }

    private static void addBodyColumn(List<BlockPos> candidates, BlockPos feet) {
        if (feet == null) {
            return;
        }

        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
    }

    private BlockPos findMovementTarget(ServerLevel serverLevel, BlockPos workPos) {
        BlockPos current = this.playerNpc.blockPosition();
        if (PathNavigationAi.canStandAt(serverLevel, current)
                && this.isWithinDirectClearReach(workPos)
                && !current.below().equals(workPos)) {
            return current.immutable();
        }

        Optional<BlockPos> scaffoldStart = this.findScaffoldStartTarget(serverLevel, workPos);
        if (scaffoldStart.isPresent()) {
            return scaffoldStart.get();
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -TERRAFORM_LOCAL_ROUTE_RADIUS; dx <= TERRAFORM_LOCAL_ROUTE_RADIUS; dx++) {
            for (int dz = -TERRAFORM_LOCAL_ROUTE_RADIUS; dz <= TERRAFORM_LOCAL_ROUTE_RADIUS; dz++) {
                if (dx * dx + dz * dz > TERRAFORM_LOCAL_ROUTE_RADIUS * TERRAFORM_LOCAL_ROUTE_RADIUS) {
                    continue;
                }
                // Foundation work is reachable from up to three blocks below its support.
                // Searching only one block below hid ordinary ground-level side approaches.
                for (int dy = -MAX_DIRECT_CLEAR_VERTICAL_GAP; dy <= 3; dy++) {
                    BlockPos candidate = workPos.offset(dx, dy, dz);
                    if (!PathNavigationAi.canStandAt(serverLevel, candidate)
                            || candidate.below().equals(workPos)
                            || this.needsScaffoldForVerticalReach(candidate, workPos)
                            || this.distanceToWorkSqr(candidate, workPos) > WORK_DISTANCE_SQR) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos candidate) -> candidate.distSqr(current))
                .thenComparingDouble(candidate -> this.distanceToWorkSqr(candidate, workPos)));
        return candidates.isEmpty() ? workPos.immutable() : candidates.get(0);
    }

    private boolean isStandingOnClearTarget(BlockPos pos) {
        return this.target != null
                && this.target.phase() == TerraformPhase.CLEAR
                && pos != null
                && this.playerNpc.blockPosition().below().equals(pos);
    }

    private Optional<BlockPos> findScaffoldStartTarget(ServerLevel serverLevel, BlockPos workPos) {
        if (this.target == null
                || this.target.phase() != TerraformPhase.CLEAR
                || !this.needsScaffoldForVerticalReach(workPos)
                || !hasScaffoldBlock(this.playerNpc)) {
            return Optional.empty();
        }

        BlockPos current = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int minY = Math.max(serverLevel.getMinBuildHeight(), current.getY() - 2);
        int maxY = Math.min(serverLevel.getMaxBuildHeight() - 2, current.getY() + 2);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos candidate = new BlockPos(workPos.getX() + dx, y, workPos.getZ() + dz);
                    if (!PathNavigationAi.canStandAt(serverLevel, candidate)
                            || this.horizontalDistanceToWorkSqr(candidate, workPos) > SCAFFOLD_HORIZONTAL_REACHED_SQR
                            || candidate.equals(current) && !this.canCenterOnScaffoldBase(serverLevel, candidate)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos candidate) -> candidate.distSqr(current))
                .thenComparingDouble(candidate -> this.horizontalDistanceToWorkSqr(candidate, workPos))
                .thenComparingInt(candidate -> Math.abs(candidate.getY() - current.getY())));
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    private double horizontalDistanceToWorkSqr(BlockPos standPos, BlockPos workPos) {
        double dx = standPos.getX() + 0.5D - (workPos.getX() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (workPos.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private boolean centerOnScaffoldBase(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canCenterOnScaffoldBase(serverLevel, pos)) {
            return false;
        }

        double targetX = pos.getX() + 0.5D;
        double targetZ = pos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        if (Math.abs(dx) <= SCAFFOLD_CENTER_EPSILON && Math.abs(dz) <= SCAFFOLD_CENTER_EPSILON) {
            return true;
        }

        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setPos(targetX, this.playerNpc.getY(), targetZ);
        this.playerNpc.setDeltaMovement(0.0D, motion.y, 0.0D);
        return true;
    }

    private boolean canCenterOnScaffoldBase(ServerLevel serverLevel, BlockPos pos) {
        double targetX = pos.getX() + 0.5D;
        double targetZ = pos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        if (Math.abs(dx) <= SCAFFOLD_CENTER_EPSILON && Math.abs(dz) <= SCAFFOLD_CENTER_EPSILON) {
            return true;
        }

        AABB centeredBox = this.playerNpc.getBoundingBox().move(dx, 0.0D, dz);
        return PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, centeredBox);
    }

    private double distanceToWorkSqr(BlockPos standPos, BlockPos workPos) {
        double dx = standPos.getX() + 0.5D - (workPos.getX() + 0.5D);
        double dy = standPos.getY() + 0.5D - (workPos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (workPos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private void updateTaskDetail() {
        this.updateTaskDetail(null);
    }

    private void updateTaskDetail(String override) {
        if (override != null && !override.isBlank()) {
            this.playerNpc.setCurrentAiDetail(override);
            return;
        }
        if (this.clearBlockAi.isRunning()) {
            String detail = this.clearBlockAi.detail();
            if (!detail.isBlank()) {
                this.playerNpc.setCurrentAiDetail(detail);
            }
            return;
        }
        if (this.scaffoldPlacePos != null) {
            this.playerNpc.setCurrentAiDetail("pillar up @ "
                    + this.scaffoldPlacePos.getX() + " "
                    + this.scaffoldPlacePos.getY() + " "
                    + this.scaffoldPlacePos.getZ());
            return;
        }
        if (!this.temporaryScaffold.isEmpty() && this.target == null) {
            BlockPos pos = this.temporaryScaffold.get(this.temporaryScaffold.size() - 1);
            this.playerNpc.setCurrentAiDetail("pillar down @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            return;
        }
        if (this.target == null) {
            this.playerNpc.setCurrentAiDetail(this.continuingTargetSearch
                    ? "checking build site " + (this.targetSearchClearComplete
                            ? "supports " + this.targetSearchSupportColumnIndex
                            : "blocks " + this.targetSearchClearBlockIndex)
                    : "");
            return;
        }

        String action = switch (this.target.phase()) {
            case CLEAR -> "clear";
            case CLEAR_WATER -> "drain";
            case FILL_SUPPORT -> "fill support";
        };
        BlockPos pos = this.target.pos();
        this.playerNpc.setCurrentAiDetail(action + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private boolean isTargetStillValid(ServerLevel serverLevel, TerraformTarget target) {
        BlockState existing = serverLevel.getBlockState(target.pos());
        return switch (target.phase()) {
            case CLEAR_WATER -> !existing.getFluidState().isEmpty();
            case CLEAR -> target.targetState().isAir()
                    ? !existing.isAir() && this.canClearBlueprintTarget(serverLevel, target.pos(), existing)
                    : !PlayerNpcBuildMaterialUtil.matches(existing, target.targetState())
                    && !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, target.pos())
                    && this.canClearBlueprintTarget(serverLevel, target.pos(), existing);
            case FILL_SUPPORT -> !isGoodFloorBlock(serverLevel, target.pos(), existing)
                    && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, target.pos())
                    && hasFillBlock(this.playerNpc);
        };
    }

    private TargetSearchResult findNextTarget(ServerLevel serverLevel, boolean requireFillItem) {
        this.failedDirectClearTargets.entrySet().removeIf(entry -> entry.getValue() <= serverLevel.getGameTime());
        Optional<BuildContext> context = findBuildContext(this.playerNpc);
        if (context.isEmpty()) {
            this.resetTargetSearch();
            return TargetSearchResult.complete(null);
        }

        BuildContext buildContext = context.get();
        this.ensureTargetSearchContext(buildContext);
        boolean worker = PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc);
        long deadline = System.nanoTime() + WORKER_TARGET_SEARCH_BUDGET_NANOS;
        if (!this.targetSearchClearComplete) {
            // Layout data is already an immutable, deterministic loader-order list. Do not lazily
            // sort the entire blueprint inside the first canUse call for this layout; that full
            // stream sort bypasses the retained slice. Holders may cheaply skip already-clear
            // entries in a larger slice, but expensive terrain checks stop it after one millisecond.
            List<PlayerNpcBuildLayout.RelativeBlock> blocks = buildContext.layout().blocks();
            int endIndex = Math.min(
                    blocks.size(),
                    this.targetSearchClearBlockIndex + (worker
                            ? MAX_WORKER_CLEAR_BLOCKS_PER_SLICE : MAX_TERRAFORM_CLEAR_BLOCKS_PER_SLICE)
            );
            for (int index = this.targetSearchClearBlockIndex; index < endIndex; index++) {
                if (index > this.targetSearchClearBlockIndex && worker && System.nanoTime() >= deadline) {
                    this.targetSearchClearBlockIndex = index;
                    return TargetSearchResult.pending();
                }
                PlayerNpcBuildLayout.RelativeBlock block = blocks.get(index);
                BlockPos worldPos = block.toWorld(buildContext.origin());
                if (this.failedDirectClearTargets.containsKey(worldPos)) {
                    continue;
                }
                Optional<TerraformTarget> clearTarget = clearTargetForBlock(
                        serverLevel,
                        this.playerNpc,
                        buildContext.origin(),
                        block
                );
                if (clearTarget.isPresent()) {
                    TerraformTarget selected = clearTarget.get();
                    // Resume after this blueprint entry once it has been cleared. Restarting at
                    // zero after every target made later terraform blocks take many admitted
                    // four-entry slices even though the active episode already owned a cursor.
                    this.targetSearchClearBlockIndex = index + 1;
                    return TargetSearchResult.complete(selected);
                }
            }
            this.targetSearchClearBlockIndex = endIndex;
            if (endIndex < blocks.size()) {
                return TargetSearchResult.pending();
            }
            this.targetSearchClearComplete = true;
            // Start the support phase in a later admitted slice. Even a small blueprint must not
            // combine its complete clear pass with another footprint/depth pass in one tick.
            return TargetSearchResult.pending();
        }

        if (requireFillItem && !hasFillBlock(this.playerNpc)) {
            this.resetTargetSearch();
            return TargetSearchResult.complete(null);
        }

        PlayerNpcBuildLayout layout = buildContext.layout();
        int totalColumns = layout.width() * layout.depth();
        int endColumn = Math.min(
                totalColumns,
                this.targetSearchSupportColumnIndex + (worker
                        ? MAX_WORKER_SUPPORT_COLUMNS_PER_SLICE : MAX_TERRAFORM_SUPPORT_COLUMNS_PER_SLICE)
        );
        for (int columnIndex = this.targetSearchSupportColumnIndex; columnIndex < endColumn; columnIndex++) {
            if (columnIndex > this.targetSearchSupportColumnIndex && worker && System.nanoTime() >= deadline) {
                this.targetSearchSupportColumnIndex = columnIndex;
                return TargetSearchResult.pending();
            }
            int x = columnIndex % layout.width();
            int z = columnIndex / layout.width();
            if (!layout.isInFootprint(x, z)) {
                continue;
            }

            BlockPos topSupport = buildContext.origin().offset(x, -1, z);
            if (isGoodFloorBlock(serverLevel, topSupport, serverLevel.getBlockState(topSupport))) {
                continue;
            }

            for (int dy = -SUPPORT_SCAN_DEPTH; dy <= -1; dy++) {
                BlockPos pos = buildContext.origin().offset(x, dy, z);
                BlockState state = serverLevel.getBlockState(pos);
                if (this.skippedSupportTargets.contains(pos)) {
                    continue;
                }
                if (!isGoodFloorBlock(serverLevel, pos, state)
                        && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
                    TerraformTarget selected = new TerraformTarget(
                            pos.immutable(),
                            TerraformPhase.FILL_SUPPORT,
                            Blocks.DIRT.defaultBlockState()
                    );
                    // Retain this column so a multi-block support hole is filled bottom-up on the
                    // next bounded pass, without rescanning all earlier footprint columns.
                    this.targetSearchSupportColumnIndex = columnIndex;
                    return TargetSearchResult.complete(selected);
                }
            }
        }
        this.targetSearchSupportColumnIndex = endColumn;
        if (endColumn < totalColumns) {
            return TargetSearchResult.pending();
        }
        this.resetTargetSearch();
        return TargetSearchResult.complete(null);
    }

    private void ensureTargetSearchContext(BuildContext context) {
        if (context.layout().id().equals(this.targetSearchLayoutId)
                && context.origin().equals(this.targetSearchOrigin)) {
            return;
        }
        this.resetTargetSearch();
        this.targetSearchLayoutId = context.layout().id();
        this.targetSearchOrigin = context.origin().immutable();
    }

    private void resetTargetSearch() {
        this.targetSearchLayoutId = "";
        this.targetSearchOrigin = null;
        this.targetSearchClearBlockIndex = 0;
        this.targetSearchSupportColumnIndex = 0;
        this.targetSearchClearComplete = false;
    }

    private static boolean canRunTargetNow(ServerLevel serverLevel, PlayerNpcEntity playerNpc, TerraformTarget target) {
        if (target.phase() == TerraformPhase.FILL_SUPPORT) {
            // A support needing vertical recovery is still owned work. Keep it through the
            // cursor handoff so start/tick can request recovery instead of rescanning the site.
            return true;
        }
        if (target.phase() != TerraformPhase.CLEAR) {
            return true;
        }

        BlockState state = serverLevel.getBlockState(target.pos());
        return !state.is(BlockTags.MINEABLE_WITH_SHOVEL) || hasTool(playerNpc, ShovelItem.class);
    }

    private static ActionablePrepCache matchingPrepCache(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        ActionablePrepCache cached = ACTIONABLE_PREP_CACHE.get(playerNpc);
        return cached != null && cached.matches(playerNpc, serverLevel, home.orElse(null), layoutId)
                ? cached
                : null;
    }

    private static void publishPrepCache(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            TerraformTarget target
    ) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        boolean hasPrep = target != null;
        boolean needsShovel = hasPrep
                && target.phase() == TerraformPhase.CLEAR
                && serverLevel.getBlockState(target.pos()).is(BlockTags.MINEABLE_WITH_SHOVEL)
                && !hasTool(playerNpc, ShovelItem.class);
        ACTIONABLE_PREP_CACHE.put(playerNpc, new ActionablePrepCache(
                playerNpc.tickCount,
                serverLevel.dimension().location(),
                home.map(PlayerNpcHomeUtil.HomeArea::origin).orElse(null),
                layoutId,
                hasPrep,
                hasPrep && canRunTargetNow(serverLevel, playerNpc, target),
                needsShovel,
                playerNpc.shouldPrioritizeLogGathering(),
                playerNpc.shouldPrioritizeCobblestoneGathering(),
                hasFillBlock(playerNpc)
        ));
    }

    private static void publishPendingPrepCache(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        ACTIONABLE_PREP_CACHE.put(playerNpc, new ActionablePrepCache(
                playerNpc.tickCount,
                serverLevel.dimension().location(),
                home.map(PlayerNpcHomeUtil.HomeArea::origin).orElse(null),
                layoutId,
                true,
                true,
                false,
                playerNpc.shouldPrioritizeLogGathering(),
                playerNpc.shouldPrioritizeCobblestoneGathering(),
                hasFillBlock(playerNpc)
        ));
    }

    private boolean deferSupportFillForVerticalEscape(ServerLevel serverLevel, BlockPos supportPos) {
        if (!shouldDeferSupportFillForVerticalEscape(this.playerNpc, supportPos)) {
            return false;
        }

        // This method records a persistent handoff and requests a world-changing pillar action.
        // Selector probes are read-only; the admitted Terraform worker calls it from start().
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        int escapeY = Math.max(Math.max(homeArea.origin().getY(), supportPos.getY() + 1), feet.getY() + 3);
        int maxPillarBlocks = Math.max(1, escapeY - feet.getY() + SUPPORT_FILL_ESCAPE_EXTRA_BLOCKS);
        BlockPos escapeTarget = new BlockPos(feet.getX(), escapeY, feet.getZ());
        this.beginSupportFillEscapeHandoff(serverLevel, supportPos);
        this.playerNpc.requestTerraformSupportUpwardEscapeTo(
                escapeTarget,
                SUPPORT_FILL_ESCAPE_REQUEST_TICKS,
                maxPillarBlocks
        );
        this.playerNpc.setCurrentAiDetail("pillaring before fill support @ "
                + supportPos.getX() + " " + supportPos.getY() + " " + supportPos.getZ());
        return true;
    }

    public boolean hasPendingSupportFillEscapeHandoff(ServerLevel serverLevel) {
        return this.getValidSupportFillEscapeHandoff(serverLevel) != null;
    }

    private TerraformTarget getValidSupportFillEscapeHandoff(ServerLevel serverLevel) {
        if (this.supportFillEscapeHandoffPos == null) {
            return null;
        }
        if (serverLevel.getGameTime() >= this.supportFillEscapeHandoffUntilTick) {
            this.clearSupportFillEscapeHandoff(true);
            return null;
        }

        TerraformTarget handoff = new TerraformTarget(
                this.supportFillEscapeHandoffPos,
                TerraformPhase.FILL_SUPPORT,
                Blocks.DIRT.defaultBlockState()
        );
        if (!hasActiveBuildingJob(this.playerNpc, serverLevel)
                || !this.isTargetStillValid(serverLevel, handoff)) {
            this.clearSupportFillEscapeHandoff(false);
            return null;
        }
        return handoff;
    }

    private void beginSupportFillEscapeHandoff(ServerLevel serverLevel, BlockPos supportPos) {
        if (supportPos.equals(this.supportFillEscapeHandoffPos)
                && serverLevel.getGameTime() < this.supportFillEscapeHandoffUntilTick) {
            return;
        }
        this.supportFillEscapeHandoffPos = supportPos.immutable();
        this.supportFillEscapeHandoffUntilTick = serverLevel.getGameTime() + SUPPORT_FILL_ESCAPE_HANDOFF_TICKS;
    }

    private boolean isSupportFillEscapeHandoffTarget(TerraformTarget target) {
        return target != null
                && target.phase() == TerraformPhase.FILL_SUPPORT
                && target.pos().equals(this.supportFillEscapeHandoffPos);
    }

    private void clearSupportFillEscapeHandoff(boolean retryCooldown) {
        this.supportFillEscapeHandoffPos = null;
        this.supportFillEscapeHandoffUntilTick = 0L;
        if (retryCooldown) {
            this.supportFillRetryCooldownTicks = Math.max(
                    this.supportFillRetryCooldownTicks,
                    SUPPORT_FILL_RETRY_COOLDOWN_TICKS
            );
        }
    }

    private static boolean shouldDeferSupportFillForVerticalEscape(PlayerNpcEntity playerNpc, BlockPos supportPos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty() || supportPos == null) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos feet = playerNpc.blockPosition();
        return feet.getY() < homeArea.origin().getY()
                && supportPos.getY() >= feet.getY()
                // Recover when trapped below the house itself. An outside builder can approach
                // and fill a foundation from its side; pillaring at a distant current column
                // creates no route toward that support and leads straight into column descent.
                && PlayerNpcHomeUtil.isInsideFootprint(homeArea, feet);
    }

    private static Optional<BuildContext> findBuildContext(PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return Optional.empty();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != home.get().width()
                || layout.get().depth() != home.get().depth()) {
            return Optional.empty();
        }

        return Optional.of(new BuildContext(layout.get(), home.get().origin()));
    }

    private static Optional<TerraformTarget> clearTargetForBlock(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos origin, PlayerNpcBuildLayout.RelativeBlock block) {
        BlockPos pos = block.toWorld(origin);
        BlockState targetState = block.state();
        BlockState existing = serverLevel.getBlockState(pos);
        if (!existing.getFluidState().isEmpty() && !existing.getFluidState().equals(targetState.getFluidState())) {
            return Optional.of(new TerraformTarget(pos.immutable(), TerraformPhase.CLEAR_WATER, targetState));
        }
        if (targetState.isAir()) {
            return !existing.isAir() && canClearBlueprintTarget(serverLevel, playerNpc, pos, existing)
                    ? Optional.of(new TerraformTarget(pos.immutable(), TerraformPhase.CLEAR, targetState))
                    : Optional.empty();
        }
        if (PlayerNpcBuildMaterialUtil.matches(existing, targetState)
                || PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                || !canClearBlueprintTarget(serverLevel, playerNpc, pos, existing)) {
            return Optional.empty();
        }
        return Optional.of(new TerraformTarget(pos.immutable(), TerraformPhase.CLEAR, targetState));
    }

    private boolean canClearForBuild(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return canClearForBuild(serverLevel, this.playerNpc, pos, state);
    }

    private boolean canClearBlueprintTarget(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return canClearBlueprintTarget(serverLevel, this.playerNpc, pos, state);
    }

    private static boolean canClearBlueprintTarget(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos pos, BlockState state) {
        return PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos)
                && canClearForBuild(serverLevel, playerNpc, pos, state, true);
    }

    private static boolean canClearForBuild(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos pos, BlockState state) {
        return canClearForBuild(serverLevel, playerNpc, pos, state, false);
    }

    private static boolean canClearForBuild(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos pos,
            BlockState state,
            boolean allowMisplacedFurnace
    ) {
        boolean bedObstruction = PlayerNpcBedUtil.isSafeBuildObstruction(serverLevel, playerNpc, pos, state);
        boolean safeUtilityObstruction = allowMisplacedFurnace && state.is(Blocks.FURNACE);
        return !state.isAir()
                && !FarmAi.isOwnedFarmDestructionProtected(playerNpc, pos)
                && !playerNpc.isTemporaryPillarSupport(pos)
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && !isProtectedTemporaryCraftingTable(playerNpc, serverLevel, pos)
                && (serverLevel.getBlockEntity(pos) == null || bedObstruction || safeUtilityObstruction)
                && (bedObstruction
                || safeUtilityObstruction
                || state.canBeReplaced()
                || state.getCollisionShape(serverLevel, pos).isEmpty()
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(BlockTags.MINEABLE_WITH_AXE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || state.is(BlockTags.LEAVES));
    }

    private static boolean isProtectedTemporaryCraftingTable(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos) {
        return CraftBasicGearGoal.isTemporaryCraftingTable(playerNpc, serverLevel, pos)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos);
    }

    private static boolean isGoodFloorBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && (state.isFaceSturdy(serverLevel, pos, Direction.UP) || state.isSolidRender(serverLevel, pos))
                && !state.is(Blocks.BEDROCK);
    }

    private static boolean hasFillBlock(PlayerNpcEntity playerNpc) {
        return isFillStack(playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(playerNpc, TerraformBuildSiteGoal::isFillStack);
    }

    private static boolean hasScaffoldBlock(PlayerNpcEntity playerNpc) {
        return isScaffoldStack(playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(playerNpc, TerraformBuildSiteGoal::isScaffoldStack)
                || PlayerNpcCraftingUtil.countLogs(playerNpc.getInventory()) > 0;
    }

    private static boolean hasTool(PlayerNpcEntity playerNpc, Class<?> toolClass) {
        return toolClass.isInstance(playerNpc.getMainHandItem().getItem())
                || toolClass.isInstance(playerNpc.getOffhandItem().getItem())
                || InventoryUtils.hasItem(playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private static boolean isFillStack(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem)
                || stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.PLANKS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem) {
            return false;
        }
        for (net.minecraft.world.item.Item item : FILL_ITEMS) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isScaffoldStack(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem
                || stack.is(Items.SAND)
                || stack.is(Items.RED_SAND)
                || stack.is(Items.GRAVEL)) {
            return false;
        }
        if (!isFillStack(stack) && !stack.is(ItemTags.PLANKS)) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.getFluidState().isEmpty()
                && !state.canBeReplaced()
                && state.canOcclude();
    }

    private Optional<ItemStack> useFillBlockInMainHand() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        // Once a temporary fill item has entered its visible windup, keep that exact stack selected
        // until placement, final failure, or interruption restores the original hand. A newly
        // acquired higher-priority fill item must not make the rendered hand change mid-action.
        if (this.usingTemporaryMainHand && isFillStack(mainHand)) {
            return Optional.of(mainHand);
        }
        Optional<net.minecraft.world.item.Item> preferredInventoryFill = this.findPreferredInventoryFillItem();
        if (preferredInventoryFill.isPresent()
                && (!isFillStack(mainHand)
                || fillPriority(preferredInventoryFill.get()) < fillPriority(mainHand))) {
            ItemStack fill = this.playerNpc.consumeInventoryItem(preferredInventoryFill.get(), 1)
                    .orElse(ItemStack.EMPTY);
            if (!fill.isEmpty()) {
                this.setTemporaryMainHand(fill);
                return Optional.of(fill);
            }
        }

        if (isFillStack(mainHand)) {
            return Optional.of(mainHand);
        }

        Optional<ItemStack> fill = this.consumePreferredFillBlock();
        fill.ifPresent(this::setTemporaryMainHand);
        return fill;
    }

    private Optional<net.minecraft.world.item.Item> findPreferredInventoryFillItem() {
        for (net.minecraft.world.item.Item item : FILL_ITEMS) {
            if (this.playerNpc.hasInventoryItem(item)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private Optional<ItemStack> consumePreferredFillBlock() {
        for (net.minecraft.world.item.Item item : FILL_ITEMS) {
            ItemStack fill = this.playerNpc.consumeInventoryItem(item, 1)
                    .orElse(ItemStack.EMPTY);
            if (!fill.isEmpty()) {
                return Optional.of(fill);
            }
        }
        return Optional.empty();
    }

    private static int fillPriority(ItemStack stack) {
        for (int i = 0; i < FILL_ITEMS.size(); i++) {
            if (stack.is(FILL_ITEMS.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int fillPriority(net.minecraft.world.item.Item item) {
        int index = FILL_ITEMS.indexOf(item);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    private Optional<ItemStack> useScaffoldBlockInMainHand() {
        if (isScaffoldStack(this.playerNpc.getMainHandItem())) {
            return Optional.of(this.playerNpc.getMainHandItem());
        }

        if (!InventoryUtils.hasItem(this.playerNpc, TerraformBuildSiteGoal::isScaffoldStack)) {
            PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), 0);
        }

        ItemStack block = this.playerNpc.consumeInventoryItem(TerraformBuildSiteGoal::isScaffoldStack, 1)
                .orElse(ItemStack.EMPTY);
        if (block.isEmpty()) {
            return Optional.empty();
        }

        this.setTemporaryMainHand(block);
        return Optional.of(block);
    }

    private int countScaffoldBlocks() {
        int count = isScaffoldStack(this.playerNpc.getMainHandItem()) ? this.playerNpc.getMainHandItem().getCount() : 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (isScaffoldStack(stack)) {
                count += stack.getCount();
            }
        }
        return count + PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) * 4;
    }

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .noneMatch(box -> box.move(pos).intersects(this.playerNpc.getBoundingBox().inflate(0.05D)));
    }

    private boolean canPlaceSupportWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!this.placingBlockAi.findBlockingPlacementEntities(serverLevel, pos, state).isEmpty()) {
            return false;
        }

        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        AABB currentBox = this.playerNpc.getBoundingBox();
        if (boxes.stream().noneMatch(box -> box.intersects(currentBox))) {
            return true;
        }

        double blockTopY = pos.getY() + 1.0D;
        if (blockTopY <= currentBox.minY + 0.02D) {
            return true;
        }

        double snapUp = blockTopY - currentBox.minY;
        if (snapUp < -0.05D || snapUp > 0.35D) {
            return false;
        }

        AABB snappedBox = currentBox.move(0.0D, snapUp + 0.01D, 0.0D);
        return boxes.stream().noneMatch(box -> box.intersects(snappedBox.inflate(0.001D)))
                && PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, snappedBox);
    }

    private boolean tryJumpForSupportClearance(ServerLevel serverLevel, BlockPos supportPos) {
        if (!this.playerNpc.onGround() || this.supportClearanceJumpCooldownTicks > 0) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (supportPos.getX() != feet.getX()
                || supportPos.getZ() != feet.getZ()
                || supportPos.getY() != feet.getY()
                || !this.hasOpenJumpSpace(serverLevel, feet)) {
            return false;
        }

        this.playerNpc.getJumpControl().jump();
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setDeltaMovement(motion.x, Math.max(motion.y, 0.42D), motion.z);
        this.playerNpc.hasImpulse = true;
        this.supportClearanceJumpCooldownTicks = SUPPORT_CLEARANCE_JUMP_COOLDOWN_TICKS;
        return true;
    }

    private boolean hasOpenJumpSpace(ServerLevel serverLevel, BlockPos feet) {
        return serverLevel.getBlockState(feet.above()).getCollisionShape(serverLevel, feet.above()).isEmpty()
                && serverLevel.getBlockState(feet.above(2)).getCollisionShape(serverLevel, feet.above(2)).isEmpty()
                && serverLevel.getFluidState(feet.above()).isEmpty()
                && serverLevel.getFluidState(feet.above(2)).isEmpty();
    }

    private BlockPos findSupportClearanceStand(ServerLevel serverLevel, BlockPos supportPos, BlockState supportState) {
        BlockPos current = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = supportPos.offset(dx, dy, dz);
                        if (!candidate.equals(current)
                                && this.canUseSupportClearanceStand(serverLevel, candidate, supportPos, supportState)) {
                            candidates.add(candidate.immutable());
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos candidate) -> candidate.distSqr(current))
                .thenComparingDouble(candidate -> this.horizontalDistanceToWorkSqr(candidate, supportPos)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean canUseSupportClearanceStand(ServerLevel serverLevel, BlockPos standPos, BlockPos supportPos, BlockState supportState) {
        if (!PathNavigationAi.canStandAt(serverLevel, standPos)) {
            return false;
        }

        double width = this.playerNpc.getBbWidth();
        double height = this.playerNpc.getBbHeight();
        double x = standPos.getX() + 0.5D;
        double z = standPos.getZ() + 0.5D;
        AABB standBox = new AABB(
                x - width / 2.0D,
                standPos.getY(),
                z - width / 2.0D,
                x + width / 2.0D,
                standPos.getY() + height,
                z + width / 2.0D
        ).inflate(0.05D);

        return PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, standBox)
                && this.placingBlockAi.placementCollisionBoxes(serverLevel, supportPos, supportState)
                .stream()
                .noneMatch(box -> box.intersects(standBox));
    }

    private boolean hasScaffoldPlacementClearance() {
        if (this.scaffoldPlacePos == null) {
            return false;
        }

        double clearedY = this.playerNpc.getBoundingBox().minY - this.scaffoldPlacePos.getY();
        return clearedY >= SCAFFOLD_PLACE_CLEARANCE_Y
                || this.scaffoldPlaceWaitTicks >= SCAFFOLD_FORCE_PLACE_TICKS
                && clearedY >= SCAFFOLD_FALLBACK_PLACE_CLEARANCE_Y
                && this.playerNpc.getDeltaMovement().y <= 0.05D;
    }

    private boolean canPlaceScaffoldWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.hasOtherEntityInBlock(serverLevel, pos)) {
            return false;
        }

        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        if (boxes.stream().noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)))) {
            return true;
        }

        double snapUp = pos.getY() + 1.0D - this.playerNpc.getBoundingBox().minY;
        if (snapUp < -0.05D || snapUp > 0.35D) {
            return false;
        }

        AABB snappedBox = this.playerNpc.getBoundingBox().move(0.0D, snapUp + 0.01D, 0.0D);
        return boxes.stream().noneMatch(box -> box.intersects(snappedBox.inflate(0.001D)))
                && PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, snappedBox);
    }

    private void snapAboveScaffoldIfNeeded(BlockPos pos) {
        double topY = pos.getY() + 1.0D;
        if (this.playerNpc.getBoundingBox().minY >= topY) {
            return;
        }

        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setPos(this.playerNpc.getX(), topY, this.playerNpc.getZ());
        this.playerNpc.setDeltaMovement(motion.x, Math.max(0.0D, motion.y), motion.z);
        this.playerNpc.fallDistance = 0.0F;
    }

    private boolean hasOtherEntityInBlock(ServerLevel serverLevel, BlockPos pos) {
        return !PlayerNpcCollisionUtil.blockingEntitiesInBox(serverLevel, this.playerNpc, new AABB(pos).inflate(0.05D)).isEmpty();
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
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

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_MINE_TICKS;
        }

        ItemStack heldStack = this.playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_MINE_TICKS;
        }

        return Math.max(1, Math.min(MAX_MINE_TICKS, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private void equipToolFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            if (!this.equipTool(ShovelItem.class)) {
                this.equipEmptyHand();
            }
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(BlockTags.LOGS)) {
            if (!this.equipTool(AxeItem.class)) {
                this.equipEmptyHand();
            }
        } else if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            if (!this.equipTool(PickaxeItem.class)) {
                this.equipEmptyHand();
            }
        } else if (state.is(BlockTags.LEAVES)) {
            this.equipEmptyHand();
        }
    }

    private boolean equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        if (this.restorePreviousMainHandForTool(toolClass)) {
            return true;
        }

        ItemStack tool = this.playerNpc.consumeInventoryItem(stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return false;
        }

        this.setTemporaryMainHand(tool);
        return true;
    }

    private void equipEmptyHand() {
        if (!this.playerNpc.getMainHandItem().isEmpty()) {
            this.setTemporaryMainHand(ItemStack.EMPTY);
        }
    }

    private void setTemporaryMainHand(ItemStack stack) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryMainHand) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryMainHand = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    private boolean restorePreviousMainHandForTool(Class<?> toolClass) {
        if (!this.usingTemporaryMainHand || !toolClass.isInstance(this.previousMainHand.getItem())) {
            return false;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        return true;
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryMainHand) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
    }

    private record BuildContext(PlayerNpcBuildLayout layout, BlockPos origin) {
    }

    private record TerraformTarget(BlockPos pos, TerraformPhase phase, BlockState targetState) {
    }

    private record TargetSearchResult(TerraformTarget target, boolean complete) {
        private static TargetSearchResult pending() {
            return new TargetSearchResult(null, false);
        }

        private static TargetSearchResult complete(TerraformTarget target) {
            return new TargetSearchResult(target, true);
        }
    }

    private record ActionablePrepCache(
            int tick,
            ResourceLocation dimension,
            BlockPos homeOrigin,
            String layoutId,
            boolean hasPrep,
            boolean actionable,
            boolean needsShovel,
            boolean needsLogSupply,
            boolean needsStoneSupply,
            boolean hasFillMaterial
    ) {
        private boolean matches(
                PlayerNpcEntity playerNpc,
                ServerLevel serverLevel,
                PlayerNpcHomeUtil.HomeArea home,
                String currentLayoutId
        ) {
            int age = playerNpc.tickCount - this.tick;
            int maxAge = this.hasPrep ? ACTIONABLE_PREP_CACHE_TICKS : NO_PREP_CACHE_TICKS;
            return age >= 0
                    && age <= maxAge
                    + Math.floorMod(playerNpc.getUUID().hashCode(), 10)
                    && this.dimension.equals(serverLevel.dimension().location())
                    && java.util.Objects.equals(this.homeOrigin, home == null ? null : home.origin())
                    && this.layoutId.equals(currentLayoutId)
                    && this.needsLogSupply == playerNpc.shouldPrioritizeLogGathering()
                    && this.needsStoneSupply == playerNpc.shouldPrioritizeCobblestoneGathering()
                    && this.hasFillMaterial == hasFillBlock(playerNpc);
        }
    }

    private enum TerraformPhase {
        CLEAR,
        CLEAR_WATER,
        FILL_SUPPORT
    }
}
