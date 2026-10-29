package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class DescendHighColumnGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.descending_column";
    private static final int REQUIRED_BREAK_TICKS = 16;
    private static final int MAX_GOAL_TICKS = 20 * 30;
    private static final int MAX_DESCENT_STEPS = 24;
    private static final int LOWER_TERRAIN_RADIUS = 6;
    private static final int MIN_COLUMN_DROP_BLOCKS = 3;
    private static final int MAX_SOLID_SIDE_SUPPORTS = 1;
    private static final int MIN_ENCLOSED_BODY_SIDES = 2;
    private static final int LOWER_TERRAIN_COLUMNS_PER_PASS = 4;
    private static final List<BlockPos> LOWER_TERRAIN_OFFSETS = createLowerTerrainOffsets();

    private final PlayerNpcEntity playerNpc;
    private final TerraformBuildSiteGoal terraformBuildSiteGoal;
    private final DigDownForStoneGoal digDownForStoneGoal;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(10);
    private BlockPos floorTarget;
    private int goalTicks;
    private int descentSteps;
    private boolean finished;
    private boolean floorTargetOwnedTemporary;
    private int lowerTerrainColumnCursor;
    private BlockPos lowerTerrainSearchOrigin;
    private boolean workerSlotPaused;

    public DescendHighColumnGoal(PlayerNpcEntity playerNpc, TerraformBuildSiteGoal terraformBuildSiteGoal, DigDownForStoneGoal digDownForStoneGoal) {
        this.playerNpc = playerNpc;
        this.terraformBuildSiteGoal = terraformBuildSiteGoal;
        this.digDownForStoneGoal = digDownForStoneGoal;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.isGatheringJobRunning()
                || this.digDownForStoneGoal.isMiningHandoffPending()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.terraformBuildSiteGoal.hasPendingSupportFillEscapeHandoff(serverLevel)
                || ReturnHomeGoal.hasUphillBuilderReturnIntent(this.playerNpc, serverLevel)
                || this.shouldYieldToMiningSupplyWork()
                || !this.shouldRunForCurrentState()) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.floorTarget = this.findDescendFloor(serverLevel);
            this.workerSlotPaused = false;
            return this.floorTarget != null;
        } finally {
            PlayerNpcPerformanceMonitor.recordGoalWork(
                    this.playerNpc,
                    this.getClass().getSimpleName() + ".canUse",
                    timing
            );
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            return false;
        }
        return !this.finished
                && this.goalTicks < MAX_GOAL_TICKS
                && this.descentSteps < MAX_DESCENT_STEPS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && !this.terraformBuildSiteGoal.hasPendingSupportFillEscapeHandoff(serverLevel)
                && !ReturnHomeGoal.hasUphillBuilderReturnIntent(this.playerNpc, serverLevel);
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
        this.descentSteps = 0;
        this.finished = false;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.pauseForWorkerSlotLoss();
            return;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finished = true;
            return;
        }
        if (this.playerNpc.getUpwardEscapeTarget() != null
                || this.terraformBuildSiteGoal.hasPendingSupportFillEscapeHandoff(serverLevel)
                || ReturnHomeGoal.hasUphillBuilderReturnIntent(this.playerNpc, serverLevel)) {
            this.breakingBlockAi.stop();
            this.finished = true;
            return;
        }

        this.goalTicks++;
        if (!this.playerNpc.onGround()) {
            this.playerNpc.fallDistance = 0.0F;
            this.updateDetail();
            return;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isInsideEnclosedBodyColumn(serverLevel, feet)) {
            this.deferDescentInsideHole(feet);
            this.finished = true;
            return;
        }

        if (this.floorTarget != null
                && this.floorTargetOwnedTemporary
                && !this.playerNpc.isTemporaryPillarSupport(this.floorTarget)) {
            // Ownership disappeared or the tracked block changed after admission. Never reinterpret
            // that stale target as an ordinary natural column during the same descent episode.
            this.finished = true;
            return;
        }
        if (this.floorTarget == null || !this.canBreakColumnBlock(serverLevel, this.floorTarget, serverLevel.getBlockState(this.floorTarget))) {
            this.floorTarget = this.findDescendFloor(serverLevel);
            if (this.floorTarget == null) {
                this.finished = true;
                return;
            }
        }

        if (!this.hasSafeLandingAfterDescent(serverLevel, this.floorTarget)) {
            this.breakingBlockAi.stop();
            this.traceUnsafeLanding(this.floorTarget);
            this.finished = true;
            return;
        }

        this.playerNpc.getNavigation().stop();
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.floorTarget,
                state -> this.canBreakColumnBlock(serverLevel, this.floorTarget, state)
                        && this.hasSafeLandingAfterDescent(serverLevel, this.floorTarget)
                        && !ReturnHomeGoal.hasUphillBuilderReturnIntent(this.playerNpc, serverLevel),
                REQUIRED_BREAK_TICKS,
                "pillar down"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }
        if (result == BreakingBlockAi.TickResult.DONE) {
            this.playerNpc.forgetTemporaryPillarSupport(this.floorTarget);
            this.descentSteps++;
            this.playerNpc.fallDistance = 0.0F;
            this.floorTarget = null;
            this.floorTargetOwnedTemporary = false;
            this.updateDetail();
            return;
        }

        this.finished = true;
    }

    @Override
    public void stop() {
        if (this.workerSlotPaused || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.pauseForWorkerSlotLoss();
            return;
        }
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.floorTarget = null;
        this.floorTargetOwnedTemporary = false;
        this.goalTicks = 0;
        this.descentSteps = 0;
        this.finished = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void pauseForWorkerSlotLoss() {
        this.playerNpc.getNavigation().stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.workerSlotPaused = true;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        // Keep floorTarget and the entity's persisted ownership ledger. Admission revalidates the
        // exact block after this NPC earns a later worker slot.
    }

    private boolean shouldRunForCurrentState() {
        String state = this.playerNpc.getCurrentAiState();
        return PlayerNpcEntity.AI_IDLE.equals(state)
                || "ai.player_npc.returning_home".equals(state);
    }

    private boolean shouldYieldToMiningSupplyWork() {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && (this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.shouldPrioritizeCobblestoneGathering());
    }

    private BlockPos findDescendFloor(ServerLevel serverLevel) {
        this.floorTargetOwnedTemporary = false;
        if (!this.playerNpc.onGround()) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isInsideEnclosedBodyColumn(serverLevel, feet)) {
            this.deferDescentInsideHole(feet);
            return null;
        }
        BlockPos floor = feet.below();
        BlockState floorState = serverLevel.getBlockState(floor);
        boolean ownedTemporarySupport = this.playerNpc.isTemporaryPillarSupport(floor);
        if (!this.canBreakColumnBlock(serverLevel, floor, floorState)) {
            return null;
        }
        // Ordinary hillside/support blocks are not pillar-descent candidates. Prove the
        // column first so speculative idle checks do not report a failed landing for a
        // resource dig site that this goal would never be allowed to descend from.
        if (!ownedTemporarySupport && (!this.isNarrowColumnTop(serverLevel, floor)
                || !this.hasLowerWalkableTerrainNearby(serverLevel, feet))) {
            return null;
        }
        if (!this.hasSafeLandingAfterDescent(serverLevel, floor)) {
            this.traceUnsafeLanding(floor);
            return null;
        }
        if (ownedTemporarySupport) {
            this.floorTargetOwnedTemporary = true;
            return floor.immutable();
        }
        return floor.immutable();
    }

    private boolean hasSafeLandingAfterDescent(ServerLevel serverLevel, BlockPos landingFeet) {
        if (landingFeet == null
                || !serverLevel.hasChunkAt(landingFeet)
                || !serverLevel.getBlockState(landingFeet.below()).isSolidRender(serverLevel, landingFeet.below())
                || this.isInsideEnclosedBodyColumn(serverLevel, landingFeet)) {
            return false;
        }
        // Evaluate the body position AFTER removing this floor, not only the current feet.
        // Another owned support below proves support, not an exit: a stacked column can pass
        // back through a mine's rim and make EscapeHole immediately replace the removed block.
        // Keep open stacked-column descent, and require a surface exit at its final support.
        return !this.playerNpc.isTemporaryPillarSupport(landingFeet)
                || this.playerNpc.isTemporaryPillarSupport(landingFeet.below())
                || this.hasSafeSurfaceExitAfterLanding(serverLevel, landingFeet);
    }

    private void traceUnsafeLanding(BlockPos landingFeet) {
        this.playerNpc.setIdleTraceDetail("pillar descent deferred: unsafe landing @ "
                + landingFeet.getX() + " " + landingFeet.getY() + " " + landingFeet.getZ(), 40);
    }

    private boolean hasSafeSurfaceExitAfterLanding(ServerLevel serverLevel, BlockPos landingFeet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stepOff = landingFeet.relative(direction);
            if (serverLevel.hasChunkAt(stepOff)
                    && PathNavigationAi.canStandAt(serverLevel, stepOff)
                    && serverLevel.getFluidState(stepOff).isEmpty()
                    && serverLevel.getFluidState(stepOff.above()).isEmpty()
                    && serverLevel.canSeeSky(stepOff.above())) {
                return true;
            }
        }
        return false;
    }

    private boolean canBreakColumnBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        // Completed pillar supports stay remembered for placement safety. Admission separately
        // requires either conservative natural-column evidence or an owned-support landing exit.
        return pos != null
                && serverLevel.isInWorldBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !this.isProtectedHomeBlock(pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        // Foundation supports below the home origin are part of the build footprint too.
        // Removing them immediately after Terraform's escape recreates the same fill/climb job.
        return home.isPresent() && (PlayerNpcHomeUtil.isInside(home.get(), pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos));
    }

    private boolean isNarrowColumnTop(ServerLevel serverLevel, BlockPos floor) {
        int solidSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = floor.relative(direction);
            if (serverLevel.getBlockState(side).isSolidRender(serverLevel, side)) {
                solidSides++;
            }
        }
        return solidSides <= MAX_SOLID_SIDE_SUPPORTS;
    }

    /**
     * A descent support may look like an isolated pillar at its own Y level while the NPC's body
     * is still surrounded by the higher rim of a 1x1, 1x2, or 2x2 shaft. In that situation,
     * breaking downward only deepens the hole and can undo blocks just placed by hole escape.
     */
    private boolean isInsideEnclosedBodyColumn(ServerLevel serverLevel, BlockPos feet) {
        int enclosedSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            if (!serverLevel.hasChunkAt(side)) {
                return true;
            }
            if (hasBlockingCollision(serverLevel, side)
                    || hasBlockingCollision(serverLevel, side.above())) {
                enclosedSides++;
                if (enclosedSides >= MIN_ENCLOSED_BODY_SIDES) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        return !serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty();
    }

    private void deferDescentInsideHole(BlockPos feet) {
        this.playerNpc.setIdleTraceDetail(
                "pillar descent deferred: NPC is inside an enclosed hole @ "
                        + feet.getX() + " " + feet.getY() + " " + feet.getZ(),
                40
        );
    }

    private boolean hasLowerWalkableTerrainNearby(ServerLevel serverLevel, BlockPos feet) {
        if (this.lowerTerrainSearchOrigin == null || !this.lowerTerrainSearchOrigin.equals(feet)) {
            this.lowerTerrainSearchOrigin = feet.immutable();
            this.lowerTerrainColumnCursor = 0;
        }
        int size = LOWER_TERRAIN_OFFSETS.size();
        int checked = 0;
        while (checked++ < LOWER_TERRAIN_COLUMNS_PER_PASS && this.lowerTerrainColumnCursor < size) {
            BlockPos offset = LOWER_TERRAIN_OFFSETS.get(this.lowerTerrainColumnCursor++);
            int x = feet.getX() + offset.getX();
            int z = feet.getZ() + offset.getZ();
            if (!serverLevel.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (feet.getY() - candidate.getY() >= MIN_COLUMN_DROP_BLOCKS
                    && PathNavigationAi.canStandAt(serverLevel, candidate)) {
                this.lowerTerrainColumnCursor = 0;
                this.lowerTerrainSearchOrigin = null;
                return true;
            }
        }
        if (this.lowerTerrainColumnCursor >= size) {
            this.lowerTerrainColumnCursor = 0;
            this.lowerTerrainSearchOrigin = null;
        }
        return false;
    }

    private static List<BlockPos> createLowerTerrainOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        int radiusSqr = LOWER_TERRAIN_RADIUS * LOWER_TERRAIN_RADIUS;
        for (int dx = -LOWER_TERRAIN_RADIUS; dx <= LOWER_TERRAIN_RADIUS; dx++) {
            for (int dz = -LOWER_TERRAIN_RADIUS; dz <= LOWER_TERRAIN_RADIUS; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr > 0 && distanceSqr <= radiusSqr) {
                    offsets.add(new BlockPos(dx, 0, dz));
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(offset -> offset.distSqr(BlockPos.ZERO)));
        return List.copyOf(offsets);
    }

    private void updateDetail() {
        if (this.floorTarget == null) {
            this.playerNpc.setCurrentAiDetail("pillar down");
            return;
        }
        this.playerNpc.setCurrentAiDetail("pillar down @ "
                + this.floorTarget.getX() + " "
                + this.floorTarget.getY() + " "
                + this.floorTarget.getZ());
    }
}
