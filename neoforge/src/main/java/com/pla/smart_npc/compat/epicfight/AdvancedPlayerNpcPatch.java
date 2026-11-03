package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedCombatBehaviors;
import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedMobPatch;
import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedAnimationAttackGoal;
import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedChasingGoal;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.Style;
import yesman.epicfight.world.capabilities.item.CapabilityItem.WeaponCategories;

import java.util.List;
import java.util.EnumSet;

public class AdvancedPlayerNpcPatch<T extends PathfinderMob> extends AdvancedMobPatch<T> {
    public AdvancedPlayerNpcPatch(T original) {
        super(original, Factions.NEUTRAL);
        this.setChasingSpeed(1.0D);
    }

    @Override
    protected void configureCombatGoalControls(Goal attackGoal, Goal chasingGoal) {
        attackGoal.setFlags(EnumSet.of(Goal.Flag.LOOK));
        chasingGoal.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    protected int getAttackGoalPriority() {
        return 6;
    }

    @Override
    protected int getChasingGoalPriority() {
        return 6;
    }

    @Override
    protected boolean isCombatEnabled() {
        return !(this.getOriginal() instanceof PlayerNpcEntity npc)
                || !npc.hasInterest(PlayerNpcInterest.CAUTIOUS);
    }

    @Override
    protected boolean isUtilityActionActive() {
        if (this.getOriginal() instanceof PlayerNpcEntity npc
                && (npc.isHealing() || npc.isUsingItem() || npc.isEpicFightDigging() || npc.isSleeping())) {
            return true;
        }
        // Include the recovery frames of atomic item uses after their goal stops.
        var player = this.getAnimator().getPlayerFor(null);
        if (player != null && !player.isEmpty()
                && player.getRealAnimation().equals(EpicFightCloneAnimations.USE_MAINHAND)) {
            return true;
        }
        // Cheap selector state only; never call another goal's eligibility or paths here.
        for (WrappedGoal wrapped : this.getOriginal().goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning()
                    && !(wrapped.getGoal() instanceof AdvancedAnimationAttackGoal<?>)
                    && !(wrapped.getGoal() instanceof AdvancedChasingGoal)
                    && (wrapped.getFlags().contains(Goal.Flag.MOVE) || wrapped.getFlags().contains(Goal.Flag.LOOK))) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.IDLE, Animations.BIPED_IDLE);
        animator.addLivingAnimation(LivingMotions.WALK, Animations.BIPED_WALK);
        animator.addLivingAnimation(LivingMotions.RUN, Animations.BIPED_RUN);
        animator.addLivingAnimation(LivingMotions.CHASE, Animations.BIPED_RUN);
        animator.addLivingAnimation(LivingMotions.SNEAK, Animations.BIPED_SNEAK);
        animator.addLivingAnimation(LivingMotions.KNEEL, Animations.BIPED_KNEEL);
        animator.addLivingAnimation(LivingMotions.FALL, Animations.BIPED_FALL);
        animator.addLivingAnimation(LivingMotions.MOUNT, Animations.BIPED_MOUNT);
        animator.addLivingAnimation(LivingMotions.SLEEP, Animations.BIPED_SLEEPING);
        animator.addLivingAnimation(LivingMotions.DEATH, Animations.BIPED_DEATH);
        if (EpicFightCloneAnimations.DIG_MAINHAND != null) {
            animator.addLivingAnimation(LivingMotions.DIGGING, EpicFightCloneAnimations.DIG_MAINHAND);
        }
        if (EpicFightCloneAnimations.EAT_MAINHAND != null) {
            animator.addLivingAnimation(LivingMotions.EAT, EpicFightCloneAnimations.EAT_MAINHAND);
        }
    }

    @Override
    protected void addCustomBehaviorRoots(AdvancedCombatBehaviors.Builder<MobPatch<?>> builder,
                                          CapabilityItem mainHandCap,
                                          CapabilityItem offHandCap, Style style) {
        builder.newBehaviorRoot(
                        AdvancedCombatBehaviors.BehaviorRoot.builder()
                                .priority(1.0D)
                                .weight(10.0D)
                                .maxCooldown(80)
                                .waitForAnimationCompletion()
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .withinDistance(0.0D, 5.0D)
                                                .animationBehavior(Animations.BIPED_ROLL_BACKWARD, 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .withinDistance(0.0D, 5.0D)
                                                .animationBehavior(Animations.BIPED_ROLL_BACKWARD, 0.0F)
                                )
                );
    }

