package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

public class LowHealthFleeGoal extends Goal {
    private static final float START_HEALTH_RATIO = 0.40F;
    private static final float STOP_HEALTH_RATIO = 0.65F;
    private static final double START_FLEE_DISTANCE_SQR = 18.0D * 18.0D;
    private static final double STOP_FLEE_DISTANCE_SQR = 26.0D * 26.0D;
    // Mob.setSpeed also sets forward input, so sprinting at 1.0 compounds the 0.35
    // movement attribute. sqrt(0.13) / (0.35 * 1.3) ~= 0.79 matches player sprinting.
    private static final double RUN_SPEED = 0.79D;
    private static final int MIN_FLEE_TICKS = 70;
    private static final int MAX_FLEE_TICKS = 130;
    private static final int PATH_RECALCULATE_TICKS = 20;
    private static final int MIN_JUMP_COOLDOWN_TICKS = 12;
    private static final int MAX_JUMP_COOLDOWN_TICKS = 28;
    private static final int POST_FLEE_ESCAPE_COOLDOWN_TICKS = 20 * 5;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;
    private static final float SUPPORT_JUMP_CHANCE = 0.30F;
    private static final int MAX_SUPPORT_PLACE_TICKS = 10;
    private static final double MIN_SUPPORT_PLACE_Y_OFFSET = 1.01D;
    private static final double MAX_SUPPORT_HORIZONTAL_DRIFT_SQR = 1.5D * 1.5D;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final ToolAi toolAi;
    private final CanUseThrottle activationThrottle = new CanUseThrottle();
    private LivingEntity threat;
    private Vec3 fleePos;
    private int fleeTicks;
    private int pathRecalculateTicks;
    private int jumpCooldownTicks;
    private BlockPos jumpSupportPos;
    private int jumpSupportTicks;

    public LowHealthFleeGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.toolAi = new ToolAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (!this.playerNpc.hasInterest(PlayerNpcInterest.COWARD)
                || !this.canMoveForFlee() || !this.activationThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        LivingEntity target = this.playerNpc.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (!this.canFleeFrom(target)) {
            return false;
        }

        double distanceSqr = this.playerNpc.distanceToSqr(target);
        float startHealthRatio = this.getStartHealthRatio(target);
        if (distanceSqr > START_FLEE_DISTANCE_SQR && this.getHealthRatio() > startHealthRatio * 0.65F) {
            return false;
        }
        if (!this.isHighDangerThreat(target)
                && InventoryUtils.hasHealingFood(this.playerNpc)
                && this.playerNpc.getRandom().nextFloat() < 0.45F) {
            return false;
        }

        Vec3 pos = this.findFleePos(target);
        if (pos == null) {
            return false;
        }

        this.threat = target;
        this.fleePos = pos;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.playerNpc.hasInterest(PlayerNpcInterest.COWARD)
                && this.fleeTicks > 0
                && this.threat != null
                && this.threat.isAlive()
                && this.canMoveForFlee()
                && this.getHealthRatio() < this.getStopHealthRatio(this.threat)
                && this.playerNpc.distanceToSqr(this.threat) < STOP_FLEE_DISTANCE_SQR;
    }

    @Override
    public void start() {
        this.fleeTicks = MIN_FLEE_TICKS + this.playerNpc.getRandom().nextInt(MAX_FLEE_TICKS - MIN_FLEE_TICKS + 1);
        this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
        this.jumpCooldownTicks = this.nextJumpCooldown();
        this.playerNpc.clearUpwardEscapeTarget();
        this.playerNpc.setHoleEscapeCooldown(POST_FLEE_ESCAPE_COOLDOWN_TICKS);
        this.playerNpc.setSprinting(true);
        this.playerNpc.setTarget(null);
        this.playerNpc.setCurrentAiState("ai.player_npc.fleeing_low_health");
        this.moveToFleePos();
    }

