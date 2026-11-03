package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.client.renderer.layer.BetterCombatItemInHandLayer;
import com.pla.smart_npc.init.SmartNpcModEntities;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.event.types.registry.RegisterPatchedRenderersEvent;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.renderer.patched.entity.PHumanoidRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;

public class EpicFightSmartNpcPatchedRenderer {
    private static boolean rendererHookRegistered;

    public static void registerRendererHook() {
        if (!rendererHookRegistered) {
            rendererHookRegistered = true;
            EpicFightClientEventHooks.Registry.ADD_PATCHED_ENTITY.registerEvent(
                    EpicFightSmartNpcPatchedRenderer::onPatchedRenderer, SmartNpc.MODID);
        }
    }

    public static void onPatchedRenderer(RegisterPatchedRenderersEvent.AddEntity add) {
        add.addPatchedEntityRenderer(SmartNpcModEntities.PLAYER_NPC.get(),
                (entitytype) -> {
                    var renderer = new PHumanoidRenderer<>(Meshes.BIPED, add.getContext(), entitytype);
                    // Epic Fight matches exact layer classes, not ItemInHandLayer subclasses.
                    // Replace our Better Combat layer before the root-attached fallback is installed.
                    renderer.addPatchedLayer(BetterCombatItemInHandLayer.class, new PatchedItemInHandLayer<>());
                    return renderer.initLayerLast(add.getContext(), entitytype);
                });
    }
}
