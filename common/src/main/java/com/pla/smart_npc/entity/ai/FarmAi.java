package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcFarmPlan;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Phase;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Shape;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** Shared farming mechanics built around {@link PlayerNpcFarmPlan}. */
public final class FarmAi {
    public static final int MIN_SIZE = 5;
    public static final int MAX_SIZE = 8;
    public static final int REQUIRED_STONE = 2;
    public static final int FARM_FURNACE_STONE = 8;

    private static final int PLAN_SEARCH_ATTEMPTS = 112;
    private static final int PLAN_SEARCH_ATTEMPTS_PER_PASS = 8;
    private static final int PLAN_SEARCH_RESET_DISTANCE_SQR = 5 * 5;
    private static final int MIN_SITE_CENTER_DISTANCE = 5;
    private static final int MAX_SITE_SEARCH_RADIUS = 14;
    private static final int MAX_SITE_VERTICAL_OFFSET = 4;
    private static final int MAX_FARM_TORCH_TARGETS = 4;
    private static final int FARM_TORCH_LOW_LIGHT_LEVEL = 7;
    private static final int FARM_STONE_PROTECTION_BUFFER = 2;
    private static final int FARM_TORCH_CHARCOAL_LOG_TARGET = 3;
    private static final float PLAN_ENTRY_PATH_NODE_MULTIPLIER = 0.03F;
    private static final float OVERLOADED_PLAN_ENTRY_PATH_NODE_MULTIPLIER = 0.01F;
    private static final List<SiteOffset> NEARBY_SITE_OFFSETS = List.copyOf(createNearbySiteOffsets());
    private static final Map<PlayerNpcEntity, PlanSearchCursor> PLAN_SEARCH_CURSORS = new WeakHashMap<>();

    private FarmAi() {
    }

