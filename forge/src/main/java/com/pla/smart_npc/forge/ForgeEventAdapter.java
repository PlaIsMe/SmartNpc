package com.pla.smart_npc.forge;

import com.pla.smart_npc.ai.AiChatService;import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.platform.event.NpcEvents;
import com.pla.smart_npc.platform.event.NpcEventHooks;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid = SmartNpc.MODID)
public final class ForgeEventAdapter {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onAcceptedChat(net.minecraftforge.event.ServerChatEvent event) {
        if (!event.isCanceled()) AiChatService.chat(event.getPlayer(), event.getMessage().getString());
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerAboutToStartEvent(net.minecraftforge.event.server.ServerAboutToStartEvent event) {
        var shared = new NpcEvents.ServerAboutToStartEvent(event.getServer());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onServerStoppedEvent(net.minecraftforge.event.server.ServerStoppedEvent event) {
        var shared = new NpcEvents.ServerStoppedEvent(event.getServer());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onAddReloadListenerEvent(net.minecraftforge.event.AddReloadListenerEvent event) {
        var shared = new NpcEvents.AddReloadListenerEvent(event::addListener);
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerInteractEventRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return;
        var shared = new NpcEvents.PlayerInteractEvent.RightClickBlock(event.getLevel(), event.getPos(), event.getEntity());
        NpcEventHooks.fire(shared);
        if (shared.isCanceled()) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockEventBreakEvent(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        var shared = new NpcEvents.BlockEvent.BreakEvent(event.getLevel(), event.getPos(), event.getState(), event.getPlayer());
        NpcEventHooks.fire(shared);
        if (shared.isCanceled()) event.setCanceled(true);
    }
    @SubscribeEvent
    public static void onRegisterCommandsEvent(net.minecraftforge.event.RegisterCommandsEvent event) {
        var shared = new NpcEvents.RegisterCommandsEvent(event.getDispatcher());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onLivingEventLivingTickEvent(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        var shared = new NpcEvents.LivingEvent.LivingTickEvent(event.getEntity());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onServerStoppingEvent(net.minecraftforge.event.server.ServerStoppingEvent event) {
        var shared = new NpcEvents.ServerStoppingEvent(event.getServer());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onTickEventPlayerTickEvent(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        var shared = new NpcEvents.TickEvent.PlayerTickEvent(event.player, event.phase == net.minecraftforge.event.TickEvent.Phase.END ? NpcEvents.TickEvent.Phase.END : NpcEvents.TickEvent.Phase.START);
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeathEvent(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.isCanceled()) return;
        var shared = new NpcEvents.LivingDeathEvent(event.getEntity(), event.getSource());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onEntityLeaveLevelEvent(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        var shared = new NpcEvents.EntityLeaveLevelEvent(event.getEntity(), event.getLevel());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onPlayerEventPlayerLoggedInEvent(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        var shared = new NpcEvents.PlayerEvent.PlayerLoggedInEvent(event.getEntity());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onPlayerEventPlayerLoggedOutEvent(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        var shared = new NpcEvents.PlayerEvent.PlayerLoggedOutEvent(event.getEntity());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onPlayerEventPlayerRespawnEvent(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        var shared = new NpcEvents.PlayerEvent.PlayerRespawnEvent(event.getEntity());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onPlayerEventClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        var shared = new NpcEvents.PlayerEvent.Clone(event.getEntity(), event.getOriginal());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingAttackEvent(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
        if (event.isCanceled()) return;
        var shared = new NpcEvents.LivingAttackEvent(event.getEntity(), event.getSource());
        NpcEventHooks.fire(shared);
        if (shared.isCanceled()) event.setCanceled(true);
    }
    @SubscribeEvent
    public static void onServerStartedEvent(net.minecraftforge.event.server.ServerStartedEvent event) {
        var shared = new NpcEvents.ServerStartedEvent(event.getServer());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onTickEventServerTickEvent(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        var shared = new NpcEvents.TickEvent.ServerTickEvent(event.getServer(), event.phase == net.minecraftforge.event.TickEvent.Phase.END ? NpcEvents.TickEvent.Phase.END : NpcEvents.TickEvent.Phase.START);
        NpcEventHooks.tickWork(shared);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTickStart(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.START) com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerTickStart(new NpcEvents.TickEvent.ServerTickEvent(event.getServer(), NpcEvents.TickEvent.Phase.START));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTickEnd(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerTickEnd(new NpcEvents.TickEvent.ServerTickEvent(event.getServer(), NpcEvents.TickEvent.Phase.END));
    }
    @SubscribeEvent
    public static void onPlayerEventPlayerChangedDimensionEvent(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        var shared = new NpcEvents.PlayerEvent.PlayerChangedDimensionEvent(event.getEntity());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onEntityJoinLevelEvent(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        var shared = new NpcEvents.EntityJoinLevelEvent(event.getEntity(), event.getLevel());
        NpcEventHooks.fire(shared);
    }
    @SubscribeEvent
    public static void onPlayerEventTabListNameFormat(net.minecraftforge.event.entity.player.PlayerEvent.TabListNameFormat event) {
        var shared = new NpcEvents.PlayerEvent.TabListNameFormat(event.getEntity());
        NpcEventHooks.fire(shared);
        if (shared.getDisplayName() != null) event.setDisplayName(shared.getDisplayName());
    }
}
