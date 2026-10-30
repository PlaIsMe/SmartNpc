package com.pla.smart_npc.client.renderer;

import net.minecraft.core.component.DataComponents;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.pla.smart_npc.clazz.FakePlayer;
import com.pla.smart_npc.client.compat.BetterCombatClientCompat;
import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import com.pla.smart_npc.client.model.BetterCombatPlayerNpcModel;
import com.pla.smart_npc.client.renderer.layer.BetterCombatItemInHandLayer;
import com.pla.smart_npc.compat.BetterCombatCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

public class FakePlayerRenderer<T extends FakePlayer>
        extends HumanoidMobRenderer<T, AvatarRenderState, PlayerModel> {
    private final PlayerModel defaultModel;
    private final PlayerModel slimModel;
    private final RenderLayer<AvatarRenderState, PlayerModel> defaultArmorLayer;
    private final RenderLayer<AvatarRenderState, PlayerModel> slimArmorLayer;
    private final int armorLayerIndex;

    public FakePlayerRenderer(EntityRendererProvider.Context context) {
        super(context, new BetterCombatPlayerNpcModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.defaultModel = this.model;
        this.slimModel = new BetterCombatPlayerNpcModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.defaultArmorLayer = new HumanoidArmorLayer<>(
                this,
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), part -> new BetterCombatPlayerNpcModel(part, false)),
                context.getEquipmentRenderer());
        this.slimArmorLayer = new HumanoidArmorLayer<>(
                this,
                ArmorModelSet.bake(ModelLayers.PLAYER_SLIM_ARMOR, context.getModelSet(), part -> new BetterCombatPlayerNpcModel(part, true)),
                context.getEquipmentRenderer());

        if (BetterCombatCompat.isLoaded()) {
            int heldItemLayerIndex = -1;
            for (int index = 0; index < this.layers.size(); index++) {
                if (this.layers.get(index) instanceof ItemInHandLayer<?, ?>) {
                    heldItemLayerIndex = index;
                    break;
                }
            }
            if (heldItemLayerIndex >= 0) {
                this.layers.remove(heldItemLayerIndex);
                this.layers.add(heldItemLayerIndex, new BetterCombatItemInHandLayer(this));
            } else {
                this.addLayer(new BetterCombatItemInHandLayer(this));
            }
        }

        ArrowLayer<PlayerModel> arrowLayer = new ArrowLayer<>(this, context);
        this.addLayer(arrowLayer);
        this.armorLayerIndex = this.layers.indexOf(arrowLayer);
        this.addLayer(new FakePlayerCapeLayer(this, context.getModelSet(), context.getEquipmentAssets()));
    }

    @Override
    public void submit(AvatarRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        boolean slim = state instanceof FakePlayerRenderState npcState && npcState.slim;
        this.model = slim ? this.slimModel : this.defaultModel;
        this.layers.remove(this.defaultArmorLayer);
        this.layers.remove(this.slimArmorLayer);
        this.layers.add(Math.min(this.armorLayerIndex, this.layers.size()), slim ? this.slimArmorLayer : this.defaultArmorLayer);
        super.submit(state, poseStack, collector, camera);
    }

    @Override
    protected HumanoidModel.ArmPose getArmPose(T entity, HumanoidArm arm) {
        InteractionHand hand = entity.getMainArm() == arm ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = entity.getItemInHand(hand);
        if (stack.isEmpty()) return HumanoidModel.ArmPose.EMPTY;
        if (stack.is(Items.SPYGLASS) && entity.isUsingItem() && entity.getUsedItemHand() == hand) {
            return HumanoidModel.ArmPose.SPYGLASS;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return entity.isUsingItem() && entity.getUsedItemHand() == hand
                    ? HumanoidModel.ArmPose.CROSSBOW_CHARGE : HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        if (stack.getItem() instanceof BowItem && entity.isAggressive()) {
            return HumanoidModel.ArmPose.BOW_AND_ARROW;
        }
        if (stack.has(DataComponents.BLOCKS_ATTACKS) && entity.isUsingItem() && entity.getUsedItemHand() == hand) {
            return HumanoidModel.ArmPose.BLOCK;
        }
        if (hand == InteractionHand.MAIN_HAND && isMainHandAttackAnimating(entity)) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        return HumanoidModel.ArmPose.ITEM;
    }

    @Override
    public FakePlayerRenderState createRenderState() {
        return new FakePlayerRenderState();
    }

    @Override
    public void extractRenderState(T entity, AvatarRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        // Gesture goals toggle the synchronized sneak flag without changing the physical pose.
        // Preserve swimming/sleeping poses while restoring the player model's crouch animation.
        state.isCrouching = state.isCrouching || entity.isShiftKeyDown() && state.hasPose(Pose.STANDING);
        state.skin = FakePlayerTextureUtils.getPlayerSkinData(entity);
        state.isSpectator = false;
        state.showHat = true;
        state.showJacket = true;
        state.showLeftPants = true;
        state.showRightPants = true;
        state.showLeftSleeve = true;
        state.showRightSleeve = true;
        state.showCape = true;
        state.arrowCount = entity.getArrowCount();

        if (state instanceof FakePlayerRenderState npcState) {
            npcState.slim = state.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
            npcState.playerNpc = entity instanceof PlayerNpcEntity playerNpc ? playerNpc : null;
            npcState.hideHead = SmartNpcInspectorOverlay.shouldRenderInspectatorCameraTargetBody(entity);
            npcState.swimXRot = state.xRot;
            if (npcState.playerNpc != null && state.isInWater && state.swimAmount > 0.0F) {
                // The work target can be above water while the NPC dives. Tilt the body
                // along its motion without replacing the goal's independent head look.
                Vec3 motion = entity.getDeltaMovement();
                npcState.swimXRot = motion.lengthSqr() > 1.0E-6D
                        ? (float) Math.toDegrees(-Math.atan2(motion.y, motion.horizontalDistance())) : 0.0F;
            }
        }
        if (entity instanceof PlayerNpcEntity playerNpc) {
            if (playerNpc.isSocialNoticePausingWork() || playerNpc.isHealing()
                    || entity.isUsingItem() && entity.getUseItem().has(DataComponents.BLOCKS_ATTACKS)
                    || BetterCombatClientCompat.hasActiveAttackAnimation(playerNpc)) {
                state.attackTime = 0.0F;
            } else if (state.attackTime <= 0.0F && playerNpc.getMainHandAttackAnimationTicks() > 0) {
                float duration = playerNpc.getMainHandAttackAnimationDuration();
                state.attackTime = Mth.clamp(1.0F - ((float) playerNpc.getMainHandAttackAnimationTicks() - partialTick) / duration, 0.0F, 1.0F);
            }
        }
        extractCapeState(entity, state, partialTick);
    }

    private static void extractCapeState(FakePlayer entity, AvatarRenderState state, float partialTick) {
        double deltaX = Mth.lerp(partialTick, entity.xCloakO, entity.xCloak) - Mth.lerp(partialTick, entity.xo, entity.getX());
        double deltaY = Mth.lerp(partialTick, entity.yCloakO, entity.yCloak) - Mth.lerp(partialTick, entity.yo, entity.getY());
        double deltaZ = Mth.lerp(partialTick, entity.zCloakO, entity.zCloak) - Mth.lerp(partialTick, entity.zo, entity.getZ());
        float bodyRot = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        double forwardX = Mth.sin(bodyRot * ((float) Math.PI / 180.0F));
        double forwardZ = -Mth.cos(bodyRot * ((float) Math.PI / 180.0F));
        state.capeFlap = Mth.clamp((float) deltaY * 10.0F, -6.0F, 32.0F);
        state.capeLean = Mth.clamp((float) (deltaX * forwardX + deltaZ * forwardZ) * 100.0F, 0.0F, 150.0F);
        state.capeLean2 = Mth.clamp((float) (deltaX * forwardZ - deltaZ * forwardX) * 100.0F, -20.0F, 20.0F);
        if (state.isCrouching) state.capeFlap += 25.0F;
    }

    @Override
    protected boolean shouldShowName(T entity, double distanceToCameraSq) {
        if (entity instanceof PlayerNpcEntity playerNpc && playerNpc.isDisplayNameHiddenBySneakingAi()) return false;
        return SmartNpcInspectorOverlay.shouldForceInspectatorTargetName(entity)
                || super.shouldShowName(entity, distanceToCameraSq);
    }

    private boolean isMainHandAttackAnimating(T entity) {
        return entity instanceof PlayerNpcEntity playerNpc && playerNpc.getMainHandAttackAnimationTicks() > 0;
    }

    @Override
    protected void setupRotations(AvatarRenderState state, PoseStack poseStack, float bodyRot, float entityScale) {
        super.setupRotations(state, poseStack, bodyRot, entityScale);
        // HumanoidMobRenderer animates the limbs but does not apply AvatarRenderer's swim transform.
        if (!state.isFallFlying && !state.isAutoSpinAttack && state.deathTime <= 0.0F
                && !state.hasPose(Pose.SLEEPING) && state.swimAmount > 0.0F) {
            float swimXRot = state instanceof FakePlayerRenderState npcState ? npcState.swimXRot : state.xRot;
            float targetXRot = state.isInWater ? -90.0F - swimXRot : -90.0F;
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(state.swimAmount, 0.0F, targetXRot)));
            if (state.isVisuallySwimming) {
                poseStack.translate(0.0F, -1.0F, 0.3F);
            }
        }
    }

    @Override
    protected void scale(AvatarRenderState state, PoseStack poseStack) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    @Override
    public Identifier getTextureLocation(AvatarRenderState state) {
        return state.skin.body().texturePath();
    }
}
