package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class BoatStockpileGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 40;
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;
    private static final int CAN_USE_CHECK_INTERVAL_TICKS = 40;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(CAN_USE_CHECK_INTERVAL_TICKS);

    public BoatStockpileGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getTarget() != null
                || !this.playerNpc.isBoatCollector()
                || this.playerNpc.getBoatStockCooldown() > 0
                || this.countBoats() >= this.playerNpc.getDesiredBoatCount()) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)
                || !this.hasNearbyCraftingTable(serverLevel)) {
            return false;
        }

        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 5;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting_boat");
        this.playerNpc.setCurrentAiDetail(this.countBoats() + "/" + this.playerNpc.getDesiredBoatCount());
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 5, this.playerNpc.getRawLogReserveTarget())) {
            InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.OAK_BOAT));
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.6F, 1.2F);
        }
        this.playerNpc.setBoatStockCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 40));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private int countBoats() {
        int count = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BoatItem) {
                count += stack.getCount();
            }
        }
        if (this.playerNpc.getMainHandItem().getItem() instanceof BoatItem) {
            count += this.playerNpc.getMainHandItem().getCount();
        }
        if (this.playerNpc.getOffhandItem().getItem() instanceof BoatItem) {
            count += this.playerNpc.getOffhandItem().getCount();
        }
        return count;
    }

    private boolean hasNearbyCraftingTable(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-CRAFTING_TABLE_SCAN_RADIUS, -2, -CRAFTING_TABLE_SCAN_RADIUS),
                origin.offset(CRAFTING_TABLE_SCAN_RADIUS, 2, CRAFTING_TABLE_SCAN_RADIUS))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }
}
