package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.platform.event.*;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Gui.class)
public abstract class GuiJumpBarMixin {
    @Inject(method = "renderJumpMeter", at = @At("HEAD"), cancellable = true)
    private void smartNpc$jump(PlayerRideableJumping mount, GuiGraphics graphics, int x, CallbackInfo ci) {
        var event = new NpcClientEvents.RenderGuiOverlayEvent.Pre(new ResourceLocation("minecraft", "jump_bar"));
        NpcClientEventHooks.fire(event);
        if (event.isCanceled()) ci.cancel();
    }
}
