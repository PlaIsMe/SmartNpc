package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public final class ClearBlockAi {
    public enum TickResult {
        IDLE,
        RUNNING,
        DONE,
        FAILED
    }

    private static final double DEFAULT_CLEAR_DISTANCE_SQR = 4.5D * 4.5D;
    static final double BREAK_REACH_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double BREAK_STAND_REACHED_SQR = 0.95D * 0.95D;
    private static final double BREAK_STAND_CENTERED_SQR = 0.35D * 0.35D;
    private static final double STAND_EYE_HEIGHT = 1.5D;
    private static final int BREAK_STAND_RADIUS = 2;
    private static final int BREAK_STAND_VERTICAL_RANGE = 2;
    private static final int MAX_BREAK_STAND_PATH_CHECKS = 12;
    private static final int MAX_ACCESSIBLE_CLEAR_CANDIDATES = 12;
    // Path construction is the expensive part. Selection retries rotate through candidates, so
    // one total path here bounds the server tick without permanently favoring the nearest miss.
    private static final int MAX_CLEAR_SELECTION_PATHS = 1;
    private static final int MAX_RUNNING_CLEAR_STAND_PATHS = 1;
    private static final int RUNNING_STAND_SELECTION_RETRY_TICKS = 20;
    private static final int DENIED_PATH_RETRY_MAX_TICKS = 4;
    // Clear-route recovery is optional local work. A quarter of the entity's unusually large
    // path-node budget still measured at 133.8 ms in GatherStone.tick; keep each retained route
    // attempt deliberately tiny and let the existing stand cursor try another candidate later.
    private static final float CLEAR_STAND_PATH_NODE_MULTIPLIER = 0.005F;
    private static final int APPROACH_REPATH_INTERVAL_TICKS = 10;
    private static final int MAX_APPROACH_TICKS = 20 * 8;
    private static final int MAX_CLEAR_TARGET_TICKS = 20 * 15;
    private static final int MAX_CENTERED_BLOCKED_STAND_TICKS = 20;
    private static final int MAX_BREAK_RAY_SHAPE_COMPONENTS = 12;

    private final PlayerNpcEntity playerNpc;
    private final BreakingBlockAi breakingBlockAi;
    private BlockPos requestedTargetPos;
    private BlockPos targetPos;
    private BlockPos standPos;
    private Predicate<BlockState> targetPredicate;
    private String detail = "clearing block";
    private int requiredTicks;
    private double clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;
    private boolean allowSoftCover;
    private boolean allowOwnedFarmDestruction;
    private Path plannedApproachPath;
    private int approachRepathTicks;
    private int approachTicks;
    private int clearTargetTicks;
    private int centeredBlockedStandTicks;
    private int clearSelectionCursor;
    private int clearSelectionFailedPathAttempts;
    private int clearSelectionSignature;
    private boolean clearSelectionInitialized;
    private boolean clearSelectionPending;
    private int runningStandSelectionCursor;
    private int runningStandCandidatesExamined;
    private int runningStandCandidateCount;
    private int nextRunningStandSelectionTick;

    public ClearBlockAi(PlayerNpcEntity playerNpc, BreakingBlockAi breakingBlockAi) {
        this.playerNpc = playerNpc;
        this.breakingBlockAi = breakingBlockAi;
    }

    public boolean isRunning() {
        return this.targetPos != null;
    }

    public BlockPos targetPos() {
        return this.targetPos;
    }

    /** True when a bounded startNearest pass has more stand candidates to inspect later. */
    public boolean hasPendingSelection() {
        return this.clearSelectionPending;
    }

    /** True only when the next clear tick would construct a new navigation path. */
    public boolean needsPathWork(ServerLevel serverLevel) {
        if (serverLevel == null
                || this.targetPos == null
                || canBreakFromCurrentPosition(serverLevel, this.playerNpc, this.targetPos, this.allowSoftCover)) {
            return false;
        }
        if (this.playerNpc.tickCount < this.nextRunningStandSelectionTick) {
            return false;
        }
        if (this.standPos == null
                || !canUseBreakStand(serverLevel, this.standPos, this.targetPos, this.allowSoftCover)) {
            return true;
        }
        if (isAtBreakStand(this.playerNpc, this.standPos)
                || this.plannedApproachPath != null
                || this.approachRepathTicks > 0
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck()) {
            return false;
        }
        return true;
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks
    ) {
        return this.start(serverLevel, targetPos, targetPredicate, detail, requiredTicks, DEFAULT_CLEAR_DISTANCE_SQR);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr
    ) {
        return this.start(serverLevel, targetPos, targetPredicate, detail, requiredTicks, clearDistanceSqr, false);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftCover
    ) {
        return this.start(serverLevel, targetPos, targetPredicate, detail, requiredTicks,
                clearDistanceSqr, allowSoftCover, false);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftCover,
            boolean allowOwnedFarmDestruction
    ) {
        if (!allowOwnedFarmDestruction
                && FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, targetPos)
                || !isClearable(serverLevel, targetPos, targetPredicate, allowSoftCover)
                || this.playerNpc.distanceToSqr(centerX(targetPos), centerY(targetPos), centerZ(targetPos)) > clearDistanceSqr) {
            return false;
        }

        this.stop();
        this.requestedTargetPos = targetPos.immutable();
        this.targetPos = this.requestedTargetPos;
        this.targetPredicate = targetPredicate;
        this.detail = detail;
        this.requiredTicks = Math.max(1, requiredTicks);
        this.clearDistanceSqr = clearDistanceSqr;
        this.allowSoftCover = allowSoftCover;
        this.allowOwnedFarmDestruction = allowOwnedFarmDestruction;
        this.clearTargetTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.updateDetail();
        return true;
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks
    ) {
        return this.startNearest(serverLevel, candidates, targetPredicate, detail, requiredTicks, DEFAULT_CLEAR_DISTANCE_SQR);
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr
    ) {
        return this.startNearest(serverLevel, candidates, targetPredicate, detail, requiredTicks, clearDistanceSqr, false);
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        return this.startNearest(serverLevel, candidates, targetPredicate, detail, requiredTicks,
                clearDistanceSqr, allowSoftColumnCover, false);
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftColumnCover,
            boolean allowOwnedFarmDestruction
    ) {
        Collection<BlockPos> effectiveCandidates = allowOwnedFarmDestruction
                ? candidates
                : candidates.stream()
                .filter(pos -> !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos))
                .toList();
        int selectionSignature = clearSelectionSignature(
                effectiveCandidates,
                detail,
                clearDistanceSqr,
                allowSoftColumnCover,
                allowOwnedFarmDestruction
        );
        if (!this.clearSelectionInitialized || this.clearSelectionSignature != selectionSignature) {
            this.clearSelectionCursor = 0;
            this.clearSelectionFailedPathAttempts = 0;
            this.clearSelectionSignature = selectionSignature;
            this.clearSelectionInitialized = true;
        }
        ClearTargetSelection selection = selectNearestAccessibleClearable(
                serverLevel,
                this.playerNpc,
                effectiveCandidates,
                targetPredicate,
                clearDistanceSqr,
                allowSoftColumnCover,
                new NavigationPathBudget(MAX_CLEAR_SELECTION_PATHS),
                this.clearSelectionCursor
        );
        if (selection.plan().isEmpty()) {
            this.clearSelectionCursor = selection.nextCandidateIndex();
            this.clearSelectionFailedPathAttempts += selection.pathsAttempted();
            this.clearSelectionPending = selection.pathsAttempted() > 0
                    && this.clearSelectionFailedPathAttempts < selection.pathCandidateCount();
            if (!this.clearSelectionPending) {
                this.resetClearSelectionProgress();
            }
            return false;
        }

        ClearTargetPlan plan = selection.plan().get();
        BlockPos target = plan.target();
        Predicate<BlockState> effectivePredicate = targetPredicate;
        BlockState state = serverLevel.getBlockState(target);
        if (allowSoftColumnCover
                && !targetPredicate.test(state)
                && isPartialShapePathObstruction(serverLevel, target, state)) {
            effectivePredicate = blockState -> targetPredicate.test(blockState)
                    || isPotentialPartialShapeState(blockState);
        }
        boolean started = this.start(serverLevel, target, effectivePredicate, detail, requiredTicks,
                clearDistanceSqr, allowSoftColumnCover, allowOwnedFarmDestruction);
        if (!started) {
            this.resetClearSelectionProgress();
            return false;
        }
        this.standPos = plan.stand();
        this.plannedApproachPath = plan.path();
        if (plan.stand() != null) {
            // The start pass already evaluated the nearest stand. If that retained route later
            // becomes unusable, running recovery resumes from the next bounded stand candidate.
            this.runningStandSelectionCursor = 1;
            this.runningStandCandidatesExamined = 1;
            this.runningStandCandidateCount = MAX_BREAK_STAND_PATH_CHECKS;
        }
        this.resetClearSelectionProgress();
        return true;
    }

    public TickResult tick(ServerLevel serverLevel) {
        return this.tick(serverLevel, true);
    }

    public TickResult tick(ServerLevel serverLevel, boolean pathWorkAllowed) {
        if (this.targetPos == null) {
            return TickResult.IDLE;
        }

        if (this.targetPredicate == null) {
            this.stop();
            return TickResult.FAILED;
        }

        if (!this.allowOwnedFarmDestruction
                && FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, this.targetPos)) {
            this.playerNpc.setIdleTraceDetail("clear target protected by owned farm @ "
                    + this.targetPos.getX() + " " + this.targetPos.getY() + " " + this.targetPos.getZ(), 40);
            this.stop();
            return TickResult.FAILED;
        }

        if (!isClearable(serverLevel, this.targetPos, this.targetPredicate, this.allowSoftCover)) {
            if (this.resumeRequestedTarget(serverLevel)) {
                this.updateDetail();
                return TickResult.RUNNING;
            }
            this.stop();
            return TickResult.DONE;
        }

        if (++this.clearTargetTicks > MAX_CLEAR_TARGET_TICKS) {
            this.stop();
            return TickResult.FAILED;
        }

        if (this.playerNpc.distanceToSqr(centerX(this.targetPos), centerY(this.targetPos), centerZ(this.targetPos)) > this.clearDistanceSqr) {
            this.stop();
            return TickResult.FAILED;
        }

        Optional<BlockPos> blocker = findBreakRayBlocker(
                serverLevel,
                this.playerNpc,
                this.targetPos,
                this.targetPredicate,
                this.clearDistanceSqr,
                this.allowSoftCover
        );
        if (blocker.isPresent() && !blocker.get().equals(this.targetPos)) {
            if (!this.allowOwnedFarmDestruction
                    && FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, blocker.get())) {
                this.playerNpc.setIdleTraceDetail("clear blocker protected by owned farm @ "
                        + blocker.get().getX() + " " + blocker.get().getY() + " " + blocker.get().getZ(), 40);
                this.stop();
                return TickResult.FAILED;
            }
            this.breakingBlockAi.stop();
            this.retargetBlocker(blocker.get());
            this.updateDetail();
            return TickResult.RUNNING;
        }

        if (!canBreakFromCurrentPosition(serverLevel, this.playerNpc, this.targetPos, this.allowSoftCover)) {
            this.breakingBlockAi.stop();
            if (!this.moveNearTarget(serverLevel, pathWorkAllowed)) {
                this.stop();
                return TickResult.FAILED;
            }
            this.updateDetail();
            return TickResult.RUNNING;
        }

        this.playerNpc.getNavigation().stop();
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                this.targetPredicate,
                this.requiredTicks,
                this.detail + (this.isClearingBlocker() ? " blocker" : ""),
                false,
                this.allowOwnedFarmDestruction
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            this.updateDetail();
            return TickResult.RUNNING;
        }
        if (result == BreakingBlockAi.TickResult.DONE) {
            if (this.resumeRequestedTarget(serverLevel)) {
                this.updateDetail();
                return TickResult.RUNNING;
            }
            this.stop();
            return TickResult.DONE;
        }

        this.stop();
        return TickResult.FAILED;
    }

    public void stop() {
        if (this.targetPos != null) {
            this.breakingBlockAi.stop();
        }
        this.requestedTargetPos = null;
        this.targetPos = null;
        this.standPos = null;
        this.targetPredicate = null;
        this.requiredTicks = 0;
        this.clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;
        this.allowSoftCover = false;
        this.allowOwnedFarmDestruction = false;
        this.plannedApproachPath = null;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.clearTargetTicks = 0;
        this.centeredBlockedStandTicks = 0;
        this.resetClearSelectionProgress();
        this.resetRunningStandSelection();
    }

    /**
     * Keeps the selected clear request, but makes its next tick choose an approach from the
     * NPC's new position. The overall clear timeout is deliberately preserved.
     */
    public void retryFromCurrentPosition() {
        if (this.targetPos == null) {
            return;
        }
        this.breakingBlockAi.stop();
        this.standPos = null;
        this.plannedApproachPath = null;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.centeredBlockedStandTicks = 0;
        this.resetRunningStandSelection();
    }

    private void resetClearSelectionProgress() {
        this.clearSelectionCursor = 0;
        this.clearSelectionFailedPathAttempts = 0;
        this.clearSelectionSignature = 0;
        this.clearSelectionInitialized = false;
        this.clearSelectionPending = false;
    }

    private void resetRunningStandSelection() {
        this.runningStandSelectionCursor = 0;
        this.runningStandCandidatesExamined = 0;
        this.runningStandCandidateCount = 0;
        this.nextRunningStandSelectionTick = 0;
    }

    public String detail() {
        if (this.breakingBlockAi.isRunning()) {
            return this.breakingBlockAi.detail();
        }
        if (this.targetPos == null) {
            return "";
        }
        return this.detail
                + (this.isClearingBlocker() ? " blocker" : "")
                + " @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ();
    }

    private void updateDetail() {
        String currentDetail = this.detail();
        if (!currentDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(currentDetail);
        }
    }

    public static List<BlockPos> gatherObstructionCandidates(BlockPos feet, BlockPos standPos, BlockPos targetPos) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        addBodyColumn(candidates, feet);
        if (feet != null
                && ((standPos != null && standPos.getY() < feet.getY())
                || (targetPos != null && targetPos.getY() < feet.getY()))) {
            candidates.add(feet.below());
        }
        if (standPos != null) {
            addBodyColumn(candidates, standPos);
            addLineCandidates(candidates, feet, standPos, 8);
            addLineCandidates(candidates, feet.above(), standPos.above(), 8);
        }
        if (targetPos != null) {
            addLineCandidates(candidates, feet.above(), targetPos, 8);
            candidates.add(targetPos.below());
            candidates.add(targetPos.above());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos side = targetPos.relative(direction);
                candidates.add(side);
                candidates.add(side.below());
            }
        }
        return candidates;
    }

    public static Optional<BlockPos> findNearestClearable(
            ServerLevel serverLevel,
            BlockPos origin,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr
    ) {
        return candidates.stream()
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> pos.distSqr(origin) <= maxDistanceSqr)
                .filter(pos -> isClearable(serverLevel, pos, targetPredicate))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
    }

    public static Optional<BlockPos> findNearestAccessibleClearable(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr
    ) {
        return findNearestAccessibleClearable(serverLevel, playerNpc, candidates, targetPredicate, maxDistanceSqr, false);
    }

    public static Optional<BlockPos> findNearestAccessibleClearable(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        return selectNearestAccessibleClearable(
                serverLevel,
                playerNpc,
                candidates,
                targetPredicate,
                maxDistanceSqr,
                allowSoftColumnCover,
                new NavigationPathBudget(MAX_BREAK_STAND_PATH_CHECKS),
                0
        ).plan().map(ClearTargetPlan::target);
    }

    private static ClearTargetSelection selectNearestAccessibleClearable(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftColumnCover,
            NavigationPathBudget pathBudget,
            int startCandidateIndex
    ) {
        BlockPos origin = playerNpc.blockPosition();
        Set<BlockPos> seen = new HashSet<>();
        ArrayList<BlockPos> clearable = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || immutable.distSqr(origin) > maxDistanceSqr
                    || !isClearable(serverLevel, immutable, targetPredicate, allowSoftColumnCover)) {
                continue;
            }
            clearable.add(immutable);
        }

        clearable.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        int candidateCount = clearable.size();
        if (candidateCount <= 0) {
            return new ClearTargetSelection(Optional.empty(), 0, 0, 0);
        }
        int startIndex = Math.floorMod(startCandidateIndex, candidateCount);
        int maxCandidates = Math.min(MAX_ACCESSIBLE_CLEAR_CANDIDATES, candidateCount);
        int nextCandidateIndex = (startIndex + maxCandidates) % candidateCount;
        int pathsAttempted = 0;
        for (int offset = 0; offset < maxCandidates; offset++) {
            int candidateIndex = (startIndex + offset) % candidateCount;
            BlockPos candidate = clearable.get(candidateIndex);
            if (findBreakRayBlocker(
                    serverLevel,
                    playerNpc,
                    candidate,
                    targetPredicate,
                    maxDistanceSqr,
                    allowSoftColumnCover
            ).isPresent()) {
                return new ClearTargetSelection(
                        Optional.of(new ClearTargetPlan(candidate, null, null)),
                        (candidateIndex + 1) % candidateCount,
                        pathsAttempted,
                        maxCandidates
                );
            }
            if (canBreakFromCurrentPosition(serverLevel, playerNpc, candidate, allowSoftColumnCover)) {
                return new ClearTargetSelection(
                        Optional.of(new ClearTargetPlan(candidate, null, null)),
                        (candidateIndex + 1) % candidateCount,
                        pathsAttempted,
                        maxCandidates
                );
            }
            Optional<BlockPos> cover = findColumnCover(serverLevel, playerNpc, candidate, targetPredicate, maxDistanceSqr, allowSoftColumnCover);
            if (cover.isPresent()) {
                return new ClearTargetSelection(
                        Optional.of(new ClearTargetPlan(cover.get(), null, null)),
                        (candidateIndex + 1) % candidateCount,
                        pathsAttempted,
                        maxCandidates
                );
            }
            if (pathBudget.exhausted()) {
                continue;
            }
            nextCandidateIndex = (candidateIndex + 1) % candidateCount;
            int remainingBefore = pathBudget.remaining();
            Optional<ReachableBreakStand> stand = findReachableBreakStandPlan(
                    playerNpc,
                    serverLevel,
                    candidate,
                    allowSoftColumnCover,
                    pathBudget,
                    1
            );
            pathsAttempted += Math.max(0, remainingBefore - pathBudget.remaining());
            if (stand.isPresent()) {
                return new ClearTargetSelection(
                        Optional.of(new ClearTargetPlan(candidate, stand.get().stand(), stand.get().path())),
                        nextCandidateIndex,
                        pathsAttempted,
                        maxCandidates
                );
            }
        }
        return new ClearTargetSelection(Optional.empty(), nextCandidateIndex, pathsAttempted, maxCandidates);
    }

    private static int clearSelectionSignature(
            Collection<BlockPos> candidates,
            String detail,
            double clearDistanceSqr,
            boolean allowSoftColumnCover,
            boolean allowOwnedFarmDestruction
    ) {
        int result = candidates == null ? 0 : candidates.hashCode();
        result = 31 * result + (detail == null ? 0 : detail.hashCode());
        long distanceBits = Double.doubleToLongBits(clearDistanceSqr);
        result = 31 * result + (int) (distanceBits ^ distanceBits >>> 32);
        result = 31 * result + Boolean.hashCode(allowSoftColumnCover);
        return 31 * result + Boolean.hashCode(allowOwnedFarmDestruction);
    }

    /**
     * Resolves the block a clear request would initially hit from the NPC's current eye
     * position. Clear requests deliberately remember their requested target so they can
     * resume it after a ray blocker is removed; callers that protect owned structures can
     * use this view to validate that blocker before starting the request.
     */
    public static Optional<BlockPos> resolveInitialClearTarget(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos requestedTarget,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        if (serverLevel == null
                || playerNpc == null
                || requestedTarget == null
                || targetPredicate == null
                || playerNpc.distanceToSqr(centerX(requestedTarget), centerY(requestedTarget), centerZ(requestedTarget))
                > maxDistanceSqr
                || !isClearable(serverLevel, requestedTarget, targetPredicate, allowSoftColumnCover)) {
            return Optional.empty();
        }
        return findBreakRayBlocker(
                serverLevel,
                playerNpc,
                requestedTarget,
                targetPredicate,
                maxDistanceSqr,
                allowSoftColumnCover
        ).or(() -> Optional.of(requestedTarget.immutable()));
    }

    private static Optional<BlockPos> findColumnCover(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        BlockPos origin = playerNpc.blockPosition();
        if (targetPos == null || targetPos.getY() >= origin.getY()) {
            return Optional.empty();
        }

        int topY = Math.min(origin.getY(), serverLevel.getMaxY());
        for (int y = topY; y > targetPos.getY(); y--) {
            BlockPos cover = new BlockPos(targetPos.getX(), y, targetPos.getZ());
            if (!serverLevel.hasChunkAt(cover)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(cover);
            boolean targetMatch = targetPredicate.test(state);
            boolean softCover = allowSoftColumnCover
                    && isPartialShapePathObstruction(serverLevel, cover, state);
            if (playerNpc.distanceToSqr(centerX(cover), centerY(cover), centerZ(cover)) > maxDistanceSqr
                    || !targetMatch && !softCover
                    || !isBreakablePathObstruction(serverLevel, cover, state, softCover)) {
                continue;
            }
            return Optional.of(cover.immutable());
        }
        return Optional.empty();
    }

    public static boolean isBreakablePathObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return isBreakablePathObstruction(serverLevel, pos, state, false);
    }

    public static boolean isBreakablePathObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state, boolean allowSoftCover) {
        if (serverLevel == null
                || pos == null
                || state == null
                || !serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)
                || state.isAir()
                || state.getDestroySpeed(serverLevel, pos) < 0.0F
                || !state.getFluidState().isEmpty()
                || serverLevel.getBlockEntity(pos) != null) {
            return false;
        }

        return !state.getCollisionShape(serverLevel, pos).isEmpty()
                || allowSoftCover && isPartialShapePathObstruction(serverLevel, pos, state);
    }

    private static boolean isPotentialPartialShapeState(BlockState state) {
        return state != null
                && !state.isAir()
                && (state.canBeReplaced() || !state.canOcclude());
    }

    private static boolean isPartialShapePathObstruction(
            ServerLevel serverLevel,
            BlockPos pos,
            BlockState state
    ) {
        if (serverLevel == null || pos == null || state == null || !serverLevel.hasChunkAt(pos)) {
            return false;
        }
        VoxelShape collision = state.getCollisionShape(serverLevel, pos);
        VoxelShape outline = state.getShape(serverLevel, pos);
        return isPotentialPartialShapeState(state)
                || collision.isEmpty()
                || !Block.isShapeFullBlock(collision)
                || outline.isEmpty()
                || !Block.isShapeFullBlock(outline);
    }

    private static boolean isClearable(ServerLevel serverLevel, BlockPos pos, Predicate<BlockState> targetPredicate) {
        return isClearable(serverLevel, pos, targetPredicate, false);
    }

    private static boolean isClearable(ServerLevel serverLevel, BlockPos pos, Predicate<BlockState> targetPredicate, boolean allowSoftCover) {
        if (pos == null
                || targetPredicate == null
                || !serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return targetPredicate.test(state)
                && isBreakablePathObstruction(serverLevel, pos, state, allowSoftCover);
    }

    private boolean moveNearTarget(ServerLevel serverLevel, boolean pathWorkAllowed) {
        if (this.targetPos == null) {
            return false;
        }

        if (canBreakFromCurrentPosition(serverLevel, this.playerNpc, this.targetPos, this.allowSoftCover)) {
            this.plannedApproachPath = null;
            this.resetRunningStandSelection();
            this.approachTicks = 0;
            this.centeredBlockedStandTicks = 0;
            return true;
        }

        if (this.standPos == null
                || !canUseBreakStand(serverLevel, this.standPos, this.targetPos, this.allowSoftCover)) {
            this.plannedApproachPath = null;
            if (this.playerNpc.tickCount < this.nextRunningStandSelectionTick) {
                return true;
            }
            if (!pathWorkAllowed) {
                this.deferDeniedPathWork();
                return true;
            }
            ReachableBreakStandSelection selection = selectReachableBreakStandPlan(
                    this.playerNpc,
                    serverLevel,
                    this.targetPos,
                    this.allowSoftCover,
                    new NavigationPathBudget(MAX_RUNNING_CLEAR_STAND_PATHS),
                    MAX_BREAK_STAND_PATH_CHECKS,
                    this.runningStandSelectionCursor
            );
            this.runningStandSelectionCursor = selection.nextCandidateIndex();
            this.runningStandCandidateCount = selection.pathCandidateCount();
            this.runningStandCandidatesExamined += selection.pathsAttempted();
            if (selection.plan().isEmpty()) {
                if (selection.pathsAttempted() <= 0
                        || this.runningStandCandidatesExamined >= this.runningStandCandidateCount) {
                    this.resetRunningStandSelection();
                    return false;
                }
                this.nextRunningStandSelectionTick = this.playerNpc.tickCount
                        + RUNNING_STAND_SELECTION_RETRY_TICKS;
                return true;
            }
            ReachableBreakStand stand = selection.plan().get();
            this.standPos = stand.stand();
            this.plannedApproachPath = stand.path();
            if (selection.pathsAttempted() <= 0) {
                this.runningStandCandidatesExamined++;
            }
            this.nextRunningStandSelectionTick = 0;
            this.approachRepathTicks = 0;
        }
        if (this.standPos == null) {
            return false;
        }
        if (++this.approachTicks > MAX_APPROACH_TICKS) {
            this.standPos = null;
            this.approachTicks = 0;
            return this.deferRunningStandSelection();
        }

        this.playerNpc.getLookControl().setLookAt(
                centerX(this.targetPos),
                centerY(this.targetPos),
                centerZ(this.targetPos),
                35.0F,
                35.0F
        );

        if (isAtBreakStand(this.playerNpc, this.standPos)) {
            this.plannedApproachPath = null;
            this.playerNpc.getNavigation().stop();
            if (this.isCenteredOnBreakStand() && ++this.centeredBlockedStandTicks > MAX_CENTERED_BLOCKED_STAND_TICKS) {
                this.standPos = null;
                this.centeredBlockedStandTicks = 0;
                return this.deferRunningStandSelection();
            }
            this.nudgeTowardBreakStandCenter();
            return true;
        }
        this.centeredBlockedStandTicks = 0;
        if (this.approachRepathTicks > 0
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck()) {
            this.approachRepathTicks--;
            return true;
        }

        Path path = this.plannedApproachPath;
        this.plannedApproachPath = null;
        if (path == null) {
            if (this.playerNpc.tickCount < this.nextRunningStandSelectionTick) {
                return true;
            }
            if (!pathWorkAllowed) {
                this.deferDeniedPathWork();
                return true;
            }
            path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    this.standPos,
                    CLEAR_STAND_PATH_NODE_MULTIPLIER
            );
            this.nextRunningStandSelectionTick = 0;
        }
        if (!isUsablePathToStand(path, this.standPos)) {
            this.standPos = null;
            return this.deferRunningStandSelection();
        }
        this.approachRepathTicks = APPROACH_REPATH_INTERVAL_TICKS;
        if (!this.playerNpc.getNavigation().moveTo(path, 1.0D)) {
            this.standPos = null;
            return this.deferRunningStandSelection();
        }
        return true;
    }

    private void deferDeniedPathWork() {
        this.nextRunningStandSelectionTick = this.playerNpc.tickCount
                + 1 + this.playerNpc.getRandom().nextInt(DENIED_PATH_RETRY_MAX_TICKS);
    }

    private boolean deferRunningStandSelection() {
        this.plannedApproachPath = null;
        if (this.runningStandCandidateCount <= 0
                || this.runningStandCandidatesExamined >= this.runningStandCandidateCount) {
            this.resetRunningStandSelection();
            return false;
        }
        this.nextRunningStandSelectionTick = this.playerNpc.tickCount
                + RUNNING_STAND_SELECTION_RETRY_TICKS;
        return true;
    }

    private void retargetBlocker(BlockPos blockerPos) {
        this.targetPos = blockerPos.immutable();
        this.standPos = null;
        this.plannedApproachPath = null;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.centeredBlockedStandTicks = 0;
        this.resetRunningStandSelection();
        this.playerNpc.getNavigation().stop();
    }

    private boolean resumeRequestedTarget(ServerLevel serverLevel) {
        if (!this.isClearingBlocker()
                || !isClearable(serverLevel, this.requestedTargetPos, this.targetPredicate, this.allowSoftCover)) {
            return false;
        }

        this.targetPos = this.requestedTargetPos;
        this.standPos = null;
        this.plannedApproachPath = null;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.centeredBlockedStandTicks = 0;
        this.resetRunningStandSelection();
        this.playerNpc.getNavigation().stop();
        return true;
    }

    private boolean isClearingBlocker() {
        return this.requestedTargetPos != null
                && this.targetPos != null
                && !this.requestedTargetPos.equals(this.targetPos);
    }

    /**
     * Finds a bounded, exactly reachable stand whose centered eye ray first hits the
     * requested block. At most {@value #MAX_BREAK_STAND_PATH_CHECKS} paths are probed.
     */
    public static Optional<BlockPos> findReachableBreakStand(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos targetPos
    ) {
        return findReachableBreakStandPlan(
                playerNpc,
                serverLevel,
                targetPos,
                false,
                new NavigationPathBudget(MAX_BREAK_STAND_PATH_CHECKS),
                MAX_BREAK_STAND_PATH_CHECKS
        ).map(ReachableBreakStand::stand);
    }

    private static Optional<ReachableBreakStand> findReachableBreakStandPlan(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos targetPos,
            boolean allowSoftCover,
            NavigationPathBudget pathBudget,
            int maximumPathsForTarget
    ) {
        return selectReachableBreakStandPlan(
                playerNpc,
                serverLevel,
                targetPos,
                allowSoftCover,
                pathBudget,
                maximumPathsForTarget,
                0
        ).plan();
    }

    private static ReachableBreakStandSelection selectReachableBreakStandPlan(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos targetPos,
            boolean allowSoftCover,
            NavigationPathBudget pathBudget,
            int maximumPathsForTarget,
            int startCandidateIndex
    ) {
        if (playerNpc == null || serverLevel == null || targetPos == null || pathBudget == null) {
            return new ReachableBreakStandSelection(Optional.empty(), 0, 0, 0);
        }
        ArrayList<BlockPos> candidates = new ArrayList<>();
        BlockPos playerFeet = playerNpc.blockPosition();
        if (canUseBreakStand(serverLevel, playerFeet, targetPos, allowSoftCover)) {
            candidates.add(playerFeet.immutable());
        }

        for (int dx = -BREAK_STAND_RADIUS; dx <= BREAK_STAND_RADIUS; dx++) {
            for (int dy = -BREAK_STAND_VERTICAL_RANGE; dy <= BREAK_STAND_VERTICAL_RANGE; dy++) {
                for (int dz = -BREAK_STAND_RADIUS; dz <= BREAK_STAND_RADIUS; dz++) {
                    BlockPos candidate = targetPos.offset(dx, dy, dz);
                    if (candidate.equals(targetPos)
                            || !canUseBreakStand(serverLevel, candidate, targetPos, allowSoftCover)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(pos -> playerNpc.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY(),
                pos.getZ() + 0.5D
        )));

        int candidateCount = Math.min(Math.max(0, maximumPathsForTarget), candidates.size());
        if (candidateCount <= 0) {
            return new ReachableBreakStandSelection(Optional.empty(), 0, 0, 0);
        }
        int startIndex = Math.floorMod(startCandidateIndex, candidateCount);
        int nextCandidateIndex = startIndex;
        int pathsAttempted = 0;
        for (int offset = 0; offset < candidateCount; offset++) {
            int candidateIndex = (startIndex + offset) % candidateCount;
            BlockPos candidate = candidates.get(candidateIndex);
            if (isAtBreakStand(playerNpc, candidate)) {
                return new ReachableBreakStandSelection(
                        Optional.of(new ReachableBreakStand(candidate, null)),
                        (candidateIndex + 1) % candidateCount,
                        pathsAttempted,
                        candidateCount
                );
            }
            if (!pathBudget.tryConsume()) {
                break;
            }
            pathsAttempted++;
            nextCandidateIndex = (candidateIndex + 1) % candidateCount;
            Path path = PathNavigationAi.createBoundedPath(
                    playerNpc,
                    candidate,
                    CLEAR_STAND_PATH_NODE_MULTIPLIER
            );
            if (isUsablePathToStand(path, candidate)) {
                return new ReachableBreakStandSelection(
                        Optional.of(new ReachableBreakStand(candidate, path)),
                        nextCandidateIndex,
                        pathsAttempted,
                        candidateCount
                );
            }
        }
        return new ReachableBreakStandSelection(
                Optional.empty(),
                nextCandidateIndex,
                pathsAttempted,
                candidateCount
        );
    }

    private static boolean canUseBreakStand(ServerLevel serverLevel, BlockPos standPos, BlockPos targetPos) {
        return canUseBreakStand(serverLevel, standPos, targetPos, false);
    }

    private static boolean canUseBreakStand(
            ServerLevel serverLevel,
            BlockPos standPos,
            BlockPos targetPos,
            boolean allowSoftCover
    ) {
        return PathNavigationAi.canStandAt(serverLevel, standPos)
                && distanceFromStandToTargetSqr(standPos, targetPos) <= BREAK_REACH_DISTANCE_SQR
                && hasClearBreakRay(serverLevel, standPos, targetPos, allowSoftCover);
    }

    private static boolean isWithinBreakReach(PlayerNpcEntity playerNpc, BlockPos targetPos) {
        double dx = playerNpc.getX() - centerX(targetPos);
        double dy = playerNpc.getEyeY() - centerY(targetPos);
        double dz = playerNpc.getZ() - centerZ(targetPos);
        return dx * dx + dy * dy + dz * dz <= BREAK_REACH_DISTANCE_SQR;
    }

    private static boolean canBreakFromCurrentPosition(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos targetPos) {
        return canBreakFromCurrentPosition(serverLevel, playerNpc, targetPos, false);
    }

    /** Cheap geometric/raycast preflight for callers that intentionally forbid clear-route A*. */
    public static boolean canBreakFromCurrentStand(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos
    ) {
        return canBreakFromCurrentPosition(serverLevel, playerNpc, targetPos, false);
    }

    /**
     * Cheap geometric/raycast preflight for callers whose clear contract explicitly permits
     * partial-shape cover such as flowers, mushrooms, and other replaceable farm vegetation.
     */
    public static boolean canBreakFromCurrentStand(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos,
            boolean allowSoftCover
    ) {
        return canBreakFromCurrentPosition(serverLevel, playerNpc, targetPos, allowSoftCover);
    }

    private static boolean canBreakFromCurrentPosition(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos,
            boolean allowSoftCover
    ) {
        return isWithinBreakReach(playerNpc, targetPos)
                && (hasClearBreakRay(serverLevel, playerNpc, targetPos, allowSoftCover)
                || isImmediateBodyObstruction(serverLevel, playerNpc, targetPos));
    }

    private static boolean isImmediateBodyObstruction(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos targetPos) {
        BlockPos feet = playerNpc.blockPosition();
        int dx = Math.abs(targetPos.getX() - feet.getX());
        int dy = targetPos.getY() - feet.getY();
        int dz = Math.abs(targetPos.getZ() - feet.getZ());
        boolean supportBelow = dx == 0 && dz == 0 && dy == -1;
        if (!supportBelow && (dy < 0 || dy > 2 || dx + dz > 1)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(targetPos);
        return isBreakablePathObstruction(serverLevel, targetPos, state, true);
    }

    private static boolean hasClearBreakRay(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos targetPos) {
        return hasClearBreakRay(serverLevel, playerNpc, targetPos, false);
    }

    private static boolean hasClearBreakRay(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos,
            boolean allowSoftCover
    ) {
        return hasClearBreakRay(
                serverLevel,
                new Vec3(playerNpc.getX(), playerNpc.getEyeY(), playerNpc.getZ()),
                targetPos,
                allowSoftCover
        );
    }

    private static boolean hasClearBreakRay(ServerLevel serverLevel, BlockPos standPos, BlockPos targetPos) {
        return hasClearBreakRay(serverLevel, standPos, targetPos, false);
    }

    private static boolean hasClearBreakRay(
            ServerLevel serverLevel,
            BlockPos standPos,
            BlockPos targetPos,
            boolean allowSoftCover
    ) {
        return hasClearBreakRay(
                serverLevel,
                new Vec3(standPos.getX() + 0.5D, standPos.getY() + STAND_EYE_HEIGHT, standPos.getZ() + 0.5D),
                targetPos,
                allowSoftCover
        );
    }

    private static boolean hasClearBreakRay(ServerLevel serverLevel, Vec3 eye, BlockPos targetPos) {
        return hasClearBreakRay(serverLevel, eye, targetPos, false);
    }

    private static boolean hasClearBreakRay(
            ServerLevel serverLevel,
            Vec3 eye,
            BlockPos targetPos,
            boolean allowSoftCover
    ) {
        BlockHitResult hit = clipBreakRay(serverLevel, eye, targetPos);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().equals(targetPos);
        }
        // Some modded terrain deliberately exposes no outline. A MISS proves the same OUTLINE
        // segment encountered no wall; only explicit partial-shape callers may use that result,
        // and only when the loaded/revalidated target still has an empty outline.
        if (!allowSoftCover
                || hit.getType() != HitResult.Type.MISS
                || !serverLevel.hasChunkAt(targetPos)) {
            return false;
        }
        BlockState targetState = serverLevel.getBlockState(targetPos);
        return targetState.getShape(serverLevel, targetPos).isEmpty()
                && isPartialShapePathObstruction(serverLevel, targetPos, targetState);
    }

    private static Optional<BlockPos> findBreakRayBlocker(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftCover
    ) {
        if (targetPos == null || targetPredicate == null) {
            return Optional.empty();
        }

        Vec3 eye = new Vec3(playerNpc.getX(), playerNpc.getEyeY(), playerNpc.getZ());
        BlockHitResult hit = clipBreakRay(serverLevel, eye, targetPos);
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(targetPos)) {
            return Optional.empty();
        }

        BlockPos blockerPos = hit.getBlockPos();
        // A ray to a lower block can cross the block currently supporting the NPC.
        // Treating that support as an ordinary cover block makes the clear request
        // alternate between its real target and the floor under the NPC. Leave the
        // support in place and let moveNearTarget choose a safe break stand instead.
        if (blockerPos.equals(playerNpc.blockPosition().below())) {
            return Optional.empty();
        }
        BlockState blockerState = serverLevel.getBlockState(blockerPos);
        if (!targetPredicate.test(blockerState)
                || playerNpc.distanceToSqr(centerX(blockerPos), centerY(blockerPos), centerZ(blockerPos)) > maxDistanceSqr
                || !isBreakablePathObstruction(serverLevel, blockerPos, blockerState, allowSoftCover)) {
            return Optional.empty();
        }
        return Optional.of(blockerPos.immutable());
    }

    private static BlockHitResult clipBreakRay(ServerLevel serverLevel, Vec3 eye, BlockPos targetPos) {
        return serverLevel.clip(new ClipContext(
                eye,
                breakRayTarget(serverLevel, eye, targetPos),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()
        ));
    }

    private static Vec3 breakRayTarget(ServerLevel serverLevel, Vec3 eye, BlockPos targetPos) {
        if (!serverLevel.hasChunkAt(targetPos)) {
            return Vec3.atCenterOf(targetPos);
        }
        VoxelShape outline = serverLevel.getBlockState(targetPos).getShape(serverLevel, targetPos);
        if (outline.isEmpty()) {
            return Vec3.atCenterOf(targetPos);
        }

        // Aim inside the nearest real outline component rather than at the block-cell center.
        // Mushrooms, petals, slabs, fences, and modded multipart terrain may not occupy that cell
        // center. ClipContext still reports the first outline hit, so an intervening wall rejects
        // the request before the intended partial shape can be reached.
        List<AABB> components = outline.toAabbs();
        AABB nearest = null;
        double nearestDistanceSqr = Double.MAX_VALUE;
        int componentLimit = Math.min(MAX_BREAK_RAY_SHAPE_COMPONENTS, components.size());
        for (int index = 0; index < componentLimit; index++) {
            AABB component = components.get(index);
            double distanceSqr = distanceToShapeCenterSqr(eye, targetPos, component);
            if (distanceSqr < nearestDistanceSqr) {
                nearest = component;
                nearestDistanceSqr = distanceSqr;
            }
        }
        if (nearest == null) {
            return Vec3.atCenterOf(targetPos);
        }
        return new Vec3(
                targetPos.getX() + (nearest.minX + nearest.maxX) * 0.5D,
                targetPos.getY() + (nearest.minY + nearest.maxY) * 0.5D,
                targetPos.getZ() + (nearest.minZ + nearest.maxZ) * 0.5D
        );
    }

    private static double distanceToShapeCenterSqr(Vec3 eye, BlockPos targetPos, AABB box) {
        double dx = eye.x - (targetPos.getX() + (box.minX + box.maxX) * 0.5D);
        double dy = eye.y - (targetPos.getY() + (box.minY + box.maxY) * 0.5D);
        double dz = eye.z - (targetPos.getZ() + (box.minZ + box.maxZ) * 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isAtBreakStand(PlayerNpcEntity playerNpc, BlockPos standPos) {
        return playerNpc.blockPosition().getY() == standPos.getY()
                && playerNpc.distanceToSqr(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D)
                <= BREAK_STAND_REACHED_SQR;
    }

    private void nudgeTowardBreakStandCenter() {
        if (this.standPos == null || this.isCenteredOnBreakStand()) {
            return;
        }

        this.playerNpc.getMoveControl().setWantedPosition(
                this.standPos.getX() + 0.5D,
                this.playerNpc.getY(),
                this.standPos.getZ() + 0.5D,
                1.0D
        );
    }

    private boolean isCenteredOnBreakStand() {
        double dx = this.playerNpc.getX() - (this.standPos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (this.standPos.getZ() + 0.5D);
        return dx * dx + dz * dz <= BREAK_STAND_CENTERED_SQR;
    }

    private static double distanceFromStandToTargetSqr(BlockPos standPos, BlockPos targetPos) {
        double dx = standPos.getX() + 0.5D - centerX(targetPos);
        double dy = standPos.getY() + STAND_EYE_HEIGHT - centerY(targetPos);
        double dz = standPos.getZ() + 0.5D - centerZ(targetPos);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isUsablePathToStand(Path path, BlockPos standPos) {
        if (path == null || !path.canReach()) {
            return false;
        }
        Node endNode = path.getEndNode();
        return endNode != null && endNode.asBlockPos().equals(standPos);
    }

    private record ClearTargetPlan(BlockPos target, BlockPos stand, Path path) {
    }

    private record ClearTargetSelection(
            Optional<ClearTargetPlan> plan,
            int nextCandidateIndex,
            int pathsAttempted,
            int pathCandidateCount
    ) {
    }

    private record ReachableBreakStand(BlockPos stand, Path path) {
    }

    private record ReachableBreakStandSelection(
            Optional<ReachableBreakStand> plan,
            int nextCandidateIndex,
            int pathsAttempted,
            int pathCandidateCount
    ) {
    }

    private static final class NavigationPathBudget {
        private int remaining;

        private NavigationPathBudget(int maximumPaths) {
            this.remaining = Math.max(0, maximumPaths);
        }

        private boolean exhausted() {
            return this.remaining <= 0;
        }

        private int remaining() {
            return this.remaining;
        }

        private boolean tryConsume() {
            if (this.exhausted()) {
                return false;
            }
            this.remaining--;
            return true;
        }
    }

    private static void addBodyColumn(List<BlockPos> candidates, BlockPos feet) {
        if (feet == null) {
            return;
        }
        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
    }

    private static void addLineCandidates(List<BlockPos> candidates, BlockPos start, BlockPos target, int maxSteps) {
        double dx = target.getX() - start.getX();
        double dy = target.getY() - start.getY();
        double dz = target.getZ() - start.getZ();
        int steps = Math.max(1, Math.min(maxSteps, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))))));
        for (int i = 1; i <= steps; i++) {
            double progress = i / (double) steps;
            int x = start.getX() + (int) Math.round(dx * progress);
            int y = start.getY() + (int) Math.round(dy * progress);
            int z = start.getZ() + (int) Math.round(dz * progress);
            candidates.add(new BlockPos(x, y, z));
        }
    }

    private static double centerX(BlockPos pos) {
        return pos.getX() + 0.5D;
    }

    private static double centerY(BlockPos pos) {
        return pos.getY() + 0.5D;
    }

    private static double centerZ(BlockPos pos) {
        return pos.getZ() + 0.5D;
    }

}
