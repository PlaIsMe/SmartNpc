package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class UseLavaBucketGoal extends Goal {
    private static final double MAX_PLACE_DISTANCE_SQR = 6.0D * 6.0D;

    private final PlayerNpcEntity playerNpc;
    private BlockPos placePos;

    public UseLavaBucketGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.isClearingCombatObstruction()
                || !InventoryUtils.hasItem(this.playerNpc, Items.LAVA_BUCKET)
                || this.playerNpc.getBucketCooldown() > 0) {
            return false;
        }

        LivingEntity target = this.playerNpc.getTarget();
        if (target == null
                || !target.isAlive()
                || this.playerNpc.distanceToSqr(target) > MAX_PLACE_DISTANCE_SQR
                || target.isInWater()) {
            return false;
        }

        this.placePos = this.findPlacement(serverLevel, target);
        return this.placePos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.playerNpc.isClearingCombatObstruction()
                || this.placePos == null
                || !this.canPlaceLava(serverLevel, this.placePos)
                || InventoryUtils.consumeItem(this.playerNpc, Items.LAVA_BUCKET, 1).isEmpty()) {
            this.placePos = null;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.placePos.getX() + 0.5D, this.placePos.getY() + 0.5D, this.placePos.getZ() + 0.5D, 40.0F, 40.0F);
        this.playerNpc.setCurrentAiState("ai.player_npc.using_lava_bucket");
        if (!serverLevel.setBlockAndUpdate(this.placePos, Blocks.LAVA.defaultBlockState())) {
            this.giveOrDrop(new ItemStack(Items.LAVA_BUCKET));
            this.placePos = null;
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            return;
        }
        this.playerNpc.triggerMainHandUseAnimation();
        this.giveOrDrop(new ItemStack(Items.BUCKET));
        serverLevel.playSound(null, this.placePos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
        this.playerNpc.setBucketCooldown();
        this.placePos = null;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private BlockPos findPlacement(ServerLevel serverLevel, LivingEntity target) {
        BlockPos targetPos = target.blockPosition();
        Direction facing = Direction.fromYRot(target.getYRot());
        BlockPos[] candidates = {
                targetPos,
                targetPos.relative(facing.getOpposite()),
                targetPos.relative(facing.getClockWise()),
                targetPos.relative(facing.getCounterClockWise()),
                targetPos.above()
        };

        for (BlockPos candidate : candidates) {
            if (this.canPlaceLava(serverLevel, candidate)) {
                return candidate.immutable();
            }
        }

        return null;
    }

    private boolean canPlaceLava(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).isAir();
    }

    private void giveOrDrop(ItemStack stack) {
        if (!InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }
}
