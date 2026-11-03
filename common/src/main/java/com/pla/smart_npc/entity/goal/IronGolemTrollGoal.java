package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class IronGolemTrollGoal extends Goal {
    private static final double SEARCH_RADIUS = 10.0D;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(40);
    private IronGolem golem;
    private BlockPos pillarBase;

    public IronGolemTrollGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getIronGolemTrollCooldown() > 0
                || !this.canUseThrottle.canCheck(this.playerNpc)
                || this.playerNpc.getRandom().nextFloat() > 0.015F
                || this.countBlocks() < 3) {
            return false;
        }

        this.golem = this.findGolem();
        this.pillarBase = this.golem == null ? null : this.findPillarBase(serverLevel);
        return this.golem != null && this.pillarBase != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.golem == null || this.pillarBase == null) {
            return;
        }

        List<ItemStack> blocks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ItemStack stack = InventoryUtils.consumeItem(this.playerNpc, this::isTrollBlock, 1).orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                blocks.forEach(this::returnStack);
                return;
            }
            blocks.add(stack);
        }

        for (int i = 0; i < 3; i++) {
            ItemStack stack = blocks.get(i);
            if (stack.getItem() instanceof BlockItem blockItem
                    && !this.placingBlockAi.placeBlock(serverLevel, this.pillarBase.above(i), blockItem.getBlock().defaultBlockState(), false)) {
                blocks.subList(i, blocks.size()).forEach(this::returnStack);
                return;
            }
        }

        this.playerNpc.snapTo(this.pillarBase.getX() + 0.5D, this.pillarBase.getY() + 3.0D, this.pillarBase.getZ() + 0.5D, this.playerNpc.getYRot(), this.playerNpc.getXRot());
        this.playerNpc.setTarget(this.golem);
        this.playerNpc.setCurrentAiState("ai.player_npc.trolling_golem");
        this.playerNpc.setCurrentAiDetail(this.golem.getDisplayName().getString());
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        serverLevel.playSound(null, this.pillarBase, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
        serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.6F, 1.1F);
        this.playerNpc.setIronGolemTrollCooldown(20 * 60 * 10 + this.playerNpc.getRandom().nextInt(20 * 60 * 10));
        this.golem = null;
        this.pillarBase = null;
    }

    private IronGolem findGolem() {
        List<IronGolem> golems = this.playerNpc.level().getEntitiesOfClass(
                IronGolem.class,
                this.playerNpc.getBoundingBox().inflate(SEARCH_RADIUS),
                golem -> golem.isAlive() && !golem.isAlliedTo(this.playerNpc)
        );
        if (golems.isEmpty()) {
            return null;
        }
        golems.sort(Comparator.comparingDouble(this.playerNpc::distanceToSqr));
        return golems.get(0);
    }

    private BlockPos findPillarBase(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3, 0, -3), center.offset(3, 1, 3))) {
            BlockPos base = pos.immutable();
            if (serverLevel.getBlockState(base.below()).isSolidRender()
                    && serverLevel.getBlockState(base).isAir()
                    && serverLevel.getBlockState(base.above()).isAir()
                    && serverLevel.getBlockState(base.above(2)).isAir()
                    && serverLevel.getBlockState(base.above(3)).isAir()) {
                return base;
            }
        }
        return null;
    }

    private int countBlocks() {
        int count = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (this.isTrollBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isTrollBlock(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && !stack.is(Items.CRAFTING_TABLE)
                && !stack.is(Items.CHEST)
                && !stack.is(Items.FURNACE)
                && !(stack.getItem() instanceof BedItem)
                && !((BlockItem) stack.getItem()).getBlock().defaultBlockState().is(Blocks.TORCH);
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }
}
