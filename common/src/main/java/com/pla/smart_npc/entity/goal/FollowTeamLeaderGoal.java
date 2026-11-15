package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/** Non-worker movement used only after a TEAMUP invitation has been accepted. */
public final class FollowTeamLeaderGoal extends Goal {
    private static final double START_DISTANCE_SQR = 5.0D * 5.0D;
    private static final double STOP_DISTANCE_SQR = 2.5D * 2.5D;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;

    private final PlayerNpcEntity follower;
    private final double speed;
    private LivingEntity leader;
    private int repathTicks;

    public FollowTeamLeaderGoal(PlayerNpcEntity follower, double speed) {
        this.follower = follower;
        this.speed = Math.min(1.1D, Math.max(0.1D, speed));
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        this.leader = PlayerNpcTeamUpManager.resolveNearbyLeader(this.follower);
        return this.canFollowLeader()
                && this.follower.distanceToSqr(this.leader) > START_DISTANCE_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canFollowLeader()
                && this.follower.distanceToSqr(this.leader) > STOP_DISTANCE_SQR;
    }

    @Override
    public void start() {
        // The first route must be immediate. The old UUID phase could leave a newly recruited
        // follower standing still for a full second before it even tried to follow.
        this.repathTicks = 0;
        this.follower.clearTeamFollowUpwardEscapeTarget();
        this.follower.setCurrentAiState("ai.player_npc.following_team_leader");
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!this.canFollowLeader()) {
            return;
        }
        this.follower.getLookControl().setLookAt(this.leader, 35.0F, 35.0F);
        if (this.repathTicks-- > 0) {
            return;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.follower)) {
            // Several newly recruited followers can request their first path on the same tick.
            // Retry admission on a short UUID-stagger without creating a path; a successful path
            // still keeps the required 20-tick creation cadence.
            this.repathTicks = 2 + Math.floorMod(this.follower.getUUID().hashCode(), 6);
            return;
        }
        this.repathTicks = REPATH_INTERVAL_TICKS;
        BlockPos target = this.leader.blockPosition();
        Path path = PathNavigationAi.createBoundedPath(this.follower, target, PATH_NODE_MULTIPLIER);
        // A moving/occupied leader position can produce a useful partial path. Following it is
        // better than standing still; the next bounded repath will advance toward the new pose.
        if (path != null && path.getNodeCount() > 0 && path.getEndNode() != null
                && !path.getEndNode().asBlockPos().equals(this.follower.blockPosition())) {
            this.follower.getNavigation().moveTo(path, this.speed);
        }
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.follower.getNavigation().stop();
        // Team-follow recovery used to outlive the follow goal when a leader moved out of range,
        // which let the escape goal pillar toward a stale waypoint. Following now releases cleanly
        // and ordinary navigation retries when the leader comes back within range.
        this.follower.clearTeamFollowUpwardEscapeTarget();
        this.follower.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.follower.setCurrentAiDetail("");
        this.leader = null;
    }

    private boolean canFollowLeader() {
        return this.follower.isTeamFollower()
                && this.leader != null
                && this.leader.isAlive()
                && !this.leader.isRemoved()
                && this.leader.level() == this.follower.level()
                && this.follower.isAlive()
                && !this.follower.isNoAi()
                && !this.follower.isPassenger()
                && !this.follower.isHealing()
                && this.follower.getTarget() == null
                && PlayerNpcTeamUpManager.isLeaderWithinFollowRange(this.follower, this.leader);
    }

    private void updateDetail() {
        if (this.leader != null) {
            this.follower.setCurrentAiDetail("following " + this.leader.getDisplayName().getString()
                    + " (" + this.follower.getTeamName() + ")");
        }
    }

}
