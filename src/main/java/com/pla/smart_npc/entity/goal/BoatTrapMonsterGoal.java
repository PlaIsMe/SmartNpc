package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

public class BoatTrapMonsterGoal extends Goal {
    private static final double MAX_TRAP_DISTANCE_SQR = 5.5D * 5.5D;
    private static final int COOLDOWN_TICKS = 20 * 22;

    private final PlayerNpcEntity playerNpc;
    private LivingEntity target;

    public BoatTrapMonsterGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getBoatTrapCooldown() > 0
                || !InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BoatItem)) {
            return false;
        }

        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (!this.isValidTrapTarget(currentTarget)
                || currentTarget.isPassenger()
                || this.playerNpc.distanceToSqr(currentTarget) > MAX_TRAP_DISTANCE_SQR) {
            return false;
        }

        this.target = currentTarget;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.target == null) {
            return;
        }

        ItemStack boatStack = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BoatItem, 1).orElse(ItemStack.EMPTY);
        if (boatStack.isEmpty()) {
            this.target = null;
            return;
        }

        Boat boat = EntityType.OAK_BOAT.create(serverLevel, EntitySpawnReason.TRIGGERED);
        if (boat == null) {
            InventoryUtils.addItem(this.playerNpc, boatStack);
            this.target = null;
            return;
        }
        boat.snapTo(this.target.getX(), this.target.getY(), this.target.getZ(), this.target.getYRot(), 0.0F);
        boat.setYRot(this.target.getYRot());
        if (!serverLevel.noCollision(boat, boat.getBoundingBox())) {
            if (!InventoryUtils.addItem(this.playerNpc, boatStack)) {
                this.playerNpc.spawnAtLocation(boatStack);
            }
            this.target = null;
            return;
        }

        serverLevel.addFreshEntity(boat);
        this.target.startRiding(boat);
        this.playerNpc.getLookControl().setLookAt(this.target, 40.0F, 40.0F);
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        this.playerNpc.setCurrentAiState("ai.player_npc.trapping_monster_boat");
        this.playerNpc.setCurrentAiDetail(this.target.getDisplayName().getString());
        serverLevel.playSound(null, this.target.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.HOSTILE, 0.8F, 1.0F);
        this.playerNpc.setBoatTrapCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 25));
        this.target = null;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean isValidTrapTarget(LivingEntity target) {
        return target instanceof Monster
                && !(target instanceof PlayerNpcEntity)
                && !(target instanceof Player)
                && target.isAlive();
    }
}
