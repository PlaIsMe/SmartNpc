package com.pla.smart_npc.mixin.combat_evolution;

import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedMobPatch;
import net.shelmarow.combat_evolution.execution.ExecutionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

@Mixin(value = ExecutionHandler.class, remap = false)
public abstract class ExecutionHandlerMixin {
    @Inject(method = "isTargetSupported", at = @At("HEAD"), cancellable = true)
    private static void supportAdvancedMobPatchExecutions(
            LivingEntityPatch<?> executorPatch,
            LivingEntityPatch<?> targetPatch,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (targetPatch instanceof AdvancedMobPatch<?> advancedMobPatch) {
            cir.setReturnValue(advancedMobPatch.canBeExecuted(executorPatch));
        }
    }
}