    @Override
    public void stop() {
        this.playerNpc.setSprinting(false);
        this.threat = null;
        this.fleePos = null;
        this.fleeTicks = 0;
        this.pathRecalculateTicks = 0;
        this.jumpCooldownTicks = 0;
        this.clearJumpSupport();
        this.playerNpc.getNavigation().stop();
        this.playerNpc.clearUpwardEscapeTarget();
        this.playerNpc.setHoleEscapeCooldown(POST_FLEE_ESCAPE_COOLDOWN_TICKS);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    @Override
    public void tick() {
        if (!this.playerNpc.hasInterest(PlayerNpcInterest.COWARD) || !this.canMoveForFlee()) {
            this.clearJumpSupport();
            return;
        }
        this.fleeTicks--;
        this.playerNpc.setTarget(null);

        if (this.threat == null) {
            return;
        }

        if (this.pathRecalculateTicks-- <= 0) {
            Vec3 nextPos = this.findFleePos(this.threat);
            if (nextPos != null) {
                this.fleePos = nextPos;
                this.moveToFleePos();
            }
            this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
        }

        if (this.jumpSupportPos == null && this.fleePos != null) {
            this.playerNpc.getLookControl().setLookAt(this.fleePos.x, this.fleePos.y, this.fleePos.z, 60.0F, 60.0F);
        }

        this.tickRandomJump();
    }

    private boolean canFleeFrom(LivingEntity threat) {
        return this.canMoveForFlee()
                && this.getHealthRatio() <= this.getStartHealthRatio(threat);
    }

    private boolean canMoveForFlee() {
        return !this.playerNpc.level().isClientSide && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isInWaterOrBubble()
                && !this.playerNpc.isInLava();
    }

    private float getHealthRatio() {
        return this.playerNpc.getHealth() / this.playerNpc.getMaxHealth();
    }

    private float getStartHealthRatio(LivingEntity threat) {
        return this.playerNpc.getSmartNpcFleeHealthRatio(threat, START_HEALTH_RATIO);
    }

    private float getStopHealthRatio(LivingEntity threat) {
        float startHealthRatio = this.getStartHealthRatio(threat);
        return Math.max(STOP_HEALTH_RATIO, Math.min(0.95F, startHealthRatio + 0.15F));
    }

    private boolean isHighDangerThreat(LivingEntity threat) {
        return threat != null && this.playerNpc.isSmartNpcCompatHighDangerThreat(threat);
    }

    private Vec3 findFleePos(LivingEntity threat) {
        Vec3 pos = DefaultRandomPos.getPosAway(this.playerNpc, 18, 7, threat.position());
        if (pos != null) {
            return pos;
        }

        Vec3 away = this.playerNpc.position().subtract(threat.position());
        if (away.lengthSqr() < 1.0E-4D) {
            away = Vec3.directionFromRotation(0.0F, this.playerNpc.getYRot());
        }
        return this.playerNpc.position().add(away.normalize().scale(16.0D));
    }

    private void moveToFleePos() {
        if (this.fleePos != null) {
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    BlockPos.containing(this.fleePos.x, this.fleePos.y, this.fleePos.z),
                    PATH_NODE_MULTIPLIER
            );
            if (path != null && path.getNodeCount() > 0) {
                this.playerNpc.getNavigation().moveTo(path, RUN_SPEED);
            }
        }
    }

    private void tickRandomJump() {
        if (this.jumpSupportPos != null) {
            this.tickJumpSupport();
            return;
        }

        if (this.jumpCooldownTicks > 0) {
            this.jumpCooldownTicks--;
            return;
        }

        if (this.playerNpc.onGround()
                && !this.playerNpc.isInWaterOrBubble()
                && !this.playerNpc.isInLava()) {
            BlockPos feet = this.playerNpc.blockPosition();
            if (this.playerNpc.getRandom().nextFloat() < SUPPORT_JUMP_CHANCE
                    && this.canPrepareJumpSupport(feet) && this.equipJumpSupport()) {
                this.jumpSupportPos = feet.immutable();
                this.jumpSupportTicks = 0;
                this.lookDownAt(feet);
            }
            // The custom jump helper adds another forward shove on top of the
            // vanilla sprint-jump impulse, making repeated flee jumps too fast.
            this.playerNpc.getJumpControl().jump();
        }
        this.jumpCooldownTicks = this.nextJumpCooldown();
    }

    private void tickJumpSupport() {
        BlockPos supportPos = this.jumpSupportPos;
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || supportPos == null
                || ++this.jumpSupportTicks > MAX_SUPPORT_PLACE_TICKS
                || this.playerNpc.isInWaterOrBubble()
                || this.playerNpc.isInLava()
                || this.playerNpc.onGround() && this.jumpSupportTicks > 1
                || this.horizontalDistanceSqr(supportPos) > MAX_SUPPORT_HORIZONTAL_DRIFT_SQR) {
            this.clearJumpSupport();
            return;
        }

