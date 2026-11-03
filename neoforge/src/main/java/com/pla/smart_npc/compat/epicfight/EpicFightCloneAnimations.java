package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.SmartNpc;
import net.neoforged.bus.api.SubscribeEvent;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.property.AnimationProperty.StaticAnimationProperty;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.model.armature.HumanoidArmature;

public final class EpicFightCloneAnimations {
    public static AnimationManager.AnimationAccessor<StaticAnimation> DIG_MAINHAND;
    public static AnimationManager.AnimationAccessor<StaticAnimation> USE_MAINHAND;
    public static AnimationManager.AnimationAccessor<StaticAnimation> EAT_MAINHAND;

    private EpicFightCloneAnimations() {
    }

    @SubscribeEvent
    public static void registerAnimations(AnimationManager.AnimationRegistryEvent event) {
        event.newBuilder(SmartNpc.MODID, EpicFightCloneAnimations::build);
    }

    private static void build(AnimationManager.AnimationBuilder builder) {
        Armatures.ArmatureAccessor<HumanoidArmature> humanoidArmature = Armatures.BIPED;
        DIG_MAINHAND = builder.nextAccessor("biped/living/dig_mainhand",
                accessor -> new StaticAnimation(0.1F, true, accessor, humanoidArmature)
                        .addState(EntityState.COMBO_ATTACKS_DOABLE, false));
        USE_MAINHAND = builder.nextAccessor("biped/living/use_mainhand",
                accessor -> new StaticAnimation(0.1F, false, accessor, humanoidArmature)
                        .addState(EntityState.COMBO_ATTACKS_DOABLE, false));
        EAT_MAINHAND = builder.nextAccessor("biped/living/eat_mainhand",
                accessor -> new StaticAnimation(0.1F, true, accessor, humanoidArmature)
                        .addProperty(StaticAnimationProperty.FIXED_HEAD_ROTATION, true)
                        .addState(EntityState.COMBO_ATTACKS_DOABLE, false));
    }
}
