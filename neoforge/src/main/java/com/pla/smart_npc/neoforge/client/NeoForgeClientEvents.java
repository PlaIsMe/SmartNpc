package com.pla.smart_npc.neoforge.client;

import com.pla.smart_npc.platform.event.*;
import net.neoforged.bus.api.EventPriority;

@net.neoforged.fml.common.EventBusSubscriber(modid="smart_npc", value=net.neoforged.api.distmarker.Dist.CLIENT)
public final class NeoForgeClientEvents {

    @net.neoforged.bus.api.SubscribeEvent
    public static void onSmartNpcInspectorOverlay_onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var shared=new NpcClientEvents.ClientTickEvent.Post();
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onClientTick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onSmartNpcInspectorOverlay_onScreenOpening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        var shared=new NpcClientEvents.ScreenEvent.Opening(event.getNewScreen());
        shared.setCanceled(event.isCanceled());
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onScreenOpening(shared);
        event.setCanceled(shared.isCanceled());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onSmartNpcInspectorOverlay_onMovementInputUpdate(net.neoforged.neoforge.client.event.MovementInputUpdateEvent event) {
        var shared=new NpcClientEvents.MovementInputUpdateEvent(event.getEntity(),event.getInput());
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onMovementInputUpdate(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onSmartNpcInspectorOverlay_onRenderGuiOverlayPre(net.neoforged.neoforge.client.event.RenderGuiLayerEvent.Pre event) {
        var shared=new NpcClientEvents.RenderGuiLayerEvent.Pre(event.getName());
        shared.setCanceled(event.isCanceled());
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onRenderGuiOverlayPre(shared);
        event.setCanceled(shared.isCanceled());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onSmartNpcInspectorOverlay_onRenderGui(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        var shared=new NpcClientEvents.RenderGuiEvent.Post(event.getGuiGraphics());
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onRenderGui(shared);
    }

}
