package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;

import java.util.EnumSet;

/** Vanilla bow movement and charging for a PathfinderMob; vanilla's generic bound requires a Monster on Fabric. */
public class NpcRangedBowGoal extends Goal {
    private final PlayerNpcEntity mob;
    private final double speed;
    private final int attackInterval;
    private final float radiusSquared;
    private int attackTime = -1;
    private int seeTime;
    private int strafingTime = -1;
    private boolean clockwise;
    private boolean backwards;

    protected NpcRangedBowGoal(PlayerNpcEntity mob, double speed, int interval, float radius) {
        this.mob = mob;
        this.speed = speed;
        this.attackInterval = interval;
        this.radiusSquared = radius * radius;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    protected boolean isHoldingBow() { return this.mob.isHolding(stack -> stack.getItem() instanceof BowItem); }
    @Override public boolean canUse() { return this.mob.getTarget() != null && this.isHoldingBow(); }
    @Override public boolean canContinueToUse() { return (this.canUse() || !this.mob.getNavigation().isDone()) && this.isHoldingBow(); }
    @Override public void start() { this.mob.setAggressive(true); }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void stop() {
        this.mob.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
        this.mob.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;
        double distance = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean visible = this.mob.getSensing().hasLineOfSight(target);
        if (visible != this.seeTime > 0) this.seeTime = 0;
        this.seeTime += visible ? 1 : -1;
        if (distance <= this.radiusSquared && this.seeTime >= 20) {
            this.mob.getNavigation().stop();
            this.strafingTime++;
        } else {
            this.mob.getNavigation().moveTo(target, this.speed);
            this.strafingTime = -1;
        }
        if (this.strafingTime >= 20) {
            if (this.mob.getRandom().nextFloat() < 0.3F) this.clockwise = !this.clockwise;
            if (this.mob.getRandom().nextFloat() < 0.3F) this.backwards = !this.backwards;
            this.strafingTime = 0;
        }
        if (this.strafingTime > -1) {
            if (distance > this.radiusSquared * 0.75F) this.backwards = false;
            else if (distance < this.radiusSquared * 0.25F) this.backwards = true;
            this.mob.getMoveControl().strafe(this.backwards ? -0.5F : 0.5F, this.clockwise ? 0.5F : -0.5F);
            this.mob.lookAt(target, 30.0F, 30.0F);
        } else {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        if (this.mob.isUsingItem()) {
            if (!visible && this.seeTime < -60) this.mob.stopUsingItem();
            else if (visible && this.mob.getTicksUsingItem() >= 20) {
                int ticks = this.mob.getTicksUsingItem();
                this.mob.stopUsingItem();
                this.mob.performRangedAttack(target, BowItem.getPowerForTime(ticks));
                this.attackTime = this.attackInterval;
            }
        } else if (--this.attackTime <= 0 && this.seeTime >= -60) {
            this.mob.startUsingItem(this.mob.getMainHandItem().getItem() instanceof BowItem ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        }
    }
}
