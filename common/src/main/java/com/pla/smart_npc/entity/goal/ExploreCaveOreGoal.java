package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcAdaptiveSearchScope;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;

public class ExploreCaveOreGoal extends Goal {
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int SEARCH_DOWN = 15;
    private static final int SEARCH_UP = 15;
    // The cursor preserves complete eventual coverage; keep each admitted activation below the
    // repeatedly measured 31-51 ms half-thousand-block scan.
    private static final int MAX_ORE_SCAN_BLOCKS_PER_ATTEMPT = 32;
    private static final int MAX_ORE_TARGET_PATH_CHECKS = 6;
    private static final int MAX_ORE_SELECTION_NAVIGATION_PATHS = 1;
    private static final int MAX_CLUSTER_TARGET_CHECKS = 12;
    private static final int NEARBY_ORE_CACHE_TICKS = 20;
    private static final double NEARBY_ORE_CACHE_MOVE_SQR = 2.0D * 2.0D;
    private static final int ORE_SEARCH_INTERVAL_TICKS = 20 * 5;
    private static final double BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double PATH_OBSTRUCTION_BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final int COOLDOWN_TICKS = 20 * 12;
    private static final int FAILED_RETRY_COOLDOWN_TICKS = ORE_SEARCH_INTERVAL_TICKS;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_MINE_TICKS = 20 * 12;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 2;
    private static final int MAX_ORE_CLEAR_TARGET_TICKS = 20 * 5;
    private static final int MAX_CLUSTER_SCAN_BLOCKS = 48;
    private static final int MAX_SAFE_DROP_BLOCKS = 4;
    private static final int ORE_WALK_STUCK_TICKS = 20 * 2;
    private static final int LOCAL_ROUTE_HORIZONTAL_RADIUS = 8;
    private static final int LOCAL_ROUTE_VERTICAL_DOWN = 5;
    private static final int LOCAL_ROUTE_VERTICAL_UP = 6;
    private static final int MAX_LOCAL_ROUTE_PATH_CHECKS = 2;
    private static final float ACTIVE_ROUTE_PATH_NODE_MULTIPLIER = 0.05F;
    private static final float OVERLOADED_ROUTE_PATH_NODE_MULTIPLIER = 0.01F;
    private static final int CLEAR_OBSTRUCTION_TICKS = 28;
    private static final int HIGH_ORE_MIN_VERTICAL_GAP = 3;
    private static final int HIGH_ORE_MAX_HORIZONTAL_RADIUS = 4;
    private static final int MAX_HIGH_ORE_CLEARS_WITHOUT_RISE = 3;
    private static final int HIGH_ORE_PILLAR_EXTRA_BLOCKS = 2;
    private static final int HIGH_ORE_UPWARD_REQUEST_TICKS = 20 * 20;
    private static final double STAND_EYE_HEIGHT = 1.5D;
    private static final int SUCCESS_COOLDOWN_TICKS = 20 * 2;
    private static final int TORCH_PLACE_INTERVAL_TICKS = 20 * 4;
    private static final float TORCH_PLACE_CHANCE = 0.35F;
    private static final int TORCH_NEARBY_RADIUS = 6;
    private static final int TORCH_LOW_LIGHT_LEVEL = 7;
    private static final int SURFACE_ESCAPE_SCAN_UP = 96;
    private static final int UPWARD_ESCAPE_REQUEST_TICKS = 20 * 8;
    private static final int IDLE_BLOCK_TRACE_TICKS = 20 * 20;
    private static final int GEAR_PRIORITY_RECHECK_TICKS = 20;
    private static final int CONTINUE_ELIGIBILITY_RECHECK_TICKS = 20;
    private static final Map<Integer, List<BlockPos>> ORE_SEARCH_OFFSETS = Map.of(
            PlayerNpcAdaptiveSearchScope.ORE_LOCAL_RADIUS,
            createOreSearchOffsets(PlayerNpcAdaptiveSearchScope.ORE_LOCAL_RADIUS),
            PlayerNpcAdaptiveSearchScope.ORE_NORMAL_RADIUS,
            createOreSearchOffsets(PlayerNpcAdaptiveSearchScope.ORE_NORMAL_RADIUS),
            PlayerNpcAdaptiveSearchScope.ORE_HEALTHY_RADIUS,
            createOreSearchOffsets(PlayerNpcAdaptiveSearchScope.ORE_HEALTHY_RADIUS)
    );
    private static final Map<PlayerNpcEntity, NearbyOreProbe> NEARBY_ORE_PROBES = new WeakHashMap<>();

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PlacingBlockAi placingBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final double speed;
    private final Set<BlockPos> clusterOres = new HashSet<>();
    private final Set<BlockPos> minedClusterOres = new HashSet<>();
    private final Set<BlockPos> skippedOreTargets = new HashSet<>();
    private final Set<BlockPos> skippedPathObstructions = new HashSet<>();
    private BlockPos targetPos;
    private BlockPos standPos;
    private BlockPos pathObstructionPos;
    private BlockPos activeClearTarget;
    private BlockPos lastOreWalkPos;
    private BlockPos oreSearchCenter;
    private BlockPos resumeOrePos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private OreFamily clusterFamily = OreFamily.NONE;
    private int mineTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int activeClearTargetTicks;
    private int oreWalkStillTicks;
    private int highOreClearsWithoutRise;
    private int highestOreApproachY;
    private int torchPlaceTicks;
    private int nextOreSearchTick;
    private int oreSearchCursor;
    private int oreSearchRadius;
    private int oreSelectionNavigationPathsRemaining = -1;
    private boolean lastOreSearchCompletedPass;
    private int nextGearPriorityCheckTick;
    private int nextContinueEligibilityCheckTick;
    private boolean usingTemporaryPickaxe;
    private boolean minedAnyOre;
    private boolean targetRequiresUpwardApproach;
    private boolean requestedUpwardOreApproach;
    private boolean resumeInterruptedOreWork;
    private boolean gearCraftingHasPriority;
    private boolean continueEligibilityAllowed = true;

