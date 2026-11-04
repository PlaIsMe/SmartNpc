package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Arrays;
import java.util.List;

public class InterestGatedGoal extends Goal {
    private final PlayerNpcEntity playerNpc;
    private final Goal delegate;
    private final List<PlayerNpcInterest> interests;

    public InterestGatedGoal(PlayerNpcEntity playerNpc, Goal delegate, PlayerNpcInterest... interests) {
        this.playerNpc = playerNpc;
        this.delegate = delegate;
        this.interests = List.copyOf(Arrays.asList(interests));
        this.setFlags(delegate.getFlags());
    }

    public Goal getDelegateGoal() {
        return this.delegate;
    }

    /** Cheap eligibility preflight used before requesting a routine-worker probe. */
    public boolean isInterestGateActive() {
        return this.playerNpc.isInterestGateActive(this.interests);
    }

    @Override
    public boolean canUse() {
        if (!this.isInterestGateActive()) {
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
        if (!this.isInterestGateActive()) {
            return false;
        }
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
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.start();
        } finally {
            this.recordGoalWork("start", timing);
        }
    }

    @Override
    public void stop() {
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.stop();
        } finally {
            this.recordGoalWork("stop", timing);
        }
    }

    @Override
    public void tick() {
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.tick();
        } finally {
            this.recordGoalWork("tick", timing);
        }
    }

    private void recordGoalWork(String operation, long timing) {
        PlayerNpcPerformanceMonitor.recordGoalWork(
                this.playerNpc,
                this.delegate.getClass().getSimpleName() + "." + operation,
                timing
        );
    }
}
