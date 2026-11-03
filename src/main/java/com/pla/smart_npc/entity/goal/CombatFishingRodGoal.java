package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class CombatFishingRodGoal extends Goal {
    private static final double MIN_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double MAX_DISTANCE_SQR = 15.0D * 15.0D;
    private static final int CAST_TICKS = 8;
    private static final int PULL_TICKS = 22;
    private static final int COOLDOWN_TICKS = 20 * 12;

    private final PlayerNpcEntity playerNpc;
    private LivingEntity target;
    private ItemStack previousOffhand = ItemStack.EMPTY;
    private boolean usingTemporaryRod;
    private int castTicks;
    private int pullTicks;
    private boolean pulled;
    private boolean wasSprinting;

    public CombatFishingRodGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getCombatFishingCooldown() > 0
                || !this.hasFishingRod()) {
            return false;
        }

        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (currentTarget == null || !currentTarget.isAlive()) {
            return false;
        }

        double distanceSqr = this.playerNpc.distanceToSqr(currentTarget);
        if (distanceSqr < MIN_DISTANCE_SQR || distanceSqr > MAX_DISTANCE_SQR) {
            return false;
        }
        if (this.playerNpc.getRandom().nextFloat() > 0.22F) {
            return false;
        }

        this.target = currentTarget;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.pullTicks > 0
                && this.target != null
                && this.target.isAlive()
                && this.playerNpc.isAlive()
                && this.isHoldingRod();
    }

    @Override
    public void start() {
        if (!this.equipRodIfNeeded()) {
            this.target = null;
            return;
        }

        this.castTicks = CAST_TICKS;
        this.pullTicks = PULL_TICKS;
        this.pulled = false;
        this.wasSprinting = this.playerNpc.isSprinting();
        this.playerNpc.setSprinting(false);
        this.playerNpc.setCurrentAiState("ai.player_npc.combat_fishing");
        if (this.target != null) {
            this.playerNpc.setCurrentAiDetail(this.target.getDisplayName().getString());
            this.playerNpc.getLookControl().setLookAt(this.target, 60.0F, 60.0F);
        }
        this.playerNpc.swing(InteractionHand.OFF_HAND, true);
        this.playerNpc.playSound(SoundEvents.FISHING_BOBBER_THROW, 1.0F, 1.0F);
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }

        this.playerNpc.setSprinting(false);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.target, 60.0F, 60.0F);
        if (this.castTicks > 0) {
            this.castTicks--;
            return;
        }

        if (!this.pulled) {
            this.pullTarget();
            this.pulled = true;
            this.playerNpc.hurtItemInHand(InteractionHand.OFF_HAND, 1);
            this.playerNpc.swing(InteractionHand.OFF_HAND, true);
            this.playerNpc.level().playSound(null, this.playerNpc.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.HOSTILE, 0.9F, 1.0F);
        } else if (this.pullTicks % 8 == 0) {
            this.pullTarget();
        }
        this.pullTicks--;
    }

    @Override
    public void stop() {
        if (this.usingTemporaryRod) {
            ItemStack rod = this.playerNpc.getOffhandItem().copy();
            if (!rod.isEmpty() && rod.getItem() instanceof FishingRodItem && !InventoryUtils.addItem(this.playerNpc, rod)) {
                this.playerNpc.spawnAtLocation(rod);
            }
            this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, this.previousOffhand.copy());
        }

        this.playerNpc.setSprinting(this.wasSprinting);
        this.wasSprinting = false;
        this.target = null;
        this.previousOffhand = ItemStack.EMPTY;
        this.usingTemporaryRod = false;
        this.castTicks = 0;
        this.pullTicks = 0;
        this.pulled = false;
        this.playerNpc.setCombatFishingCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 18));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void pullTarget() {
        if (this.target == null) {
            return;
        }

        Vec3 pull = this.playerNpc.position().add(0.0D, 0.35D, 0.0D).subtract(this.target.position());
        if (pull.lengthSqr() < 1.0E-4D) {
            return;
        }

        Vec3 motion = this.target.getDeltaMovement();
        Vec3 pullMotion = pull.normalize().scale(0.48D);
        this.target.setDeltaMovement(motion.x + pullMotion.x, Math.min(motion.y + 0.12D, 0.35D), motion.z + pullMotion.z);
        this.target.hurtMarked = true;
        this.target.hurtMarked = true;
    }

    private boolean hasFishingRod() {
        return this.playerNpc.getOffhandItem().getItem() instanceof FishingRodItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof FishingRodItem);
    }

    private boolean isHoldingRod() {
        return this.playerNpc.getOffhandItem().getItem() instanceof FishingRodItem;
    }

    private boolean equipRodIfNeeded() {
        if (this.playerNpc.getOffhandItem().getItem() instanceof FishingRodItem) {
            return true;
        }

        ItemStack rod = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof FishingRodItem, 1).orElse(ItemStack.EMPTY);
        if (rod.isEmpty()) {
            return false;
        }

        this.previousOffhand = this.playerNpc.getOffhandItem().copy();
        this.usingTemporaryRod = true;
        this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, rod);
        return true;
    }
}
