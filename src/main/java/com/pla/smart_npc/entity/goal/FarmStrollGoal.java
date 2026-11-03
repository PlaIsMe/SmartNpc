package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** A bounded low-priority daytime walk after all owned crop cells are planted. */
public final class FarmStrollGoal extends Goal {
    private static final int OUTSIDE_RING_OFFSET = 2;
    private static final int OUTSIDE_VERTICAL_RANGE = 2;
    private static final int MAX_PATH_CHECKS = 1;
    private static final float FARM_STROLL_PATH_NODE_MULTIPLIER = 0.01F;
    private static final int MAX_STROLL_TICKS = 20 * 12;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MIN_RETRY_TICKS = 20 * 5;
    private static final int RANDOM_RETRY_TICKS = 20 * 7;
    private static final double ARRIVAL_DISTANCE_SQR = 1.5D * 1.5D;

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final double speed;
    private BlockPos targetPos;
    private int strollTicks;
    private int repathTicks;
    private int nextAttemptTick;
    private int targetCandidateCursor;

    public FarmStrollGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.canStroll(serverLevel)
                || !this.playerNpc.onGround()
                || this.playerNpc.isInWater()
                || this.playerNpc.tickCount < this.nextAttemptTick) {
            return false;
        }
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        this.targetPos = plan == null ? null : this.findTarget(serverLevel, plan);
        if (this.targetPos == null) {
            this.scheduleRetry();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.strollTicks < MAX_STROLL_TICKS
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.canStroll(serverLevel)
                && !this.playerNpc.isInWater()
                && this.distanceToTargetSqr() > ARRIVAL_DISTANCE_SQR;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.strollTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.farming");
        this.updateDetail();
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.moveToTarget(serverLevel);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }
        this.strollTicks++;
        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY(),
                this.targetPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );
        if (this.repathTicks-- <= 0) {
            if (!this.moveToTarget(serverLevel)) {
                this.targetPos = null;
                return;
            }
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.playerNpc.getNavigation().stop();
        this.targetPos = null;
        this.strollTicks = 0;
        this.repathTicks = 0;
        this.scheduleRetry();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean canStroll(ServerLevel serverLevel) {
        return this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && !serverLevel.isDarkOutside()
                && !serverLevel.isThundering()
                && FarmAi.isFarmingJobActive(this.playerNpc)
                && (FarmCropGoal.isFullyPlantedOwnedFarm(this.playerNpc, serverLevel)
                || this.shouldReturnToFarmForPlantingSupplies(serverLevel))
                && !FarmCropGoal.hasActionableOwnedFarmWork(this.playerNpc, serverLevel);
    }

    private boolean shouldReturnToFarmForPlantingSupplies(ServerLevel serverLevel) {
        if (!FarmCropGoal.shouldExploreForFarmSupplies(this.playerNpc, serverLevel)) {
            return false;
        }
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (plan == null) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        int minX = plan.origin().getX() - OUTSIDE_RING_OFFSET;
        int maxX = plan.origin().getX() + plan.width() - 1 + OUTSIDE_RING_OFFSET;
        int minZ = plan.origin().getZ() - OUTSIDE_RING_OFFSET;
        int maxZ = plan.origin().getZ() + plan.depth() - 1 + OUTSIDE_RING_OFFSET;
        return feet.getX() < minX || feet.getX() > maxX || feet.getZ() < minZ || feet.getZ() > maxZ;
    }

    private BlockPos findTarget(ServerLevel serverLevel, Plan plan) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos ground : plan.pathPositions()) {
            candidates.add(ground.above());
        }

        int minX = plan.origin().getX() - OUTSIDE_RING_OFFSET;
        int maxX = plan.origin().getX() + plan.width() - 1 + OUTSIDE_RING_OFFSET;
        int minZ = plan.origin().getZ() - OUTSIDE_RING_OFFSET;
        int maxZ = plan.origin().getZ() + plan.depth() - 1 + OUTSIDE_RING_OFFSET;
        int baseFeetY = plan.origin().getY() + 1;
        for (int dy = -OUTSIDE_VERTICAL_RANGE; dy <= OUTSIDE_VERTICAL_RANGE; dy++) {
            int feetY = baseFeetY + dy;
            for (int x = minX; x <= maxX; x++) {
                candidates.add(new BlockPos(x, feetY, minZ));
                candidates.add(new BlockPos(x, feetY, maxZ));
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                candidates.add(new BlockPos(minX, feetY, z));
                candidates.add(new BlockPos(maxX, feetY, z));
            }
        }

        List<BlockPos> unique = new ArrayList<>(candidates.stream().distinct().toList());
        int checks = 0;
        int start = unique.isEmpty() ? 0 : Math.floorMod(this.targetCandidateCursor++, unique.size());
        for (int offset = 0; offset < unique.size() && checks++ < MAX_PATH_CHECKS; offset++) {
            BlockPos candidate = unique.get((start + offset) % unique.size()).immutable();
            BlockPos candidateGround = candidate.below();
            boolean explicitPath = plan.pathPositions().contains(candidateGround);
            if (candidate.equals(this.playerNpc.blockPosition())
                    || candidate.equals(plan.gatePos())
                    || plan.isFencePosition(candidate)
                    || plan.containsGround(candidateGround) && !explicitPath
                    || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                    || !PathNavigationAi.canStandAt(serverLevel, candidate)
                    || !this.pathNavigationAi.hasExactPathTo(candidate, FARM_STROLL_PATH_NODE_MULTIPLIER)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private boolean moveToTarget(ServerLevel serverLevel) {
        return this.targetPos != null
                && this.pathNavigationAi.moveToExact(
                serverLevel,
                this.targetPos,
                this.speed,
                0,
                FARM_STROLL_PATH_NODE_MULTIPLIER);
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY(),
                this.targetPos.getZ() + 0.5D
        );
    }

    private void updateDetail() {
        String action = this.playerNpc.level() instanceof ServerLevel serverLevel
                && !FarmCropGoal.isFullyPlantedOwnedFarm(this.playerNpc, serverLevel)
                ? "returning to farm for planting supplies"
                : "strolling around planted farm";
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail(action);
            return;
        }
        this.playerNpc.setCurrentAiDetail(action + " @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ());
    }

    private void scheduleRetry() {
        this.nextAttemptTick = this.playerNpc.tickCount
                + MIN_RETRY_TICKS
                + this.playerNpc.getRandom().nextInt(RANDOM_RETRY_TICKS + 1);
    }
}
