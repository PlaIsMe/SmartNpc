package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.entity.ai.WaterEscapeAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcBedUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcCollisionUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public class BuildHouseGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 90;
    private static final int MATERIAL_RETRY_COOLDOWN_TICKS = 20 * 20;
    private static final int ACTIVE_BUILD_RETRY_COOLDOWN_TICKS = 0;
    private static final int MIN_BASE_BUILD_BLOCKS = 16;
    public static final int MIN_HOME_BUILD_COMMIT_BLOCKS = 32;
    private static final int BUILD_SEARCH_RADIUS = 14;
    private static final int MAX_RANDOM_LAYOUT_ATTEMPTS = 24;
    private static final int MAX_TERRAIN_CLEARS_PER_BUILD_SITE = 36;
    private static final int MAX_PLACEMENT_CLEARANCE_TICKS = 20 * 3;
    private static final int MAX_PLACEMENT_CLEARANCE_RETRIES = 3;
    private static final int MAX_SAME_PLACEMENT_TICKS = 20 * 8;
    private static final int MAX_PLACEMENT_ATTEMPTS = 4;
    private static final int MAX_UNREACHABLE_BUILD_TARGET_TICKS = 20 * 4;
    private static final int BUILD_ROUTE_REPATH_TICKS = 20;
    private static final int BUILD_ROUTE_NO_PROGRESS_TICKS = 20 * 3;
    // Building changes blocks beside the active route, which can make vanilla navigation defer a
    // synchronous path recomputation until Mob.super.tick(). Keep that delayed work bounded for
    // the complete MOVE-owning lifetime rather than only around the explicit moveTo call.
    private static final float ACTIVE_BUILD_PATH_NODE_MULTIPLIER = 0.05F;
    private static final int CRAFT_ROUTE_CLEAR_TRIGGER_TICKS = 20;
    private static final int CRAFT_ROUTE_NO_PROGRESS_TICKS = 20 * 3;
    private static final int CRAFT_ROUTE_CLEAR_TICKS = 24;
    private static final int BUILD_MOTION_INTERVAL_TICKS = 12;
    private static final int BUILD_WORK_AREA_MARGIN = 4;
    private static final int BUILD_WORK_AREA_HEIGHT = 8;
    private static final int READY_BUILD_WORK_CACHE_TICKS = 20 * 3;
    // Blueprint state/material checks are synchronous server-thread reads. Small cursor slices
    // keep a cache refresh from consuming the complete 50 ms tick budget on a large layout.
    private static final int MAX_READY_BUILD_BLOCKS_PER_SLICE = 8;
    private static final int HOME_FINISHED_CACHE_TICKS = 20;
    private static final double BUILD_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double BUILD_HORIZONTAL_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double CRAFT_ROUTE_PROGRESS_EPSILON_SQR = 0.25D;
    private static final double CRAFT_ROUTE_CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final String ACTIVE_BUILD_BATCH_KEY = "SmartNpcActiveBuildBatch";
    private static final Map<PlayerNpcEntity, HomeBuildWorkCache> HOME_BUILD_WORK_CACHE = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, HomeBuildWorkSearch> HOME_BUILD_WORK_SEARCHES = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, HomeFinishedCache> HOME_FINISHED_CACHE = new WeakHashMap<>();
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
    };

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final WaterEscapeAi waterEscapeAi;
    private final ToolAi craftRouteToolAi;
    private final BreakingBlockAi craftRouteBreakingBlockAi;
    private final ClearBlockAi craftRouteClearBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final List<PlayerNpcBuildLayout.RelativeBlock> blueprint = new ArrayList<>();
    private final Set<BlockPos> skippedCraftRouteClearTargets = new HashSet<>();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private PlayerNpcBuildLayout selectedLayout;
    private BlockPos origin;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int placeDelay;
    private int completedPlacements;
    private int totalPlacements;
    private boolean ranOutOfMaterials;
    private boolean blockedBuildPass;
    private boolean showingPlacementItem;
    private boolean waitingForPlacementClearance;
    private boolean placedBlockThisTick;
    private BlockState placedBlockStateThisTick;
    private BlockPos placedBlockSoundPos;
    private BlockPos placementClearancePos;
    private BlockState placementClearanceState;
    private BlockPos activePlacementPos;
    private PlayerNpcBuildLayout.RelativeBlock activeBuildBlock;
    private PlayerNpcBuildLayout.RelativeBlock cachedCraftingBlock;
    private boolean cachedCraftingNeeded;
    private boolean placementRecoveryThisTick;
    private int placementClearanceTicks;
    private int placementClearanceRetries;
    private int samePlacementTicks;
    private int placementAttempts;
    private int nextBuildMotionTick;
    private BlockPos unreachableBuildTargetPos;
    private int unreachableBuildTargetTicks;
    private BlockPos buildRouteTarget;
    private BlockPos buildRouteLastProgressPos;
    private int buildRouteRepathTicks;
    private int buildRouteNoProgressTicks;
    private BlockPos craftApproachTarget;
    private BlockPos craftRouteTablePos;
    private BlockPos craftRouteRequestedPos;
    private int craftRouteNoProgressTicks;
    private double bestCraftApproachDistanceSqr = Double.MAX_VALUE;
    private String missingMaterial = "";

    public BuildHouseGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.waterEscapeAi = new WaterEscapeAi(playerNpc);
        this.craftRouteToolAi = new ToolAi(playerNpc);
        this.craftRouteBreakingBlockAi = new BreakingBlockAi(playerNpc, this.craftRouteToolAi);
        this.craftRouteClearBlockAi = new ClearBlockAi(playerNpc, this.craftRouteBreakingBlockAi);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasReadyHomeBuildWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                || !isConstructionWindow(playerNpc, serverLevel)
                || playerNpc.getBuildHouseCooldown() > 0
                || !playerNpc.hasMetBuildSupplyGoals() && !isBuildBatchActive(playerNpc)) {
            return false;
        }

        return hasContinuableHomeBuildWork(playerNpc, serverLevel);
    }

    /**
     * Lets log-supply owners hand movement back when inventory changes make an unfinished build
     * actionable before an older no-work cooldown expires. The actual blueprint/world query stays
     * cursor-sliced; a pending matching search reserves the handoff until it resolves.
     */
    public static boolean shouldYieldSupplyWorkForBuild(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                || !isConstructionWindow(playerNpc, serverLevel)
                || !playerNpc.hasMetBuildSupplyGoals() && !isBuildBatchActive(playerNpc)) {
            return false;
        }

        // A completed missing-material scan is the authoritative phase owner.  `continuable`
        // means that some blueprint block can be placed, possibly from a later phase; it must not
        // suppress the log route that supplies the earliest unfinished wooden phase.
        if (PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, playerNpc)) {
            return false;
        }

        if (hasContinuableHomeBuildWork(playerNpc, serverLevel)) {
            // A cooldown recorded against the previous inventory must not strand newly placeable
            // work. Structural/terrain gates are still revalidated by BuildHouseGoal.canUse().
            playerNpc.setBuildHouseCooldown(0);
            return true;
        }
        return isMatchingHomeBuildWorkSearchPending(playerNpc, serverLevel);
    }

    /** True while the admitted existing-home readiness scan still has blueprint slices to inspect. */
    public static boolean isHomeBuildWorkSearchPending(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                || !isConstructionWindow(playerNpc, serverLevel)
                || playerNpc.getBuildHouseCooldown() > 0
                || !playerNpc.hasMetBuildSupplyGoals() && !isBuildBatchActive(playerNpc)) {
            return false;
        }

        return isMatchingHomeBuildWorkSearchPending(playerNpc, serverLevel);
    }

    private static boolean isMatchingHomeBuildWorkSearchPending(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel
    ) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return false;
        }
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        HomeBuildWorkSearch search = HOME_BUILD_WORK_SEARCHES.get(playerNpc);
        return search != null && search.matches(
                serverLevel.dimension().identifier(),
                home.get(),
                layoutId,
                buildWorkInventoryHash(playerNpc)
        );
    }

    public static boolean hasContinuableHomeBuildWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)) {
            return false;
        }

        return cachedContinuableHomeBuildWork(playerNpc, serverLevel);
    }

    public static void invalidateHomeBuildWorkCache(PlayerNpcEntity playerNpc) {
        HOME_BUILD_WORK_CACHE.remove(playerNpc);
        HOME_BUILD_WORK_SEARCHES.remove(playerNpc);
        HOME_FINISHED_CACHE.remove(playerNpc);
    }

    public static boolean isHomeLayoutFinished(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        Optional<PlayerNpcBuildLayout> layout = PlayerNpcBuildLayoutLoader.getLayout(layoutId);
        if (home.isPresent()) {
            HomeFinishedCache cached = HOME_FINISHED_CACHE.get(playerNpc);
            if (cached != null && cached.matches(playerNpc.tickCount, serverLevel.dimension().identifier(), home.get(), layoutId)) {
                return cached.finished();
            }
        }
        if (home.isEmpty()
                || layout.isEmpty()
                || layout.get().width() != home.get().width()
                || layout.get().depth() != home.get().depth()) {
            return false;
        }

        boolean finished = !TerraformBuildSiteGoal.hasPrepWorkIgnoringActiveJob(playerNpc, serverLevel);
        BlockPos origin = home.get().origin();
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (!finished) {
                break;
            }
            if (block.optional()
                    || block.state().isAir()
                    || PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())) {
                continue;
            }
            if (!PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())) {
                finished = false;
                break;
            }
        }
        HOME_FINISHED_CACHE.put(playerNpc, HomeFinishedCache.create(
                playerNpc,
                serverLevel.dimension().identifier(),
                home.get(),
                layoutId,
                finished
        ));
        return finished;
    }

    private static boolean cachedContinuableHomeBuildWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> existingHome = PlayerNpcHomeUtil.getHome(playerNpc);
        if (existingHome.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = existingHome.get();
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        int inventoryHash = buildWorkInventoryHash(playerNpc);
        HomeBuildWorkCache cache = HOME_BUILD_WORK_CACHE.get(playerNpc);
        if (cache != null
                && cache.matches(
                playerNpc.tickCount,
                serverLevel.dimension().identifier(),
                homeArea,
                layoutId,
                inventoryHash
        )) {
            return cache.continuable();
        }
        Optional<PlayerNpcBuildLayout> layout = layoutId.isEmpty()
                ? Optional.empty()
                : PlayerNpcBuildLayoutLoader.getLayout(layoutId);
        if (layout.isEmpty()) {
            HOME_BUILD_WORK_SEARCHES.remove(playerNpc);
            HOME_BUILD_WORK_CACHE.put(playerNpc, HomeBuildWorkCache.create(
                    playerNpc,
                    serverLevel.dimension().identifier(),
                    homeArea,
                    layoutId,
                    inventoryHash,
                    false
            ));
            return false;
        }

        if (layout.get().width() != homeArea.width() || layout.get().depth() != homeArea.depth()) {
            HOME_BUILD_WORK_SEARCHES.remove(playerNpc);
            HOME_BUILD_WORK_CACHE.put(playerNpc, HomeBuildWorkCache.create(
                    playerNpc,
                    serverLevel.dimension().identifier(),
                    homeArea,
                    layoutId,
                    inventoryHash,
                    false
            ));
            return false;
        }

        HomeBuildWorkSearch search = HOME_BUILD_WORK_SEARCHES.get(playerNpc);
        if (search == null || !search.matches(
                serverLevel.dimension().identifier(),
                homeArea,
                layoutId,
                inventoryHash
        )) {
            search = HomeBuildWorkSearch.create(
                    serverLevel.dimension().identifier(),
                    homeArea,
                    layoutId,
                    inventoryHash
            );
        }
        if (search.lastSliceTick() == playerNpc.tickCount
                || !PlayerNpcAiWorkBudget.tryAcquire(serverLevel, playerNpc)) {
            // Active workers bypass the global expensive-work queue. Multiple arbitration
            // predicates may therefore reach this method in one selector pass; retain the cursor
            // after one eight-block slice instead of completing the blueprint synchronously.
            HOME_BUILD_WORK_SEARCHES.put(playerNpc, search);
            return cache != null
                    && cache.sameContext(
                    serverLevel.dimension().identifier(),
                    homeArea,
                    layoutId,
                    inventoryHash
            )
                    && cache.continuable();
        }
        List<PlayerNpcBuildLayout.RelativeBlock> blocks = layout.get().blocks();
        int endIndex = Math.min(blocks.size(), search.nextBlockIndex() + MAX_READY_BUILD_BLOCKS_PER_SLICE);
        boolean hasUnfinishedRequired = search.hasUnfinishedRequired();
        boolean hasMaterialForPlacement = search.hasMaterialForPlacement();
        for (int index = search.nextBlockIndex(); index < endIndex; index++) {
            PlayerNpcBuildLayout.RelativeBlock block = blocks.get(index);
            if (PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())) {
                continue;
            }
            boolean built = isBuiltMatch(serverLevel, block.toWorld(homeArea.origin()), block.state());
            if (!built && !block.optional()) {
                hasUnfinishedRequired = true;
            }
            if (!built && PlayerNpcBuildMaterialUtil.hasMaterialFor(serverLevel, playerNpc, block, homeArea.origin())) {
                hasMaterialForPlacement = true;
            }
            if (hasUnfinishedRequired && hasMaterialForPlacement) {
                break;
            }
        }
        boolean continuable = hasUnfinishedRequired && hasMaterialForPlacement;
        if (!continuable && endIndex < blocks.size()) {
            HOME_BUILD_WORK_SEARCHES.put(playerNpc, search.advance(
                    endIndex,
                    playerNpc.tickCount,
                    hasUnfinishedRequired,
                    hasMaterialForPlacement
            ));
            return cache != null
                    && cache.sameContext(
                    serverLevel.dimension().identifier(),
                    homeArea,
                    layoutId,
                    inventoryHash
            )
                    && cache.continuable();
        }

        HOME_BUILD_WORK_SEARCHES.remove(playerNpc);
        HOME_BUILD_WORK_CACHE.put(playerNpc, HomeBuildWorkCache.create(
                playerNpc,
                serverLevel.dimension().identifier(),
                homeArea,
                layoutId,
                inventoryHash,
                continuable
        ));
        return continuable;
    }

    private static int buildWorkInventoryHash(PlayerNpcEntity playerNpc) {
        int hash = 1;
        hash = 31 * hash + itemStackHash(playerNpc.getMainHandItem());
        hash = 31 * hash + itemStackHash(playerNpc.getOffhandItem());
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            hash = 31 * hash + itemStackHash(playerNpc.getInventory().getItem(i));
        }
        return hash;
    }

    private static int itemStackHash(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        int hash = id == null ? 0 : id.hashCode();
        hash = 31 * hash + stack.getCount();
        hash = 31 * hash + stack.getComponents().hashCode();
        return hash;
    }

    public static int countAvailableBuildingBlocks(PlayerNpcEntity playerNpc) {
        int count = 0;
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty()
                    && isBuildingBlockItem(stack)
                    && !PlayerNpcCraftingUtil.isPlanks(stack)) {
                count += stack.getCount();
            }
        }
        return count + PlayerNpcCraftingUtil.countPlankEquivalent(playerNpc.getInventory(), playerNpc.getRawLogReserveTarget());
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.isStoneAccessClearing()
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || ReturnHomeGoal.needsHomeSurfaceRecovery(this.playerNpc, serverLevel)
                || this.playerNpc.getTarget() != null) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        boolean constructionWindow = isConstructionWindow(this.playerNpc, serverLevel);
        boolean existingHome = PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent();
        if (!constructionWindow && existingHome) {
            // Until both supply goals are met, daytime remains reserved for site preparation and
            // supply work once a home exists. A first site still has to be selected during daytime:
            // stone gathering deliberately waits for that site to be prepared, so postponing
            // selection until night leaves a new builder unable to enter either phase.
            return false;
        }
        if (this.playerNpc.getBuildHouseCooldown() > 0) {
            return false;
        }
        if (existingHome) {
            // Terraform owns the full footprint scan. Its admitted result is intentionally checked
            // before any blueprint/material pass so failed BuildHouse eligibility cannot duplicate
            // the same builder-site work in this selector cycle.
            if (TerraformBuildSiteGoal.hasPrepWork(this.playerNpc, serverLevel)) {
                return false;
            }
            return this.loadReadyExistingHomeBuild(serverLevel);
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(4));
            return false;
        }

        BuildSelection selection = this.findBuildSelection(serverLevel);
        if (selection == null) {
            setBuildBatchActive(this.playerNpc, false);
            return false;
        }

        this.selectedLayout = selection.layout();
        this.origin = selection.origin();
        this.homeArea = new PlayerNpcHomeUtil.HomeArea(this.origin, this.selectedLayout.width(), this.selectedLayout.depth());
        PlayerNpcHomeUtil.setHome(this.playerNpc, this.homeArea, this.selectedLayout.id());
        if (TerraformBuildSiteGoal.hasPrepWork(this.playerNpc, serverLevel)) {
            return false;
        }
        // Site selection/preparation may begin before every supply goal is met, but actual
        // blueprint placement requires the full supply threshold outside shelter hours.
        return constructionWindow && this.playerNpc.hasMetBuildSupplyGoals();
    }

    private boolean loadReadyExistingHomeBuild(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> existingHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (existingHome.isEmpty()) {
            return false;
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea home = existingHome.get();
        PlayerNpcBuildLayout buildLayout = layout.get();
        if (buildLayout.width() != home.width()
                || buildLayout.depth() != home.depth()
                || !this.isInsideBuildWorkArea(home)
                || !cachedContinuableHomeBuildWork(this.playerNpc, serverLevel)) {
            return false;
        }
        if (!isBuildBatchActive(this.playerNpc)
                && !this.playerNpc.hasMetBuildSupplyGoals()) {
            return false;
        }

        this.selectedLayout = buildLayout;
        this.origin = home.origin();
        this.homeArea = home;
        PlayerNpcHomeUtil.setHome(this.playerNpc, this.homeArea, this.selectedLayout.id());
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.origin != null
                && !this.blueprint.isEmpty()
                && this.isInsideBuildWorkArea(this.homeArea)
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                && this.playerNpc.isAlive()
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && !this.needsHomeSurfaceRecovery()
                && this.playerNpc.getTarget() == null
                && this.isConstructionWindow();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.applyActiveNavigationBudget();
        this.blueprint.clear();
        this.blueprint.addAll(this.selectedLayout.blocks());
        this.blueprint.sort(Comparator
                .comparingInt(BuildHouseGoal::buildPlacementPriority)
                .thenComparingInt(PlayerNpcBuildLayout.RelativeBlock::y)
                .thenComparingInt(block -> block.isSecondHalfOfSingleItemBlock() ? 1 : 0)
                .thenComparingInt(PlayerNpcBuildLayout.RelativeBlock::x)
                .thenComparingInt(PlayerNpcBuildLayout.RelativeBlock::z));
        this.placeDelay = 0;
        this.completedPlacements = 0;
        this.totalPlacements = countRequiredPlacements(this.blueprint);
        this.ranOutOfMaterials = false;
        this.blockedBuildPass = false;
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        this.placementClearancePos = null;
        this.placementClearanceState = null;
        this.activePlacementPos = null;
        this.activeBuildBlock = null;
        this.cachedCraftingBlock = null;
        this.cachedCraftingNeeded = false;
        this.placementRecoveryThisTick = false;
        this.placementClearanceTicks = 0;
        this.placementClearanceRetries = 0;
        this.samePlacementTicks = 0;
        this.placementAttempts = 0;
        this.nextBuildMotionTick = 0;
        this.clearUnreachableBuildTarget();
        this.resetBuildRoute();
        this.stopCraftRouteClear();
        this.resetCraftRouteProgress();
        this.skippedCraftRouteClearTargets.clear();
        this.placingBlockAi.resetDelay();
        this.missingMaterial = "";
        setBuildBatchActive(this.playerNpc, true);
        this.playerNpc.setCurrentAiState("ai.player_npc.building_house");
        this.updateTaskDetail("starting");
    }

    @Override
    public void tick() {
        try {
            if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.origin == null || this.blueprint.isEmpty()) {
                return;
            }

            if (this.tickWaterEscape(serverLevel, this.activeBuildBlock)) {
                return;
            }
            if (this.tickCraftRouteClear(serverLevel, this.activeBuildBlock)) {
                return;
            }

            PlayerNpcBuildLayout.RelativeBlock block = this.currentBuildBlock(serverLevel);
            if (block == null) {
                this.blueprint.clear();
                return;
            }

            BlockPos target = block.toWorld(this.origin);

            if (this.trackPlacementTarget(target) && this.samePlacementTicks >= MAX_SAME_PLACEMENT_TICKS) {
                this.recoverStalledPlacement(serverLevel, target, block);
                return;
            }
            if (this.tryPlaceBuildSiteCraftingTable(serverLevel)
                    || this.tryCraftMaterialAtBuildSiteCraftingTable(serverLevel, block)) {
                return;
            }

            this.previewPlacementItem(block);
            this.playerNpc.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 40.0F, 40.0F);
            if (this.waitingForPlacementClearance
                    && this.placementClearancePos != null
                    && this.placementClearanceState != null) {
                if (!this.canPlaceWithoutClipping(serverLevel, this.placementClearancePos, this.placementClearanceState)) {
                    if (this.placementClearanceTicks++ >= MAX_PLACEMENT_CLEARANCE_TICKS) {
                        this.placementClearanceTicks = 0;
                        this.placementClearanceRetries++;
                    }
                    if (this.placementClearanceRetries >= MAX_PLACEMENT_CLEARANCE_RETRIES) {
                        this.deferBlockedPlacement(block);
                        this.clearPlacementClearance();
                        this.updateTaskDetail("deferred blocked", block);
                        return;
                    }
                    this.handlePlacementCollision(serverLevel, this.placementClearancePos, this.placementClearanceState);
                    this.updateTaskDetail("moving clear of", block);
                    return;
                }
                this.clearPlacementClearance();
            }

            if (!this.canPlaceFromCurrentPosition(target)) {
                this.moveTowardBuildTarget(target, block, "walking to");
                return;
            }
            this.clearUnreachableBuildTarget();

            this.playerNpc.getNavigation().stop();
            if (this.placingBlockAi.tickDelay(PlacingBlockAi.PLAYER_LIKE_BUILD_DELAY)) {
                this.tickBuildMotion(serverLevel, target, block.state());
                this.updateTaskDetail("placing", block);
                return;
            }
            this.placeDelay = 0;
            this.placementAttempts++;
            if (this.placementAttempts > MAX_PLACEMENT_ATTEMPTS) {
                this.deferBlockedPlacement(block);
                this.placementAttempts = 0;
                this.samePlacementTicks = 0;
                this.updateTaskDetail("deferred retry limit", block);
                return;
            }

            if (!this.placeExactBlock(serverLevel, block)) {
                if (this.ranOutOfMaterials) {
                    this.blueprint.clear();
                    return;
                }
                if (this.waitingForPlacementClearance || this.placementRecoveryThisTick) {
                    return;
                }
                this.deferBlockedPlacement(block);
                this.placementAttempts = 0;
                this.samePlacementTicks = 0;
                return;
            }

            if (this.placedBlockThisTick && this.placedBlockStateThisTick != null) {
                this.placingBlockAi.playPlaceEffects(
                        serverLevel,
                        this.placedBlockSoundPos == null ? target : this.placedBlockSoundPos,
                        this.placedBlockStateThisTick
                );
                this.finishPlacementMainHand();
            }
            this.blueprint.remove(block);
            this.clearActiveBuildBlock();
            this.placementAttempts = 0;
            this.samePlacementTicks = 0;
            if (countsTowardBuildProgress(block)) {
                this.completedPlacements++;
            }
            this.updateTaskDetail("placed", block);
        } finally {
            // Helper paths restore the navigation default after an explicit path build. Reapply
            // the builder lifetime budget so vanilla delayed recomputation remains bounded.
            this.applyActiveNavigationBudget();
        }
    }

    @Override
    public void stop() {
        this.stopCraftRouteClear();
        this.restorePreviousMainHand();
        // Releasing MOVE does not clear PathNavigation's retained route. Stop it before restoring
        // the default node budget so a later idle tick cannot recompute a stale build path.
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getNavigation().resetMaxVisitedNodesMultiplier();
        if (!this.playerNpc.level().isClientSide()) {
            boolean canContinueBatch = this.playerNpc.level() instanceof ServerLevel serverLevel
                    && !this.ranOutOfMaterials
                    && !this.blockedBuildPass
                    && hasContinuableHomeBuildWork(this.playerNpc, serverLevel);
            if (!canContinueBatch) {
                setBuildBatchActive(this.playerNpc, false);
            }
            int cooldown = this.ranOutOfMaterials
                    ? MATERIAL_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 20)
                    : this.blockedBuildPass
                    ? 20 * 10 + this.playerNpc.getRandom().nextInt(20 * 10)
                    : canContinueBatch
                    ? ACTIVE_BUILD_RETRY_COOLDOWN_TICKS
                    : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 60);
            this.playerNpc.setBuildHouseCooldown(cooldown);
            this.playerNpc.setManageHomeCooldown(canContinueBatch ? ACTIVE_BUILD_RETRY_COOLDOWN_TICKS : 0);
        }
        this.blueprint.clear();
        this.homeArea = null;
        this.selectedLayout = null;
        this.origin = null;
        this.placeDelay = 0;
        this.completedPlacements = 0;
        this.totalPlacements = 0;
        this.ranOutOfMaterials = false;
        this.blockedBuildPass = false;
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        this.placementClearancePos = null;
        this.placementClearanceState = null;
        this.activePlacementPos = null;
        this.activeBuildBlock = null;
        this.cachedCraftingBlock = null;
        this.cachedCraftingNeeded = false;
        this.placementRecoveryThisTick = false;
        this.placementClearanceTicks = 0;
        this.placementClearanceRetries = 0;
        this.samePlacementTicks = 0;
        this.placementAttempts = 0;
        this.nextBuildMotionTick = 0;
        this.clearUnreachableBuildTarget();
        this.resetBuildRoute();
        this.resetCraftRouteProgress();
        this.skippedCraftRouteClearTargets.clear();
        this.placingBlockAi.resetDelay();
        this.waterEscapeAi.stop();
        this.missingMaterial = "";
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void applyActiveNavigationBudget() {
        this.playerNpc.getNavigation().setMaxVisitedNodesMultiplier(ACTIVE_BUILD_PATH_NODE_MULTIPLIER);
    }

    private boolean tickWaterEscape(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
        BlockPos workDestination = block == null || this.origin == null ? this.origin : block.toWorld(this.origin);
        WaterEscapeAi.TickResult result = this.waterEscapeAi.tick(serverLevel, 1.0D, workDestination);
        if (result != WaterEscapeAi.TickResult.RUNNING && result != WaterEscapeAi.TickResult.DONE) {
            return false;
        }

        this.playerNpc.setCurrentAiState("ai.player_npc.building_house");
        if (result == WaterEscapeAi.TickResult.RUNNING && !this.waterEscapeAi.detail().isBlank()) {
            this.updateTaskDetail(this.waterEscapeAi.detail(), block);
        } else {
            this.updateTaskDetail("resuming", block);
        }
        return true;
    }

    private BuildSelection findBuildSelection(ServerLevel serverLevel) {
        if (PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()) {
            // Existing homes use loadReadyExistingHomeBuild and its cursor-sliced shared cache.
            // Never fall back to a complete blueprint scan from first-base selection.
            return null;
        }

        if (!this.hasReadyFirstBaseReserves()) {
            return null;
        }

        int availableBlocks = this.countAvailableBaseSelectionBlocks();
        if (availableBlocks < MIN_BASE_BUILD_BLOCKS) {
            return null;
        }

        List<PlayerNpcBuildLayout> layouts = PlayerNpcBuildLayoutLoader.getLayouts();
        if (layouts.isEmpty()) {
            return null;
        }

        for (int i = 0; i < Math.min(MAX_RANDOM_LAYOUT_ATTEMPTS, layouts.size() * 2); i++) {
            PlayerNpcBuildLayout layout = layouts.get(this.playerNpc.getRandom().nextInt(layouts.size()));
            BlockPos origin = this.findBuildOrigin(serverLevel, layout);
            if (origin != null) {
                return new BuildSelection(layout, origin);
            }
        }

        for (PlayerNpcBuildLayout layout : layouts) {
            BlockPos origin = this.findBuildOrigin(serverLevel, layout);
            if (origin != null) {
                return new BuildSelection(layout, origin);
            }
        }
        return null;
    }

    private BlockPos findBuildOrigin(ServerLevel serverLevel, PlayerNpcBuildLayout layout) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> origins = new ArrayList<>();
        for (int x = center.getX() - BUILD_SEARCH_RADIUS; x <= center.getX() + BUILD_SEARCH_RADIUS; x++) {
            for (int z = center.getZ() - BUILD_SEARCH_RADIUS; z <= center.getZ() + BUILD_SEARCH_RADIUS; z++) {
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                origins.add(new BlockPos(x, y, z));
            }
        }

        origins.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos originCandidate : origins) {
            if (this.canBuildAt(serverLevel, layout, originCandidate, false)) {
                return originCandidate;
            }
        }
        return null;
    }

    private boolean canBuildAt(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin, boolean allowExistingHouseBlocks) {
        if (FarmAi.overlapsOwnedFarm(this.playerNpc, origin, layout.width(), layout.depth())) {
            return false;
        }
        int terrainClears = 0;
        int maxTerrainClears = Math.max(MAX_TERRAIN_CLEARS_PER_BUILD_SITE, layout.width() * layout.height() * layout.depth() / 3);
        for (long packedFootprint : layout.footprint()) {
            int x = (int) (packedFootprint >> 32);
            int z = (int) packedFootprint;
            BlockPos floor = origin.offset(x, 0, z);
            if (!serverLevel.getBlockState(floor.below()).isSolidRender()
                    && !serverLevel.getBlockState(floor.below(2)).isSolidRender()) {
                return false;
            }
        }

        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            BlockPos checkPos = block.toWorld(origin);
            if (!serverLevel.isInWorldBounds(checkPos)
                    || !serverLevel.getWorldBorder().isWithinBounds(checkPos)) {
                return false;
            }
            if (PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())) {
                continue;
            }
            if (allowExistingHouseBlocks && isBuiltMatch(serverLevel, checkPos, block.state())) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(checkPos);
            if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, checkPos)) {
                continue;
            }
            if (!this.canClearForBuild(serverLevel, checkPos, state)
                    || ++terrainClears > maxTerrainClears) {
                return false;
            }
        }
        return true;
    }

    private boolean hasReadyFirstBaseReserves() {
        return this.countRawLogs() >= this.playerNpc.getRawLogReserveTarget();
    }

    private static boolean shouldBuildDuringShelter(ServerLevel serverLevel) {
        return serverLevel.isDarkOutside() || serverLevel.isThundering();
    }

    private static boolean isConstructionWindow(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return shouldBuildDuringShelter(serverLevel) || playerNpc.hasMetBuildSupplyGoals();
    }

    private boolean isConstructionWindow() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && isConstructionWindow(this.playerNpc, serverLevel);
    }

    private static boolean isBuildBatchActive(PlayerNpcEntity playerNpc) {
        return playerNpc.getPersistentData().getBooleanOr(ACTIVE_BUILD_BATCH_KEY, false);
    }

    private static void setBuildBatchActive(PlayerNpcEntity playerNpc, boolean active) {
        CompoundTag data = playerNpc.getPersistentData();
        if (active) {
            data.putBoolean(ACTIVE_BUILD_BATCH_KEY, true);
        } else {
            data.remove(ACTIVE_BUILD_BATCH_KEY);
        }
        invalidateHomeBuildWorkCache(playerNpc);
    }

    private int countAvailableBaseSelectionBlocks() {
        int count = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty()
                    && isBuildingBlockItem(stack)
                    && !PlayerNpcCraftingUtil.isPlanks(stack)) {
                count += stack.getCount();
            }
        }
        return count + PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), 0);
    }

    private int countRawLogs() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(ItemTags.LOGS));
    }

    private int countCobblestone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private boolean tryPlaceBuildSiteCraftingTable(ServerLevel serverLevel) {
        if (this.findBuildSiteCraftingTable(serverLevel) != null) {
            return false;
        }

        BlockPos placement = this.findBuildSiteCraftingTablePlacement(serverLevel);
        if (placement == null) {
            return false;
        }

        ItemStack table = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        if (table.isEmpty()
                && !PlayerNpcCraftingUtil.tryCraftWithLogConversion(serverLevel, this.playerNpc.getInventory(), Items.CRAFTING_TABLE, false, 0)) {
            return false;
        }
        if (table.isEmpty()) {
            table = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        }
        if (table.isEmpty()) {
            return false;
        }

        this.showPlacementItem(table);
        if (!this.placingBlockAi.placeBlock(serverLevel, placement, Blocks.CRAFTING_TABLE.defaultBlockState())) {
            this.returnStack(table);
            return false;
        }
        this.finishPlacementMainHand();
        this.playerNpc.getLookControl().setLookAt(placement.getX() + 0.5D, placement.getY() + 0.5D, placement.getZ() + 0.5D, 40.0F, 40.0F);
        this.updateTaskDetail("placed build crafting table", Blocks.CRAFTING_TABLE.defaultBlockState(), placement);
        return true;
    }

    private boolean tryCraftMaterialAtBuildSiteCraftingTable(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
        if (!this.needsCraftingForActiveBlock(serverLevel, block)) {
            return false;
        }

        BlockPos tablePos = this.findBuildSiteCraftingTable(serverLevel);
        if (tablePos == null) {
            return false;
        }

        this.restorePreviousMainHand();
        this.playerNpc.getLookControl().setLookAt(tablePos.getX() + 0.5D, tablePos.getY() + 0.5D, tablePos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(tablePos.getX() + 0.5D, tablePos.getY(), tablePos.getZ() + 0.5D) > BUILD_DISTANCE_SQR) {
            this.trackCraftRouteProgress(tablePos);
            this.moveTowardBuildTarget(tablePos, block, "walking to craft");
            if ((this.unreachableBuildTargetTicks >= CRAFT_ROUTE_CLEAR_TRIGGER_TICKS
                    || this.craftRouteNoProgressTicks >= CRAFT_ROUTE_NO_PROGRESS_TICKS)
                    && this.startCraftRouteClear(serverLevel, tablePos, block)) {
                this.clearUnreachableBuildTarget();
            }
            return true;
        }
        this.clearUnreachableBuildTarget();
        this.resetCraftRouteProgress();
        this.skippedCraftRouteClearTargets.clear();

        this.playerNpc.getNavigation().stop();
        if (this.placeDelay++ < 8) {
            this.updateTaskDetail("crafting", block);
            return true;
        }
        this.placeDelay = 0;

        if (!PlayerNpcBuildMaterialUtil.craftMaterialFor(serverLevel, this.playerNpc, block)) {
            this.cachedCraftingNeeded = false;
            return false;
        }

        this.cachedCraftingNeeded = false;
        this.playerNpc.triggerMainHandUseAnimation();
        this.playerNpc.level().playSound(null, tablePos, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.45F, 1.0F);
        this.updateTaskDetail("crafted", block);
        return true;
    }

    private boolean needsCraftingForActiveBlock(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block == null) {
            return false;
        }
        if (block != this.cachedCraftingBlock) {
            this.cachedCraftingBlock = block;
            this.cachedCraftingNeeded = PlayerNpcBuildMaterialUtil.needsCraftingForPlacement(serverLevel, this.playerNpc, block);
        }
        return this.cachedCraftingNeeded;
    }

    private BlockPos findBuildSiteCraftingTable(ServerLevel serverLevel) {
        if (this.origin == null || this.selectedLayout == null) {
            return null;
        }

        for (BlockPos pos : BlockPos.betweenClosed(
                this.origin.offset(-2, 0, -2),
                this.origin.offset(this.selectedLayout.width() + 1, 3, this.selectedLayout.depth() + 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)
                    && !this.isInsideSelectedBuildFootprint(pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findBuildSiteCraftingTablePlacement(ServerLevel serverLevel) {
        return this.findBuildSiteUtilityPlacement(serverLevel);
    }

    private BlockPos findBuildSiteUtilityPlacement(ServerLevel serverLevel) {
        if (this.origin == null || this.selectedLayout == null) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -1; x <= this.selectedLayout.width(); x++) {
            candidates.add(this.origin.offset(x, 0, -1));
            candidates.add(this.origin.offset(x, 0, this.selectedLayout.depth()));
        }
        for (int z = 0; z < this.selectedLayout.depth(); z++) {
            candidates.add(this.origin.offset(-1, 0, z));
            candidates.add(this.origin.offset(this.selectedLayout.width(), 0, z));
        }

        candidates.sort(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canPlaceBuildSiteUtilityAt(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canPlaceBuildSiteUtilityAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !this.isInsideSelectedBuildFootprint(pos)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private boolean isInsideSelectedBuildFootprint(BlockPos pos) {
        if (this.origin == null || this.selectedLayout == null) {
            return false;
        }

        return pos.getX() >= this.origin.getX()
                && pos.getX() < this.origin.getX() + this.selectedLayout.width()
                && pos.getZ() >= this.origin.getZ()
                && pos.getZ() < this.origin.getZ() + this.selectedLayout.depth();
    }

    private PlayerNpcBuildLayout.RelativeBlock nextUnfinishedBlock(ServerLevel serverLevel) {
        PlayerNpcBuildLayout.RelativeBlock firstMissingBlock = null;
        int unfinishedPhase = Integer.MAX_VALUE;
        for (int i = 0; i < this.blueprint.size(); ) {
            PlayerNpcBuildLayout.RelativeBlock block = this.blueprint.get(i);
            if (PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())) {
                this.blueprint.remove(i);
                continue;
            }
            if (isBuiltMatch(serverLevel, block.toWorld(this.origin), block.state())) {
                this.blueprint.remove(i);
                if (countsTowardBuildProgress(block)) {
                    this.completedPlacements++;
                }
                continue;
            }

            int blockPhase = buildPlacementPriority(block);
            if (unfinishedPhase == Integer.MAX_VALUE) {
                unfinishedPhase = blockPhase;
            } else if (blockPhase > unfinishedPhase) {
                // Do not skip an unavailable foundation/core phase and place later decoration or
                // amenities.  Continue looking within the same phase so carried partial supplies
                // are still consumed before the gather handoff.
                break;
            }

            boolean hasMaterial = PlayerNpcBuildMaterialUtil.hasMaterialFor(serverLevel, this.playerNpc, block, this.origin);
            if (block.optional() && !hasMaterial) {
                this.blueprint.remove(i);
                continue;
            }

            if (hasMaterial) {
                if (this.shouldDelayTorchPlacement(serverLevel, block)) {
                    i++;
                    continue;
                }
                return block;
            }

            if (firstMissingBlock == null) {
                firstMissingBlock = block;
            }
            i++;
        }

        if (firstMissingBlock != null) {
            BlockPos pos = firstMissingBlock.toWorld(this.origin);
            this.ranOutOfMaterials = true;
            this.missingMaterial = PlayerNpcBuildMaterialUtil.describeTarget(firstMissingBlock.state());
            this.updateTaskDetail("missing", firstMissingBlock.state(), pos);
            return null;
        }

        while (!this.blueprint.isEmpty()) {
            PlayerNpcBuildLayout.RelativeBlock block = this.blueprint.get(0);
            if (PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())) {
                this.blueprint.remove(0);
                continue;
            }
            if (isBuiltMatch(serverLevel, block.toWorld(this.origin), block.state())) {
                this.blueprint.remove(0);
                if (countsTowardBuildProgress(block)) {
                    this.completedPlacements++;
                }
                continue;
            }
            return block;
        }
        return null;
    }

    private PlayerNpcBuildLayout.RelativeBlock currentBuildBlock(ServerLevel serverLevel) {
        if (this.activeBuildBlock != null && this.blueprint.contains(this.activeBuildBlock)) {
            if (isBuiltMatch(serverLevel, this.activeBuildBlock.toWorld(this.origin), this.activeBuildBlock.state())) {
                this.blueprint.remove(this.activeBuildBlock);
                if (countsTowardBuildProgress(this.activeBuildBlock)) {
                    this.completedPlacements++;
                }
                this.clearActiveBuildBlock();
            } else {
                return this.activeBuildBlock;
            }
        }

        this.activeBuildBlock = this.nextUnfinishedBlock(serverLevel);
        this.cachedCraftingBlock = null;
        this.cachedCraftingNeeded = false;
        this.placingBlockAi.resetDelay();
        return this.activeBuildBlock;
    }

    private void clearActiveBuildBlock() {
        this.activeBuildBlock = null;
        this.cachedCraftingBlock = null;
        this.cachedCraftingNeeded = false;
        this.clearUnreachableBuildTarget();
        this.resetBuildRoute();
        if (!this.craftRouteClearBlockAi.isRunning()) {
            this.resetCraftRouteProgress();
            this.skippedCraftRouteClearTargets.clear();
        }
        this.placingBlockAi.resetDelay();
    }

    private boolean tickCraftRouteClear(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
        if (!this.craftRouteClearBlockAi.isRunning()) {
            return false;
        }

        BlockPos clearTarget = this.craftRouteClearBlockAi.targetPos();
        if (!this.isSafeCraftRouteClearTarget(serverLevel, clearTarget)) {
            this.rejectProtectedCraftRouteTarget(clearTarget);
            this.updateTaskDetail("craft path clear protected; retrying", block);
            return true;
        }

        ClearBlockAi.TickResult result = this.craftRouteClearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            BlockPos resolvedTarget = this.craftRouteClearBlockAi.targetPos();
            if (!this.isSafeCraftRouteClearTarget(serverLevel, resolvedTarget)) {
                this.rejectProtectedCraftRouteTarget(resolvedTarget);
                this.updateTaskDetail("craft path clear protected; retrying", block);
                return true;
            }
            this.updateTaskDetail(this.craftRouteClearBlockAi.detail(), block);
            return true;
        }

        this.craftRouteToolAi.restoreMainHand();
        this.craftRouteTablePos = null;
        this.craftRouteRequestedPos = null;
        if (result == ClearBlockAi.TickResult.DONE) {
            this.clearUnreachableBuildTarget();
            this.resetCraftRouteProgress();
            this.skippedCraftRouteClearTargets.clear();
            this.updateTaskDetail("cleared craft path; retrying", block);
            return true;
        }
        if (result == ClearBlockAi.TickResult.FAILED && clearTarget != null) {
            this.skippedCraftRouteClearTargets.add(clearTarget.immutable());
        }
        this.updateTaskDetail("craft path clear failed; retrying", block);
        return true;
    }

    private boolean startCraftRouteClear(
            ServerLevel serverLevel,
            BlockPos tablePos,
            PlayerNpcBuildLayout.RelativeBlock block
    ) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(feet, tablePos, tablePos));
        candidates.removeIf(pos -> pos == null
                || pos.equals(tablePos)
                || pos.equals(tablePos.below())
                || pos.equals(feet.below())
                || this.playerNpc.isTemporaryPillarSupport(pos)
                || this.skippedCraftRouteClearTargets.contains(pos)
                || this.isBuiltBlueprintBlock(serverLevel, pos));

        this.restorePreviousMainHand();
        this.craftRouteToolAi.restoreMainHand();
        List<BlockPos> ordered = candidates.stream()
                .map(BlockPos::immutable)
                .distinct()
                .sorted(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
                .toList();
        for (BlockPos candidate : ordered) {
            this.craftRouteTablePos = tablePos.immutable();
            this.craftRouteRequestedPos = candidate;
            if (!this.craftRouteClearBlockAi.start(
                    serverLevel,
                    candidate,
                    BuildHouseGoal::isCraftRouteFoliage,
                    "clearing craft path",
                    CRAFT_ROUTE_CLEAR_TICKS,
                    CRAFT_ROUTE_CLEAR_DISTANCE_SQR,
                    true
            )) {
                continue;
            }
            if (!this.isSafeCraftRouteClearTarget(serverLevel, this.craftRouteClearBlockAi.targetPos())) {
                this.rejectProtectedCraftRouteTarget(this.craftRouteClearBlockAi.targetPos());
                continue;
            }
            this.playerNpc.getNavigation().stop();
            this.updateTaskDetail(this.craftRouteClearBlockAi.detail(), block);
            return true;
        }
        this.craftRouteTablePos = null;
        this.craftRouteRequestedPos = null;
        return false;
    }

    private void trackCraftRouteProgress(BlockPos tablePos) {
        double distanceSqr = this.playerNpc.distanceToSqr(
                tablePos.getX() + 0.5D,
                tablePos.getY(),
                tablePos.getZ() + 0.5D
        );
        if (!tablePos.equals(this.craftApproachTarget)) {
            this.craftApproachTarget = tablePos.immutable();
            this.bestCraftApproachDistanceSqr = distanceSqr;
            this.craftRouteNoProgressTicks = 0;
            this.skippedCraftRouteClearTargets.clear();
            return;
        }
        if (distanceSqr + CRAFT_ROUTE_PROGRESS_EPSILON_SQR < this.bestCraftApproachDistanceSqr) {
            this.bestCraftApproachDistanceSqr = distanceSqr;
            this.craftRouteNoProgressTicks = 0;
            return;
        }
        this.craftRouteNoProgressTicks++;
    }

    private void resetCraftRouteProgress() {
        this.craftApproachTarget = null;
        this.craftRouteNoProgressTicks = 0;
        this.bestCraftApproachDistanceSqr = Double.MAX_VALUE;
    }

    private void stopCraftRouteClear() {
        this.craftRouteClearBlockAi.stop();
        this.craftRouteToolAi.restoreMainHand();
        this.craftRouteTablePos = null;
        this.craftRouteRequestedPos = null;
    }

    private boolean isSafeCraftRouteClearTarget(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || this.craftRouteTablePos == null
                || pos.equals(this.craftRouteTablePos)
                || pos.equals(this.craftRouteTablePos.below())
                || pos.equals(this.playerNpc.blockPosition().below())
                || this.playerNpc.isTemporaryPillarSupport(pos)
                || this.skippedCraftRouteClearTargets.contains(pos)
                || this.isBuiltBlueprintBlock(serverLevel, pos)) {
            return false;
        }
        return isCraftRouteFoliage(serverLevel.getBlockState(pos));
    }

    private void rejectProtectedCraftRouteTarget(BlockPos resolvedTarget) {
        if (this.craftRouteRequestedPos != null) {
            this.skippedCraftRouteClearTargets.add(this.craftRouteRequestedPos.immutable());
        }
        if (resolvedTarget != null) {
            this.skippedCraftRouteClearTargets.add(resolvedTarget.immutable());
        }
        this.stopCraftRouteClear();
    }

    private boolean isBuiltBlueprintBlock(ServerLevel serverLevel, BlockPos pos) {
        if (this.origin == null || this.selectedLayout == null) {
            return false;
        }
        for (PlayerNpcBuildLayout.RelativeBlock block : this.selectedLayout.blocks()) {
            if (block.toWorld(this.origin).equals(pos)
                    && !PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())
                    && isBuiltMatch(serverLevel, pos, block.state())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCraftRouteFoliage(BlockState state) {
        return state != null
                && !state.isAir()
                && (state.is(BlockTags.LEAVES) || state.canBeReplaced());
    }

    private boolean placeExactBlock(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
        this.waitingForPlacementClearance = false;
        this.placementRecoveryThisTick = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        BlockPos pos = block.toWorld(this.origin);
        BlockState targetState = block.state();

        if (PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(targetState)) {
            return true;
        }
        if (isBuiltMatch(serverLevel, pos, targetState)) {
            return true;
        }

        BlockState existing = serverLevel.getBlockState(pos);
        if (!PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
            if (this.tryRelocateBlockingUtility(serverLevel, pos, existing)) {
                this.placementRecoveryThisTick = true;
                this.placeDelay = 0;
                return false;
            }
            if (!this.canClearForBuild(serverLevel, pos, existing)) {
                this.updateTaskDetail("blocked by", existing, pos);
                return false;
            }
            this.blockedBuildPass = true;
            this.blueprint.clear();
            this.clearActiveBuildBlock();
            this.updateTaskDetail("terraform needed", existing, pos);
            return false;
        }

        if (targetState.isAir()) {
            return true;
        }

        Optional<PlayerNpcBuildMaterialUtil.PlacementMaterial> placementMaterial = PlayerNpcBuildMaterialUtil.resolvePlacement(serverLevel, this.playerNpc, block, this.origin);
        if (placementMaterial.isEmpty()) {
            if (block.optional()) {
                return true;
            }
            this.ranOutOfMaterials = true;
            this.missingMaterial = PlayerNpcBuildMaterialUtil.describeTarget(targetState);
            this.updateTaskDetail("missing", targetState, pos);
            return false;
        }

        PlayerNpcBuildMaterialUtil.PlacementMaterial material = placementMaterial.get();
        BlockState placementState = material.state();
        Optional<PairedPlacement> pairedPlacement = this.pairedPlacementFor(pos, targetState, placementState);
        ItemStack consumed = material.consumedItem();
        if (pairedPlacement.isPresent() && !this.preparePairedPlacementTarget(serverLevel, pairedPlacement.get())) {
            this.returnPlacementMaterial(material);
            return false;
        }

        if (!this.canPlaceWithoutClipping(serverLevel, pos, placementState)) {
            this.returnPlacementMaterial(material);
            this.waitForPlacementClearance(pos, placementState);
            this.updateTaskDetail("collision at", placementState, pos);
            return false;
        }
        if (pairedPlacement.isPresent() && !this.canPlaceWithoutClipping(serverLevel, pairedPlacement.get().pos(), pairedPlacement.get().state())) {
            this.returnPlacementMaterial(material);
            this.waitForPlacementClearance(pairedPlacement.get().pos(), pairedPlacement.get().state());
            this.updateTaskDetail("collision at", pairedPlacement.get().state(), pairedPlacement.get().pos());
            return false;
        }

        this.showPlacementItem(consumed);
        if (!this.placingBlockAi.placeBlock(serverLevel, pos, placementState, false)) {
            this.returnPlacementMaterial(material);
            this.updateTaskDetail("set failed", placementState, pos);
            return false;
        }
        if (pairedPlacement.isPresent()) {
            PairedPlacement pair = pairedPlacement.get();
            if (!this.placingBlockAi.placeBlock(serverLevel, pair.pos(), pair.state(), false)) {
                serverLevel.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                this.returnPlacementMaterial(material);
                this.updateTaskDetail("set failed", pair.state(), pair.pos());
                return false;
            }
        }
        this.applyBlockEntityData(serverLevel, pos, block.blockEntityTag());
        this.markPlacedBlock(pos, placementState);
        return true;
    }

    private Optional<PairedPlacement> pairedPlacementFor(BlockPos pos, BlockState targetState, BlockState placementState) {
        if (targetState.getBlock() instanceof DoorBlock
                && placementState.getBlock() instanceof DoorBlock
                && targetState.hasProperty(DoorBlock.HALF)
                && placementState.hasProperty(DoorBlock.HALF)
                && targetState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
            return Optional.of(new PairedPlacement(pos.above(), placementState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
        }

        if (targetState.getBlock() instanceof DoublePlantBlock
                && placementState.getBlock() instanceof DoublePlantBlock
                && targetState.hasProperty(DoublePlantBlock.HALF)
                && placementState.hasProperty(DoublePlantBlock.HALF)
                && targetState.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
            return Optional.of(new PairedPlacement(pos.above(), placementState.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)));
        }

        if (targetState.getBlock() instanceof BedBlock
                && placementState.getBlock() instanceof BedBlock
                && targetState.hasProperty(BedBlock.PART)
                && targetState.hasProperty(BedBlock.FACING)
                && placementState.hasProperty(BedBlock.PART)
                && placementState.hasProperty(BedBlock.FACING)
                && targetState.getValue(BedBlock.PART) == BedPart.FOOT) {
            Direction facing = placementState.getValue(BedBlock.FACING);
            return Optional.of(new PairedPlacement(pos.relative(facing), placementState.setValue(BedBlock.PART, BedPart.HEAD)));
        }

        return Optional.empty();
    }

    private boolean preparePairedPlacementTarget(ServerLevel serverLevel, PairedPlacement pairedPlacement) {
        BlockPos pos = pairedPlacement.pos();
        BlockState existing = serverLevel.getBlockState(pos);
        if (PlayerNpcBuildMaterialUtil.matches(existing, pairedPlacement.state())
                || PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
            return true;
        }

        if (this.tryRelocateBlockingUtility(serverLevel, pos, existing)) {
            this.placementRecoveryThisTick = true;
            this.placeDelay = 0;
            return false;
        }
        if (!this.canClearForBuild(serverLevel, pos, existing)) {
            this.updateTaskDetail("paired cell blocked by", existing, pos);
            return false;
        }

        this.blockedBuildPass = true;
        this.blueprint.clear();
        this.clearActiveBuildBlock();
        this.updateTaskDetail("terraform needed", existing, pos);
        return false;
    }

    private void applyBlockEntityData(ServerLevel serverLevel, BlockPos pos, CompoundTag blockEntityTag) {
        if (blockEntityTag == null || blockEntityTag.isEmpty()) {
            return;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }

        CompoundTag tag = blockEntityTag.copy();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        blockEntity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, serverLevel.registryAccess(), tag));
        blockEntity.setChanged();
        serverLevel.sendBlockUpdated(pos, serverLevel.getBlockState(pos), serverLevel.getBlockState(pos), 3);
    }

    private static boolean isBuiltMatch(ServerLevel serverLevel, BlockPos pos, BlockState targetState) {
        return PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(pos), targetState);
    }

    private void markPlacedBlock(BlockPos pos, BlockState state) {
        this.placedBlockThisTick = true;
        this.placedBlockStateThisTick = state;
        this.placedBlockSoundPos = pos.immutable();
    }

    private void previewPlacementItem(PlayerNpcBuildLayout.RelativeBlock block) {
        if (this.playerNpc.level() instanceof ServerLevel serverLevel && this.origin != null) {
            this.showPlacementItem(PlayerNpcBuildMaterialUtil.previewItem(serverLevel, this.playerNpc, block));
            return;
        }
        this.showPlacementItem(block.requiredItem());
    }

    private void showPlacementItem(ItemStack stack) {
        if (stack.isEmpty()) {
            if (this.showingPlacementItem) {
                this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
            return;
        }

        if (!this.showingPlacementItem) {
            this.previousMainHand = this.playerNpc.getMainHandItem().copy();
            this.showingPlacementItem = true;
        }

        ItemStack preview = stack.copy();
        preview.setCount(1);
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, preview);
    }

    private void restorePreviousMainHand() {
        if (!this.showingPlacementItem) {
            return;
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
    }

    private void finishPlacementMainHand() {
        if (!this.showingPlacementItem) {
            return;
        }

        this.placingBlockAi.finishHeldPlacement(this.previousMainHand);
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
    }

    private boolean canClearForBuild(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        boolean bedObstruction = PlayerNpcBedUtil.isSafeBuildObstruction(serverLevel, this.playerNpc, pos, state);
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && !this.isProtectedTemporaryCraftingTable(serverLevel, pos)
                && (serverLevel.getBlockEntity(pos) == null || bedObstruction)
                && (bedObstruction
                || state.canBeReplaced()
                || state.getCollisionShape(serverLevel, pos).isEmpty()
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(BlockTags.MINEABLE_WITH_AXE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || state.is(BlockTags.LEAVES));
    }

    private boolean isProtectedTemporaryCraftingTable(ServerLevel serverLevel, BlockPos pos) {
        return CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && !this.isInsideSelectedBuildFootprint(pos);
    }

    private boolean tryRelocateBlockingUtility(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!this.isInsideSelectedBuildFootprint(pos)
                || !(state.is(Blocks.CHEST) || state.is(Blocks.FURNACE))
                || !(serverLevel.getBlockEntity(pos) instanceof Container source)) {
            return false;
        }

        BlockPos relocation = this.findBuildSiteUtilityPlacement(serverLevel);
        if (relocation == null) {
            return false;
        }

        List<ItemStack> contents = this.copyContainerContents(source);
        serverLevel.removeBlock(pos, false);
        if (!this.placingBlockAi.placeBlock(serverLevel, relocation, state, false)) {
            serverLevel.setBlockAndUpdate(pos, state);
            this.restoreContainerContents(serverLevel, pos, contents);
            return false;
        }

        this.restoreContainerContents(serverLevel, relocation, contents);
        if (state.is(Blocks.CHEST)) {
            this.playerNpc.setOwnedChestPos(relocation);
        }
        this.placingBlockAi.playPlaceEffects(serverLevel, relocation, state);
        this.updateTaskDetail("moved utility", state, relocation);
        return true;
    }

    private List<ItemStack> copyContainerContents(Container container) {
        List<ItemStack> contents = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            contents.add(container.getItem(slot).copy());
        }
        return contents;
    }

    private void restoreContainerContents(ServerLevel serverLevel, BlockPos pos, List<ItemStack> contents) {
        if (!(serverLevel.getBlockEntity(pos) instanceof Container container)) {
            return;
        }

        int max = Math.min(container.getContainerSize(), contents.size());
        for (int slot = 0; slot < max; slot++) {
            container.setItem(slot, contents.get(slot).copy());
        }
        container.setChanged();
    }

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, state);
    }

    private void waitForPlacementClearance(BlockPos pos, BlockState state) {
        if (!pos.equals(this.placementClearancePos)) {
            this.placementClearanceRetries = 0;
        }
        this.waitingForPlacementClearance = true;
        this.placementClearancePos = pos.immutable();
        this.placementClearanceState = state;
        this.placementClearanceTicks = 0;
        this.placeDelay = 0;
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.handlePlacementCollision(serverLevel, pos, state);
        }
    }

    private void clearPlacementClearance() {
        this.waitingForPlacementClearance = false;
        this.placementClearancePos = null;
        this.placementClearanceState = null;
        this.placementClearanceTicks = 0;
        this.placementClearanceRetries = 0;
    }

    private boolean trackPlacementTarget(BlockPos target) {
        if (target == null) {
            this.activePlacementPos = null;
            this.samePlacementTicks = 0;
            return false;
        }
        if (!target.equals(this.activePlacementPos)) {
            this.activePlacementPos = target.immutable();
            this.samePlacementTicks = 0;
            this.placementAttempts = 0;
            return false;
        }
        if (!this.playerNpc.getNavigation().isDone()
                || !this.canPlaceFromCurrentPosition(target)) {
            this.samePlacementTicks = 0;
            return false;
        }

        this.samePlacementTicks++;
        return true;
    }

    private void recoverStalledPlacement(ServerLevel serverLevel, BlockPos target, PlayerNpcBuildLayout.RelativeBlock block) {
        this.samePlacementTicks = 0;
        this.placementClearanceRetries++;
        if (this.placementClearanceRetries >= MAX_PLACEMENT_CLEARANCE_RETRIES) {
            this.deferBlockedPlacement(block);
            this.clearPlacementClearance();
            this.updateTaskDetail("deferred stalled", block);
            return;
        }

        this.placeDelay = 0;
        this.handlePlacementCollision(serverLevel, target, block.state());
        this.updateTaskDetail("moving clear of", block);
    }

    private boolean canPlaceFromCurrentPosition(BlockPos target) {
        double dx = this.playerNpc.getX() - (target.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (target.getZ() + 0.5D);
        return dx * dx + dz * dz <= BUILD_HORIZONTAL_DISTANCE_SQR;
    }

    private void moveTowardBuildTarget(BlockPos target, PlayerNpcBuildLayout.RelativeBlock block, String stage) {
        boolean newRouteTarget = !target.equals(this.buildRouteTarget);
        if (newRouteTarget) {
            this.buildRouteTarget = target.immutable();
            this.buildRouteLastProgressPos = this.playerNpc.blockPosition().immutable();
            this.buildRouteRepathTicks = 0;
            this.buildRouteNoProgressTicks = 0;
        } else {
            BlockPos currentPos = this.playerNpc.blockPosition();
            if (!currentPos.equals(this.buildRouteLastProgressPos)) {
                this.buildRouteLastProgressPos = currentPos.immutable();
                this.buildRouteNoProgressTicks = 0;
                this.clearUnreachableBuildTarget();
            } else {
                this.buildRouteNoProgressTicks++;
            }
        }

        if (this.buildRouteRepathTicks > 0) {
            this.buildRouteRepathTicks--;
        }
        boolean navigationActive = !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck();
        if (!newRouteTarget
                && navigationActive
                && this.buildRouteNoProgressTicks < BUILD_ROUTE_NO_PROGRESS_TICKS) {
            // Reissuing moveTo(x, y, z) creates a fresh path. Keep the current usable path instead
            // of rebuilding it every tick while the builder is still making progress.
            this.updateTaskDetail(stage, block);
            return;
        }
        if (this.buildRouteRepathTicks > 0) {
            this.trackUnreachableBuildTarget(target);
            if (this.unreachableBuildTargetTicks >= MAX_UNREACHABLE_BUILD_TARGET_TICKS) {
                this.deferBlockedPlacement(block);
                this.updateTaskDetail("deferred unreachable", block);
                return;
            }
            this.updateTaskDetail(stage, block);
            return;
        }

        boolean moving = this.playerNpc.getNavigation().moveTo(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D,
                1.0D
        );
        this.buildRouteRepathTicks = BUILD_ROUTE_REPATH_TICKS;
        if (moving
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck()
                && this.buildRouteNoProgressTicks < BUILD_ROUTE_NO_PROGRESS_TICKS) {
            this.clearUnreachableBuildTarget();
            this.updateTaskDetail(stage, block);
            return;
        }

        this.trackUnreachableBuildTarget(target);
        if (this.unreachableBuildTargetTicks >= MAX_UNREACHABLE_BUILD_TARGET_TICKS) {
            this.deferBlockedPlacement(block);
            this.updateTaskDetail("deferred unreachable", block);
            return;
        }

        this.updateTaskDetail(stage, block);
    }

    private void trackUnreachableBuildTarget(BlockPos target) {
        if (target == null) {
            this.clearUnreachableBuildTarget();
            return;
        }
        if (!target.equals(this.unreachableBuildTargetPos)) {
            this.unreachableBuildTargetPos = target.immutable();
            this.unreachableBuildTargetTicks = 0;
            return;
        }

        this.unreachableBuildTargetTicks++;
    }

    private void clearUnreachableBuildTarget() {
        this.unreachableBuildTargetPos = null;
        this.unreachableBuildTargetTicks = 0;
    }

    private void resetBuildRoute() {
        this.buildRouteTarget = null;
        this.buildRouteLastProgressPos = null;
        this.buildRouteRepathTicks = 0;
        this.buildRouteNoProgressTicks = 0;
    }

    private boolean isInsideBuildWorkArea(PlayerNpcHomeUtil.HomeArea homeArea) {
        if (homeArea == null) {
            return false;
        }

        BlockPos pos = this.playerNpc.blockPosition();
        return pos.getX() >= homeArea.origin().getX() - BUILD_WORK_AREA_MARGIN
                && pos.getX() < homeArea.origin().getX() + homeArea.width() + BUILD_WORK_AREA_MARGIN
                && pos.getZ() >= homeArea.origin().getZ() - BUILD_WORK_AREA_MARGIN
                && pos.getZ() < homeArea.origin().getZ() + homeArea.depth() + BUILD_WORK_AREA_MARGIN
                && pos.getY() >= homeArea.origin().getY()
                && pos.getY() <= homeArea.origin().getY() + BUILD_WORK_AREA_HEIGHT;
    }

    private boolean needsHomeSurfaceRecovery() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && ReturnHomeGoal.needsHomeSurfaceRecovery(this.playerNpc, serverLevel);
    }

    private void deferBlockedPlacement(PlayerNpcBuildLayout.RelativeBlock block) {
        if (this.blueprint.size() <= 1) {
            this.blockedBuildPass = true;
            this.blueprint.clear();
            this.clearActiveBuildBlock();
            return;
        }

        int index = this.blueprint.indexOf(block);
        if (index < 0) {
            return;
        }

        int phase = buildPlacementPriority(block);
        this.blueprint.remove(index);
        int insertionIndex = 0;
        while (insertionIndex < this.blueprint.size()
                && buildPlacementPriority(this.blueprint.get(insertionIndex)) <= phase) {
            insertionIndex++;
        }
        this.blueprint.add(insertionIndex, block);
        this.clearActiveBuildBlock();
    }

    private void moveAwayFromPlacement(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        BlockPos standPos = this.findPlacementClearanceStand(serverLevel, pos, state);
        if (standPos != null
                && this.playerNpc.getNavigation().moveTo(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D, 1.0D)) {
            return;
        }
        if (this.tryRandomClearanceStep(serverLevel, pos, state)) {
            return;
        }

        double dx = this.playerNpc.getX() - (pos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (pos.getZ() + 0.5D);
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-4D) {
            Direction direction = this.playerNpc.getDirection().getOpposite();
            dx = direction.getStepX();
            dz = direction.getStepZ();
            distance = 1.0D;
        }

        double targetX = this.playerNpc.getX() + dx / distance * 1.2D;
        double targetZ = this.playerNpc.getZ() + dz / distance * 1.2D;
        this.playerNpc.getNavigation().moveTo(targetX, this.playerNpc.getY(), targetZ, 1.0D);
    }

    private void tickBuildMotion(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.playerNpc.tickCount < this.nextBuildMotionTick) {
            return;
        }

        this.nextBuildMotionTick = this.playerNpc.tickCount
                + BUILD_MOTION_INTERVAL_TICKS
                + this.playerNpc.getRandom().nextInt(BUILD_MOTION_INTERVAL_TICKS + 1);

        if (this.hasSelfPlacementCollision(serverLevel, pos, state)) {
            this.handlePlacementCollision(serverLevel, pos, state);
            return;
        }

        if (this.tryStepAroundPlacement(serverLevel, pos, state)) {
            return;
        }

        if (this.playerNpc.onGround() && this.playerNpc.getRandom().nextFloat() < 0.25F) {
            this.tryJumpForPlacementClearance(serverLevel);
        }
    }

    private boolean tryStepAroundPlacement(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        List<Direction> directions = new ArrayList<>();
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            directions.add(direction);
        }
        directions.sort(Comparator.comparingDouble(direction ->
                this.playerNpc.blockPosition().distSqr(pos.relative(direction))));

        for (Direction direction : directions) {
            for (int y = 0; y >= -1; y--) {
                BlockPos candidate = pos.relative(direction).offset(0, y, 0);
                if (!this.canUsePlacementClearanceStand(serverLevel, candidate, pos, state)) {
                    continue;
                }
                if (this.playerNpc.distanceToSqr(
                        candidate.getX() + 0.5D,
                        candidate.getY(),
                        candidate.getZ() + 0.5D) <= 0.35D * 0.35D) {
                    continue;
                }

                if (candidate.getY() > this.playerNpc.blockPosition().getY()) {
                    this.tryJumpForPlacementClearance(serverLevel);
                }
                this.playerNpc.getMoveControl().setWantedPosition(
                        candidate.getX() + 0.5D,
                        candidate.getY(),
                        candidate.getZ() + 0.5D,
                        0.85D
                );
                return true;
            }
        }
        return false;
    }

    private boolean tryJumpForPlacementClearance(ServerLevel serverLevel) {
        if (!this.playerNpc.onGround()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.hasOpenJumpSpace(serverLevel, feet)) {
            return false;
        }

        this.playerNpc.getJumpControl().jump();
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setDeltaMovement(motion.x, Math.max(motion.y, 0.42D), motion.z);
        this.playerNpc.hurtMarked = true;
        return true;
    }

    private boolean hasOpenJumpSpace(ServerLevel serverLevel, BlockPos feet) {
        return serverLevel.getBlockState(feet.above()).getCollisionShape(serverLevel, feet.above()).isEmpty()
                && serverLevel.getBlockState(feet.above(2)).getCollisionShape(serverLevel, feet.above(2)).isEmpty()
                && serverLevel.getFluidState(feet.above()).isEmpty()
                && serverLevel.getFluidState(feet.above(2)).isEmpty();
    }

    private boolean tryRandomClearanceStep(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        BlockPos current = this.playerNpc.blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            Direction direction = HORIZONTAL_DIRECTIONS[this.playerNpc.getRandom().nextInt(HORIZONTAL_DIRECTIONS.length)];
            int distance = 1 + this.playerNpc.getRandom().nextInt(2);
            for (int y = -1; y <= 1; y++) {
                BlockPos candidate = current.relative(direction, distance).offset(0, y, 0);
                if (this.canUsePlacementClearanceStand(serverLevel, candidate, pos, state)
                        && this.playerNpc.getNavigation().moveTo(
                        candidate.getX() + 0.5D,
                        candidate.getY(),
                        candidate.getZ() + 0.5D,
                        1.0D)) {
                    return true;
                }
            }
        }
        return false;
    }

    private BlockPos findPlacementClearanceStand(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        BlockPos current = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int radius = 1; radius <= 3; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                        continue;
                    }
                    for (int y = -1; y <= 1; y++) {
                        BlockPos candidate = pos.offset(x, y, z);
                        if (this.canUsePlacementClearanceStand(serverLevel, candidate, pos, state)) {
                            candidates.add(candidate.immutable());
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }

        candidates.sort(Comparator.comparingDouble(current::distSqr));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean canUsePlacementClearanceStand(ServerLevel serverLevel, BlockPos standPos, BlockPos placementPos, BlockState placementState) {
        if (!serverLevel.isInWorldBounds(standPos)
                || !serverLevel.getWorldBorder().isWithinBounds(standPos)
                || !serverLevel.getBlockState(standPos).getCollisionShape(serverLevel, standPos).isEmpty()
                || !serverLevel.getBlockState(standPos.above()).getCollisionShape(serverLevel, standPos.above()).isEmpty()
                || !serverLevel.getBlockState(standPos.below()).isSolidRender()
                || !serverLevel.getFluidState(standPos).isEmpty()
                || !serverLevel.getFluidState(standPos.above()).isEmpty()) {
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
                && placementState.getCollisionShape(serverLevel, placementPos)
                .toAabbs()
                .stream()
                .noneMatch(box -> box.move(placementPos).intersects(standBox));
    }

    private void handlePlacementCollision(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        List<Entity> blockingEntities = this.findBlockingPlacementEntities(serverLevel, pos, state);
        if (!blockingEntities.isEmpty()) {
            this.clearBlockingPlacementEntities(pos, blockingEntities);
        }

        if (this.hasSelfPlacementCollision(serverLevel, pos, state) || blockingEntities.isEmpty()) {
            this.tryJumpForPlacementClearance(serverLevel);
            this.moveAwayFromPlacement(serverLevel, pos, state);
        }
    }

    private boolean hasSelfPlacementCollision(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.placingBlockAi.hasSelfPlacementCollision(serverLevel, pos, state);
    }

    private List<Entity> findBlockingPlacementEntities(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.placingBlockAi.findBlockingPlacementEntities(serverLevel, pos, state);
    }

    private List<AABB> placementCollisionBoxes(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.placingBlockAi.placementCollisionBoxes(serverLevel, pos, state);
    }

    private void clearBlockingPlacementEntities(BlockPos pos, List<Entity> blockingEntities) {
        boolean swung = false;
        for (Entity entity : blockingEntities) {
            double dx = entity.getX() - (pos.getX() + 0.5D);
            double dz = entity.getZ() - (pos.getZ() + 0.5D);
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < 1.0E-4D) {
                Direction direction = this.playerNpc.getDirection();
                dx = direction.getStepX();
                dz = direction.getStepZ();
                distance = 1.0D;
            }

            entity.push(dx / distance * 0.45D, 0.08D, dz / distance * 0.45D);
            if (!swung && entity instanceof LivingEntity && !(entity instanceof Player)) {
                this.playerNpc.doHurtTarget(entity);
                swung = true;
            }
        }
        if (!swung) {
            this.playerNpc.triggerMainHandAttackAnimation();
        }
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void returnPlacementMaterial(PlayerNpcBuildMaterialUtil.PlacementMaterial material) {
        this.returnStack(material.consumedItem());
        for (ItemStack stack : material.extraConsumedItems()) {
            this.returnStack(stack);
        }
    }

    private void updateTaskDetail(String stage) {
        this.updateTaskDetail(stage, null, null);
    }

    private void updateTaskDetail(String stage, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block == null || this.origin == null) {
            this.updateTaskDetail(stage, null, null);
            return;
        }

        this.updateTaskDetail(stage, block.state(), block.toWorld(this.origin));
    }

    private void updateTaskDetail(String stage, BlockState state, BlockPos pos) {
        if (this.selectedLayout == null) {
            return;
        }

        String progress = this.completedPlacements + "/" + this.totalPlacements;
        StringBuilder detail = new StringBuilder("Build: ").append(this.selectedLayout.name());
        if (state == null || state.isAir() || pos == null) {
            detail.append('\n')
                    .append(formatTaskStage(stage))
                    .append('\n')
                    .append("Progress: ")
                    .append(progress);
        } else if ("missing".equals(stage)) {
            detail.append('\n')
                    .append("Missing: ")
                    .append(this.missingMaterial.isBlank() ? describeTaskState(state) : this.missingMaterial)
                    .append('\n')
                    .append(describeTaskState(state))
                    .append(" @ ")
                    .append(formatTaskPos(pos))
                    .append(' ')
                    .append(progress);
        } else {
            detail.append('\n')
                    .append(formatTaskStage(stage))
                    .append(": ")
                    .append(describeTaskState(state))
                    .append('\n')
                    .append("@ ")
                    .append(formatTaskPos(pos))
                    .append(' ')
                    .append(progress);
        }

        this.playerNpc.setCurrentAiDetail(detail.toString());
    }

    private static String formatTaskStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "Working";
        }

        String trimmed = stage.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private static String describeTaskState(BlockState state) {
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockId == null) {
            return state.getBlock().getDescriptionId();
        }
        return "minecraft".equals(blockId.getNamespace()) ? blockId.getPath() : blockId.toString();
    }

    private static String formatTaskPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static int countRequiredPlacements(List<PlayerNpcBuildLayout.RelativeBlock> blocks) {
        int count = 0;
        for (PlayerNpcBuildLayout.RelativeBlock block : blocks) {
            if (countsTowardBuildProgress(block)) {
                count++;
            }
        }
        return count;
    }

    private static boolean countsTowardBuildProgress(PlayerNpcBuildLayout.RelativeBlock block) {
        return block != null
                && !block.optional()
                && !block.state().isAir()
                && !PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state());
    }

    private static int buildPlacementPriority(PlayerNpcBuildLayout.RelativeBlock block) {
        return block == null
                ? Integer.MAX_VALUE
                : PlayerNpcBuildMaterialUtil.buildMaterialPhasePriority(block.state());
    }

    private static boolean isTorchPlacement(BlockState state) {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
    }

    private boolean shouldDelayTorchPlacement(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock torchBlock) {
        if (torchBlock == null || !isTorchPlacement(torchBlock.state())) {
            return false;
        }

        for (PlayerNpcBuildLayout.RelativeBlock block : this.blueprint) {
            if (block == torchBlock
                    || block.optional()
                    || block.state().isAir()
                    || PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())
                    || block.isSecondHalfOfSingleItemBlock()
                    || !isTorchPrerequisitePlacement(block.state())
                    || isBuiltMatch(serverLevel, block.toWorld(this.origin), block.state())) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isTorchPrerequisitePlacement(BlockState state) {
        return state.is(BlockTags.LOGS)
                || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.WOODEN_DOORS)
                || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.WOODEN_FENCES)
                || state.getBlock() instanceof FenceGateBlock
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_BUTTONS)
                || state.is(BlockTags.WOODEN_PRESSURE_PLATES);
    }



    private static boolean isBuildingBlockItem(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return !state.is(Blocks.AIR)
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty();
    }

    private record PairedPlacement(BlockPos pos, BlockState state) {}

    private record BuildSelection(PlayerNpcBuildLayout layout, BlockPos origin) {}

    private record HomeBuildWorkCache(
            int tick,
            Identifier dimension,
            BlockPos origin,
            int width,
            int depth,
            String layoutId,
            int inventoryHash,
            boolean continuable
    ) {
        static HomeBuildWorkCache create(
                PlayerNpcEntity playerNpc,
                Identifier dimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String layoutId,
                int inventoryHash,
                boolean continuable
        ) {
            return new HomeBuildWorkCache(
                    playerNpc.tickCount,
                    dimension,
                    homeArea.origin(),
                    homeArea.width(),
                    homeArea.depth(),
                    layoutId,
                    inventoryHash,
                    continuable
            );
        }

        boolean matches(
                int currentTick,
                Identifier currentDimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String currentLayoutId,
                int currentInventoryHash
        ) {
            int age = currentTick - this.tick;
            return age >= 0
                    && age <= READY_BUILD_WORK_CACHE_TICKS
                    && this.sameContext(currentDimension, homeArea, currentLayoutId, currentInventoryHash);
        }

        boolean sameContext(
                Identifier currentDimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String currentLayoutId,
                int currentInventoryHash
        ) {
            return this.dimension.equals(currentDimension)
                    && this.origin.equals(homeArea.origin())
                    && this.width == homeArea.width()
                    && this.depth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId)
                    && this.inventoryHash == currentInventoryHash;
        }
    }

    private record HomeBuildWorkSearch(
            Identifier dimension,
            BlockPos origin,
            int width,
            int depth,
            String layoutId,
            int inventoryHash,
            int nextBlockIndex,
            int lastSliceTick,
            boolean hasUnfinishedRequired,
            boolean hasMaterialForPlacement
    ) {
        private static HomeBuildWorkSearch create(
                Identifier dimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String layoutId,
                int inventoryHash
        ) {
            return new HomeBuildWorkSearch(
                    dimension,
                    homeArea.origin(),
                    homeArea.width(),
                    homeArea.depth(),
                    layoutId,
                    inventoryHash,
                    0,
                    Integer.MIN_VALUE,
                    false,
                    false
            );
        }

        private boolean matches(
                Identifier currentDimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String currentLayoutId,
                int currentInventoryHash
        ) {
            return this.dimension.equals(currentDimension)
                    && this.origin.equals(homeArea.origin())
                    && this.width == homeArea.width()
                    && this.depth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId)
                    && this.inventoryHash == currentInventoryHash;
        }

        private HomeBuildWorkSearch advance(
                int nextBlockIndex,
                int lastSliceTick,
                boolean hasUnfinishedRequired,
                boolean hasMaterialForPlacement
        ) {
            return new HomeBuildWorkSearch(
                    this.dimension,
                    this.origin,
                    this.width,
                    this.depth,
                    this.layoutId,
                    this.inventoryHash,
                    nextBlockIndex,
                    lastSliceTick,
                    hasUnfinishedRequired,
                    hasMaterialForPlacement
            );
        }
    }

    private record HomeFinishedCache(
            int tick,
            Identifier dimension,
            BlockPos origin,
            int width,
            int depth,
            String layoutId,
            boolean finished
    ) {
        static HomeFinishedCache create(
                PlayerNpcEntity playerNpc,
                Identifier dimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String layoutId,
                boolean finished
        ) {
            return new HomeFinishedCache(
                    playerNpc.tickCount,
                    dimension,
                    homeArea.origin(),
                    homeArea.width(),
                    homeArea.depth(),
                    layoutId,
                    finished
            );
        }

        boolean matches(
                int currentTick,
                Identifier currentDimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String currentLayoutId
        ) {
            return currentTick - this.tick <= HOME_FINISHED_CACHE_TICKS
                    && this.dimension.equals(currentDimension)
                    && this.origin.equals(homeArea.origin())
                    && this.width == homeArea.width()
                    && this.depth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId);
        }
    }
}
