package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.platform.event.NpcEvents;
import com.pla.smart_npc.platform.event.NpcEventHooks;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void smartNpc$beforeServerStart(CallbackInfo ci) {
        // Capture YSM's old registry before any SERVER_STARTING reload listener runs.
        NpcEventHooks.fire(new NpcEvents.ServerAboutToStartEvent((MinecraftServer)(Object)this));
    }
}