        this.lookDownAt(supportPos);
        if (this.playerNpc.getBoundingBox().minY < supportPos.getY() + MIN_SUPPORT_PLACE_Y_OFFSET) {
            if (this.playerNpc.getDeltaMovement().y < -0.05D) {
                this.clearJumpSupport();
            }
            return;
        }
        if (!this.canPlaceJumpSupport(serverLevel, supportPos)) {
            this.clearJumpSupport();
            return;
        }

        ItemStack held = this.playerNpc.getMainHandItem();
        BlockState state = InventoryUtils.getBlockState(held);
        if (!this.isEscapeSupportBlock(held)
                || state == null
                || !state.isCollisionShapeFullBlock(serverLevel, supportPos)) {
            this.clearJumpSupport();
            return;
        }
        // Expected body overlap before the jump apex is a wait, not a failed attempt.
        if (!this.placingBlockAi.canPlaceWithoutClipping(serverLevel, supportPos, state)) {
            return;
        }
        if (!this.placingBlockAi.placeHeldBlock(serverLevel, supportPos, state)) {
            this.clearJumpSupport();
            return;
        }

        this.playerNpc.markTemporaryPillarSupport(supportPos);
        this.clearJumpSupport();
    }

    private boolean canPrepareJumpSupport(BlockPos feet) {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !(this.isEscapeSupportBlock(this.playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(this.playerNpc, this::isEscapeSupportBlock))
                || !this.canPlaceJumpSupport(serverLevel, feet)) {
            return false;
        }

        BlockPos below = feet.below();
        BlockState support = serverLevel.getBlockState(below);
        return support.isFaceSturdy(serverLevel, below, Direction.UP)
                || !support.getCollisionShape(serverLevel, below).isEmpty();
    }

    private boolean canPlaceJumpSupport(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)
                || !serverLevel.hasChunkAt(pos.below())
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)) {
            return false;
        }

        FluidState fluidState = serverLevel.getFluidState(pos);
        BlockPos below = pos.below();
        BlockState support = serverLevel.getBlockState(below);
        return fluidState.isEmpty() && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockEntity(pos) == null
                && support.getFluidState().isEmpty()
                && support.isFaceSturdy(serverLevel, below, Direction.UP);
    }

    private boolean isEscapeSupportBlock(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.hasCustomHoverName()
                || stack.isEnchanted()
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.is(Items.FURNACE)
                || stack.getItem() instanceof BedItem
                || blockItem.getBlock().defaultBlockState().is(Blocks.TORCH)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.canOcclude()
                && state.getFluidState().isEmpty()
                && (stack.is(Items.DIRT)
                || stack.is(Items.GRASS_BLOCK)
                || stack.is(Items.COARSE_DIRT)
                || stack.is(Items.ROOTED_DIRT)
                || stack.is(Items.PODZOL)
                || stack.is(ItemTags.PLANKS) && !this.playerNpc.shouldPrioritizeLogGathering()
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.COBBLED_DEEPSLATE));
    }

    private double horizontalDistanceSqr(BlockPos pos) {
        double dx = this.playerNpc.getX() - (pos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private void lookDownAt(BlockPos pos) {
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() + 0.1D,
                pos.getZ() + 0.5D,
                70.0F,
                70.0F
        );
    }

    private void clearJumpSupport() {
        this.jumpSupportPos = null;
        this.jumpSupportTicks = 0;
        this.toolAi.restoreMainHand();
    }

    private boolean equipJumpSupport() {
        if (this.isEscapeSupportBlock(this.playerNpc.getMainHandItem())) {
            return true;
        }
        for (int slot = 0; slot < this.playerNpc.getInventory().getContainerSize(); slot++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(slot);
            if (this.isEscapeSupportBlock(stack)) {
                if (this.toolAi.equipItem(stack.getItem())
                        && this.isEscapeSupportBlock(this.playerNpc.getMainHandItem())) {
                    return true;
                }
                this.toolAi.restoreMainHand();
                return false;
            }
        }
        return false;
    }

    private int nextJumpCooldown() {
        return MIN_JUMP_COOLDOWN_TICKS
                + this.playerNpc.getRandom().nextInt(MAX_JUMP_COOLDOWN_TICKS - MIN_JUMP_COOLDOWN_TICKS + 1);
    }
}
