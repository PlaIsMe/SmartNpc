package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.SneakingAi;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class BeingAtHomeGoal extends Goal {
    private static final double AFK_REACHED_DISTANCE_SQR = 1.5D * 1.5D;
    private static final int MIN_AFK_TICKS = 20 * 12;
    private static final int MAX_AFK_TICKS = 20 * 35;
    private static final int REPATH_INTERVAL_TICKS = 20 * 2;
    private static final int SHORT_COOLDOWN_TICKS = 20 * 35;
    private static final int BASE_COOLDOWN_TICKS = 20 * 90;
    private static final int RANDOM_COOLDOWN_TICKS = 20 * 150;
    private static final float DAY_LAZY_START_CHANCE = 0.08F;
    private static final double HOME_DOOR_OPEN_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double HOME_ENTRY_DOOR_OPEN_DISTANCE_SQR = 16.0D * 16.0D;
    private static final int HOME_ENTRY_SAFE_DROP_BLOCKS = 3;
    private static final int MIN_STATIONARY_WALK_PAUSE_TICKS = 20 * 3;
    private static final int RANDOM_STATIONARY_WALK_PAUSE_TICKS = 20 * 6;
    private static final int MIN_LOOK_AROUND_TICKS = 20;
    private static final int RANDOM_LOOK_AROUND_TICKS = 20 * 3;
    private static final int MIN_HOME_ACTIVITY_TICKS = 20 * 4;
    private static final int RANDOM_HOME_ACTIVITY_TICKS = 20 * 7;
    private static final float WALK_SNEAK_CHANCE = 0.35F;
    private static final int BUILD_WORK_CHECK_INTERVAL_TICKS = 20 * 3;

    private final PlayerNpcEntity playerNpc;
    private final SneakingAi sneakingAi;
    private final PathNavigationAi pathNavigationAi;
    private final PathStuckFallbackAi pathStuckFallbackAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(20);
    private final double speed;
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos afkPos;
    private int afkTicks;
    private int cooldownTicks;
    private int repathTicks;
    private boolean reachedSpot;
    private boolean sheltering;
    private boolean utilitySpot;
    private ActivityMode activityMode = ActivityMode.LOOK;
    private int activityTicks;
    private int lookTicks;
    private int stationaryTicks;
    private int buildWorkCheckCooldown;
    private boolean walkSneaking;
    private boolean cachedReadyBuildWork;
    private BlockPos homeEntranceApproachPos;
    private boolean routingViaHomeEntrance;

    public BeingAtHomeGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.sneakingAi = new SneakingAi(playerNpc);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.pathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.speed = speed;
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
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0) {
            return false;
        }

        boolean shelterNow = this.shouldShelterAtHome(serverLevel);
        if (this.shouldHuntMonstersTonight(serverLevel)) {
            return false;
        }
        if (this.cooldownTicks > 0 && !shelterNow) {
            this.cooldownTicks--;
            return false;
        }
        if (shelterNow) {
            this.cooldownTicks = 0;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> savedHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (savedHome.isEmpty()) {
            return false;
        }

        this.homeArea = savedHome.get();
        this.sheltering = shelterNow;
        if (!ReturnHomeGoal.isInsideHomeWorkArea(this.playerNpc, this.homeArea)
                || ReturnHomeGoal.needsHomeSurfaceRecovery(this.playerNpc, serverLevel)) {
            return false;
        }
        // Night/thunder is now the builder's placement window. Shelter activity may only claim
        // MOVE after both Terraform and BuildHouse have resolved that no runnable work exists.
        if (this.hasReadyHomeWork(serverLevel, true)) {
            return false;
        }
        if (!this.sheltering
                && (this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.shouldPrioritizeCobblestoneGathering())) {
            return false;
        }
        if (!this.sheltering && !this.isFinishedHouse(serverLevel, this.homeArea)) {
            this.cooldownTicks = SHORT_COOLDOWN_TICKS;
            return false;
        }

        if (!this.sheltering && this.playerNpc.getRandom().nextFloat() >= DAY_LAZY_START_CHANCE) {
            this.cooldownTicks = SHORT_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(SHORT_COOLDOWN_TICKS);
            return false;
        }

        this.afkPos = this.findAfkPos(serverLevel, this.homeArea);
        if (this.afkPos == null) {
            this.cooldownTicks = SHORT_COOLDOWN_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.homeArea != null
                && ReturnHomeGoal.isInsideHomeWorkArea(this.playerNpc, this.homeArea)
                && !ReturnHomeGoal.needsHomeSurfaceRecovery(this.playerNpc, serverLevel)
                && (this.sheltering || this.afkTicks > 0)
                && this.afkPos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && (this.sheltering
                || !this.playerNpc.shouldPrioritizeLogGathering()
                && !this.playerNpc.shouldPrioritizeCobblestoneGathering())
                && !this.hasReadyHomeWork()
                && (!this.sheltering || this.shouldShelterNow());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.afkTicks = MIN_AFK_TICKS + this.playerNpc.getRandom().nextInt(MAX_AFK_TICKS - MIN_AFK_TICKS + 1);
        this.repathTicks = 0;
        this.activityTicks = this.nextActivityTicks();
        this.lookTicks = 0;
        this.stationaryTicks = this.nextStationaryTicks();
        this.buildWorkCheckCooldown = BUILD_WORK_CHECK_INTERVAL_TICKS;
        this.cachedReadyBuildWork = false;
        this.walkSneaking = false;
        this.pathStuckFallbackAi.stop();
        this.homeEntranceApproachPos = this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.homeArea != null
                ? this.findHomeEntranceApproach(serverLevel, this.homeArea)
                : null;
        this.routingViaHomeEntrance = this.homeEntranceApproachPos != null
                && this.homeArea != null
                && !PlayerNpcHomeUtil.isInside(this.homeArea, this.playerNpc.blockPosition());
        this.activityMode = this.randomActivityMode();
        this.reachedSpot = this.isAtAfkPos();
        this.playerNpc.setCurrentAiState("ai.player_npc.being_at_home");
        this.updateDetail();
        if (this.reachedSpot) {
            this.playerNpc.getNavigation().stop();
        } else {
            this.moveToAfkPos();
        }
    }

    @Override
    public void tick() {
        if (this.afkPos == null) {
            return;
        }

        if (!this.sheltering) {
            this.afkTicks--;
        }
        if (!this.isAtAfkPos()) {
            this.reachedSpot = false;
            if (this.routingViaHomeEntrance && this.isAtHomeEntranceApproach()) {
                this.routingViaHomeEntrance = false;
                this.pathStuckFallbackAi.stop();
                this.repathTicks = 0;
                this.moveToAfkPos();
            }
            if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
                BlockPos routeTarget = this.currentHomeRouteTarget();
                BlockPos directionTarget = this.homeEntranceApproachPos == null
                        ? routeTarget
                        : this.homeEntranceApproachPos;
                String moveDetail = this.utilitySpot ? "walking to home utility" : "walking to indoor spot";
                if (this.pathStuckFallbackAi.watchAndStartWhileNavigating(
                        serverLevel,
                        routeTarget,
                        directionTarget,
                        moveDetail,
                        pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos))) {
                    if (this.homeEntranceApproachPos != null) {
                        this.routingViaHomeEntrance = true;
                    }
                    this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(moveDetail));
                    return;
                }
            }
            if (this.repathTicks-- <= 0) {
                this.moveToAfkPos();
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        this.reachedSpot = true;
        this.routingViaHomeEntrance = false;
        this.pathStuckFallbackAi.stop();
        this.tickHomeActivity();
        this.updateDetail();
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide()) {
            this.cooldownTicks = this.sheltering
                    ? 20 * 5 + this.playerNpc.getRandom().nextInt(20 * 5)
                    : BASE_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_COOLDOWN_TICKS);
        }
        this.homeArea = null;
        this.afkPos = null;
        this.afkTicks = 0;
        this.repathTicks = 0;
        this.reachedSpot = false;
        this.sheltering = false;
        this.utilitySpot = false;
        this.activityMode = ActivityMode.LOOK;
        this.activityTicks = 0;
        this.lookTicks = 0;
        this.stationaryTicks = 0;
        this.buildWorkCheckCooldown = 0;
        this.cachedReadyBuildWork = false;
        this.walkSneaking = false;
        this.homeEntranceApproachPos = null;
        this.routingViaHomeEntrance = false;
        this.pathStuckFallbackAi.stop();
        this.playerNpc.getNavigation().stop();
        this.stopCustomHomeIdleAnimation(this.playerNpc);
        this.sneakingAi.stopSneaking();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean isFinishedHouse(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()) {
            return this.hasBasicHomeAmenities(serverLevel, homeArea);
        }
        return BuildHouseGoal.isHomeLayoutFinished(this.playerNpc, serverLevel);
    }

    private BlockPos findAfkPos(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        this.utilitySpot = false;
        double doorOpenDistance = PlayerNpcHomeUtil.isInside(homeArea, this.playerNpc.blockPosition())
                ? HOME_DOOR_OPEN_DISTANCE_SQR
                : HOME_ENTRY_DOOR_OPEN_DISTANCE_SQR;
        this.openNearbyHomeDoor(serverLevel, homeArea, doorOpenDistance);
        List<BlockPos> utilityCandidates = new ArrayList<>();
        this.addUtilityStandCandidates(serverLevel, homeArea, utilityCandidates);
        if (!utilityCandidates.isEmpty()) {
            this.utilitySpot = true;
            return utilityCandidates.get(this.playerNpc.getRandom().nextInt(utilityCandidates.size()));
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = 1; x < homeArea.width() - 1; x++) {
            for (int z = 1; z < homeArea.depth() - 1; z++) {
                BlockPos candidate = PlayerNpcHomeUtil.interiorPos(homeArea, x, z);
                if (this.canStandAt(serverLevel, candidate) && this.canReachOrAlreadyAt(serverLevel, homeArea, candidate)) {
                    candidates.add(candidate.immutable());
                }
            }
        }

        if (candidates.isEmpty()) {
            return this.sheltering ? this.findHomeAreaFallbackPos(serverLevel, homeArea) : null;
        }
        return candidates.get(this.playerNpc.getRandom().nextInt(candidates.size()));
    }

    private BlockPos findHomeAreaFallbackPos(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        BlockPos current = this.playerNpc.blockPosition();
        BlockPos homeCenter = PlayerNpcHomeUtil.center(homeArea);
        if (this.canStandAt(serverLevel, current)
                && ReturnHomeGoal.isInsideHomeWorkArea(current, homeArea)) {
            return current.immutable();
        }

        List<BlockPos> candidates = new ArrayList<>();
        int radius = Math.max(4, Math.min(8, Math.max(homeArea.width(), homeArea.depth()) + 2));
        for (int x = homeCenter.getX() - radius; x <= homeCenter.getX() + radius; x++) {
            for (int z = homeCenter.getZ() - radius; z <= homeCenter.getZ() + radius; z++) {
                for (int y = homeCenter.getY() - 2; y <= homeCenter.getY() + 4; y++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (ReturnHomeGoal.isInsideHomeWorkArea(candidate, homeArea)
                            && this.canStandAt(serverLevel, candidate)
                            && this.canReachOrAlreadyAt(serverLevel, homeArea, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(homeCenter))
                .thenComparingDouble(this::distanceToPosSqr));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void addUtilityStandCandidates(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, List<BlockPos> candidates) {
        if (this.addUtilityStandCandidates(serverLevel, homeArea, candidates, this::isBedUtility)) {
            return;
        }
        if (this.addUtilityStandCandidates(serverLevel, homeArea, candidates, this::isFurnaceUtility)) {
            return;
        }
        this.addUtilityStandCandidates(serverLevel, homeArea, candidates, this::isCraftingUtility);
    }

    private boolean addUtilityStandCandidates(
            ServerLevel serverLevel,
            PlayerNpcHomeUtil.HomeArea homeArea,
            List<BlockPos> candidates,
            Predicate<BlockState> utilityPredicate) {
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 6, homeArea.depth() - 1))) {
            BlockState state = serverLevel.getBlockState(pos);
            if (!utilityPredicate.test(state)) {
                continue;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                this.addAfkCandidate(serverLevel, homeArea, pos.relative(direction), candidates);
            }
        }
        candidates.sort(Comparator.comparingDouble(this::distanceToPosSqr));
        return !candidates.isEmpty();
    }

    private boolean isHomeUtility(BlockState state) {
        return state.getBlock() instanceof BedBlock
                || state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.BARREL)
                || state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER);
    }

    private boolean isBedUtility(BlockState state) {
        return state.getBlock() instanceof BedBlock;
    }

    private boolean isFurnaceUtility(BlockState state) {
        return state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER);
    }

    private boolean isCraftingUtility(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE);
    }

    private void addAfkCandidate(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, BlockPos candidate, List<BlockPos> candidates) {
        BlockPos immutable = candidate.immutable();
        if (candidates.contains(immutable)
                || !PlayerNpcHomeUtil.isInside(homeArea, immutable)
                || !this.canStandAt(serverLevel, immutable)
                || !this.canReachOrAlreadyAt(serverLevel, homeArea, immutable)) {
            return;
        }

        candidates.add(immutable);
    }

    private boolean canReachOrAlreadyAt(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, BlockPos pos) {
        if (this.distanceToPosSqr(pos) <= AFK_REACHED_DISTANCE_SQR) {
            return true;
        }
        if (homeArea != null
                && PlayerNpcHomeUtil.isInside(homeArea, this.playerNpc.blockPosition())
                && PlayerNpcHomeUtil.isInside(homeArea, pos)) {
            return true;
        }

        // Candidate enumeration can visit every utility/interior stand. Prebuilding a path for
        // each candidate made a nominal idle activation an unbounded navigation batch. The goal
        // builds one route to the selected stand in moveToAfkPos and its stuck fallback handles a
        // failed choice, so selection only needs to reject unloaded positions here.
        return serverLevel.hasChunkAt(pos);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)) {
            return false;
        }

        BlockState feet = serverLevel.getBlockState(pos);
        BlockState head = serverLevel.getBlockState(pos.above());
        BlockPos floorPos = pos.below();
        return feet.getCollisionShape(serverLevel, pos).isEmpty()
                && head.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && serverLevel.getBlockState(floorPos).isSolidRender();
    }

    private void moveToAfkPos() {
        if (this.afkPos == null) {
            return;
        }
        if (this.playerNpc.level() instanceof ServerLevel serverLevel && this.homeArea != null) {
            double doorOpenDistance = PlayerNpcHomeUtil.isInside(this.homeArea, this.playerNpc.blockPosition())
                    ? HOME_DOOR_OPEN_DISTANCE_SQR
                    : HOME_ENTRY_DOOR_OPEN_DISTANCE_SQR;
            this.openNearbyHomeDoor(serverLevel, this.homeArea, doorOpenDistance);
        }
        if (this.activityMode == ActivityMode.WALK && this.walkSneaking) {
            this.sneakingAi.setSneaking(true);
        } else {
            this.sneakingAi.setSneaking(false);
        }

        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.pathNavigationAi.moveTo(
                    serverLevel,
                    this.currentHomeRouteTarget(),
                    this.speed,
                    HOME_ENTRY_SAFE_DROP_BLOCKS);
        }
    }

    private BlockPos currentHomeRouteTarget() {
        return this.routingViaHomeEntrance && this.homeEntranceApproachPos != null
                ? this.homeEntranceApproachPos
                : this.afkPos;
    }

    private boolean isAtHomeEntranceApproach() {
        return this.homeEntranceApproachPos != null
                && this.distanceToPosSqr(this.homeEntranceApproachPos) <= AFK_REACHED_DISTANCE_SQR;
    }

    private BlockPos findHomeEntranceApproach(
            ServerLevel serverLevel,
            PlayerNpcHomeUtil.HomeArea homeArea
    ) {
        List<BlockPos> outsideCandidates = new ArrayList<>();
        List<BlockPos> relaxedCandidates = new ArrayList<>();
        for (BlockPos doorPos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 6, homeArea.depth() - 1))) {
            if (!(serverLevel.getBlockState(doorPos).getBlock() instanceof DoorBlock)) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = doorPos.relative(direction).immutable();
                if (!this.canStandAt(serverLevel, candidate)
                        || outsideCandidates.contains(candidate)
                        || relaxedCandidates.contains(candidate)) {
                    continue;
                }
                if (PlayerNpcHomeUtil.isInsideFootprint(homeArea, candidate)) {
                    relaxedCandidates.add(candidate);
                } else {
                    outsideCandidates.add(candidate);
                }
            }
        }

        Comparator<BlockPos> nearestFirst = Comparator.comparingDouble(this::distanceToPosSqr);
        outsideCandidates.sort(nearestFirst);
        relaxedCandidates.sort(nearestFirst);
        if (!outsideCandidates.isEmpty()) {
            return outsideCandidates.get(0);
        }
        return relaxedCandidates.isEmpty() ? null : relaxedCandidates.get(0);
    }

    private boolean isAtAfkPos() {
        return this.afkPos != null && this.distanceToPosSqr(this.afkPos) <= AFK_REACHED_DISTANCE_SQR;
    }

    private double distanceToPosSqr(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    private void updateDetail() {
        if (!this.reachedSpot) {
            if (this.routingViaHomeEntrance) {
                this.playerNpc.setCurrentAiDetail("walking around home to entrance");
            } else {
                this.playerNpc.setCurrentAiDetail(this.utilitySpot ? "walking to home utility" : "walking to indoor spot");
            }
            return;
        }

        this.playerNpc.setCurrentAiDetail(switch (this.activityMode) {
            case SNEAK -> "sneaking inside home";
            case WALK -> this.stationaryTicks > 0 ? "looking around home" : "walking inside home";
            case LOOK -> this.utilitySpot ? "standing by home utility" : "watching inside home";
        });
    }

    private boolean shouldShelterNow() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.shouldShelterAtHome(serverLevel);
    }

    private boolean shouldShelterAtHome(ServerLevel serverLevel) {
        return (serverLevel.isDarkOutside() || serverLevel.isThundering())
                && !this.shouldHuntMonstersTonight(serverLevel);
    }

    private boolean shouldHuntMonstersTonight(ServerLevel serverLevel) {
        return serverLevel.isDarkOutside()
                && this.playerNpc.hasInterest(PlayerNpcInterest.HUNT_MONSTERS);
    }

    private boolean hasReadyHomeWork() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.hasReadyHomeWork(serverLevel, false);
    }

    private boolean hasReadyHomeWork(ServerLevel serverLevel, boolean force) {
        if (!force && this.buildWorkCheckCooldown > 0) {
            this.buildWorkCheckCooldown--;
            return this.cachedReadyBuildWork;
        }

        this.buildWorkCheckCooldown = BUILD_WORK_CHECK_INTERVAL_TICKS + this.playerNpc.getRandom().nextInt(6);
        this.cachedReadyBuildWork = TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.isHomeBuildWorkSearchPending(this.playerNpc, serverLevel);
        return this.cachedReadyBuildWork;
    }

    private boolean hasBasicHomeAmenities(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        boolean hasUtility = false;
        boolean hasStandSpot = false;
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 6, homeArea.depth() - 1))) {
            BlockPos immutable = pos.immutable();
            if (!hasUtility && this.isHomeUtility(serverLevel.getBlockState(immutable))) {
                hasUtility = true;
            }
            if (!hasStandSpot && PlayerNpcHomeUtil.isInside(homeArea, immutable) && this.canStandAt(serverLevel, immutable)) {
                hasStandSpot = true;
            }
            if (hasUtility && hasStandSpot) {
                return true;
            }
        }
        return false;
    }

    private void openNearbyHomeDoor(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, double maxDistanceSqr) {
        int radius = Math.max(1, (int) Math.ceil(Math.sqrt(maxDistanceSqr)));
        BlockPos center = this.playerNpc.blockPosition();
        BlockPos from = center.offset(-radius, -2, -radius);
        BlockPos to = center.offset(radius, 3, radius);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockPos immutable = pos.immutable();
            if (!PlayerNpcHomeUtil.isInside(homeArea, immutable)) {
                continue;
            }
            if (this.distanceToPosSqr(immutable) > maxDistanceSqr) {
                continue;
            }

            BlockState state = serverLevel.getBlockState(immutable);
            if (state.getBlock() instanceof DoorBlock doorBlock
                    && state.hasProperty(DoorBlock.OPEN)
                    && !state.getValue(DoorBlock.OPEN)) {
                doorBlock.setOpen(this.playerNpc, serverLevel, state, immutable, true);
            }
        }
    }

    private void tickHomeActivity() {
        if (this.activityTicks-- <= 0) {
            this.switchHomeActivity();
        }

        if (this.activityMode == ActivityMode.WALK) {
            this.tickWalkAtHome();
        } else if (this.activityMode == ActivityMode.SNEAK) {
            this.playerNpc.getNavigation().stop();
            this.tickSneakAtHome();
        } else {
            this.playerNpc.getNavigation().stop();
            this.sneakingAi.setSneaking(false);
            this.lookAroundAtHome();
        }
    }

    private void tickSneakAtHome() {
        if (this.tickCustomHomeIdleAnimation(this.playerNpc)) {
            this.sneakingAi.setSneaking(false);
        } else {
            this.sneakingAi.setSneaking(true);
        }
        this.lookAroundAtHome();
    }

    private void tickWalkAtHome() {
        this.playerNpc.getNavigation().stop();
        this.sneakingAi.setSneaking(false);
        this.lookAroundAtHome();
        if (this.stationaryTicks-- > 0) {
            return;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.homeArea == null) {
            this.stationaryTicks = this.nextStationaryTicks();
            return;
        }

        BlockPos nextPos = this.findAfkPos(serverLevel, this.homeArea);
        this.stationaryTicks = this.nextStationaryTicks();
        if (nextPos == null || nextPos.equals(this.afkPos)) {
            return;
        }
        this.afkPos = nextPos;
        this.reachedSpot = false;
        this.walkSneaking = this.playerNpc.getRandom().nextFloat() < WALK_SNEAK_CHANCE;
        this.moveToAfkPos();
    }

    private void switchHomeActivity() {
        ActivityMode previous = this.activityMode;
        this.stopCustomHomeIdleAnimation(this.playerNpc);
        this.sneakingAi.setSneaking(false);
        this.walkSneaking = false;
        this.activityMode = this.randomActivityMode(previous);
        this.activityTicks = this.nextActivityTicks();
        this.stationaryTicks = this.nextStationaryTicks();
    }

    private ActivityMode randomActivityMode() {
        return this.randomActivityMode(null);
    }

    private ActivityMode randomActivityMode(ActivityMode previous) {
        ActivityMode next;
        do {
            float roll = this.playerNpc.getRandom().nextFloat();
            if (roll < 0.34F) {
                next = ActivityMode.SNEAK;
            } else if (roll < 0.72F) {
                next = ActivityMode.WALK;
            } else {
                next = ActivityMode.LOOK;
            }
        } while (next == previous);
        return next;
    }

    private int nextActivityTicks() {
        return MIN_HOME_ACTIVITY_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_HOME_ACTIVITY_TICKS + 1);
    }

    private int nextStationaryTicks() {
        return MIN_STATIONARY_WALK_PAUSE_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_STATIONARY_WALK_PAUSE_TICKS + 1);
    }

    private void lookAroundAtHome() {
        if (this.lookTicks-- > 0) {
            return;
        }
        double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
        double distance = 4.0D + this.playerNpc.getRandom().nextDouble() * 4.0D;
        this.playerNpc.getLookControl().setLookAt(
                this.playerNpc.getX() + Math.cos(angle) * distance,
                this.playerNpc.getEyeY(),
                this.playerNpc.getZ() + Math.sin(angle) * distance,
                30.0F,
                30.0F
        );
        this.lookTicks = MIN_LOOK_AROUND_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_LOOK_AROUND_TICKS + 1);
    }

    protected boolean tickCustomHomeIdleAnimation(PlayerNpcEntity playerNpc) {
        return false;
    }

    protected void stopCustomHomeIdleAnimation(PlayerNpcEntity playerNpc) {
    }

    private enum ActivityMode {
        SNEAK,
        WALK,
        LOOK
    }
}
