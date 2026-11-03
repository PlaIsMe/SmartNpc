package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.platform.event.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void smartNpc$input(boolean slow, float multiplier, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player != null) NpcClientEventHooks.fire(new NpcClientEvents.MovementInputUpdateEvent(player, (Input)(Object)this));
    }
}
