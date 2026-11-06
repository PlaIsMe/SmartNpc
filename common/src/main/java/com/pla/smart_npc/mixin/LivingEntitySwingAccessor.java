package com.pla.smart_npc.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Clears a previous swing when the eating goal takes ownership of the hand. */
@Mixin(LivingEntity.class)
public interface LivingEntitySwingAccessor {
    @Mutable
    @Accessor("swingState")
    void smartNpc$setSwingState(LivingEntity.SwingState state);
}
