package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void player_npc$suppressInspectatorFirstPersonRotationPacket(Packet<?> packet, CallbackInfo callbackInfo) {
        if (SmartNpcInspectorOverlay.shouldSuppressInspectatorRotationPacket(packet)) {
            callbackInfo.cancel();
        }
    }
}
