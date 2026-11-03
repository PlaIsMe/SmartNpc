package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.CombatToolCraftAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class BreakTargetObstructionGoal extends Goal {
    private static final double MAX_TARGET_DISTANCE_SQR = 28.0D * 28.0D;
    private static final double BREAK_DISTANCE_SQR = 3.25D * 3.25D;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_GOAL_TICKS = 20 * 40;
    private static final int HIGH_TARGET_PILLAR_REQUEST_TICKS = 20 * 8;
    private static final int CAN_USE_CHECK_INTERVAL_TICKS = 20;
    private static final float COMBAT_PATH_NODE_MULTIPLIER = 0.15F;

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final CombatToolCraftAi combatToolCraftAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(CAN_USE_CHECK_INTERVAL_TICKS);
    private LivingEntity target;
    private BlockPos obstructionPos;
    private int obstructionRay;
    private int nextSelectionTick;
    private int nextRepathTick;
    private int goalTicks;
    private boolean finished;
    private int standSearchCursor;
    private long pathAdmissionTick = Long.MIN_VALUE;
    private int pathAttemptsThisTick;

    public BreakTargetObstructionGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.combatToolCraftAi = new CombatToolCraftAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive() || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger() || this.playerNpc.isHealing()) {
            return false;
        }
        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (!this.isValidTarget(currentTarget)
                || this.playerNpc.distanceToSqr(currentTarget) > MAX_TARGET_DISTANCE_SQR
                || !this.canUseThrottle.canCheck(this.playerNpc)
                || !this.hasLoadedTargetCorridor(serverLevel, currentTarget)
                || !this.tryAcquirePathBatch()) {
            return false;
        }
        Path targetPath = this.createBoundedPath(currentTarget.blockPosition());
        if (isReachablePath(targetPath)) {
            return false;
        }
        if (this.isHighTargetPillarCandidate(currentTarget)) {
            this.playerNpc.requestUpwardEscapeTo(currentTarget.blockPosition(), HIGH_TARGET_PILLAR_REQUEST_TICKS);
            return false;
        }
        Obstruction candidate = this.findTargetObstruction(serverLevel, currentTarget);
        if (candidate == null) {
            return false;
        }
        this.target = currentTarget;
        this.selectObstruction(candidate);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished && this.goalTicks < MAX_GOAL_TICKS
                && this.isValidTarget(this.target) && this.playerNpc.getTarget() == this.target
                && this.playerNpc.distanceToSqr(this.target) <= MAX_TARGET_DISTANCE_SQR
                && !this.isHighTargetPillarCandidate(this.target)
                && this.playerNpc.isAlive() && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger() && !this.playerNpc.isHealing();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Physical mining advances in server ticks. Discovery, A* and approach decisions keep
        // their explicit >=20-tick deadlines; the intervening ticks only validate/work one block.
        return true;
    }

    @Override
    public void start() {
        this.goalTicks = 0;
        this.finished = false;
        this.nextRepathTick = this.playerNpc.tickCount;
        this.nextSelectionTick = this.playerNpc.tickCount + CAN_USE_CHECK_INTERVAL_TICKS;
        this.playerNpc.markCombatProgress();
        this.playerNpc.setCurrentAiState("ai.player_npc.breaking_target_obstruction");
        this.updateTaskDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || !this.canContinueToUse()) {
            this.finished = true;
            return;
        }
        this.goalTicks++;
        // Damage callbacks may publish "retaliating" while this goal still owns the action.
        this.playerNpc.setCurrentAiState("ai.player_npc.breaking_target_obstruction");
        if (!this.hasLoadedTargetCorridor(serverLevel, this.target)) {
            this.finished = true;
            return;
        }

        // Opening eye-level glass is not completion: path/body passage decides the next block.
        // Discovery/reachability is cadenced; the current exact ray is revalidated before mining.
        if (this.playerNpc.tickCount >= this.nextSelectionTick) {
            this.nextSelectionTick = this.playerNpc.tickCount + CAN_USE_CHECK_INTERVAL_TICKS;
            if (this.tryAcquirePathBatch()) {
                if (isReachablePath(this.createBoundedPath(this.target.blockPosition()))) {
                    this.finished = true;
                    return;
                }
                Obstruction candidate = this.findTargetObstruction(serverLevel, this.target);
                if (candidate == null) {
                    this.finished = true;
                    return;
                }
                this.selectObstruction(candidate);
            }
        }
        if (this.obstructionPos == null) {
            return;
        }
        BlockPos selected = this.obstructionPos;
        if (!serverLevel.hasChunkAt(selected)
                || !this.isCombatObstruction(serverLevel, selected, serverLevel.getBlockState(selected))) {
            this.breakingBlockAi.stop();
            this.combatToolCraftAi.stop();
            this.obstructionPos = null;
            return;
        }
        if (!this.isCurrentRayHit(serverLevel, this.target, selected, this.obstructionRay)) {
            // A small target/stance movement can move the old sample off this block while
            // another body sample still hits it. Wait for the admitted ray pass without
            // discarding earned progress or mining an unvalidated target in the meantime.
            this.breakingBlockAi.pause();
            return;
        }
        if (this.distanceToBlockCenterSqr(selected) > BREAK_DISTANCE_SQR
                || !ClearBlockAi.canBreakFromCurrentStand(serverLevel, this.playerNpc, selected)) {
            this.breakingBlockAi.pause();
            this.combatToolCraftAi.stop();
            if (this.playerNpc.tickCount >= this.nextRepathTick) {
                this.nextRepathTick = this.playerNpc.tickCount + REPATH_INTERVAL_TICKS;
                if (this.tryAcquirePathBatch()) {
                    this.moveNearObstruction(serverLevel);
                }
            }
            this.updateTaskDetail();
            return;
        }
        this.playerNpc.getNavigation().stop();
        this.playerNpc.markCombatProgress();
        BlockState state = serverLevel.getBlockState(selected);
        if (!this.breakingBlockAi.isRunning()
                && this.combatToolCraftAi.tick(serverLevel, state, selected)) {
            return;
        }
        if (!this.toolAi.hasPreferredToolFor(state)) {
            this.toolAi.equipEmptyMainHand();
        }
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(serverLevel, selected,
                current -> this.isCombatObstruction(serverLevel, selected, current), 0,
                "clearing target passage");
        if (result == BreakingBlockAi.TickResult.FAILED) {
            this.finished = true;
        } else if (result == BreakingBlockAi.TickResult.DONE) {
            this.obstructionPos = null;
            // Keep the existing discovery deadline. Adding a fresh twenty-tick delay here
            // used to stack idle time onto every successful block in the same passage.
        }
    }

    @Override
    public void stop() {
        this.breakingBlockAi.stop();
        this.combatToolCraftAi.stop();
        this.toolAi.restoreMainHand();
        this.target = null;
        this.obstructionPos = null;
        this.goalTicks = 0;
        this.finished = false;
        this.standSearchCursor = 0;
        this.playerNpc.setCurrentAiDetail("");
        this.playerNpc.setCurrentAiState(this.playerNpc.getTarget() == null ? PlayerNpcEntity.AI_IDLE : "ai.player_npc.engaging");
    }

    private boolean isValidTarget(LivingEntity candidate) {
        return candidate != null && candidate.isAlive() && !candidate.isRemoved()
                && !this.playerNpc.isAlliedTo(candidate);
    }

    private boolean isHighTargetPillarCandidate(LivingEntity target) {
        return target.getY() - this.playerNpc.getY() > 2.0D;
    }

    private boolean hasLoadedTargetCorridor(ServerLevel level, LivingEntity target) {
        return PathNavigationAi.hasLoadedChunkCorridor(level, this.playerNpc.blockPosition(), target.blockPosition(), 1);
    }

    private boolean tryAcquirePathBatch() {
        long tick = this.playerNpc.level().getServer().getTickCount();
        if (this.pathAdmissionTick == tick) {
            return true;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
            return false;
        }
        this.pathAdmissionTick = tick;
        this.pathAttemptsThisTick = 0;
        return true;
    }

    private void selectObstruction(Obstruction candidate) {
        if (!candidate.pos().equals(this.obstructionPos)) {
            this.breakingBlockAi.stop();
            this.combatToolCraftAi.stop();
            this.playerNpc.getNavigation().stop();
            this.standSearchCursor = 0;
        }
        this.obstructionPos = candidate.pos();
        this.obstructionRay = candidate.ray();
    }

    private Obstruction findTargetObstruction(ServerLevel level, LivingEntity target) {
        Obstruction nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        boolean nearestInReach = false;
        // One eye ray plus nine lower/mid/upper rays inside the NPC's body width. Only first
        // physical hits qualify; there is no nearby block-volume or side-wall fallback.
        for (int ray = 0; ray < 10; ray++) {
            BlockHitResult hit = this.clipPassageRay(level, target, ray);
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos pos = hit.getBlockPos();
            if (level.hasChunkAt(pos) && this.isCombatObstruction(level, pos, level.getBlockState(pos))) {
                double distance = hit.getLocation().distanceToSqr(this.playerNpc.position());
                boolean inReach = this.distanceToBlockCenterSqr(pos) <= BREAK_DISTANCE_SQR
                        && ClearBlockAi.canBreakFromCurrentStand(level, this.playerNpc, pos);
                if (inReach && pos.equals(this.obstructionPos)) {
                    // Preserve a valid ongoing break even if target movement changes which
                    // body ray hits it, or another block becomes marginally nearer.
                    return new Obstruction(pos.immutable(), ray);
                }
                if (nearest == null || inReach && !nearestInReach
                        || inReach == nearestInReach && distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestInReach = inReach;
                    nearest = new Obstruction(pos.immutable(), ray);
                }
            }
        }
        return nearest;
    }

    private boolean isCurrentRayHit(ServerLevel level, LivingEntity target, BlockPos pos, int ray) {
        BlockHitResult hit = this.clipPassageRay(level, target, ray);
        return hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private BlockHitResult clipPassageRay(ServerLevel level, LivingEntity target, int ray) {
        Vec3 start;
        Vec3 end;
        if (ray == 0) {
            start = this.playerNpc.getEyePosition();
            end = target.getEyePosition();
        } else {
            // Same-level entry only: an angled ankle ray down a slope could select its floor.
            if (Math.abs(target.getY() - this.playerNpc.getY()) > 0.25D) {
                return null;
            }
            Vec3 delta = target.position().subtract(this.playerNpc.position());
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            if (horizontal < 0.1D) {
                return null;
            }
            double radius = Math.max(0.0D, this.playerNpc.getBbWidth() * 0.5D - 0.05D);
            double lateral = ((ray - 1) % 3 - 1) * radius;
            double height = switch ((ray - 1) / 3) {
                case 0 -> 0.1D;
                case 1 -> this.playerNpc.getBbHeight() * 0.5D;
                default -> this.playerNpc.getBbHeight() - 0.1D;
            };
            double y = Math.max(this.playerNpc.getY(), target.getY()) + height;
            double dx = -delta.z / horizontal * lateral;
            double dz = delta.x / horizontal * lateral;
            start = new Vec3(this.playerNpc.getX() + dx, y, this.playerNpc.getZ() + dz);
            end = new Vec3(target.getX() + dx, y, target.getZ() + dz);
        }
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, this.playerNpc));
    }

    private boolean isCombatObstruction(ServerLevel level, BlockPos pos, BlockState state) {
        return level.hasChunkAt(pos) && level.isInWorldBounds(pos)
                && level.getWorldBorder().isWithinBounds(pos)
                && !pos.equals(this.playerNpc.blockPosition().below())
                && !state.isAir() && !state.getCollisionShape(level, pos).isEmpty()
                && state.getDestroySpeed(level, pos) >= 0.0F && state.getFluidState().isEmpty()
                && level.getBlockEntity(pos) == null
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, level, pos)
                && !this.isProtectedHomeBlock(pos)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos);
    }

    private void moveNearObstruction(ServerLevel serverLevel) {
        Path activePath = this.playerNpc.getNavigation().getPath();
        if (activePath != null && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck() && activePath.canReach()) {
            return;
        }
        StandMovePlan plan = this.findStandNear(serverLevel, this.obstructionPos);
        if (plan == null) {
            return;
        }
        if (plan.path() != null) {
            this.playerNpc.getNavigation().moveTo(plan.path(), 1.0D);
        } else {
            this.playerNpc.getMoveControl().setWantedPosition(
                    plan.stand().getX() + 0.5D,
                    plan.stand().getY(),
                    plan.stand().getZ() + 0.5D,
                    1.0D
            );
        }
    }

    private StandMovePlan findStandNear(ServerLevel serverLevel, BlockPos blockPos) {
        if (blockPos == null) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(blockPos.relative(direction));
            candidates.add(blockPos.relative(direction).below());
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        int candidateCount = candidates.size();
        for (int offset = 0; offset < candidateCount; offset++) {
            int candidateIndex = Math.floorMod(this.standSearchCursor + offset, candidateCount);
            BlockPos candidate = candidates.get(candidateIndex);
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)
                    || this.distanceToBlockCenterSqrFrom(immutable, blockPos) > BREAK_DISTANCE_SQR) {
                continue;
            }
            if (this.playerNpc.distanceToSqr(immutable.getX() + 0.5D, immutable.getY(), immutable.getZ() + 0.5D) <= 1.5D * 1.5D) {
                this.standSearchCursor = 0;
                return new StandMovePlan(immutable, null);
            }
            // One synchronous path per repath interval. A miss advances to another stand on the
            // next interval instead of batching every adjacent stand plus a final moveTo path.
            this.standSearchCursor = (candidateIndex + 1) % candidateCount;
            Path path = this.createBoundedPath(immutable);
            if (isReachablePath(path)) {
                return new StandMovePlan(immutable, path);
            }
            return null;
        }
        this.standSearchCursor = 0;
        return null;
    }

    private Path createBoundedPath(BlockPos targetPos) {
        if (!this.tryAcquirePathBatch() || this.pathAttemptsThisTick++ >= 2) {
            return null;
        }
        return PathNavigationAi.createBoundedPath(
                this.playerNpc,
                targetPos,
                COMBAT_PATH_NODE_MULTIPLIER
        );
    }

    private static boolean isReachablePath(Path path) {
        return path != null && path.canReach();
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.hasChunkAt(pos)
                && serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private void updateTaskDetail() {
        if (this.obstructionPos == null || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        BlockState state = serverLevel.getBlockState(this.obstructionPos);
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "%s @ %d %d %d",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                this.obstructionPos.getX(),
                this.obstructionPos.getY(),
                this.obstructionPos.getZ()
        ));
    }

    private double distanceToBlockCenterSqr(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private double distanceToBlockCenterSqrFrom(BlockPos stand, BlockPos target) {
        double dx = stand.getX() + 0.5D - (target.getX() + 0.5D);
        double dy = stand.getY() - (target.getY() + 0.5D);
        double dz = stand.getZ() + 0.5D - (target.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private record Obstruction(BlockPos pos, int ray) {
    }

    private record StandMovePlan(BlockPos stand, Path path) {
    }
}
