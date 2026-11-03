package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.renderer.patched.entity.PHumanoidRenderer;

public class EpicFightSmartNpcPatchedRenderer {
    @SubscribeEvent
    public static void onPatchedRenderer(PatchedRenderersEvent.Add add) {
        add.addPatchedEntityRenderer(SmartNpcModEntities.PLAYER_NPC.get(),
                (entitytype) -> (new PHumanoidRenderer<>(Meshes.BIPED, add.getContext(), entitytype))
                        .initLayerLast(add.getContext(), entitytype));
    }
}
