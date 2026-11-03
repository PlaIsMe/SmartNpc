package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

@EventBusSubscriber(modid = SmartNpc.MODID)
public final class PlayerNpcPerformanceMonitor {
    private static final int ROLLING_WINDOW_TICKS = 100;
    private static final int MIN_AVERAGE_WARNING_SAMPLES = 20;
    private static final int STARTUP_WARMUP_TICKS = 20 * 10;
    private static final int PLAYER_JOIN_WARMUP_TICKS = 20 * 8;
    private static final int PLAYER_NPC_JOIN_WARMUP_TICKS = 20 * 5;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0D;
    private static final double MAX_TPS = 20.0D;
    private static final double PAUSE_OR_LOAD_TICK_MSPT = 1000.0D;
    private static final double ROLLING_WARNING_CURRENT_TICK_MSPT_FLOOR = 50.0D;
    private static final double HEALTHY_AVERAGE_SPIKE_SUPPRESSION_MSPT = 50.0D;
    private static final double SEVERE_SINGLE_TICK_SPIKE_MSPT = 1000.0D;
    private static final double AVERAGE_WARNING_MSPT = 60.0D;
    private static final double SPIKE_WARNING_MSPT = 200.0D;
    private static final double OPTIONAL_AI_MAX_AVERAGE_MSPT = 50.0D;
    private static final double NPC_DOMINATED_WARNING_MIN_MSPT = 25.0D;
    private static final int WARNING_COOLDOWN_TICKS = 200;
    private static final int MAX_WARNING_TRACE_LINES = 8;
    private static final String PASSIVE_HOME_STATE = "ai.player_npc.being_at_home";

    private static final double[] rollingMspt = new double[ROLLING_WINDOW_TICKS];
    private static final double[] rollingNpcMs = new double[ROLLING_WINDOW_TICKS];
    private static int rollingIndex;
    private static int rollingCount;
    private static double rollingTotalMspt;
    private static double rollingTotalNpcMs;
    private static double latestMspt;
    private static long tickStartNanos = -1L;
    private static long ignoreSamplesUntilServerTick = Long.MIN_VALUE;
    private static long lastWarningServerTick = Long.MIN_VALUE;
    private static long lastSuppressedWarningServerTick = Long.MIN_VALUE;
    private static long measuredNpcTickNanos;
    private static long measuredNpcSuperTickNanos;
    private static long measuredNpcCustomTickNanos;
    private static long hottestNpcTickNanos;
    private static long hottestNpcSuperTickNanos;
    private static long hottestNpcCustomTickNanos;
    private static long hottestNpcWrappedGoalNanos;
    private static long hottestNpcUnattributedSuperNanos;
    private static String hottestNpcTickName = "none";
    private static String hottestNpcTickState = PlayerNpcEntity.AI_IDLE;
    private static String hottestNpcTickDetail = "none";
    private static String hottestNpcNavigationDetail = "none";
    private static long measuredForceManagerNanos;
    private static long measuredTraceLoggerNanos;
    private static long measuredWaitingStrollNanos;
    private static long hottestGoalWorkNanos;
    private static long totalGoalWorkNanos;
    private static int goalWorkInvocationCount;
    private static String hottestGoalWorkName = "none";
    private static String hottestGoalWorkNpc = "none";
    private static final Map<Integer, Long> goalWorkNanosByNpcId = new LinkedHashMap<>();

