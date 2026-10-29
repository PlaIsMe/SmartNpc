package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;

/** Shared, paced melee pursuit; animation and goal owners decide when movement is allowed. */
public final class CombatMovementAi {
    private final Mob mob;
    private int nextPathTick;
    private int nextStepTick;
    private int stepUntilTick;
    private BlockPos lastPathTarget;
    private Path retainedPath;

    public CombatMovementAi(Mob mob) {
        this.mob = mob;
    }

    public void tick(LivingEntity target, double attackRange, double speed, boolean movementAllowed) {
        if (target == null || !target.isAlive()) {
            this.stop();
            return;
        }
        if (!movementAllowed) {
            if (this.mob instanceof PlayerNpcEntity npc) {
                npc.getWaterTravelAi().release(this);
            }
            this.mob.getNavigation().stop();
            this.stepUntilTick = 0;
            return;
        }

        double distanceSqr = this.mob.distanceToSqr(target);
        double separation = (this.mob.getBbWidth() + target.getBbWidth()) * 0.5D + 0.25D;
        boolean inReach = distanceSqr <= attackRange * attackRange && this.mob.getSensing().hasLineOfSight(target);
        if (this.mob instanceof PlayerNpcEntity npc && this.mob.level() instanceof ServerLevel
                && this.mob.isInWaterOrBubble() && !inReach) {
            npc.getWaterTravelAi().request(this, target.blockPosition(), speed, true);
        } else if (this.mob instanceof PlayerNpcEntity npc) {
            npc.getWaterTravelAi().release(this);
        }
        if (inReach && distanceSqr <= separation * separation) {
            this.stop();
            return;
        }
        if (inReach) {
            // Wait for a path opportunity before choosing a brief forward step, so a
            // throttled path cannot consume the whole step window without any movement.
            if (this.mob.tickCount >= this.nextStepTick
                    && (!this.mob.getNavigation().isDone() || this.mob.tickCount >= this.nextPathTick)) {
                this.nextStepTick = this.mob.tickCount + 24 + this.mob.getRandom().nextInt(17);
                if (this.mob.getRandom().nextFloat() < 0.70F) {
                    this.stepUntilTick = this.mob.tickCount + 8 + this.mob.getRandom().nextInt(6);
                }
            }
            if (this.mob.tickCount >= this.stepUntilTick) {
                this.mob.getNavigation().stop();
                return;
            }
        }

        BlockPos targetPos = target.blockPosition();
        if (this.mob.getNavigation().isDone() && this.retainedPath != null && !this.retainedPath.isDone()
                && this.lastPathTarget != null && this.lastPathTarget.distSqr(targetPos) <= 1.0D
                && (!(this.mob instanceof PlayerNpcEntity npc) || PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(npc))) {
            // An animation pause may resume the existing route without a new path search.
            this.mob.getNavigation().moveTo(this.retainedPath, Math.min(1.0D, speed));
            return;
        }
        // Out-of-range pursuit never depends on a random step or the attack cooldown.
        // Preserve this deadline on stop/restart, including animation movement locks.
        if (this.mob.tickCount < this.nextPathTick) {
            return;
        }
        this.nextPathTick = this.mob.tickCount + 20 + this.mob.getRandom().nextInt(6);
        if (!this.mob.getNavigation().isDone() && this.lastPathTarget != null && this.lastPathTarget.distSqr(targetPos) <= 1.0D) {
            return;
        }
        Path path;
        if (this.mob instanceof PlayerNpcEntity npc) {
            if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(npc)) {
                return;
            }
            path = PathNavigationAi.createBoundedPath(npc, targetPos, 0.15F);
        } else {
            path = this.mob.getNavigation().createPath(targetPos, 0);
        }
        if (path != null) {
            this.retainedPath = path;
            this.lastPathTarget = targetPos.immutable();
            this.mob.getNavigation().moveTo(path, Math.min(1.0D, speed));
        } else {
            this.retainedPath = null;
            this.mob.getNavigation().stop();
        }
    }

    public void stop() {
        if (this.mob instanceof PlayerNpcEntity npc) {
            npc.getWaterTravelAi().release(this);
        }
        this.mob.getNavigation().stop();
        this.lastPathTarget = null;
        this.retainedPath = null;
        this.stepUntilTick = 0;
    }
}
