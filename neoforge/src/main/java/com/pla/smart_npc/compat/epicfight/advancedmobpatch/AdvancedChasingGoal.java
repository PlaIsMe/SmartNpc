/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Chasing behavior adapted from Combat Evolution by ShelMarow.
 */
package com.pla.smart_npc.compat.epicfight.advancedmobpatch;

import com.pla.smart_npc.entity.ai.CombatMovementAi;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;

public final class AdvancedChasingGoal extends Goal {
    private final MobPatch<? extends Mob> mobPatch;
    private final Mob mob;
    private final double attackRadius;
    private final double speedModifier;
    private final BooleanSupplier movementAllowed;
    private final CombatMovementAi combatMovement;

    public AdvancedChasingGoal(
            MobPatch<? extends Mob> mobPatch,
            double attackRadius,
            double speedModifier,
            BooleanSupplier movementAllowed
    ) {
        this.mobPatch = mobPatch;
        this.mob = mobPatch.getOriginal();
        this.attackRadius = Math.max(0.0D, attackRadius);
        this.speedModifier = Math.max(0.0D, speedModifier);
        this.movementAllowed = movementAllowed;
        this.combatMovement = new CombatMovementAi(this.mob);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.canChase(this.mob.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return this.canChase(this.mob.getTarget());
    }

    @Override
    public void start() {
        this.mob.setAggressive(true);
    }

    @Override
    public void stop() {
        LivingEntity target = this.mob.getTarget();
        if (target != null && !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            this.mob.setTarget(null);
        }
        this.mob.setAggressive(false);
        this.combatMovement.stop();
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (!this.canChase(target)) {
            this.combatMovement.stop();
            return;
        }

        if (!this.mobPatch.getEntityState().turningLocked()) {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (this.mob.distanceToSqr(target) <= this.attackRadius * this.attackRadius) {
                this.mobPatch.rotateTo(target, 30.0F, true);
            }
        }
        this.combatMovement.tick(target, this.attackRadius, this.speedModifier, !this.mobPatch.getEntityState().movementLocked());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean canChase(LivingEntity target) {
        return this.movementAllowed.getAsBoolean()
                && target != null
                && target.isAlive()
                && !target.isRemoved()
                && !this.mob.isAlliedTo(target)
                && !target.isAlliedTo(this.mob)
                && EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target);
    }
}