    private PlayerNpcPerformanceMonitor() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerTickStart(ServerTickEvent.Pre event) {
        measuredNpcTickNanos = 0L;
        measuredNpcSuperTickNanos = 0L;
        measuredNpcCustomTickNanos = 0L;
        hottestNpcTickNanos = 0L;
        hottestNpcSuperTickNanos = 0L;
        hottestNpcCustomTickNanos = 0L;
        hottestNpcWrappedGoalNanos = 0L;
        hottestNpcUnattributedSuperNanos = 0L;
        hottestNpcTickName = "none";
        hottestNpcTickState = PlayerNpcEntity.AI_IDLE;
        hottestNpcTickDetail = "none";
        hottestNpcNavigationDetail = "none";
        measuredForceManagerNanos = 0L;
        measuredTraceLoggerNanos = 0L;
        measuredWaitingStrollNanos = 0L;
        hottestGoalWorkNanos = 0L;
        totalGoalWorkNanos = 0L;
        goalWorkInvocationCount = 0;
        hottestGoalWorkName = "none";
        hottestGoalWorkNpc = "none";
        goalWorkNanosByNpcId.clear();
        tickStartNanos = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        if (tickStartNanos < 0L) {
            tickStartNanos = -1L;
            return;
        }

        long elapsedNanos = Math.max(0L, System.nanoTime() - tickStartNanos);
        tickStartNanos = -1L;

        latestMspt = elapsedNanos / NANOS_PER_MILLISECOND;
        if (isWarmupSample(event.getServer())) {
            resetSamples();
            latestMspt = 0.0D;
            return;
        }
        // Startup/player joins own explicit warmups. A later extreme pause/load tick is omitted
        // from the rolling window without erasing stable evidence from the surrounding slow run.
        // Still evaluate it against the existing stable window so the severe-spike diagnostic is
        // reachable and an actual 1000+ ms NPC stall is not silently discarded.
        if (latestMspt >= PAUSE_OR_LOAD_TICK_MSPT) {
            maybeLogWarning(event.getServer(), latestMspt);
            return;
        }

        addSample(latestMspt);
        maybeLogWarning(event.getServer(), latestMspt);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level() instanceof ServerLevel serverLevel) {
            startWarmup(serverLevel.getServer(), PLAYER_JOIN_WARMUP_TICKS);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity
                && event.getLevel() instanceof ServerLevel serverLevel) {
            startWarmup(serverLevel.getServer(), PLAYER_NPC_JOIN_WARMUP_TICKS);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PlayerNpcAiWorkBudget.clear(event.getServer());
        tickStartNanos = -1L;
        latestMspt = 0.0D;
        ignoreSamplesUntilServerTick = Long.MIN_VALUE;
        lastWarningServerTick = Long.MIN_VALUE;
        lastSuppressedWarningServerTick = Long.MIN_VALUE;
        measuredNpcTickNanos = 0L;
        measuredNpcSuperTickNanos = 0L;
        measuredNpcCustomTickNanos = 0L;
        hottestNpcTickNanos = 0L;
        hottestNpcSuperTickNanos = 0L;
        hottestNpcCustomTickNanos = 0L;
        hottestNpcWrappedGoalNanos = 0L;
        hottestNpcUnattributedSuperNanos = 0L;
        hottestNpcNavigationDetail = "none";
        measuredForceManagerNanos = 0L;
        measuredTraceLoggerNanos = 0L;
        measuredWaitingStrollNanos = 0L;
        hottestGoalWorkNanos = 0L;
        totalGoalWorkNanos = 0L;
        goalWorkInvocationCount = 0;
        hottestGoalWorkName = "none";
        hottestGoalWorkNpc = "none";
        goalWorkNanosByNpcId.clear();
        resetSamples();
    }

    public static String createInspectorText() {
        if (rollingCount <= 0) {
            return "TPS warming up";
        }

        return String.format(
                Locale.ROOT,
                "TPS %.1f/20 | MSPT %.1f avg, %.1f last",
                getAverageTps(),
                getAverageMspt(),
                latestMspt
        );
    }

    public static boolean shouldMeasureNpcEntityTick() {
        return true;
    }

    /** Rolling server tick time used by the automatic AI scheduler. Zero means no stable sample yet. */
    public static double getRollingAverageMspt() {
        return getAverageMspt();
    }

    /** Rolling measured Player NPC entity time used by the automatic natural-spawn cap. */
    public static double getRollingAverageNpcMs() {
        return rollingCount > 0
                ? rollingTotalNpcMs / rollingCount
                : 0.0D;
    }

    /**
     * Rolling baseline used by population probing. The highest five percent of samples are
     * omitted so an isolated admitted path/search spike cannot masquerade as sustained passive
     * population cost. Sustained overload remains in the other ninety-five percent.
     */
    public static double getRollingBaselineMspt() {
        if (rollingCount <= 0) {
            return 0.0D;
        }
        double[] ordered = Arrays.copyOf(rollingMspt, rollingCount);
        Arrays.sort(ordered);
        int excludedHighSamples = Math.max(1, rollingCount / 20);
        int includedSamples = Math.max(1, rollingCount - excludedHighSamples);
        double total = 0.0D;
        for (int index = 0; index < includedSamples; index++) {
            total += ordered[index];
        }
        return total / includedSamples;
    }

    public static boolean hasStableRollingSample() {
        return rollingCount >= MIN_AVERAGE_WARNING_SAMPLES;
    }

    public static long beginAuxiliaryTiming() {
        return System.nanoTime();
    }

    public static void recordForceManagerTick(long startNanos) {
        if (startNanos > 0L) {
            measuredForceManagerNanos += Math.max(0L, System.nanoTime() - startNanos);
        }
    }

