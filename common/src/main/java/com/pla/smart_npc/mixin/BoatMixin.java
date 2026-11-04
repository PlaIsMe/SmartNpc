package com.pla.smart_npc.mixin;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Boat.class)
public abstract class BoatMixin {
    @Inject(method = "canAddPassenger", at = @At("HEAD"), cancellable = true)
    private void blockSpecificMobsFromBoat(Entity passenger, CallbackInfoReturnable<Boolean> cir) {
        if (passenger instanceof PlayerNpcEntity) {
            cir.setReturnValue(false);
        }
    }
}
