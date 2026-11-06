package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class GuiMixin {
    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void player_npc$hideInspectatorMountPrompt(Component component, boolean animateColor, CallbackInfo ci) {
        if (SmartNpcInspectorOverlay.isInspectatorActive()
                && component.getContents() instanceof TranslatableContents contents
                && "mount.onboard".equals(contents.getKey())) {
            ci.cancel();
        }
    }
}
