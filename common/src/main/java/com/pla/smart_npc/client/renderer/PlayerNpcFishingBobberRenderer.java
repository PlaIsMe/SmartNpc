package com.pla.smart_npc.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.PlayerNpcFishingBobberEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

public class PlayerNpcFishingBobberRenderer extends EntityRenderer<PlayerNpcFishingBobberEntity> {
    private static final ResourceLocation TEXTURE_LOCATION = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/fishing_hook.png");
    private static final RenderType RENDER_TYPE = RenderType.entityCutout(TEXTURE_LOCATION);

    public PlayerNpcFishingBobberRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(@NotNull PlayerNpcFishingBobberEntity entity, float entityYaw, float partialTicks, @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer, int packedLight) {
        PlayerNpcEntity angler = entity.getAngler();
        if (angler == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.pushPose();
        poseStack.scale(0.5F, 0.5F, 0.5F);
        // In 1.21.1 the camera orientation already faces the quad toward the viewer.
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer hookConsumer = buffer.getBuffer(RENDER_TYPE);
        vertex(hookConsumer, pose, packedLight, 0.0F, 0, 0, 1);
        vertex(hookConsumer, pose, packedLight, 1.0F, 0, 1, 1);
        vertex(hookConsumer, pose, packedLight, 1.0F, 1, 1, 0);
        vertex(hookConsumer, pose, packedLight, 0.0F, 1, 0, 0);
        poseStack.popPose();

        Vec3 handPosition = getHandPosition(angler, entity.getCastHand(), partialTicks);
        double bobberX = Mth.lerp((double) partialTicks, entity.xo, entity.getX());
        double bobberY = Mth.lerp((double) partialTicks, entity.yo, entity.getY()) + 0.25D;
        double bobberZ = Mth.lerp((double) partialTicks, entity.zo, entity.getZ());
        float xOffset = (float) (handPosition.x - bobberX);
        float yOffset = (float) (handPosition.y - bobberY) + (angler.isCrouching() ? -0.1875F : 0.0F);
        float zOffset = (float) (handPosition.z - bobberZ);
        VertexConsumer lineConsumer = buffer.getBuffer(RenderType.lineStrip());
        PoseStack.Pose linePose = poseStack.last();

        for (int i = 0; i <= 16; i++) {
            stringVertex(xOffset, yOffset, zOffset, lineConsumer, linePose, fraction(i, 16), fraction(i + 1, 16));
        }

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private static Vec3 getHandPosition(PlayerNpcEntity angler, InteractionHand hand, float partialTicks) {
        int handSide = getLineHandSide(angler, hand);
        float bodyYaw = Mth.lerp(partialTicks, angler.yBodyRotO, angler.yBodyRot) * ((float) Math.PI / 180F);
        double bodySin = Mth.sin(bodyYaw);
        double bodyCos = Mth.cos(bodyYaw);
        double sideOffset = handSide * 0.35D;
        double x = Mth.lerp((double) partialTicks, angler.xo, angler.getX()) - bodyCos * sideOffset - bodySin * 0.8D;
        double y = angler.yo + angler.getEyeHeight() + (angler.getY() - angler.yo) * partialTicks - 0.45D;
        double z = Mth.lerp((double) partialTicks, angler.zo, angler.getZ()) - bodySin * sideOffset + bodyCos * 0.8D;
        return new Vec3(x, y, z);
    }

    private static int getLineHandSide(PlayerNpcEntity angler, InteractionHand hand) {
        int handSide = angler.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        if (hand == InteractionHand.OFF_HAND) {
            handSide = -handSide;
        }
        return handSide;
    }

    private static float fraction(int numerator, int denominator) {
        return (float) numerator / (float) denominator;
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int lightmapUv, float x, int y, int u, int v) {
        consumer.addVertex(pose, x - 0.5F, y - 0.5F, 0.0F)
                .setColor(-1)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(lightmapUv)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static void stringVertex(float x, float y, float z, VertexConsumer consumer, PoseStack.Pose pose, float fromStep, float toStep) {
        float fromX = x * fromStep;
        float fromY = y * (fromStep * fromStep + fromStep) * 0.5F + 0.25F;
        float fromZ = z * fromStep;
        float normalX = x * toStep - fromX;
        float normalY = y * (toStep * toStep + toStep) * 0.5F + 0.25F - fromY;
        float normalZ = z * toStep - fromZ;
        float normalLength = Mth.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        normalX /= normalLength;
        normalY /= normalLength;
        normalZ /= normalLength;
        consumer.addVertex(pose, fromX, fromY, fromZ)
                .setColor(-16777216)
                .setNormal(pose, normalX, normalY, normalZ);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull PlayerNpcFishingBobberEntity entity) {
        return TEXTURE_LOCATION;
    }
}
