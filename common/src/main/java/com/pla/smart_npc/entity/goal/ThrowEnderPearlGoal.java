package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class ThrowEnderPearlGoal extends Goal {
    private static final double MIN_CHASE_DISTANCE_SQR = 10.0D * 10.0D;
    private static final double CLOSE_ESCAPE_DISTANCE_SQR = 5.0D * 5.0D;
    private static final int THROW_WINDUP_TICKS = 6;
    private static final float RANDOM_REPOSITION_CHANCE = 0.08F;

    private final PlayerNpcEntity playerNpc;
    private Vec3 pearlTarget;
    private ItemStack previousOffHand = ItemStack.EMPTY;
    private ItemStack equippedPearl = ItemStack.EMPTY;
    private int throwTicks;
    private boolean usingTemporaryPearl;
    private boolean threwPearl;

    public ThrowEnderPearlGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.isClearingCombatObstruction()
                || this.playerNpc.getEnderPearlCooldown() > 0
                || !InventoryUtils.hasItem(this.playerNpc, Items.ENDER_PEARL)) {
            return false;
        }

        LivingEntity target = this.playerNpc.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        double distanceSqr = this.playerNpc.distanceToSqr(target);
        if (distanceSqr >= MIN_CHASE_DISTANCE_SQR && this.playerNpc.hasLineOfSight(target)) {
            this.pearlTarget = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
            return true;
        }

        boolean shouldEscape = distanceSqr <= CLOSE_ESCAPE_DISTANCE_SQR
                && this.playerNpc.getHealth() <= this.playerNpc.getMaxHealth() * 0.45F;
        if (shouldEscape) {
            Vec3 away = this.playerNpc.position().subtract(target.position());
            if (away.lengthSqr() < 1.0E-4D) {
                away = Vec3.directionFromRotation(0.0F, this.playerNpc.getYRot());
            }
            this.pearlTarget = this.playerNpc.position().add(away.normalize().scale(16.0D)).add(0.0D, 1.5D, 0.0D);
            return true;
        }

        if (this.playerNpc.getRandom().nextFloat() < RANDOM_REPOSITION_CHANCE) {
            this.pearlTarget = this.randomCombatPosition();
            return true;
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return this.throwTicks > 0
                && this.playerNpc.isAlive()
                && !this.playerNpc.isClearingCombatObstruction()
                && !this.equippedPearl.isEmpty()
                && this.pearlTarget != null;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.playerNpc.isClearingCombatObstruction()
                || this.pearlTarget == null
                || !this.equipPearl()) {
            this.reset();
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.pearlTarget.x, this.pearlTarget.y, this.pearlTarget.z, 60.0F, 60.0F);
        this.playerNpc.setCurrentAiState("ai.player_npc.throwing_ender_pearl");
        this.playerNpc.swing(InteractionHand.OFF_HAND, true);
        this.throwTicks = THROW_WINDUP_TICKS;
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.pearlTarget == null) {
            this.stop();
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.pearlTarget.x, this.pearlTarget.y, this.pearlTarget.z, 60.0F, 60.0F);
        this.throwTicks--;
        if (this.throwTicks > 0) {
            return;
        }

        this.throwPearl(serverLevel);
        this.stop();
    }

    @Override
    public void stop() {
        if (this.usingTemporaryPearl) {
            ItemStack currentOffHand = this.playerNpc.getOffhandItem().copy();
            if (!this.threwPearl && this.isSamePearl(currentOffHand)) {
                this.giveOrDrop(currentOffHand);
            } else if (!currentOffHand.isEmpty()
                    && !this.isSamePearl(currentOffHand)
                    && !ItemStack.isSameItemSameComponents(currentOffHand, this.previousOffHand)) {
                this.giveOrDrop(currentOffHand);
            }
            this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, this.previousOffHand.copy());
        }

        this.reset();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean equipPearl() {
        ItemStack pearl = InventoryUtils.consumeItem(this.playerNpc, Items.ENDER_PEARL, 1).orElse(ItemStack.EMPTY);
        if (pearl.isEmpty()) {
            return false;
        }

        this.previousOffHand = this.playerNpc.getOffhandItem().copy();
        this.equippedPearl = pearl.copy();
        this.equippedPearl.setCount(1);
        this.usingTemporaryPearl = true;
        this.threwPearl = false;
        this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, this.equippedPearl.copy());
        return true;
    }

    private void throwPearl(ServerLevel serverLevel) {
        if (this.playerNpc.isClearingCombatObstruction()) {
            return;
        }
        ItemStack heldPearl = this.playerNpc.getOffhandItem();
        if (!this.isSamePearl(heldPearl)) {
            return;
        }

        heldPearl.shrink(1);
        this.threwPearl = true;
        this.playerNpc.swing(InteractionHand.OFF_HAND, true);
        ThrownEnderpearl pearl = new ThrownEnderpearl(serverLevel, this.playerNpc);
        pearl.setPos(this.playerNpc.getX(), this.playerNpc.getEyeY() - 0.1D, this.playerNpc.getZ());
        double x = this.pearlTarget.x - pearl.getX();
        double y = this.pearlTarget.y - pearl.getY();
        double z = this.pearlTarget.z - pearl.getZ();
        double horizontalDistance = Math.sqrt(x * x + z * z);
        pearl.shoot(x, y + horizontalDistance * 0.15D, z, 1.5F, 1.0F);
        serverLevel.addFreshEntity(pearl);
        serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.ENDER_PEARL_THROW, SoundSource.HOSTILE, 1.0F, 1.0F);
        this.playerNpc.setEnderPearlCooldown();
    }

    private Vec3 randomCombatPosition() {
        double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
        double distance = 8.0D + this.playerNpc.getRandom().nextDouble() * 8.0D;
        return this.playerNpc.position().add(Math.cos(angle) * distance, 1.5D, Math.sin(angle) * distance);
    }

    private boolean isSamePearl(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, this.equippedPearl);
    }

    private void giveOrDrop(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void reset() {
        this.pearlTarget = null;
        this.previousOffHand = ItemStack.EMPTY;
        this.equippedPearl = ItemStack.EMPTY;
        this.throwTicks = 0;
        this.usingTemporaryPearl = false;
        this.threwPearl = false;
    }
}