    public static void recordTraceLoggerTick(long startNanos) {
        if (startNanos > 0L) {
            measuredTraceLoggerNanos += Math.max(0L, System.nanoTime() - startNanos);
        }
    }

    public static void recordWaitingStrollWork(long startNanos) {
        if (startNanos > 0L) {
            measuredWaitingStrollNanos += Math.max(0L, System.nanoTime() - startNanos);
        }
    }

    /** Optional visual AI waits for a trustworthy sample and yields once the server misses 20 TPS. */
    public static boolean isOptionalAiWorkAllowed() {
        return hasStableRollingSample() && getAverageMspt() < OPTIONAL_AI_MAX_AVERAGE_MSPT;
    }

    /**
     * True while the stable rolling window is already missing 20 TPS. Runtime schedulers and
     * navigation goals use this to shed optional capacity without abandoning active destinations.
     */
    public static boolean isAiWorkOverloaded() {
        return hasStableRollingSample() && getAverageMspt() >= OPTIONAL_AI_MAX_AVERAGE_MSPT;
    }

    /** Records executed Player NPC time, unlike post-tick active-state correlation. */
    public static void recordNpcEntityTick(
            PlayerNpcEntity playerNpc,
            long elapsedNanos,
            long superTickNanos
    ) {
        if (playerNpc == null
                || elapsedNanos <= 0L) {
            return;
        }
        long boundedSuperTickNanos = Math.max(0L, Math.min(elapsedNanos, superTickNanos));
        long customTickNanos = Math.max(0L, elapsedNanos - boundedSuperTickNanos);
        measuredNpcTickNanos += elapsedNanos;
        measuredNpcSuperTickNanos += boundedSuperTickNanos;
        measuredNpcCustomTickNanos += customTickNanos;
        if (elapsedNanos > hottestNpcTickNanos) {
            long wrappedGoalNanos = goalWorkNanosByNpcId.getOrDefault(playerNpc.getId(), 0L);
            hottestNpcTickNanos = elapsedNanos;
            hottestNpcSuperTickNanos = boundedSuperTickNanos;
            hottestNpcCustomTickNanos = customTickNanos;
            hottestNpcWrappedGoalNanos = wrappedGoalNanos;
            hottestNpcUnattributedSuperNanos = Math.max(0L, boundedSuperTickNanos - wrappedGoalNanos);
            hottestNpcTickName = sanitize(playerNpc.getDisplayName().getString()) + "#" + playerNpc.getId();
            hottestNpcTickState = sanitize(playerNpc.getCurrentAiState());
            hottestNpcTickDetail = sanitize(playerNpc.getCurrentAiDetail());
            if (hottestNpcTickDetail.isBlank()) {
                hottestNpcTickDetail = "none";
            }
            var navigation = playerNpc.getNavigation();
            var path = navigation.getPath();
            hottestNpcNavigationDetail = path == null
                    ? "done=" + navigation.isDone() + " stuck=" + navigation.isStuck() + " path=none"
                    : "done=" + navigation.isDone()
                    + " stuck=" + navigation.isStuck()
                    + " nodes=" + path.getNodeCount()
                    + " next=" + path.getNextNodeIndex();
        }
    }

    /** Records the slowest wrapped goal phase so idle final states can be narrowed on the next warning. */
    public static void recordGoalWork(PlayerNpcEntity playerNpc, String phase, long startNanos) {
        if (startNanos <= 0L || playerNpc == null) {
            return;
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - startNanos);
        totalGoalWorkNanos += elapsedNanos;
        goalWorkInvocationCount++;
        goalWorkNanosByNpcId.merge(playerNpc.getId(), elapsedNanos, Long::sum);
        if (elapsedNanos <= hottestGoalWorkNanos) {
            return;
        }
        hottestGoalWorkNanos = elapsedNanos;
        hottestGoalWorkName = sanitize(phase);
        hottestGoalWorkNpc = sanitize(playerNpc.getDisplayName().getString()) + "#" + playerNpc.getId();
    }

    private static void addSample(double mspt) {
        if (rollingCount < rollingMspt.length) {
            rollingCount++;
        } else {
            rollingTotalMspt -= rollingMspt[rollingIndex];
            rollingTotalNpcMs -= rollingNpcMs[rollingIndex];
        }

        rollingMspt[rollingIndex] = mspt;
        rollingNpcMs[rollingIndex] = measuredNpcTickNanos / NANOS_PER_MILLISECOND;
        rollingTotalMspt += mspt;
        rollingTotalNpcMs += rollingNpcMs[rollingIndex];
        rollingIndex = (rollingIndex + 1) % rollingMspt.length;
    }

