package com.pla.smart_npc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class PlayerRendererMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void player_npc$hideInspectatorLocalPlayer(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callback) {
        var player = Minecraft.getInstance().player;
        if (player != null && state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar && avatar.id == player.getId() && SmartNpcInspectorOverlay.shouldHideInspectatorLocalPlayer(player)) {
            callback.cancel();
        }
    }
}
