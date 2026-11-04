package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Reusable return movement helper for goals that need to get an NPC back to a known position.
 */
public final class ReturnPositionAi {
    private static final int REPATH_DELAY_TICKS = 20;
    private static final int REPATH_JITTER_TICKS = 10;
    private static final int CLEAR_ROUTE_TICKS = 28;
    private static final int UPWARD_ESCAPE_REQUEST_TICKS = 80;
    private static final int MAX_SAFE_DROP_BLOCKS = 5;
    private static final int MAX_RETURN_PILLAR_BLOCKS = 10;
    private static final int MAX_DIRECT_RETURN_PILLAR_BLOCKS = 10;
    private static final int LOCAL_ROUTE_HORIZONTAL_RADIUS = 8;
    private static final int LOCAL_ROUTE_VERTICAL_DOWN = 5;
    private static final int LOCAL_ROUTE_VERTICAL_UP = 6;
    private static final int FAILED_LOCAL_ROUTE_COOLDOWN_TICKS = 20 * 3;
    private static final int CLEAR_ATTEMPTS_BEFORE_BREAKING = 2;
    private static final int CLEAR_ATTEMPTS_BEFORE_ESCAPE = 4;
    private static final int FAILED_ROUTE_RELOCATE_RADIUS = 6;
    private static final int FAILED_ROUTE_RELOCATE_VERTICAL_DOWN = 6;
    private static final int FAILED_ROUTE_RELOCATE_VERTICAL_UP = 2;
    private static final int FAILED_ROUTE_RELOCATE_RANDOM_POOL = 12;
    private static final int FAILED_ROUTE_RELOCATE_PATH_CHECKS = 24;
    private static final int FAILED_ROUTE_RELOCATE_COOLDOWN_TICKS = 20 * 4;
    private static final int BUILDER_CLEAR_NO_PROGRESS_TICKS = 20 * 2;
    private static final int BUILDER_CLEAR_CORRIDOR_DEPTH = 2;
    private static final int BUILDER_CLEAR_CORRIDOR_HEIGHT = 2;
    private static final double BUILDER_PROGRESS_DISTANCE_SQR = 0.45D * 0.45D;
    private static final double CLEAR_ROUTE_DISTANCE_SQR = 6.25D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PillarUpAi pillarUpAi;
    private final PathNavigationAi pathNavigationAi;
    private final PathStuckFallbackAi pathStuckFallbackAi;

    private BlockPos target;
    private int repathTicks;
    private int routeAttempts;
    private int directPillarsPlaced;
    private int failedLocalRouteCooldownTicks;
    private int failedRouteRelocateCooldownTicks;
    private Vec3 builderProgressAnchor;
    private int builderNoProgressTicks;
    private boolean builderCorridorClearActive;
    private Direction builderCorridorDirection;
    private String detail = "";
    private String lastRouteDebug = "";
    private String lastClearDebug = "";
    private String lastPillarDebug = "";
    private boolean workerSlotPaused;

    public ReturnPositionAi(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pillarUpAi = new PillarUpAi(playerNpc, this.toolAi, Items.DIRT, Blocks.DIRT.defaultBlockState());
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.pathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
    }

    public void start(BlockPos target) {
        if (this.workerSlotPaused
                && PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                && this.target != null
                && this.target.equals(target)
                && this.pillarUpAi.isRunning()) {
            this.workerSlotPaused = false;
            return;
        }
        this.workerSlotPaused = false;
        this.target = target.immutable();
        this.repathTicks = 0;
        this.routeAttempts = 0;
        this.directPillarsPlaced = 0;
        this.failedLocalRouteCooldownTicks = 0;
        this.failedRouteRelocateCooldownTicks = 0;
        this.resetBuilderProgressWatch();
        this.clearBuilderCorridorState();
        this.detail = "";
        this.lastRouteDebug = "";
        this.lastClearDebug = "";
        this.lastPillarDebug = "";
        this.clearBlockAi.stop();
        this.pillarUpAi.clear();
        this.pathStuckFallbackAi.stop();
    }

