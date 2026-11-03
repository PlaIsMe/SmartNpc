package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.platform.event.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MinecraftScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void smartNpc$screen(Screen screen, CallbackInfo ci) {
        var event = new NpcClientEvents.ScreenEvent.Opening(screen);
        NpcClientEventHooks.fire(event);
        if (event.isCanceled()) ci.cancel();
    }
}
