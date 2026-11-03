package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.CautiousThreatAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

public class CautiousAvoidThreatGoal extends AvoidEntityGoal<LivingEntity> {
    private static final float START_DISTANCE = 14.0F;
    private static final double STOP_DISTANCE = 20.0D;
    private static final double WALK_SPEED = 1.15D;
    private static final double SPRINT_SPEED = 1.35D;
    private static final int PATH_HORIZONTAL_RANGE = 16;
    private static final int PATH_VERTICAL_RANGE = 7;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();

    public CautiousAvoidThreatGoal(PlayerNpcEntity playerNpc) {
        super(
                playerNpc,
                LivingEntity.class,
                START_DISTANCE,
                WALK_SPEED,
                SPRINT_SPEED,
                candidate -> CautiousThreatAi.isValidThreat(playerNpc, candidate)
        );
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.canMove() || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.toAvoid = CautiousThreatAi.findNearestThreat(this.playerNpc, START_DISTANCE);
        if (this.toAvoid == null) {
            return false;
        }

        this.path = this.findEscapePath(this.toAvoid);
        if (this.path == null) {
            this.toAvoid = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canMove()
                && CautiousThreatAi.isValidThreat(this.playerNpc, this.toAvoid)
                && !this.pathNav.isDone()
                && this.playerNpc.distanceToSqr(this.toAvoid) < STOP_DISTANCE * STOP_DISTANCE;
    }

    @Override
    public void start() {
        this.playerNpc.setTarget(null);
        this.playerNpc.setSprinting(true);
        this.playerNpc.setCurrentAiState("ai.player_npc.cautious_avoiding");
        this.updateAiDetail();
        super.start();
    }

    @Override
    public void tick() {
        this.playerNpc.setTarget(null);
        if (!CautiousThreatAi.isValidThreat(this.playerNpc, this.toAvoid)) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.toAvoid, 45.0F, 45.0F);
        // Match vanilla AvoidEntityGoal while active: speed/look updates are cheap, and the
        // path/entity search remains an activation-only decision behind CanUseThrottle.
        super.tick();
        this.playerNpc.setSprinting(true);
    }

    @Override
    public void stop() {
        this.pathNav.stop();
        this.playerNpc.setSprinting(false);
        this.playerNpc.setTarget(null);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.path = null;
        super.stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean canMove() {
        return !this.playerNpc.level().isClientSide()
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger();
    }

    @Nullable
    private Path findEscapePath(LivingEntity avoid) {
        double currentDistanceSqr = this.playerNpc.distanceToSqr(avoid);
        Vec3 escapePos = DefaultRandomPos.getPosAway(
                this.playerNpc,
                PATH_HORIZONTAL_RANGE,
                PATH_VERTICAL_RANGE,
                avoid.position()
        );
        if (escapePos == null || escapePos.distanceToSqr(avoid.position()) <= currentDistanceSqr) {
            return null;
        }

        // Vanilla AvoidEntityGoal creates one path for its one selected away position. Avoid
        // turning one cautious activation into a batch of four server-thread path searches.
        return PathNavigationAi.createBoundedPath(
                this.playerNpc,
                BlockPos.containing(escapePos),
                PATH_NODE_MULTIPLIER
        );
    }

    private void updateAiDetail() {
        if (this.toAvoid != null) {
            this.playerNpc.setCurrentAiDetail("avoiding: " + this.toAvoid.getDisplayName().getString());
        }
    }
}