    /** Starts return state while reusing an exact path already paid for by target selection. */
    public void start(BlockPos target, Path selectedPath) {
        this.start(target);
        if (this.pillarUpAi.isRunning()) {
            return;
        }
        if (!isExactPathTo(selectedPath, target)) {
            return;
        }
        if (this.playerNpc.getNavigation().moveTo(selectedPath, this.speed)) {
            this.repathTicks = REPATH_DELAY_TICKS;
        }
    }

    private static boolean isExactPathTo(Path path, BlockPos target) {
        if (path == null || target == null || !path.canReach()) {
            return false;
        }
        Node endNode = path.getEndNode();
        return endNode != null && endNode.asBlockPos().equals(target);
    }

    public void tick(ServerLevel serverLevel, BlockPos target, Predicate<BlockPos> protectedBlock, String moveDetail, String clearDetail) {
        this.tickInternal(serverLevel, target, protectedBlock, moveDetail, clearDetail, false, false, false);
    }

    public void tick(
            ServerLevel serverLevel,
            BlockPos target,
            Predicate<BlockPos> protectedBlock,
            String moveDetail,
            String clearDetail,
            boolean allowPathStuckFallback
    ) {
        this.tickInternal(serverLevel, target, protectedBlock, moveDetail, clearDetail, allowPathStuckFallback, false, false);
    }

    public void tickBuilderHomeReturn(
            ServerLevel serverLevel,
            BlockPos target,
            Predicate<BlockPos> protectedBlock,
            String moveDetail,
            String clearDetail
    ) {
        this.tickInternal(serverLevel, target, protectedBlock, moveDetail, clearDetail, true, true, false);
    }

    public void tickFarmCampReturn(
            ServerLevel serverLevel,
            BlockPos target,
            Predicate<BlockPos> protectedBlock,
            String moveDetail,
            String clearDetail
    ) {
        this.tickInternal(serverLevel, target, protectedBlock, moveDetail, clearDetail, false, false, true);
    }

