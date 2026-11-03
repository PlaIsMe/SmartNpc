package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class CraftShieldGoal extends Goal {
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;
    private static final int COOLDOWN_TICKS = 20 * 75;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(40);

    public CraftShieldGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getShieldCraftCooldown() > 0
                || this.hasShield()
                || !this.canUseThrottle.canCheck(this.playerNpc)
                || this.playerNpc.getRandom().nextFloat() > 0.40F
                || !this.hasNearbyCraftingTable(serverLevel)) {
            return false;
        }

        return PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), Items.SHIELD, true);
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
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting_shield");
        ItemStack shield = PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), Items.SHIELD, true).orElse(ItemStack.EMPTY);
        if (!shield.isEmpty()) {
            if (!InventoryUtils.addItem(this.playerNpc, shield)) {
                this.playerNpc.spawnAtLocation(shield);
            }
            this.playerNpc.triggerMainHandUseAnimation();
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.7F, 1.15F);
        }
        this.playerNpc.setShieldCraftCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 90));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean hasShield() {
        return this.playerNpc.getOffhandItem().getItem() instanceof ShieldItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof ShieldItem);
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
