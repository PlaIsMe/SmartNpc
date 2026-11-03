package com.pla.smart_npc.entity.goal;

import net.minecraft.world.attribute.EnvironmentAttributes;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Emergency water-bucket clutch for a damaging fall. The vertical ray is deliberately short so
 * this never turns into a terrain scan or causes chunks below the NPC to load.
 */
public class WaterFallGoal extends Goal {
    private static final float FALL_DAMAGE_DISTANCE = 3.0F;
    private static final double MIN_FALLING_Y_VELOCITY = -0.20D;
    private static final double MAX_GROUND_SCAN_DISTANCE = 6.0D;
    private static final double MIN_PLACE_DISTANCE = 1.5D;
    private static final double MAX_PLACE_DISTANCE = 5.5D;
    private static final double PLACE_DISTANCE_PADDING = 1.25D;
    private static final int PICKUP_DELAY_AFTER_CONTACT_TICKS = 5;
    private static final int MAX_ACTIVE_TICKS = 80;
    private static final double BUCKET_REACH_SQR = 4.5D * 4.5D;

    private final PlayerNpcEntity playerNpc;
    private BlockPos placedWaterPos;
    private int activeTicks;
    private int pickupDelayTicks = -1;
    private boolean contactedPlacedWater;
    private boolean finished;