    private void tickInternal(
            ServerLevel serverLevel,
            BlockPos target,
            Predicate<BlockPos> protectedBlock,
            String moveDetail,
            String clearDetail,
            boolean allowPathStuckFallback,
            boolean useHistoricalBuilderFallback,
            boolean allowOpenSkyVerticalRecovery
    ) {
        if (target == null) {
            return;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.pauseForWorkerSlotLoss();
            return;
        }
        if (this.target == null || !this.target.equals(target)) {
            this.start(target);
        }
        if (this.failedLocalRouteCooldownTicks > 0) {
            this.failedLocalRouteCooldownTicks--;
        }
        if (this.failedRouteRelocateCooldownTicks > 0) {
            this.failedRouteRelocateCooldownTicks--;
        }

        if (this.pathStuckFallbackAi.tick(serverLevel, moveDetail)) {
            this.detail = this.pathStuckFallbackAi.detail(moveDetail);
            return;
        }

        if (this.clearBlockAi.isRunning()) {
            if (this.isResolvedClearTargetProtected(protectedBlock)) {
                this.lastClearDebug = "clear stopped: resolved target protected @ " + posText(this.clearBlockAi.targetPos());
                this.clearBlockAi.stop();
                this.clearBuilderCorridorState();
                this.resetBuilderProgressWatch();
                this.repathTicks = 0;
                this.detail = moveDetail + " (protected route blocker; searching alternate route)";
                return;
            }
            ClearBlockAi.TickResult clearResult = this.clearBlockAi.tick(serverLevel);
            if (this.clearBlockAi.isRunning() && this.isResolvedClearTargetProtected(protectedBlock)) {
                this.lastClearDebug = "clear stopped after retarget: protected @ " + posText(this.clearBlockAi.targetPos());
                this.clearBlockAi.stop();
                this.clearBuilderCorridorState();
                this.resetBuilderProgressWatch();
                this.repathTicks = 0;
                this.detail = moveDetail + " (protected route blocker; searching alternate route)";
                return;
            }
            this.detail = this.clearBlockAi.detail();
            if (!this.clearBlockAi.isRunning()) {
                if (clearResult == ClearBlockAi.TickResult.DONE
                        && useHistoricalBuilderFallback
                        && this.builderCorridorClearActive
                        && this.startNextBuilderCorridorClear(
                        serverLevel,
                        protectedBlock,
                        clearDetail,
                        this.builderCorridorDirection)) {
                    this.detail = this.clearBlockAi.detail();
                    return;
                }
                this.clearBuilderCorridorState();
                this.repathTicks = 0;
                this.routeAttempts = 0;
                this.failedLocalRouteCooldownTicks = 0;
                this.resetBuilderProgressWatch();
            }
            return;
        }

        if (this.pillarUpAi.isRunning()) {
            PillarUpAi.TickResult result = this.pillarUpAi.tick(serverLevel);
            this.detail = this.pillarUpAi.detail();
            if (result == PillarUpAi.TickResult.PLACED) {
                this.directPillarsPlaced++;
                this.repathTicks = 0;
                this.routeAttempts = 0;
                this.failedLocalRouteCooldownTicks = 0;
                this.resetBuilderProgressWatch();
                this.detail = moveDetail + " (pillared upward)";
            } else if (result == PillarUpAi.TickResult.FAILED) {
                this.tryClearPillarBlocker(serverLevel, protectedBlock);
                this.pillarUpAi.clear();
                this.resetBuilderProgressWatch();
                this.repathTicks = 0;
            }
            return;
        }

        boolean builderClearReady = !useHistoricalBuilderFallback || this.tickBuilderProgressWatch();

        boolean navigationDone = this.playerNpc.getNavigation().isDone();
        boolean stuck = this.playerNpc.getNavigation().isStuck();
        if (this.repathTicks-- > 0) {
            this.detail = !navigationDone && !stuck
                    ? moveDetail
                    : moveDetail + " (waiting to retry route)";
            return;
        }

        // The destination is static for one return episode. Rebuilding an already-live path on
        // every cadence adds server cost without changing the route. Builder home return keeps
        // its historical no-progress recovery, while ordinary returns wait for navigation to
        // finish or report a stuck path before opening another discovery batch.
        if (!navigationDone && !stuck && !(useHistoricalBuilderFallback && builderClearReady)) {
            this.repathTicks = REPATH_DELAY_TICKS + this.playerNpc.getRandom().nextInt(REPATH_JITTER_TICKS + 1);
            this.detail = moveDetail;
            return;
        }

        // A return retry may combine obstruction discovery, local-fallback candidate scans and
        // many navigation paths. It is a shared expensive batch even though ordinary movement
        // along an existing path remains cheap and runs every tick.
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.repathTicks = 1 + this.playerNpc.getRandom().nextInt(4);
            this.detail = moveDetail + " (waiting for shared route budget)";
            return;
        }

        if (navigationDone || stuck) {
            this.routeAttempts++;
        }

        if (this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_BREAKING
                && this.startClearingRoute(
                serverLevel,
                protectedBlock,
                clearDetail,
                useHistoricalBuilderFallback && builderClearReady)) {
            this.detail = this.clearBlockAi.detail();
            return;
        }

