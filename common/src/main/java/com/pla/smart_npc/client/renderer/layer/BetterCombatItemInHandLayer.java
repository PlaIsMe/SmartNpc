package com.pla.smart_npc.client.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.pla.smart_npc.client.compat.BetterCombatClientCompat;
import com.pla.smart_npc.client.renderer.FakePlayerRenderState;
import net.minecraft.client.model.effects.SpearAnimations;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;

/** Adds Better Combat's held-item channel to the 26.1 render-state item layer. */
public final class BetterCombatItemInHandLayer extends ItemInHandLayer<AvatarRenderState, PlayerModel> {
    public BetterCombatItemInHandLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    protected void submitArmWithItem(AvatarRenderState state, ItemStackRenderState item,
                                     ItemStack itemStack, HumanoidArm arm, PoseStack poseStack,
                                     SubmitNodeCollector collector, int lightCoords) {
        if (item.isEmpty()) return;

        poseStack.pushPose();
        this.getParentModel().translateToHand(state, arm, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        boolean leftHand = arm == HumanoidArm.LEFT;
        float offsetX = state.isBaby ? 0.0F : 1.0F;
        float offsetY = state.isBaby ? 1.0F : 2.0F;
        float offsetZ = state.isBaby ? -4.5F : -10.0F;
        poseStack.translate((leftHand ? -1 : 1) * offsetX / 16.0F, offsetY / 16.0F, offsetZ / 16.0F);
        if (state.attackTime > 0.0F && state.attackArm == arm && state.swingAnimationType == SwingAnimationType.STAB) {
            SpearAnimations.thirdPersonAttackItem(state, poseStack);
        }
        float ticksUsingItem = state.ticksUsingItem(arm);
        if (ticksUsingItem != 0.0F) {
            (arm == HumanoidArm.RIGHT ? state.rightArmPose : state.leftArmPose)
                    .animateUseItem(state, poseStack, ticksUsingItem, arm, itemStack);
        }
        if (state instanceof FakePlayerRenderState npcState && npcState.playerNpc != null) {
            BetterCombatClientCompat.applyHeldItemTransform(poseStack, npcState.playerNpc, arm, npcState.partialTick);
        }
        item.submit(poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }
}
