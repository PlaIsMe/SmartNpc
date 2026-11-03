package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedCombatBehaviors;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;

/**
 * Builds Combat Evolution behaviors only after Forge has confirmed that the mod is loaded.
 * Keeping these references out of AdvancedPlayerNpcPatch prevents the JVM from resolving
 * Combat Evolution classes while that optional mod is absent.
 */
final class CombatEvolutionBehaviorProvider {
    private CombatEvolutionBehaviorProvider() {
    }

    static void addTo(AdvancedCombatBehaviors.Builder<MobPatch<?>> builder) {
        builder.newBehaviorRoot(
                AdvancedCombatBehaviors.BehaviorRoot.builder()
                        .priority(4.0D)
                        .weight(1000.0D)
                        .maxCooldown(0)
                        .waitForAnimationCompletion()
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .custom(CombatEvolution::canExecute)
                                        .withinDistance(0.0D, 5.0D)
                                        .animationBehavior(Animations.BIPED_SNEAK, 0.0F)
                                        .addExBehavior(CombatEvolution::performExecute)
                        )
        );
    }
}