        boolean allowLocalFallback = this.failedLocalRouteCooldownTicks <= 0;
        boolean moved;
        if (allowLocalFallback) {
            moved = this.pathNavigationAi.moveToWithLocalFallback(
                    serverLevel,
                    target,
                    this.speed,
                    MAX_SAFE_DROP_BLOCKS,
                    LOCAL_ROUTE_HORIZONTAL_RADIUS,
                    LOCAL_ROUTE_VERTICAL_DOWN,
                    LOCAL_ROUTE_VERTICAL_UP,
                    useHistoricalBuilderFallback);
        } else {
            moved = useHistoricalBuilderFallback
                    ? this.pathNavigationAi.moveToAllowingUsefulUphillPartial(
                    serverLevel,
                    target,
                    this.speed,
                    MAX_SAFE_DROP_BLOCKS)
                    : this.pathNavigationAi.moveTo(serverLevel, target, this.speed, MAX_SAFE_DROP_BLOCKS);
        }
        this.repathTicks = REPATH_DELAY_TICKS + this.playerNpc.getRandom().nextInt(REPATH_JITTER_TICKS + 1);
        this.lastRouteDebug = this.pathNavigationAi.lastMoveFailureDetail();
        BlockPos uphillPartialRoute = this.pathNavigationAi.lastUphillPartialRouteTarget();
        if (moved && uphillPartialRoute != null) {
            this.routeAttempts = 0;
            this.failedLocalRouteCooldownTicks = 0;
            this.resetBuilderProgressWatch();
            this.lastRouteDebug = "partial uphill path end=" + posText(uphillPartialRoute)
                    + " endDist=" + Math.round(uphillPartialRoute.distSqr(target));
            this.detail = moveDetail + " (uphill route @ " + posText(uphillPartialRoute) + ")";
            return;
        }
        boolean acceptedWithoutProgress = moved
                && useHistoricalBuilderFallback
                && builderClearReady;
        if (moved && !acceptedWithoutProgress) {
            this.failedLocalRouteCooldownTicks = 0;
            BlockPos localRoute = allowLocalFallback ? this.pathNavigationAi.lastLocalRouteTarget() : null;
            this.detail = localRoute == null ? moveDetail : moveDetail + " (local route @ " + posText(localRoute) + ")";
            return;
        }
        if (acceptedWithoutProgress) {
            this.routeAttempts = Math.max(this.routeAttempts, CLEAR_ATTEMPTS_BEFORE_ESCAPE);
            this.lastRouteDebug = "movement accepted without progress="
                    + this.builderNoProgressTicks + "t";
        } else if (allowLocalFallback) {
            this.failedLocalRouteCooldownTicks = FAILED_LOCAL_ROUTE_COOLDOWN_TICKS
                    + this.playerNpc.getRandom().nextInt(FAILED_LOCAL_ROUTE_COOLDOWN_TICKS / 2 + 1);
        } else if (this.lastRouteDebug != null && !this.lastRouteDebug.isBlank()) {
            this.lastRouteDebug = this.lastRouteDebug + " localRoute=cooling";
        }

        boolean verticalEscapeNeeded = this.needsVerticalEscape(
                serverLevel,
                target,
                allowOpenSkyVerticalRecovery);
        if (verticalEscapeNeeded
                && this.tryStartDirectPillar(
                serverLevel,
                target,
                protectedBlock,
                "clearing return pillar space",
                true)) {
            this.detail = this.pillarUpAi.detail();
            return;
        }

        if (!verticalEscapeNeeded
                && this.startClearingRoute(
                serverLevel,
                protectedBlock,
                clearDetail,
                useHistoricalBuilderFallback && builderClearReady)) {
            this.detail = this.clearBlockAi.detail();
            return;
        }

        if (!verticalEscapeNeeded
                && useHistoricalBuilderFallback
                && allowPathStuckFallback
                && builderClearReady
                && this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_ESCAPE
                && this.pathStuckFallbackAi.start(serverLevel, target, moveDetail, protectedBlock)) {
            this.detail = this.pathStuckFallbackAi.detail(moveDetail);
            this.repathTicks = REPATH_DELAY_TICKS;
            this.routeAttempts = CLEAR_ATTEMPTS_BEFORE_BREAKING;
            return;
        }

        if (useHistoricalBuilderFallback
                && this.tryStartDirectPillar(
                serverLevel,
                target,
                protectedBlock,
                "clearing return pillar space",
                this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_BREAKING)) {
            this.detail = this.pillarUpAi.detail();
            return;
        }

        if (verticalEscapeNeeded
                && this.startClearingRoute(
                serverLevel,
                protectedBlock,
                clearDetail,
                useHistoricalBuilderFallback && builderClearReady)) {
            this.detail = this.clearBlockAi.detail();
            return;
        }

