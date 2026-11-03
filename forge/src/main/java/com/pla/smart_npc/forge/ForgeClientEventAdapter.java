package com.pla.smart_npc.forge;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.platform.event.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid = SmartNpc.MODID, value = Dist.CLIENT)
public final class ForgeClientEventAdapter {
    @SubscribeEvent public static void tick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) NpcClientEventHooks.fire(new NpcEvents.TickEvent.ClientTickEvent(NpcEvents.TickEvent.Phase.END));
    }
    @SubscribeEvent public static void logout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) { NpcClientEventHooks.fire(new NpcClientEvents.ClientPlayerNetworkEvent.LoggingOut()); }
    @SubscribeEvent public static void input(net.minecraftforge.client.event.MovementInputUpdateEvent event) { NpcClientEventHooks.fire(new NpcClientEvents.MovementInputUpdateEvent(event.getEntity(), event.getInput())); }
    @SubscribeEvent public static void render(net.minecraftforge.client.event.RenderGuiEvent.Post event) { NpcClientEventHooks.fire(new NpcClientEvents.RenderGuiEvent.Post(event.getGuiGraphics())); }
    @SubscribeEvent public static void screen(net.minecraftforge.client.event.ScreenEvent.Opening event) {
        var shared = new NpcClientEvents.ScreenEvent.Opening(event.getNewScreen());
        NpcClientEventHooks.fire(shared);
        if (shared.isCanceled()) event.setCanceled(true);
    }
    @SubscribeEvent public static void overlay(net.minecraftforge.client.event.RenderGuiOverlayEvent.Pre event) {
        var shared = new NpcClientEvents.RenderGuiOverlayEvent.Pre(event.getOverlay().id());
        NpcClientEventHooks.fire(shared);
        if (shared.isCanceled()) event.setCanceled(true);
    }
}
