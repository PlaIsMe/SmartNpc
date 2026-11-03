package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

public class PlantSaplingGoal extends Goal {
    private static final int SEARCH_RADIUS = 8;
    private static final int COOLDOWN_TICKS = 20 * 25;
    private static final int CAN_USE_CHECK_INTERVAL_TICKS = 40;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(CAN_USE_CHECK_INTERVAL_TICKS);
    private BlockPos plantPos;
    private ItemStack saplingStack = ItemStack.EMPTY;

    public PlantSaplingGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
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
                || this.playerNpc.getSaplingPlantCooldown() > 0
                || !InventoryUtils.hasItem(this.playerNpc, stack -> stack.is(ItemTags.SAPLINGS) && stack.getItem() instanceof BlockItem)) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        PlantTarget target = this.findPlantTarget(serverLevel);
        if (target == null) {
            return false;
        }

        this.plantPos = target.pos();
        this.saplingStack = target.sapling();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.plantPos == null || this.saplingStack.isEmpty()) {
            return;
        }

        ItemStack consumed = this.playerNpc.consumeInventoryItem(stack -> ItemStack.isSameItemSameComponents(stack, this.saplingStack), 1).orElse(ItemStack.EMPTY);
        if (consumed.isEmpty() || !(consumed.getItem() instanceof BlockItem blockItem)) {
            this.clear();
            return;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!serverLevel.getBlockState(this.plantPos).isAir() || !state.canSurvive(serverLevel, this.plantPos)) {
            if (!InventoryUtils.addItem(this.playerNpc, consumed)) {
                this.playerNpc.spawnAtLocation(consumed);
            }
            this.clear();
            return;
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, this.plantPos, state)) {
            if (!InventoryUtils.addItem(this.playerNpc, consumed)) {
                this.playerNpc.spawnAtLocation(consumed);
            }
            this.clear();
            return;
        }
        this.playerNpc.getLookControl().setLookAt(this.plantPos.getX() + 0.5D, this.plantPos.getY() + 0.5D, this.plantPos.getZ() + 0.5D, 40.0F, 40.0F);
        this.playerNpc.setCurrentAiState("ai.player_npc.planting_sapling");
        this.playerNpc.setSaplingPlantCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 35));
        this.clear();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private PlantTarget findPlantTarget(ServerLevel serverLevel) {
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(ItemTags.SAPLINGS) || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }

            BlockState state = blockItem.getBlock().defaultBlockState();
            BlockPos center = this.playerNpc.blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -1, -SEARCH_RADIUS), center.offset(SEARCH_RADIUS, 1, SEARCH_RADIUS))) {
                BlockPos immutable = pos.immutable();
                if (serverLevel.getBlockState(immutable).isAir()
                        && state.canSurvive(serverLevel, immutable)
                        && this.playerNpc.distanceToSqr(immutable.getX() + 0.5D, immutable.getY(), immutable.getZ() + 0.5D) <= SEARCH_RADIUS * SEARCH_RADIUS) {
                    return new PlantTarget(immutable, oneOf(stack));
                }
            }
        }
        return null;
    }

    private void clear() {
        this.plantPos = null;
        this.saplingStack = ItemStack.EMPTY;
    }

    private static ItemStack oneOf(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    private record PlantTarget(BlockPos pos, ItemStack sapling) {}
}
