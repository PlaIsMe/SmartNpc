package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.BedItem;

import java.util.Comparator;
import java.util.EnumSet;

public class HuntSheepForBedGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 30;
    private static final double SEARCH_RANGE = 18.0D;

    private final PlayerNpcEntity playerNpc;
    private Sheep sheep;

    public HuntSheepForBedGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.hasAnimalLootPriority()
                || this.playerNpc.hasCollectableSupplyDropNearby(24.0D)
                || this.playerNpc.getHuntSheepCooldown() > 0
                || this.hasBedOrEnoughWool()) {
            return false;
        }

        this.sheep = serverLevel.getEntitiesOfClass(
                        Sheep.class,
                        this.playerNpc.getBoundingBox().inflate(SEARCH_RANGE),
                        sheepEntity -> sheepEntity.isAlive() && !sheepEntity.isBaby()
                )
                .stream()
                .min(Comparator.comparingDouble(this.playerNpc::distanceToSqr))
                .orElse(null);
        return this.sheep != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (this.sheep == null || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.playerNpc.setTarget(this.sheep);
        this.playerNpc.setCurrentAiState("ai.player_npc.hunting_sheep");
        this.playerNpc.setCurrentAiDetail("bed wool");
        this.playerNpc.setHuntSheepCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 20));
        this.sheep = null;
    }

    private boolean hasBedOrEnoughWool() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BedItem)
                || PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(ItemTags.WOOL)) >= 3;
    }
}
