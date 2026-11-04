package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

public class CraftCropFoodGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 18;

    private final PlayerNpcEntity playerNpc;

    public CraftCropFoodGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getCraftCooldown() <= 0
                && PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), Items.BREAD, true);
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
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting");
        this.playerNpc.setCurrentAiDetail("crafting bread");
        if (PlayerNpcCraftingUtil.tryCraftBread(serverLevel, this.playerNpc.getInventory())) {
            this.playerNpc.triggerMainHandUseAnimation();
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.5F, 1.2F);
        }
        this.playerNpc.setCraftCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 12));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }
}
