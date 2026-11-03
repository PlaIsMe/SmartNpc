/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Goal behavior adapted from Combat Evolution by ShelMarow.
 */
package com.pla.smart_npc.compat.epicfight.advancedmobpatch;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;

import java.util.function.BooleanSupplier;

public final class AdvancedAnimationAttackGoal<T extends MobPatch<?>> extends Goal {
    private final T mobPatch;
    private final AdvancedCombatBehaviors<T> combatBehaviors;
    private final BooleanSupplier actionAllowed;
    private final BooleanSupplier tryStartGuard;
    private AssetAccessor<? extends StaticAnimation> ownedAnimation;

    public AdvancedAnimationAttackGoal(
            T mobPatch,
            AdvancedCombatBehaviors<T> combatBehaviors,
            BooleanSupplier actionAllowed,
            BooleanSupplier tryStartGuard
    ) {
        this.mobPatch = mobPatch;
        this.combatBehaviors = combatBehaviors;
        this.actionAllowed = actionAllowed;
        this.tryStartGuard = tryStartGuard;
    }

    @Override
    public boolean canUse() {
        var player = this.mobPatch.getAnimator().getPlayerFor(null);
        boolean finishingAction = this.ownedAnimation != null && player != null && !player.isEmpty()
                && this.ownedAnimation.equals(player.getRealAnimation());
        return this.actionAllowed.getAsBoolean() && (this.hasValidTarget() || finishingAction);
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void tick() {
        if (!this.actionAllowed.getAsBoolean() || !this.hasValidTarget()) {
            this.combatBehaviors.clearCurrentBehavior();
            return;
        }
        // Give a locally configured guard the same opening that an attack root
        // would otherwise claim immediately. The patch only permits this while
        // no behavior or animation is active, so an attack is never interrupted.
        if (this.tryStartGuard.getAsBoolean()) {
            this.combatBehaviors.clearCurrentBehavior();
            return;
        }
        var previousBehavior = this.combatBehaviors.getCurrentBehavior();
        this.combatBehaviors.tick(this.mobPatch);
        if (this.combatBehaviors.getCurrentBehavior() != null
                && this.combatBehaviors.getCurrentBehavior() != previousBehavior) {
            // Only execution of a new behavior can claim an animation. Waiting ticks must
            // not adopt an unrelated utility, stun, or externally supplied animation.
            var player = this.mobPatch.getAnimator().getPlayerFor(null);
            this.ownedAnimation = player != null && !player.isEmpty() ? player.getRealAnimation() : null;
        }
    }

    @Override
    public void stop() {
        this.combatBehaviors.clearCurrentBehavior();
        // Goal preemption must cancel the animation as well as the combo scheduler.
        // Stop only our animation: a stun or execution may already have replaced it.
        var player = this.mobPatch.getAnimator().getPlayerFor(null);
        if (this.ownedAnimation != null && player != null && !player.isEmpty()
                && !(this.mobPatch instanceof AdvancedMobPatch<?> advanced && advanced.isCombatActionLocked())
                && this.ownedAnimation.equals(player.getRealAnimation())) {
            this.mobPatch.stopPlaying(this.ownedAnimation);
        }
        this.ownedAnimation = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean hasValidTarget() {
        LivingEntity target = this.mobPatch.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        return !(target instanceof Player player) || (!player.isSpectator() && !player.isCreative());
    }
}
