package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedMobPatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.fml.ModList;
import net.shelmarow.combat_evolution.effect.CEMobEffects;
import net.shelmarow.combat_evolution.execution.ExecutionHandler;
import net.shelmarow.combat_evolution.execution.ExecutionTypeManager;
import net.shelmarow.combat_evolution.gameassets.animation.ExecutionHitAnimation;
import net.shelmarow.combat_evolution.tickTask.TickTask;
import net.shelmarow.combat_evolution.tickTask.TickTaskManager;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;

import javax.annotation.Nullable;
import java.util.Objects;

public class CombatEvolution {
    private static class MobExecutionTask extends TickTask {
        private final LivingEntity executor;
        private final LivingEntity target;
        private final ExecutionTypeManager.Type executionType;
        private AdvancedMobPatch<?> lockedExecutorPatch;
        private boolean cancelled = false;
        private boolean finished = false;

        public MobExecutionTask(LivingEntity executor, LivingEntity target,
                                ExecutionTypeManager.Type executionType, int durationTicks) {
            super(durationTicks);
            this.executor = executor;
            this.target = target;
            this.executionType = executionType;
        }

        @Override
        public void onStart() {
            ExecutionHandler.addExecutingTarget(target, executor);

            LivingEntityPatch<?> executorPatch = EpicFightCapabilities.getEntityPatch(executor, LivingEntityPatch.class);
            LivingEntityPatch<?> targetPatch = EpicFightCapabilities.getEntityPatch(target, LivingEntityPatch.class);

            if (executorPatch instanceof AdvancedMobPatch<?> advancedMobPatch) {
                this.lockedExecutorPatch = advancedMobPatch;
                advancedMobPatch.lockCombatActions(this);
            }

            executor.addEffect(new MobEffectInstance(CEMobEffects.FULL_STUN_IMMUNITY.get(), 100, 1, true, false));
            target.addEffect(new MobEffectInstance(CEMobEffects.FULL_STUN_IMMUNITY.get(), 100, 1, true, false));
            executor.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 4));