    public WaterFallGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isRemoved()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.onGround()
                || this.playerNpc.isInWater()
                || serverLevel.environmentAttributes().getValue(
                        EnvironmentAttributes.WATER_EVAPORATES, this.playerNpc.position())
                || this.playerNpc.getDeltaMovement().y >= MIN_FALLING_Y_VELOCITY
                || this.playerNpc.fallDistance <= FALL_DAMAGE_DISTANCE
                || this.playerNpc.getBucketCooldown() > 0
                || !InventoryUtils.hasItem(this.playerNpc, Items.WATER_BUCKET)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.activeTicks < MAX_ACTIVE_TICKS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isRemoved()
                && this.playerNpc.level() instanceof ServerLevel
                && (this.placedWaterPos != null
                || !this.playerNpc.onGround() && !this.playerNpc.isInWater());
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.reset();
            return;
        }

        this.reset();
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.using_water_bucket");
        this.tryPlaceWater(serverLevel);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finished = true;
            return;
        }

        this.activeTicks++;
        this.playerNpc.getNavigation().stop();

        if (this.placedWaterPos == null) {
            if (this.playerNpc.onGround()
                    || this.playerNpc.isInWater()
                    || this.playerNpc.getDeltaMovement().y >= 0.0D) {
                this.finished = true;
                return;
            }
            this.tryPlaceWater(serverLevel);
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.placedWaterPos.getX() + 0.5D,
                this.placedWaterPos.getY() + 0.5D,
                this.placedWaterPos.getZ() + 0.5D,
                70.0F,
                70.0F
        );

        if (!serverLevel.hasChunkAt(this.placedWaterPos)) {
            this.finished = true;
            return;
        }
        FluidState fluidState = serverLevel.getFluidState(this.placedWaterPos);
        if (!fluidState.is(FluidTags.WATER) || !fluidState.isSource()) {
            this.finished = true;
            return;
        }

        if (!this.contactedPlacedWater) {
            if (this.isTouchingPlacedWater(serverLevel)) {
                this.contactedPlacedWater = true;
                this.pickupDelayTicks = PICKUP_DELAY_AFTER_CONTACT_TICKS;
            }
            return;
        }

        if (this.pickupDelayTicks > 0) {
            this.pickupDelayTicks--;
            return;
        }

        if (this.tryPickupWater(serverLevel)) {
            this.finished = true;
        }
    }

    @Override
    public void stop() {
        if (!this.finished
                && this.contactedPlacedWater
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.tryPickupWater(serverLevel);
        }
        this.reset();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private BlockPos findTimedWaterPlacement(ServerLevel serverLevel) {
        Vec3 motion = this.playerNpc.getDeltaMovement();
        double projectedX = this.playerNpc.getX() + motion.x;
        double projectedZ = this.playerNpc.getZ() + motion.z;
        double feetY = this.playerNpc.getBoundingBox().minY + 0.05D;
        Vec3 start = new Vec3(projectedX, feetY, projectedZ);
        Vec3 end = start.add(0.0D, -MAX_GROUND_SCAN_DISTANCE, 0.0D);
        if (!serverLevel.hasChunkAt(BlockPos.containing(start))
                || !serverLevel.hasChunkAt(BlockPos.containing(end))) {
            return null;
        }
        BlockHitResult hit = serverLevel.clip(new ClipContext(
                start,
                end,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this.playerNpc
        ));
        if (hit.getType() == HitResult.Type.MISS) {
            return null;
        }

        double distanceToGround = start.y - hit.getLocation().y;
        double placeDistance = Mth.clamp(
                -motion.y + PLACE_DISTANCE_PADDING,
                MIN_PLACE_DISTANCE,
                MAX_PLACE_DISTANCE
        );
        if (distanceToGround < 0.0D
                || distanceToGround > placeDistance
                || this.playerNpc.fallDistance + distanceToGround <= FALL_DAMAGE_DISTANCE) {
            return null;
        }

        BlockPos waterPos = hit.getBlockPos().above();
        if (this.playerNpc.getEyePosition().distanceToSqr(Vec3.atCenterOf(waterPos)) > BUCKET_REACH_SQR) {
            return null;
        }
        return this.canPlaceWater(serverLevel, waterPos) ? waterPos.immutable() : null;
    }

    private boolean tryPlaceWater(ServerLevel serverLevel) {
        BlockPos target = this.findTimedWaterPlacement(serverLevel);
        if (target == null) {
            return false;
        }
        if (this.playerNpc.getBucketCooldown() > 0
                || !this.canPlaceWater(serverLevel, target)
                || InventoryUtils.consumeItem(this.playerNpc, Items.WATER_BUCKET, 1).isEmpty()) {
            this.finished = true;
            return false;
        }

        this.playerNpc.getLookControl().setLookAt(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D,
                70.0F,
                70.0F
        );
        this.playerNpc.triggerMainHandUseAnimation();
        if (!serverLevel.setBlockAndUpdate(target, Blocks.WATER.defaultBlockState())) {
            this.giveOrDrop(new ItemStack(Items.WATER_BUCKET));
            return false;
        }

        this.placedWaterPos = target.immutable();
        this.giveOrDrop(new ItemStack(Items.BUCKET));
        serverLevel.playSound(null, target, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        this.playerNpc.setBucketCooldown();
        this.activeTicks = 0;
        return true;
    }

    private boolean canPlaceWater(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)
                || serverLevel.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)) {
            return false;
        }

        FluidState fluidState = serverLevel.getFluidState(pos);
        if (fluidState.is(FluidTags.WATER)) {
            return false;
        }
        if (!fluidState.isEmpty() && !fluidState.is(FluidTags.LAVA)) {
            return false;
        }

        BlockState blockState = serverLevel.getBlockState(pos);
        if (!fluidState.is(FluidTags.LAVA) && !blockState.isAir() && !blockState.canBeReplaced()) {
            return false;
        }

        BlockPos below = pos.below();
        BlockState support = serverLevel.getBlockState(below);
        return support.isFaceSturdy(serverLevel, below, Direction.UP)
                || !support.getCollisionShape(serverLevel, below).isEmpty();
    }

    private boolean isTouchingPlacedWater(ServerLevel serverLevel) {
        if (this.placedWaterPos == null) {
            return false;
        }
        FluidState fluidState = serverLevel.getFluidState(this.placedWaterPos);
        return fluidState.is(FluidTags.WATER)
                && fluidState.isSource()
                && this.playerNpc.isInWater()
                && this.playerNpc.getBoundingBox().intersects(new AABB(this.placedWaterPos).inflate(0.01D));
    }

    private boolean tryPickupWater(ServerLevel serverLevel) {
        if (this.placedWaterPos == null) {
            return false;
        }
        if (!serverLevel.hasChunkAt(this.placedWaterPos)
                || this.playerNpc.getEyePosition().distanceToSqr(Vec3.atCenterOf(this.placedWaterPos)) > BUCKET_REACH_SQR) {
            return false;
        }

        FluidState fluidState = serverLevel.getFluidState(this.placedWaterPos);
        if (!fluidState.is(FluidTags.WATER) || !fluidState.isSource()) {
            return false;
        }
        if (InventoryUtils.consumeItem(this.playerNpc, Items.BUCKET, 1).isEmpty()) {
            return false;
        }

        if (!serverLevel.setBlockAndUpdate(this.placedWaterPos, Blocks.AIR.defaultBlockState())) {
            this.giveOrDrop(new ItemStack(Items.BUCKET));
            return false;
        }
        this.giveOrDrop(new ItemStack(Items.WATER_BUCKET));
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, this.placedWaterPos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private void giveOrDrop(ItemStack stack) {
        if (!InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void reset() {
        this.placedWaterPos = null;
        this.activeTicks = 0;
        this.pickupDelayTicks = -1;
        this.contactedPlacedWater = false;
        this.finished = false;
    }
}
