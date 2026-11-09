package com.pla.smart_npc.mixin;

import com.pla.smart_npc.entity.FishingRodCastState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.FishingRodCast;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingRodCast.class)
public abstract class FishingRodCastMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void smartNpc$showCastModel(ItemStack stack, @Nullable ClientLevel level,
                                        @Nullable LivingEntity owner, int seed,
                                        ItemDisplayContext displayContext,
                                        CallbackInfoReturnable<Boolean> callback) {
        if (owner instanceof FishingRodCastState castState) {
            boolean mainHand = owner.getMainHandItem() == stack
                    && castState.isFishingRodCast(InteractionHand.MAIN_HAND);
            boolean offHand = owner.getOffhandItem() == stack
                    && castState.isFishingRodCast(InteractionHand.OFF_HAND);
            callback.setReturnValue(mainHand || offHand);
        }
    }
}