    private static boolean isWarmupSample(MinecraftServer server) {
        return server.getTickCount() < STARTUP_WARMUP_TICKS
                || server.getTickCount() < ignoreSamplesUntilServerTick;
    }

    private static void startWarmup(MinecraftServer server, int ticks) {
        if (server == null || ticks <= 0) {
            return;
        }

        ignoreSamplesUntilServerTick = Math.max(ignoreSamplesUntilServerTick, server.getTickCount() + ticks);
        resetSamples();
        latestMspt = 0.0D;
    }

    private static void resetSamples() {
        rollingIndex = 0;
        rollingCount = 0;
        rollingTotalMspt = 0.0D;
        rollingTotalNpcMs = 0.0D;
    }

    private static double getAverageMspt() {
        return rollingCount <= 0 ? 0.0D : rollingTotalMspt / rollingCount;
    }

    private static double getAverageTps() {
        double averageMspt = getAverageMspt();
        if (averageMspt <= 0.0D) {
            return MAX_TPS;
        }
        return Math.min(MAX_TPS, 1000.0D / averageMspt);
    }

    private static void maybeLogWarning(MinecraftServer server, double currentMspt) {
        if (!SmartNpcConfig.PERFORMANCE_MONITOR_ENABLED.get()) {
            return;
        }

        double averageMspt = getAverageMspt();
        boolean slowAverage = rollingCount >= MIN_AVERAGE_WARNING_SAMPLES
                && averageMspt >= AVERAGE_WARNING_MSPT
                && currentMspt >= Math.min(AVERAGE_WARNING_MSPT, ROLLING_WARNING_CURRENT_TICK_MSPT_FLOOR);
        boolean tickSpike = rollingCount >= MIN_AVERAGE_WARNING_SAMPLES
                && currentMspt >= SPIKE_WARNING_MSPT
                && (averageMspt >= HEALTHY_AVERAGE_SPIKE_SUPPRESSION_MSPT
                || currentMspt >= SEVERE_SINGLE_TICK_SPIKE_MSPT);
        if (!slowAverage && !tickSpike) {
            return;
        }

        long serverTick = server.getTickCount();
        if (lastWarningServerTick != Long.MIN_VALUE
                && serverTick - lastWarningServerTick < WARNING_COOLDOWN_TICKS) {
            return;
        }
        if (lastSuppressedWarningServerTick != Long.MIN_VALUE
                && serverTick - lastSuppressedWarningServerTick < Math.min(20, WARNING_COOLDOWN_TICKS)) {
            return;
        }

        NpcTraceSummary summary = collectNpcTrace(server);
        PlayerNpcAiWorkBudget.ResourceSnapshot resources = PlayerNpcAiWorkBudget.resourceSnapshot(server);
        double measuredNpcMs = measuredNpcTickNanos / NANOS_PER_MILLISECOND;
        boolean npcDominatedIdleTick = measuredNpcMs >= NPC_DOMINATED_WARNING_MIN_MSPT
                && measuredNpcMs >= currentMspt * 0.5D;
        if (summary.activeNpcCount() <= 0 && !npcDominatedIdleTick) {
            lastSuppressedWarningServerTick = serverTick;
            return;
        }

        lastWarningServerTick = serverTick;
        SmartNpc.LOGGER.warn(
                "Smart NPC TPS warning: server tick is slow; latestMspt={}, averageMspt={}, effectiveTps={}/20, sampleWindowTicks={}, reason={}, activePlayerNpcGoals={}, totalPlayerNpcs={}, routineWorkers={}/{}, runningRoutineWorkers={}, idleRoutineWorkers={}, waitingRoutineNpcs={}",
                format(currentMspt),
                format(averageMspt),
                format(getAverageTps()),
                rollingCount,
                tickSpike ? "single tick spike" : "rolling average",
                summary.activeNpcCount(),
                summary.totalNpcCount(),
                resources.activeWorkerCount(),
                resources.effectiveWorkerLimit(),
                resources.runningWorkerCount(),
                resources.idleWorkerCount(),
                resources.waitingNpcCount()
        );

        SmartNpc.LOGGER.warn("Smart NPC TPS trace states: {}", summary.stateCountsText());
        double averageNpcMs = rollingCount <= 0 ? 0.0D : rollingTotalNpcMs / rollingCount;
        SmartNpc.LOGGER.warn(
                "Smart NPC measured work: latestNpcMs={} (super={} custom={}), averageNpcMs={}, hottestNpcMs={} (super={} custom={} wrappedGoals={} unattributedSuper={}), hottestNpc={} state={} detail=\"{}\" nav=\"{}\", wrappedGoalTotalMs={} calls={}, hottestWrappedGoalMs={} goal={} npc={}, waitingStrollMs={}, forceManagerMs={}, traceLoggerMs={}; latestUnmeasuredServerMs={} (chunks, block entities, other entities/mods and server tasks)",
                format(measuredNpcMs),
                format(measuredNpcSuperTickNanos / NANOS_PER_MILLISECOND),
                format(measuredNpcCustomTickNanos / NANOS_PER_MILLISECOND),
                format(averageNpcMs),
                format(hottestNpcTickNanos / NANOS_PER_MILLISECOND),
                format(hottestNpcSuperTickNanos / NANOS_PER_MILLISECOND),
                format(hottestNpcCustomTickNanos / NANOS_PER_MILLISECOND),
                format(hottestNpcWrappedGoalNanos / NANOS_PER_MILLISECOND),
                format(hottestNpcUnattributedSuperNanos / NANOS_PER_MILLISECOND),
                hottestNpcTickName,
                hottestNpcTickState,
                hottestNpcTickDetail,
                hottestNpcNavigationDetail,
                format(totalGoalWorkNanos / NANOS_PER_MILLISECOND),
                goalWorkInvocationCount,
                format(hottestGoalWorkNanos / NANOS_PER_MILLISECOND),
                hottestGoalWorkName,
                hottestGoalWorkNpc,
                format(measuredWaitingStrollNanos / NANOS_PER_MILLISECOND),
                format(measuredForceManagerNanos / NANOS_PER_MILLISECOND),
                format(measuredTraceLoggerNanos / NANOS_PER_MILLISECOND),
                format(Math.max(0.0D, currentMspt - measuredNpcMs
                        - measuredForceManagerNanos / NANOS_PER_MILLISECOND
                        - measuredTraceLoggerNanos / NANOS_PER_MILLISECOND))
        );
        for (String traceLine : summary.traceLines()) {
            SmartNpc.LOGGER.warn("Smart NPC TPS trace: {}", traceLine);
        }
    }