    public ExploreCaveOreGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasNearbyOreTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return false;
        }
        BlockPos center = playerNpc.blockPosition();
        NearbyOreProbe probe = NEARBY_ORE_PROBES.computeIfAbsent(playerNpc, ignored -> new NearbyOreProbe());
        if (probe.level == serverLevel
                && probe.center != null
                && probe.center.distSqr(center) <= NEARBY_ORE_CACHE_MOVE_SQR
                && playerNpc.tickCount < probe.nextProbeTick) {
            return probe.foundOre;
        }

        ExploreCaveOreGoal boundedProbe = new ExploreCaveOreGoal(playerNpc, 1.0D);
        boundedProbe.oreSearchCenter = center.immutable();
        boundedProbe.oreSearchCursor = probe.level == serverLevel
                && probe.center != null
                && probe.center.distSqr(center) <= NEARBY_ORE_CACHE_MOVE_SQR
                ? probe.searchCursor
                : 0;
        boundedProbe.oreSearchRadius = probe.level == serverLevel
                && probe.center != null
                && probe.center.distSqr(center) <= NEARBY_ORE_CACHE_MOVE_SQR
                ? probe.searchRadius
                : PlayerNpcAdaptiveSearchScope.oreCoverageRadius(serverLevel);
        boolean foundOre = boundedProbe.findOreTarget(serverLevel) != null;
        probe.level = serverLevel;
        probe.center = center.immutable();
        probe.searchCursor = boundedProbe.oreSearchCursor;
        probe.searchRadius = boundedProbe.oreSearchRadius;
        probe.foundOre = foundOre;
        probe.nextProbeTick = playerNpc.tickCount
                + (foundOre || boundedProbe.lastOreSearchCompletedPass ? NEARBY_ORE_CACHE_TICKS : 1);
        return foundOre;
    }

    public static boolean isOreInventoryBlocked(PlayerNpcEntity playerNpc) {
        return freeInventorySlots(playerNpc) <= 2 && !InventoryUtils.hasPlaceableBlock(playerNpc);
    }

    public static int freeInventorySlots(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return 0;
        }
        SimpleContainer inventory = playerNpc.getInventory();
        int freeSlots = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null) {
            this.traceOreCanUseBlocked("ore goal blocked: basic state");
            return false;
        }
        if (this.playerNpc.tickCount < this.nextOreSearchTick
                || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            this.traceOreCanUseBlocked("ore goal blocked: mining night camp");
            return false;
        }
        if (!this.isReadyForOreMining(serverLevel)) {
            if (this.shouldRequestSurfaceEscapeForLogResupply(serverLevel)) {
                this.requestSurfaceEscapeIfUnderground(serverLevel);
            }
            this.traceOreCanUseBlocked("ore goal blocked: not ready logsNeed="
                    + this.playerNpc.shouldPrioritizeLogGathering()
                    + " stoneNeed=" + this.playerNpc.shouldPrioritizeCobblestoneGathering());
            return false;
        }
        if (this.playerNpc.getOreMiningCooldown() > 0) {
            this.traceOreCanUseBlocked("ore goal blocked: oreCooldown=" + this.playerNpc.getOreMiningCooldown());
            return false;
        }
        if (!this.hasAnyPickaxe()) {
            this.traceOreCanUseBlocked("ore goal blocked: no pickaxe");
            return false;
        }
        if (this.inventoryIsMostlyFull()) {
            this.traceOreCanUseBlocked("ore goal blocked: inventory free="
                    + freeInventorySlots(this.playerNpc)
                    + " placeable=" + InventoryUtils.hasPlaceableBlock(this.playerNpc));
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            int retryTicks = 1 + this.playerNpc.getRandom().nextInt(4);
            this.nextOreSearchTick = this.playerNpc.tickCount + retryTicks;
            this.canUseThrottle.retryIn(this.playerNpc, retryTicks);
            return false;
        }

        if (this.resumeOrePos != null) {
            BlockPos resumePos = this.resumeOrePos;
            this.resumeOrePos = null;
            OreTarget resumeTarget = this.isOreSearchTarget(serverLevel, resumePos)
                    ? this.createBoundedClusterOreTarget(serverLevel, resumePos)
                    : null;
            if (resumeTarget != null) {
                this.nextOreSearchTick = this.playerNpc.tickCount + ORE_SEARCH_INTERVAL_TICKS;
                this.resumeInterruptedOreWork = false;
                this.targetPos = resumeTarget.targetPos();
                this.standPos = resumeTarget.standPos();
                this.targetRequiresUpwardApproach = resumeTarget.requiresUpwardApproach();
                return true;
            }
        }

        OreTarget target = this.findOreTarget(serverLevel);
        this.resumeInterruptedOreWork = false;
        if (target == null) {
            // A 32-offset slice is PENDING until the cursor completes the whole bounded
            // search volume. Retrying partial slices promptly preserves mining liveness while
            // the shared admission gate still permits only one small batch per server tick.
            int retryTicks = this.lastOreSearchCompletedPass
                    ? FAILED_RETRY_COOLDOWN_TICKS
                    : 1 + this.playerNpc.getRandom().nextInt(4);
            this.nextOreSearchTick = this.playerNpc.tickCount + retryTicks;
            if (!this.lastOreSearchCompletedPass) {
                this.canUseThrottle.retryIn(this.playerNpc, retryTicks);
            }
            this.traceOreCanUseBlocked("ore goal blocked: no reachable ore");
            return false;
        }

        this.nextOreSearchTick = this.playerNpc.tickCount + ORE_SEARCH_INTERVAL_TICKS;
        this.targetPos = target.targetPos();
        this.standPos = target.standPos();
        this.targetRequiresUpwardApproach = target.requiresUpwardApproach();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.targetPos == null
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.mineTicks >= MAX_MINE_TICKS
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.hasAnyPickaxe()) {
            return false;
        }
        if (this.playerNpc.tickCount >= this.nextContinueEligibilityCheckTick) {
            this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                    + CONTINUE_ELIGIBILITY_RECHECK_TICKS;
            this.continueEligibilityAllowed = this.isReadyForOreMining(serverLevel)
                    && !MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel);
        }
        return this.continueEligibilityAllowed;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.torchPlaceTicks = this.playerNpc.getRandom().nextInt(TORCH_PLACE_INTERVAL_TICKS);
        this.pathObstructionPos = null;
        this.activeClearTarget = null;
        this.lastOreWalkPos = null;
        this.previousMainHand = ItemStack.EMPTY;
        this.clusterFamily = OreFamily.NONE;
        this.clusterOres.clear();
        this.minedClusterOres.clear();
        this.skippedPathObstructions.clear();
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.activeClearTargetTicks = 0;
        this.oreWalkStillTicks = 0;
        this.highOreClearsWithoutRise = 0;
        this.highestOreApproachY = this.playerNpc.blockPosition().getY();
        this.usingTemporaryPickaxe = false;
        this.minedAnyOre = false;
        this.requestedUpwardOreApproach = false;
        this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                + CONTINUE_ELIGIBILITY_RECHECK_TICKS;
        this.continueEligibilityAllowed = true;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring_cave");
        if (this.targetPos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            if (!serverLevel.hasChunkAt(this.targetPos)) {
                this.targetPos = null;
                return;
            }
            BlockState targetState = serverLevel.getBlockState(this.targetPos);
            this.clusterFamily = this.oreFamily(targetState);
            this.refreshClusterOres(serverLevel, this.targetPos);
            if (!this.equipPickaxeFor(targetState)) {
                this.targetPos = null;
                return;
            }
            this.updateTaskDetail(serverLevel);
            if (this.targetRequiresUpwardApproach && this.tryRequestUpwardOreApproach(serverLevel)) {
                return;
            }
        }
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.moveToTarget(serverLevel);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }
        if (!serverLevel.hasChunkAt(this.targetPos)) {
            this.targetPos = null;
            return;
        }

        this.mineTicks++;
        this.trackHighOreApproachProgress();
        if (this.tickClearBlock(serverLevel)) {
            this.updateTaskDetail(serverLevel);
            return;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!this.isOreBlock(state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                this.playerNpc.setCurrentAiDetail("ore cluster selection queued for shared search slice");
                return;
            }
            OreTarget nextOre = this.findNextOreInCluster(serverLevel, this.targetPos);
            if (!this.switchToNextClusterOre(serverLevel, nextOre)) {
                this.targetPos = null;
            }
            return;
        }
        if (this.clusterFamily == OreFamily.NONE) {
            this.clusterFamily = this.oreFamily(state);
        }
        if (this.oreFamily(state) != this.clusterFamily || !this.equipPickaxeFor(state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            this.targetPos = null;
            return;
        }
        this.tickTorchPlacement(serverLevel);

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            boolean navigationDone = this.playerNpc.getNavigation().isDone();
            boolean navigationStuck = this.playerNpc.getNavigation().isStuck();
            if ((navigationDone || navigationStuck) && this.hasOreWalkStalled()) {
                if (this.tryRequestUpwardOreApproach(serverLevel)) {
                    return;
                }
                this.skipCurrentOreTarget();
                this.playerNpc.clearBlockBreakProgress(this.targetPos);
                this.targetPos = null;
                return;
            }
            if (this.repathTicks-- <= 0) {
                if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                    this.repathTicks = 1 + this.playerNpc.getRandom().nextInt(4);
                    return;
                }
                if (this.tryStartPathObstructionMining(serverLevel)) {
                    return;
                } else if (this.tryStepDownToward(serverLevel, this.standPos)) {
                    this.failedPathTicks = 0;
                } else if (this.moveToTarget(serverLevel)) {
                    this.failedPathTicks = 0;
                } else {
                    this.failedPathTicks += REPATH_INTERVAL_TICKS;
                    if (this.failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                        if (!this.tryRequestUpwardOreApproach(serverLevel)) {
                            this.skipCurrentOreTarget();
                            this.playerNpc.clearBlockBreakProgress(this.targetPos);
                            this.targetPos = null;
                        }
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateTaskDetail(serverLevel);
            return;
        }
        this.resetOreWalkStall();

        if (!this.hasClearMiningRay(serverLevel, this.playerNpc.blockPosition(), this.targetPos)
                && this.tryStartOreCoverClearing(serverLevel)) {
            this.updateTaskDetail(serverLevel);
            return;
        }
        if (!this.hasClearMiningRay(serverLevel, this.playerNpc.blockPosition(), this.targetPos)) {
            this.skipCurrentOreTarget();
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            this.targetPos = null;
            return;
        }

        this.playerNpc.getNavigation().stop();
        BlockPos minedPos = this.targetPos;
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                this::isMineableOreState,
                MAX_MINE_TICKS,
                "mining ore"
        );
        this.updateTaskDetail(serverLevel);
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        this.resetOreWalkStall();
        if (result == BreakingBlockAi.TickResult.DONE) {
            this.minedAnyOre = true;
            this.minedClusterOres.add(minedPos.immutable());
            this.skippedOreTargets.remove(minedPos.immutable());
        } else {
            this.skipCurrentOreTarget();
        }
        this.playerNpc.clearBlockBreakProgress(minedPos);
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.playerNpc.setCurrentAiDetail("ore cluster selection queued for shared search slice");
            return;
        }
        OreTarget nextOre = this.findNextOreInCluster(serverLevel, minedPos);
        if (this.switchToNextClusterOre(serverLevel, nextOre)) {
            return;
        }
        this.targetPos = null;
    }

    @Override
    public void stop() {
        boolean upwardApproachRequested = this.requestedUpwardOreApproach;
        boolean interruptedWithOreTarget = this.targetPos != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.isOreSearchTarget(serverLevel, this.targetPos);
        BlockPos interruptedOrePos = interruptedWithOreTarget ? this.targetPos.immutable() : null;
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.playerNpc.clearBlockBreakProgress(this.pathObstructionPos);
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        this.targetPos = null;
        this.standPos = null;
        this.pathObstructionPos = null;
        this.activeClearTarget = null;
        this.lastOreWalkPos = null;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.activeClearTargetTicks = 0;
        this.oreWalkStillTicks = 0;
        this.highOreClearsWithoutRise = 0;
        this.highestOreApproachY = 0;
        this.torchPlaceTicks = 0;
        this.targetRequiresUpwardApproach = false;
        this.requestedUpwardOreApproach = false;
        this.nextContinueEligibilityCheckTick = 0;
        this.continueEligibilityAllowed = true;
        this.clusterFamily = OreFamily.NONE;
        this.clusterOres.clear();
        this.minedClusterOres.clear();
        this.skippedPathObstructions.clear();
        int cooldown = upwardApproachRequested
                ? 0
                : interruptedWithOreTarget
                ? 0
                : this.minedAnyOre
                ? SUCCESS_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 2)
                : FAILED_RETRY_COOLDOWN_TICKS;
        this.minedAnyOre = false;
        if (interruptedWithOreTarget) {
            this.nextOreSearchTick = this.playerNpc.tickCount;
        }
        this.resumeOrePos = interruptedOrePos;
        this.resumeInterruptedOreWork = interruptedWithOreTarget;
        this.playerNpc.setOreMiningCooldown(cooldown);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private OreTarget findOreTarget(ServerLevel serverLevel) {
        List<OreTarget> candidates = new ArrayList<>();
        List<BlockPos> oreCandidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();
        if (this.oreSearchCenter == null
                || this.oreSearchCenter.distSqr(center) > NEARBY_ORE_CACHE_MOVE_SQR) {
            this.oreSearchCenter = center.immutable();
            this.oreSearchCursor = 0;
            this.oreSearchRadius = PlayerNpcAdaptiveSearchScope.oreCoverageRadius(serverLevel);
        }
        if (this.oreSearchRadius <= 0) {
            this.oreSearchRadius = PlayerNpcAdaptiveSearchScope.oreCoverageRadius(serverLevel);
        }
        List<BlockPos> searchOffsets = ORE_SEARCH_OFFSETS.getOrDefault(
                this.oreSearchRadius,
                ORE_SEARCH_OFFSETS.get(PlayerNpcAdaptiveSearchScope.ORE_LOCAL_RADIUS)
        );
        int offsetCount = searchOffsets.size();
        int start = Math.floorMod(this.oreSearchCursor, offsetCount);
        int scanCount = Math.min(MAX_ORE_SCAN_BLOCKS_PER_ATTEMPT, offsetCount - start);
        this.lastOreSearchCompletedPass = start + scanCount >= offsetCount;
        for (int checked = 0; checked < scanCount; checked++) {
            BlockPos offset = searchOffsets.get(start + checked);
            BlockPos immutable = center.offset(offset).immutable();
            if (!serverLevel.hasChunkAt(immutable)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(immutable);
            if (this.skippedOreTargets.contains(immutable)
                    || !this.isInsideResourceRadius(immutable)
                    || !this.isOreSearchTarget(serverLevel, immutable, state)
                    || !this.hasUsablePickaxeFor(state)) {
                continue;
            }

            oreCandidates.add(immutable);
        }
        this.oreSearchCursor = this.lastOreSearchCompletedPass ? 0 : start + scanCount;

        oreCandidates.sort(Comparator
                .comparingInt((BlockPos pos) -> this.orePriority(serverLevel.getBlockState(pos)))
                .thenComparingInt(pos -> this.hasAdjacentAir(serverLevel, pos) ? 0 : 1)
                .thenComparingDouble(center::distSqr));
        this.oreSelectionNavigationPathsRemaining = MAX_ORE_SELECTION_NAVIGATION_PATHS;
        try {
            int checked = 0;
            for (BlockPos oreCandidate : oreCandidates) {
                OreTarget target = this.createOreTarget(serverLevel, oreCandidate);
                if (target != null) {
                    candidates.add(target);
                }
                if (++checked >= MAX_ORE_TARGET_PATH_CHECKS) {
                    break;
                }
            }
        } finally {
            this.oreSelectionNavigationPathsRemaining = -1;
        }

        if (candidates.isEmpty()) {
            return null;
        }

        if (candidates.stream().anyMatch(target -> !target.requiresUpwardApproach())) {
            candidates.removeIf(OreTarget::requiresUpwardApproach);
        }

        candidates.sort(Comparator
                .comparingInt((OreTarget target) -> this.orePriority(serverLevel.getBlockState(target.targetPos())))
                .thenComparingInt(target -> this.hasAdjacentAir(serverLevel, target.targetPos()) ? 0 : 1)
                .thenComparingDouble(target -> center.distSqr(target.standPos())));
        this.oreSearchCenter = center.immutable();
        return candidates.get(this.playerNpc.getRandom().nextInt(Math.min(candidates.size(), 6)));
    }

    private OreTarget createOreTarget(ServerLevel serverLevel, BlockPos orePos) {
        BlockPos stand = this.findStandPos(serverLevel, orePos);
        if (stand != null) {
            return new OreTarget(orePos, stand, false);
        }
        if (!this.needsUpwardOreApproach(serverLevel, orePos)) {
            return null;
        }
        return new OreTarget(orePos, orePos.below().immutable(), true);
    }

    private OreTarget createClusterOreTarget(ServerLevel serverLevel, BlockPos orePos) {
        OreTarget exposedTarget = this.createOreTarget(serverLevel, orePos);
        if (exposedTarget != null) {
            return exposedTarget;
        }
        BlockPos coveredStand = this.findCoveredClusterStand(serverLevel, orePos);
        return coveredStand == null ? null : new OreTarget(orePos, coveredStand, false);
    }

    private OreTarget createBoundedClusterOreTarget(ServerLevel serverLevel, BlockPos orePos) {
        this.oreSelectionNavigationPathsRemaining = MAX_ORE_SELECTION_NAVIGATION_PATHS;
        try {
            return this.createClusterOreTarget(serverLevel, orePos);
        } finally {
            this.oreSelectionNavigationPathsRemaining = -1;
        }
    }

    private void requestSurfaceEscapeIfUnderground(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (serverLevel.canSeeSky(feet.above())) {
            return;
        }

        BlockPos surfaceTarget = this.findSurfaceEscapeTarget(serverLevel, feet);
        if (surfaceTarget != null && !this.isTreeCanopyColumn(serverLevel, feet, surfaceTarget)) {
            this.playerNpc.requestExplorationUpwardEscapeTo(surfaceTarget, UPWARD_ESCAPE_REQUEST_TICKS, 0);
        }
    }

    private boolean isReadyForOreMining(ServerLevel serverLevel) {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !GatherLogsGoal.hasLogSupplyDemand(this.playerNpc, serverLevel)
                && !GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                && !this.shouldYieldToGearCrafting(serverLevel);
    }

    private boolean shouldYieldToGearCrafting(ServerLevel serverLevel) {
        if (this.playerNpc.tickCount >= this.nextGearPriorityCheckTick) {
            this.nextGearPriorityCheckTick = this.playerNpc.tickCount + GEAR_PRIORITY_RECHECK_TICKS;
            this.gearCraftingHasPriority = CraftBasicGearGoal.shouldPrioritizeGearCrafting(this.playerNpc, serverLevel);
        }
        return this.gearCraftingHasPriority;
    }

    private boolean shouldRequestSurfaceEscapeForLogResupply(ServerLevel serverLevel) {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && this.playerNpc.shouldPrioritizeLogGathering()
                && !GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc)
                && !serverLevel.isNight()
                && !serverLevel.canSeeSky(this.playerNpc.blockPosition().above());
    }

    private boolean isTreeCanopyColumn(ServerLevel serverLevel, BlockPos feet, BlockPos surfaceTarget) {
        boolean foundTreeBlock = false;
        int topY = Math.min(surfaceTarget.getY(), serverLevel.getMaxBuildHeight() - 1);
        for (int y = feet.getY() + 1; y <= topY; y++) {
            BlockPos pos = new BlockPos(feet.getX(), y, feet.getZ());
            BlockState state = serverLevel.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.is(BlockTags.LOGS)
                    || state.is(BlockTags.LEAVES)
                    || state.is(Blocks.VINE)
                    || state.is(Blocks.CAVE_VINES)
                    || state.is(Blocks.CAVE_VINES_PLANT)) {
                foundTreeBlock = true;
                continue;
            }
            if (!state.canBeReplaced() || !state.getCollisionShape(serverLevel, pos).isEmpty()) {
                return false;
            }
        }
        return foundTreeBlock;
    }

    private BlockPos findSurfaceEscapeTarget(ServerLevel serverLevel, BlockPos feet) {
        int scanTop = Math.min(serverLevel.getMaxBuildHeight() - 3, feet.getY() + SURFACE_ESCAPE_SCAN_UP);
        for (int y = feet.getY() + 3; y <= scanTop; y++) {
            BlockPos target = new BlockPos(feet.getX(), y, feet.getZ());
            if (serverLevel.canSeeSky(target.above())) {
                return target.immutable();
            }
        }
        return null;
    }

    private boolean isOreSearchTarget(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.hasChunkAt(pos)
                && this.isOreSearchTarget(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    private boolean isOreSearchTarget(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!this.isOreBlock(state) || this.isProtectedHomeBlock(pos)) {
            return false;
        }

        return true;
    }

    private boolean hasAdjacentAir(ServerLevel serverLevel, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = pos.relative(direction);
            if (serverLevel.hasChunkAt(adjacent)
                    && serverLevel.getBlockState(adjacent).isAir()) {
                return true;
            }
        }
        return false;
    }

    private OreTarget findNextOreInCluster(ServerLevel serverLevel, BlockPos originPos) {
        if (this.clusterFamily == OreFamily.NONE) {
            return null;
        }

        this.oreSelectionNavigationPathsRemaining = MAX_ORE_SELECTION_NAVIGATION_PATHS;
        try {
            return this.findNextOreInClusterBounded(serverLevel, originPos);
        } finally {
            this.oreSelectionNavigationPathsRemaining = -1;
        }
    }

    private OreTarget findNextOreInClusterBounded(ServerLevel serverLevel, BlockPos originPos) {
        this.refreshClusterOres(serverLevel, originPos);
        if (!this.clusterOres.isEmpty()) {
            List<BlockPos> rememberedCandidates = new ArrayList<>();
            for (BlockPos clusterOre : this.clusterOres) {
                if (!serverLevel.hasChunkAt(clusterOre)) {
                    continue;
                }
                BlockState state = serverLevel.getBlockState(clusterOre);
                if (this.oreFamily(state) != this.clusterFamily
                        || this.minedClusterOres.contains(clusterOre)
                        || this.skippedOreTargets.contains(clusterOre)
                        || !this.isInsideResourceRadius(clusterOre)
                        || !this.hasUsablePickaxeFor(state)) {
                    continue;
                }

                rememberedCandidates.add(clusterOre.immutable());
            }

            if (!rememberedCandidates.isEmpty()) {
                BlockPos npcPos = this.playerNpc.blockPosition();
                rememberedCandidates.sort(Comparator
                        .comparingDouble((BlockPos pos) -> originPos.distSqr(pos))
                        .thenComparingDouble(npcPos::distSqr));
                int checked = 0;
                for (BlockPos candidate : rememberedCandidates) {
                    OreTarget target = this.createClusterOreTarget(serverLevel, candidate);
                    if (target != null) {
                        return target;
                    }
                    if (++checked >= MAX_CLUSTER_TARGET_CHECKS) {
                        break;
                    }
                }
            }
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> candidates = new ArrayList<>();

        if (this.minedClusterOres.isEmpty()) {
            BlockPos seed = originPos.immutable();
            queue.add(seed);
            visited.add(seed);
        } else {
            for (BlockPos minedOre : this.minedClusterOres) {
                queue.add(minedOre);
                visited.add(minedOre);
            }
        }

        int scannedOreBlocks = 0;
        while (!queue.isEmpty() && scannedOreBlocks < MAX_CLUSTER_SCAN_BLOCKS) {
            BlockPos current = queue.remove();
            for (BlockPos next : this.clusterNeighbors(current)) {
                if (!visited.add(next)) {
                    continue;
                }
                if (!serverLevel.hasChunkAt(next)) {
                    continue;
                }

                BlockState nextState = serverLevel.getBlockState(next);
                if (this.oreFamily(nextState) != this.clusterFamily) {
                    continue;
                }

                scannedOreBlocks++;
                queue.add(next);
                if (this.minedClusterOres.contains(next)
                        || this.skippedOreTargets.contains(next)
                        || !this.isInsideResourceRadius(next)
                        || !this.hasUsablePickaxeFor(nextState)) {
                    continue;
                }

                candidates.add(next.immutable());
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        BlockPos npcPos = this.playerNpc.blockPosition();
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> originPos.distSqr(pos))
                .thenComparingDouble(npcPos::distSqr));
        int checked = 0;
        for (BlockPos candidate : candidates) {
            OreTarget target = this.createClusterOreTarget(serverLevel, candidate);
            if (target != null) {
                return target;
            }
            if (++checked >= MAX_CLUSTER_TARGET_CHECKS) {
                break;
            }
        }
        return null;
    }

    private void refreshClusterOres(ServerLevel serverLevel, BlockPos seedPos) {
        if (this.clusterFamily == OreFamily.NONE || seedPos == null) {
            return;
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>(this.clusterOres);
        if (serverLevel.hasChunkAt(seedPos)
                && this.oreFamily(serverLevel.getBlockState(seedPos)) == this.clusterFamily) {
            BlockPos seed = seedPos.immutable();
            queue.add(seed);
            visited.add(seed);
            this.clusterOres.add(seed);
        }
        for (BlockPos knownOre : this.clusterOres) {
            if (serverLevel.hasChunkAt(knownOre)
                    && this.oreFamily(serverLevel.getBlockState(knownOre)) == this.clusterFamily) {
                queue.add(knownOre);
            }
        }
        for (BlockPos minedOre : this.minedClusterOres) {
            queue.add(minedOre);
            visited.add(minedOre);
        }

        int scannedOreBlocks = 0;
        while (!queue.isEmpty() && scannedOreBlocks < MAX_CLUSTER_SCAN_BLOCKS) {
            BlockPos current = queue.remove();
            for (BlockPos next : this.clusterNeighbors(current)) {
                if (!visited.add(next)) {
                    continue;
                }

                if (!serverLevel.hasChunkAt(next)
                        || !this.isInsideResourceRadius(next)
                        || this.oreFamily(serverLevel.getBlockState(next)) != this.clusterFamily) {
                    continue;
                }

                scannedOreBlocks++;
                this.clusterOres.add(next.immutable());
                queue.add(next);
            }
        }
    }

    private List<BlockPos> clusterNeighbors(BlockPos center) {
        List<BlockPos> neighbors = new ArrayList<>(26);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (!pos.equals(center)) {
                neighbors.add(pos.immutable());
            }
        }
        return neighbors;
    }

    private boolean switchToNextClusterOre(ServerLevel serverLevel, OreTarget nextOre) {
        if (nextOre == null || !serverLevel.hasChunkAt(nextOre.targetPos())) {
            return false;
        }

        BlockState nextState = serverLevel.getBlockState(nextOre.targetPos());
        if (!this.equipPickaxeFor(nextState)) {
            return false;
        }

        this.targetPos = nextOre.targetPos();
        this.standPos = nextOre.standPos();
        this.targetRequiresUpwardApproach = nextOre.requiresUpwardApproach();
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.activeClearTarget = null;
        this.activeClearTargetTicks = 0;
        this.highOreClearsWithoutRise = 0;
        this.highestOreApproachY = this.playerNpc.blockPosition().getY();
        this.resetOreWalkStall();
        this.updateTaskDetail(serverLevel);
        if (this.targetRequiresUpwardApproach && this.tryRequestUpwardOreApproach(serverLevel)) {
            return true;
        }
        this.moveToTarget(serverLevel);
        return true;
    }

    private void skipCurrentOreTarget() {
        if (this.targetPos == null) {
            return;
        }

        if (this.skippedOreTargets.size() > 64) {
            this.skippedOreTargets.clear();
        }
        this.skippedOreTargets.add(this.targetPos.immutable());
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        candidates.add(target.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).above());
            candidates.add(target.relative(direction).below());
        }
        candidates.add(target.above());

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canReachStand(serverLevel, immutable, target)
                    && immutable.distSqr(target) <= BREAK_DISTANCE_SQR + 2.0D
                    && this.hasOreAccessFromStand(serverLevel, immutable, target)) {
                return immutable;
            }
        }
        return null;
    }

    private BlockPos findCoveredClusterStand(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        candidates.add(target.below());
        candidates.add(target.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).above());
            candidates.add(target.relative(direction).below());
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (immutable.distSqr(target) <= BREAK_DISTANCE_SQR + 2.0D
                    && this.canReachStand(serverLevel, immutable, target)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canReachStand(ServerLevel serverLevel, BlockPos pos, BlockPos target) {
        if (this.canStandAt(serverLevel, pos)
                && (this.playerNpc.blockPosition().equals(pos)
                || this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= 2.0D * 2.0D)) {
            return true;
        }

        if (this.canStandAt(serverLevel, pos)) {
            if (this.oreSelectionNavigationPathsRemaining == 0) {
                return false;
            }
            if (this.oreSelectionNavigationPathsRemaining > 0) {
                this.oreSelectionNavigationPathsRemaining--;
            }
            return this.pathNavigationAi.canReachOrSafelyDropTo(serverLevel, pos, MAX_SAFE_DROP_BLOCKS);
        }

        return this.canClearStandAt(serverLevel, pos)
                && this.playerNpc.blockPosition().distSqr(pos) <= PATH_OBSTRUCTION_BREAK_DISTANCE_SQR
                && this.hasOreAccessFromStand(serverLevel, pos, target)
                && this.findPathObstructionToward(serverLevel, pos, target) != null;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private boolean canClearStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.isInWorldBounds(pos.above())
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos.above())
                || !serverLevel.hasChunkAt(pos)) {
            return false;
        }

        return serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && this.canClearBodySpace(serverLevel, pos)
                && this.canClearBodySpace(serverLevel, pos.above());
    }

    private boolean canClearBodySpace(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) {
            return serverLevel.getFluidState(pos).isEmpty();
        }

        return this.isPathObstructionBlock(serverLevel, pos, state);
    }

    private boolean tryStepDownToward(ServerLevel serverLevel, BlockPos destination) {
        if (destination == null
                || !this.playerNpc.onGround()
                || destination.getY() >= this.playerNpc.blockPosition().getY()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<Direction> directions = this.directionsToward(feet, destination);
        for (Direction direction : directions) {
            BlockPos edgeFeet = feet.relative(direction);
            BlockPos landingFeet = this.findSafeDropLanding(serverLevel, edgeFeet);
            if (landingFeet == null || landingFeet.distSqr(destination) > feet.distSqr(destination) + 4.0D) {
                continue;
            }

            double dx = edgeFeet.getX() + 0.5D - this.playerNpc.getX();
            double dz = edgeFeet.getZ() + 0.5D - this.playerNpc.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001D) {
                continue;
            }

            Vec3 motion = this.playerNpc.getDeltaMovement();
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setDeltaMovement(dx / length * 0.28D, Math.min(motion.y, -0.08D), dz / length * 0.28D);
            this.playerNpc.hasImpulse = true;
            return true;
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

    private BlockPos findSafeDropLanding(ServerLevel serverLevel, BlockPos edgeFeet) {
        if (!this.hasOpenBodySpace(serverLevel, edgeFeet)) {
            return null;
        }

        for (int drop = 1; drop <= MAX_SAFE_DROP_BLOCKS; drop++) {
            BlockPos landingFeet = edgeFeet.below(drop);
            if (this.hasOpenBodySpace(serverLevel, landingFeet)
                    && this.canStandAt(serverLevel, landingFeet)) {
                return landingFeet;
            }
        }
        return null;
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private boolean moveToTarget(ServerLevel serverLevel) {
        if (this.standPos == null) {
            return false;
        }

        float pathNodeMultiplier = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                ? OVERLOADED_ROUTE_PATH_NODE_MULTIPLIER
                : ACTIVE_ROUTE_PATH_NODE_MULTIPLIER;
        return this.pathNavigationAi.moveToWithLocalFallback(
                serverLevel,
                this.standPos,
                this.speed,
                MAX_SAFE_DROP_BLOCKS,
                LOCAL_ROUTE_HORIZONTAL_RADIUS,
                LOCAL_ROUTE_VERTICAL_DOWN,
                LOCAL_ROUTE_VERTICAL_UP,
                MAX_LOCAL_ROUTE_PATH_CHECKS,
                pathNodeMultiplier);
    }

    private boolean tryStartPathObstructionMining(ServerLevel serverLevel) {
        BlockPos obstruction = this.findPathObstructionTarget(serverLevel);
        return this.startClearingBlock(serverLevel, obstruction, "clearing ore path");
    }

    private boolean tryStartOreCoverClearing(ServerLevel serverLevel) {
        Optional<BlockPos> rayBlocker = this.findMiningRayBlocker(serverLevel, this.playerNpc.blockPosition(), this.targetPos);
        if (rayBlocker.isPresent()
                && this.startClearingBlock(serverLevel, rayBlocker.get(), "clearing ore cover")) {
            return true;
        }

        List<BlockPos> candidates = ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                this.standPos,
                this.targetPos
        );
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> this.targetPos == null ? 0.0D : pos.distSqr(this.targetPos))
                .thenComparingDouble(this::distanceToBlockCenterSqr));
        for (BlockPos candidate : candidates) {
            if (this.startClearingBlock(serverLevel, candidate, "clearing ore cover")) {
                return true;
            }
        }
        return false;
    }

    private boolean tickClearBlock(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            return false;
        }

        BlockPos clearTarget = this.clearBlockAi.targetPos();
        this.trackActiveClearTarget(clearTarget);
        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            if (this.activeClearTargetTicks >= MAX_ORE_CLEAR_TARGET_TICKS) {
                this.abortSlowClearTarget(clearTarget);
                if (this.tryRequestUpwardOreApproach(serverLevel)) {
                    return true;
                }
                return false;
            }
            return true;
        }

        this.toolAi.restoreMainHand();
        if (result == ClearBlockAi.TickResult.DONE) {
            boolean requestUpwardApproach = this.recordHighOreClearWithoutRise();
            this.failedPathTicks = 0;
            this.repathTicks = 0;
            this.clearPathObstruction();
            if (requestUpwardApproach && this.tryRequestUpwardOreApproach(serverLevel)) {
                return true;
            }
            this.moveToTarget(serverLevel);
            return true;
        }

        if (clearTarget != null) {
            this.skippedPathObstructions.add(clearTarget.immutable());
        }
        this.clearPathObstruction();
        return false;
    }

    private void trackActiveClearTarget(BlockPos clearTarget) {
        if (clearTarget == null) {
            this.activeClearTarget = null;
            this.activeClearTargetTicks = 0;
            return;
        }

        if (!clearTarget.equals(this.activeClearTarget)) {
            this.activeClearTarget = clearTarget.immutable();
            this.activeClearTargetTicks = 0;
        }
        this.activeClearTargetTicks++;
    }

    private void abortSlowClearTarget(BlockPos clearTarget) {
        this.toolAi.restoreMainHand();
        if (clearTarget != null) {
            this.skippedPathObstructions.add(clearTarget.immutable());
        }
        this.failedPathTicks += REPATH_INTERVAL_TICKS;
        this.repathTicks = 0;
        this.clearPathObstruction();
    }

    private boolean startClearingBlock(ServerLevel serverLevel, BlockPos pos, String detail) {
        if (pos == null) {
            return false;
        }

        BlockPos immutable = pos.immutable();
        if (this.isCurrentSupportBlock(immutable)
                || this.skippedPathObstructions.contains(immutable)
                || this.targetPos != null && immutable.equals(this.targetPos)
                || !serverLevel.isInWorldBounds(immutable)
                || !serverLevel.getWorldBorder().isWithinBounds(immutable)
                || !serverLevel.hasChunkAt(immutable)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(immutable);
        if (!this.isPathObstructionBlock(serverLevel, immutable, state)
                || !this.equipToolForPathObstruction(state)) {
            return false;
        }

        this.pathObstructionPos = immutable;
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.breakingBlockAi.stop();
        this.playerNpc.getNavigation().stop();
        if (this.clearBlockAi.start(
                serverLevel,
                immutable,
                targetState -> this.isPathObstructionBlock(serverLevel, immutable, targetState),
                detail,
                CLEAR_OBSTRUCTION_TICKS,
                PATH_OBSTRUCTION_BREAK_DISTANCE_SQR,
                true
        )) {
            return true;
        }

        this.skippedPathObstructions.add(immutable);
        this.clearPathObstruction();
        return false;
    }

    private void clearPathObstruction() {
        if (this.pathObstructionPos != null) {
            this.playerNpc.clearBlockBreakProgress(this.pathObstructionPos);
        }
        this.clearBlockAi.stop();
        this.pathObstructionPos = null;
        this.activeClearTarget = null;
        this.activeClearTargetTicks = 0;
    }

    private boolean hasOreWalkStalled() {
        BlockPos feet = this.playerNpc.blockPosition();
        if (!feet.equals(this.lastOreWalkPos)) {
            this.lastOreWalkPos = feet.immutable();
            this.oreWalkStillTicks = 0;
            return false;
        }

        return ++this.oreWalkStillTicks >= ORE_WALK_STUCK_TICKS;
    }

    private void resetOreWalkStall() {
        this.lastOreWalkPos = null;
        this.oreWalkStillTicks = 0;
    }

    private void trackHighOreApproachProgress() {
        int currentY = this.playerNpc.blockPosition().getY();
        if (currentY > this.highestOreApproachY) {
            this.highestOreApproachY = currentY;
            this.highOreClearsWithoutRise = 0;
        }
    }

    private boolean recordHighOreClearWithoutRise() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.needsUpwardOreApproach(serverLevel, this.targetPos)) {
            this.highOreClearsWithoutRise = 0;
            return false;
        }

        int currentY = this.playerNpc.blockPosition().getY();
        if (currentY > this.highestOreApproachY) {
            this.highestOreApproachY = currentY;
            this.highOreClearsWithoutRise = 0;
            return false;
        }
        return ++this.highOreClearsWithoutRise >= MAX_HIGH_ORE_CLEARS_WITHOUT_RISE;
    }

    private boolean needsUpwardOreApproach(ServerLevel serverLevel, BlockPos orePos) {
        if (orePos == null || serverLevel.canSeeSky(this.playerNpc.blockPosition().above())) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        int verticalGap = orePos.getY() - feet.getY();
        int dx = orePos.getX() - feet.getX();
        int dz = orePos.getZ() - feet.getZ();
        return verticalGap >= HIGH_ORE_MIN_VERTICAL_GAP
                && dx * dx + dz * dz <= HIGH_ORE_MAX_HORIZONTAL_RADIUS * HIGH_ORE_MAX_HORIZONTAL_RADIUS
                && this.playerNpc.distanceToSqr(
                orePos.getX() + 0.5D,
                orePos.getY() + 0.5D,
                orePos.getZ() + 0.5D
        ) > BREAK_DISTANCE_SQR;
    }

    private boolean tryRequestUpwardOreApproach(ServerLevel serverLevel) {
        if (!this.needsUpwardOreApproach(serverLevel, this.targetPos)
                || !this.isOreSearchTarget(serverLevel, this.targetPos)) {
            return false;
        }

        BlockPos oreTarget = this.targetPos.immutable();
        int verticalGap = oreTarget.getY() - this.playerNpc.blockPosition().getY();
        int maxPillarBlocks = Math.max(1, verticalGap + HIGH_ORE_PILLAR_EXTRA_BLOCKS);
        this.clearPathObstruction();
        this.breakingBlockAi.stop();
        this.playerNpc.clearBlockBreakProgress(oreTarget);
        this.playerNpc.getNavigation().stop();
        this.skippedOreTargets.remove(oreTarget);
        this.nextOreSearchTick = Math.min(this.nextOreSearchTick, this.playerNpc.tickCount + 20);
        this.targetRequiresUpwardApproach = false;
        this.requestedUpwardOreApproach = true;
        this.playerNpc.requestForcedUpwardEscapeTo(
                oreTarget,
                HIGH_ORE_UPWARD_REQUEST_TICKS,
                maxPillarBlocks
        );
        this.playerNpc.setCurrentAiDetail("requesting upward ore approach @ "
                + oreTarget.getX() + " "
                + oreTarget.getY() + " "
                + oreTarget.getZ());
        this.targetPos = null;
        this.standPos = null;
        return true;
    }

    private BlockPos findPathObstructionTarget(ServerLevel serverLevel) {
        if (this.standPos == null || this.targetPos == null) {
            return null;
        }

        return this.findPathObstructionToward(serverLevel, this.standPos, this.targetPos);
    }

    private boolean hasLocalPathObstructionToward(ServerLevel serverLevel, BlockPos destination, BlockPos target) {
        return this.playerNpc.blockPosition().distSqr(destination) <= 6.0D * 6.0D
                && this.findPathObstructionToward(serverLevel, destination, target) != null;
    }

    private BlockPos findPathObstructionToward(ServerLevel serverLevel, BlockPos destination, BlockPos target) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(feet, destination, target));
        this.addDiagonalRouteCandidates(candidates, feet, destination);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            candidates.add(side);
            candidates.add(side.above());
            if (destination.getY() > feet.getY()) {
                candidates.add(side.above(2));
            }
            if (destination.getY() < feet.getY()) {
                candidates.add(side.below());
                candidates.add(side.below(2));
            }
        }

        Set<BlockPos> seen = new HashSet<>();
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(destination))
                .thenComparingDouble(this::distanceToBlockCenterSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || this.isCurrentSupportBlock(immutable)
                    || this.skippedPathObstructions.contains(immutable)
                    || target != null && immutable.equals(target)
                    || !serverLevel.isInWorldBounds(immutable)
                    || !serverLevel.getWorldBorder().isWithinBounds(immutable)
                    || !serverLevel.hasChunkAt(immutable)) {
                continue;
            }

            BlockState state = serverLevel.getBlockState(immutable);
            if (this.isPathObstructionBlock(serverLevel, immutable, state)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean isCurrentSupportBlock(BlockPos pos) {
        return pos != null && pos.equals(this.playerNpc.blockPosition().below());
    }

    private void addDiagonalRouteCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos destination) {
        if (feet == null || destination == null) {
            return;
        }

        int stepX = Integer.compare(destination.getX(), feet.getX());
        int stepZ = Integer.compare(destination.getZ(), feet.getZ());
        if (stepX == 0 && stepZ == 0) {
            return;
        }

        BlockPos step = feet.offset(stepX, 0, stepZ);
        candidates.add(step);
        candidates.add(step.above());
        if (destination.getY() > feet.getY()) {
            candidates.add(step.above(2));
        }
        if (destination.getY() < feet.getY()) {
            candidates.add(step.below());
            candidates.add(step.below(2));
        }

        if (stepX != 0 && stepZ != 0) {
            BlockPos xStep = feet.offset(stepX, 0, 0);
            BlockPos zStep = feet.offset(0, 0, stepZ);
            candidates.add(xStep);
            candidates.add(xStep.above());
            candidates.add(zStep);
            candidates.add(zStep.above());
            if (destination.getY() < feet.getY()) {
                candidates.add(xStep.below());
                candidates.add(zStep.below());
            }
        }
    }

    private boolean hasOreAccessFromStand(ServerLevel serverLevel, BlockPos stand, BlockPos target) {
        if (this.hasClearMiningRay(serverLevel, stand, target)) {
            return true;
        }

        Optional<BlockPos> blocker = this.findMiningRayBlocker(serverLevel, stand, target);
        if (blocker.isEmpty()) {
            return false;
        }

        BlockPos blockerPos = blocker.get();
        BlockState blockerState = serverLevel.getBlockState(blockerPos);
        return !this.skippedPathObstructions.contains(blockerPos)
                && this.isPathObstructionBlock(serverLevel, blockerPos, blockerState);
    }

    private boolean hasClearMiningRay(ServerLevel serverLevel, BlockPos stand, BlockPos target) {
        if (stand == null || target == null) {
            return false;
        }

        BlockHitResult hit = serverLevel.clip(new ClipContext(
                this.miningEyeForStand(stand),
                Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                this.playerNpc
        ));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private Optional<BlockPos> findMiningRayBlocker(ServerLevel serverLevel, BlockPos stand, BlockPos target) {
        if (stand == null || target == null) {
            return Optional.empty();
        }

        BlockHitResult hit = serverLevel.clip(new ClipContext(
                this.miningEyeForStand(stand),
                Vec3.atCenterOf(target),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                this.playerNpc
        ));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) {
            return Optional.empty();
        }
        return Optional.of(hit.getBlockPos().immutable());
    }

    private Vec3 miningEyeForStand(BlockPos stand) {
        if (stand.equals(this.playerNpc.blockPosition())) {
            return new Vec3(this.playerNpc.getX(), this.playerNpc.getEyeY(), this.playerNpc.getZ());
        }
        return new Vec3(stand.getX() + 0.5D, stand.getY() + STAND_EYE_HEIGHT, stand.getZ() + 0.5D);
    }

    private boolean isPathObstructionBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && !this.isProtectedHomeBlock(pos)
                && (!state.requiresCorrectToolForDrops() || this.hasUsablePickaxeFor(state));
    }

    private boolean equipToolForPathObstruction(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.requiresCorrectToolForDrops()) {
            return this.equipPickaxeFor(state);
        }
        return true;
    }

    private double distanceToBlockCenterSqr(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean isMineableOreState(BlockState state) {
        return this.isOreBlock(state) && this.hasUsablePickaxeFor(state);
    }

    private boolean isOreBlock(BlockState state) {
        return state.is(Blocks.IRON_ORE)
                || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.COAL_ORE)
                || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.GOLD_ORE)
                || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                || state.is(Blocks.COPPER_ORE)
                || state.is(Blocks.DEEPSLATE_COPPER_ORE);
    }

    private int orePriority(BlockState state) {
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) {
            return 0;
        }
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) {
            return 1;
        }
        if (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)) {
            return 2;
        }
        return 3;
    }

    private OreFamily oreFamily(BlockState state) {
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) {
            return OreFamily.IRON;
        }
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) {
            return OreFamily.COAL;
        }
        if (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)) {
            return OreFamily.GOLD;
        }
        if (state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)) {
            return OreFamily.COPPER;
        }
        return OreFamily.NONE;
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockState state) {
        return this.getRequiredMineTicks(serverLevel, this.targetPos, state);
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

        return Math.min(MAX_MINE_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private boolean hasAnyPickaxe() {
        // ClearBlockAi may temporarily swap the ore pickaxe out of the NPC's visible
        // inventory while a shovel or another clearing tool is equipped. ToolAi still
        // owns that saved pickaxe, so it must count for the goal's continuation check.
        return this.toolAi.hasTool(PickaxeItem.class);
    }

    private boolean hasUsablePickaxeFor(BlockState state) {
        return this.isUsablePickaxeFor(this.playerNpc.getMainHandItem(), state)
                || this.isUsablePickaxeFor(this.playerNpc.getOffhandItem(), state)
                || this.isUsablePickaxeFor(this.playerNpc.getMainWeaponItem(), state)
                || this.isUsablePickaxeFor(this.playerNpc.getOffWeaponItem(), state)
                || InventoryUtils.hasItem(this.playerNpc, stack -> this.isUsablePickaxeFor(stack, state));
    }

    private boolean isUsablePickaxeFor(ItemStack stack, BlockState state) {
        return !stack.isEmpty()
                && stack.getItem() instanceof PickaxeItem
                && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state));
    }

    private boolean equipPickaxeFor(BlockState state) {
        if (this.isUsablePickaxeFor(this.playerNpc.getMainHandItem(), state)) {
            return true;
        }

        ItemStack pickaxe = this.playerNpc.consumeInventoryItem(stack -> this.isUsablePickaxeFor(stack, state), 1)
                .orElse(ItemStack.EMPTY);
        if (!pickaxe.isEmpty()) {
            return this.equipTemporaryPickaxe(pickaxe);
        }

        ItemStack offhandPickaxe = this.playerNpc.getOffhandItem();
        if (this.isUsablePickaxeFor(offhandPickaxe, state)) {
            this.playerNpc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            return this.equipTemporaryPickaxe(offhandPickaxe.copy());
        }

        ItemStack mainWeaponPickaxe = this.playerNpc.takeMainWeaponItem(stack -> this.isUsablePickaxeFor(stack, state));
        if (!mainWeaponPickaxe.isEmpty()) {
            return this.equipTemporaryPickaxe(mainWeaponPickaxe);
        }

        ItemStack offWeaponPickaxe = this.playerNpc.takeOffWeaponItem(stack -> this.isUsablePickaxeFor(stack, state));
        if (!offWeaponPickaxe.isEmpty()) {
            return this.equipTemporaryPickaxe(offWeaponPickaxe);
        }

        return false;
    }

    private boolean equipTemporaryPickaxe(ItemStack pickaxe) {
        if (pickaxe.isEmpty()) {
            return false;
        }
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryPickaxe) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryPickaxe = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
        return true;
    }

    private void tickTorchPlacement(ServerLevel serverLevel) {
        if (this.breakingBlockAi.isRunning() || this.clearBlockAi.isRunning()) {
            return;
        }

        if (this.torchPlaceTicks-- > 0) {
            return;
        }
        this.torchPlaceTicks = TORCH_PLACE_INTERVAL_TICKS + this.playerNpc.getRandom().nextInt(TORCH_PLACE_INTERVAL_TICKS);

        if (this.playerNpc.getRandom().nextFloat() > TORCH_PLACE_CHANCE) {
            return;
        }

        if (!InventoryUtils.hasItem(this.playerNpc, Items.TORCH)
                && !PlayerNpcCraftingUtil.tryCraftTorches(this.playerNpc.getInventory(), this.torchCraftRawLogReserve())) {
            return;
        }

        BlockPos torchPos = this.findTorchPlacement(serverLevel);
        if (torchPos == null) {
            return;
        }

        ItemStack torch = this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
        if (torch.isEmpty()) {
            return;
        }

        this.placingBlockAi.placeBlock(serverLevel, torchPos, Blocks.TORCH.defaultBlockState());
        this.playerNpc.getLookControl().setLookAt(torchPos.getX() + 0.5D, torchPos.getY() + 0.5D, torchPos.getZ() + 0.5D, 40.0F, 40.0F);
    }

    private int torchCraftRawLogReserve() {
        return this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                ? this.playerNpc.getRawLogReserveTarget()
                : 0;
    }

    private BlockPos findTorchPlacement(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        if (serverLevel.getBrightness(LightLayer.BLOCK, center) > TORCH_LOW_LIGHT_LEVEL
                || this.hasNearbyTorch(serverLevel, center)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(center);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(direction));
            candidates.add(center.relative(direction).below());
        }

        BlockState torchState = Blocks.TORCH.defaultBlockState();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (serverLevel.hasChunkAt(immutable)
                    && serverLevel.getBlockState(immutable).canBeReplaced()
                    && serverLevel.getFluidState(immutable).isEmpty()
                    && torchState.canSurvive(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean hasNearbyTorch(ServerLevel serverLevel, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-TORCH_NEARBY_RADIUS, -2, -TORCH_NEARBY_RADIUS),
                center.offset(TORCH_NEARBY_RADIUS, 2, TORCH_NEARBY_RADIUS))) {
            if (serverLevel.hasChunkAt(pos)
                    && serverLevel.getBlockState(pos).is(Blocks.TORCH)) {
                return true;
            }
        }
        return false;
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryPickaxe) {
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
        this.usingTemporaryPickaxe = false;
    }

    private void updateTaskDetail(ServerLevel serverLevel) {
        if (this.clearBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.clearBlockAi.detail());
            return;
        }
        if (this.breakingBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.breakingBlockAi.detail());
            return;
        }
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, state);
        boolean inBreakRange = this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D
        ) <= BREAK_DISTANCE_SQR;
        this.playerNpc.setCurrentAiDetail(String.format(
                Locale.ROOT,
                "%s @ %d %d %d %s",
                blockName,
                this.targetPos.getX(),
                this.targetPos.getY(),
                this.targetPos.getZ(),
                inBreakRange ? String.format(Locale.ROOT, "%d/%dt", Math.min(this.mineTicks, requiredMineTicks), requiredMineTicks) : "walking"
        ));
    }

    private boolean inventoryIsMostlyFull() {
        return isOreInventoryBlocked(this.playerNpc);
    }

    private void traceOreCanUseBlocked(String detail) {
        if (this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)) {
            this.playerNpc.setIdleTraceDetail(detail, IDLE_BLOCK_TRACE_TICKS);
        }
    }

    private static List<BlockPos> createOreSearchOffsets(int searchRadius) {
        List<BlockPos> offsets = new ArrayList<>();
        int radiusSqr = searchRadius * searchRadius;
        for (int dy = -Math.min(SEARCH_DOWN, searchRadius); dy <= Math.min(SEARCH_UP, searchRadius); dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    if (dx * dx + dy * dy + dz * dz <= radiusSqr) {
                        offsets.add(new BlockPos(dx, dy, dz));
                    }
                }
            }
        }
        offsets.sort(Comparator
                .comparingInt((BlockPos pos) -> pos.getX() * pos.getX()
                        + pos.getY() * pos.getY()
                        + pos.getZ() * pos.getZ())
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        int sliceCount = Math.max(1,
                (offsets.size() + MAX_ORE_SCAN_BLOCKS_PER_ATTEMPT - 1) / MAX_ORE_SCAN_BLOCKS_PER_ATTEMPT);
        List<BlockPos> distributed = new ArrayList<>(offsets.size());
        for (int slice = 0; slice < sliceCount; slice++) {
            for (int index = slice; index < offsets.size(); index += sliceCount) {
                distributed.add(offsets.get(index));
            }
        }
        return List.copyOf(distributed);
    }

    private static final class NearbyOreProbe {
        private ServerLevel level;
        private BlockPos center;
        private int nextProbeTick;
        private int searchCursor;
        private int searchRadius;
        private boolean foundOre;
    }

    private enum OreFamily {
        NONE,
        IRON,
        COAL,
        GOLD,
        COPPER
    }

    private record OreTarget(BlockPos targetPos, BlockPos standPos, boolean requiresUpwardApproach) {}
}
