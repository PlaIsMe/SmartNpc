package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared, paced melee pursuit; animation and goal owners decide when movement is allowed. */
public final class CombatMovementAi {
    /** Implemented only by goals that currently own melee pursuit, while movement is allowed. */
    public interface PursuitGoal {
        boolean canSupportCombatFishing();
    }

    private final Mob mob;
    private int nextPathTick;
    private int nextStepTick;
    private int stepUntilTick;
    private BlockPos lastPathTarget;
    private Path retainedPath;
    private int nextSprintJumpTick;

    public CombatMovementAi(Mob mob) {
        this.mob = mob;
    }

    public void tick(LivingEntity target, double attackRange, double speed, boolean movementAllowed) {
        this.tick(target, attackRange, speed, movementAllowed, true);
    }

    public void tick(LivingEntity target, double attackRange, double speed, boolean movementAllowed, boolean sprintAllowed) {
        if (target == null || !target.isAlive()) {
            this.stop();
            return;
        }
        if (!movementAllowed) {
            this.stopSprint();
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
        this.tickSprint(!inReach && sprintAllowed && !this.mob.isUsingItem()
                && !this.mob.isShiftKeyDown() && !this.mob.isCrouching()
                && !this.mob.isInWater() && !this.mob.isInLava()
                && !this.mob.onClimbable());
        this.trySprintJump();
        if (this.mob instanceof PlayerNpcEntity npc && this.mob.level() instanceof ServerLevel
                && this.mob.isInWater() && !inReach) {
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
        this.stopSprint();
        if (this.mob instanceof PlayerNpcEntity npc) {
            npc.getWaterTravelAi().release(this);
        }
        this.mob.getNavigation().stop();
        this.lastPathTarget = null;
        this.retainedPath = null;
        this.stepUntilTick = 0;
    }

    private void tickSprint(boolean allowed) {
        if (!(this.mob instanceof PlayerNpcEntity)) {
            return;
        }
        if (this.mob.isSprinting() != allowed) {
            this.mob.setSprinting(allowed);
        }
    }

    private void trySprintJump() {
        if (!(this.mob instanceof PlayerNpcEntity) || !this.mob.isSprinting()
                || !this.mob.onGround() || this.mob.getDeltaMovement().y > 0.0D
                || this.mob.tickCount < this.nextSprintJumpTick) {
            return;
        }
        // One bounded attempt every 2-4 seconds; preemption never resets this deadline.
        this.nextSprintJumpTick = this.mob.tickCount + 40 + this.mob.getRandom().nextInt(41);
        Path path = this.mob.getNavigation().getPath();
        if (this.mob.getRandom().nextFloat() >= 0.35F || path == null || path.isDone()
                || this.mob.getNavigation().isDone() || this.mob.horizontalCollision
                || this.mob.hasEffect(MobEffects.BLINDNESS) || this.mob.hasEffect(MobEffects.LEVITATION)
                || this.mob.hasEffect(MobEffects.JUMP_BOOST) || this.mob.hasEffect(MobEffects.SLOW_FALLING)) {
            return;
        }
        Vec3 motion = this.mob.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
        Vec3 next = path.getNextEntityPos(this.mob).subtract(this.mob.position());
        Vec3 forward = Vec3.directionFromRotation(0.0F, this.mob.getYRot());
        if (motion.lengthSqr() < 0.0025D || next.horizontalDistanceSqr() < 0.01D
                || Math.abs(next.y) > 0.5D
                || forward.dot(motion.normalize()) < 0.9D
                || forward.dot(next.multiply(1.0D, 0.0D, 1.0D).normalize()) < 0.9D) {
            return;
        }
        // Conservative normal-jump lookahead, capped at ten blocks; skip unusually fast motion.
        double clearanceDistance = Math.max(3.0D, motion.length() * 12.0D + 1.5D);
        if (clearanceDistance > 10.0D) {
            return;
        }
        // Flat, loaded ground ahead only. Ordinary navigation owns obstacle/stair jumps.
        int groundSamples = (int) Math.ceil(clearanceDistance * 2.0D);
        AABB corridor = this.mob.getBoundingBox().expandTowards(forward.scale(groundSamples * 0.5D))
                .expandTowards(0.0D, 1.25D, 0.0D);
        if (!this.mob.level().hasChunkAt(BlockPos.containing(corridor.minX, corridor.minY, corridor.minZ))
                || !this.mob.level().hasChunkAt(BlockPos.containing(corridor.minX, corridor.minY, corridor.maxZ))
                || !this.mob.level().hasChunkAt(BlockPos.containing(corridor.maxX, corridor.minY, corridor.minZ))
                || !this.mob.level().hasChunkAt(BlockPos.containing(corridor.maxX, corridor.minY, corridor.maxZ))) {
            return;
        }
        for (int step = 1; step <= groundSamples; step++) {
            Vec3 sample = this.mob.position().add(forward.scale(step * 0.5D));
            BlockPos floor = BlockPos.containing(sample.x, sample.y - 0.1D, sample.z);
            if (!this.mob.level().getBlockState(floor).isFaceSturdy(this.mob.level(), floor, Direction.UP)
                    || !this.mob.level().getFluidState(floor).isEmpty()) {
                return;
            }
        }
        if (this.mob.level().noCollision(this.mob, corridor)) {
            // Vanilla jumping supplies its normal sprint impulse; no added velocity or speed multiplier.
            this.mob.getJumpControl().jump();
        }
    }

    private void stopSprint() {
        if (this.mob instanceof PlayerNpcEntity) {
            if (this.mob.isSprinting()) {
                this.mob.setSprinting(false);
            }
        }
    }
}
