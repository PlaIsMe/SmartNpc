package com.pla.smart_npc.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.client.compat.ysm.Ysm265Renderer;
import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import com.pla.smart_npc.compat.YesSteveModelCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Client-only, lazily linked boundary: ordinary NPC rendering never requires YSM classes. */
@Mod.EventBusSubscriber(modid = SmartNpc.MODID, value = Dist.CLIENT)
public final class YesSteveModelClientCompat {
    private static final Set<YesSteveModelClientCompat> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());
    private final EntityRendererProvider.Context context;
    private Renderer renderer;
    private boolean failed;

    public YesSteveModelClientCompat(EntityRendererProvider.Context context) {
        this.context = context;
        INSTANCES.add(this);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Release display proxies and their old ClientLevel when leaving a world.
        for (YesSteveModelClientCompat instance : INSTANCES) {
            instance.renderer = null;
        }
    }

    public boolean render(PlayerNpcEntity npc, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (this.failed || !npc.isYsmActive() || !YesSteveModelCompat.isSupported()
                || SmartNpcInspectorOverlay.shouldRenderInspectatorCameraTargetBody(npc)) {
            return false;
        }
        try {
            if (this.renderer == null) {
                this.renderer = new Ysm265Renderer(this.context);
            }
            // Isolate the caller's transform stack even if a third-party renderer fails mid-frame.
            PoseStack isolated = new PoseStack();
            isolated.last().pose().set(poseStack.last().pose());
            isolated.last().normal().set(poseStack.last().normal());
            return this.renderer.render(npc, yaw, partialTick, isolated, buffers, light);
        } catch (LinkageError | RuntimeException error) {
            this.failed = true;
            this.renderer = null;
            SmartNpc.LOGGER.warn("Smart NPC disabled YSM rendering after an adapter failure; using normal NPC skins.", error);
            return false;
        }
    }

    public interface Renderer {
        boolean render(PlayerNpcEntity npc, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light);
    }
}
