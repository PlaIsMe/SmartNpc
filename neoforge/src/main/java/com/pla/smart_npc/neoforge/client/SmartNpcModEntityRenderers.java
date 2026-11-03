package com.pla.smart_npc.neoforge.client;
import com.pla.smart_npc.init.SmartNpcModEntities;

import com.pla.smart_npc.client.renderer.*;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers;
import net.neoforged.bus.api.SubscribeEvent;

public class SmartNpcModEntityRenderers {

    @SubscribeEvent
    public static void registerEntityRenderers(RegisterRenderers registerrenderers) {
        registerrenderers.registerEntityRenderer(SmartNpcModEntities.PLAYER_NPC.get(), FakePlayerRenderer::new);
        registerrenderers.registerEntityRenderer(SmartNpcModEntities.PLAYER_NPC_FISHING_BOBBER.get(), PlayerNpcFishingBobberRenderer::new);
    }
}
