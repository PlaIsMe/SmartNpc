package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class WaterEscapeAi {
    public enum TickResult {
        NOT_NEEDED,
        RUNNING,
        DONE,
        FAILED
    }

    private static final int MAX_ESCAPE_TICKS = 20 * 5;
    private static final double MIN_FLOW_STRENGTH_SQR = 0.0004D;
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 30;
    private static final double STAND_PLACE_CLEARANCE_Y = 0.92D;
    private static final double FALLBACK_STAND_PLACE_CLEARANCE_Y = 0.68D;
    private static final int DRY_EXIT_FALLBACK_MIN_RADIUS = 4;
    private static final int DRY_EXIT_FALLBACK_MAX_RADIUS = 5;
    private static final int DRY_EXIT_FALLBACK_VERTICAL_DOWN = 2;
    private static final int DRY_EXIT_FALLBACK_VERTICAL_UP = 2;
    private static final int DRY_EXIT_FALLBACK_RANDOM_POOL = 8;
    private static final int DESTINATION_RECOVERY_SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_DESTINATION_SWIM_TICKS = 20 * 120;
    private static final int MIN_DESTINATION_EPISODE_TICKS = 20 * 20;
    private static final int MAX_DESTINATION_EPISODE_TICKS = 20 * 120;
    private static final int DESTINATION_UNCHANGED_POSITION_TICKS = 20 * 8;
    private static final int FAILED_DESTINATION_QUARANTINE_TICKS = 20 * 30;
    private static final int DESTINATION_DRY_CONFIRM_TICKS = 5;
    private static final int DESTINATION_STALL_TICKS = 20 * 2;
    private static final int MAX_DESTINATION_STALL_EPISODES = 3;
    private static final double DESTINATION_PROGRESS_EPSILON_SQR = 0.04D;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private BlockPos waterPos;
    private BlockPos standPlacePos;
    private BlockPos dryExitPos;
    private BlockPos preferredDestination;
    private BlockPos failedDestination;
    private BlockPos quarantinedDestination;
    private int quarantinedDestinationUntilTick;
    private boolean placingStandBlock;
    private boolean movingToDryExit;
    private boolean swimmingToDestination;
    private int escapeTicks;
    private int placeDelayTicks;
    private int placeWaitTicks;
    private int destinationSwimTicks;
    private int destinationStallTicks;
    private int destinationStallEpisodes;
    private int nextDestinationRecoveryScanTick;
    private int destinationEpisodeStartTick = -1;
    private int destinationEpisodeBudgetTicks = MIN_DESTINATION_EPISODE_TICKS;
    private int destinationLastMovementTick = -1;
    private int destinationDryConfirmTicks;
    private double destinationProgressX;
    private double destinationProgressZ;
    private double bestDestinationDistanceSqr = Double.MAX_VALUE;
    private String detail = "";

    public WaterEscapeAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
    }

    public boolean canStart(ServerLevel serverLevel) {
        if (!this.isInWater(serverLevel)) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        boolean hasDryExit = this.findDryStepOut(serverLevel, feet) != null;
        return hasDryExit
                || this.findWaterStandTarget(serverLevel, false) != null
                || this.findWaterCurrentTarget(serverLevel) != null;
    }

    public boolean isRunning() {
        return this.waterPos != null
                || this.dryExitPos != null
                || this.standPlacePos != null
                || this.swimmingToDestination;
    }

    public String detail() {
        return this.detail;
    }

    public TickResult tick(ServerLevel serverLevel, double speed) {
        return this.tick(serverLevel, speed, null);
    }

    public TickResult tick(ServerLevel serverLevel, double speed, @Nullable BlockPos preferredDestination) {
        BlockPos requestedDestination = preferredDestination == null ? null : preferredDestination.immutable();
        if (!this.isInWater(serverLevel) && requestedDestination != null
                && this.destinationEpisodeStartTick >= 0) {
            BlockPos feet = this.playerNpc.blockPosition();
            if (this.canStandDryAt(serverLevel, feet) && this.playerNpc.onGround()) {
                if (++this.destinationDryConfirmTicks >= DESTINATION_DRY_CONFIRM_TICKS) {
                    this.stop();
                    return TickResult.DONE;
                }
            } else {
                this.destinationDryConfirmTicks = 0;
            }
            if (this.playerNpc.tickCount - this.destinationEpisodeStartTick >= this.destinationEpisodeBudgetTicks) {
                this.failOrStopPreferredDestination();
                return TickResult.FAILED;
            }
            this.detail = "water escape: confirming dry footing @ " + posText(feet);
            return TickResult.RUNNING;
        }
        if (!this.isInWater(serverLevel)) {
            boolean wasRunning = this.isRunning();
            this.stop();
            return wasRunning ? TickResult.DONE : TickResult.NOT_NEEDED;
        }

        if (this.quarantinedDestination != null
                && this.playerNpc.tickCount >= this.quarantinedDestinationUntilTick) {
            this.quarantinedDestination = null;
            this.quarantinedDestinationUntilTick = 0;
        }
        if (this.quarantinedDestination != null
                && Objects.equals(this.quarantinedDestination, requestedDestination)) {
            this.jumpUpFromWater(serverLevel);
            this.detail = "water escape: route cooling down @ " + posText(requestedDestination);
            return TickResult.FAILED;
        }
        if (this.failedDestination != null && Objects.equals(this.failedDestination, requestedDestination)) {
            this.jumpUpFromWater(serverLevel);
            this.detail = "water escape: stalled toward work target @ " + posText(requestedDestination);
            return TickResult.FAILED;
        }
        if (this.failedDestination != null) {
            this.stop();
        }
        if (!Objects.equals(this.preferredDestination, requestedDestination)) {
            this.stop();
            this.preferredDestination = requestedDestination;
        }

        if (!this.isRunning() && !this.start(serverLevel, requestedDestination)) {
            this.jumpUpFromWater(serverLevel);
            this.detail = "water escape: jumping";
            return TickResult.FAILED;
        }
        if (requestedDestination != null
                && this.destinationEpisodeStartTick >= 0
                && this.playerNpc.tickCount - this.destinationEpisodeStartTick >= this.destinationEpisodeBudgetTicks) {
            this.failOrStopPreferredDestination();
            return TickResult.FAILED;
        }

        double safeSpeed = Math.min(1.0D, Math.max(0.1D, speed));
        if (this.swimmingToDestination) {
            return this.tickDestinationSwim(serverLevel, safeSpeed);
        }
        if (this.escapeTicks++ >= MAX_ESCAPE_TICKS) {
            this.failOrStopPreferredDestination();
            return TickResult.FAILED;
        }
        if (this.movingToDryExit) {
            return this.tickDryExit(serverLevel, safeSpeed);
        }
        if (this.placingStandBlock) {
            return this.tickStandPlacement(serverLevel);
        }

        this.jumpAgainstCurrent(serverLevel);
        this.updateDetail();
        return TickResult.RUNNING;
    }

    public void stop() {
        this.playerNpc.getWaterTravelAi().release(this);
        this.waterPos = null;
        this.standPlacePos = null;
        this.dryExitPos = null;
        this.preferredDestination = null;
        this.failedDestination = null;
        this.placingStandBlock = false;
        this.movingToDryExit = false;
        this.swimmingToDestination = false;
        this.escapeTicks = 0;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.destinationSwimTicks = 0;
        this.destinationStallTicks = 0;
        this.destinationStallEpisodes = 0;
        this.nextDestinationRecoveryScanTick = 0;
        this.destinationEpisodeStartTick = -1;
        this.destinationEpisodeBudgetTicks = MIN_DESTINATION_EPISODE_TICKS;
        this.destinationLastMovementTick = -1;
        this.destinationDryConfirmTicks = 0;
        this.bestDestinationDistanceSqr = Double.MAX_VALUE;
        this.detail = "";
    }

    private boolean start(ServerLevel serverLevel, @Nullable BlockPos preferredDestination) {
        this.escapeTicks = 0;
        this.placeDelayTicks = PLACE_DELAY_TICKS;
        this.placeWaitTicks = 0;
        this.preferredDestination = preferredDestination == null ? null : preferredDestination.immutable();

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.preferredDestination != null) {
            // The owning goal knows why the NPC entered the water and where it was going. Keep
            // that route instead of selecting the nearest bank, which can be the bank it just
            // left and creates a cross-river ping-pong loop.
            this.beginDestinationSwim(feet, this.preferredDestination, true);
            return true;
        }

        BlockPos dryExit = this.findDryStepOut(serverLevel, feet, this.preferredDestination);
        if (dryExit != null) {
            this.beginDryExit(feet, dryExit);
            return true;
        }

        // Survival comes before continuing work. Do not swim directly at a stone/log target
        // while a usable footing block can get the NPC out of the water.
        WaterStandTarget standTarget = this.findWaterStandTarget(serverLevel, false);
        if (standTarget != null) {
            this.playerNpc.getWaterTravelAi().release(this);
            this.waterPos = standTarget.waterPos();
            this.standPlacePos = standTarget.placePos();
            this.dryExitPos = null;
            this.placingStandBlock = true;
            this.movingToDryExit = false;
            this.updateDetail();
            return true;
        }

        WaterCurrentTarget currentTarget = this.findWaterCurrentTarget(serverLevel);
        if (currentTarget == null) {
            return false;
        }

        this.beginCurrentEscape(currentTarget);
        return true;
    }

    private TickResult tickDestinationSwim(ServerLevel serverLevel, double speed) {
        if (this.preferredDestination == null) {
            this.stop();
            return TickResult.FAILED;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        this.waterPos = feet.immutable();
        this.destinationSwimTicks++;
        if (this.destinationSwimTicks >= MAX_DESTINATION_SWIM_TICKS) {
            this.failOrStopPreferredDestination();
            return TickResult.FAILED;
        }
        double movedX = this.playerNpc.getX() - this.destinationProgressX;
        double movedZ = this.playerNpc.getZ() - this.destinationProgressZ;
        if (movedX * movedX + movedZ * movedZ >= 0.5D * 0.5D) {
            this.destinationProgressX = this.playerNpc.getX();
            this.destinationProgressZ = this.playerNpc.getZ();
            this.destinationDryConfirmTicks = 0;
            this.destinationLastMovementTick = this.playerNpc.tickCount;
        } else if (this.destinationLastMovementTick >= 0
                && this.playerNpc.tickCount - this.destinationLastMovementTick >= DESTINATION_UNCHANGED_POSITION_TICKS) {
            this.failOrStopPreferredDestination();
            return TickResult.FAILED;
        }
        double distanceSqr = horizontalDistanceToCenterSqr(this.playerNpc.position(), this.preferredDestination);
        if (distanceSqr + DESTINATION_PROGRESS_EPSILON_SQR < this.bestDestinationDistanceSqr) {
            this.bestDestinationDistanceSqr = distanceSqr;
            this.destinationStallTicks = 0;
        } else {
            this.destinationStallTicks++;
        }

        // Dry-exit and footing discovery scan dozens/hundreds of world positions. Keep the cheap
        // steering below at 20 Hz, but run all recovery discovery on one shared absolute
        // one-second cadence so goal tick-rate changes cannot accidentally multiply world scans.
        if (this.playerNpc.tickCount >= this.nextDestinationRecoveryScanTick) {
            if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                this.nextDestinationRecoveryScanTick = this.playerNpc.tickCount
                        + 1
                        + this.playerNpc.getRandom().nextInt(4);
                this.steerTowardDestination(speed);
                this.updateDetail();
                return TickResult.RUNNING;
            }
            this.nextDestinationRecoveryScanTick = this.playerNpc.tickCount
                    + DESTINATION_RECOVERY_SCAN_INTERVAL_TICKS;
            if (this.destinationStallTicks >= DESTINATION_STALL_TICKS) {
                if (this.tryStartDestinationFallback(serverLevel, feet)) {
                    return TickResult.RUNNING;
                }
                this.destinationStallEpisodes++;
                if (this.destinationStallEpisodes >= MAX_DESTINATION_STALL_EPISODES) {
                    this.failOrStopPreferredDestination();
                    return TickResult.FAILED;
                }
                this.destinationStallTicks = 0;
                this.destinationSwimTicks = 0;
                this.bestDestinationDistanceSqr = distanceSqr;
            } else {
                BlockPos dryExit = this.findDestinationSideDryStepOut(serverLevel, feet);
                if (dryExit != null) {
                    this.beginDryExit(feet, dryExit);
                    return TickResult.RUNNING;
                }
            }
        }

        this.steerTowardDestination(speed);
        this.updateDetail();
        return TickResult.RUNNING;
    }

    private void steerTowardDestination(double speed) {
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getWaterTravelAi().request(this, this.preferredDestination, speed, false);
        double wantedY = Math.max(
                this.playerNpc.getY() + 0.5D,
                Math.min(this.preferredDestination.getY(), this.playerNpc.getY() + 1.0D)
        );
        this.playerNpc.getLookControl().setLookAt(
                this.preferredDestination.getX() + 0.5D,
                wantedY,
                this.preferredDestination.getZ() + 0.5D,
                50.0F,
                50.0F
        );
        this.playerNpc.getMoveControl().setWantedPosition(
                this.preferredDestination.getX() + 0.5D,
                wantedY,
                this.preferredDestination.getZ() + 0.5D,
                speed
        );
        this.steerThroughWaterToward(this.preferredDestination, speed);
    }

    private boolean tryStartDestinationFallback(ServerLevel serverLevel, BlockPos feet) {
        BlockPos dryExit = this.findDestinationSideDryStepOut(serverLevel, feet);
        if (dryExit != null) {
            this.beginDryExit(feet, dryExit);
            return true;
        }

        WaterStandTarget standTarget = this.findDestinationStandTarget(serverLevel, feet);
        if (standTarget != null) {
            this.playerNpc.getWaterTravelAi().release(this);
            this.waterPos = standTarget.waterPos();
            this.standPlacePos = standTarget.placePos();
            this.dryExitPos = null;
            this.placingStandBlock = true;
            this.movingToDryExit = false;
            this.swimmingToDestination = false;
            this.placeDelayTicks = PLACE_DELAY_TICKS;
            this.placeWaitTicks = 0;
            this.updateDetail();
            return true;
        }

        // Keep swimming toward the owner's target. Footing blocks are reserved for shallow water
        // or a wall/shore obstruction, so an ordinary river crossing does not become a bridge-
        // building or bank-selection behavior.
        return false;
    }

    private void beginDestinationSwim(BlockPos feet, BlockPos destination, boolean newEpisode) {
        this.waterPos = feet.immutable();
        this.preferredDestination = destination.immutable();
        this.standPlacePos = null;
        this.dryExitPos = null;
        this.placingStandBlock = false;
        this.movingToDryExit = false;
        this.swimmingToDestination = true;
        if (newEpisode || this.destinationEpisodeStartTick < 0) {
            this.destinationEpisodeStartTick = this.playerNpc.tickCount;
            // Budget once from the original crossing distance, including time to breathe.
            // Recovery transitions never refresh this absolute deadline.
            double crossingDistance = Math.sqrt(horizontalDistanceToCenterSqr(this.playerNpc.position(), destination));
            this.destinationEpisodeBudgetTicks = (int) Math.min(MAX_DESTINATION_EPISODE_TICKS,
                    Math.max(MIN_DESTINATION_EPISODE_TICKS, 200.0D + crossingDistance * 20.0D));
            this.destinationLastMovementTick = this.playerNpc.tickCount;
            this.destinationProgressX = this.playerNpc.getX();
            this.destinationProgressZ = this.playerNpc.getZ();
            this.destinationSwimTicks = 0;
            this.destinationStallEpisodes = 0;
            this.bestDestinationDistanceSqr = horizontalDistanceToCenterSqr(
                    this.playerNpc.position(),
                    this.preferredDestination
            );
        }
        this.destinationStallTicks = 0;
        this.nextDestinationRecoveryScanTick = this.playerNpc.tickCount
                + DESTINATION_RECOVERY_SCAN_INTERVAL_TICKS;
        this.updateDetail();
    }

    private void beginDryExit(BlockPos feet, BlockPos dryExit) {
        this.playerNpc.getWaterTravelAi().release(this);
        this.waterPos = feet.immutable();
        this.dryExitPos = dryExit.immutable();
        this.standPlacePos = null;
        this.placingStandBlock = false;
        this.movingToDryExit = true;
        this.swimmingToDestination = false;
        this.updateDetail();
    }

    private void beginCurrentEscape(WaterCurrentTarget target) {
        this.waterPos = target.waterPos();
        this.standPlacePos = null;
        this.dryExitPos = null;
        this.placingStandBlock = false;
        this.movingToDryExit = false;
        this.swimmingToDestination = false;
        this.updateDetail();
    }

    private void failOrStopPreferredDestination() {
        BlockPos failed = this.preferredDestination;
        this.stop();
        if (failed != null) {
            this.failedDestination = failed.immutable();
            this.quarantinedDestination = failed.immutable();
            this.quarantinedDestinationUntilTick = this.playerNpc.tickCount
                    + FAILED_DESTINATION_QUARANTINE_TICKS;
            this.detail = "water escape: stalled toward work target @ " + posText(failed);
        }
    }

    private TickResult tickDryExit(ServerLevel serverLevel, double speed) {
        if (this.dryExitPos == null) {
            this.failOrStopPreferredDestination();
            return TickResult.FAILED;
        }

        if (!this.isInWater(serverLevel)) {
            this.stop();
            return TickResult.DONE;
        }

        if (!this.canStandDryAt(serverLevel, this.dryExitPos)) {
            BlockPos feet = this.playerNpc.blockPosition();
            this.dryExitPos = this.preferredDestination == null
                    ? this.findDryStepOut(serverLevel, feet, null)
                    : this.findDestinationSideDryStepOut(serverLevel, feet);
            if (this.dryExitPos == null) {
                if (this.preferredDestination != null) {
                    this.beginDestinationSwim(feet, this.preferredDestination, false);
                    return TickResult.RUNNING;
                }
                this.failOrStopPreferredDestination();
                return TickResult.FAILED;
            }
        }

        this.waterPos = this.playerNpc.blockPosition().immutable();
        this.playerNpc.getNavigation().stop();
        this.jumpUpFromWater(serverLevel);
        this.playerNpc.getLookControl().setLookAt(
                this.dryExitPos.getX() + 0.5D,
                this.dryExitPos.getY() + 0.5D,
                this.dryExitPos.getZ() + 0.5D,
                50.0F,
                50.0F
        );
        this.playerNpc.getMoveControl().setWantedPosition(
                this.dryExitPos.getX() + 0.5D,
                this.dryExitPos.getY(),
                this.dryExitPos.getZ() + 0.5D,
                speed
        );
        this.steerThroughWaterToward(this.dryExitPos, speed);
        this.updateDetail();
        return TickResult.RUNNING;
    }

    private TickResult tickStandPlacement(ServerLevel serverLevel) {
        if (this.standPlacePos == null) {
            this.failOrStopPreferredDestination();
            return TickResult.FAILED;
        }

        this.playerNpc.getNavigation().stop();
        this.jumpUpFromWater(serverLevel);
        this.playerNpc.getLookControl().setLookAt(
                this.standPlacePos.getX() + 0.5D,
                this.standPlacePos.getY() + 0.5D,
                this.standPlacePos.getZ() + 0.5D,
                50.0F,
                50.0F
        );
        this.updateDetail();

        if (!this.canPlaceStandBlockAt(serverLevel, this.standPlacePos)) {
            BlockPos feet = this.playerNpc.blockPosition();
            WaterStandTarget target = this.preferredDestination == null
                    ? this.findWaterStandTarget(serverLevel)
                    : this.findDestinationStandTarget(serverLevel, feet);
            if (target == null) {
                if (this.preferredDestination != null) {
                    this.beginDestinationSwim(feet, this.preferredDestination, false);
                    return TickResult.RUNNING;
                }
                this.stop();
                return TickResult.DONE;
            }
            this.waterPos = target.waterPos();
            this.standPlacePos = target.placePos();
            this.placeDelayTicks = PLACE_DELAY_TICKS;
            this.placeWaitTicks = 0;
            return TickResult.RUNNING;
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return TickResult.RUNNING;
        }

        this.placeWaitTicks++;
        if (!this.hasStandPlacementClearance(this.standPlacePos)) {
            if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
                this.failOrStopPreferredDestination();
                return TickResult.FAILED;
            }
            return TickResult.RUNNING;
        }

        if (this.tryPlaceStandBlock(serverLevel, this.standPlacePos)) {
            BlockPos placedPos = this.standPlacePos;
            BlockPos dryStandPos = placedPos.above();
            if (this.canStandDryAt(serverLevel, dryStandPos)) {
                this.beginDryExit(this.playerNpc.blockPosition(), dryStandPos);
                return TickResult.RUNNING;
            }
            this.stop();
            return TickResult.DONE;
        }

        if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
            this.failOrStopPreferredDestination();
            return TickResult.FAILED;
        }
        return TickResult.RUNNING;
    }

    private WaterCurrentTarget findWaterCurrentTarget(ServerLevel serverLevel) {
        if (!this.playerNpc.isInWater()
                && !serverLevel.getFluidState(this.playerNpc.blockPosition()).is(FluidTags.WATER)) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> waterCandidates = new ArrayList<>();
        waterCandidates.add(feet);
        waterCandidates.add(feet.above());
        waterCandidates.add(feet.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            waterCandidates.add(feet.relative(direction));
            waterCandidates.add(feet.relative(direction).above());
        }

        WaterCurrentTarget best = null;
        double bestScore = 0.0D;
        for (BlockPos candidate : waterCandidates) {
            if (!serverLevel.hasChunkAt(candidate)) {
                continue;
            }
            FluidState fluidState = serverLevel.getFluidState(candidate);
            if (!isFlowingWater(serverLevel, candidate, fluidState)) {
                continue;
            }

            double flowStrength = horizontalFlowStrengthSqr(serverLevel, candidate, fluidState);
            double score = flowStrength + (candidate.equals(feet) ? 1.0D : 0.0D);
            if (best == null || score > bestScore) {
                best = new WaterCurrentTarget(candidate.immutable());
                bestScore = score;
            }
        }
        return best;
    }

    private WaterStandTarget findWaterStandTarget(ServerLevel serverLevel) {
        return this.findWaterStandTarget(serverLevel, true);
    }

    private WaterStandTarget findWaterStandTarget(ServerLevel serverLevel, boolean checkDryExit) {
        if (!this.isInWater(serverLevel) || !this.hasWaterPlugBlock()) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (checkDryExit && this.findDryStepOut(serverLevel, feet) != null) {
            return null;
        }

        BlockPos place = this.findStandPlacePos(serverLevel, feet);
        return place == null ? null : new WaterStandTarget(feet.immutable(), place);
    }

    @Nullable
    private WaterStandTarget findDestinationStandTarget(ServerLevel serverLevel, BlockPos feet) {
        if (this.preferredDestination == null || !this.hasWaterPlugBlock()) {
            return null;
        }

        BlockPos place = this.findDestinationFootingPos(serverLevel, feet);
        return place != null && this.canPlaceStandBlockAt(serverLevel, place)
                ? new WaterStandTarget(feet.immutable(), place.immutable())
                : null;
    }

    @Nullable
    private BlockPos findDestinationFootingPos(ServerLevel serverLevel, BlockPos feet) {
        BlockPos currentWaterCell = serverLevel.getFluidState(feet).is(FluidTags.WATER)
                ? feet
                : serverLevel.getFluidState(feet.below()).is(FluidTags.WATER) ? feet.below() : null;
        if (currentWaterCell == null) {
            return null;
        }
        boolean oneBlockDeep = !serverLevel.getFluidState(currentWaterCell.above()).is(FluidTags.WATER)
                && !serverLevel.getFluidState(currentWaterCell.below()).is(FluidTags.WATER)
                && this.isWalkableFloor(serverLevel, currentWaterCell.below());
        if (oneBlockDeep) {
            return currentWaterCell.immutable();
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = currentWaterCell.relative(direction);
            if (this.canStandDryAt(serverLevel, adjacent)
                    || this.canStandDryAt(serverLevel, adjacent.above())) {
                return currentWaterCell.immutable();
            }
        }
        return null;
    }

    private BlockPos findStandPlacePos(ServerLevel serverLevel, BlockPos feet) {
        BlockPos place = this.findDestinationFootingPos(serverLevel, feet);
        return place != null && this.canPlaceStandBlockAt(serverLevel, place)
                ? place.immutable()
                : null;
    }

    private void jumpAgainstCurrent(ServerLevel serverLevel) {
        FluidState fluidState = serverLevel.getFluidState(this.waterPos);
        Vec3 flow = fluidState.getFlow(serverLevel, this.waterPos);
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getJumpControl().jump();
        this.playerNpc.setDeltaMovement(
                motion.x - flow.x * 0.18D,
                Math.max(motion.y, 0.12D),
                motion.z - flow.z * 0.18D
        );
        this.playerNpc.hurtMarked = true;
    }

    private void jumpUpFromWater(ServerLevel serverLevel) {
        BlockPos pos = this.waterPos == null ? this.playerNpc.blockPosition() : this.waterPos;
        FluidState fluidState = serverLevel.getFluidState(pos);
        Vec3 flow = fluidState.is(FluidTags.WATER) ? fluidState.getFlow(serverLevel, pos) : Vec3.ZERO;
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.getJumpControl().jump();
        this.playerNpc.setDeltaMovement(
                motion.x - flow.x * 0.08D,
                Math.max(motion.y, 0.22D),
                motion.z - flow.z * 0.08D
        );
        this.playerNpc.hurtMarked = true;
    }

    /**
     * MoveControl turns the NPC and selects its movement speed, but it does not give this
     * PathfinderMob any forward swimming input after navigation has been stopped. Apply a small,
     * bounded water-only impulse so destination swimming actually makes horizontal progress while
     * collision handling remains with the entity physics.
     */
    private void steerThroughWaterToward(BlockPos destination, double speed) {
        double dx = destination.getX() + 0.5D - this.playerNpc.getX();
        double dz = destination.getZ() + 0.5D - this.playerNpc.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }

        double safeSpeed = Math.min(1.0D, Math.max(0.1D, speed));
        double acceleration = this.playerNpc.getWaterTravelAi().swimAcceleration() * safeSpeed;
        double maxSpeed = this.playerNpc.getWaterTravelAi().swimSpeedCap() * safeSpeed;
        Vec3 motion = this.playerNpc.getDeltaMovement();
        double nextX = motion.x + dx / length * acceleration;
        double nextZ = motion.z + dz / length * acceleration;
        double horizontalSpeed = Math.sqrt(nextX * nextX + nextZ * nextZ);
        if (horizontalSpeed > maxSpeed) {
            nextX = nextX / horizontalSpeed * maxSpeed;
            nextZ = nextZ / horizontalSpeed * maxSpeed;
        }

        this.playerNpc.setDeltaMovement(nextX, motion.y, nextZ);
        this.playerNpc.hurtMarked = true;
    }

    private boolean tryPlaceStandBlock(ServerLevel serverLevel, BlockPos pos) {
        ItemStack blockStack = this.takeWaterPlugBlock();
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            this.returnStack(blockStack);
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!this.canPlaceStandBlockAt(serverLevel, pos)
                || !state.canSurvive(serverLevel, pos)
                || !this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }
        this.playerNpc.markTemporaryPillarSupport(pos);
        return true;
    }

    private boolean canPlaceStandBlockAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)
                || this.isProtectedPlacementPos(pos)) {
            return false;
        }

        return serverLevel.getFluidState(pos).is(FluidTags.WATER)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getEntities(this.playerNpc, new AABB(pos)).isEmpty();
    }

    private boolean isProtectedPlacementPos(BlockPos pos) {
        return FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos);
    }

    private boolean isWaterPlugBlock(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.is(Items.FURNACE)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem
                || blockItem.getBlock().defaultBlockState().is(Blocks.WATER)
                || blockItem.getBlock().defaultBlockState().is(Blocks.LAVA)) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.canOcclude()
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty()
                && !StoneAi.isStone(state);
    }

    private boolean hasWaterPlugBlock() {
        return InventoryUtils.hasItem(this.playerNpc, this::isWaterPlugBlock)
                || this.canConvertLogToWaterPlugPlanks();
    }

    private ItemStack takeWaterPlugBlock() {
        ItemStack block = InventoryUtils.consumeItem(this.playerNpc, this::isWaterPlugBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (!block.isEmpty()) {
            return block;
        }

        if (PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), 0)) {
            return InventoryUtils.consumeItem(this.playerNpc, this::isWaterPlugBlock, 1)
                    .orElse(ItemStack.EMPTY);
        }
        return ItemStack.EMPTY;
    }

    private boolean canConvertLogToWaterPlugPlanks() {
        return InventoryUtils.hasItem(this.playerNpc.getInventory(), PlayerNpcCraftingUtil::isLogs);
    }

    private boolean isInWater(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isInWater()
                || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER)
                || !this.playerNpc.onGround() && serverLevel.getFluidState(feet.below()).is(FluidTags.WATER);
    }

    private BlockPos findDryStepOut(ServerLevel serverLevel, BlockPos feet) {
        return this.findDryStepOut(serverLevel, feet, null);
    }

    private BlockPos findDryStepOut(
            ServerLevel serverLevel,
            BlockPos feet,
            @Nullable BlockPos preferredDestination
    ) {
        if (!this.isInWater(serverLevel)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = feet.relative(direction);
            if (this.canStandDryAt(serverLevel, adjacent)) {
                candidates.add(adjacent.immutable());
            }

            BlockPos stepUp = adjacent.above();
            if (this.isWalkableFloor(serverLevel, adjacent) && this.canStandDryAt(serverLevel, stepUp)) {
                candidates.add(stepUp.immutable());
            }
        }
        if (candidates.isEmpty()) {
            return this.findNearbyDryStepOut(serverLevel, feet, preferredDestination);
        }

        Vec3 look = this.playerNpc.getLookAngle();
        candidates.sort(this.dryExitComparator(feet, look, preferredDestination));
        return candidates.get(0);
    }

    @Nullable
    private BlockPos findDestinationSideDryStepOut(ServerLevel serverLevel, BlockPos feet) {
        if (this.preferredDestination == null) {
            return null;
        }
        BlockPos candidate = this.findDryStepOut(serverLevel, feet, this.preferredDestination);
        if (candidate == null) {
            return null;
        }

        double currentDistance = horizontalDistanceSqr(feet, this.preferredDestination);
        double exitDistance = horizontalDistanceSqr(candidate, this.preferredDestination);
        if (currentDistance <= 1.0D) {
            // A work stand directly above/below the water column cannot be approached by
            // horizontal swimming. Accept the best dry side exit even though stepping sideways
            // briefly increases horizontal distance; the owning goal can repath from dry land.
            return candidate;
        }
        return exitDistance + DESTINATION_PROGRESS_EPSILON_SQR < currentDistance ? candidate : null;
    }

    private BlockPos findNearbyDryStepOut(
            ServerLevel serverLevel,
            BlockPos feet,
            @Nullable BlockPos preferredDestination
    ) {
        List<BlockPos> candidates = new ArrayList<>();
        int minRadiusSqr = DRY_EXIT_FALLBACK_MIN_RADIUS * DRY_EXIT_FALLBACK_MIN_RADIUS;
        int maxRadius = DRY_EXIT_FALLBACK_MAX_RADIUS;
        int maxRadiusSqr = maxRadius * maxRadius;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr < minRadiusSqr || distanceSqr > maxRadiusSqr) {
                    continue;
                }
                for (int dy = -DRY_EXIT_FALLBACK_VERTICAL_DOWN; dy <= DRY_EXIT_FALLBACK_VERTICAL_UP; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (this.canStandDryAt(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        Vec3 look = this.playerNpc.getLookAngle();
        candidates.sort(this.dryExitComparator(feet, look, preferredDestination)
                .thenComparingInt(BlockPos::getY));
        if (preferredDestination != null) {
            return candidates.get(0);
        }
        int poolSize = Math.min(DRY_EXIT_FALLBACK_RANDOM_POOL, candidates.size());
        return candidates.get(this.playerNpc.getRandom().nextInt(poolSize));
    }

    private Comparator<BlockPos> dryExitComparator(
            BlockPos feet,
            Vec3 look,
            @Nullable BlockPos preferredDestination
    ) {
        if (preferredDestination != null) {
            return Comparator
                    .comparingDouble((BlockPos candidate) -> horizontalDistanceSqr(candidate, preferredDestination))
                    .thenComparingDouble(feet::distSqr)
                    .thenComparingDouble(candidate -> -directionScore(feet, candidate, look));
        }
        return Comparator
                .comparingDouble((BlockPos candidate) -> feet.distSqr(candidate))
                .thenComparingDouble(candidate -> -directionScore(feet, candidate, look));
    }

    private boolean canStandDryAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.hasChunkAt(pos.above())
                && serverLevel.hasChunkAt(pos.below())
                && !serverLevel.getFluidState(pos).is(FluidTags.WATER)
                && !serverLevel.getFluidState(pos.above()).is(FluidTags.WATER)
                && !this.hasBlockingCollision(serverLevel, pos)
                && !this.hasBlockingCollision(serverLevel, pos.above())
                && this.isWalkableFloor(serverLevel, pos.below());
    }

    private boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.hasChunkAt(pos)
                && !serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean isWalkableFloor(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.hasChunkAt(pos)) {
            return false;
        }
        BlockState floor = serverLevel.getBlockState(pos);
        return floor.isFaceSturdy(serverLevel, pos, Direction.UP)
                && !floor.getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean hasStandPlacementClearance(BlockPos pos) {
        double clearedY = this.playerNpc.getBoundingBox().minY - pos.getY();
        if (clearedY >= STAND_PLACE_CLEARANCE_Y) {
            return true;
        }

        return this.placeWaitTicks >= 8
                && clearedY >= FALLBACK_STAND_PLACE_CLEARANCE_Y
                && this.playerNpc.getDeltaMovement().y <= 0.08D;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void updateDetail() {
        if (this.waterPos == null) {
            this.detail = "";
            return;
        }

        if (this.placingStandBlock) {
            String place = this.standPlacePos == null
                    ? "finding footing"
                    : "placing footing @ " + posText(this.standPlacePos);
            this.detail = "water escape: trapped water @ " + posText(this.waterPos) + " " + place;
            return;
        }

        if (this.swimmingToDestination) {
            String destination = this.preferredDestination == null
                    ? "work target"
                    : "work target @ " + posText(this.preferredDestination);
            this.detail = "water escape: " + this.playerNpc.getWaterTravelAi().phase() + " toward " + destination + " from @ " + posText(this.waterPos);
            return;
        }

        if (this.movingToDryExit) {
            String exit = this.dryExitPos == null
                    ? "finding dry exit"
                    : "dry exit @ " + posText(this.dryExitPos);
            this.detail = "water escape: leaving water @ " + posText(this.waterPos) + " " + exit;
            return;
        }

        this.detail = "water escape: flow @ " + posText(this.waterPos) + " jumping";
    }

    private static boolean isFlowingWater(ServerLevel serverLevel, BlockPos pos, FluidState fluidState) {
        return fluidState.is(FluidTags.WATER)
                && !fluidState.isSource()
                && horizontalFlowStrengthSqr(serverLevel, pos, fluidState) >= MIN_FLOW_STRENGTH_SQR;
    }

    private static double horizontalFlowStrengthSqr(ServerLevel serverLevel, BlockPos pos, FluidState fluidState) {
        Vec3 flow = fluidState.getFlow(serverLevel, pos);
        return flow.x * flow.x + flow.z * flow.z;
    }

    private static double directionScore(BlockPos from, BlockPos to, Vec3 look) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 0.0D) {
            return 0.0D;
        }
        return dx / length * look.x + dz / length * look.z;
    }

    private static double horizontalDistanceSqr(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz;
    }

    private static double horizontalDistanceToCenterSqr(Vec3 from, BlockPos to) {
        double dx = from.x - (to.getX() + 0.5D);
        double dz = from.z - (to.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record WaterCurrentTarget(BlockPos waterPos) {
    }

    private record WaterStandTarget(BlockPos waterPos, BlockPos placePos) {
    }
}
