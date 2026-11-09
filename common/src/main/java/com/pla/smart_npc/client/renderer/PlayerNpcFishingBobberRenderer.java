package com.pla.smart_npc.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.PlayerNpcFishingBobberEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;


public class PlayerNpcFishingBobberRenderer
        extends EntityRenderer<PlayerNpcFishingBobberEntity, FishingHookRenderState> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/fishing_hook.png");
    private static final RenderType RENDER_TYPE = RenderTypes.entityCutout(TEXTURE);

    public PlayerNpcFishingBobberRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void submit(FishingHookRenderState state, PoseStack poseStack,
                       SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.pushPose();
        poseStack.scale(0.5F, 0.5F, 0.5F);
        // Keep the camera-facing quad; the extra 1.20.1 Y rotation would hide it.
        poseStack.mulPose(camera.orientation);
        collector.submitCustomGeometry(poseStack, RENDER_TYPE, (pose, buffer) -> {
            vertex(buffer, pose, state.lightCoords, 0.0F, 0, 0, 1);
            vertex(buffer, pose, state.lightCoords, 1.0F, 0, 1, 1);
            vertex(buffer, pose, state.lightCoords, 1.0F, 1, 1, 0);
            vertex(buffer, pose, state.lightCoords, 0.0F, 1, 0, 0);
        });
        poseStack.popPose();

        float x = (float) state.lineOriginOffset.x;
        float y = (float) state.lineOriginOffset.y;
        float z = (float) state.lineOriginOffset.z;
        float width = Minecraft.getInstance().getWindow().getAppropriateLineWidth();
        collector.submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> {
            for (int i = 0; i < 16; i++) {
                float from = fraction(i, 16);
                float to = fraction(i + 1, 16);
                stringVertex(x, y, z, buffer, pose, from, to, width);
                stringVertex(x, y, z, buffer, pose, to, from, width);
            }
        });
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    @Override
    public FishingHookRenderState createRenderState() {
        return new FishingHookRenderState();
    }

    @Override
    public void extractRenderState(PlayerNpcFishingBobberEntity entity,
                                   FishingHookRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        PlayerNpcEntity angler = entity.getAngler();
        if (angler == null) {
            state.lineOriginOffset = Vec3.ZERO;
            return;
        }
        Vec3 hand = getHandPosition(angler, entity.getCastHand(), partialTicks);
        Vec3 bobber = entity.getPosition(partialTicks).add(0.0D, 0.25D, 0.0D);
        state.lineOriginOffset = hand.subtract(bobber);
    }

    private static Vec3 getHandPosition(PlayerNpcEntity angler, InteractionHand hand, float partialTicks) {
        int side = getLineHandSide(angler, hand);
        float yaw = Mth.lerp(partialTicks, angler.yBodyRotO, angler.yBodyRot) * ((float) Math.PI / 180.0F);
        double sin = Mth.sin(yaw);
        double cos = Mth.cos(yaw);
        double sideOffset = side * 0.35D;
        return angler.getEyePosition(partialTicks)
                .add(-cos * sideOffset - sin * 0.8D,
                        (angler.isCrouching() ? -0.1875D : 0.0D) - 0.45D,
                        -sin * sideOffset + cos * 0.8D);
    }

    private static int getLineHandSide(PlayerNpcEntity angler, InteractionHand hand) {
        int side = angler.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        return hand == InteractionHand.OFF_HAND ? -side : side;
    }

    private static float fraction(int numerator, int denominator) {
        return (float) numerator / denominator;
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int light,
                               float x, int y, int u, int v) {
        consumer.addVertex(pose, x - 0.5F, y - 0.5F, 0.0F)
                .setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private static void stringVertex(float xa, float ya, float za, VertexConsumer consumer,
                                     PoseStack.Pose pose, float step, float next, float width) {
        float x = xa * step;
        float y = ya * (step * step + step) * 0.5F + 0.25F;
        float z = za * step;
        float nx = xa * next - x;
        float ny = ya * (next * next + next) * 0.5F + 0.25F - y;
        float nz = za * next - z;
        float length = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        consumer.addVertex(pose, x, y, z).setColor(-16777216)
                .setNormal(pose, nx / length, ny / length, nz / length).setLineWidth(width);
    }

    @Override
    public boolean shouldRender(PlayerNpcFishingBobberEntity entity, Frustum culler,
                                double camX, double camY, double camZ) {
        return entity.getAngler() != null && super.shouldRender(entity, culler, camX, camY, camZ);
    }

    @Override
    protected boolean affectedByCulling(PlayerNpcFishingBobberEntity entity) {
        return false;
    }
}
