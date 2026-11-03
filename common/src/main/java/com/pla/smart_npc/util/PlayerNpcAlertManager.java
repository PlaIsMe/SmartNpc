package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PlayerNpcAlertManager {
    private static final int THREAT_ALERT_TICKS = 20 * 45;
    private static final int DEATH_ALERT_TICKS = 20 * 90;
    private static final Map<ResourceKey<Level>, List<Alert>> ALERTS = new HashMap<>();

    public static void raiseThreatAlert(PlayerNpcEntity reporter, LivingEntity threat) {
        raiseAlert(reporter, threat, false);
    }

    public static void raiseDeathAlert(PlayerNpcEntity reporter, LivingEntity threat) {
        // ChatUtil.reportDeath owns the visible warning/reaction; this method owns AI state only.
        raiseAlert(reporter, threat, true);
    }

    public static Optional<LivingEntity> getNearbyThreat(PlayerNpcEntity listener, double radius) {
        if (!(listener.level() instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }

        prune(serverLevel);
        List<Alert> alerts = ALERTS.getOrDefault(serverLevel.dimension(), List.of());
        double radiusSqr = radius * radius;

        for (Alert alert : alerts) {
            if (alert.reporterId().equals(listener.getUUID())) {
                continue;
            }
            if (listener.blockPosition().distSqr(alert.position()) > radiusSqr) {
                continue;
            }

            Entity threat = serverLevel.getEntity(alert.threatId());
            if (threat instanceof LivingEntity livingThreat && livingThreat.isAlive()) {
                if (listener.isTeamAlliedWith(livingThreat)) {
                    continue;
                }
                return Optional.of(livingThreat);
            }
        }

        return Optional.empty();
    }

    private static boolean raiseAlert(PlayerNpcEntity reporter, LivingEntity threat, boolean deathAlert) {
        if (!(reporter.level() instanceof ServerLevel serverLevel)
                || threat == null
                || !threat.isAlive()) {
            return false;
        }

        prune(serverLevel);
        List<Alert> alerts = ALERTS.computeIfAbsent(serverLevel.dimension(), key -> new ArrayList<>());
        long expiresAt = serverLevel.getGameTime() + (deathAlert ? DEATH_ALERT_TICKS : THREAT_ALERT_TICKS);
        alerts.add(new Alert(reporter.getUUID(), threat.getUUID(), reporter.blockPosition().immutable(), expiresAt));
        return true;
    }

    private static void prune(ServerLevel serverLevel) {
        List<Alert> alerts = ALERTS.get(serverLevel.dimension());
        if (alerts == null) {
            return;
        }

        long gameTime = serverLevel.getGameTime();
        Iterator<Alert> iterator = alerts.iterator();
        while (iterator.hasNext()) {
            Alert alert = iterator.next();
            if (alert.expiresAt() <= gameTime || !(serverLevel.getEntity(alert.threatId()) instanceof LivingEntity livingThreat) || !livingThreat.isAlive()) {
                iterator.remove();
            }
        }
    }

    private record Alert(UUID reporterId, UUID threatId, BlockPos position, long expiresAt) {}
}
