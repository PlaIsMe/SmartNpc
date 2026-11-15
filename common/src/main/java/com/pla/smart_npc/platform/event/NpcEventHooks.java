package com.pla.smart_npc.platform.event;
import com.pla.smart_npc.ai.AiChatService;
import com.pla.smart_npc.platform.event.NpcEvents.*;
public final class NpcEventHooks {
    public static void fire(ServerAboutToStartEvent event) {
        com.pla.smart_npc.compat.YesSteveModelCompat.onServerAboutToStart(event);
    }
    public static void fire(ServerStoppedEvent event) {
        AiChatService.stop(event.getServer());
        com.pla.smart_npc.compat.YesSteveModelCompat.onServerStopped(event);
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onServerStopped(event);
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerStopped(event);
    }
    public static void fire(AddReloadListenerEvent event) {
        new com.pla.smart_npc.event.NpcGearLoadEvent().onAddReloadListeners(event);
    }
    public static void fire(PlayerInteractEvent.RightClickBlock event) {
        com.pla.smart_npc.event.PlayerNpcChestProtectEvent.onRightClickBlock(event);
    }
    public static void fire(BlockEvent.BreakEvent event) {
        com.pla.smart_npc.event.PlayerNpcHomeEvent.onBlockBreak(event);
        com.pla.smart_npc.event.PlayerNpcChestProtectEvent.onBlockBreak(event);
    }
    public static void fire(RegisterCommandsEvent event) {
        com.pla.smart_npc.event.PlayerNpcCommandEvent.registerCommands(event);
    }
    public static void fire(LivingEvent.LivingTickEvent event) {
        com.pla.smart_npc.event.PlayerNpcDepartureEvent.tick(event);
        com.pla.smart_npc.event.ProgressionEvent.onLivingTick(event);
    }
    public static void fire(ServerStoppingEvent event) {
        AiChatService.stop(event.getServer());
        com.pla.smart_npc.event.PlayerNpcDepartureEvent.stopping(event);
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onServerStopping(event);
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onServerStopping(event);
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onServerStopping(event);
    }
    public static void fire(TickEvent.PlayerTickEvent event) {
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerTick(event);
    }
    public static void fire(LivingDeathEvent event) {
        if (AiChatService.get(event.getEntity().getServer()) != null) AiChatService.get(event.getEntity().getServer()).witnessDeath(event.getEntity(), event.getSource());
        if (AiChatService.get(event.getEntity().getServer()) != null) AiChatService.get(event.getEntity().getServer()).forget(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onLivingDeath(event);
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onLivingDeath(event);
        com.pla.smart_npc.event.ProgressionEvent.onLivingDeath(event);
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onLivingDeath(event);
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onLivingDeath(event);
    }
    public static void fire(EntityLeaveLevelEvent event) {
        if (AiChatService.get(event.getEntity().getServer()) != null) AiChatService.get(event.getEntity().getServer()).forget(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onEntityLeaveLevel(event);
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onEntityLeave(event);
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onEntityLeaveLevel(event);
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onEntityLeaveLevel(event);
    }
    public static void fire(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player && AiChatService.get(player.getServer()) != null) AiChatService.get(player.getServer()).onPlayerJoin(player);
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerLoggedIn(event);
        com.pla.smart_npc.event.ProgressionEvent.onPlayerLoggedIn(event);
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onPlayerLoggedIn(event);
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onPlayerLoggedIn(event);
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onPlayerLoggedIn(event);
    }
    public static void fire(PlayerEvent.PlayerLoggedOutEvent event) {
        if (AiChatService.get(event.getEntity().getServer()) != null) AiChatService.get(event.getEntity().getServer()).forget(event.getEntity());
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerLoggedOut(event);
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onPlayerLogout(event);
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onPlayerLoggedOut(event);
    }
    public static void fire(PlayerEvent.PlayerRespawnEvent event) {
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerRespawn(event);
    }
    public static void fire(PlayerEvent.Clone event) {
        com.pla.smart_npc.event.PlayerNpcInspectatorEvent.onPlayerClone(event);
    }
    public static void fire(LivingAttackEvent event) {
        com.pla.smart_npc.event.PlayerNpcTeamUpEvent.onLivingAttack(event);
    }
    public static void fire(ServerStartedEvent event) {
        AiChatService.start(event.getServer());
        com.pla.smart_npc.event.ProgressionEvent.onServerStarted(event);
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onServerStarted(event);
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onServerStarted(event);
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onServerStarted(event);
    }
    public static void fire(TickEvent.ServerTickEvent event) {
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerTickStart(event);
        tickWork(event);
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onServerTickEnd(event);
    }
    public static void tickWork(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && AiChatService.get(event.getServer()) != null) AiChatService.get(event.getServer()).tick();
        com.pla.smart_npc.event.ProgressionEvent.onServerTick(event);
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onServerTick(event);
        com.pla.smart_npc.util.PlayerNpcGoalTraceLogger.onServerTick(event);
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onServerTick(event);
    }
    public static void fire(PlayerEvent.PlayerChangedDimensionEvent event) {
        com.pla.smart_npc.event.ProgressionEvent.onPlayerChangedDimension(event);
    }
    public static void fire(EntityJoinLevelEvent event) {
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onEntityJoinLevel(event);
        com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap.onEntityJoinLevel(event);
        com.pla.smart_npc.util.PlayerNpcPerformanceMonitor.onEntityJoinLevel(event);
    }
    public static void fire(PlayerEvent.TabListNameFormat event) {
        com.pla.smart_npc.util.PlayerNpcForceTickManager.onTabListNameFormat(event);
    }
}
