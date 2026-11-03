package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.platform.event.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void smartNpc$tab(CallbackInfoReturnable<Component> cir) {
        var event = new NpcEvents.PlayerEvent.TabListNameFormat((ServerPlayer)(Object)this);
        NpcEventHooks.fire(event);
        if (event.getDisplayName() != null) cir.setReturnValue(event.getDisplayName());
    }
}
