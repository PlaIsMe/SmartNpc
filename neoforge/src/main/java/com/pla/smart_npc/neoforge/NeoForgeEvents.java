package com.pla.smart_npc.neoforge;

import com.pla.smart_npc.platform.event.*;
import net.neoforged.bus.api.EventPriority;

@net.neoforged.fml.common.EventBusSubscriber(modid="smart_npc")
public final class NeoForgeEvents {
    @net.neoforged.bus.api.SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onYsmStarting(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) { com.pla.smart_npc.compat.YesSteveModelCompat.onServerAboutToStart(new NpcEvents.ServerAboutToStartEvent(event.getServer())); }
    @net.neoforged.bus.api.SubscribeEvent
    public static void onYsmStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) { com.pla.smart_npc.compat.YesSteveModelCompat.onServerStopped(new NpcEvents.ServerStoppedEvent(event.getServer())); }



    @net.neoforged.bus.api.SubscribeEvent
    public static void onNpcGearLoadEvent_onAddReloadListeners(net.neoforged.neoforge.event.AddServerReloadListenersEvent event) {
        var shared=new NpcEvents.AddReloadListenerEvent(event::addListener);
        new com.pla.smart_npc.event.NpcGearLoadEvent().onAddReloadListeners(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerNpcChestProtectEvent_onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        var shared=new NpcEvents.PlayerInteractEvent.RightClickBlock(event.getLevel(),event.getPos(),event.getEntity());
        shared.setCanceled(event.isCanceled());
        com.pla.smart_npc.event.PlayerNpcChestProtectEvent.onRightClickBlock(shared);
        event.setCanceled(shared.isCanceled());
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerNpcChestProtectEvent_onBlockBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        var shared=new NpcEvents.BlockEvent.BreakEvent(event.getLevel(),event.getPos(),event.getState(),event.getPlayer());
        shared.setCanceled(event.isCanceled());
        com.pla.smart_npc.event.PlayerNpcChestProtectEvent.onBlockBreak(shared);
        event.setCanceled(shared.isCanceled());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcCommandEvent_registerCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        var shared=new NpcEvents.RegisterCommandsEvent(event.getDispatcher());
        com.pla.smart_npc.event.PlayerNpcCommandEvent.registerCommands(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcDepartureEvent_tick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        var shared=new NpcEvents.EntityTickEvent.Post(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcDepartureEvent.tick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcDepartureEvent_stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        var shared=new NpcEvents.ServerStoppingEvent(event.getServer());
        com.pla.smart_npc.event.PlayerNpcDepartureEvent.stopping(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcHomeEvent_onBlockBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        var shared=new NpcEvents.BlockEvent.BreakEvent(event.getLevel(),event.getPos(),event.getState(),event.getPlayer());
        shared.setCanceled(event.isCanceled());
        com.pla.smart_npc.event.PlayerNpcHomeEvent.onBlockBreak(shared);
        event.setCanceled(shared.isCanceled());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcInspectatorEvent_onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        var shared=new NpcEvents.PlayerTickEvent.Post(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerTick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcInspectatorEvent_onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        var shared=new NpcEvents.LivingDeathEvent(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onLivingDeath(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcInspectatorEvent_onEntityLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        var shared=new NpcEvents.EntityLeaveLevelEvent(event.getEntity(),event.getLevel());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onEntityLeaveLevel(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcInspectatorEvent_onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedInEvent(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerLoggedIn(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcInspectatorEvent_onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedOutEvent(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerLoggedOut(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcInspectatorEvent_onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerRespawnEvent(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerRespawn(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcInspectatorEvent_onPlayerClone(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone event) {
        var shared=new NpcEvents.PlayerEvent.Clone(event.getEntity(),event.getOriginal());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerClone(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcTeamUpEvent_onPlayerLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedOutEvent(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onPlayerLogout(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcTeamUpEvent_onEntityLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        var shared=new NpcEvents.EntityLeaveLevelEvent(event.getEntity(),event.getLevel());
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onEntityLeave(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcTeamUpEvent_onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        var shared=new NpcEvents.ServerStoppedEvent(event.getServer());
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onServerStopped(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerNpcTeamUpEvent_onLivingAttack(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        var shared=new NpcEvents.LivingIncomingDamageEvent(event.getEntity(),event.getSource());
        shared.setCanceled(event.isCanceled());
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onLivingAttack(shared);
        event.setCanceled(shared.isCanceled());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcTeamUpEvent_onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        var shared=new NpcEvents.LivingDeathEvent(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onLivingDeath(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onProgressionEvent_onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        var shared=new NpcEvents.ServerStartedEvent(event.getServer());
        com.pla.smart_npc.event.ProgressionEvent.onServerStarted(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onProgressionEvent_onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var shared=new NpcEvents.ServerTickEvent.Post(event.getServer());
        com.pla.smart_npc.event.ProgressionEvent.onServerTick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onProgressionEvent_onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedInEvent(event.getEntity());
        com.pla.smart_npc.event.ProgressionEvent.onPlayerLoggedIn(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onProgressionEvent_onPlayerChangedDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerChangedDimensionEvent(event.getEntity());
        com.pla.smart_npc.event.ProgressionEvent.onPlayerChangedDimension(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onProgressionEvent_onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        var shared=new NpcEvents.LivingDeathEvent(event.getEntity());
        com.pla.smart_npc.event.ProgressionEvent.onLivingDeath(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onProgressionEvent_onLivingTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        var shared=new NpcEvents.EntityTickEvent.Post(event.getEntity());
        com.pla.smart_npc.event.ProgressionEvent.onLivingTick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onEntityJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        var shared=new NpcEvents.EntityJoinLevelEvent(event.getEntity(),event.getLevel());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onEntityJoinLevel(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onEntityLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        var shared=new NpcEvents.EntityLeaveLevelEvent(event.getEntity(),event.getLevel());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onEntityLeaveLevel(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        var shared=new NpcEvents.LivingDeathEvent(event.getEntity());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onLivingDeath(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedInEvent(event.getEntity());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onPlayerLoggedIn(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var shared=new NpcEvents.ServerTickEvent.Post(event.getServer());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onServerTick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        var shared=new NpcEvents.ServerStartedEvent(event.getServer());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onServerStarted(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        var shared=new NpcEvents.ServerStoppingEvent(event.getServer());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onServerStopping(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcForceTickManager_onTabListNameFormat(net.neoforged.neoforge.event.entity.player.PlayerEvent.TabListNameFormat event) {
        var shared=new NpcEvents.PlayerEvent.TabListNameFormat(event.getEntity());
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onTabListNameFormat(shared);
        if (shared.getDisplayName()!=null) event.setDisplayName(shared.getDisplayName());
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcGoalTraceLogger_onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var shared=new NpcEvents.ServerTickEvent.Post(event.getServer());
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onServerTick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcGoalTraceLogger_onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedInEvent(event.getEntity());
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onPlayerLoggedIn(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcGoalTraceLogger_onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedOutEvent(event.getEntity());
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onPlayerLoggedOut(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcGoalTraceLogger_onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        var shared=new NpcEvents.ServerStartedEvent(event.getServer());
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onServerStarted(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcGoalTraceLogger_onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        var shared=new NpcEvents.ServerStoppingEvent(event.getServer());
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onServerStopping(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcNaturalSpawnCap_onEntityJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        var shared=new NpcEvents.EntityJoinLevelEvent(event.getEntity(),event.getLevel());
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onEntityJoinLevel(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcNaturalSpawnCap_onEntityLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        var shared=new NpcEvents.EntityLeaveLevelEvent(event.getEntity(),event.getLevel());
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onEntityLeaveLevel(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcNaturalSpawnCap_onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        var shared=new NpcEvents.LivingDeathEvent(event.getEntity());
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onLivingDeath(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcNaturalSpawnCap_onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        var shared=new NpcEvents.ServerStartedEvent(event.getServer());
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onServerStarted(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcNaturalSpawnCap_onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var shared=new NpcEvents.ServerTickEvent.Post(event.getServer());
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onServerTick(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcNaturalSpawnCap_onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        var shared=new NpcEvents.ServerStoppingEvent(event.getServer());
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onServerStopping(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerNpcPerformanceMonitor_onServerTickStart(net.neoforged.neoforge.event.tick.ServerTickEvent.Pre event) {
        var shared=new NpcEvents.ServerTickEvent.Pre(event.getServer());
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerTickStart(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerNpcPerformanceMonitor_onServerTickEnd(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var shared=new NpcEvents.ServerTickEvent.Post(event.getServer());
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerTickEnd(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcPerformanceMonitor_onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        var shared=new NpcEvents.PlayerEvent.PlayerLoggedInEvent(event.getEntity());
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onPlayerLoggedIn(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcPerformanceMonitor_onEntityJoinLevel(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        var shared=new NpcEvents.EntityJoinLevelEvent(event.getEntity(),event.getLevel());
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onEntityJoinLevel(shared);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onPlayerNpcPerformanceMonitor_onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        var shared=new NpcEvents.ServerStoppedEvent(event.getServer());
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerStopped(shared);
    }

}
