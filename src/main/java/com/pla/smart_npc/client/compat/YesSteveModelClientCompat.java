package com.pla.smart_npc.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.client.compat.ysm.Ysm265Renderer;
import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import com.pla.smart_npc.compat.YesSteveModelCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Client-only, lazily linked boundary: ordinary NPC rendering never requires YSM classes. */
@EventBusSubscriber(modid = SmartNpc.MODID, value = Dist.CLIENT)
public final class YesSteveModelClientCompat {
    private static final Set<YesSteveModelClientCompat> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());
    private final EntityRendererProvider.Context context;
    private Renderer renderer;
    private boolean failed;
    private boolean reportedSubmission;
    private final Map<PlayerNpcEntity, String> reportedStates = new WeakHashMap<>();

    public YesSteveModelClientCompat(EntityRendererProvider.Context context) {
        this.context = context;
        INSTANCES.add(this);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Release display proxies and their old ClientLevel when leaving a world.
        for (YesSteveModelClientCompat instance : INSTANCES) {
            instance.renderer = null;
            instance.reportedStates.clear();
            instance.reportedSubmission = false;
        }
    }

    public @Nullable Frame extract(PlayerNpcEntity npc, AvatarRenderState state, float partialTick) {
        if (this.failed || !npc.isYsmActive() || !YesSteveModelCompat.isSupported()
                || SmartNpcInspectorOverlay.shouldRenderInspectatorCameraTargetBody(npc)) {
            if (!npc.getYsmModelId().isEmpty()) {
                String reason = this.failed ? "adapter disabled" : !npc.isYsmActive() ? "server active flag is false"
                        : !YesSteveModelCompat.isSupported() ? "YSM unavailable" : "inspector body camera";
                this.report(npc, reason);
            }
            return null;
        }
        try {
            this.report(npc, "server active flag received; extracting appearance");
            if (this.renderer == null) {
                this.renderer = new Ysm265Renderer(this.context);
            }
            return this.renderer.extract(npc, state, partialTick);
        } catch (LinkageError | RuntimeException error) {
            this.disable(error);
            return null;
        }
    }

    public boolean submit(@Nullable Frame frame, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (this.failed || frame == null) {
            return false;
        }
        try {
            // The frame contains extracted state; never read the NPC during submission.
            PoseStack isolated = new PoseStack();
            isolated.last().pose().set(poseStack.last().pose());
            isolated.last().normal().set(poseStack.last().normal());
            frame.submit(isolated, collector, camera);
            if (!this.reportedSubmission) {
                this.reportedSubmission = true;
                SmartNpc.LOGGER.info("Smart NPC YSM submission: native frame submitted");
            }
            return true;
        } catch (LinkageError | RuntimeException error) {
            this.disable(error);
            return false;
        }
    }

    private void disable(Throwable error) {
        this.failed = true;
        this.renderer = null;
        SmartNpc.LOGGER.warn("Smart NPC disabled YSM rendering after an adapter failure; using normal NPC skins.", error);
    }

    private void report(PlayerNpcEntity npc, String state) {
        if (!state.equals(this.reportedStates.put(npc, state))) {
            SmartNpc.LOGGER.info("Smart NPC YSM client gate: NPC={}, model='{}', {}", npc.getName().getString(), npc.getYsmModelId(), state);
        }
    }

    public interface Renderer {
        @Nullable Frame extract(PlayerNpcEntity npc, AvatarRenderState state, float partialTick);
    }

    /** Optional-mod types stay inside the lazily loaded adapter. */
    public interface Frame {
        void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera);
    }
}
