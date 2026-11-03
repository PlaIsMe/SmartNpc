package com.pla.smart_npc.fabric;
import com.pla.smart_npc.client.SmartNpcClientItemProperties;
import com.pla.smart_npc.client.gui.InventoryViewerScreen;
import com.pla.smart_npc.client.renderer.FakePlayerRenderer;
import com.pla.smart_npc.client.renderer.PlayerNpcFishingBobberRenderer;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.init.SmartNpcModMenus;
import com.pla.smart_npc.platform.event.*;
import dev.architectury.registry.menu.MenuRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
public final class SmartNpcFabricClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        EntityRendererRegistry.register(SmartNpcModEntities.PLAYER_NPC.get(), FakePlayerRenderer::new);
        EntityRendererRegistry.register(SmartNpcModEntities.PLAYER_NPC_FISHING_BOBBER.get(), PlayerNpcFishingBobberRenderer::new);
        MenuRegistry.registerScreenFactory(SmartNpcModMenus.INVENTORY_VIEWER.get(), InventoryViewerScreen::new);
        SmartNpcClientItemProperties.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> NpcClientEventHooks.fire(new NpcClientEvents.ClientTickEvent.Post()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> NpcClientEventHooks.fire(new NpcClientEvents.ClientPlayerNetworkEvent.LoggingOut()));
        HudRenderCallback.EVENT.register((graphics, partialTick) -> NpcClientEventHooks.fire(new NpcClientEvents.RenderGuiEvent.Post(graphics)));
    }
}
