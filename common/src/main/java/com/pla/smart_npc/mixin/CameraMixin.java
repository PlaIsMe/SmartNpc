package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @ModifyConstant(method = "alignWithEntity", constant = @Constant(floatValue = 4.0F))
    private float player_npc$inspectatorCameraDistance(float vanillaDistance) {
        return (float) SmartNpcInspectorOverlay.getInspectatorCameraDistance(vanillaDistance);
    }

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void player_npc$inspectatorFirstPersonHeadPosition(float partialTick, CallbackInfo callbackInfo) {
        if (((Camera) (Object) this).isDetached()) return;

        SmartNpcInspectorOverlay.InspectatorCameraTransform transform =
                SmartNpcInspectorOverlay.getInspectatorCameraTransform(partialTick);
        if (transform != null) {
            this.setPosition(transform.eyePosition());
            this.setRotation(transform.yRot(), transform.xRot());
        }
    }
}
