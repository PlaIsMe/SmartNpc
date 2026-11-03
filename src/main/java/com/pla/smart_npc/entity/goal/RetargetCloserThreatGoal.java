package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

public class RetargetCloserThreatGoal extends TargetGoal {
    private static final int DEFAULT_SCAN_INTERVAL = 5;
    private static final double SWITCH_MARGIN_SQR = 0.25D;

    private final int scanInterval;
    private final TargetingConditions targetConditions;
    private int nextScanTick;
    @Nullable
    private LivingEntity nextTarget;

    public RetargetCloserThreatGoal(Mob mob) {
        this(mob, DEFAULT_SCAN_INTERVAL);
    }

    public RetargetCloserThreatGoal(Mob mob, int scanInterval) {
        super(mob, true, false);
        this.scanInterval = Math.max(1, scanInterval);
        this.targetConditions = TargetingConditions.forCombat().range(this.getFollowDistance());
        // NPCs commonly load or spawn as a group. Keep their full follow-distance entity queries
        // off the same selector tick; the fixed recurring cadence preserves this initial phase.
        this.nextScanTick = mob.tickCount + mob.getRandom().nextInt(Math.max(20, this.scanInterval));
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.mob instanceof PlayerNpcEntity npc && npc.isClearingCombatObstruction()) {
            return false;
        }
        if (this.mob.level().isClientSide() || this.mob.tickCount < this.nextScanTick) {
            return false;
        }
        this.nextScanTick = this.mob.tickCount + Math.max(20, this.scanInterval);

        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.nextTarget = this.findCloserThreatTarget();
            return this.nextTarget != null;
        } finally {
            if (this.mob instanceof PlayerNpcEntity playerNpc) {
                PlayerNpcPerformanceMonitor.recordGoalWork(
                        playerNpc,
                        this.getClass().getSimpleName() + ".canUse",
                        timing
                );
            }
        }
    }

    @Override
    public void start() {
        this.mob.setTarget(this.nextTarget);
        super.start();
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void stop() {
        this.nextTarget = null;
    }

    @Nullable
    private LivingEntity findCloserThreatTarget() {
        LivingEntity currentTarget = this.mob.getTarget();
        double currentDistanceSqr = currentTarget != null && currentTarget.isAlive()
                ? this.mob.distanceToSqr(currentTarget)
                : Double.MAX_VALUE;
        double followDistance = this.getFollowDistance();
        AABB searchBox = this.mob.getBoundingBox().inflate(followDistance, 4.0D, followDistance);
        LivingEntity bestTarget = null;
        double bestDistanceSqr = currentDistanceSqr;

        for (Mob candidate : this.mob.level().getEntitiesOfClass(Mob.class, searchBox, this::isThreatTargetingMob)) {
            double candidateDistanceSqr = this.mob.distanceToSqr(candidate);
            if (candidateDistanceSqr + SWITCH_MARGIN_SQR >= bestDistanceSqr) {
                continue;
            }
            if (!this.canAttack(candidate, this.targetConditions)) {
                continue;
            }

            bestTarget = candidate;
            bestDistanceSqr = candidateDistanceSqr;
        }

        return bestTarget;
    }

    private boolean isThreatTargetingMob(Mob candidate) {
        return candidate != this.mob
                && candidate.isAlive()
                && !candidate.isSpectator()
                && candidate.getTarget() == this.mob
                && !this.mob.isAlliedTo(candidate)
                && !candidate.isAlliedTo(this.mob);
    }
}
