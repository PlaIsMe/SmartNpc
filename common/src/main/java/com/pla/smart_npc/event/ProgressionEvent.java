package com.pla.smart_npc.event;

import com.pla.smart_npc.platform.event.NpcEvents.*;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.clazz.Difficulty;
import com.pla.smart_npc.util.ProgressionUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

public final class ProgressionEvent {
    private ProgressionEvent() {
    }
    public static void onServerStarted(ServerStartedEvent event) {
        ProgressionUtil.reconcileHistoricalProgression(event.getServer());
    }
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount() % 20 == 0) {
            ProgressionUtil.reconcileDragonFightProgression(event.getServer());
        }
    }
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProgressionUtil.reconcileHistoricalProgression(player);
        }
    }
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ProgressionUtil.increaseDifficulty(player.server, Difficulty.MEDIUM);
        }
    }
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon && event.getEntity().level() instanceof ServerLevel level) {
            ProgressionUtil.increaseDifficulty(level.getServer(), Difficulty.HARD);
        }
    }
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon
                && dragon.dragonDeathTime > 0
                && dragon.level() instanceof ServerLevel level) {
            ProgressionUtil.increaseDifficulty(level.getServer(), Difficulty.HARD);
        }
    }
}
