package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumSet;

/** Frees the NPC's occupied body space after an unsafe teleport or a block placed into it. */
public final class EscapeWallGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.breaking_target_obstruction";
    private static final int MAX_BODY_CANDIDATES = 20;
    private static final int MAX_EPISODE_TICKS = 20 * 31;
    private static final double CONTACT_EPSILON = 0.02D;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle activationThrottle = new CanUseThrottle();
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private BlockPos targetPos;
    private BlockState targetState;
    private int elapsedTicks;
    private boolean finished;
    private boolean selectionAdmitted;

    public EscapeWallGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!this.canAct()
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.activationThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        AABB body = this.playerNpc.getBoundingBox().deflate(CONTACT_EPSILON);
        BlockPos eyes = BlockPos.containing(this.playerNpc.getEyePosition());
        this.selectionAdmitted = false;
        if (this.selectTarget(serverLevel, eyes, body)) {
            return true;
        }
        int minX = Mth.floor(body.minX);
        int maxX = Mth.floor(body.maxX);
        int minY = Mth.floor(body.minY) - 1;
        int maxY = Mth.floor(body.maxY);
        int minZ = Mth.floor(body.minZ);
        int maxZ = Mth.floor(body.maxZ);
        int inspected = 1;
        // Include one cell below: fences and similar shapes may extend above their own cell.
        // Exact shape intersection rejects an ordinary floor, mere wall contact, and openings.
        for (int y = maxY; y >= minY && inspected < MAX_BODY_CANDIDATES; y--) {
            for (int x = minX; x <= maxX && inspected < MAX_BODY_CANDIDATES; x++) {
                for (int z = minZ; z <= maxZ && inspected < MAX_BODY_CANDIDATES; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (candidate.equals(eyes)) {
                        continue;
                    }
                    inspected++;
                    if (this.selectTarget(serverLevel, candidate, body)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished && this.targetPos != null
                && this.elapsedTicks < MAX_EPISODE_TICKS && this.canAct();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // The running fast path only validates/mines the one selected body block. It never
        // searches for another block or builds a path; a further obstruction starts a new pass.
        return true;
    }

    @Override
    public void start() {
        this.finished = false;
        this.elapsedTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.playerNpc.setCurrentAiDetail("freeing occupied body space");
    }

    @Override
    public void tick() {
        if (!this.canAct()
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.targetPos == null
                || ++this.elapsedTicks > MAX_EPISODE_TICKS
                || !serverLevel.hasChunkAt(this.targetPos)) {
            this.finish();
            return;
        }
        BlockState currentState = serverLevel.getBlockState(this.targetPos);
        if (currentState != this.targetState
                || !this.canClear(serverLevel, this.targetPos, currentState,
                this.playerNpc.getBoundingBox().deflate(CONTACT_EPSILON))) {
            this.finish();
            return;
        }
        this.playerNpc.getNavigation().stop();
        // Unlike route clearing, the selected block already intersects the NPC's body. No
        // external stand or eye-to-outline ray can be required when the eye is inside a wall.
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel, this.targetPos, state -> state == this.targetState,
                1, "freeing occupied body space"
        );
        if (result != BreakingBlockAi.TickResult.RUNNING) {
            this.finish();
        }
    }

    @Override
    public void stop() {
        this.finish();
        this.playerNpc.getNavigation().stop();
        this.targetPos = null;
        this.targetState = null;
        this.activationThrottle.retryIn(this.playerNpc, 20 + this.playerNpc.getRandom().nextInt(11));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean canAct() {
        return this.playerNpc.isAlive() && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger();
    }

    private void finish() {
        this.finished = true;
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
    }

    private boolean selectTarget(ServerLevel serverLevel, BlockPos pos, AABB body) {
        if (!serverLevel.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        // Air/floor contact is the common case. Prove overlap cheaply before borrowing the
        // optional slice, so an empty safety probe does not starve actual navigation starts.
        if (!this.intersectsBody(serverLevel, pos, state, body)) {
            return false;
        }
        if (!this.selectionAdmitted) {
            this.selectionAdmitted = PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc);
            if (!this.selectionAdmitted) {
                return false;
            }
        }
        if (!this.canClear(serverLevel, pos, state, body)) {
            return false;
        }
        this.targetPos = pos.immutable();
        this.targetState = state;
        return true;
    }

    private boolean canClear(ServerLevel serverLevel, BlockPos pos, BlockState state, AABB body) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || state.isAir() || state.getDestroySpeed(serverLevel, pos) < 0.0F
                || !state.getFluidState().isEmpty()
                || serverLevel.getBlockEntity(pos) != null
                || state.getBlock() instanceof CropBlock
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                || CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)) {
            return false;
        }
        if (!this.intersectsBody(serverLevel, pos, state, body)) {
            return false;
        }
        // Do not open a fluid source into the occupied cell or collapse a gravity column onto
        // the NPC. Unknown neighboring terrain is unsafe, never a reason to load another chunk.
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!serverLevel.hasChunkAt(neighbor)
                    || !serverLevel.getFluidState(neighbor).isEmpty()
                    || direction == Direction.UP
                    && serverLevel.getBlockState(neighbor).getBlock() instanceof FallingBlock) {
                return false;
            }
        }
        return true;
    }

    private boolean intersectsBody(ServerLevel serverLevel, BlockPos pos, BlockState state, AABB body) {
        if (state.isAir()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(serverLevel, pos, CollisionContext.of(this.playerNpc));
        return !shape.isEmpty() && Shapes.joinIsNotEmpty(
                shape.move(pos.getX(), pos.getY(), pos.getZ()), Shapes.create(body), BooleanOp.AND);
    }
}
