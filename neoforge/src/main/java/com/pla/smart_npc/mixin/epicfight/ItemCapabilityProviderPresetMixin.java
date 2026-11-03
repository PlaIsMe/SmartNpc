package com.pla.smart_npc.mixin.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.pla.smart_npc.compat.epicfight.WeaponCapabilityPresetTracking;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.provider.CommonItemCapabilityProvider;

import java.util.function.Function;

@Mixin(value = CommonItemCapabilityProvider.class, remap = false)
public abstract class ItemCapabilityProviderPresetMixin {
    @WrapOperation(
            method = {"get", "lambda$addDefaultItems$3"},
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"),
            require = 3
    )
    private static Object smartnpc$rememberPreset(
            Function<Item, CapabilityItem.Builder> preset, Object item, Operation<Object> original) {
        return WeaponCapabilityPresetTracking.recordBuilder(
                (CapabilityItem.Builder) original.call(preset, item), preset);
    }
}
