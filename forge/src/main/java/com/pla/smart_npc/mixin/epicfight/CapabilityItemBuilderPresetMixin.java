package com.pla.smart_npc.mixin.epicfight;

import com.pla.smart_npc.compat.epicfight.WeaponCapabilityPresetTracking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.world.capabilities.item.CapabilityItem;

@Mixin(value = CapabilityItem.Builder.class, remap = false)
public abstract class CapabilityItemBuilderPresetMixin {
    @Inject(method = "build", at = @At("RETURN"), require = 1)
    private void smartnpc$rememberPreset(CallbackInfoReturnable<CapabilityItem> cir) {
        WeaponCapabilityPresetTracking.recordBuiltCapability(
                (CapabilityItem.Builder) (Object) this, cir.getReturnValue());
    }
}