    public static boolean isFarmingJobActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && playerNpc.hasInterest(PlayerNpcInterest.FARMING)
                && playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING);
    }

    public static Optional<Plan> getPlan(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return Optional.empty();
        }
        Optional<Plan> saved = PlayerNpcFarmPlan.get(playerNpc);
        if (saved.isEmpty()) {
            return Optional.empty();
        }

        Plan plan = saved.get();
        String dimension = serverLevel.dimension().identifier().toString();
        if (plan.dimension().isBlank()) {
            plan = plan.withDimension(dimension);
            PlayerNpcFarmPlan.save(playerNpc, plan);
        }
        if (dimension.equals(plan.dimension()) && !isGateOnPerimeter(plan)) {
            BlockPos gateAnchor = PlayerNpcHomeUtil.getHome(playerNpc)
                    .map(PlayerNpcHomeUtil::center)
                    .orElseGet(playerNpc::blockPosition);
            BlockPos migratedGate = chooseGatePos(plan.origin(), plan.width(), plan.depth(), gateAnchor);
            plan = new Plan(
                    plan.origin(),
                    plan.width(),
                    plan.depth(),
                    plan.shape(),
                    plan.phase(),
                    plan.waterPos(),
                    migratedGate,
                    plan.dimension()
            );
            PlayerNpcFarmPlan.save(playerNpc, plan);
        }
        if (!dimension.equals(plan.dimension()) || !isPlanGeometryValid(playerNpc, plan)) {
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    public static Optional<Plan> getOrCreatePlan(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return getOrCreatePlan(playerNpc, serverLevel, false);
    }

    /**
     * Continues the bounded farm-site search while reusing a caller-owned expensive-work permit.
     * Running setup recovery can clear an invalid plan and request its replacement in the same
     * activation; making that caller reacquire would turn a valid deferral into a false exhausted
     * result.
     */
    public static Optional<Plan> getOrCreatePlan(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            boolean admissionHeld
    ) {
        Optional<Plan> existing = getPlan(playerNpc, serverLevel);
        if (existing.isPresent()) {
            PLAN_SEARCH_CURSORS.remove(playerNpc);
            return existing;
        }
        Optional<Plan> invalidOrOtherDimension = PlayerNpcFarmPlan.get(playerNpc);
        if (invalidOrOtherDimension.isPresent()) {
            if (!invalidOrOtherDimension.get().dimension().isBlank()
                    && !serverLevel.dimension().identifier().toString().equals(invalidOrOtherDimension.get().dimension())) {
                return Optional.empty();
            }
            PlayerNpcFarmPlan.clear(playerNpc);
            PLAN_SEARCH_CURSORS.remove(playerNpc);
        }

        BlockPos searchCenter = playerNpc.blockPosition();
        String dimension = serverLevel.dimension().identifier().toString();
        PlanSearchCursor cursor = PLAN_SEARCH_CURSORS.get(playerNpc);
        if (cursor == null
                || !dimension.equals(cursor.dimension)
                || cursor.searchCenter.distSqr(searchCenter) >= PLAN_SEARCH_RESET_DISTANCE_SQR) {
            cursor = new PlanSearchCursor(dimension, searchCenter.immutable());
            PLAN_SEARCH_CURSORS.put(playerNpc, cursor);
        }
        if (cursor.exhausted || !admissionHeld && !PlayerNpcAiWorkBudget.tryAcquire(serverLevel, playerNpc)) {
            return Optional.empty();
        }

        BlockPos gateAnchor = PlayerNpcHomeUtil.getHome(playerNpc)
                .map(PlayerNpcHomeUtil::center)
                .orElse(cursor.searchCenter);
        int passAttempts = 0;
        while (cursor.nextOffsetIndex < NEARBY_SITE_OFFSETS.size()
                && cursor.totalAttempts < PLAN_SEARCH_ATTEMPTS
                && passAttempts++ < PLAN_SEARCH_ATTEMPTS_PER_PASS) {
            SiteOffset offset = NEARBY_SITE_OFFSETS.get(cursor.nextOffsetIndex++);
            cursor.totalAttempts++;
            int width = MIN_SIZE + playerNpc.getRandom().nextInt(MAX_SIZE - MIN_SIZE + 1);
            int depth = MIN_SIZE + playerNpc.getRandom().nextInt(MAX_SIZE - MIN_SIZE + 1);
            int centerX = cursor.searchCenter.getX() + offset.dx();
            int centerZ = cursor.searchCenter.getZ() + offset.dz();
            if (!serverLevel.hasChunk(centerX >> 4, centerZ >> 4)) {
                continue;
            }
            int groundY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ) - 1;
            if (Math.abs(groundY + 1 - cursor.searchCenter.getY()) > MAX_SITE_VERTICAL_OFFSET) {
                continue;
            }
            BlockPos origin = new BlockPos(centerX - width / 2, groundY, centerZ - depth / 2);
            if (!cursor.triedOrigins.add(origin.asLong())) {
                continue;
            }

            Shape shape = width >= 6 && depth >= 6 && playerNpc.getRandom().nextFloat() < 0.35F
                    ? Shape.ROUNDED
                    : Shape.RECTANGLE;
            BlockPos water = chooseWaterPos(playerNpc, origin, width, depth);
            BlockPos gate = chooseGatePos(origin, width, depth, gateAnchor);
            Plan candidate = new Plan(
                    origin,
                    width,
                    depth,
                    shape,
                    Phase.GATHER_LOGS,
                    water,
                    gate,
                    dimension
            );
            if (!canClaimPlan(playerNpc, serverLevel, candidate)) {
                continue;
            }

            PlayerNpcFarmPlan.save(playerNpc, candidate);
            PLAN_SEARCH_CURSORS.remove(playerNpc);
            return Optional.of(candidate);
        }
        if (cursor.totalAttempts >= PLAN_SEARCH_ATTEMPTS
                || cursor.nextOffsetIndex >= NEARBY_SITE_OFFSETS.size()) {
            cursor.exhausted = true;
        }
        return Optional.empty();
    }

    /** True while the current bounded site search still has work; exploration must wait for it. */
    public static boolean isPlanSearchPending(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null || PlayerNpcFarmPlan.get(playerNpc).isPresent()) {
            return false;
        }
        PlanSearchCursor cursor = PLAN_SEARCH_CURSORS.get(playerNpc);
        String currentDimension = serverLevel.dimension().identifier().toString();
        return cursor == null
                || !currentDimension.equals(cursor.dimension)
                || !cursor.exhausted;
    }

    public static boolean needsFarmLogs(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!isFarmingJobActive(playerNpc)) {
            return false;
        }
        Optional<Plan> plan = getPlan(playerNpc, serverLevel);
        if (plan.isEmpty()) {
            return false;
        }
        if (plan.get().phase() == Phase.GATHER_LOGS) {
            return ResourceAi.countLogs(playerNpc) < requiredRawLogs(plan.get());
        }
        if (plan.get().phase() == Phase.READY && hasPendingFarmLighting(serverLevel, playerNpc)) {
            return ResourceAi.countLogs(playerNpc) < requiredFarmLightingLogs(playerNpc);
        }
        return plan.get().phase() != Phase.READY
                && missingFenceCount(serverLevel, plan.get()) > carriedFenceCount(playerNpc)
                && ResourceAi.countLogs(playerNpc) == 0
                && !InventoryUtils.hasItem(playerNpc, stack -> stack.is(ItemTags.PLANKS) || stack.is(Items.STICK));
    }

    public static boolean needsFarmStone(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!isFarmingJobActive(playerNpc)) {
            return false;
        }
        Optional<Plan> plan = getPlan(playerNpc, serverLevel);
        if (plan.isEmpty() || plan.get().phase() == Phase.GATHER_LOGS) {
            return false;
        }
        int requiredStone = plan.get().phase() == Phase.GATHER_STONE || needsHoe(playerNpc, serverLevel)
                ? REQUIRED_STONE
                : 0;
        if (needsFarmTorchCharcoalSmelting(serverLevel, playerNpc)
                && !InventoryUtils.hasItem(playerNpc, Items.FURNACE)
                && !PlayerNpcCraftingUtil.canCraftFurnace(playerNpc.getInventory())
                && !FurnaceAi.hasValidTrackedTemporaryFurnace(serverLevel, playerNpc)) {
            requiredStone = Math.max(requiredStone, FARM_FURNACE_STONE);
        }
        return ResourceAi.countStone(playerNpc) < requiredStone;
    }

    public static boolean needsHoe(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!isFarmingJobActive(playerNpc) || hasHoe(playerNpc)) {
            return false;
        }
        return getPlan(playerNpc, serverLevel).map(plan -> {
            if (plan.phase().ordinal() < Phase.CLEAR.ordinal()) {
                return false;
            }
            if (plan.phase() != Phase.READY) {
                return true;
            }
            return hasExposedUntilledCropGround(serverLevel, plan);
        }).orElse(false);
    }

    public static boolean hasExposedUntilledCropGround(ServerLevel serverLevel, Plan plan) {
        if (serverLevel == null || plan == null) {
            return false;
        }
        for (BlockPos ground : plan.cropGroundPositions()) {
            if (serverLevel.getBlockState(ground.above()).getBlock() instanceof CropBlock) {
                continue;
            }
            BlockState groundState = serverLevel.getBlockState(ground);
            if (!groundState.is(Blocks.FARMLAND) && isTillableGround(groundState)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasHoe(PlayerNpcEntity playerNpc) {
        return playerNpc != null && playerNpc.hasCarriedTool(HoeItem.class);
    }

    public static int requiredRawLogs(Plan plan) {
        int fenceCount = plan == null ? 24 : plan.fencePositions().size();
        int estimatedPlanks = fenceCount * 2 + 4;
        return Math.max(12, Math.min(24, (estimatedPlanks + 3) / 4 + 2));
    }

    public static Plan advancePhase(PlayerNpcEntity playerNpc, Plan plan, Phase phase) {
        if (playerNpc == null || plan == null || phase == null || plan.phase() == phase) {
            return plan;
        }
        Plan updated = plan.withPhase(phase);
        PlayerNpcFarmPlan.save(playerNpc, updated);
        return updated;
    }

    public static boolean isSetupReady(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return getPlan(playerNpc, serverLevel).map(plan -> plan.phase().isReady()).orElse(false);
    }

    /**
     * Crop work may only consume a READY plan after infrastructure and every currently exposed
     * crop cell are valid. A crop already occupying a cell is left alone until its normal harvest;
     * the next scheduler pass then exposes and rewinds any damaged support to TILL.
     */
    public static boolean isReadyForCropWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return getPlan(playerNpc, serverLevel)
                .filter(plan -> plan.phase() == Phase.READY)
                .filter(plan -> hasIrrigationWater(serverLevel, plan))
                .filter(plan -> missingFenceCount(serverLevel, plan) == 0)
                .filter(plan -> hasGate(serverLevel, plan))
                .filter(plan -> hasStrictFarmGround(serverLevel, plan))
                .isPresent();
    }

    public static boolean hasStrictFarmGround(ServerLevel serverLevel, Plan plan) {
        if (serverLevel == null || plan == null) {
            return false;
        }
        Set<BlockPos> cropGround = Set.copyOf(plan.cropGroundPositions());
        for (BlockPos ground : plan.allGroundPositions()) {
            if (ground.equals(plan.waterPos())) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(ground);
            if (!cropGround.contains(ground)) {
                if (!isTillableGround(state)) {
                    return false;
                }
                continue;
            }
            if (serverLevel.getBlockState(ground.above()).getBlock() instanceof CropBlock) {
                continue;
            }
            if (!state.is(Blocks.FARMLAND)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isOwnedIrrigationWater(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos) {
        return getPlan(playerNpc, serverLevel).map(plan -> plan.waterPos().equals(pos)).orElse(false);
    }

    public static boolean isCropGround(Plan plan, BlockPos pos) {
        return plan != null
                && pos != null
                && plan.containsGround(pos)
                && !plan.waterPos().equals(pos)
                && !plan.isFarmPath(pos);
    }

    public static boolean isCropPosition(Plan plan, BlockPos pos) {
        return pos != null && isCropGround(plan, pos.below());
    }

    public static boolean isWithinWorkBounds(Plan plan, BlockPos pos) {
        return plan != null
                && pos != null
                && pos.getX() >= plan.origin().getX() - 2
                && pos.getX() <= plan.origin().getX() + plan.width() + 1
                && pos.getZ() >= plan.origin().getZ() - 2
                && pos.getZ() <= plan.origin().getZ() + plan.depth() + 1
                && pos.getY() >= plan.origin().getY()
                && pos.getY() <= plan.origin().getY() + 3;
    }

    public static boolean isInsideFarmFootprint(Plan plan, BlockPos pos) {
        return plan != null
                && pos != null
                && pos.getX() >= plan.origin().getX() - 1
                && pos.getX() <= plan.origin().getX() + plan.width()
                && pos.getZ() >= plan.origin().getZ() - 1
                && pos.getZ() <= plan.origin().getZ() + plan.depth();
    }

    public static boolean isInsideOwnedFarmFootprint(PlayerNpcEntity playerNpc, BlockPos pos) {
        return playerNpc != null
                && playerNpc.hasInterest(PlayerNpcInterest.FARMING)
                && pos != null
                && PlayerNpcFarmPlan.get(playerNpc)
                .map(plan -> isInsideFarmFootprint(plan, pos))
                .orElse(false);
    }

    /** Protects every block below the owned farm and a small perimeter buffer down to world minimum. */
    public static boolean isBelowOwnedFarmFootprint(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || !playerNpc.hasInterest(PlayerNpcInterest.FARMING) || pos == null) {
            return false;
        }
        return sameDimensionPlan(playerNpc).map(plan -> pos.getY() <= plan.origin().getY()
                && pos.getX() >= plan.origin().getX() - FARM_STONE_PROTECTION_BUFFER
                && pos.getX() <= plan.origin().getX() + plan.width() - 1 + FARM_STONE_PROTECTION_BUFFER
                && pos.getZ() >= plan.origin().getZ() - FARM_STONE_PROTECTION_BUFFER
                && pos.getZ() <= plan.origin().getZ() + plan.depth() - 1 + FARM_STONE_PROTECTION_BUFFER)
                .orElse(false);
    }

    /** X/Z work and entrance area used to move stone gathering away before it clears or mines. */
    public static boolean isInsideOwnedFarmWorkOrEntranceFootprint(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || !playerNpc.hasInterest(PlayerNpcInterest.FARMING) || pos == null) {
            return false;
        }
        return sameDimensionPlan(playerNpc).map(plan -> {
            boolean workBounds = pos.getX() >= plan.origin().getX() - 2
                    && pos.getX() <= plan.origin().getX() + plan.width() + 1
                    && pos.getZ() >= plan.origin().getZ() - 2
                    && pos.getZ() <= plan.origin().getZ() + plan.depth() + 1;
            int gateDx = Math.abs(pos.getX() - plan.gatePos().getX());
            int gateDz = Math.abs(pos.getZ() - plan.gatePos().getZ());
            return workBounds || gateDx + gateDz <= 2;
        }).orElse(false);
    }

    /** Protects the farm surface, perimeter, entrance, and shallow support from generic mining/build placement. */
    public static boolean isProtectedFarmlandBlock(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || !playerNpc.hasInterest(PlayerNpcInterest.FARMING) || pos == null) {
            return false;
        }
        return PlayerNpcFarmPlan.get(playerNpc).map(plan -> {
            if (!plan.dimension().isBlank()
                    && !playerNpc.level().dimension().identifier().toString().equals(plan.dimension())) {
                return false;
            }
            if (plan.owns(pos) || isEntranceCorridor(plan, pos)) {
                return true;
            }
            return pos.getX() >= plan.origin().getX() - 1
                    && pos.getX() <= plan.origin().getX() + plan.width()
                    && pos.getZ() >= plan.origin().getZ() - 1
                    && pos.getZ() <= plan.origin().getZ() + plan.depth()
                    && pos.getY() >= plan.origin().getY() - 3
                    && pos.getY() <= plan.origin().getY() + 2;
        }).orElse(false);
    }

    /**
     * Authoritative guard for generic destructive AI.  The surface ownership check protects the
     * prepared soil, crops, irrigation, path, and perimeter while the column check prevents a
     * mining/escape/descent goal from removing support underneath the farm.
     *
     * FarmSetupGoal and FarmCropGoal may bypass this only after validating an intentional owned
     * maintenance or harvest target themselves.
     */
    public static boolean isOwnedFarmDestructionProtected(PlayerNpcEntity playerNpc, BlockPos pos) {
        return isProtectedFarmlandBlock(playerNpc, pos) || isBelowOwnedFarmFootprint(playerNpc, pos);
    }

    public static List<BlockPos> farmTorchTargets(Plan plan) {
        if (plan == null) {
            return List.of();
        }
        List<BlockPos> fences = plan.fencePositions();
        if (fences.isEmpty()) {
            return List.of();
        }
        int targetCount = Math.min(MAX_FARM_TORCH_TARGETS, fences.size());
        Set<BlockPos> targets = new LinkedHashSet<>();
        for (int index = 0; index < targetCount; index++) {
            int fenceIndex = (int) ((long) index * fences.size() / targetCount);
            targets.add(fences.get(fenceIndex).above().immutable());
        }
        return List.copyOf(targets);
    }

    public static Optional<BlockPos> findPendingFarmTorchPlacement(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<Plan> plan = getPlan(playerNpc, serverLevel);
        if (plan.isEmpty() || plan.get().phase() != Phase.READY) {
            return Optional.empty();
        }
        BlockState torchState = Blocks.TORCH.defaultBlockState();
        return farmTorchTargets(plan.get()).stream()
                .filter(pos -> serverLevel.getBlockState(pos.below()).getBlock() instanceof FenceBlock)
                .filter(pos -> serverLevel.getBlockState(pos).canBeReplaced())
                .filter(pos -> serverLevel.getFluidState(pos).isEmpty())
                .filter(pos -> torchState.canSurvive(serverLevel, pos))
                .filter(pos -> serverLevel.getBrightness(LightLayer.BLOCK, pos) <= FARM_TORCH_LOW_LIGHT_LEVEL)
                .findFirst()
                .map(BlockPos::immutable);
    }

    public static boolean hasPendingFarmLighting(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return serverLevel != null
                && playerNpc != null
                && findPendingFarmTorchPlacement(serverLevel, playerNpc).isPresent();
    }

    public static boolean needsFarmTorchCharcoalSmelting(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return hasPendingFarmLighting(serverLevel, playerNpc)
                && !InventoryUtils.hasItem(playerNpc, Items.TORCH)
                && !PlayerNpcCraftingUtil.canCraftTorches(playerNpc.getInventory(), 0)
                && !InventoryUtils.hasItem(playerNpc, stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL))
                && ResourceAi.countLogs(playerNpc) >= FARM_TORCH_CHARCOAL_LOG_TARGET;
    }

    private static int requiredFarmLightingLogs(PlayerNpcEntity playerNpc) {
        if (InventoryUtils.hasItem(playerNpc, Items.TORCH)
                || PlayerNpcCraftingUtil.canCraftTorches(playerNpc.getInventory(), 0)) {
            return 0;
        }
        return InventoryUtils.hasItem(playerNpc, stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL))
                ? 1
                : FARM_TORCH_CHARCOAL_LOG_TARGET;
    }

    private static Optional<Plan> sameDimensionPlan(PlayerNpcEntity playerNpc) {
        if (playerNpc == null || !playerNpc.hasInterest(PlayerNpcInterest.FARMING)) {
            return Optional.empty();
        }
        return PlayerNpcFarmPlan.get(playerNpc).filter(plan -> plan.dimension().isBlank()
                || playerNpc.level().dimension().identifier().toString().equals(plan.dimension()));
    }

    public static boolean overlapsOwnedFarm(PlayerNpcEntity playerNpc, BlockPos origin, int width, int depth) {
        if (playerNpc == null || !playerNpc.hasInterest(PlayerNpcInterest.FARMING) || origin == null) {
            return false;
        }
        return PlayerNpcFarmPlan.get(playerNpc).map(plan -> {
            if (!plan.dimension().isBlank()
                    && !playerNpc.level().dimension().identifier().toString().equals(plan.dimension())) {
                return false;
            }
            int firstMinX = origin.getX() - 1;
            int firstMaxX = origin.getX() + width;
            int firstMinZ = origin.getZ() - 1;
            int firstMaxZ = origin.getZ() + depth;
            int farmMinX = plan.origin().getX() - 1;
            int farmMaxX = plan.origin().getX() + plan.width();
            int farmMinZ = plan.origin().getZ() - 1;
            int farmMaxZ = plan.origin().getZ() + plan.depth();
            return firstMaxX >= farmMinX && firstMinX <= farmMaxX
                    && firstMaxZ >= farmMinZ && firstMinZ <= farmMaxZ;
        }).orElse(false);
    }

    public static boolean isEntranceCorridor(Plan plan, BlockPos pos) {
        if (plan == null || pos == null
                || pos.getY() < plan.gatePos().getY()
                || pos.getY() > plan.gatePos().getY() + 1) {
            return false;
        }
        int dx = Math.abs(pos.getX() - plan.gatePos().getX());
        int dz = Math.abs(pos.getZ() - plan.gatePos().getZ());
        return dx + dz <= 2;
    }

    public static boolean isGrowingCrop(ServerLevel serverLevel, Plan plan, BlockPos pos) {
        if (serverLevel == null || !isCropPosition(plan, pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return state.getBlock() instanceof CropBlock cropBlock && !cropBlock.isMaxAge(state);
    }

    public static boolean isTillableGround(BlockState state) {
        return state != null
                && (state.getBlock() instanceof FarmlandBlock
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT));
    }

    public static BlockState tilledState(BlockState state) {
        if (state == null || state.getBlock() instanceof FarmlandBlock) {
            return state;
        }
        if (state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)) {
            return Blocks.DIRT.defaultBlockState();
        }
        return Blocks.FARMLAND.defaultBlockState();
    }

    public static boolean isFarmSeedOrCrop(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && (stack.is(Items.WHEAT_SEEDS)
                || stack.is(Items.CARROT)
                || stack.is(Items.POTATO)
                || stack.is(Items.BEETROOT_SEEDS));
    }

    public static boolean hasFarmSeedOrCrop(PlayerNpcEntity playerNpc) {
        return playerNpc != null && InventoryUtils.hasItem(playerNpc, FarmAi::isFarmSeedOrCrop);
    }

    public static boolean isForagePlant(BlockState state) {
        return state != null
                && (state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN));
    }

    public static boolean isHarvestableCrop(BlockState state) {
        return state != null
                && state.getBlock() instanceof CropBlock cropBlock
                && cropBlock.isMaxAge(state);
    }

    public static int missingFenceCount(ServerLevel serverLevel, Plan plan) {
        int missing = 0;
        for (BlockPos pos : plan.fencePositions()) {
            BlockState state = serverLevel.getBlockState(pos);
            if (!(state.getBlock() instanceof FenceBlock)) {
                missing++;
            }
        }
        return missing;
    }

    public static boolean hasGate(ServerLevel serverLevel, Plan plan) {
        if (!hasGateBlock(serverLevel, plan)) {
            return false;
        }
        Direction facing = expectedGateFacing(plan);
        BlockState state = serverLevel.getBlockState(plan.gatePos());
        if (facing == null
                || !state.hasProperty(FenceGateBlock.FACING)
                || state.getValue(FenceGateBlock.FACING) != facing
                || !serverLevel.getBlockState(plan.gatePos().below()).isSolidRender()) {
            return false;
        }
        Direction firstSide = facing.getClockWise();
        Direction secondSide = firstSide.getOpposite();
        return hasRequiredGateSide(serverLevel, plan, plan.gatePos().relative(firstSide))
                && hasRequiredGateSide(serverLevel, plan, plan.gatePos().relative(secondSide));
    }

    public static boolean hasGateBlock(ServerLevel serverLevel, Plan plan) {
        return serverLevel != null
                && plan != null
                && serverLevel.getBlockState(plan.gatePos()).getBlock() instanceof FenceGateBlock;
    }

    public static Direction expectedGateFacing(Plan plan) {
        if (plan == null || plan.containsGround(plan.gatePos().below())) {
            return null;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (plan.containsGround(plan.gatePos().relative(direction).below())) {
                return direction;
            }
        }
        return null;
    }

    private static boolean hasRequiredGateSide(ServerLevel serverLevel, Plan plan, BlockPos sidePos) {
        return !plan.isFencePosition(sidePos)
                || serverLevel.getBlockState(sidePos).getBlock() instanceof FenceBlock;
    }

    public static boolean hasIrrigationWater(ServerLevel serverLevel, Plan plan) {
        return plan != null
                && serverLevel.getFluidState(plan.waterPos()).isSource()
                && serverLevel.getBlockState(plan.waterPos()).is(Blocks.WATER);
    }

    private static int carriedFenceCount(PlayerNpcEntity playerNpc) {
        return ResourceAi.countHeldAndInventoryItems(
                playerNpc,
                stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceBlock
        );
    }

    private static boolean isPlanGeometryValid(PlayerNpcEntity playerNpc, Plan plan) {
        if (plan.width() < MIN_SIZE || plan.width() > MAX_SIZE || plan.depth() < MIN_SIZE || plan.depth() > MAX_SIZE
                || !plan.containsGround(plan.waterPos())
                || !isGateOnPerimeter(plan)) {
            return false;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return true;
        }
        for (BlockPos ground : plan.allGroundPositions()) {
            if (PlayerNpcHomeUtil.isInsideFootprint(home.get(), ground)) {
                return false;
            }
        }
        for (BlockPos fence : plan.fencePositions()) {
            if (PlayerNpcHomeUtil.isInsideFootprint(home.get(), fence)) {
                return false;
            }
        }
        return !PlayerNpcHomeUtil.isInsideFootprint(home.get(), plan.gatePos());
    }

    private static boolean canClaimPlan(PlayerNpcEntity playerNpc, ServerLevel serverLevel, Plan plan) {
        BlockPos farX = plan.origin().offset(plan.width(), 0, 0);
        BlockPos farZ = plan.origin().offset(0, 0, plan.depth());
        BlockPos farCorner = plan.origin().offset(plan.width(), 0, plan.depth());
        if (!isPlanGeometryValid(playerNpc, plan)
                || !serverLevel.hasChunkAt(plan.origin())
                || !serverLevel.hasChunkAt(farX)
                || !serverLevel.hasChunkAt(farZ)
                || !serverLevel.hasChunkAt(farCorner)) {
            return false;
        }
        for (BlockPos ground : plan.allGroundPositions()) {
            if (!isTillableGround(serverLevel.getBlockState(ground))
                    || !canClearSurfaceAt(serverLevel, ground.above())
                    || !canClearSurfaceAt(serverLevel, ground.above(2))) {
                return false;
            }
        }
        for (BlockPos fence : plan.fencePositions()) {
            if (!serverLevel.getBlockState(fence.below()).isSolidRender()
                    || !canClearSurfaceAt(serverLevel, fence)) {
                return false;
            }
        }
        return serverLevel.getBlockState(plan.gatePos().below()).isSolidRender()
                && canClearSurfaceAt(serverLevel, plan.gatePos())
                && hasReachableEntry(playerNpc, serverLevel, plan);
    }

    private static List<SiteOffset> createNearbySiteOffsets() {
        List<SiteOffset> offsets = new ArrayList<>();
        for (int dx = -MAX_SITE_SEARCH_RADIUS; dx <= MAX_SITE_SEARCH_RADIUS; dx++) {
            for (int dz = -MAX_SITE_SEARCH_RADIUS; dz <= MAX_SITE_SEARCH_RADIUS; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < MIN_SITE_CENTER_DISTANCE) {
                    continue;
                }
                offsets.add(new SiteOffset(dx, dz));
            }
        }
        offsets.sort(Comparator.comparingInt(SiteOffset::distanceSqr));
        return offsets;
    }

    public static boolean hasReachableEntry(PlayerNpcEntity playerNpc, ServerLevel serverLevel, Plan plan) {
        if (playerNpc == null || serverLevel == null || plan == null || plan.pathPositions().isEmpty()) {
            return false;
        }
        BlockPos entryStand = plan.pathPositions().get(0).above();
        if (!PathNavigationAi.canStandAt(serverLevel, entryStand)) {
            return false;
        }
        if (playerNpc.blockPosition().equals(entryStand)
                || playerNpc.distanceToSqr(entryStand.getX() + 0.5D, entryStand.getY(), entryStand.getZ() + 0.5D)
                <= 1.5D * 1.5D) {
            return true;
        }
        float pathBudget = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                ? OVERLOADED_PLAN_ENTRY_PATH_NODE_MULTIPLIER
                : PLAN_ENTRY_PATH_NODE_MULTIPLIER;
        Path path = PathNavigationAi.createBoundedPath(playerNpc, entryStand, pathBudget);
        Node end = path == null ? null : path.getEndNode();
        return path != null && path.canReach() && end != null && end.asBlockPos().equals(entryStand);
    }

    public static boolean canReplaceUnreachableUnpreparedPlan(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            Plan plan
    ) {
        if (plan.phase() == Phase.READY
                || plan.phase().ordinal() < Phase.CLEAR.ordinal()
                || !serverLevel.hasChunkAt(plan.origin())
                || !serverLevel.hasChunkAt(plan.origin().offset(plan.width() - 1, 0, plan.depth() - 1))
                || hasStartedFarmInfrastructure(serverLevel, plan)) {
            return false;
        }
        return true;
    }

    private static boolean hasStartedFarmInfrastructure(ServerLevel serverLevel, Plan plan) {
        if (hasIrrigationWater(serverLevel, plan) || hasGateBlock(serverLevel, plan)) {
            return true;
        }
        for (BlockPos fence : plan.fencePositions()) {
            if (serverLevel.getBlockState(fence).getBlock() instanceof FenceBlock) {
                return true;
            }
        }
        for (BlockPos ground : plan.cropGroundPositions()) {
            if (serverLevel.getBlockState(ground).getBlock() instanceof FarmlandBlock
                    || serverLevel.getBlockState(ground.above()).getBlock() instanceof CropBlock) {
                return true;
            }
        }
        return false;
    }

    private static boolean canClearSurfaceAt(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || state.getBlock() instanceof CropBlock) {
            return state.getFluidState().isEmpty();
        }
        return serverLevel.getBlockEntity(pos) == null
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && !state.is(BlockTags.WITHER_IMMUNE);
    }

    private static BlockPos chooseWaterPos(PlayerNpcEntity playerNpc, BlockPos origin, int width, int depth) {
        int firstX = Math.max(1, (width - 1) / 2);
        int secondX = Math.min(width - 2, width / 2);
        int firstZ = Math.max(1, (depth - 1) / 2);
        int secondZ = Math.min(depth - 2, depth / 2);
        int x = firstX == secondX || playerNpc.getRandom().nextBoolean() ? firstX : secondX;
        int z = firstZ == secondZ || playerNpc.getRandom().nextBoolean() ? firstZ : secondZ;
        return origin.offset(x, 0, z);
    }

    private static BlockPos chooseGatePos(BlockPos origin, int width, int depth, BlockPos anchor) {
        BlockPos center = origin.offset(width / 2, 0, depth / 2);
        int dx = anchor.getX() - center.getX();
        int dz = anchor.getZ() - center.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx < 0
                    ? origin.offset(-1, 1, depth / 2)
                    : origin.offset(width, 1, depth / 2);
        }
        return dz < 0
                ? origin.offset(width / 2, 1, -1)
                : origin.offset(width / 2, 1, depth);
    }

    private static boolean isGateOnPerimeter(Plan plan) {
        BlockPos gate = plan.gatePos();
        if (gate.getY() != plan.origin().getY() + 1 || plan.containsGround(gate.below())) {
            return false;
        }
        Direction facing = expectedGateFacing(plan);
        return facing != null
                && plan.isFencePosition(gate.relative(facing.getClockWise()))
                && plan.isFencePosition(gate.relative(facing.getCounterClockWise()));
    }

    private record SiteOffset(int dx, int dz) {
        private int distanceSqr() {
            return this.dx * this.dx + this.dz * this.dz;
        }
    }

    private static final class PlanSearchCursor {
        private final String dimension;
        private final BlockPos searchCenter;
        private final Set<Long> triedOrigins = new LinkedHashSet<>();
        private int nextOffsetIndex;
        private int totalAttempts;
        private boolean exhausted;

        private PlanSearchCursor(String dimension, BlockPos searchCenter) {
            this.dimension = dimension;
            this.searchCenter = searchCenter;
        }
    }
}
