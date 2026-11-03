package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class PathStuckFallbackAi {
    private static final int DEFAULT_STUCK_TICKS = 20 * 5;
    private static final int DEFAULT_ACTIVE_TICKS = 20 * 2;
    private static final int DEFAULT_RECHECK_TICKS = 20 * 3;
    private static final int DEFAULT_SEARCH_RADIUS = 5;
    // Heightmap initialization can be expensive in a newly loaded chunk. Keep the retained
    // radius-five search responsive without allowing a dozen first-touch columns to cluster in
    // one entity custom tick.
    private static final int MAX_SEARCH_COLUMNS_PER_TICK = 4;
    private static final int DEFAULT_MAX_FALL = 16;
    private static final double DEFAULT_STEP_OFF_SPEED = 0.28D;
    private static final List<BlockPos> STEP_OFF_COLUMN_OFFSETS = createStepOffColumnOffsets();

    private final PlayerNpcEntity playerNpc;
    private BlockPos watchFeetPos;
    private BlockPos watchTargetPos;
    private int watchStartTick;
    private int recheckTicks;
    private BlockPos stepOffStartPos;
    private BlockPos stepOffTargetPos;
    private int stepOffTicks;
    private BlockPos searchFeetPos;
    private BlockPos searchDirectionTarget;
    private Predicate<BlockPos> searchAvoidedStand;
    private BlockPos searchRelaxedTarget;
    private int searchColumnCursor;
    private String detail = "";

    public PathStuckFallbackAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public boolean isRunning() {
        return this.searchFeetPos != null
                || this.stepOffStartPos != null && this.stepOffTargetPos != null && this.stepOffTicks > 0;
    }

    public boolean tick(ServerLevel serverLevel, String detailPrefix) {
        if (!this.isRunning()) {
            return false;
        }
        if (this.searchFeetPos != null) {
            return this.tickStepOffSearch(serverLevel, detailPrefix);
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!feet.equals(this.stepOffStartPos) && this.playerNpc.onGround()) {
            this.clearStepOff();
            return false;
        }

        this.stepOffTicks--;
        if (this.stepOffTicks <= 0) {
            this.clearStepOff();
            return false;
        }

        this.forceHorizontalStepOff(detailPrefix);
        return true;
    }

    public boolean watchAndStart(ServerLevel serverLevel, BlockPos routeTarget, String detailPrefix) {
        return this.watchAndStart(serverLevel, routeTarget, routeTarget, detailPrefix, pos -> false);
    }

    public boolean start(ServerLevel serverLevel, BlockPos directionTarget, String detailPrefix) {
        return this.start(serverLevel, directionTarget, detailPrefix, pos -> false);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> avoidedStand
    ) {
        if (this.isRunning()) {
            return this.tick(serverLevel, detailPrefix);
        }
        if (!this.playerNpc.onGround()) {
            this.resetWatch();
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        this.beginStepOffSearch(feet, directionTarget, avoidedStand);
        this.resetWatch();
        return this.tickStepOffSearch(serverLevel, detailPrefix);
    }

    /**
     * Starts one strictly validated nearby relocation step. Unlike the emergency fallback above,
     * this variant never relaxes the rejected-position predicate and never falls back to an open
     * horizontal push without a proven landing. It is intended for ordinary exploration retries,
     * where failing cleanly is safer than stepping into an unloaded, protected, wet, or deep cell.
     */
    public boolean startValidatedNearby(
            ServerLevel serverLevel,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> rejectedStand,
            int maxSafeFall
    ) {
        return this.startValidatedNearby(
                serverLevel,
                directionTarget,
                detailPrefix,
                rejectedStand,
                maxSafeFall,
                false
        );
    }

    public boolean startValidatedNearby(
            ServerLevel serverLevel,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> rejectedStand,
            int maxSafeFall,
            boolean includeLeafCanopySurfaces
    ) {
        if (this.isRunning()) {
            return this.tick(serverLevel, detailPrefix);
        }
        if (!this.playerNpc.onGround()) {
            this.resetWatch();
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos stepOffTarget = this.findValidatedNearbyTarget(
                serverLevel,
                feet,
                directionTarget,
                rejectedStand,
                Math.max(0, maxSafeFall),
                includeLeafCanopySurfaces
        );
        if (stepOffTarget == null) {
            this.detail = detailPrefix + " safe relocation blocked: no validated nearby stand @ " + posText(feet);
            this.resetWatch();
            return false;
        }

        this.stepOffStartPos = feet.immutable();
        this.stepOffTargetPos = stepOffTarget.immutable();
        this.stepOffTicks = DEFAULT_ACTIVE_TICKS;
        this.resetWatch();
        this.playerNpc.getNavigation().stop();
        this.forceHorizontalStepOff(detailPrefix);
        return true;
    }

    public boolean watchAndStart(
            ServerLevel serverLevel,
            BlockPos routeTarget,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> avoidedStand
    ) {
        return this.watchAndStartInternal(
                serverLevel,
                routeTarget,
                directionTarget,
                detailPrefix,
                avoidedStand,
                true);
    }

    public boolean watchAndStartWhileNavigating(
            ServerLevel serverLevel,
            BlockPos routeTarget,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> avoidedStand
    ) {
        return this.watchAndStartInternal(
                serverLevel,
                routeTarget,
                directionTarget,
                detailPrefix,
                avoidedStand,
                false);
    }

    private boolean watchAndStartInternal(
            ServerLevel serverLevel,
            BlockPos routeTarget,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> avoidedStand,
            boolean requireStoppedNavigation
    ) {
        if (this.tick(serverLevel, detailPrefix)) {
            return true;
        }
        if (routeTarget == null || !this.playerNpc.onGround()) {
            this.resetWatch();
            return false;
        }
        if (requireStoppedNavigation
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck()) {
            this.resetWatch();
            return false;
        }
        if (this.recheckTicks > 0) {
            this.recheckTicks--;
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.watchFeetPos == null
                || !this.watchFeetPos.equals(feet)
                || this.watchTargetPos == null
                || !this.watchTargetPos.equals(routeTarget)) {
            this.watchFeetPos = feet.immutable();
            this.watchTargetPos = routeTarget.immutable();
            this.watchStartTick = this.playerNpc.tickCount;
            return false;
        }

        if (this.playerNpc.tickCount - this.watchStartTick < DEFAULT_STUCK_TICKS) {
            return false;
        }

        this.beginStepOffSearch(feet, directionTarget, avoidedStand);
        return this.tickStepOffSearch(serverLevel, detailPrefix);
    }

    public String detail(String fallback) {
        return this.detail == null || this.detail.isBlank() ? fallback : this.detail;
    }

    public void stop() {
        this.clearStepOff();
        this.clearStepOffSearch();
        this.resetWatch();
        this.recheckTicks = 0;
        this.detail = "";
    }

    private void beginStepOffSearch(
            BlockPos feet,
            BlockPos directionTarget,
            Predicate<BlockPos> avoidedStand
    ) {
        this.searchFeetPos = feet.immutable();
        this.searchDirectionTarget = directionTarget == null ? null : directionTarget.immutable();
        this.searchAvoidedStand = avoidedStand == null ? pos -> false : avoidedStand;
        this.searchRelaxedTarget = null;
        this.searchColumnCursor = 0;
    }

    private boolean tickStepOffSearch(ServerLevel serverLevel, String detailPrefix) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.searchFeetPos == null || !feet.equals(this.searchFeetPos) || !this.playerNpc.onGround()) {
            this.clearStepOffSearch();
            return false;
        }
        boolean acquired = this.playerNpc.isTeamFollowUpwardEscapeRequested()
                ? PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)
                : PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc);
        if (!acquired) {
            this.detail = detailPrefix + " path fallback queued for shared search slice";
            return true;
        }

        int end = Math.min(
                STEP_OFF_COLUMN_OFFSETS.size(),
                this.searchColumnCursor + MAX_SEARCH_COLUMNS_PER_TICK
        );
        for (; this.searchColumnCursor < end; this.searchColumnCursor++) {
            BlockPos offset = STEP_OFF_COLUMN_OFFSETS.get(this.searchColumnCursor);
            int x = feet.getX() + offset.getX();
            int z = feet.getZ() + offset.getZ();
            BlockPos loadedColumn = new BlockPos(x, feet.getY(), z);
            if (!serverLevel.hasChunkAt(loadedColumn)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int fall = feet.getY() - y;
            if (fall < 0 || fall > DEFAULT_MAX_FALL) {
                continue;
            }

            BlockPos candidate = new BlockPos(x, y, z);
            if (!PathNavigationAi.canStandAt(serverLevel, candidate)
                    || !this.canStepOffToward(serverLevel, feet, candidate)) {
                continue;
            }
            if (this.searchAvoidedStand.test(candidate)) {
                if (this.searchRelaxedTarget == null
                        || this.stepOffScore(serverLevel, feet, this.searchDirectionTarget, candidate)
                        < this.stepOffScore(serverLevel, feet, this.searchDirectionTarget, this.searchRelaxedTarget)) {
                    this.searchRelaxedTarget = candidate.immutable();
                }
                continue;
            }
            this.startStepOff(feet, candidate, detailPrefix);
            return true;
        }

        if (this.searchColumnCursor < STEP_OFF_COLUMN_OFFSETS.size()) {
            this.detail = detailPrefix + " path fallback scanning "
                    + this.searchColumnCursor + "/" + STEP_OFF_COLUMN_OFFSETS.size();
            return true;
        }

        BlockPos selected = this.searchRelaxedTarget != null
                ? this.searchRelaxedTarget.immutable()
                : this.findOpenPushTarget(serverLevel, feet, this.searchDirectionTarget);
        this.clearStepOffSearch();
        if (selected == null) {
            this.detail = detailPrefix + " path fallback blocked: no step off @ " + posText(feet);
            this.watchStartTick = this.playerNpc.tickCount;
            this.recheckTicks = DEFAULT_RECHECK_TICKS;
            return false;
        }
        this.startStepOff(feet, selected, detailPrefix);
        return true;
    }

    private void startStepOff(BlockPos feet, BlockPos target, String detailPrefix) {
        this.clearStepOffSearch();
        this.stepOffStartPos = feet.immutable();
        this.stepOffTargetPos = target.immutable();
        this.stepOffTicks = DEFAULT_ACTIVE_TICKS;
        this.playerNpc.getNavigation().stop();
        this.forceHorizontalStepOff(detailPrefix);
    }

    private void clearStepOffSearch() {
        this.searchFeetPos = null;
        this.searchDirectionTarget = null;
        this.searchAvoidedStand = null;
        this.searchRelaxedTarget = null;
        this.searchColumnCursor = 0;
    }

    private static List<BlockPos> createStepOffColumnOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        int radiusSqr = DEFAULT_SEARCH_RADIUS * DEFAULT_SEARCH_RADIUS;
        for (int dx = -DEFAULT_SEARCH_RADIUS; dx <= DEFAULT_SEARCH_RADIUS; dx++) {
            for (int dz = -DEFAULT_SEARCH_RADIUS; dz <= DEFAULT_SEARCH_RADIUS; dz++) {
                if ((dx == 0 && dz == 0) || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                offsets.add(new BlockPos(dx, 0, dz));
            }
        }
        offsets.sort(Comparator
                .comparingInt((BlockPos pos) -> pos.getX() * pos.getX() + pos.getZ() * pos.getZ())
                .thenComparingInt(pos -> Math.max(Math.abs(pos.getX()), Math.abs(pos.getZ())))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(offsets);
    }

    private BlockPos findValidatedNearbyTarget(
            ServerLevel serverLevel,
            BlockPos feet,
            BlockPos directionTarget,
            Predicate<BlockPos> rejectedStand,
            int maxSafeFall,
            boolean includeLeafCanopySurfaces
    ) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                BlockPos loadedColumn = new BlockPos(x, feet.getY(), z);
                if (!serverLevel.hasChunkAt(loadedColumn)) {
                    continue;
                }
                int y = serverLevel.getHeight(
                        includeLeafCanopySurfaces
                                ? Heightmap.Types.MOTION_BLOCKING
                                : Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x,
                        z
                );
                int fall = feet.getY() - y;
                if (fall < 0 || fall > maxSafeFall) {
                    continue;
                }

                BlockPos candidate = new BlockPos(x, y, z);
                boolean validStand = includeLeafCanopySurfaces
                        ? canStandOnGroundOrLeaves(serverLevel, candidate)
                        : PathNavigationAi.canStandAt(serverLevel, candidate);
                if (!validStand
                        || !this.canStepOffToward(serverLevel, feet, candidate)
                        || rejectedStand.test(candidate)) {
                    continue;
                }
                candidates.add(candidate.immutable());
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> this.stepOffScore(serverLevel, feet, directionTarget, pos))
                .thenComparingDouble(pos -> horizontalDistanceSqr(feet, pos))
                .thenComparingInt(pos -> Math.abs(feet.getY() - pos.getY())));
        return candidates.isEmpty() ? null : candidates.get(0).immutable();
    }

    private static boolean canStandOnGroundOrLeaves(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)) {
            return false;
        }
        BlockState support = serverLevel.getBlockState(pos.below());
        return serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && (support.isSolidRender() || support.is(BlockTags.LEAVES))
                && !support.getCollisionShape(serverLevel, pos.below()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private double stepOffScore(ServerLevel serverLevel, BlockPos feet, BlockPos directionTarget, BlockPos candidate) {
        double score = horizontalDistanceSqr(feet, candidate) * 1.5D;
        if (directionTarget != null) {
            score += candidate.distSqr(directionTarget) * 0.12D;
        }
        if (candidate.getY() < feet.getY()) {
            score -= Math.min(12.0D, (feet.getY() - candidate.getY()) * 2.0D);
        }
        if (serverLevel.canSeeSky(candidate.above())) {
            score -= 8.0D;
        }
        return score;
    }

    private BlockPos findOpenPushTarget(ServerLevel serverLevel, BlockPos feet, BlockPos directionTarget) {
        for (Direction direction : this.directionsToward(feet, directionTarget)) {
            BlockPos adjacent = feet.relative(direction);
            if (serverLevel.isInWorldBounds(adjacent)
                    && serverLevel.getWorldBorder().isWithinBounds(adjacent)
                    && this.hasOpenBodySpace(serverLevel, adjacent)) {
                return feet.relative(direction, DEFAULT_SEARCH_RADIUS).immutable();
            }
        }
        return null;
    }

    private boolean canStepOffToward(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        Direction direction = this.firstStepDirection(feet, target);
        if (direction == null) {
            return false;
        }
        BlockPos adjacent = feet.relative(direction);
        return serverLevel.isInWorldBounds(adjacent)
                && serverLevel.getWorldBorder().isWithinBounds(adjacent)
                && this.hasOpenBodySpace(serverLevel, adjacent);
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.hasChunkAt(pos) || !serverLevel.hasChunkAt(pos.above())) {
            return false;
        }
        BlockState feetState = serverLevel.getBlockState(pos);
        BlockState headState = serverLevel.getBlockState(pos.above());
        return feetState.getCollisionShape(serverLevel, pos).isEmpty()
                && headState.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private Direction firstStepDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private List<Direction> directionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        if (to != null) {
            for (Direction direction : this.primaryDirectionsToward(from, to)) {
                if (!directions.contains(direction)) {
                    directions.add(direction);
                }
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!directions.contains(direction)) {
                directions.add(direction);
            }
        }
        return directions;
    }

    private List<Direction> primaryDirectionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        Direction xDirection = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : null;
        Direction zDirection = dz > 0 ? Direction.SOUTH : dz < 0 ? Direction.NORTH : null;
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (xDirection != null) {
                directions.add(xDirection);
            }
            if (zDirection != null) {
                directions.add(zDirection);
            }
        } else {
            if (zDirection != null) {
                directions.add(zDirection);
            }
            if (xDirection != null) {
                directions.add(xDirection);
            }
        }
        return directions;
    }

    private void forceHorizontalStepOff(String detailPrefix) {
        if (this.stepOffStartPos == null || this.stepOffTargetPos == null) {
            return;
        }

        double targetX = this.stepOffTargetPos.getX() + 0.5D;
        double targetZ = this.stepOffTargetPos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(targetX, this.playerNpc.getY(), targetZ, 30.0F, 30.0F);
        this.playerNpc.getMoveControl().setWantedPosition(targetX, this.playerNpc.getY(), targetZ, 1.15D);
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setDeltaMovement(
                dx / length * DEFAULT_STEP_OFF_SPEED,
                motion.y,
                dz / length * DEFAULT_STEP_OFF_SPEED
        );
        this.playerNpc.hurtMarked = true;
        this.detail = detailPrefix + " path fallback forced step off @ "
                + posText(this.stepOffStartPos)
                + " -> "
                + posText(this.stepOffTargetPos);
    }

    private void clearStepOff() {
        this.stepOffStartPos = null;
        this.stepOffTargetPos = null;
        this.stepOffTicks = 0;
    }

    private void resetWatch() {
        this.watchFeetPos = null;
        this.watchTargetPos = null;
        this.watchStartTick = 0;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
