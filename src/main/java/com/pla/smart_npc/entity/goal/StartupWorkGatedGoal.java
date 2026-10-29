package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Defers routine work while an integrated/dedicated server is still restoring its world.
 * Combat, targeting, fleeing, healing, and emergency movement are intentionally not wrapped.
 */
public final class StartupWorkGatedGoal extends Goal {
    public static final int SERVER_STARTUP_GRACE_TICKS = 60;
    public static final int MAX_RELEASE_STAGGER_TICKS = 60;
    // GoalSelector evaluates new goals every three ticks. Eight slices prevent a non-holder's
    // one-tick startup probe from polling several expensive job predicates in the same selector
    // pass. Persistent holders bypass these admission slices; their per-goal scans/path starts
    // retain their own bounds and can run independently up to the configured worker limit.
    public static final int ROUTINE_PROBE_SLICES = 8;

    private final PlayerNpcEntity playerNpc;
    private final Goal delegate;
    private final int releaseServerTick;
    private final int predicateSlice;
    private boolean socialPaused;
    private Path socialPausedPath;
    private double socialPausedSpeed;

    public StartupWorkGatedGoal(PlayerNpcEntity playerNpc, Goal delegate, int registrationIndex) {
        this.playerNpc = playerNpc;
        this.delegate = delegate;
        this.releaseServerTick = SERVER_STARTUP_GRACE_TICKS
                + Math.floorMod(playerNpc.getUUID().hashCode(), MAX_RELEASE_STAGGER_TICKS + 1);
        this.predicateSlice = Math.floorMod(registrationIndex, ROUTINE_PROBE_SLICES);
        this.setFlags(delegate.getFlags());
    }

    public Goal getDelegateGoal() {
        return this.delegate;
    }

    private boolean isStartupGraceComplete() {
        MinecraftServer server = this.playerNpc.level().getServer();
        return server != null && server.getTickCount() > this.releaseServerTick;
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.getSocialNoticeAi().isActive()) {
            return false;
        }
        if ((PlayerNpcTeamUpManager.shouldSuspendRoutineWork(this.playerNpc)
                || this.playerNpc.isTeamUpRequestPending())
                || !this.isStartupGraceComplete()
                || !PlayerNpcAiWorkBudget.canStartWork(
                this.playerNpc,
                this.predicateSlice,
                ROUTINE_PROBE_SLICES
        )) {
            return false;
        }
        // Advance from actual resource-owned selector opportunities, not absolute entity time.
        // GoalSelector's new-goal cadence plus the server-wide probe queue can repeatedly grant an
        // NPC only a subset of tickCount modulo phases, starving an absolute-time slice forever.
        if (this.delegate instanceof InterestGatedGoal interestGoal
                && !interestGoal.isInterestGateActive()) {
            return false;
        }
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            return this.delegate.canUse();
        } finally {
            this.recordGoalWork("canUse", timing);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if ((PlayerNpcTeamUpManager.shouldSuspendRoutineWork(this.playerNpc)
                || this.playerNpc.isTeamUpRequestPending())
                || !this.isStartupGraceComplete()
                || !PlayerNpcAiWorkBudget.canContinueWork(this.playerNpc)) {
            return false;
        }
        if (this.shouldPauseForNotice()) {
            // Navigation has been suspended; do not mistake that for a completed/failed route.
            // Actual safety owners remain unwrapped and can preempt this retained goal normally.
            return this.playerNpc.isAlive() && !this.playerNpc.isNoAi() && this.playerNpc.getTarget() == null
                    && !this.playerNpc.isHealing() && !this.playerNpc.isSleeping();
        }
        this.resumeSocialWork();
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            return this.delegate.canContinueToUse();
        } finally {
            this.recordGoalWork("canContinue", timing);
        }
    }

    @Override
    public boolean isInterruptable() {
        return this.delegate.isInterruptable();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return this.delegate.requiresUpdateEveryTick();
    }

    @Override
    public void start() {
        if (PlayerNpcTeamUpManager.shouldSuspendRoutineWork(this.playerNpc)
                || this.playerNpc.isTeamUpRequestPending()) {
            return;
        }
        PlayerNpcAiWorkBudget.onWorkStarted(this.playerNpc);
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.start();
        } finally {
            this.recordGoalWork("start", timing);
        }
    }

    @Override
    public void stop() {
        this.socialPaused = false;
        this.socialPausedPath = null;
        this.playerNpc.setSocialNoticePausesWork(false);
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.stop();
        } finally {
            this.recordGoalWork("stop", timing);
            PlayerNpcAiWorkBudget.onWorkStopped(this.playerNpc);
        }
    }

    @Override
    public void tick() {
        // Membership can change after GoalSelector's continuation check. Do not let a stale
        // running wrapper perform another job action while its follower/pending gate is closed.
        if (PlayerNpcTeamUpManager.shouldSuspendRoutineWork(this.playerNpc)
                || this.playerNpc.isTeamUpRequestPending()) {
            return;
        }
        if (this.shouldPauseForNotice()) {
            if (!this.socialPaused) {
                this.socialPaused = true;
                this.socialPausedPath = this.playerNpc.getNavigation().getPath();
                this.socialPausedSpeed = Math.max(0.1D, Math.min(1.0D, this.playerNpc.getMoveControl().getSpeedModifier()));
            }
            this.playerNpc.getNavigation().stop();
            this.playerNpc.getMoveControl().setWantedPosition(this.playerNpc.getX(), this.playerNpc.getY(), this.playerNpc.getZ(), 0.0D);
            this.playerNpc.getSocialNoticeAi().pauseWorkAnimation();
            return;
        }
        this.resumeSocialWork();
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.tick();
        } finally {
            this.recordGoalWork("tick", timing);
        }
    }

    private void recordGoalWork(String operation, long timing) {
        // InterestGatedGoal already measures its delegate. Recording this outer wrapper as well
        // would double-count the same elapsed time in wrappedGoalTotalMs/calls.
        if (this.delegate instanceof InterestGatedGoal) {
            return;
        }
        PlayerNpcPerformanceMonitor.recordGoalWork(
                this.playerNpc,
                this.delegate.getClass().getSimpleName() + "." + operation,
                timing
        );
    }

    private boolean shouldPauseForNotice() {
        Goal goal = this.delegate;
        while (goal instanceof InterestGatedGoal interest) {
            goal = interest.getDelegateGoal();
        }
        return this.playerNpc.getSocialNoticeAi().isActive() && this.playerNpc.canSociallyNotice()
                && !(goal instanceof PlayerNpcFishingGoal) && !(goal instanceof ExploreAroundGoal);
    }

    private void resumeSocialWork() {
        if (!this.socialPaused) {
            return;
        }
        this.socialPaused = false;
        this.playerNpc.setSocialNoticePausesWork(false);
        if (this.socialPausedPath != null && !this.socialPausedPath.isDone()
                && this.playerNpc.getTarget() == null && !this.playerNpc.isHealing()) {
            this.playerNpc.getNavigation().moveTo(this.socialPausedPath, this.socialPausedSpeed);
        }
        this.socialPausedPath = null;
    }
}
