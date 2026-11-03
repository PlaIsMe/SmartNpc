package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.stream.StreamSupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

public class PlayerNpcProjectileBlockGoal extends Goal {
    private static final double PROJECTILE_SCAN_RADIUS = 8.0D;
    private static final double PROJECTILE_PREDICTION_TICKS = 8.0D;
    private static final double TARGET_BOX_INFLATE = 0.45D;
    private static final int PLACE_INTERVAL_TICKS = 2;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final Queue<BlockPos> placementQueue = new ArrayDeque<>();
    private Projectile projectile;
    private UUID lastConsideredProjectile;
    private int nextProjectileScanTick;
    private int placeDelayTicks;
    private boolean finished;

    public PlayerNpcProjectileBlockGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || !this.playerNpc.onGround()
                || this.playerNpc.isHealing()
                || this.playerNpc.hasPlaceBlockParryCooldown()) {
            return false;
        }
        if (this.playerNpc.tickCount < this.nextProjectileScanTick) {
            return false;
        }
        this.nextProjectileScanTick = this.playerNpc.tickCount + 2 + this.playerNpc.getRandom().nextInt(2);
        if (!InventoryUtils.hasItem(this.playerNpc, this::isDefensiveBlock)) {
            return false;
        }

        Projectile incomingProjectile = this.findIncomingProjectile(serverLevel);
        if (incomingProjectile == null) {
            this.lastConsideredProjectile = null;
            return false;
        }
        if (incomingProjectile.getUUID().equals(this.lastConsideredProjectile)) {
            return false;
        }

        this.lastConsideredProjectile = incomingProjectile.getUUID();
        if (this.playerNpc.getRandom().nextDouble() > this.playerNpc.getPlaceBlockToParryChance()) {
            return false;
        }

        List<BlockPos> placements = this.findPlacementPattern(serverLevel, incomingProjectile);
        if (placements.isEmpty()) {
            return false;
        }

        this.projectile = incomingProjectile;
        this.placementQueue.clear();
        this.placementQueue.addAll(placements);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.level() instanceof ServerLevel
                && !this.placementQueue.isEmpty()
                && InventoryUtils.hasItem(this.playerNpc, this::isDefensiveBlock);
    }

    @Override
    public void start() {
        this.finished = false;
        this.placeDelayTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setPlaceBlockParryCooldown();
        this.playerNpc.setCurrentAiState("ai.player_npc.blocking_projectile");
        // Build the supported two-block core together. Waiting multiple selector ticks between
        // the base and head-height block lets fast arrows pass over the unfinished defense.
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            for (int i = 0; i < 2 && !this.placementQueue.isEmpty(); i++) {
                this.placeIfReplaceable(serverLevel, this.placementQueue.poll());
            }
            this.placeDelayTicks = PLACE_INTERVAL_TICKS;
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finished = true;
            return;
        }

        if (this.projectile != null && this.projectile.isAlive()) {
            this.playerNpc.getLookControl().setLookAt(this.projectile, 60.0F, 60.0F);
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return;
        }

        while (!this.placementQueue.isEmpty()) {
            BlockPos placePos = this.placementQueue.poll();
            if (this.placeIfReplaceable(serverLevel, placePos)) {
                this.placeDelayTicks = PLACE_INTERVAL_TICKS;
                return;
            }
        }

        this.finished = true;
    }

    @Override
    public void stop() {
        this.placementQueue.clear();
        this.projectile = null;
        this.placeDelayTicks = 0;
        this.finished = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private Projectile findIncomingProjectile(ServerLevel serverLevel) {
        AABB targetBox = this.playerNpc.getBoundingBox().inflate(TARGET_BOX_INFLATE);
        Projectile closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Projectile projectile : serverLevel.getEntitiesOfClass(
                Projectile.class,
                this.playerNpc.getBoundingBox().inflate(PROJECTILE_SCAN_RADIUS),
                this::isThreateningProjectile
        )) {
            Vec3 velocity = projectile.getDeltaMovement();
            Vec3 start = projectile.position();
            Vec3 toTarget = targetBox.getCenter().subtract(start);
            if (velocity.lengthSqr() < 1.0E-6D || velocity.dot(toTarget) <= 0.0D) {
                continue;
            }

            Vec3 end = start.add(velocity.scale(PROJECTILE_PREDICTION_TICKS));
            if (targetBox.clip(start, end).isEmpty()) {
                continue;
            }

            double distance = this.playerNpc.distanceToSqr(projectile);
            if (distance < closestDistance) {
                closest = projectile;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private boolean isThreateningProjectile(Projectile projectile) {
        if (projectile == null || !projectile.isAlive() || projectile.isRemoved()) {
            return false;
        }

        Entity owner = projectile.getOwner();
        if (owner == this.playerNpc || (owner != null && this.playerNpc.isAlliedTo(owner))) {
            return false;
        }

        if (projectile instanceof AbstractThrownPotion thrownPotion) {
            Iterable<net.minecraft.world.effect.MobEffectInstance> effects = thrownPotion.getItem()
                    .getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY)
                    .getAllEffects();
            if (!effects.iterator().hasNext()
                    || StreamSupport.stream(effects.spliterator(), false)
                    .allMatch(effect -> effect.getEffect().value().isBeneficial())) {
                return false;
            }
        }
        return true;
    }

    private List<BlockPos> findPlacementPattern(ServerLevel serverLevel, Projectile projectile) {
        Direction threatDirection = this.getThreatDirection(projectile);
        Direction sideDirection = threatDirection.getClockWise();
        BlockPos center = this.playerNpc.blockPosition().relative(threatDirection);
        List<BlockPos> placements = new ArrayList<>();
        BlockPos[] candidates = {
                center,
                center.above(),
                center.relative(sideDirection),
                center.relative(sideDirection.getOpposite()),
                center.relative(sideDirection).above(),
                center.relative(sideDirection.getOpposite()).above()
        };
        for (BlockPos candidate : candidates) {
            if (this.canPlaceAt(serverLevel, candidate)) {
                placements.add(candidate.immutable());
            }
        }
        return placements;
    }

    private Direction getThreatDirection(Projectile projectile) {
        double dx = projectile.getX() - this.playerNpc.getX();
        double dz = projectile.getZ() - this.playerNpc.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        if (Math.abs(dz) > 1.0E-6D) {
            return dz >= 0.0D ? Direction.SOUTH : Direction.NORTH;
        }
        return this.playerNpc.getDirection();
    }

    private boolean placeIfReplaceable(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canPlaceAt(serverLevel, pos)) {
            return false;
        }

        ItemStack blockStack = InventoryUtils.consumeItem(this.playerNpc, this::isDefensiveBlock, 1).orElse(ItemStack.EMPTY);
        BlockState blockState = InventoryUtils.getBlockState(blockStack);
        if (blockStack.isEmpty() || blockState == null || !blockState.canOcclude()) {
            this.giveOrDrop(blockStack);
            return false;
        }

        if (!this.hasPlacementSupport(serverLevel, pos)
                || !this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, blockState)
                || !this.placingBlockAi.placeBlock(serverLevel, pos, blockState)) {
            this.giveOrDrop(blockStack);
            return false;
        }
        return true;
    }

    private boolean canPlaceAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getFluidState(pos).isEmpty()
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                && serverLevel.getBlockState(pos).canBeReplaced();
    }

    private boolean hasPlacementSupport(ServerLevel serverLevel, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState belowState = serverLevel.getBlockState(below);
        if (belowState.isFaceSturdy(serverLevel, below, Direction.UP)
                || !belowState.getCollisionShape(serverLevel, below).isEmpty()) {
            return true;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(direction);
            if (serverLevel.hasChunkAt(neighbor)
                    && !serverLevel.getBlockState(neighbor).getCollisionShape(serverLevel, neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isDefensiveBlock(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
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

    private void giveOrDrop(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }
}