            if (executorPatch != null && targetPatch != null) {
                executorPatch.playAnimationSynchronized(executionType.executionAnimation(), 0.0F);
                targetPatch.playAnimationSynchronized(executionType.executedAnimation(), 0.0F);

                Vec3 from = executor.getEyePosition();
                Vec3 to = target.getEyePosition();
                double dx = to.x - from.x;
                double dz = to.z - from.z;
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F) + executionType.rotationOffset();
                executorPatch.setYRot(yaw);
            }
        }

        @Override
        public void onTick() {
            if (cancelled) {
                return;
            }

            if (target.isAlive()) {
                LivingEntityPatch<?> targetPatch = EpicFightCapabilities.getEntityPatch(target, LivingEntityPatch.class);

                if (targetPatch == null) {
                    cancelExecution(false);
                    return;
                }

                if (targetPatch.getAnimator().getPlayerFor(null) == null) {
                    cancelExecution(false);
                    return;
                }

                AssetAccessor<? extends StaticAnimation> targetDynamicAnimation =
                        Objects.requireNonNull(targetPatch.getAnimator().getPlayerFor(null)).getRealAnimation();

                if (!(targetDynamicAnimation.get() instanceof ExecutionHitAnimation)) {
                    cancelExecution(true);
                }
            }
        }

        private void cancelExecution(boolean rollExecutorBackward) {
            if (cancelled) {
                return;
            }
            cancelled = true;

            LivingEntityPatch<?> executorPatch = EpicFightCapabilities.getEntityPatch(executor, LivingEntityPatch.class);
            LivingEntityPatch<?> targetPatch = EpicFightCapabilities.getEntityPatch(target, LivingEntityPatch.class);

            if (executorPatch != null) {
                executorPatch.stopPlaying(executionType.executionAnimation());

                if (rollExecutorBackward) {
                    executorPatch.playAnimationInstantly(Animations.BIPED_ROLL_BACKWARD);
                }
            }

            if (targetPatch != null) {
                targetPatch.stopPlaying(executionType.executedAnimation());
            }

            onFinish();
            this.tickTimer = this.maxTime;
        }

        @Override
        public void onFinish() {
            if (this.finished) {
                return;
            }
            this.finished = true;
            ExecutionHandler.removeExecutingTarget(target);
            if (this.lockedExecutorPatch != null) {
                this.lockedExecutorPatch.unlockCombatActions(this);
                this.lockedExecutorPatch = null;
            }
        }
    }

    public static boolean isHoldingWeapon(LivingEntity entity) {
        CapabilityItem capabilityItem = EpicFightCapabilities.getItemStackCapability(entity.getItemInHand(InteractionHand.MAIN_HAND));
        return capabilityItem.getWeaponCategory() != CapabilityItem.WeaponCategories.NOT_WEAPON && capabilityItem.getWeaponCategory() != CapabilityItem.WeaponCategories.FIST;
    }

    public static boolean targetIsInRange(LivingEntity attacker, LivingEntity target, double minDist, double maxDist, double maxAngleDegrees) {
        Vec3 targetPos = target.position();
        Vec3 playerPos = attacker.position();

        double distance = playerPos.distanceTo(targetPos);
        if (distance < minDist || distance > maxDist) return false;

        float yaw = target.getYRot();
        double yawRad = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad)).normalize();
        Vec3 toPlayer = playerPos.subtract(targetPos).normalize();

        double dot = forward.dot(toPlayer);
        double angle = Math.toDegrees(Math.acos(dot));

        return angle <= maxAngleDegrees;
    }

    public static boolean canExecute(LivingEntity attacker, LivingEntity victim, LivingEntityPatch<?> attackerEntityPatch, LivingEntityPatch<?> victimEntityPatch) {
        float maxDist = ExecutionHandler.EXECUTION_DISTANCE;
        return attacker.isAlive() && victim.isAlive()
                && !ExecutionHandler.isExecutingTarget(attacker, victim)
                && ExecutionHandler.isTargetSupported(attackerEntityPatch, victimEntityPatch)
                && isHoldingWeapon(attacker)
                && targetIsInRange(attacker, victim, 0, maxDist, 180);
    }

    @Nullable
    private static ExecutionHandler.ExecutionTransform calculateExecutionPosition(Level level, LivingEntity executor, LivingEntity target, Vec3 offset) {
        float yaw = target.getYRot();
        ExecutionHandler.ExecutionTransform executionTransform = findPosAround(level, executor, target, offset, yaw, 360.0F, 0.5F);
        if (executionTransform == null) {
            Vec3 executorPos = executor.position();
            Vec3 targetPos = target.position();
            Vec3 deltaVec = executorPos.subtract(targetPos);
            float startAngle = (float) (Math.toDegrees(Mth.atan2(deltaVec.z, deltaVec.x)) - (double) 90.0F);
            float allowedY = 0.5F;
            executionTransform = findPosAround(level, executor, target, offset, startAngle, 12.0F, allowedY);
            if (executionTransform == null) {
                allowedY = 0.95F;
                executionTransform = findPosAround(level, executor, target, offset, startAngle, 12.0F, allowedY);
            }
        }

        return executionTransform;
    }

    @Nullable
    private static ExecutionHandler.ExecutionTransform findPosAround(Level level, LivingEntity executor, LivingEntity target, Vec3 offset, float startAngle, float angleStep, float allowedY) {
        for (float angleOffset = 0.0F; angleOffset < 360.0F; angleOffset += angleStep) {
            float yaw = startAngle + angleOffset;
            double rad = Math.toRadians(yaw);
            double forwardX = -Math.sin(rad);
            double forwardZ = Math.cos(rad);
            double rightX = Math.cos(rad);
            double rightZ = Math.sin(rad);
            double offsetX = forwardX * offset.x + rightX * offset.z;
            double offsetY = offset.y;
            double offsetZ = forwardZ * offset.x + rightZ * offset.z;
            Vec3 testPos = target.position().add(offsetX, offsetY, offsetZ);
            Vec3 executionPos = canStandHere(level, testPos, executor, target, allowedY);
            if (executionPos != null) {
                return new ExecutionHandler.ExecutionTransform(executionPos, yaw);
            }
        }

        return null;
    }

    @Nullable
    public static Vec3 canStandHere(Level level, Vec3 pos, LivingEntity executor, LivingEntity target, float allowedY) {
        AABB entityBox = executor.getBoundingBox();
        double width = entityBox.getXsize();
        double height = entityBox.getYsize();

        for (float i = allowedY; i > -allowedY; i -= 0.05F) {
            BlockPos blockPosBelow = BlockPos.containing(pos.x, pos.y + (double) i, pos.z);
            BlockState stateBelow = level.getBlockState(blockPosBelow);
            VoxelShape shapeBelow = stateBelow.getCollisionShape(level, blockPosBelow);
            if (!shapeBelow.isEmpty()) {
                double offsetY = shapeBelow.max(Direction.Axis.Y);
                AABB checkBox = new AABB(pos.x - width / (double) 2.0F, (double) blockPosBelow.getY() + offsetY, pos.z - width / (double) 2.0F, pos.x + width / (double) 2.0F, (double) blockPosBelow.getY() + offsetY + height, pos.z + width / (double) 2.0F);
                Vec3 standPos = new Vec3(pos.x, (double) blockPosBelow.getY() + offsetY, pos.z);
                if (level.noCollision(checkBox) && getEntityInView(executor, new Vec3(standPos.x, executor.getEyePosition().y, standPos.z), target) != null) {
                    return standPos;
                }
            }
        }

        return null;
    }

    private static LivingEntity getEntityInView(LivingEntity executor, Vec3 startPos, Entity target) {
        BlockHitResult blockHit = executor.level().clip(new ClipContext(startPos, target.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, executor));
        double blockDistanceSqr = blockHit.getType() != HitResult.Type.MISS ? startPos.distanceToSqr(blockHit.getLocation()) : Double.MAX_VALUE;
        double entityDistanceSqr = startPos.distanceToSqr(target.getEyePosition());
        return entityDistanceSqr < blockDistanceSqr && blockDistanceSqr - entityDistanceSqr > target.getBoundingBox().minX ? (LivingEntity) target : null;
    }

    public static boolean canExecute(MobPatch<?> mobPatch) {
        if (!ModList.get().isLoaded("combat_evolution")) return false;
        Mob attacker = mobPatch.getOriginal();
        LivingEntity victim = attacker.getTarget();
        if (victim == null || !victim.isAlive()) return false;

        LivingEntityPatch<?> victimEntityPatch = EpicFightCapabilities.getEntityPatch(victim, LivingEntityPatch.class);
        if (victimEntityPatch != null) {
            AssetAccessor<? extends StaticAnimation> currentAnimation =
                    Objects.requireNonNull(victimEntityPatch.getAnimator().getPlayerFor(null)).getRealAnimation();

            if (ExecutionHandler.isTargetGuardBreak(currentAnimation, victimEntityPatch) && canExecute(attacker, victim, mobPatch, victimEntityPatch)) {
                ExecutionTypeManager.Type executionType = ExecutionHandler.getExecutionType(mobPatch, victimEntityPatch);
                return calculateExecutionPosition(attacker.level(), attacker, victim, executionType.offset()) != null;
            }
        }
        return false;
    }

    public static void performExecute(MobPatch<?> mobPatch) {
        final Mob attacker = mobPatch.getOriginal();
        final LivingEntity victim = attacker.getTarget();
        if (victim == null) return;
        if (attacker.isPassenger()) attacker.stopRiding();

        final LivingEntityPatch<?> victimPatch = EpicFightCapabilities.getEntityPatch(victim, LivingEntityPatch.class);
        if (victimPatch == null) return;

        final ExecutionTypeManager.Type execType = ExecutionHandler.getExecutionType(mobPatch, victimPatch);
        faceTargetHard(attacker, victim);
        ExecutionHandler.ExecutionTransform transform = calculateExecutionPosition(attacker.level(), attacker, victim, execType.offset());
        if (transform != null) {
            Vec3 executionPos = transform.position();
            attacker.teleportTo(executionPos.x, executionPos.y, executionPos.z);
            faceTargetHard(attacker, victim);
            TickTaskManager.addTask(victim.getUUID(),
                    new MobExecutionTask(attacker, victim, execType, execType.totalTick()));
        }
    }

    private static void faceTargetHard(Mob self, LivingEntity target) {
        Vec3 from = self.getEyePosition(1.0F);
        Vec3 to = target.getEyePosition(1.0F);
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-6) horiz = 1.0E-6;

        float yaw = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dy, horiz) * (180F / Math.PI)));

        self.getNavigation().stop();
        self.setYRot(yaw);
        self.setXRot(pitch);
        self.setYBodyRot(yaw);
        self.setYHeadRot(yaw);
        self.yRotO = yaw;
        self.xRotO = pitch;
        self.yBodyRotO = yaw;
        self.yHeadRotO = yaw;
        self.getLookControl().setLookAt(target, 90.0F, 90.0F);
    }
}
