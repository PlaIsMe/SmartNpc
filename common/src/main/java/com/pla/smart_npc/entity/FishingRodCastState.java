package com.pla.smart_npc.entity;

import net.minecraft.world.InteractionHand;

/** Hand-specific cast state for held fishing-rod models. */
public interface FishingRodCastState {
    boolean isFishingRodCast(InteractionHand hand);
}
