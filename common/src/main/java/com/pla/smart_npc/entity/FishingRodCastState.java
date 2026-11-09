package com.pla.smart_npc.entity;

import net.minecraft.world.InteractionHand;

/** Cast state shared by an NPC and its client-only display player. */
public interface FishingRodCastState {
    boolean isFishingRodCast(InteractionHand hand);
}