    @Override
    public boolean canGuard() {
        return this.getLocalGuardAnimation() != null;
    }

    @Override
    public int getGuardChance() {
        return 12;
    }

    //    mixin this method at head for more compat moveset, do not ci.cancel
    public List<AdditionalAttackGroup> addMoreAttackGroups(CapabilityItem mainHandCap, CapabilityItem offHandCap, Style style) {
        return super.getAdditionalAttackGroups(mainHandCap, offHandCap, style);
    }

    @Override
    protected List<AdditionalAttackGroup> getAdditionalAttackGroups(CapabilityItem mainHandCap, CapabilityItem offHandCap, Style style) {
        var category = mainHandCap.getWeaponCategory();
        if (category == WeaponCategories.SWORD) {
            return style == CapabilityItem.Styles.TWO_HAND
                    ? List.of(AdditionalAttackGroup.random(0.25F, Animations.SWEEPING_EDGE))
                    : List.of(AdditionalAttackGroup.random(0.25F, Animations.DANCING_EDGE)
            );
        }
        if (category == WeaponCategories.AXE) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.THE_GUILLOTINE));
        }
        if (category == WeaponCategories.SPEAR) {
            return style == CapabilityItem.Styles.TWO_HAND
                    ? List.of(AdditionalAttackGroup.random(0.25F, Animations.GRASPING_SPIRAL_FIRST, Animations.GRASPING_SPIRAL_SECOND))
                    : List.of(AdditionalAttackGroup.random(0.25F, Animations.HEARTPIERCER)
            );
        }
        if (category == WeaponCategories.GREATSWORD) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.STEEL_WHIRLWIND));
        }
        if (category == WeaponCategories.UCHIGATANA) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.BATTOJUTSU, Animations.BATTOJUTSU_DASH));
        }
        if (category == WeaponCategories.LONGSWORD) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.SHARP_STAB));
        }
        if (category == WeaponCategories.DAGGER) {
            return style == CapabilityItem.Styles.TWO_HAND
                    ? List.of(AdditionalAttackGroup.random(0.25F, Animations.BLADE_RUSH_COMBO1, Animations.BLADE_RUSH_COMBO2, Animations.BLADE_RUSH_COMBO3))
                    : List.of(AdditionalAttackGroup.random(0.25F, Animations.EVISCERATE_FIRST, Animations.EVISCERATE_SECOND)
            );
        }
        if (category == WeaponCategories.FIST) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.RELENTLESS_COMBO));
        }
        return addMoreAttackGroups(mainHandCap, offHandCap, style);
    }

    @Override
    public void updateMotion(boolean considerInaction) {
        super.updateMotion(considerInaction);
        if (this.getOriginal() instanceof PlayerNpcEntity playerNpc) {
            boolean eating = !playerNpc.isSleeping() && playerNpc.isAlive()
                    && playerNpc.isHealing() && playerNpc.getMainHandItem().has(net.minecraft.core.component.DataComponents.FOOD);
            EpicFight.updateClientEatingAnimation(this, eating);
            if (playerNpc.isSleeping()) {
                this.currentLivingMotion = LivingMotions.SLEEP;
                this.currentCompositeMotion = LivingMotions.SLEEP;
            } else if (eating) {
                this.currentCompositeMotion = LivingMotions.EAT;
            } else if (playerNpc.isEpicFightDigging()) {
                this.currentLivingMotion = LivingMotions.DIGGING;
                this.currentCompositeMotion = LivingMotions.DIGGING;
            } else if ((playerNpc.isShiftKeyDown() || playerNpc.isCrouching()) && canApplyCrouchMotion()) {
                this.currentLivingMotion = isMovingMotion() ? LivingMotions.SNEAK : LivingMotions.KNEEL;
                this.currentCompositeMotion = this.currentLivingMotion;
            } else if (playerNpc.isSprinting() && isMovingMotion()) {
                this.currentLivingMotion = LivingMotions.RUN;
                this.currentCompositeMotion = LivingMotions.RUN;
            }
        }
    }

    private boolean canApplyCrouchMotion() {
        return this.currentLivingMotion == LivingMotions.IDLE
                || this.currentLivingMotion == LivingMotions.WALK
                || this.currentLivingMotion == LivingMotions.RUN
                || this.currentLivingMotion == LivingMotions.CHASE;
    }

    private boolean isMovingMotion() {
        return this.currentLivingMotion == LivingMotions.WALK
                || this.currentLivingMotion == LivingMotions.RUN
                || this.currentLivingMotion == LivingMotions.CHASE;
    }
}
