package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Visibly dismantles abandoned blocks which this NPC proved it placed as temporary supports.
 * This is routine maintenance scheduled ahead of new GatherLogs routes; emergency descent goals
 * remain responsible for an NPC which is still standing on its own column.
 */
public final class CleanupTemporaryPillarGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.gathering_materials";
    private static final int PROBE_INTERVAL_TICKS = 40;
    private static final int FAILED_COLUMN_BACKOFF_TICKS = 20 * 15;
    private static final int MAX_COLUMN_CANDIDATES_PER_PROBE = 4;
    private static final int MAX_STAND_BLOCK_PROBES = 64;
    private static final int MAX_STAND_CANDIDATES = 12;
    private static final int MAX_PATH_ATTEMPTS = 4;
    private static final int MAX_ROUTE_TICKS = 20 * 12;
    private static final int MAX_GOAL_TICKS = 20 * 45;
    private static final int MAX_BLOCKS_PER_ACTIVATION = 16;
    private static final int MAX_HORIZONTAL_CLEANUP_DISTANCE = 32;
    private static final double BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final float PATH_NODE_MULTIPLIER = 0.03F;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final Map<Long, Integer> failedColumnsUntil = new HashMap<>();

    private BlockPos targetPos;
    private int columnX;
    private int columnZ;
    private List<BlockPos> standCandidates = List.of();
    private int standCandidateIndex;
    private BlockPos standPos;
    private boolean navigating;
    private int routeTicks;
    private int goalTicks;
    private int blocksBroken;
    private int nextProbeTick;
    private boolean workerSlotPaused;
    private boolean finished;

    public CleanupTemporaryPillarGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !PlayerNpcAiWorkBudget.hasWorkerSlot(this.playerNpc)
                || !this.canPerformMaintenance()
                || GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc)) {
            return false;
        }
        if (this.workerSlotPaused && this.targetPos != null
                && this.isWithinCleanupRange(this.targetPos)
                && this.isBreakableOwnedTop(serverLevel, this.targetPos)) {
            this.standCandidates = this.findStandCandidates(serverLevel, this.targetPos);
            if (!this.standCandidates.isEmpty()) {
                return true;
            }
        }
        if (this.playerNpc.tickCount < this.nextProbeTick) {
            return false;
        }
        this.nextProbeTick = this.playerNpc.tickCount + PROBE_INTERVAL_TICKS;
        this.pruneFailedColumnBackoffs();
        return this.selectColumn(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            return false;
        }
        return !this.finished
                && this.targetPos != null
                && this.goalTicks < MAX_GOAL_TICKS
                && this.blocksBroken < MAX_BLOCKS_PER_ACTIVATION
                && this.canPerformMaintenance()
                && !GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc);
    }

    @Override
    public void start() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.playerNpc.getNavigation().stop();
            return;
        }
        this.workerSlotPaused = false;
        this.goalTicks = 0;
        this.blocksBroken = 0;
        this.finished = false;
        this.routeTicks = 0;
        this.navigating = false;
        this.standCandidateIndex = 0;
        this.standPos = null;
        this.breakingBlockAi.stop();
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.updateDetail("planning visible pillar cleanup");
    }

    @Override
    public void tick() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.pauseForWorkerSlotLoss();
            return;
        }
        this.goalTicks++;
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.failColumn();
            return;
        }

        if (!this.isBreakableOwnedTop(serverLevel, this.targetPos)) {
            // Source-aware validation rejects missing GatherLogs proof and invalidates the ledger
            // only when exact block identity has genuinely gone stale. Protected, occupied, or
            // unreachable supports remain owned and are retried after backoff.
            if (!this.playerNpc.isGatherLogsTemporaryPillarSupport(this.targetPos)
                    && this.selectNextTargetInColumn(serverLevel)) {
                return;
            }
            this.failColumn();
            return;
        }

        if (this.canBreakSafelyFromCurrentPosition(serverLevel, this.targetPos)) {
            this.playerNpc.getNavigation().stop();
            this.navigating = false;
            this.breakTarget(serverLevel);
            return;
        }

        if (this.navigating) {
            this.routeTicks++;
            this.playerNpc.getLookControl().setLookAt(
                    this.targetPos.getX() + 0.5D,
                    this.targetPos.getY() + 0.5D,
                    this.targetPos.getZ() + 0.5D,
                    30.0F,
                    30.0F
            );
            if (this.routeTicks >= MAX_ROUTE_TICKS || this.playerNpc.getNavigation().isDone()) {
                this.navigating = false;
                this.standPos = null;
            } else {
                this.updateDetail("walking to owned pillar");
                return;
            }
        }

        this.tryNextStandPath(serverLevel);
    }

    @Override
    public void stop() {
        if (this.workerSlotPaused || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.pauseForWorkerSlotLoss();
            return;
        }
        this.playerNpc.getNavigation().stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        if (AI_STATE.equals(this.playerNpc.getCurrentAiState())) {
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        }
        this.targetPos = null;
        this.standPos = null;
        this.standCandidates = List.of();
        this.navigating = false;
        this.finished = true;
    }

    private void pauseForWorkerSlotLoss() {
        this.playerNpc.getNavigation().stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.navigating = false;
        this.workerSlotPaused = true;
        if (AI_STATE.equals(this.playerNpc.getCurrentAiState())) {
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        }
        // targetPos, the failed-column backoff, and exact persisted GatherLogs provenance remain
        // intact. The next holder revalidates identity and resumes visible cleanup.
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean canPerformMaintenance() {
        return this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null;
    }

    private boolean selectColumn(ServerLevel serverLevel) {
        Map<Long, BlockPos> topByColumn = new HashMap<>();
        for (BlockPos pos : this.playerNpc.getGatherLogsTemporaryPillarSupportsSnapshot()) {
            if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
                this.playerNpc.forgetTemporaryPillarSupport(pos);
                continue;
            }
            if (!serverLevel.hasChunkAt(pos) || !this.playerNpc.isGatherLogsTemporaryPillarSupport(pos)) {
                continue;
            }
            long key = columnKey(pos.getX(), pos.getZ());
            BlockPos top = topByColumn.get(key);
            if (top == null || pos.getY() > top.getY()) {
                topByColumn.put(key, pos.immutable());
            }
        }

        List<BlockPos> tops = new ArrayList<>(topByColumn.values());
        tops.sort(Comparator.comparingDouble(pos -> pos.distSqr(this.playerNpc.blockPosition())));
        int inspected = 0;
        for (BlockPos top : tops) {
            long key = columnKey(top.getX(), top.getZ());
            if (this.failedColumnsUntil.getOrDefault(key, 0) > this.playerNpc.tickCount
                    || !this.isWithinCleanupRange(top)
                    || !this.isBreakableOwnedTop(serverLevel, top)) {
                continue;
            }
            if (inspected++ >= MAX_COLUMN_CANDIDATES_PER_PROBE) {
                break;
            }
            List<BlockPos> candidates = this.findStandCandidates(serverLevel, top);
            if (candidates.isEmpty()) {
                this.failedColumnsUntil.put(key, this.playerNpc.tickCount + FAILED_COLUMN_BACKOFF_TICKS);
                continue;
            }
            this.columnX = top.getX();
            this.columnZ = top.getZ();
            this.targetPos = top.immutable();
            this.standCandidates = candidates;
            return true;
        }
        return false;
    }

    private boolean selectNextTargetInColumn(ServerLevel serverLevel) {
        BlockPos next = null;
        for (BlockPos pos : this.playerNpc.getGatherLogsTemporaryPillarSupportsSnapshot()) {
            if (pos.getX() != this.columnX || pos.getZ() != this.columnZ
                    || !serverLevel.hasChunkAt(pos)
                    || !this.playerNpc.isGatherLogsTemporaryPillarSupport(pos)) {
                continue;
            }
            if (next == null || pos.getY() > next.getY()) {
                next = pos.immutable();
            }
        }
        if (next == null) {
            this.finished = true;
            return false;
        }
        this.targetPos = next;
        this.standCandidates = this.findStandCandidates(serverLevel, next);
        this.standCandidateIndex = 0;
        this.standPos = null;
        this.routeTicks = 0;
        this.navigating = false;
        if (this.standCandidates.isEmpty() || !this.isBreakableOwnedTop(serverLevel, next)) {
            this.failColumn();
            return false;
        }
        this.updateDetail("continuing owned pillar top-down");
        return true;
    }

    private void tryNextStandPath(ServerLevel serverLevel) {
        int pathAttempts = Math.min(MAX_PATH_ATTEMPTS, this.standCandidates.size());
        if (this.standCandidateIndex >= pathAttempts) {
            this.failColumn();
            return;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
            this.updateDetail("waiting to route to owned pillar");
            return;
        }

        BlockPos candidate = this.standCandidates.get(this.standCandidateIndex++);
        if (candidate.equals(this.playerNpc.blockPosition())) {
            this.standPos = candidate;
            if (this.canBreakSafelyFromCurrentPosition(serverLevel, this.targetPos)) {
                this.breakTarget(serverLevel);
                return;
            }
        }
        Path path = PathNavigationAi.createBoundedPath(this.playerNpc, candidate, PATH_NODE_MULTIPLIER);
        if (path == null || !path.canReach() || path.getEndNode() == null
                || !candidate.equals(path.getEndNode().asBlockPos())) {
            this.updateDetail("owned pillar stand unreachable; trying another");
            return;
        }
        this.standPos = candidate;
        this.routeTicks = 0;
        this.navigating = this.playerNpc.getNavigation().moveTo(path, this.speed);
        if (!this.navigating) {
            this.standPos = null;
        }
    }

    private void breakTarget(ServerLevel serverLevel) {
        if (!this.isBreakableOwnedTop(serverLevel, this.targetPos)
                || !this.canBreakSafelyFromCurrentPosition(serverLevel, this.targetPos)) {
            this.failColumn();
            return;
        }
        BlockPos breakingPos = this.targetPos;
        BlockState expectedState = serverLevel.getBlockState(breakingPos);
        this.toolAi.equipBestToolFor(expectedState);
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                breakingPos,
                state -> state.getBlock() == expectedState.getBlock()
                        && this.isBreakableOwnedTop(serverLevel, breakingPos),
                BreakingBlockAi.requiredBreakTicks(serverLevel, breakingPos, expectedState, this.playerNpc),
                "breaking owned temporary pillar"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }
        if (result == BreakingBlockAi.TickResult.FAILED) {
            this.failColumn();
            return;
        }

        // DONE can mean either a successful visible break or that another system changed the
        // target. Source-aware validation checks both GatherLogs provenance and exact identity;
        // forgetting is therefore permitted only after that combined proof is no longer valid.
        if (this.playerNpc.isGatherLogsTemporaryPillarSupport(breakingPos)) {
            this.failColumn();
            return;
        }
        this.playerNpc.forgetTemporaryPillarSupport(breakingPos);
        this.blocksBroken++;
        this.selectNextTargetInColumn(serverLevel);
    }

    private boolean isBreakableOwnedTop(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null
                || !serverLevel.hasChunkAt(pos)
                || !this.playerNpc.isGatherLogsTemporaryPillarSupport(pos)
                || serverLevel.getBlockEntity(pos) != null
                || this.isProtected(pos)
                || this.isSupportingLivingEntity(serverLevel, pos)) {
            return false;
        }
        return true;
    }

    private boolean isSupportingLivingEntity(ServerLevel serverLevel, BlockPos pos) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (feet.getX() == pos.getX() && feet.getZ() == pos.getZ() && feet.getY() > pos.getY()) {
            return true;
        }
        AABB supportArea = new AABB(pos.above()).inflate(0.08D, 0.35D, 0.08D);
        return !serverLevel.getEntitiesOfClass(
                LivingEntity.class,
                supportArea,
                entity -> entity.isAlive() && !entity.isRemoved()
        ).isEmpty();
    }

    private List<BlockPos> findStandCandidates(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>(MAX_STAND_CANDIDATES);
        BlockPos current = this.playerNpc.blockPosition();
        if (this.isSafeStand(serverLevel, current, target)) {
            candidates.add(current.immutable());
        }

        int probes = 0;
        int[] verticalOffsets = {-1, -2, -3, 0, 1, 2};
        outer:
        for (int radius = 1; radius <= 3; radius++) {
            for (int dy : verticalOffsets) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        if (++probes > MAX_STAND_BLOCK_PROBES) {
                            break outer;
                        }
                        BlockPos candidate = target.offset(dx, dy, dz);
                        if (this.isSafeStand(serverLevel, candidate, target)) {
                            candidates.add(candidate.immutable());
                            if (candidates.size() >= MAX_STAND_CANDIDATES) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(current)));
        return candidates;
    }

    private boolean isSafeStand(ServerLevel serverLevel, BlockPos stand, BlockPos target) {
        if (stand.getX() == target.getX() && stand.getZ() == target.getZ()) {
            return false;
        }
        Vec3 eye = new Vec3(stand.getX() + 0.5D, stand.getY() + this.playerNpc.getEyeHeight(), stand.getZ() + 0.5D);
        return eye.distanceToSqr(Vec3.atCenterOf(target)) <= BREAK_DISTANCE_SQR
                && PathNavigationAi.canStandAt(serverLevel, stand)
                && !this.isProtected(stand)
                && !this.isProtected(stand.below());
    }

    private boolean canBreakSafelyFromCurrentPosition(ServerLevel serverLevel, BlockPos target) {
        if (target == null) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        return (feet.getX() != target.getX() || feet.getZ() != target.getZ())
                && PathNavigationAi.canStandAt(serverLevel, feet)
                && !this.isProtected(feet)
                && !this.isProtected(feet.below())
                && this.playerNpc.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= BREAK_DISTANCE_SQR;
    }

    private boolean isProtected(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || PlayerNpcHomeUtil.getHome(this.playerNpc)
                .map(home -> PlayerNpcHomeUtil.isInside(home, pos))
                .orElse(false)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos);
    }

    private boolean isWithinCleanupRange(BlockPos pos) {
        int dx = Math.abs(pos.getX() - this.playerNpc.getBlockX());
        int dz = Math.abs(pos.getZ() - this.playerNpc.getBlockZ());
        return Math.max(dx, dz) <= MAX_HORIZONTAL_CLEANUP_DISTANCE;
    }

    private void failColumn() {
        this.failedColumnsUntil.put(
                columnKey(this.columnX, this.columnZ),
                this.playerNpc.tickCount + FAILED_COLUMN_BACKOFF_TICKS
        );
        this.finished = true;
        this.nextProbeTick = Math.max(this.nextProbeTick, this.playerNpc.tickCount + PROBE_INTERVAL_TICKS);
    }

    private void pruneFailedColumnBackoffs() {
        this.failedColumnsUntil.entrySet().removeIf(entry -> entry.getValue() <= this.playerNpc.tickCount);
    }

    private void updateDetail(String action) {
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail(action);
            return;
        }
        this.playerNpc.setCurrentAiDetail(action + " @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ());
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
