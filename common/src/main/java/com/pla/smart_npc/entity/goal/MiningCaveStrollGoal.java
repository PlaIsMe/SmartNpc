package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class MiningCaveStrollGoal extends Goal {
    private static final int HORIZONTAL_RADIUS = 14;
    private static final int VERTICAL_RADIUS = 5;
    private static final int MIN_HORIZONTAL_DISTANCE = 9;
    private static final int CAVE_DIRECTION_SECTORS = 8;
    private static final int MAX_SECTION_CHECKS = 24;
    private static final int MAX_PATH_CHECKS_PER_SCAN = 1;
    private static final int MAX_REACHABLE_TARGETS = 1;
    private static final int CAVE_SCAN_CACHE_TICKS = 20;
    private static final double CAVE_SCAN_CACHE_MOVE_SQR = 2.0D * 2.0D;
    private static final int TARGET_ROOM_SCAN_RADIUS = 3;
    private static final int TARGET_ROOM_SCAN_DOWN = 2;
    private static final int TARGET_ROOM_SCAN_UP = 2;
    private static final int MIN_CAVE_SURFACE_DEPTH = 6;
    private static final int MIN_TARGET_ROOM_STAND_POSITIONS = 7;
    private static final int MIN_TARGET_OPEN_DIRECTIONS = 2;
    private static final int MAX_STROLL_TICKS = 20 * 12;
    private static final int MAX_NO_PROGRESS_TICKS = 20;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MIN_RETRY_TICKS = 10;
    private static final int RANDOM_RETRY_TICKS = 10;
    private static final double ARRIVAL_DISTANCE_SQR = 1.5D * 1.5D;
    private static final Map<PlayerNpcEntity, CaveScanCache> CAVE_SCAN_CACHE = new WeakHashMap<>();

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos strollCenter;
    private BlockPos targetPos;
    private int strollTicks;
    private int repathTicks;
    private int noProgressTicks;
    private int nextAttemptTick;
    private BlockPos lastProgressPos;
    private boolean strollFailed;

    public MiningCaveStrollGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.onGround()
                || this.playerNpc.tickCount < this.nextAttemptTick) {
            return false;
        }
        if (!this.canStrollInCurrentCave(serverLevel)) {
            this.scheduleRetry();
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.nextAttemptTick = this.playerNpc.tickCount + 1 + this.playerNpc.getRandom().nextInt(4);
            return false;
        }

        this.strollCenter = this.playerNpc.blockPosition().immutable();
        this.targetPos = this.findStrollTarget(serverLevel);
        if (this.targetPos == null) {
            this.scheduleRetry();
            this.strollCenter = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.strollCenter != null
                && this.strollTicks < MAX_STROLL_TICKS
                && this.noProgressTicks < MAX_NO_PROGRESS_TICKS
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.canContinueCaveStroll(serverLevel)
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
        this.noProgressTicks = 0;
        this.lastProgressPos = this.playerNpc.blockPosition().immutable();
        this.strollFailed = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring_cave");
        this.updateDetail();
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            if (!this.moveToTarget(serverLevel)) {
                this.strollFailed = true;
                this.targetPos = null;
            }
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }

        this.strollTicks++;
        this.trackMovementProgress();
        if (this.noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
            this.strollFailed = true;
            this.targetPos = null;
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY(),
                this.targetPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );
        if (this.repathTicks-- <= 0) {
            if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                this.repathTicks = 1 + this.playerNpc.getRandom().nextInt(4);
                return;
            }
            if (!this.moveToTarget(serverLevel)) {
                this.strollFailed = true;
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
        this.strollCenter = null;
        this.targetPos = null;
        this.strollTicks = 0;
        this.repathTicks = 0;
        this.noProgressTicks = 0;
        this.lastProgressPos = null;
        if (this.strollFailed) {
            this.scheduleRetry();
        } else {
            this.nextAttemptTick = this.playerNpc.tickCount;
        }
        this.strollFailed = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean canStrollInCurrentCave(ServerLevel serverLevel) {
        return this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !this.playerNpc.shouldPrioritizeLogGathering()
                && !this.playerNpc.shouldPrioritizeCobblestoneGathering()
                && !MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel);
    }

    private boolean canContinueCaveStroll(ServerLevel serverLevel) {
        return this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !this.playerNpc.shouldPrioritizeLogGathering()
                && !this.playerNpc.shouldPrioritizeCobblestoneGathering()
                && !MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)
                && !serverLevel.canSeeSky(this.playerNpc.blockPosition().above());
    }

    public static boolean hasLongTraversableCave(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return !findLongCaveTargets(playerNpc, serverLevel).isEmpty();
    }

    private static List<BlockPos> findLongCaveTargets(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return List.of();
        }

        BlockPos center = playerNpc.blockPosition();
        CaveScanCache cache = CAVE_SCAN_CACHE.computeIfAbsent(playerNpc, ignored -> new CaveScanCache());
        boolean sameArea = cache.level == serverLevel
                && cache.center != null
                && cache.center.distSqr(center) <= CAVE_SCAN_CACHE_MOVE_SQR;
        if (sameArea && playerNpc.tickCount < cache.nextScanTick) {
            return cache.targets;
        }

        int candidateOffset = sameArea ? cache.candidateOffset : 0;
        List<BlockPos> targets = scanLongCaveTargets(playerNpc, serverLevel, candidateOffset);
        cache.level = serverLevel;
        cache.center = center.immutable();
        cache.targets = targets;
        cache.nextScanTick = playerNpc.tickCount + CAVE_SCAN_CACHE_TICKS;
        cache.candidateOffset = targets.isEmpty()
                ? candidateOffset + MAX_PATH_CHECKS_PER_SCAN
                : candidateOffset;
        return targets;
    }

    private static List<BlockPos> scanLongCaveTargets(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            int candidateOffset
    ) {

        BlockPos center = playerNpc.blockPosition();
        if (center == null
                || serverLevel.canSeeSky(center.above())
                || serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ())
                - center.getY() < MIN_CAVE_SURFACE_DEPTH) {
            return List.of();
        }

        List<List<BlockPos>> candidateSectors = new ArrayList<>(CAVE_DIRECTION_SECTORS);
        for (int sector = 0; sector < CAVE_DIRECTION_SECTORS; sector++) {
            candidateSectors.add(new ArrayList<>());
        }
        int minHorizontalDistanceSqr = MIN_HORIZONTAL_DISTANCE * MIN_HORIZONTAL_DISTANCE;
        int maxHorizontalDistanceSqr = HORIZONTAL_RADIUS * HORIZONTAL_RADIUS;
        for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
            for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                int horizontalDistanceSqr = dx * dx + dz * dz;
                if (horizontalDistanceSqr < minHorizontalDistanceSqr
                        || horizontalDistanceSqr > maxHorizontalDistanceSqr) {
                    continue;
                }

                BlockPos candidate = findColumnStandCandidate(serverLevel, center, dx, dz);
                if (candidate != null) {
                    candidateSectors.get(directionSector(dx, dz)).add(candidate);
                }
            }
        }

        Comparator<BlockPos> candidateOrder = Comparator
                .comparingDouble((BlockPos pos) -> center.distSqr(pos))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ);
        candidateSectors.forEach(sector -> sector.sort(candidateOrder));

        List<BlockPos> candidates = new ArrayList<>(MAX_SECTION_CHECKS);
        for (int index = 0; candidates.size() < MAX_SECTION_CHECKS; index++) {
            boolean addedCandidate = false;
            for (List<BlockPos> sector : candidateSectors) {
                if (index < sector.size()) {
                    candidates.add(sector.get(index));
                    addedCandidate = true;
                    if (candidates.size() >= MAX_SECTION_CHECKS) {
                        break;
                    }
                }
            }
            if (!addedCandidate) {
                break;
            }
        }

        PathNavigationAi pathNavigationAi = new PathNavigationAi(playerNpc);
        List<BlockPos> reachableTargets = new ArrayList<>();
        int pathChecks = 0;
        for (int checked = 0; checked < candidates.size(); checked++) {
            BlockPos candidate = candidates.get(Math.floorMod(candidateOffset + checked, candidates.size()));
            if (reachableTargets.size() >= MAX_REACHABLE_TARGETS) {
                break;
            }
            if (!hasRoomyCaveSection(serverLevel, candidate)) {
                continue;
            }
            if (pathChecks++ >= MAX_PATH_CHECKS_PER_SCAN) {
                break;
            }
            if (pathNavigationAi.hasExactPathTo(candidate)) {
                reachableTargets.add(candidate);
            }
        }
        return reachableTargets;
    }

    private static BlockPos findColumnStandCandidate(
            ServerLevel serverLevel,
            BlockPos center,
            int dx,
            int dz
    ) {
        for (int offset = 0; offset <= VERTICAL_RADIUS; offset++) {
            BlockPos aboveCandidate = center.offset(dx, offset, dz).immutable();
            if (!serverLevel.canSeeSky(aboveCandidate.above())
                    && PathNavigationAi.canStandAt(serverLevel, aboveCandidate)) {
                return aboveCandidate;
            }
            if (offset == 0) {
                continue;
            }
            BlockPos belowCandidate = center.offset(dx, -offset, dz).immutable();
            if (!serverLevel.canSeeSky(belowCandidate.above())
                    && PathNavigationAi.canStandAt(serverLevel, belowCandidate)) {
                return belowCandidate;
            }
        }
        return null;
    }

    private static int directionSector(int dx, int dz) {
        int absX = Math.abs(dx);
        int absZ = Math.abs(dz);
        if (absX >= absZ * 2) {
            return dx > 0 ? 0 : 4;
        }
        if (absZ >= absX * 2) {
            return dz > 0 ? 2 : 6;
        }
        if (dx > 0) {
            return dz > 0 ? 1 : 7;
        }
        return dz > 0 ? 3 : 5;
    }

    private static boolean hasRoomyCaveSection(ServerLevel serverLevel, BlockPos center) {
        int standPositions = 0;
        int openDirections = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            boolean open = false;
            for (int dy = -TARGET_ROOM_SCAN_DOWN; dy <= TARGET_ROOM_SCAN_UP; dy++) {
                if (PathNavigationAi.canStandAt(serverLevel, center.relative(direction).offset(0, dy, 0))) {
                    open = true;
                    break;
                }
            }
            openDirections += open ? 1 : 0;
        }
        if (openDirections < MIN_TARGET_OPEN_DIRECTIONS) {
            return false;
        }

        int radiusSqr = TARGET_ROOM_SCAN_RADIUS * TARGET_ROOM_SCAN_RADIUS;
        for (int dx = -TARGET_ROOM_SCAN_RADIUS; dx <= TARGET_ROOM_SCAN_RADIUS; dx++) {
            for (int dz = -TARGET_ROOM_SCAN_RADIUS; dz <= TARGET_ROOM_SCAN_RADIUS; dz++) {
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                for (int dy = -TARGET_ROOM_SCAN_DOWN; dy <= TARGET_ROOM_SCAN_UP; dy++) {
                    if (PathNavigationAi.canStandAt(serverLevel, center.offset(dx, dy, dz))) {
                        standPositions++;
                        break;
                    }
                }
            }
        }
        return standPositions >= MIN_TARGET_ROOM_STAND_POSITIONS;
    }

    private BlockPos findStrollTarget(ServerLevel serverLevel) {
        List<BlockPos> targets = findLongCaveTargets(this.playerNpc, serverLevel);
        return targets.isEmpty()
                ? null
                : targets.get(this.playerNpc.getRandom().nextInt(targets.size()));
    }

    private boolean moveToTarget(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }
        Path path = this.playerNpc.getNavigation().createPath(this.targetPos, 0);
        if (path == null
                || !path.canReach()
                || path.getEndNode() == null
                || !path.getEndNode().asBlockPos().equals(this.targetPos)) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private void trackMovementProgress() {
        BlockPos currentPos = this.playerNpc.blockPosition();
        if (this.lastProgressPos == null || !this.lastProgressPos.equals(currentPos)) {
            this.lastProgressPos = currentPos.immutable();
            this.noProgressTicks = 0;
            return;
        }
        this.noProgressTicks++;
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
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("strolling around mining cave");
            return;
        }
        this.playerNpc.setCurrentAiDetail("strolling around mining cave @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ());
    }

    private void scheduleRetry() {
        this.nextAttemptTick = this.playerNpc.tickCount
                + MIN_RETRY_TICKS
                + this.playerNpc.getRandom().nextInt(RANDOM_RETRY_TICKS + 1);
    }

    private static final class CaveScanCache {
        private ServerLevel level;
        private BlockPos center;
        private List<BlockPos> targets = List.of();
        private int nextScanTick;
        private int candidateOffset;
    }
}
