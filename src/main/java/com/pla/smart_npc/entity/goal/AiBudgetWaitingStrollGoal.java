package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.level.pathfinder.Path;

public final class AiBudgetWaitingStrollGoal extends WaterAvoidingRandomStrollGoal {
    private static final int TRACE_DETAIL_TICKS = 20 * 10;
    private static final float STROLL_PATH_NODE_MULTIPLIER = 0.15F;
    // createBoundedPath restores Navigation's normal multiplier after selecting the short stroll.
    // Keep the lower budget installed while that optional route is followed so a terrain change
    // cannot make PathfinderMob.super.tick rebuild the stroll at the full 48-block budget.
    private static final float ACTIVE_STROLL_PATH_NODE_MULTIPLIER = 0.05F;

    private final PlayerNpcEntity playerNpc;
    private Path activeStrollPath;
    private BlockPos activeStrollDestination;

    public AiBudgetWaitingStrollGoal(PlayerNpcEntity playerNpc, double speed) {
        super(playerNpc, speed);
        this.playerNpc = playerNpc;
    }

    @Override
    public boolean canUse() {
        if (!this.canWaitStroll()) {
            return false;
        }

        long timingStartNanos = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        boolean vanillaSelectedTarget;
        try {
            vanillaSelectedTarget = super.canUse();
        } finally {
            PlayerNpcPerformanceMonitor.recordWaitingStrollWork(timingStartNanos);
        }
        return vanillaSelectedTarget
                && PlayerNpcAiWorkBudget.tryAcquireWaitingStrollPathStart(this.playerNpc);
    }

    @Override
    public boolean canContinueToUse() {
        Path currentPath = this.playerNpc.getNavigation().getPath();
        BlockPos currentDestination = this.playerNpc.getNavigation().getTargetPos();
        boolean ownsCurrentNavigation = this.activeStrollPath != null
                && (currentPath == this.activeStrollPath
                || this.activeStrollDestination != null
                && this.activeStrollDestination.equals(currentDestination));
        return ownsCurrentNavigation
                && this.canContinueStroll()
                && super.canContinueToUse();
    }

    @Override
    public void start() {
        this.activeStrollPath = null;
        this.activeStrollDestination = null;
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.playerNpc.getNavigation().stop();
            return;
        }
        BlockPos destination = BlockPos.containing(this.wantedX, this.wantedY, this.wantedZ);
        if (!serverLevel.hasChunkAt(destination)) {
            this.playerNpc.getNavigation().stop();
            return;
        }

        long timingStartNanos = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        Path path;
        try {
            path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    destination,
                    STROLL_PATH_NODE_MULTIPLIER
            );
        } finally {
            PlayerNpcPerformanceMonitor.recordWaitingStrollWork(timingStartNanos);
        }
        if (path == null || path.getNodeCount() <= 0 || path.isDone()
                || !this.playerNpc.getNavigation().moveTo(path, this.speedModifier)) {
            // A failed bounded probe must not let this goal inherit a stale job path.
            this.playerNpc.getNavigation().stop();
            return;
        }
        this.activeStrollPath = this.playerNpc.getNavigation().getPath();
        this.activeStrollDestination = destination.immutable();
        this.playerNpc.getNavigation().setMaxVisitedNodesMultiplier(ACTIVE_STROLL_PATH_NODE_MULTIPLIER);

        this.playerNpc.setIdleTraceDetail(
                "waiting for AI worker turn; water-avoiding random stroll",
                TRACE_DETAIL_TICKS
        );
    }

    @Override
    public void stop() {
        // This is deliberately an optional visual route. Do not leave it in PathNavigation after
        // the NPC earns a worker turn or loses its waiting status: otherwise vanilla continues
        // ticking/recomputing a stale random path under Mob.super.tick().
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getNavigation().resetMaxVisitedNodesMultiplier();
        super.stop();
        this.activeStrollPath = null;
        this.activeStrollDestination = null;
    }

    private boolean canWaitStroll() {
        return this.playerNpc.level() instanceof ServerLevel
                && !this.playerNpc.getSocialNoticeAi().isActive()
                && !PlayerNpcTeamUpManager.shouldSuspendRoutineWork(this.playerNpc)
                && !this.playerNpc.isTeamUpRequestPending()
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && (this.playerNpc.onGround() || this.playerNpc.isInWater())
                && PlayerNpcPerformanceMonitor.isOptionalAiWorkAllowed()
                && PlayerNpcAiWorkBudget.isWaitingForTurn(this.playerNpc);
    }

    private boolean canContinueStroll() {
        return this.playerNpc.level() instanceof ServerLevel
                && !this.playerNpc.getSocialNoticeAi().isActive()
                && !PlayerNpcTeamUpManager.shouldSuspendRoutineWork(this.playerNpc)
                && !this.playerNpc.isTeamUpRequestPending()
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && PlayerNpcPerformanceMonitor.isOptionalAiWorkAllowed()
                // A provisional probe is already this NPC's worker opportunity. Release MOVE as
                // soon as it stops being a true waiter so retained canUse searches can complete
                // and promote the probe to an active worker instead of being displaced by the
                // decorative stroll that began on an earlier denied tick.
                && PlayerNpcAiWorkBudget.isWaitingForTurn(this.playerNpc);
    }
}
