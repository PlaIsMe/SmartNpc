package com.pla.smart_npc.mixin.epicfight;

import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.client.events.engine.RenderEngine;

/** YSM owns this display pass; the real NPC patch supplies the pose separately. */
@Mixin(value = RenderEngine.class, remap = false)
public abstract class YsmDisplayRenderMixin {
    @Inject(method = "epicfight$renderLivingPre(Lnet/neoforged/neoforge/client/event/RenderLivingEvent$Pre;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void smartNpc$keepYsmDisplay(RenderLivingEvent.Pre<?, ?> event, CallbackInfo ci) {
        if (event.getEntity() instanceof YesSteveModelClientCompat.DisplayProxy) {
            // Cancel only Epic Fight's callback, leaving the native YSM event/render alive.
            // Otherwise getEntityPatch lazily creates an unticked player patch on the proxy.
            ci.cancel();
        }
    }
}
