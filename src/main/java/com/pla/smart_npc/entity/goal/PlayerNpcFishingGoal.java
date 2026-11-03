package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.PlayerNpcFishingBobberEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class PlayerNpcFishingGoal extends Goal {
    private static final int WATER_SCAN_RADIUS = 36;
    private static final int WATER_SCAN_RADIUS_SQR = WATER_SCAN_RADIUS * WATER_SCAN_RADIUS;
    private static final int SHORE_SCAN_RADIUS = 8;
    private static final int WATER_COLUMNS_PER_SEARCH = 192;
    private static final int MAX_WATER_SPOTS_PER_SEARCH = 12;
    private static final int SHORE_COLUMNS_PER_WATER = 32;
    private static final int MAX_TOTAL_PATH_CHECKS_PER_SEARCH = 8;
    private static final int FAILED_SEARCH_RETRY_MIN_TICKS = 20;
    private static final int FAILED_SEARCH_RETRY_RANDOM_TICKS = 20;
    private static final int CACHED_SPOT_TICKS = 20 * 5;
    private static final int SEARCH_ORIGIN_RESET_DISTANCE_SQR = 6 * 6;
    private static final int AIM_TICKS = 8;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_FISHING_TICKS = 20 * 90;
    private static final int STUCK_RETRIEVE_TICKS = 20;
    private static final int BITE_REACTION_TICKS = 8;
    private static final int BITE_REACTION_RANDOM_TICKS = 8;
    private static final int CATCH_COOLDOWN_TICKS = 20 * 4;
    private static final int CATCH_COOLDOWN_RANDOM_TICKS = 20 * 3;
    private static final int RETRY_COOLDOWN_TICKS = 20 * 2;
    private static final int RETRY_COOLDOWN_RANDOM_TICKS = 20 * 2;
    public static final int MAX_SAVED_FISHING_COOLDOWN_TICKS = CATCH_COOLDOWN_TICKS + CATCH_COOLDOWN_RANDOM_TICKS;
    private static final double ARRIVAL_DISTANCE_SQR = 1.75D * 1.75D;
    private static final double CAST_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double MIN_CAST_DISTANCE_SQR = 1.25D * 1.25D;
    private static final double PREFERRED_CAST_DISTANCE_MIN = 2.5D;
    private static final double PREFERRED_CAST_DISTANCE_MAX = 7.0D;
    private static final double CAST_EYE_HEIGHT = 1.35D;
    private static final int FISHABLE_WATER_PATCH_RADIUS = 1;
    private static final int PREFERRED_OPEN_WATER_PATCH_RADIUS = 2;
    private static final List<BlockPos> WATER_SCAN_OFFSETS = buildHorizontalOffsets(WATER_SCAN_RADIUS);
    private static final List<BlockPos> SHORE_SCAN_OFFSETS = buildHorizontalOffsets(SHORE_SCAN_RADIUS);
    private static final Map<PlayerNpcEntity, FishingSearchState> SEARCH_STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private FishingSpot fishingSpot;
    private PlayerNpcFishingBobberEntity bobber;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean usingTemporaryRod;
    private int fishingTicks;
    private int aimTicks;
    private int repathTicks;
    private int stuckTicks;
    private int biteReactionTicks;
    private int biteReactionTargetTicks;
    private boolean bobberWasCast;
    private boolean caughtFish;

    public PlayerNpcFishingGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean shouldExploreForFishingWater(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return isReadyForFishingWork(playerNpc, serverLevel);
    }

    public static boolean shouldStrollForMissingFishingString(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && serverLevel != null
                && playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING)
                && !serverLevel.isDarkOutside()
                && playerNpc.getUpwardEscapeTarget() == null
                && !hasFishingRod(playerNpc)
                && PlayerNpcCraftingUtil.countItem(playerNpc.getInventory(), stack -> stack.is(Items.STRING)) < 2
                && !playerNpc.shouldPrioritizeLogGathering()
                && !playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    public static boolean hasNearbyFishingSpot(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && serverLevel != null
                && findOrSearchFishingSpot(playerNpc, serverLevel, false) != null;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        String blockedReason = fishingWorkBlockedReason(this.playerNpc, serverLevel);
        if (blockedReason != null) {
            this.traceCanUseBlocked(blockedReason);
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        this.fishingSpot = findOrSearchFishingSpot(this.playerNpc, serverLevel, true);
        if (this.fishingSpot == null) {
            this.traceCanUseBlocked("fishing blocked: no reachable surface water; rod="
                    + fishingRodLocation(this.playerNpc)
                    + " pos="
                    + posText(this.playerNpc.blockPosition()));
        }
        return this.fishingSpot != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.fishingSpot != null
                && this.fishingTicks < MAX_FISHING_TICKS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.getTarget() == null
                && this.hasEquippedFishingRod();
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.fishingSpot == null || !this.equipRodIfNeeded()) {
            this.fishingSpot = null;
            return;
        }

        this.fishingTicks = 0;
        this.aimTicks = 0;
        this.repathTicks = 0;
        this.stuckTicks = 0;
        this.biteReactionTicks = 0;
        this.biteReactionTargetTicks = 0;
        this.bobberWasCast = false;
        this.caughtFish = false;
        this.bobber = null;
        this.playerNpc.setCurrentAiState("ai.player_npc.fishing");
        this.playerNpc.setCurrentAiDetail("walking to water @ " + posText(this.fishingSpot.waterPos()));
        this.moveToStand(serverLevel);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.fishingSpot == null) {
            this.stop();
            return;
        }

        this.fishingTicks++;
        this.lookAtWater();

        if (this.bobber == null || !this.bobber.isAlive()) {
            this.bobber = null;
            this.stuckTicks = 0;
            this.biteReactionTicks = 0;
            this.biteReactionTargetTicks = 0;
            if (!this.hasArrivedAtStand(serverLevel)) {
                if (this.repathTicks-- <= 0) {
                    this.moveToStand(serverLevel);
                    this.repathTicks = REPATH_INTERVAL_TICKS;
                }
                return;
            }

            this.playerNpc.getNavigation().stop();
            this.aimTicks++;
            this.playerNpc.setCurrentAiDetail("casting @ " + posText(this.fishingSpot.waterPos()));
            if (this.aimTicks >= AIM_TICKS && !this.castBobber(serverLevel)) {
                this.stop();
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.bobber.setInUse();
        this.playerNpc.getLookControl().setLookAt(this.bobber, 40.0F, 40.0F);
        this.playerNpc.setCurrentAiDetail("hook @ " + posText(this.bobber.blockPosition()));

        if (this.bobber.isReadyToCatch()) {
            this.caughtFish = true;
            if (this.biteReactionTargetTicks <= 0) {
                this.biteReactionTargetTicks = BITE_REACTION_TICKS + this.playerNpc.getRandom().nextInt(BITE_REACTION_RANDOM_TICKS + 1);
            }
            this.biteReactionTicks++;
            this.playerNpc.setCurrentAiDetail("bite @ " + posText(this.bobber.blockPosition()));
            if (this.biteReactionTicks >= this.biteReactionTargetTicks) {
                this.retrieveBobber();
                this.stop();
            }
            return;
        }

        this.biteReactionTicks = 0;
        this.biteReactionTargetTicks = 0;
        if (this.bobber.isStuck()) {
            this.stuckTicks++;
            if (this.stuckTicks >= STUCK_RETRIEVE_TICKS) {
                this.retrieveBobber();
                this.stop();
            }
        } else {
            this.stuckTicks = 0;
        }
    }

    @Override
    public void stop() {
        if (this.bobber != null && this.bobber.isAlive()) {
            this.bobber.discard();
        }
        this.bobber = null;

        if (this.usingTemporaryRod) {
            ItemStack rod = this.playerNpc.getMainHandItem().copy();
            if (!rod.isEmpty() && rod.canPerformAction(ItemAbilities.FISHING_ROD_CAST) && !InventoryUtils.addItem(this.playerNpc, rod)) {
                this.playerNpc.spawnAtLocation(rod);
            }
            this.playerNpc.setMainHandItemForAi(this.previousMainHand.copy());
        }

        if (!this.playerNpc.level().isClientSide()) {
            int cooldown = this.nextCooldownTicks();
            this.playerNpc.setFishingCooldown(cooldown);
        }

        this.fishingSpot = null;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryRod = false;
        this.fishingTicks = 0;
        this.aimTicks = 0;
        this.repathTicks = 0;
        this.stuckTicks = 0;
        this.biteReactionTicks = 0;
        this.biteReactionTargetTicks = 0;
        this.bobberWasCast = false;
        this.caughtFish = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean castBobber(ServerLevel serverLevel) {
        if (this.fishingSpot == null
                || !this.hasEquippedFishingRod()
                || !canCastFrom(this.playerNpc, serverLevel, this.playerNpc.blockPosition(), this.fishingSpot.waterPos())) {
            return false;
        }

        PlayerNpcFishingBobberEntity nextBobber = SmartNpcModEntities.PLAYER_NPC_FISHING_BOBBER.get().create(serverLevel, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
        if (nextBobber == null) {
            return false;
        }

        ItemStack rod = this.playerNpc.getMainHandItem();
        int luck = EnchantmentHelper.getFishingLuckBonus(serverLevel, rod, this.playerNpc);
        int lureSpeed = (int) (EnchantmentHelper.getFishingTimeReduction(serverLevel, rod, this.playerNpc) * 20.0F);
        this.lookAtWater();
        nextBobber.castFrom(this.playerNpc, this.fishingSpot.waterPos(), luck, lureSpeed);
        if (!serverLevel.addFreshEntity(nextBobber)) {
            return false;
        }

        this.bobber = nextBobber;
        this.bobberWasCast = true;
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(
                null,
                this.playerNpc.blockPosition(),
                SoundEvents.FISHING_BOBBER_THROW,
                SoundSource.NEUTRAL,
                0.5F,
                0.4F / (serverLevel.getRandom().nextFloat() * 0.4F + 0.8F)
        );
        return true;
    }

    private int nextCooldownTicks() {
        if (this.caughtFish) {
            return CATCH_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(CATCH_COOLDOWN_RANDOM_TICKS + 1);
        }
        if (this.bobberWasCast) {
            return RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(RETRY_COOLDOWN_RANDOM_TICKS + 1);
        }
        return this.playerNpc.getRandom().nextInt(RETRY_COOLDOWN_TICKS + 1);
    }

    private void retrieveBobber() {
        if (this.bobber == null) {
            return;
        }

        ItemStack rod = this.playerNpc.getMainHandItem();
        if (!rod.isEmpty() && rod.canPerformAction(ItemAbilities.FISHING_ROD_CAST)) {
            int rodDamage = this.bobber.retrieve(rod);
            if (rodDamage > 0) {
                this.playerNpc.hurtMainHandItem(rodDamage);
            }
        } else {
            this.bobber.discard();
        }

        this.playerNpc.triggerMainHandUseAnimation();
        this.playerNpc.level().playSound(null, this.playerNpc.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 0.9F, 1.0F);
        this.bobber = null;
    }

    public static boolean hasFishingRod(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && (isFishingRod(playerNpc.getMainHandItem())
                || isFishingRod(playerNpc.getOffhandItem())
                || InventoryUtils.hasItem(playerNpc, PlayerNpcFishingGoal::isFishingRod));
    }

    private boolean hasEquippedFishingRod() {
        return isFishingRod(this.playerNpc.getMainHandItem());
    }

    private boolean equipRodIfNeeded() {
        if (this.hasEquippedFishingRod()) {
            return true;
        }

        if (isFishingRod(this.playerNpc.getOffhandItem())) {
            ItemStack rod = this.playerNpc.getOffhandItem().copy();
            this.previousMainHand = this.playerNpc.getMainHandItem().copy();
            this.usingTemporaryRod = true;
            this.playerNpc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            this.playerNpc.setMainHandItemForAi(rod);
            return true;
        }

        ItemStack rod = this.playerNpc.consumeInventoryItem(PlayerNpcFishingGoal::isFishingRod, 1).orElse(ItemStack.EMPTY);
        if (rod.isEmpty()) {
            return false;
        }

        this.previousMainHand = this.playerNpc.getMainHandItem().copy();
        this.usingTemporaryRod = true;
        this.playerNpc.setMainHandItemForAi(rod);
        return true;
    }

    private static boolean isReadyForFishingWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return fishingWorkBlockedReason(playerNpc, serverLevel) == null;
    }

    private static String fishingWorkBlockedReason(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return "fishing blocked: missing npc or server level";
        }
        if (!playerNpc.isAlive()) {
            return "fishing blocked: npc not alive";
        }
        if (playerNpc.isNoAi()) {
            return "fishing blocked: no-ai enabled";
        }
        if (playerNpc.isPassenger()) {
            return "fishing blocked: passenger";
        }
        if (playerNpc.isHealing()) {
            return "fishing blocked: healing";
        }
        if (playerNpc.getTarget() != null) {
            return "fishing blocked: combat target";
        }
        if (!playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING)) {
            return "fishing blocked: fishing job inactive";
        }
        if (serverLevel.isDarkOutside()) {
            return "fishing blocked: night camp active";
        }
        if (playerNpc.getFishingCooldown() > 0) {
            return "fishing blocked: cooldown=" + playerNpc.getFishingCooldown();
        }
        if (playerNpc.getUpwardEscapeTarget() != null) {
            return "fishing blocked: upward escape active";
        }
        if (!hasFishingRod(playerNpc)) {
            return "fishing blocked: no usable carried rod; string="
                    + PlayerNpcCraftingUtil.countItem(playerNpc.getInventory(), stack -> stack.is(Items.STRING));
        }
        if (playerNpc.shouldPrioritizeLogGathering()
                && (GatherLogsGoal.isLogGatheringEpisodeActive(playerNpc)
                || playerNpc.getGatherCooldown() <= 0)) {
            return "fishing blocked: log support="
                    + ResourceAi.countLogs(playerNpc)
                    + "/"
                    + playerNpc.getLogSupplyGoal()
                    + (GatherLogsGoal.isLogGatheringEpisodeActive(playerNpc) ? " active" : " ready");
        }
        if (playerNpc.shouldPrioritizeCobblestoneGathering()
                && playerNpc.getGatherCooldown() <= 0) {
            return "fishing blocked: stone support="
                    + ResourceAi.countStone(playerNpc)
                    + "/"
                    + playerNpc.getStoneSupplyGoal()
                    + " ready";
        }
        return null;
    }

    private static String fishingRodLocation(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return "none";
        }
        if (isFishingRod(playerNpc.getMainHandItem())) {
            return "mainhand";
        }
        if (isFishingRod(playerNpc.getOffhandItem())) {
            return "offhand";
        }
        if (InventoryUtils.hasItem(playerNpc, PlayerNpcFishingGoal::isFishingRod)) {
            return "inventory";
        }
        return "none";
    }

    private void traceCanUseBlocked(String detail) {
        this.playerNpc.setIdleTraceDetail(detail, 20 * 2);
    }

    private static FishingSpot findOrSearchFishingSpot(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            boolean randomizeCastDistance
    ) {
        FishingSearchState state = SEARCH_STATES.computeIfAbsent(playerNpc, ignored -> new FishingSearchState());
        synchronized (state) {
            BlockPos center = playerNpc.blockPosition();
            ResourceKey<Level> dimension = serverLevel.dimension();
            if (!dimension.equals(state.dimension)
                    || state.searchOrigin == null
                    || horizontalDistanceSqr(center, state.searchOrigin) > SEARCH_ORIGIN_RESET_DISTANCE_SQR) {
                state.reset(center, dimension);
            }

            long gameTime = serverLevel.getGameTime();
            if (state.cachedSpot != null) {
                if (gameTime <= state.cachedSpotUntilTick
                        && isCachedFishingSpotValid(playerNpc, serverLevel, state.cachedSpot)) {
                    return state.cachedSpot;
                }
                state.cachedSpot = null;
            }
            if (gameTime < state.nextSearchTick) {
                return null;
            }

            FishingSpot found = searchFishingSpotBatch(playerNpc, serverLevel, state, randomizeCastDistance);
            if (found != null) {
                state.cachedSpot = found;
                state.cachedSpotUntilTick = gameTime + CACHED_SPOT_TICKS;
                state.nextSearchTick = gameTime;
                return found;
            }

            state.nextSearchTick = gameTime
                    + FAILED_SEARCH_RETRY_MIN_TICKS
                    + playerNpc.getRandom().nextInt(FAILED_SEARCH_RETRY_RANDOM_TICKS + 1);
            return null;
        }
    }

    private static FishingSpot searchFishingSpotBatch(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            FishingSearchState state,
            boolean randomizeCastDistance
    ) {
        BlockPos center = playerNpc.blockPosition();
        FishingSpot best = null;
        double bestScore = Double.MAX_VALUE;
        double preferredCastDistance = randomizeCastDistance
                ? Mth.nextDouble(playerNpc.getRandom(), PREFERRED_CAST_DISTANCE_MIN, PREFERRED_CAST_DISTANCE_MAX)
                : PREFERRED_CAST_DISTANCE_MIN;
        FishingSearchBudget budget = new FishingSearchBudget();
        int checkedColumns = 0;
        int checkedWaterSpots = 0;

        while (checkedColumns++ < WATER_COLUMNS_PER_SEARCH && !WATER_SCAN_OFFSETS.isEmpty()) {
            if (state.waterOffsetCursor >= WATER_SCAN_OFFSETS.size()) {
                state.waterOffsetCursor = 0;
            }
            BlockPos offset = WATER_SCAN_OFFSETS.get(state.waterOffsetCursor++);
            int x = state.searchOrigin.getX() + offset.getX();
            int z = state.searchOrigin.getZ() + offset.getZ();
            BlockPos columnPos = new BlockPos(x, center.getY(), z);
            if (!serverLevel.hasChunkAt(columnPos)) {
                continue;
            }

            BlockPos waterPos = findSurfaceWaterInColumn(serverLevel, x, z);
            if (waterPos == null || !isFishableWater(serverLevel, waterPos)) {
                continue;
            }
            if (checkedWaterSpots++ >= MAX_WATER_SPOTS_PER_SEARCH) {
                break;
            }

            BlockPos standPos = findStandNearWater(playerNpc, serverLevel, waterPos, budget);
            if (standPos == null) {
                continue;
            }

            double shoreDistance = horizontalDistanceSqr(standPos, waterPos);
            double castDistance = Math.sqrt(shoreDistance);
            double castDistancePenalty = randomizeCastDistance
                    ? Math.abs(castDistance - preferredCastDistance) * 8.0D
                    : shoreDistance * 2.0D;
            double verticalPenalty = Math.max(0, standPos.getY() - waterPos.getY() - 1) * 8.0D;
            double openWaterPenalty = hasFishableWaterPatch(serverLevel, waterPos, PREFERRED_OPEN_WATER_PATCH_RADIUS) ? 0.0D : 12.0D;
            double randomPenalty = randomizeCastDistance ? playerNpc.getRandom().nextDouble() * 4.0D : 0.0D;
            double score = center.distSqr(standPos) * 0.18D + castDistancePenalty + verticalPenalty + openWaterPenalty + randomPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = new FishingSpot(waterPos, standPos);
            }
        }

        return best;
    }

    private static boolean isCachedFishingSpotValid(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            FishingSpot spot
    ) {
        return playerNpc.blockPosition().distSqr(spot.standPos()) <= WATER_SCAN_RADIUS_SQR
                && isFishableWater(serverLevel, spot.waterPos())
                && isFishingStand(serverLevel, spot.standPos())
                && canCastFrom(playerNpc, serverLevel, spot.standPos(), spot.waterPos());
    }

    private static BlockPos findStandNearWater(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos waterPos,
            FishingSearchBudget budget
    ) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos current = playerNpc.blockPosition();
        if (isFishingStand(serverLevel, current) && canCastFrom(playerNpc, serverLevel, current, waterPos)) {
            candidates.add(current.immutable());
        }

        int shoreColumns = 0;
        for (BlockPos offset : SHORE_SCAN_OFFSETS) {
            if (shoreColumns++ >= SHORE_COLUMNS_PER_WATER) {
                break;
            }

            int x = waterPos.getX() + offset.getX();
            int z = waterPos.getZ() + offset.getZ();
            BlockPos columnPos = new BlockPos(x, waterPos.getY(), z);
            if (!serverLevel.hasChunkAt(columnPos)) {
                continue;
            }

            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!isFishingStand(serverLevel, candidate) || !canCastFrom(playerNpc, serverLevel, candidate, waterPos)) {
                continue;
            }
            candidates.add(candidate.immutable());
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> horizontalDistanceSqr(pos, waterPos))
                .thenComparingDouble(pos -> current.distSqr(pos)));

        for (BlockPos candidate : candidates) {
            if (playerNpc.distanceToSqr(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D) <= ARRIVAL_DISTANCE_SQR) {
                return candidate;
            }
            if (!budget.tryPathCheck()) {
                return null;
            }
            Path path = playerNpc.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                return candidate;
            }
        }
        return null;
    }

    private static BlockPos findSurfaceWaterInColumn(ServerLevel serverLevel, int x, int z) {
        int y = serverLevel.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (y < serverLevel.getMinY()) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        return isOpenSurfaceWater(serverLevel, pos) ? pos.immutable() : null;
    }

    private static boolean isFishingStand(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos)
                && serverLevel.canSeeSky(pos.above());
    }

    private static boolean canCastFrom(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos standPos, BlockPos waterPos) {
        if (standPos.getY() < waterPos.getY() - 1 || standPos.getY() > waterPos.getY() + 2) {
            return false;
        }

        double distanceSqr = Vec3.atCenterOf(standPos).distanceToSqr(Vec3.atCenterOf(waterPos));
        return distanceSqr >= MIN_CAST_DISTANCE_SQR
                && distanceSqr <= CAST_DISTANCE_SQR
                && hasClearCastRay(playerNpc, serverLevel, standPos, waterPos);
    }

    private static boolean hasClearCastRay(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos standPos, BlockPos waterPos) {
        Vec3 from = new Vec3(standPos.getX() + 0.5D, standPos.getY() + CAST_EYE_HEIGHT, standPos.getZ() + 0.5D);
        Vec3 to = new Vec3(waterPos.getX() + 0.5D, waterPos.getY() + 0.35D, waterPos.getZ() + 0.5D);
        BlockHitResult hit = serverLevel.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, playerNpc));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean isFishableWater(ServerLevel serverLevel, BlockPos pos) {
        return isOpenSurfaceWater(serverLevel, pos)
                && hasFishableWaterPatch(serverLevel, pos);
    }

    private static boolean hasFishableWaterPatch(ServerLevel serverLevel, BlockPos center) {
        return hasFishableWaterPatch(serverLevel, center, FISHABLE_WATER_PATCH_RADIUS);
    }

    private static boolean hasFishableWaterPatch(ServerLevel serverLevel, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!isOpenSurfaceWater(serverLevel, center.offset(dx, 0, dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isOpenSurfaceWater(ServerLevel serverLevel, BlockPos pos) {
        BlockPos above = pos.above();
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getFluidState(pos).is(FluidTags.WATER)
                && serverLevel.getFluidState(pos).isSource()
                && serverLevel.getFluidState(above).isEmpty()
                && serverLevel.getBlockState(above).getCollisionShape(serverLevel, above).isEmpty()
                && serverLevel.canSeeSky(above);
    }

    private void moveToStand(ServerLevel serverLevel) {
        if (this.fishingSpot == null) {
            return;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.fishingSpot.standPos(), 0);
        if (path != null && path.canReach()) {
            this.playerNpc.getNavigation().moveTo(path, 1.0D);
            return;
        }

        BlockPos standPos = this.fishingSpot.standPos();
        this.playerNpc.getNavigation().moveTo(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D, 1.0D);
    }

    private boolean hasArrivedAtStand(ServerLevel serverLevel) {
        if (this.fishingSpot == null) {
            return false;
        }

        BlockPos standPos = this.fishingSpot.standPos();
        BlockPos feet = this.playerNpc.blockPosition();
        if (!canCastFrom(this.playerNpc, serverLevel, feet, this.fishingSpot.waterPos())) {
            return false;
        }

        double dx = this.playerNpc.getX() - (standPos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (standPos.getZ() + 0.5D);
        return dx * dx + dz * dz <= 1.0D
                && Math.abs(feet.getY() - standPos.getY()) <= 1;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static List<BlockPos> buildHorizontalOffsets(int radius) {
        int radiusSqr = radius * radius;
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr == 0 || distanceSqr > radiusSqr) {
                    continue;
                }
                offsets.add(new BlockPos(dx, 0, dz));
            }
        }
        offsets.sort(Comparator.comparingInt(pos -> pos.getX() * pos.getX() + pos.getZ() * pos.getZ()));
        return List.copyOf(offsets);
    }

    private void lookAtWater() {
        if (this.fishingSpot == null) {
            return;
        }

        BlockPos waterPos = this.fishingSpot.waterPos();
        this.playerNpc.getLookControl().setLookAt(waterPos.getX() + 0.5D, waterPos.getY() + 0.15D, waterPos.getZ() + 0.5D, 40.0F, 40.0F);
    }

    private static boolean isFishingRod(ItemStack stack) {
        return !stack.isEmpty() && stack.canPerformAction(ItemAbilities.FISHING_ROD_CAST);
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static final class FishingSearchBudget {
        private int pathChecks;

        private boolean tryPathCheck() {
            if (this.pathChecks >= MAX_TOTAL_PATH_CHECKS_PER_SEARCH) {
                return false;
            }
            this.pathChecks++;
            return true;
        }
    }

    private static final class FishingSearchState {
        private ResourceKey<Level> dimension;
        private BlockPos searchOrigin;
        private int waterOffsetCursor;
        private long nextSearchTick;
        private FishingSpot cachedSpot;
        private long cachedSpotUntilTick;

        private void reset(BlockPos origin, ResourceKey<Level> dimension) {
            this.dimension = dimension;
            this.searchOrigin = origin.immutable();
            this.waterOffsetCursor = 0;
            this.nextSearchTick = 0L;
            this.cachedSpot = null;
            this.cachedSpotUntilTick = 0L;
        }
    }

    private record FishingSpot(BlockPos waterPos, BlockPos standPos) {}
}