        boolean requestUpwardEscape = verticalEscapeNeeded
                && !this.playerNpc.isTemporaryPillarSupport(this.playerNpc.blockPosition().below())
                && (useHistoricalBuilderFallback
                || this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_ESCAPE);
        if (requestUpwardEscape) {
            this.playerNpc.requestForcedUpwardEscapeTo(target, UPWARD_ESCAPE_REQUEST_TICKS, MAX_RETURN_PILLAR_BLOCKS);
            this.detail = moveDetail + " (escaping upward)";
            this.routeAttempts = 0;
            return;
        }

        if (!verticalEscapeNeeded && this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_ESCAPE) {
            if (this.tryMoveToFailedRouteRelocation(serverLevel, target, moveDetail)) {
                return;
            }
            if (allowPathStuckFallback
                    && this.pathStuckFallbackAi.watchAndStart(serverLevel, target, target, moveDetail, protectedBlock)) {
                this.detail = this.pathStuckFallbackAi.detail(moveDetail);
                this.repathTicks = REPATH_DELAY_TICKS;
                this.routeAttempts = CLEAR_ATTEMPTS_BEFORE_BREAKING;
                return;
            }
            this.detail = moveDetail + " (moving around after blocked return; " + this.debugText("searching route") + ")";
            this.repathTicks = REPATH_DELAY_TICKS;
            this.routeAttempts = CLEAR_ATTEMPTS_BEFORE_BREAKING;
            return;
        }

