package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Comparator;
import java.util.EnumSet;

public class TrollHitGoal extends Goal {
    private static final double SEARCH_RADIUS = 12.0D;
    private static final double ATTACK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double GIVE_UP_DISTANCE_SQR = 18.0D * 18.0D;
    private static final double RUN_SPEED = 1.0D;
    private static final int MIN_RUN_TICKS = 55;
    private static final int MAX_RUN_TICKS = 95;
    private static final int PATH_RECALCULATE_TICKS = 10;
    private static final int COOLDOWN_TICKS = 20 * 65;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;

    private final PlayerNpcEntity playerNpc;
    private LivingEntity victim;
    private Vec3 fleePos;
    private int runTicks;
    private int pathRecalculateTicks;
    private int nextWorldScanTick;
    private boolean hitOnce;

    public TrollHitGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTrollHitCooldown() > 0) {
            return false;
        }

        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (this.isValidVictim(currentTarget)
                && this.playerNpc.getRandom().nextFloat() < 0.18F) {
            this.victim = currentTarget;
            return true;
        }
        if (currentTarget != null) {
            return false;
        }
        if (this.playerNpc.tickCount < this.nextWorldScanTick) {
            return false;
        }
        this.nextWorldScanTick = this.playerNpc.tickCount + 40;
        if (this.playerNpc.getRandom().nextFloat() > 0.035F) {
            return false;
        }

        this.victim = this.findVictim();
        return this.victim != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.playerNpc.isAlive() || this.playerNpc.isNoAi() || this.playerNpc.isPassenger()) {
            return false;
        }
        if (this.hitOnce) {
            return this.runTicks > 0;
        }
        return this.isValidVictim(this.victim)
                && this.playerNpc.distanceToSqr(this.victim) <= GIVE_UP_DISTANCE_SQR;
    }

    @Override
    public void start() {
        this.hitOnce = false;
        this.fleePos = null;
        this.runTicks = 0;
        this.pathRecalculateTicks = 0;
        if (this.victim != null) {
            this.playerNpc.setSprinting(true);
            this.playerNpc.setTarget(this.victim);
            this.playerNpc.setCurrentAiState("ai.player_npc.troll_hit");
            this.playerNpc.setCurrentAiDetail(this.victim.getDisplayName().getString());
            this.moveAlongBoundedPath(this.victim.blockPosition());
            this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
        }
    }

    @Override
    public void tick() {
        if (!this.hitOnce) {
            this.tickApproachAndHit();
            return;
        }

        this.runTicks--;
        this.playerNpc.setTarget(null);
        if (this.pathRecalculateTicks-- <= 0) {
            this.fleePos = this.findFleePos();
            this.moveToFleePos();
            this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
        }
        if (this.fleePos != null) {
            this.playerNpc.getLookControl().setLookAt(this.fleePos.x, this.fleePos.y, this.fleePos.z, 45.0F, 45.0F);
        }
    }

    @Override
    public void stop() {
        this.playerNpc.setSprinting(false);
        if (this.victim != null && this.playerNpc.getTarget() == this.victim) {
            this.playerNpc.setTarget(null);
        }
        this.victim = null;
        this.fleePos = null;
        this.runTicks = 0;
        this.pathRecalculateTicks = 0;
        this.hitOnce = false;
        this.playerNpc.setTrollHitCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 90));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void tickApproachAndHit() {
        if (!this.isValidVictim(this.victim)) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.victim, 60.0F, 60.0F);
        if (this.playerNpc.distanceToSqr(this.victim) > ATTACK_DISTANCE_SQR) {
            if (this.pathRecalculateTicks-- <= 0) {
                this.moveAlongBoundedPath(this.victim.blockPosition());
                this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
            }
            return;
        }

        this.playerNpc.doHurtTarget(this.victim);
        this.hitOnce = true;
        this.runTicks = MIN_RUN_TICKS + this.playerNpc.getRandom().nextInt(MAX_RUN_TICKS - MIN_RUN_TICKS + 1);
        this.playerNpc.setTarget(null);
        this.playerNpc.setCurrentAiState("ai.player_npc.troll_running");
        this.fleePos = this.findFleePos();
        this.moveToFleePos();
        this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
    }

    private LivingEntity findVictim() {
        AABB searchBox = this.playerNpc.getBoundingBox().inflate(SEARCH_RADIUS, 4.0D, SEARCH_RADIUS);
        return this.playerNpc.level()
                .getEntitiesOfClass(LivingEntity.class, searchBox, this::isValidVictim)
                .stream()
                .min(Comparator.comparingDouble(this.playerNpc::distanceToSqr))
                .orElse(null);
    }

    private boolean isValidVictim(LivingEntity entity) {
        return entity != null
                && entity != this.playerNpc
                && entity.isAlive()
                && !entity.isSpectator()
                && !this.playerNpc.isAlliedTo(entity)
                && !entity.isAlliedTo(this.playerNpc)
                && !this.playerNpc.shouldSmartNpcAvoidTrollHitTarget(entity)
                && this.isAllowedVictimType(entity)
                && (!(entity instanceof Player player) || !player.isCreative());
    }

    private boolean isAllowedVictimType(LivingEntity entity) {
        return entity instanceof Monster
                || entity instanceof AbstractGolem
                || entity instanceof AbstractVillager
                || entity instanceof Player
                || entity instanceof PlayerNpcEntity
                || this.playerNpc.isSmartNpcCompatMonsterTarget(entity)
                || this.playerNpc.isSmartNpcCompatVillagerTarget(entity)
                || this.playerNpc.isSmartNpcCompatPlayerLikeTarget(entity);
    }

    private Vec3 findFleePos() {
        Vec3 awayFrom = this.victim == null ? this.playerNpc.position().subtract(this.playerNpc.getForward()) : this.victim.position();
        Vec3 pos = DefaultRandomPos.getPosAway(this.playerNpc, 14, 6, awayFrom);
        if (pos != null) {
            return pos;
        }

        Vec3 away = this.playerNpc.position().subtract(awayFrom);
        if (away.lengthSqr() < 1.0E-4D) {
            away = Vec3.directionFromRotation(0.0F, this.playerNpc.getYRot());
        }
        return this.playerNpc.position().add(away.normalize().scale(12.0D));
    }

    private void moveToFleePos() {
        if (this.fleePos != null) {
            this.moveAlongBoundedPath(BlockPos.containing(this.fleePos.x, this.fleePos.y, this.fleePos.z));
        }
    }

    private void moveAlongBoundedPath(BlockPos targetPos) {
        Path path = PathNavigationAi.createBoundedPath(
                this.playerNpc,
                targetPos,
                PATH_NODE_MULTIPLIER
        );
        if (path != null && path.getNodeCount() > 0) {
            this.playerNpc.getNavigation().moveTo(path, RUN_SPEED);
        }
    }
}
