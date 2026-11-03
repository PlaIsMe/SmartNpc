package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PathNavigationAi {
    private static final double PATH_END_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int PATH_END_VERTICAL_TOLERANCE = 2;
    private static final double DIRECT_STEP_REACHED_SQR = 0.75D * 0.75D;
    private static final int MAX_LOCAL_ROUTE_PATH_CHECKS = 16;
    private static final double LOCAL_ROUTE_MAX_BACKTRACK_SQR = 8.0D * 8.0D;

    private final PlayerNpcEntity playerNpc;
    private final WaterEscapeAi waterEscapeAi;
    private BlockPos lastLocalRouteTarget;
    private BlockPos lastUphillPartialRouteTarget;
    private String lastMoveFailureDetail = "";
    private int lastLocalCandidateCount;
    private int lastLocalPathChecks;

    public PathNavigationAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.waterEscapeAi = new WaterEscapeAi(playerNpc);
    }

    /**
     * Creates a diagnostic/selection path with a caller-owned node ceiling. Minecraft path
     * creation is synchronous, so even one admitted probe can otherwise spend hundreds of
     * milliseconds exploring a pathological local route. Always restore the navigation default;
     * the retained Path may be followed normally after this method returns.
     */
    public static Path createBoundedPath(
            PlayerNpcEntity playerNpc,
            BlockPos target,
            float maxVisitedNodesMultiplier
    ) {
        if (playerNpc == null || target == null) {
            return null;
        }
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)
                || !hasLoadedChunkCorridor(serverLevel, playerNpc.blockPosition(), target, 1)) {
            return null;
        }

        var navigation = playerNpc.getNavigation();
        navigation.setMaxVisitedNodesMultiplier(Math.max(0.001F, Math.min(1.0F, maxVisitedNodesMultiplier)));
        try {
            int dx = target.getX() - playerNpc.getBlockX();
            int dz = target.getZ() - playerNpc.getBlockZ();
            int horizontalDistance = (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz));
            // PlayerNpc FOLLOW_RANGE is 48, which makes vanilla inspect a 56-block region for
            // every createPath even when the candidate is local. Keep diagnostic routes local;
            // callers retry/reselect a farther target instead of constructing that huge region.
            if (horizontalDistance > 48) {
                return null;
            }
            int localFollowRange = Math.max(8, Math.min(48, horizontalDistance + 6));
            return navigation.createPath(target, 0, localFollowRange);
        } finally {
            navigation.resetMaxVisitedNodesMultiplier();
        }
    }

    /**
     * PathNavigation constructs a region wider than the route itself. Reject speculative paths
     * whose route corridor touches an unloaded chunk so EmptyLevelChunk fringes cannot turn a
     * futile activation probe into a large synchronous region/path build.
     * Recovery callers can preflight this before treating a null bounded path as a failed
     * walking route; an unloaded corridor instead leaves reachability unknown.
     */
    public static boolean hasLoadedChunkCorridor(
            ServerLevel serverLevel,
            BlockPos from,
            BlockPos to,
            int marginChunks
    ) {
        int fromX = from.getX() >> 4;
        int fromZ = from.getZ() >> 4;
        int toX = to.getX() >> 4;
        int toZ = to.getZ() >> 4;
        int steps = Math.max(Math.abs(toX - fromX), Math.abs(toZ - fromZ));
        for (int step = 0; step <= steps; step++) {
            double progress = steps == 0 ? 0.0D : (double) step / (double) steps;
            int chunkX = (int) Math.round(fromX + (toX - fromX) * progress);
            int chunkZ = (int) Math.round(fromZ + (toZ - fromZ) * progress);
            for (int dx = -marginChunks; dx <= marginChunks; dx++) {
                for (int dz = -marginChunks; dz <= marginChunks; dz++) {
                    if (!serverLevel.hasChunk(chunkX + dx, chunkZ + dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public boolean moveTo(ServerLevel serverLevel, BlockPos target, double speed, int maxSafeDrop) {
        return this.moveTo(serverLevel, target, speed, maxSafeDrop, 1.0F);
    }

    public boolean moveTo(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            float maxVisitedNodesMultiplier
    ) {
        return this.moveToInternal(serverLevel, target, speed, maxSafeDrop, maxVisitedNodesMultiplier, false);
    }

    public boolean moveToAllowingUsefulUphillPartial(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop
    ) {
        return this.moveToInternal(serverLevel, target, speed, maxSafeDrop, 1.0F, true);
    }

    private boolean moveToInternal(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            float maxVisitedNodesMultiplier,
            boolean allowUsefulUphillPartial
    ) {
        this.lastUphillPartialRouteTarget = null;
        if (this.escapeWaterIfNeeded(serverLevel, target, speed)) {
            return true;
        }

        if (this.isAlreadyAtTarget(target)) {
            this.lastMoveFailureDetail = "";
            this.playerNpc.getNavigation().stop();
            return true;
        }

        if (this.shouldStepDownBeforePath(target, maxSafeDrop)
                && this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop)) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        Path path = createBoundedPath(this.playerNpc, target, maxVisitedNodesMultiplier);
        boolean usefulUphillPartial = allowUsefulUphillPartial
                && this.isUsefulSafeUphillPartialPath(serverLevel, target, path, maxSafeDrop);
        if (this.isValidPathTo(target, path) || usefulUphillPartial) {
            this.lastMoveFailureDetail = "";
            if (this.playerNpc.getNavigation().moveTo(path, speed)) {
                if (usefulUphillPartial) {
                    this.lastUphillPartialRouteTarget = path.getEndNode().asBlockPos().immutable();
                }
                return true;
            }
        }

        boolean movedToSafeDrop = this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop);
        if (movedToSafeDrop) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        this.lastMoveFailureDetail = this.pathDebug(target, path) + " safeDrop=none";
        return false;
    }

    public boolean moveToExact(ServerLevel serverLevel, BlockPos target, double speed, int maxSafeDrop) {
        return this.moveToExact(serverLevel, target, speed, maxSafeDrop, 1.0F);
    }

    public boolean moveToExact(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            float maxVisitedNodesMultiplier
    ) {
        if (this.escapeWaterIfNeeded(serverLevel, target, speed)) {
            return true;
        }

        if (this.isAlreadyAtTarget(target)) {
            this.lastMoveFailureDetail = "";
            this.playerNpc.getNavigation().stop();
            return true;
        }

        if (this.shouldStepDownBeforePath(target, maxSafeDrop)
                && this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop)) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        Path path = createBoundedPath(this.playerNpc, target, maxVisitedNodesMultiplier);
        if (this.isExactPathTo(target, path)) {
            this.lastMoveFailureDetail = "";
            return this.playerNpc.getNavigation().moveTo(path, speed);
        }

        boolean movedToSafeDrop = this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop);
        if (movedToSafeDrop) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        this.lastMoveFailureDetail = this.pathDebug(target, path) + " exact=false safeDrop=none";
        return false;
    }

    public boolean moveToWithLocalFallback(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            int horizontalRadius,
            int verticalDown,
            int verticalUp) {
        return this.moveToWithLocalFallback(
                serverLevel,
                target,
                speed,
                maxSafeDrop,
                horizontalRadius,
                verticalDown,
                verticalUp,
                MAX_LOCAL_ROUTE_PATH_CHECKS,
                1.0F
        );
    }

    public boolean moveToWithLocalFallback(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            int horizontalRadius,
            int verticalDown,
            int verticalUp,
            boolean allowUsefulUphillPartial) {
        return this.moveToWithLocalFallback(
                serverLevel,
                target,
                speed,
                maxSafeDrop,
                horizontalRadius,
                verticalDown,
                verticalUp,
                MAX_LOCAL_ROUTE_PATH_CHECKS,
                1.0F,
                allowUsefulUphillPartial
        );
    }

    public boolean moveToWithLocalFallback(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            int horizontalRadius,
            int verticalDown,
            int verticalUp,
            int maxLocalPathChecks) {
        return this.moveToWithLocalFallback(
                serverLevel,
                target,
                speed,
                maxSafeDrop,
                horizontalRadius,
                verticalDown,
                verticalUp,
                maxLocalPathChecks,
                1.0F
        );
    }

    public boolean moveToWithLocalFallback(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            int horizontalRadius,
            int verticalDown,
            int verticalUp,
            int maxLocalPathChecks,
            float maxVisitedNodesMultiplier) {
        return this.moveToWithLocalFallback(
                serverLevel,
                target,
                speed,
                maxSafeDrop,
                horizontalRadius,
                verticalDown,
                verticalUp,
                maxLocalPathChecks,
                maxVisitedNodesMultiplier,
                false
        );
    }

    private boolean moveToWithLocalFallback(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            int horizontalRadius,
            int verticalDown,
            int verticalUp,
            int maxLocalPathChecks,
            float maxVisitedNodesMultiplier,
            boolean allowUsefulUphillPartial) {
        this.lastLocalRouteTarget = null;
        if (this.moveToInternal(
                serverLevel,
                target,
                speed,
                maxSafeDrop,
                maxVisitedNodesMultiplier,
                allowUsefulUphillPartial)) {
            return true;
        }

        RouteStep routeStep = this.findLocalRouteStep(
                serverLevel,
                target,
                horizontalRadius,
                verticalDown,
                verticalUp,
                maxLocalPathChecks,
                maxVisitedNodesMultiplier
        );
        if (routeStep == null) {
            this.lastMoveFailureDetail = this.lastMoveFailureDetail
                    + " localCandidates=" + this.lastLocalCandidateCount
                    + " localChecks=" + this.lastLocalPathChecks
                    + " localRoute=none";
            return false;
        }

        this.lastLocalRouteTarget = routeStep.pos();
        this.lastMoveFailureDetail = "";
        return this.playerNpc.getNavigation().moveTo(routeStep.path(), speed);
    }

    public BlockPos lastLocalRouteTarget() {
        return this.lastLocalRouteTarget == null ? null : this.lastLocalRouteTarget.immutable();
    }

    public BlockPos lastUphillPartialRouteTarget() {
        return this.lastUphillPartialRouteTarget == null
                ? null
                : this.lastUphillPartialRouteTarget.immutable();
    }

    public String lastMoveFailureDetail() {
        return this.lastMoveFailureDetail;
    }

    /**
     * Ticks destination-aware swimming on goal ticks between normal repath attempts. Goals with
     * long repath intervals must call this before their ordinary movement/helper logic so FloatGoal
     * can provide buoyancy while this helper preserves the same work destination.
     */
    public boolean tickWaterTravel(ServerLevel serverLevel, BlockPos target, double speed) {
        return this.escapeWaterIfNeeded(serverLevel, target, speed);
    }

    /**
     * Runs survival-first local water recovery without inventing a distant work destination.
     * Exploration uses this while it has no valid land path, so a trapped NPC does not repeatedly
     * path-test unrelated 30-block surface targets.
     */
    public boolean tickLocalWaterEscape(ServerLevel serverLevel, double speed) {
        WaterEscapeAi.TickResult result = this.waterEscapeAi.tick(
                serverLevel,
                Math.min(1.0D, Math.max(0.1D, speed)),
                null
        );
        if (result == WaterEscapeAi.TickResult.RUNNING || result == WaterEscapeAi.TickResult.DONE) {
            this.lastMoveFailureDetail = "";
            if (result == WaterEscapeAi.TickResult.RUNNING && !this.waterEscapeAi.detail().isBlank()) {
                this.playerNpc.setCurrentAiDetail(this.waterEscapeAi.detail());
            }
            return true;
        }
        return false;
    }

    public boolean canStartLocalWaterEscape(ServerLevel serverLevel) {
        return this.waterEscapeAi.canStart(serverLevel);
    }

    public void stopWaterTravel() {
        this.waterEscapeAi.stop();
    }

    private boolean escapeWaterIfNeeded(ServerLevel serverLevel, BlockPos target, double speed) {
        WaterEscapeAi.TickResult result = this.waterEscapeAi.tick(
                serverLevel,
                Math.min(1.0D, Math.max(0.1D, speed)),
                target
        );
        if (result == WaterEscapeAi.TickResult.RUNNING || result == WaterEscapeAi.TickResult.DONE) {
            this.lastMoveFailureDetail = "";
            if (result == WaterEscapeAi.TickResult.RUNNING && !this.waterEscapeAi.detail().isBlank()) {
                this.playerNpc.setCurrentAiDetail(this.waterEscapeAi.detail());
            }
            return true;
        }
        return false;
    }

    public boolean canReachOrSafelyDropTo(ServerLevel serverLevel, BlockPos target, int maxSafeDrop) {
        return this.canReachOrSafelyDropTo(serverLevel, target, maxSafeDrop, 1.0F);
    }

    public boolean canReachOrSafelyDropTo(
            ServerLevel serverLevel,
            BlockPos target,
            int maxSafeDrop,
            float maxVisitedNodesMultiplier
    ) {
        if (!serverLevel.hasChunkAt(target)) {
            return false;
        }
        Path path = createBoundedPath(this.playerNpc, target, maxVisitedNodesMultiplier);
        return this.isValidPathTo(target, path)
                || this.canSafelyDropTo(serverLevel, target, maxSafeDrop);
    }

    public boolean canSafelyDropTo(ServerLevel serverLevel, BlockPos target, int maxSafeDrop) {
        return serverLevel.hasChunkAt(target)
                && this.findSafeDropStep(serverLevel, this.playerNpc.blockPosition(), target, maxSafeDrop) != null;
    }

    /**
     * Safe-drop checks only prove the next adjacent step, not a route to a distant destination.
     * Callers selecting a new destination must use this bounded form so a far target still needs
     * a complete navigation path.
     */
    public boolean canSafelyDropToLocalTarget(
            ServerLevel serverLevel,
            BlockPos target,
            int maxSafeDrop,
            int maxHorizontalDistance
    ) {
        if (target == null) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        int radius = Math.max(0, maxHorizontalDistance);
        return target.getY() < feet.getY()
                && dx * dx + dz * dz <= radius * radius
                && this.canSafelyDropTo(serverLevel, target, maxSafeDrop);
    }

    public Optional<BlockPos> findReachableRandomizedCandidate(
            ServerLevel serverLevel,
            List<BlockPos> candidates,
            int preferredPoolSize,
            int maxChecks,
            int maxSafeDrop
    ) {
        return this.findReachableRandomizedCandidate(
                serverLevel, candidates, preferredPoolSize, maxChecks, maxSafeDrop, 1.0F
        );
    }

    public Optional<BlockPos> findReachableRandomizedCandidate(
            ServerLevel serverLevel,
            List<BlockPos> candidates,
            int preferredPoolSize,
            int maxChecks,
            int maxSafeDrop,
            float maxVisitedNodesMultiplier
    ) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<BlockPos> ordered = new ArrayList<>(candidates);
        int preferredCount = Math.min(Math.max(1, preferredPoolSize), ordered.size());
        if (preferredCount > 1) {
            Collections.rotate(
                    ordered.subList(0, preferredCount),
                    this.playerNpc.getRandom().nextInt(preferredCount)
            );
        }

        int checks = 0;
        int checkLimit = Math.max(1, maxChecks);
        for (BlockPos candidate : ordered) {
            if (checks++ >= checkLimit) {
                break;
            }
            if (canStandAt(serverLevel, candidate)
                    && this.canReachOrSafelyDropTo(
                    serverLevel,
                    candidate,
                    maxSafeDrop,
                    maxVisitedNodesMultiplier
            )) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    public Optional<ReachablePathCandidate> findReachablePathCandidate(
            ServerLevel serverLevel,
            List<BlockPos> candidates,
            int preferredPoolSize,
            int maxChecks
    ) {
        return this.findReachablePathCandidate(
                serverLevel,
                candidates,
                preferredPoolSize,
                maxChecks,
                1.0F
        );
    }

    public Optional<ReachablePathCandidate> findReachablePathCandidate(
            ServerLevel serverLevel,
            List<BlockPos> candidates,
            int preferredPoolSize,
            int maxChecks,
            float maxVisitedNodesMultiplier
    ) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<BlockPos> ordered = new ArrayList<>(candidates);
        int preferredCount = Math.min(Math.max(1, preferredPoolSize), ordered.size());
        if (preferredCount > 1) {
            Collections.rotate(
                    ordered.subList(0, preferredCount),
                    this.playerNpc.getRandom().nextInt(preferredCount)
            );
        }

        int checks = 0;
        int checkLimit = Math.max(1, maxChecks);
        for (BlockPos candidate : ordered) {
            if (checks++ >= checkLimit) {
                break;
            }
            if (!canStandAt(serverLevel, candidate)) {
                continue;
            }
            Path path = createBoundedPath(this.playerNpc, candidate, maxVisitedNodesMultiplier);
            if (this.isValidPathTo(candidate, path)) {
                return Optional.of(new ReachablePathCandidate(candidate.immutable(), path));
            }
        }
        return Optional.empty();
    }

    public record ReachablePathCandidate(BlockPos pos, Path path) {
    }

    public boolean hasValidPathTo(BlockPos target) {
        Path path = this.playerNpc.getNavigation().createPath(target, 0);
        return this.isValidPathTo(target, path);
    }

    public boolean hasExactPathTo(BlockPos target) {
        Path path = this.playerNpc.getNavigation().createPath(target, 0);
        return this.isExactPathTo(target, path);
    }

    public boolean hasExactPathTo(BlockPos target, float maxVisitedNodesMultiplier) {
        Path path = createBoundedPath(this.playerNpc, target, maxVisitedNodesMultiplier);
        return this.isExactPathTo(target, path);
    }

    public boolean isValidPathTo(BlockPos target, Path path) {
        if (path == null || !path.canReach()) {
            return false;
        }

        Node endNode = path.getEndNode();
        if (endNode == null) {
            return false;
        }

        BlockPos endPos = endNode.asBlockPos();
        return Math.abs(endPos.getY() - target.getY()) <= PATH_END_VERTICAL_TOLERANCE
                && blockDistanceSqr(endPos, target) <= PATH_END_DISTANCE_SQR;
    }

    public boolean isExactPathTo(BlockPos target, Path path) {
        if (path == null || !path.canReach()) {
            return false;
        }

        Node endNode = path.getEndNode();
        return endNode != null && endNode.asBlockPos().equals(target);
    }

    private boolean isUsefulSafeUphillPartialPath(
            ServerLevel serverLevel,
            BlockPos target,
            Path path,
            int maxSafeDrop
    ) {
        BlockPos feet = this.playerNpc.blockPosition();
        Node endNode = path == null ? null : path.getEndNode();
        if (target == null
                || target.getY() <= feet.getY() + 1
                || path == null
                || path.canReach()
                || path.getNodeCount() <= 0
                || endNode == null) {
            return false;
        }

        BlockPos end = endNode.asBlockPos();
        if (end.getY() < feet.getY()
                || end.equals(feet)
                || blockDistanceSqr(end, target) >= blockDistanceSqr(feet, target)
                || !canStandAt(serverLevel, end)) {
            return false;
        }

        BlockPos previous = feet;
        for (int index = 0; index < path.getNodeCount(); index++) {
            BlockPos node = path.getNode(index).asBlockPos();
            if (!serverLevel.hasChunkAt(node)
                    || previous.getY() - node.getY() > maxSafeDrop
                    || !serverLevel.getFluidState(node).isEmpty()
                    || !serverLevel.getFluidState(node.above()).isEmpty()) {
                return false;
            }
            previous = node;
        }
        return true;
    }

    private boolean shouldStepDownBeforePath(BlockPos target, int maxSafeDrop) {
        return target != null
                && maxSafeDrop > 0
                && this.playerNpc.onGround()
                && this.playerNpc.blockPosition().getY() - target.getY() > PATH_END_VERTICAL_TOLERANCE;
    }

    private boolean isAlreadyAtTarget(BlockPos target) {
        return target != null && this.playerNpc.blockPosition().equals(target);
    }

    private boolean moveToSafeDropStep(ServerLevel serverLevel, BlockPos target, double speed, int maxSafeDrop) {
        BlockPos step = this.findSafeDropStep(serverLevel, this.playerNpc.blockPosition(), target, maxSafeDrop);
        if (step == null) {
            return false;
        }

        this.playerNpc.getLookControl().setLookAt(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (this.playerNpc.distanceToSqr(step.getX() + 0.5D, this.playerNpc.getY(), step.getZ() + 0.5D) <= DIRECT_STEP_REACHED_SQR) {
            this.playerNpc.getNavigation().stop();
            this.playerNpc.getMoveControl().setWantedPosition(
                    target.getX() + 0.5D,
                    target.getY(),
                    target.getZ() + 0.5D,
                    speed
            );
            return true;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getMoveControl().setWantedPosition(
                step.getX() + 0.5D,
                this.playerNpc.getY(),
                step.getZ() + 0.5D,
                speed
        );
        return true;
    }

    private BlockPos findSafeDropStep(ServerLevel serverLevel, BlockPos feet, BlockPos target, int maxSafeDrop) {
        if (target == null || maxSafeDrop <= 0 || target.getY() >= feet.getY()) {
            return null;
        }

        List<Direction> directions = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            directions.add(direction);
        }
        directions.sort(Comparator.comparingDouble(direction ->
                horizontalDistanceSqr(feet.relative(direction), target)));

        for (Direction direction : directions) {
            BlockPos step = feet.relative(direction);
            if (!serverLevel.hasChunkAt(step)
                    || this.hasBlockingCollision(serverLevel, step)
                    || this.hasBlockingCollision(serverLevel, step.above())
                    || !serverLevel.getFluidState(step).isEmpty()
                    || !serverLevel.getFluidState(step.above()).isEmpty()) {
                continue;
            }

            for (int drop = 1; drop <= maxSafeDrop; drop++) {
                BlockPos landing = step.below(drop);
                if (canStandAt(serverLevel, landing)) {
                    return step.immutable();
                }
            }
        }
        return null;
    }

    private RouteStep findLocalRouteStep(
            ServerLevel serverLevel,
            BlockPos target,
            int horizontalRadius,
            int verticalDown,
            int verticalUp,
            int maxPathChecks,
            float maxVisitedNodesMultiplier) {
        BlockPos feet = this.playerNpc.blockPosition();
        double currentTargetDistance = blockDistanceSqr(feet, target);
        List<BlockPos> candidates = new ArrayList<>();

        int radius = Math.max(2, horizontalRadius);
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                for (int dy = -Math.max(0, verticalDown); dy <= Math.max(0, verticalUp); dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!canStandAt(serverLevel, candidate)) {
                        continue;
                    }
                    if (!this.isUsefulLocalRouteCandidate(feet, candidate, target, currentTargetDistance)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }
        this.lastLocalCandidateCount = candidates.size();
        this.lastLocalPathChecks = 0;

        candidates.sort(Comparator.comparingDouble(candidate -> this.localRouteScore(serverLevel, feet, candidate, target)));
        int checks = 0;
        int checkLimit = Math.max(0, maxPathChecks);
        for (BlockPos candidate : candidates) {
            if (checks++ >= checkLimit) {
                break;
            }
            this.lastLocalPathChecks = checks;
            Path path = createBoundedPath(this.playerNpc, candidate, maxVisitedNodesMultiplier);
            if (this.isValidPathTo(candidate, path)) {
                return new RouteStep(candidate, path);
            }
        }
        return null;
    }

    private boolean isUsefulLocalRouteCandidate(BlockPos feet, BlockPos candidate, BlockPos target, double currentTargetDistance) {
        double candidateTargetDistance = blockDistanceSqr(candidate, target);
        if (candidateTargetDistance < currentTargetDistance) {
            return true;
        }

        double currentToCandidateX = candidate.getX() - feet.getX();
        double currentToCandidateZ = candidate.getZ() - feet.getZ();
        double currentToTargetX = target.getX() - feet.getX();
        double currentToTargetZ = target.getZ() - feet.getZ();
        double directionDot = currentToCandidateX * currentToTargetX + currentToCandidateZ * currentToTargetZ;
        return directionDot > 0.0D && candidateTargetDistance <= currentTargetDistance + LOCAL_ROUTE_MAX_BACKTRACK_SQR;
    }

    private double localRouteScore(ServerLevel serverLevel, BlockPos feet, BlockPos candidate, BlockPos target) {
        double targetDistance = blockDistanceSqr(candidate, target);
        double stepDistance = blockDistanceSqr(feet, candidate);
        double score = targetDistance + stepDistance * 0.35D;
        if (serverLevel.canSeeSky(candidate.above())) {
            score -= 32.0D;
        }
        if (candidate.getY() > feet.getY()) {
            score -= Math.min(12.0D, (candidate.getY() - feet.getY()) * 3.0D);
        }
        return score;
    }

    private boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    public static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)) {
            return false;
        }
        return serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private static double blockDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private String pathDebug(BlockPos target, Path path) {
        if (path == null) {
            return "directPath=null";
        }

        Node endNode = path.getEndNode();
        if (endNode == null) {
            return "directPath canReach=" + path.canReach() + " end=null";
        }

        BlockPos endPos = endNode.asBlockPos();
        return "directPath canReach=" + path.canReach()
                + " end=" + posText(endPos)
                + " endDist=" + Math.round(blockDistanceSqr(endPos, target));
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record RouteStep(BlockPos pos, Path path) {
    }
}