        this.detail = moveDetail + " (" + this.debugText("searching route") + ")";
    }

    private boolean isResolvedClearTargetProtected(Predicate<BlockPos> protectedBlock) {
        return this.isReturnClearProtected(this.clearBlockAi.targetPos(), protectedBlock);
    }

    private boolean isReturnClearProtected(BlockPos pos, Predicate<BlockPos> protectedBlock) {
        return pos != null
                && (pos.equals(this.playerNpc.blockPosition().below())
                || this.playerNpc.isTemporaryPillarSupport(pos)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                || protectedBlock != null && protectedBlock.test(pos));
    }

    public String detail(String fallback) {
        return this.detail == null || this.detail.isBlank() ? fallback : this.detail;
    }

    public void stop() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc) && this.pillarUpAi.isRunning()) {
            this.pauseForWorkerSlotLoss();
            return;
        }
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.pillarUpAi.clear();
        this.pathStuckFallbackAi.stop();
        this.target = null;
        this.repathTicks = 0;
        this.routeAttempts = 0;
        this.directPillarsPlaced = 0;
        this.failedLocalRouteCooldownTicks = 0;
        this.failedRouteRelocateCooldownTicks = 0;
        this.builderProgressAnchor = null;
        this.builderNoProgressTicks = 0;
        this.clearBuilderCorridorState();
        this.detail = "";
        this.lastRouteDebug = "";
        this.lastClearDebug = "";
        this.lastPillarDebug = "";
    }

    private void pauseForWorkerSlotLoss() {
        this.playerNpc.getNavigation().stop();
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.pathStuckFallbackAi.stop();
        this.toolAi.restoreMainHand();
        this.workerSlotPaused = true;
        // Keep the return target and PillarUpAi's unplaced/settling support state. The caller may
        // restart ordinary movement later, while a matching holder resumes the vertical step.
    }

    private boolean tryStartDirectPillar(
            ServerLevel serverLevel,
            BlockPos target,
            Predicate<BlockPos> protectedBlock,
            String clearDetail,
            boolean forceAfterFailedRoute) {
        BlockPos feet = this.playerNpc.blockPosition();
        boolean targetAbove = target.getY() > feet.getY() + 1;
        if (!targetAbove) {
            this.lastPillarDebug = "pillar skipped targetY=" + target.getY() + " feetY=" + feet.getY();
            return false;
        }
        if (!forceAfterFailedRoute) {
            this.lastPillarDebug = "pillar waiting for route retries=" + this.routeAttempts;
            return false;
        }
        if (this.directPillarsPlaced >= MAX_DIRECT_RETURN_PILLAR_BLOCKS) {
            this.lastPillarDebug = "pillar skipped maxDirect=" + MAX_DIRECT_RETURN_PILLAR_BLOCKS;
            return false;
        }

        String blockerText = this.pillarUpAi.startBlocker(serverLevel, feet);
        if (blockerText.isBlank() && this.pillarUpAi.start(serverLevel, feet)) {
            this.lastPillarDebug = "pillar started @ " + posText(feet);
            return true;
        }

        BlockPos blocker = this.pillarUpAi.startBlockerPos(serverLevel, feet);
        if (blocker == null || this.isReturnClearProtected(blocker, protectedBlock)) {
            this.lastPillarDebug = blocker == null
                    ? "pillar blocked: " + blockerText
                    : "pillar blocker protected @ " + posText(blocker) + " reason=" + blockerText;
            return false;
        }
        boolean clearing = this.clearBlockAi.start(
                serverLevel,
                blocker,
                this::isClearable,
                clearDetail,
                CLEAR_ROUTE_TICKS,
                CLEAR_ROUTE_DISTANCE_SQR);
        this.lastPillarDebug = clearing
                ? "pillar clearing blocker @ " + posText(blocker) + " reason=" + blockerText
                : "pillar blocker uncleared @ " + posText(blocker) + " reason=" + blockerText;
        return clearing;
    }

    private void tryClearPillarBlocker(ServerLevel serverLevel, Predicate<BlockPos> protectedBlock) {
        BlockPos blocker = this.pillarUpAi.consumeLastFailureBlockerPos();
        if (blocker == null || this.isReturnClearProtected(blocker, protectedBlock)) {
            this.lastPillarDebug = blocker == null ? "pillar failed without blocker" : "pillar failed protected blocker @ " + posText(blocker);
            return;
        }
        boolean clearing = this.clearBlockAi.start(
                serverLevel,
                blocker,
                this::isClearable,
                "clearing return pillar space",
                CLEAR_ROUTE_TICKS,
                CLEAR_ROUTE_DISTANCE_SQR);
        this.lastPillarDebug = clearing
                ? "pillar failed; clearing blocker @ " + posText(blocker)
                : "pillar failed; blocker not clearable @ " + posText(blocker);
    }

    private boolean startClearingRoute(
            ServerLevel serverLevel,
            Predicate<BlockPos> protectedBlock,
            String clearDetail,
            boolean includeImmediateBodyObstructions
    ) {
        if (this.target == null) {
            this.lastClearDebug = "clear skipped target=null";
            return false;
        }
        if (includeImmediateBodyObstructions
                && this.startNextBuilderCorridorClear(serverLevel, protectedBlock, clearDetail, null)) {
            return true;
        }
        this.clearBuilderCorridorState();

        List<BlockPos> candidates = ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                this.target,
                this.target);
        candidates.removeIf(pos -> pos.equals(this.target) || this.isReturnClearProtected(pos, protectedBlock));
        if (candidates.isEmpty()) {
            this.lastClearDebug = "clear skipped candidates=0";
            return false;
        }
        Optional<BlockPos> clearTarget = ClearBlockAi.findNearestClearable(
                serverLevel,
                this.playerNpc.blockPosition(),
                candidates,
                state -> this.isClearable(state),
                CLEAR_ROUTE_DISTANCE_SQR);
        if (clearTarget.isEmpty()) {
            this.lastClearDebug = "clear none candidates=" + candidates.size();
            return false;
        }

        boolean started = this.clearBlockAi.start(
                serverLevel,
                clearTarget.get(),
                this::isClearable,
                clearDetail,
                CLEAR_ROUTE_TICKS,
                CLEAR_ROUTE_DISTANCE_SQR);
        this.lastClearDebug = started
                ? "clear started @ " + posText(clearTarget.get())
                : "clear start failed @ " + posText(clearTarget.get());
        return started;
    }

    private boolean startNextBuilderCorridorClear(
            ServerLevel serverLevel,
            Predicate<BlockPos> protectedBlock,
            String clearDetail,
            Direction fixedDirection
    ) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = builderCorridorObstructionCandidates(feet, this.target, fixedDirection);
        candidates.removeIf(pos -> this.isReturnClearProtected(pos, protectedBlock));
        Optional<BlockPos> clearTarget = candidates.stream()
                .filter(pos -> ClearBlockAi.isBreakablePathObstruction(
                        serverLevel,
                        pos,
                        serverLevel.getBlockState(pos)))
                .findFirst();
        if (clearTarget.isEmpty()) {
            this.lastClearDebug = "builder corridor clear complete";
            return false;
        }

        BlockPos selected = clearTarget.get();
        boolean started = this.clearBlockAi.start(
                serverLevel,
                selected,
                this::isClearable,
                clearDetail,
                CLEAR_ROUTE_TICKS,
                ClearBlockAi.BREAK_REACH_DISTANCE_SQR);
        if (!started) {
            this.lastClearDebug = "builder corridor clear start failed @ " + posText(selected);
            return false;
        }

        this.builderCorridorClearActive = true;
        if (fixedDirection != null) {
            this.builderCorridorDirection = fixedDirection;
        } else {
            Direction selectedDirection = corridorDirection(feet, selected);
            if (selectedDirection != null) {
                this.builderCorridorDirection = selectedDirection;
            }
        }
        this.lastClearDebug = "builder corridor clear started @ " + posText(selected);
        return true;
    }

    private boolean isClearable(BlockState state) {
        return state != null && !state.isAir();
    }

    private boolean tickBuilderProgressWatch() {
        Vec3 current = this.playerNpc.position();
        if (this.builderProgressAnchor == null
                || horizontalDistanceSqr(current, this.builderProgressAnchor) >= BUILDER_PROGRESS_DISTANCE_SQR) {
            this.builderProgressAnchor = current;
            this.builderNoProgressTicks = 0;
            return false;
        }

        this.builderNoProgressTicks++;
        return this.builderNoProgressTicks >= BUILDER_CLEAR_NO_PROGRESS_TICKS;
    }

    private void resetBuilderProgressWatch() {
        this.builderProgressAnchor = this.playerNpc.position();
        this.builderNoProgressTicks = 0;
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    private void clearBuilderCorridorState() {
        this.builderCorridorClearActive = false;
        this.builderCorridorDirection = null;
    }

    private static List<BlockPos> builderCorridorObstructionCandidates(
            BlockPos feet,
            BlockPos target,
            Direction fixedDirection
    ) {
        if (feet == null) {
            return List.of();
        }

        List<Direction> directions = new ArrayList<>();
        if (fixedDirection != null) {
            directions.add(fixedDirection);
        } else {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                directions.add(direction);
            }
            if (target != null) {
                directions.sort(Comparator.comparingDouble(direction -> feet.relative(direction).distSqr(target)));
            }
        }

        List<BlockPos> candidates = new ArrayList<>(
                1 + directions.size() * BUILDER_CLEAR_CORRIDOR_DEPTH * (BUILDER_CLEAR_CORRIDOR_HEIGHT + 1));
        candidates.add(feet.above(BUILDER_CLEAR_CORRIDOR_HEIGHT));
        for (Direction direction : directions) {
            for (int depth = 1; depth <= BUILDER_CLEAR_CORRIDOR_DEPTH; depth++) {
                BlockPos columnFeet = feet.relative(direction, depth);
                for (int height = 0; height <= BUILDER_CLEAR_CORRIDOR_HEIGHT; height++) {
                    candidates.add(columnFeet.above(height));
                }
            }
        }
        return candidates;
    }

    private static Direction corridorDirection(BlockPos feet, BlockPos target) {
        if (feet == null || target == null || feet.getY() > target.getY()
                || target.getY() > feet.getY() + BUILDER_CLEAR_CORRIDOR_HEIGHT) {
            return null;
        }
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        if (dz == 0 && Math.abs(dx) >= 1 && Math.abs(dx) <= BUILDER_CLEAR_CORRIDOR_DEPTH) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dx == 0 && Math.abs(dz) >= 1 && Math.abs(dz) <= BUILDER_CLEAR_CORRIDOR_DEPTH) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    private boolean needsVerticalEscape(
            ServerLevel serverLevel,
            BlockPos target,
            boolean allowOpenSkyVerticalRecovery
    ) {
        BlockPos current = this.playerNpc.blockPosition();
        if (target.getY() <= current.getY() + 1) {
            return false;
        }
        return !serverLevel.canSeeSky(current.above())
                // Clearing the grass roof can expose the shaft to sky before the NPC has
                // actually climbed out. Farm-camp returns may pillar after path retries.
                || allowOpenSkyVerticalRecovery
                && this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_BREAKING;
    }

    private boolean tryMoveToFailedRouteRelocation(ServerLevel serverLevel, BlockPos target, String moveDetail) {
        if (this.failedRouteRelocateCooldownTicks > 0) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int radius = FAILED_ROUTE_RELOCATE_RADIUS;
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int horizontalSqr = dx * dx + dz * dz;
                if (horizontalSqr < 4 || horizontalSqr > radiusSqr) {
                    continue;
                }
                for (int dy = -FAILED_ROUTE_RELOCATE_VERTICAL_DOWN; dy <= FAILED_ROUTE_RELOCATE_VERTICAL_UP; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (PathNavigationAi.canStandAt(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            this.lastRouteDebug = this.lastRouteDebug + " relocateCandidates=0";
            return false;
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> this.relocationScore(serverLevel, feet, target, pos))
                .thenComparingDouble(pos -> pos.distSqr(feet)));
        Optional<BlockPos> relocation = this.pathNavigationAi.findReachableRandomizedCandidate(
                serverLevel,
                candidates,
                FAILED_ROUTE_RELOCATE_RANDOM_POOL,
                FAILED_ROUTE_RELOCATE_PATH_CHECKS,
                MAX_SAFE_DROP_BLOCKS
        );
        if (relocation.isEmpty()) {
            this.lastRouteDebug = this.lastRouteDebug + " relocate=none";
            return false;
        }

        BlockPos stand = relocation.get();
        if (!this.pathNavigationAi.moveTo(serverLevel, stand, this.speed, MAX_SAFE_DROP_BLOCKS)) {
            this.lastRouteDebug = this.lastRouteDebug + " relocateMoveFailed @ " + posText(stand);
            return false;
        }

        this.failedRouteRelocateCooldownTicks = FAILED_ROUTE_RELOCATE_COOLDOWN_TICKS;
        this.repathTicks = REPATH_DELAY_TICKS;
        this.routeAttempts = 0;
        this.detail = moveDetail + " (moving around @ " + posText(stand) + ")";
        return true;
    }

    private double relocationScore(ServerLevel serverLevel, BlockPos feet, BlockPos target, BlockPos candidate) {
        double score = candidate.distSqr(target) * 0.35D + candidate.distSqr(feet);
        if (serverLevel.canSeeSky(candidate.above())) {
            score -= 24.0D;
        }
        if (candidate.getY() < feet.getY()) {
            score -= Math.min(18.0D, (feet.getY() - candidate.getY()) * 3.0D);
        }
        Direction directionToTarget = directionToward(feet, target);
        if (directionToTarget != null) {
            BlockPos forward = feet.relative(directionToTarget);
            score += Math.max(0.0D, 8.0D - candidate.distSqr(forward));
        }
        return score;
    }

    private static Direction directionToward(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private String debugText(String prefix) {
        String route = this.lastRouteDebug == null || this.lastRouteDebug.isBlank() ? "route=unknown" : this.lastRouteDebug;
        String clear = this.lastClearDebug == null || this.lastClearDebug.isBlank() ? "clear=unknown" : this.lastClearDebug;
        String pillar = this.lastPillarDebug == null || this.lastPillarDebug.isBlank() ? "pillar=unknown" : this.lastPillarDebug;
        return prefix
                + "; attempts=" + this.routeAttempts
                + "; " + route
                + "; " + clear
                + "; " + pillar;
    }
}
