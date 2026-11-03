package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcFarmPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FillWaterBucketGoal extends Goal {
    private static final int SEARCH_RADIUS = 10;
    private static final int SEARCH_INTERVAL_TICKS = 40;
    private static final int MAX_USE_TICKS = 120;
    private static final int MAX_REACHABLE_SOURCE_PATH_CHECKS = 24;

    private final Mob mob;
    private final double speedModifier;
    private BlockPos fluidPos;
    private BlockPos standPos;
    private Item filledBucket;
    private long nextSearchTick;
    private int useTicks;

    public FillWaterBucketGoal(Mob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!this.mob.isAlive()
                || this.mob.isPassenger()
                || this.mob.isNoAi()
                || this.mob.getTarget() != null
                || this.isHealing()
                || !InventoryUtils.hasItem(this.mob, Items.BUCKET)) {
            return false;
        }

        long gameTime = serverLevel.getGameTime();
        if (gameTime < this.nextSearchTick) {
            return false;
        }

        this.nextSearchTick = gameTime + SEARCH_INTERVAL_TICKS + this.mob.getRandom().nextInt(20);
        FluidTarget target = this.findNearestSourceFluid(serverLevel);
        if (target == null) {
            return false;
        }

        this.fluidPos = target.pos();
        this.standPos = target.standPos();
        this.filledBucket = target.filledBucket();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.fluidPos != null
                && this.standPos != null
                && this.filledBucket != null
                && this.useTicks < MAX_USE_TICKS
                && this.mob.getTarget() == null
                && InventoryUtils.hasItem(this.mob, Items.BUCKET)
                && this.mob.level() instanceof ServerLevel serverLevel
                && this.getFilledBucket(serverLevel, this.fluidPos) == this.filledBucket;
    }

    @Override
    public void start() {
        this.useTicks = 0;
        if (!this.moveToFluid()) {
            this.useTicks = MAX_USE_TICKS;
        }
    }

    @Override
    public void stop() {
        this.mob.getNavigation().stop();
        this.fluidPos = null;
        this.standPos = null;
        this.filledBucket = null;
        this.useTicks = 0;
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || this.fluidPos == null || this.filledBucket == null) {
            return;
        }

        this.useTicks++;
        this.mob.getLookControl().setLookAt(
                this.fluidPos.getX() + 0.5D,
                this.fluidPos.getY() + 0.5D,
                this.fluidPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (this.mob.distanceToSqr(Vec3.atCenterOf(this.fluidPos)) > 4.0D) {
            if (this.useTicks % 20 == 0) {
                if (!this.moveToFluid()) {
                    this.useTicks = MAX_USE_TICKS;
                }
            }
            return;
        }

        if (this.getFilledBucket(serverLevel, this.fluidPos) != this.filledBucket
                || InventoryUtils.consumeItem(this.mob, Items.BUCKET, 1).isEmpty()) {
            return;
        }

        serverLevel.setBlockAndUpdate(this.fluidPos, Blocks.AIR.defaultBlockState());
        ItemStack filledStack = new ItemStack(this.filledBucket);
        ItemStack remainder = InventoryUtils.addItemAndReturnRemainder(this.mob, filledStack);
        if (!remainder.isEmpty()) {
            this.mob.spawnAtLocation(serverLevel, remainder);
        }
        serverLevel.playSound(null, this.fluidPos, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
        this.stop();
    }

    private boolean moveToFluid() {
        if (this.standPos == null) {
            return false;
        }
        if (this.mob.blockPosition().equals(this.standPos)) {
            this.mob.getNavigation().stop();
            return true;
        }
        Path path = this.mob.getNavigation().createPath(this.standPos, 0);
        Node end = path == null ? null : path.getEndNode();
        return path != null
                && path.canReach()
                && end != null
                && end.asBlockPos().equals(this.standPos)
                && this.mob.getNavigation().moveTo(path, this.speedModifier);
    }

    private FluidTarget findNearestSourceFluid(ServerLevel serverLevel) {
        BlockPos origin = this.mob.blockPosition();
        List<FluidSource> sources = new ArrayList<>();

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (this.mob instanceof PlayerNpcEntity playerNpc
                            && PlayerNpcFarmPlan.isOwnedFarmIrrigationWater(playerNpc, pos)) {
                        continue;
                    }
                    Item filledBucket = this.getFilledBucket(serverLevel, pos);
                    if (filledBucket == null) {
                        continue;
                    }

                    sources.add(new FluidSource(pos.immutable(), filledBucket));
                }
            }
        }
        sources.sort(Comparator.comparingDouble(source -> origin.distSqr(source.pos())));

        Set<BlockPos> checkedStands = new HashSet<>();
        int pathChecks = 0;
        for (FluidSource source : sources) {
            for (BlockPos stand : this.fluidInteractionStands(source.pos(), origin)) {
                if (!checkedStands.add(stand)
                        || !PathNavigationAi.canStandAt(serverLevel, stand)) {
                    continue;
                }
                if (this.mob.blockPosition().equals(stand)) {
                    return new FluidTarget(source.pos(), stand.immutable(), source.filledBucket());
                }
                if (pathChecks++ >= MAX_REACHABLE_SOURCE_PATH_CHECKS) {
                    return null;
                }
                Path path = this.mob.getNavigation().createPath(stand, 0);
                Node end = path == null ? null : path.getEndNode();
                if (path != null
                        && path.canReach()
                        && end != null
                        && end.asBlockPos().equals(stand)) {
                    return new FluidTarget(source.pos(), stand.immutable(), source.filledBucket());
                }
            }
        }
        return null;
    }

    private List<BlockPos> fluidInteractionStands(BlockPos source, BlockPos origin) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = source.relative(direction);
            candidates.add(adjacent);
            candidates.add(adjacent.above());
            candidates.add(adjacent.below());
        }
        candidates.sort(Comparator.comparingDouble(origin::distSqr));
        return candidates;
    }

    private Item getFilledBucket(ServerLevel serverLevel, BlockPos pos) {
        FluidState fluidState = serverLevel.getFluidState(pos);
        if (!fluidState.isSource()) {
            return null;
        }
        if (fluidState.is(FluidTags.WATER)) {
            return Items.WATER_BUCKET;
        }
        if (fluidState.is(FluidTags.LAVA)) {
            return Items.LAVA_BUCKET;
        }
        return null;
    }

    private boolean isHealing() {
        if (this.mob instanceof PlayerNpcEntity playerNpcEntity) {
            return playerNpcEntity.isHealing();
        }
        return false;
    }

    private record FluidSource(BlockPos pos, Item filledBucket) {}

    private record FluidTarget(BlockPos pos, BlockPos standPos, Item filledBucket) {}
}
