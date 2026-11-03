package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ReturnPositionAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;
import java.util.Optional;

public class ReturnHomeGoal extends Goal {
    private static final double MIN_DISTANCE_SQR = 28.0D * 28.0D;
    private static final double STOP_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double UTILITY_RETURN_DISTANCE_SQR = 48.0D * 48.0D;
    private static final double POST_STONE_RETURN_DISTANCE_SQR = 96.0D * 96.0D;
    private static final int MAX_RETURN_TICKS = 20 * 20;
    private static final int HOME_WORK_AREA_MARGIN = 4;
    private static final int HOME_SURFACE_ESCAPE_TICKS = 20 * 10;
    private static final int HOME_SURFACE_ESCAPE_EXTRA_BLOCKS = 6;

    private final PlayerNpcEntity playerNpc;
    private final ReturnPositionAi returnPositionAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos homeCenter;
    private int returnTicks;
    private boolean utilityReturn;
    private boolean buildReturn;
    private boolean shelterReturn;
    private boolean completedStoneTripReturn;
    private boolean explorationRecoveryReturn;
    private boolean homeSurfaceRecoveryReturn;
    private boolean constructionHandoff;

    public ReturnHomeGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.returnPositionAi = new ReturnPositionAi(playerNpc, speed);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean shouldSuppressExplorationForHome(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return false;
        }
        if (playerNpc.hasExplorationReturnHomeRequest()) {
            return true;
        }
        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        boolean miningShelterOnly = isMiningShelterOnly(playerNpc);
        if (needsHomeSurfaceRecovery(playerNpc, serverLevel, homeArea)) {
            return true;
        }
        if (hasActiveSupplyRoute(playerNpc)) {
            // A selected supply route owns movement until completion. Crossing the home work-area
            // boundary must not turn ready build work into a higher-priority return-home loop.
            return serverLevel.isDarkOutside() || serverLevel.isThundering();
        }
        if (!serverLevel.isDarkOutside()
                && !serverLevel.isThundering()
                && (PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(serverLevel, playerNpc).isPresent()
                || PlayerNpcBuildMaterialUtil.isMissingBuildMaterialSearchPending(playerNpc))) {
            // Any earliest-phase missing input is daytime gathering work.  Restricting this
            // release to non-primary materials made ReturnHome suppress GatherLogs while
            // ReturnHome.canUse simultaneously yielded to that same log shortage.
            return false;
        }
        if (miningShelterOnly && MiningNightCampGoal.shouldPauseMiningForNightCamp(playerNpc, serverLevel)) {
            return false;
        }
        if (isInsideHomeWorkArea(playerNpc, homeArea)) {
            return false;
        }
        if (shouldReturnAfterCompletedStoneTrip(playerNpc, serverLevel, homeArea)) {
            return !miningShelterOnly;
        }
        if (miningShelterOnly) {
            return serverLevel.isDarkOutside() || serverLevel.isThundering();
        }
        if (playerNpc.shouldPrioritizeLogGathering() || playerNpc.shouldPrioritizeCobblestoneGathering()) {
            return serverLevel.isDarkOutside() || serverLevel.isThundering();
        }
        return serverLevel.isDarkOutside()
                || serverLevel.isThundering()
                || (playerNpc.getReturnHomeCooldown() <= 0
                    && BuildHouseGoal.hasReadyHomeBuildWork(playerNpc, serverLevel))
                || PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, playerNpc)
                || PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, playerNpc);
    }

    /**
     * Cheap cross-goal guard for a builder that is already committed to an uphill home return.
     * This deliberately avoids blueprint, inventory, and path scans because descent rechecks it
     * during block breaking as well as goal admission.
     */
    public static boolean hasUphillBuilderReturnIntent(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)) {
            return false;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos feet = playerNpc.blockPosition();
        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        if (homeCenter.getY() <= feet.getY() + 1 || isInsideHomeWorkArea(playerNpc, homeArea)) {
            return false;
        }
        return "ai.player_npc.returning_home".equals(playerNpc.getCurrentAiState())
                || serverLevel.isDarkOutside()
                || serverLevel.isThundering()
                || playerNpc.hasExplorationReturnHomeRequest()
                || shouldReturnAfterCompletedStoneTrip(playerNpc, serverLevel, homeArea)
                || needsHomeSurfaceRecovery(playerNpc, serverLevel, homeArea);
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.playerNpc.getTarget() != null) {
            return false;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }
        this.constructionHandoff = false;

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        boolean forcedExplorationReturn = this.playerNpc.hasExplorationReturnHomeRequest();
        boolean forcedHomeSurfaceRecovery = needsHomeSurfaceRecovery(this.playerNpc, serverLevel, homeArea);
        if (!forcedExplorationReturn
                && !forcedHomeSurfaceRecovery
                && !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.homeArea = homeArea;
        this.homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        double distanceSqr = this.playerNpc.distanceToSqr(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D);
        boolean inventoryHalfFull = this.inventoryMoreThanHalfFull();
        boolean inventoryMostlyFull = this.inventoryMostlyFull();
        boolean miningShelterOnly = isMiningShelterOnly(this.playerNpc);
        boolean insideHomeWorkArea = this.isInsideHomeWorkArea(homeArea);
        if (miningShelterOnly
                && !forcedExplorationReturn
                && !forcedHomeSurfaceRecovery
                && MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            return false;
        }
        this.shelterReturn = this.shouldShelterAtHome(serverLevel);
        this.explorationRecoveryReturn = forcedExplorationReturn;
        this.homeSurfaceRecoveryReturn = forcedHomeSurfaceRecovery;
        if ("ai.player_npc.terraforming_build_site".equals(this.playerNpc.getCurrentAiState())
                && !this.shelterReturn
                && !this.explorationRecoveryReturn
                && !this.homeSurfaceRecoveryReturn) {
            // Terraform retains its bounded cursor between actionable site blocks. An ordinary
            // build/dig-site return must not take MOVE away mid-episode; combat and the explicit
            // safety returns above still preempt through their higher-priority paths.
            this.utilityReturn = false;
            this.buildReturn = false;
            this.completedStoneTripReturn = false;
            return false;
        }
        if (hasActiveSupplyRoute(this.playerNpc)
                && !this.shelterReturn
                && !this.explorationRecoveryReturn
                && !this.homeSurfaceRecoveryReturn) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.completedStoneTripReturn = false;
            return false;
        }
        // A build-readiness refresh scans blueprint/world state in admitted slices. It cannot make
        // this goal runnable while the NPC is already home or the ordinary return cooldown is
        // active, so avoid starting that work in those cases.
        boolean deferForBuildMaterialGathering = this.shouldDeferUtilityReturnForBuildMaterialGathering(
                serverLevel,
                inventoryMostlyFull,
                homeArea
        );
        this.buildReturn = !deferForBuildMaterialGathering
                && !miningShelterOnly
                && !insideHomeWorkArea
                && this.playerNpc.getReturnHomeCooldown() <= 0
                && BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel);
        this.completedStoneTripReturn = !miningShelterOnly && shouldReturnAfterCompletedStoneTrip(this.playerNpc, serverLevel, homeArea);
        if (miningShelterOnly
                && !this.shelterReturn
                && !this.explorationRecoveryReturn
                && !this.homeSurfaceRecoveryReturn) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.completedStoneTripReturn = false;
            return false;
        }
        if (this.homeSurfaceRecoveryReturn) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            return true;
        }
        if (this.explorationRecoveryReturn) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            if (this.hasReachedExplorationRecoveryHome(homeArea)) {
                this.playerNpc.clearExplorationReturnHomeRequest();
                return false;
            }
            return true;
        }
        if (insideHomeWorkArea && this.hasReadyConstructionWork(serverLevel)) {
            // Once safely back at the site, construction owns the builder turn. Continuing an
            // ordinary shelter return to the exact center would let this priority-4 goal starve
            // the priority-5 Terraform/BuildHouse goals.
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }
        if (this.shelterReturn && distanceSqr > STOP_DISTANCE_SQR && !insideHomeWorkArea) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return true;
        }

        if (this.completedStoneTripReturn && !insideHomeWorkArea) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return true;
        }

        if (this.buildReturn && !insideHomeWorkArea) {
            this.utilityReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return true;
        }

        if (!this.shelterReturn
                && !this.completedStoneTripReturn
                && this.needsBuildMaterialReserves()
                && !inventoryMostlyFull) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        if (deferForBuildMaterialGathering) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        if (this.playerNpc.getReturnHomeCooldown() > 0
                && !this.shelterReturn
                && !this.completedStoneTripReturn
                && !this.explorationRecoveryReturn
                && !this.homeSurfaceRecoveryReturn) {
            this.utilityReturn = false;
            this.buildReturn = false;
            return false;
        }

        this.utilityReturn = this.hasHomeUtilityWork(serverLevel, homeArea);
        if (!this.buildReturn && this.utilityReturn && distanceSqr > STOP_DISTANCE_SQR) {
            return true;
        }

        if (this.playerNpc.getReturnHomeCooldown() > 0) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        if (distanceSqr < MIN_DISTANCE_SQR) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        this.utilityReturn = false;
        this.buildReturn = false;
        this.shelterReturn = false;
        this.completedStoneTripReturn = false;
        this.explorationRecoveryReturn = false;
        this.homeSurfaceRecoveryReturn = false;
        boolean randomReturn = this.playerNpc.getRandom().nextFloat() < 0.35F;
        return inventoryHalfFull || randomReturn;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (this.shelterReturn && !this.shouldShelterAtHome(serverLevel)) {
            return false;
        }
        if (this.shelterReturn
                && this.hasReachedHomeWorkAreaSurface(this.homeArea)
                && this.hasReadyConstructionWork(serverLevel)) {
            this.constructionHandoff = true;
            return false;
        }
        if (!this.shelterReturn
                && !this.buildReturn
                && !this.completedStoneTripReturn
                && !this.explorationRecoveryReturn
                && !this.homeSurfaceRecoveryReturn
                && this.needsBuildMaterialReserves()
                && !this.inventoryMostlyFull()) {
            return false;
        }
        if ((this.buildReturn || this.completedStoneTripReturn) && this.hasReachedHomeWorkAreaSurface(this.homeArea)) {
            return false;
        }
        if (this.explorationRecoveryReturn && this.hasReachedExplorationRecoveryHome(this.homeArea)) {
            return false;
        }
        if (this.homeSurfaceRecoveryReturn && this.hasReachedHomeWorkAreaSurface(this.homeArea)) {
            return false;
        }

        return this.homeCenter != null
                && this.returnTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && this.playerNpc.distanceToSqr(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D) > STOP_DISTANCE_SQR;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.returnTicks = MAX_RETURN_TICKS;
        this.playerNpc.setCurrentAiState("ai.player_npc.returning_home");
        if (this.homeSurfaceRecoveryReturn && this.homeCenter != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.requestHomeSurfaceEscape(serverLevel);
        }
        if (this.homeCenter != null) {
            this.returnPositionAi.start(this.homeCenter);
        }
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        this.returnTicks--;
        if (this.homeCenter != null) {
            this.playerNpc.getLookControl().setLookAt(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D, 40.0F, 40.0F);
            if (this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)) {
                this.returnPositionAi.tickBuilderHomeReturn(
                        serverLevel,
                        this.homeCenter,
                        this::isProtectedHomeBlock,
                        this.moveDetail(),
                        "clearing return path");
            } else {
                this.returnPositionAi.tick(
                        serverLevel,
                        this.homeCenter,
                        this::isProtectedHomeBlock,
                        this.moveDetail(),
                        "clearing return path");
            }
            this.updateDetail();
        }
    }

    @Override
    public void stop() {
        boolean arrivedAtHome = this.homeCenter != null
                && (this.buildReturn
                || this.completedStoneTripReturn
                || this.homeSurfaceRecoveryReturn
                || this.constructionHandoff
                ? this.hasReachedHomeWorkAreaSurface(this.homeArea)
                : this.hasReachedHomeCenter());
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            int cooldown = !arrivedAtHome
                    ? 0
                    : this.utilityReturn
                    || this.buildReturn
                    || this.shelterReturn
                    || this.completedStoneTripReturn
                    || this.explorationRecoveryReturn
                    || this.homeSurfaceRecoveryReturn
                    || this.constructionHandoff
                    ? 20 * 12 + this.playerNpc.getRandom().nextInt(20 * 12)
                    : 20 * 60 + this.playerNpc.getRandom().nextInt(20 * 60);
            this.playerNpc.setReturnHomeCooldown(cooldown);
            if (!arrivedAtHome && this.homeSurfaceRecoveryReturn && this.homeCenter != null
                    && this.playerNpc.blockPosition().getY() < this.homeCenter.getY() - 1) {
                this.requestHomeSurfaceEscape(serverLevel);
            }
            if (this.explorationRecoveryReturn && arrivedAtHome) {
                this.playerNpc.clearExplorationReturnHomeRequest();
                this.playerNpc.setGatherCooldown(20 * 2);
            }
            if ((this.buildReturn || this.completedStoneTripReturn) && arrivedAtHome) {
                this.playerNpc.setBuildHouseCooldown(0);
                this.playerNpc.setManageHomeCooldown(0);
                if (this.buildReturn) {
                    this.playerNpc.setGatherCooldown(20 * 8);
                }
            }
        }
        this.returnPositionAi.stop();
        this.homeArea = null;
        this.homeCenter = null;
        this.returnTicks = 0;
        this.utilityReturn = false;
        this.buildReturn = false;
        this.shelterReturn = false;
        this.completedStoneTripReturn = false;
        this.explorationRecoveryReturn = false;
        this.homeSurfaceRecoveryReturn = false;
        this.constructionHandoff = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean isInsideHomeWorkArea(PlayerNpcHomeUtil.HomeArea homeArea) {
        if (homeArea == null) {
            return false;
        }

        return isInsideHomeWorkArea(this.playerNpc, homeArea);
    }

    static boolean isInsideHomeWorkArea(PlayerNpcEntity playerNpc, PlayerNpcHomeUtil.HomeArea homeArea) {
        return isInsideHomeWorkArea(playerNpc.blockPosition(), homeArea);
    }

    static boolean isInsideHomeWorkArea(BlockPos pos, PlayerNpcHomeUtil.HomeArea homeArea) {
        if (pos == null || homeArea == null) {
            return false;
        }
        return pos.getX() >= homeArea.origin().getX() - HOME_WORK_AREA_MARGIN
                && pos.getX() < homeArea.origin().getX() + homeArea.width() + HOME_WORK_AREA_MARGIN
                && pos.getZ() >= homeArea.origin().getZ() - HOME_WORK_AREA_MARGIN
                && pos.getZ() < homeArea.origin().getZ() + homeArea.depth() + HOME_WORK_AREA_MARGIN
                && pos.getY() >= homeArea.origin().getY()
                && pos.getY() <= homeArea.origin().getY() + 8;
    }

    public static boolean needsHomeSurfaceRecovery(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return false;
        }

        return PlayerNpcHomeUtil.getHome(playerNpc)
                .filter(homeArea -> needsHomeSurfaceRecovery(playerNpc, serverLevel, homeArea))
                .isPresent();
    }

    private static boolean needsHomeSurfaceRecovery(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            PlayerNpcHomeUtil.HomeArea homeArea
    ) {
        if (homeArea == null) {
            return false;
        }

        BlockPos pos = playerNpc.blockPosition();
        // Swimming lowers blockPosition by one block as the body bobs. Inside a home footprint
        // that must not masquerade as an underground/home-floor failure: ReturnHome has a higher
        // priority than gathering and would otherwise cancel a valid river crossing, request an
        // upward escape which the water safety code clears, then repeat from the same water cell.
        if (playerNpc.isInWater()
                || serverLevel.getFluidState(pos).is(FluidTags.WATER)
                || serverLevel.getFluidState(pos.above()).is(FluidTags.WATER)
                || !playerNpc.onGround() && serverLevel.getFluidState(pos.below()).is(FluidTags.WATER)) {
            return false;
        }
        if (!PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos)) {
            return false;
        }
        if (pos.getY() < homeArea.origin().getY()) {
            // Being one block below the blueprint origin is not sufficient proof of an
            // underground home. Terraform can intentionally expose or lower part of the site.
            // If the NPC can step onto an adjacent sky-visible stand, normal navigation and
            // construction/supply work must win instead of ReturnHome repeatedly requesting a
            // pillar which EscapeHole correctly rejects as a surface-origin climb.
            return !hasNearbySurfaceExitOrUnloadedNeighbor(serverLevel, pos);
        }
        if (pos.getY() != homeArea.origin().getY() || serverLevel.canSeeSky(pos.above())) {
            return false;
        }
        if (PathNavigationAi.canStandAt(serverLevel, pos)) {
            return false;
        }
        if (hasNearbySurfaceExitOrUnloadedNeighbor(serverLevel, pos)) {
            return false;
        }

        int localSurfaceY = serverLevel.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(),
                pos.getZ()
        );
        return localSurfaceY > pos.getY() + 1;
    }

    private static boolean hasNearbySurfaceExitOrUnloadedNeighbor(ServerLevel serverLevel, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos adjacent = feet.relative(direction).offset(0, dy, 0);
                if (!serverLevel.hasChunkAt(adjacent)) {
                    // Surface recovery must be proven from loaded local terrain. Conservatively
                    // leave an incomplete neighborhood to ordinary goals instead of loading a
                    // chunk or manufacturing a pillar request from partial evidence.
                    return true;
                }
                if (serverLevel.canSeeSky(adjacent.above())
                        && PathNavigationAi.canStandAt(serverLevel, adjacent)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasReachedExplorationRecoveryHome(PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.hasReachedHomeWorkAreaSurface(homeArea) && this.hasReachedHomeCenter();
    }

    private boolean hasReachedHomeWorkAreaSurface(PlayerNpcHomeUtil.HomeArea homeArea) {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return homeArea != null
                && this.isInsideHomeWorkArea(homeArea)
                && !needsHomeSurfaceRecovery(this.playerNpc, serverLevel, homeArea)
                && this.hasSafeHomeSurfaceStand();
    }

    private boolean hasSafeHomeSurfaceStand() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || !this.playerNpc.onGround()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        return serverLevel.getBlockState(feet).canBeReplaced()
                && serverLevel.getBlockState(feet).getFluidState().isEmpty()
                && serverLevel.getBlockState(head).canBeReplaced()
                && serverLevel.getBlockState(head).getFluidState().isEmpty()
                && serverLevel.getBlockState(floor).isSolidRender();
    }

    private boolean hasReachedHomeCenter() {
        return this.homeCenter != null
                && this.playerNpc.distanceToSqr(
                this.homeCenter.getX() + 0.5D,
                this.homeCenter.getY(),
                this.homeCenter.getZ() + 0.5D
        ) <= STOP_DISTANCE_SQR;
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        return this.homeArea != null
                && !this.homeSurfaceRecoveryReturn
                && (PlayerNpcHomeUtil.isInside(this.homeArea, pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos));
    }

    private void requestHomeSurfaceEscape(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos escapeTarget = this.findHomeSurfaceEscapeTarget(serverLevel, feet);
        int maxPillarBlocks = Math.max(1, escapeTarget.getY() - feet.getY() + HOME_SURFACE_ESCAPE_EXTRA_BLOCKS);
        this.playerNpc.requestForcedUpwardEscapeTo(escapeTarget, HOME_SURFACE_ESCAPE_TICKS, maxPillarBlocks);
    }

    private BlockPos findHomeSurfaceEscapeTarget(ServerLevel serverLevel, BlockPos feet) {
        if (this.homeArea != null && PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, feet)) {
            int insideEscapeY = Math.max(feet.getY() + 2, this.homeArea.origin().getY() + 2);
            return new BlockPos(feet.getX(), insideEscapeY, feet.getZ());
        }

        BlockPos nearestSurface = null;
        double nearestDistanceSqr = Double.MAX_VALUE;
        if (this.homeArea != null) {
            int minX = this.homeArea.origin().getX() - HOME_WORK_AREA_MARGIN;
            int maxX = this.homeArea.origin().getX() + this.homeArea.width() + HOME_WORK_AREA_MARGIN - 1;
            int minZ = this.homeArea.origin().getZ() - HOME_WORK_AREA_MARGIN;
            int maxZ = this.homeArea.origin().getZ() + this.homeArea.depth() + HOME_WORK_AREA_MARGIN - 1;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (candidate.getY() <= feet.getY() + 1
                            || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                            || !serverLevel.canSeeSky(candidate.above())
                            || !PathNavigationAi.canStandAt(serverLevel, candidate)) {
                        continue;
                    }

                    double distanceSqr = candidate.distSqr(feet);
                    if (distanceSqr < nearestDistanceSqr) {
                        nearestSurface = candidate.immutable();
                        nearestDistanceSqr = distanceSqr;
                    }
                }
            }
        }
        if (nearestSurface != null) {
            return nearestSurface;
        }

        int localSurfaceY = serverLevel.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                feet.getX(),
                feet.getZ()
        );
        int fallbackY = Math.max(Math.max(feet.getY() + 2, localSurfaceY), this.homeCenter.getY());
        return new BlockPos(feet.getX(), fallbackY, feet.getZ());
    }

    private void updateDetail() {
        this.playerNpc.setCurrentAiDetail(this.returnPositionAi.detail(this.moveDetail()));
    }

    private String moveDetail() {
        if (this.shelterReturn) {
            return "returning to home shelter";
        }
        if (this.buildReturn) {
            return "returning to build site";
        }
        if (this.completedStoneTripReturn) {
            return "returning from dig site";
        }
        if (this.explorationRecoveryReturn) {
            return "returning after failed exploration";
        }
        if (this.homeSurfaceRecoveryReturn) {
            return "returning to surface";
        }
        if (this.utilityReturn) {
            return "returning to home utility";
        }
        return "returning home";
    }

    private boolean shouldShelterAtHome(ServerLevel serverLevel) {
        return serverLevel.isDarkOutside() || serverLevel.isThundering();
    }

    private static boolean hasActiveSupplyRoute(PlayerNpcEntity playerNpc) {
        return GatherLogsGoal.isLogGatheringEpisodeActive(playerNpc)
                || GatherStoneGoal.isStoneGatheringEpisodeActive(playerNpc)
                || ExploreAroundGoal.isSupplyExplorationActive(playerNpc);
    }

    private boolean hasReadyConstructionWork(ServerLevel serverLevel) {
        return TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.isHomeBuildWorkSearchPending(this.playerNpc, serverLevel);
    }

    private boolean inventoryMoreThanHalfFull() {
        int used = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            if (!this.playerNpc.getInventory().getItem(i).isEmpty()) {
                used++;
            }
        }
        return used > this.playerNpc.getInventory().getContainerSize() / 2;
    }

    private boolean inventoryMostlyFull() {
        int freeSlots = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            if (this.playerNpc.getInventory().getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots <= 2;
    }

    private boolean needsBuildMaterialReserves() {
        return this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    private boolean shouldDeferUtilityReturnForBuildMaterialGathering(
            ServerLevel serverLevel,
            boolean inventoryMostlyFull,
            PlayerNpcHomeUtil.HomeArea homeArea
    ) {
        if (this.shouldShelterAtHome(serverLevel)) {
            return false;
        }

        boolean needsMaterialWork = this.needsBuildMaterialReserves()
                || PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, this.playerNpc)
                || PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, this.playerNpc)
                || GatherMissingBuildMaterialGoal.needsMissingBuildMaterial(this.playerNpc, serverLevel)
                || PlayerNpcBuildMaterialUtil.isMissingBuildMaterialSearchPending(this.playerNpc);
        if (!needsMaterialWork) {
            return false;
        }

        return !inventoryMostlyFull || !this.hasStorageWork(serverLevel, homeArea);
    }

    private static boolean shouldReturnAfterCompletedStoneTrip(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            PlayerNpcHomeUtil.HomeArea homeArea
    ) {
        if (playerNpc.shouldPrioritizeLogGathering()
                || playerNpc.shouldPrioritizeCobblestoneGathering()
                || isInsideHomeWorkArea(playerNpc, homeArea)) {
            return false;
        }

        BlockPos feet = playerNpc.blockPosition();
        int belowHomeBlocks = homeArea.origin().getY() - feet.getY();
        if (belowHomeBlocks < 3) {
            return false;
        }

        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        if (playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) > POST_STONE_RETURN_DISTANCE_SQR) {
            return false;
        }

        return !serverLevel.canSeeSky(feet.above()) || belowHomeBlocks >= 5;
    }

    private static boolean isMiningShelterOnly(PlayerNpcEntity playerNpc) {
        return playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING);
    }



    private boolean hasHomeUtilityWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.hasStorageWork(serverLevel, homeArea)
                || this.hasImportantHomeCraftingWork(serverLevel, homeArea)
                || this.hasCookingWork(serverLevel, homeArea)
                || this.hasSleepWork(serverLevel, homeArea);
    }

    private boolean hasStorageWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.inventoryMostlyFull()
                && (this.findBlock(serverLevel, homeArea, Blocks.CHEST) != null
                || InventoryUtils.hasItem(this.playerNpc, Items.CHEST)
                || PlayerNpcCraftingUtil.canCraftChest(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()));
    }

    private boolean hasImportantHomeCraftingWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        if (!this.canUseOrCreateHomeCraftingTable(serverLevel, homeArea)) {
            return false;
        }

        return this.canCraftMetalGear()
                || this.canCraftShield();
    }

    private boolean canUseOrCreateHomeCraftingTable(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.findBlock(serverLevel, homeArea, Blocks.CRAFTING_TABLE) != null
                || InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE)
                || PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
    }

    private boolean hasCookingWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        BlockPos furnacePos = this.findBlock(serverLevel, homeArea, Blocks.FURNACE);
        if (furnacePos == null) {
            return (InventoryUtils.hasItem(this.playerNpc, Items.FURNACE)
                    || PlayerNpcCraftingUtil.canCraftFurnace(this.playerNpc.getInventory()))
                    && (this.hasCookableFood()
                    || this.hasSmeltableMaterial(serverLevel)
                    || PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, this.playerNpc)
                    || PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc));
        }

        return (this.hasCookableFood()
                || this.hasSmeltableMaterial(serverLevel)
                || PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, this.playerNpc)
                || PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc)) && this.hasFuel()
                || serverLevel.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace
                && !furnace.getItem(2).isEmpty();
    }

    private boolean hasSleepWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return serverLevel.isDarkOutside()
                && this.playerNpc.getSleepCooldown() <= 0
                && this.findBed(serverLevel, homeArea) != null;
    }

    private boolean canCraftMetalGear() {
        return this.canCraftTool(Items.DIAMOND_PICKAXE, Items.DIAMOND, 3, 2)
                || this.canCraftTool(Items.DIAMOND_SWORD, Items.DIAMOND, 2, 1)
                || this.canCraftTool(Items.DIAMOND_AXE, Items.DIAMOND, 3, 2)
                || this.canCraftTool(Items.DIAMOND_SHOVEL, Items.DIAMOND, 1, 2)
                || this.canCraftArmor(EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.DIAMOND, 5)
                || this.canCraftArmor(EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8)
                || this.canCraftArmor(EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7)
                || this.canCraftArmor(EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.DIAMOND, 4)
                || this.canCraftTool(Items.IRON_PICKAXE, Items.IRON_INGOT, 3, 2)
                || this.canCraftTool(Items.IRON_AXE, Items.IRON_INGOT, 3, 2)
                || this.canCraftTool(Items.IRON_SWORD, Items.IRON_INGOT, 2, 1)
                || this.canCraftTool(Items.IRON_SHOVEL, Items.IRON_INGOT, 1, 2)
                || this.canCraftArmor(EquipmentSlot.HEAD, Items.IRON_HELMET, Items.IRON_INGOT, 5)
                || this.canCraftArmor(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8)
                || this.canCraftArmor(EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.IRON_INGOT, 7)
                || this.canCraftArmor(EquipmentSlot.FEET, Items.IRON_BOOTS, Items.IRON_INGOT, 4);
    }

    private boolean canCraftTool(ItemLike result, ItemLike material, int materialNeeded, int sticksNeeded) {
        return !this.hasItem(result)
                && this.countMaterial(material) >= materialNeeded
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded, this.playerNpc.getRawLogReserveTarget());
    }

    private boolean canCraftArmor(EquipmentSlot slot, ItemLike result, ItemLike material, int materialNeeded) {
        return !this.hasEquippedOrStoredItem(slot, result)
                && this.countMaterial(material) >= materialNeeded;
    }

    private boolean hasEquippedOrStoredItem(EquipmentSlot slot, ItemLike itemLike) {
        return this.playerNpc.getItemBySlot(slot).is(itemLike.asItem())
                || this.hasItem(itemLike);
    }

    private int countMaterial(ItemLike material) {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(material.asItem()));
    }

    private boolean canCraftShield() {
        return !this.hasShield()
                && PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 6
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.IRON_INGOT)) >= 1;
    }

    private boolean hasItem(net.minecraft.world.level.ItemLike itemLike) {
        return this.playerNpc.getMainHandItem().is(itemLike.asItem())
                || this.playerNpc.getOffhandItem().is(itemLike.asItem())
                || InventoryUtils.hasItem(this.playerNpc, itemLike);
    }

    private boolean hasShield() {
        return this.playerNpc.getOffhandItem().getItem() instanceof ShieldItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof ShieldItem);
    }

    private boolean hasCookableFood() {
        return InventoryUtils.hasItem(this.playerNpc, this::isCookableFood);
    }

    private boolean hasSmeltableMaterial(ServerLevel serverLevel) {
        return InventoryUtils.hasItem(this.playerNpc, stack -> this.isSmeltableMaterial(stack, serverLevel));
    }

    private boolean hasFuel() {
        return InventoryUtils.hasItem(this.playerNpc, this::isFuel);
    }

    private boolean isCookableFood(ItemStack stack) {
        return stack.is(Items.BEEF)
                || stack.is(Items.PORKCHOP)
                || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON)
                || stack.is(Items.COD)
                || stack.is(Items.SALMON)
                || stack.is(Items.POTATO);
    }

    private boolean isSmeltableMaterial(ItemStack stack, ServerLevel serverLevel) {
        return PlayerNpcBuildMaterialUtil.isGlassSmeltingInput(stack)
                && PlayerNpcBuildMaterialUtil.needsGlassSmelting(serverLevel, this.playerNpc)
                || stack.is(Items.RAW_IRON)
                || stack.is(Items.RAW_COPPER)
                || stack.is(Items.RAW_GOLD)
                || stack.is(Items.IRON_ORE)
                || stack.is(Items.DEEPSLATE_IRON_ORE)
                || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)
                || stack.is(Items.GOLD_ORE)
                || stack.is(Items.DEEPSLATE_GOLD_ORE);
    }

    private boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && this.playerNpc.level().fuelValues().isFuel(stack);
    }

    private BlockPos findBlock(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findBed(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).getBlock() instanceof BedBlock) {
                return pos.immutable();
            }
        }
        return null;
    }
}
