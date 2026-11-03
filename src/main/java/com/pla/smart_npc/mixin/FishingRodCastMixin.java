package com.pla.smart_npc.mixin;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.FishingRodCast;
import net.minecraft.world.entity.LivingEntity;
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
        if (owner instanceof PlayerNpcEntity npc) {
            boolean held = npc.getMainHandItem() == stack || npc.getOffhandItem() == stack;
            boolean fishing = "ai.player_npc.fishing".equals(npc.getCurrentAiState())
                    || "ai.player_npc.combat_fishing".equals(npc.getCurrentAiState());
            callback.setReturnValue(held && fishing);
        }
    }
}