    private static NpcTraceSummary collectNpcTrace(MinecraftServer server) {
        int totalNpcCount = 0;
        int activeNpcCount = 0;
        Map<String, Integer> stateCounts = new LinkedHashMap<>();
        List<String> traceLines = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
                    continue;
                }

                totalNpcCount++;
                String state = sanitize(playerNpc.getCurrentAiState());
                if (state.isBlank() || PlayerNpcEntity.AI_IDLE.equals(state)) {
                    continue;
                }
                if (!isPerformanceRelevantState(state)) {
                    continue;
                }

                activeNpcCount++;
                stateCounts.merge(state, 1, Integer::sum);
                if (traceLines.size() < MAX_WARNING_TRACE_LINES) {
                    traceLines.add(createTraceLine(level, playerNpc, state));
                }
            }
        }

        return new NpcTraceSummary(totalNpcCount, activeNpcCount, stateCounts, traceLines);
    }

    private static boolean isPerformanceRelevantState(String state) {
        return !PASSIVE_HOME_STATE.equals(state);
    }

    private static String createTraceLine(ServerLevel level, PlayerNpcEntity playerNpc, String state) {
        BlockPos pos = playerNpc.blockPosition();
        LivingEntity target = playerNpc.getTarget();
        String targetText = target == null
                ? "none"
                : target.getType().toShortString() + "#" + target.getId();
        String detail = sanitize(playerNpc.getCurrentAiDetail());
        if (detail.isBlank()) {
            detail = "none";
        }

        return String.format(
                Locale.ROOT,
                "%s#%d dim=%s pos=%d,%d,%d state=%s detail=\"%s\" target=%s navDone=%s navStuck=%s",
                sanitize(playerNpc.getDisplayName().getString()),
                playerNpc.getId(),
                level.dimension().identifier(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                state,
                detail,
                targetText,
                playerNpc.getNavigation().isDone(),
                playerNpc.getNavigation().isStuck()
        );
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record NpcTraceSummary(
            int totalNpcCount,
            int activeNpcCount,
            Map<String, Integer> stateCounts,
            List<String> traceLines
    ) {
        private String stateCountsText() {
            StringJoiner joiner = new StringJoiner(", ");
            this.stateCounts.forEach((state, count) -> joiner.add(state + "=" + count));
            String text = joiner.toString();
            return text.isBlank() ? "none" : text;
        }
    }
}
