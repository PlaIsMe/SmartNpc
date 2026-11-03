package com.pla.smart_npc.client.renderer.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.pla.smart_npc.clazz.FakePlayer;
import com.pla.smart_npc.client.compat.BetterCombatClientCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Vanilla ItemInHandLayer with PlayerAnimator's held-item transform hook added
 * for PlayerNpcEntity.
 *
 * Better Combat attack animations and weapon idle poses contain rightItem/leftItem
 * channels in addition to rightArm/leftArm. PlayerAnimator normally injects these item
 * channels into ItemInHandLayer only when the entity implements its animated
 * Player interface. PlayerNpcEntity is a PathfinderMob, so that injection does
 * not run for it and the weapon keeps the vanilla grip/rotation.
 */
public final class BetterCombatItemInHandLayer<T extends FakePlayer> extends ItemInHandLayer<T, PlayerModel<T>> {
    private final ItemInHandRenderer itemInHandRenderer;
    private float currentPartialTick;

    public BetterCombatItemInHandLayer(
            RenderLayerParent<T, PlayerModel<T>> parent,
            ItemInHandRenderer itemInHandRenderer
    ) {
        super(parent, itemInHandRenderer);
        this.itemInHandRenderer = itemInHandRenderer;
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        this.currentPartialTick = partialTick;
        super.render(
                poseStack,
                bufferSource,
                packedLight,
                entity,
                limbSwing,
                limbSwingAmount,
                partialTick,
                ageInTicks,
                netHeadYaw,
                headPitch);
    }

    @Override
    protected void renderArmWithItem(
            LivingEntity livingEntity,
            ItemStack itemStack,
            ItemDisplayContext displayContext,
            HumanoidArm arm,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight
    ) {
        if (itemStack.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        this.getParentModel().translateToHand(arm, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        boolean leftHand = arm == HumanoidArm.LEFT;
        poseStack.translate((leftHand ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);

        // PlayerAnimator's HeldItemMixin applies attack/pose rightItem/leftItem here,
        // immediately before ItemInHandRenderer#renderItem. Keeping the same
        // transform order is important because the animation was authored in
        // this post-vanilla-item-transform coordinate space.
        if (livingEntity instanceof PlayerNpcEntity playerNpc) {
            BetterCombatClientCompat.applyHeldItemTransform(
                    poseStack,
                    playerNpc,
                    arm,
                    this.currentPartialTick);
        }

        this.itemInHandRenderer.renderItem(
                livingEntity,
                itemStack,
                displayContext,
                leftHand,
                poseStack,
                bufferSource,
                packedLight);
        poseStack.popPose();
    }
}
