package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
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

    @ModifyConstant(method = "setup", constant = @Constant(doubleValue = 4.0D))
    private double player_npc$inspectatorCameraDistance(double vanillaDistance) {
        return SmartNpcInspectorOverlay.getInspectatorCameraDistance(vanillaDistance);
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void player_npc$inspectatorFirstPersonHeadPosition(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean mirror,
            float partialTick,
            CallbackInfo callbackInfo
    ) {
        if (detached) {
            return;
        }

        SmartNpcInspectorOverlay.InspectatorCameraTransform transform =
                SmartNpcInspectorOverlay.getInspectatorCameraTransform(partialTick);
        if (transform != null) {
            this.setPosition(transform.eyePosition());
            this.setRotation(transform.yRot(), transform.xRot());
        }
    }
}
