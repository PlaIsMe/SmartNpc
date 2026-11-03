package com.pla.smart_npc.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.WeakHashMap;

/** Server-scoped, hysteretic coverage policy for retained material searches. */
public final class PlayerNpcAdaptiveSearchScope {
    public static final int LOCAL_RADIUS = 2;
    public static final int NORMAL_RADIUS = 4;
    public static final int HEALTHY_RADIUS = 5;
    public static final int ORE_LOCAL_RADIUS = 5;
    public static final int ORE_NORMAL_RADIUS = 10;
    public static final int ORE_HEALTHY_RADIUS = 15;
    public static final int BUILD_MATERIAL_LOCAL_RADIUS = 8;
    public static final int BUILD_MATERIAL_NORMAL_RADIUS = 16;
    public static final int BUILD_MATERIAL_HEALTHY_RADIUS = 32;
    public static final int MAX_COLUMNS_PER_SLICE = 25;

    private static final long EVALUATION_INTERVAL_TICKS = 100L;
    private static final double HEALTHY_MSPT = 40.0D;
    private static final double DEGRADED_MSPT = 45.0D;
    private static final double CRITICAL_MSPT = 50.0D;
    private static final int PROMOTION_CHECKS = 3;
    private static final int DEMOTION_CHECKS = 2;
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();

    private PlayerNpcAdaptiveSearchScope() {
    }

    /** Returns a snapshot to freeze for one retained search episode. */
    public static int coverageRadius(ServerLevel serverLevel) {
        if (serverLevel == null) {
            return LOCAL_RADIUS;
        }
        MinecraftServer server = serverLevel.getServer();
        synchronized (STATES) {
            return STATES.computeIfAbsent(server, ignored -> new State()).update(server.getTickCount());
        }
    }

    public static int oreCoverageRadius(ServerLevel serverLevel) {
        return mapTier(coverageRadius(serverLevel), ORE_LOCAL_RADIUS, ORE_NORMAL_RADIUS, ORE_HEALTHY_RADIUS);
    }

    public static int buildMaterialCoverageRadius(ServerLevel serverLevel) {
        return mapTier(
                coverageRadius(serverLevel),
                BUILD_MATERIAL_LOCAL_RADIUS,
                BUILD_MATERIAL_NORMAL_RADIUS,
                BUILD_MATERIAL_HEALTHY_RADIUS
        );
    }

    public static SearchScopeSnapshot snapshot(MinecraftServer server) {
        if (server == null) {
            return SearchScopeSnapshot.local();
        }
        int logRadius;
        synchronized (STATES) {
            logRadius = STATES.computeIfAbsent(server, ignored -> new State()).update(server.getTickCount());
        }
        return new SearchScopeSnapshot(
                logRadius,
                mapTier(logRadius, ORE_LOCAL_RADIUS, ORE_NORMAL_RADIUS, ORE_HEALTHY_RADIUS),
                mapTier(
                        logRadius,
                        BUILD_MATERIAL_LOCAL_RADIUS,
                        BUILD_MATERIAL_NORMAL_RADIUS,
                        BUILD_MATERIAL_HEALTHY_RADIUS
                )
        );
    }

    private static int mapTier(int logRadius, int local, int normal, int healthy) {
        if (logRadius >= HEALTHY_RADIUS) {
            return healthy;
        }
        return logRadius >= NORMAL_RADIUS ? normal : local;
    }

    private static final class State {
        private long lastEvaluationTick = Long.MIN_VALUE;
        private int radius = LOCAL_RADIUS;
        private int healthyChecks;
        private int degradedChecks;

        private int update(long tick) {
            if (this.lastEvaluationTick != Long.MIN_VALUE
                    && tick - this.lastEvaluationTick < EVALUATION_INTERVAL_TICKS) {
                return this.radius;
            }
            this.lastEvaluationTick = tick;
            if (!PlayerNpcPerformanceMonitor.hasStableRollingSample()) {
                this.radius = LOCAL_RADIUS;
                this.healthyChecks = 0;
                this.degradedChecks = 0;
                return this.radius;
            }

            double baselineMspt = PlayerNpcPerformanceMonitor.getRollingBaselineMspt();
            if (baselineMspt >= CRITICAL_MSPT) {
                this.healthyChecks = 0;
                if (++this.degradedChecks >= DEMOTION_CHECKS) {
                    this.radius = LOCAL_RADIUS;
                    this.degradedChecks = 0;
                }
                return this.radius;
            }
            if (baselineMspt >= DEGRADED_MSPT) {
                this.healthyChecks = 0;
                if (++this.degradedChecks >= DEMOTION_CHECKS) {
                    this.radius = this.radius > NORMAL_RADIUS ? NORMAL_RADIUS : LOCAL_RADIUS;
                    this.degradedChecks = 0;
                }
                return this.radius;
            }

            this.degradedChecks = 0;
            if (baselineMspt > HEALTHY_MSPT) {
                this.healthyChecks = 0;
                return this.radius;
            }
            if (++this.healthyChecks >= PROMOTION_CHECKS) {
                this.radius = this.radius < NORMAL_RADIUS ? NORMAL_RADIUS : HEALTHY_RADIUS;
                this.healthyChecks = 0;
            }
            return this.radius;
        }
    }

    public record SearchScopeSnapshot(int logRadius, int oreRadius, int buildMaterialRadius) {
        private static SearchScopeSnapshot local() {
            return new SearchScopeSnapshot(LOCAL_RADIUS, ORE_LOCAL_RADIUS, BUILD_MATERIAL_LOCAL_RADIUS);
        }

        public int logFootprintWidth() {
            return this.logRadius * 2 + 1;
        }
    }
}
