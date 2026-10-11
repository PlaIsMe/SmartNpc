package com.pla.smart_npc.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.clazz.FakePlayer;
import com.pla.smart_npc.client.compat.BetterCombatClientCompat;
import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import com.pla.smart_npc.client.model.BetterCombatPlayerNpcModel;
import com.pla.smart_npc.client.renderer.layer.BetterCombatItemInHandLayer;
import com.pla.smart_npc.compat.BetterCombatCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import org.jetbrains.annotations.NotNull;

public class FakePlayerRenderer<T extends FakePlayer> extends HumanoidMobRenderer<T, PlayerModel<T>> {
    private final PlayerModel<T> defaultModel;
    private final PlayerModel<T> slimModel;
    private final RenderLayer<T, PlayerModel<T>> defaultArmorLayer;
    private final RenderLayer<T, PlayerModel<T>> slimArmorLayer;
    private final int armorLayerIndex;

    public FakePlayerRenderer(EntityRendererProvider.Context context) {
        super(context, new BetterCombatPlayerNpcModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.defaultModel = this.model;
        this.slimModel = new BetterCombatPlayerNpcModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.defaultArmorLayer = new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager());
        this.slimArmorLayer = new HumanoidArmorLayer<>(
                this,
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_INNER_ARMOR)),
                new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM_OUTER_ARMOR)),
                context.getModelManager());

        // HumanoidMobRenderer installs vanilla ItemInHandLayer in its
        // constructor. Only replace it when Better Combat is actually loaded,
        // keeping the vanilla renderer completely untouched otherwise.
        if (BetterCombatCompat.isLoaded()) {
            int vanillaHeldItemLayerIndex = -1;
            for (int index = 0; index < this.layers.size(); index++) {
                if (this.layers.get(index) instanceof ItemInHandLayer<?, ?>) {
                    vanillaHeldItemLayerIndex = index;
                    break;
                }
            }
            if (vanillaHeldItemLayerIndex >= 0) {
                this.layers.remove(vanillaHeldItemLayerIndex);
                this.layers.add(
                        vanillaHeldItemLayerIndex,
                        new BetterCombatItemInHandLayer<>(this, context.getItemInHandRenderer()));
            } else {
                this.addLayer(new BetterCombatItemInHandLayer<>(this, context.getItemInHandRenderer()));
            }
        }

        ArrowLayer<T, PlayerModel<T>> arrowLayer = new ArrowLayer<>(context, this);
        this.addLayer(arrowLayer);
        this.armorLayerIndex = this.layers.indexOf(arrowLayer);
        this.addLayer(new FakePlayerCapeLayer<>(this));
    }

    @Override
    public void render(@NotNull T entity, float entityYaw, float partialTick, @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer, int packedLight) {
        boolean renderInspectatorBody = SmartNpcInspectorOverlay.shouldRenderInspectatorCameraTargetBody(entity);
        boolean slim = FakePlayerTextureUtils.getPlayerSkinType(entity.getProfile()) == FakePlayerTextureUtils.SkinType.SLIM;
        this.model = slim ? this.slimModel : this.defaultModel;
        this.layers.remove(this.defaultArmorLayer);
        this.layers.remove(this.slimArmorLayer);
        this.layers.add(Math.min(this.armorLayerIndex, this.layers.size()), slim ? this.slimArmorLayer : this.defaultArmorLayer);

        this.model.crouching = entity.isShiftKeyDown() || entity.isCrouching();
        this.model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        this.model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        ItemStack mainHand = entity.getMainHandItem();
        if (!mainHand.isEmpty()) {
            if (mainHand.is(Items.SPYGLASS) && entity.isUsingItem()) {
                setHandPose(entity, HumanoidModel.ArmPose.SPYGLASS);
            } else if (mainHand.getItem() instanceof CrossbowItem) {
                setHandPose(entity, entity.isUsingItem() ? HumanoidModel.ArmPose.CROSSBOW_CHARGE : HumanoidModel.ArmPose.CROSSBOW_HOLD);
            } else if (mainHand.getItem() instanceof BowItem && entity.isAggressive()) {
                setHandPose(entity, HumanoidModel.ArmPose.BOW_AND_ARROW);
            } else if (isMainHandAttackAnimating(entity)) {
                setHandPose(entity, HumanoidModel.ArmPose.EMPTY);
            } else {
                setHandPose(entity, HumanoidModel.ArmPose.ITEM);
            }
        }
        ItemStack offhand = entity.getOffhandItem();
        if (!offhand.isEmpty()
                && offhand.getItem() instanceof ShieldItem
                && entity.isUsingItem()
                && entity.getUsedItemHand() == InteractionHand.OFF_HAND) {
            setOffhandPose(entity, HumanoidModel.ArmPose.BLOCK);
        }

        boolean headVisible = this.model.head.visible;
        boolean hatVisible = this.model.hat.visible;
        if (renderInspectatorBody) {
            this.model.head.visible = false;
            this.model.hat.visible = false;
        }
        try {
            super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
        } finally {
            this.model.head.visible = headVisible;
            this.model.hat.visible = hatVisible;
        }
    }

    @Override
    protected float getAttackAnim(@NotNull T entity, float partialTick) {
        if (entity instanceof PlayerNpcEntity playerNpc
                && (playerNpc.isHealing() || BetterCombatClientCompat.hasActiveAttackAnimation(playerNpc))) {
            return 0.0F;
        }

        float attackAnim = super.getAttackAnim(entity, partialTick);
        if (attackAnim > 0.0F) {
            return attackAnim;
        }

        if (entity instanceof PlayerNpcEntity playerNpc) {
            int ticks = playerNpc.getMainHandAttackAnimationTicks();
            if (ticks > 0) {
                float duration = playerNpc.getMainHandAttackAnimationDuration();
                return Mth.clamp(1.0F - ((float) ticks - partialTick) / duration, 0.0F, 1.0F);
            }
        }

        return 0.0F;
    }

    @Override
    protected boolean shouldShowName(@NotNull T entity) {
        if (entity instanceof PlayerNpcEntity playerNpc && playerNpc.isDisplayNameHiddenBySneakingAi()) {
            return false;
        }
        return SmartNpcInspectorOverlay.shouldForceInspectatorTargetName(entity) || super.shouldShowName(entity);
    }

    private boolean isMainHandAttackAnimating(T entity) {
        return entity instanceof PlayerNpcEntity playerNpc && playerNpc.getMainHandAttackAnimationTicks() > 0;
    }

    private void setHandPose(T entity, HumanoidModel.ArmPose pose) {
        if (entity.getMainArm() == HumanoidArm.RIGHT) {
            this.model.rightArmPose = pose;
        } else {
            this.model.leftArmPose = pose;
        }
    }

    private void setOffhandPose(T entity, HumanoidModel.ArmPose pose) {
        if (entity.getMainArm() == HumanoidArm.RIGHT) {
            this.model.leftArmPose = pose;
        } else {
            this.model.rightArmPose = pose;
        }
    }

    @Override
    protected void scale(@NotNull T entity, @NotNull PoseStack poseStack, float partialTickTime) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull T entity) {
        return FakePlayerTextureUtils.getPlayerSkin(entity);
    }
}
