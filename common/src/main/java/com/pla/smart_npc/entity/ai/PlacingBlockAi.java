package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcCollisionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class PlacingBlockAi {
    public static final DelayProfile PLAYER_LIKE_BUILD_DELAY = new DelayProfile(10, 28, 0.22F, 28);
    public static final DelayProfile QUICK_UTILITY_DELAY = new DelayProfile(6, 12, 0.12F, 10);
    public static final DelayProfile PILLAR_PLACE_DELAY = new DelayProfile(5, 8, 0.0F, 0);
    public static final DelayProfile SCAFFOLD_PLACE_DELAY = new DelayProfile(2, 5, 0.0F, 0);

    private final PlayerNpcEntity playerNpc;
    private int activeDelayTicks = -1;

    public PlacingBlockAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public boolean tickDelay(DelayProfile profile) {
        if (this.activeDelayTicks < 0) {
            this.activeDelayTicks = rollDelay(this.playerNpc.getRandom(), profile);
        }
        if (this.activeDelayTicks > 0) {
            this.activeDelayTicks--;
            return true;
        }
        return false;
    }

    public void resetDelay() {
        this.activeDelayTicks = -1;
    }

    public boolean placeBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.placeBlock(serverLevel, pos, state, true);
    }

    public boolean placeBlock(ServerLevel serverLevel, BlockPos pos, BlockState state, boolean playEffects) {
        BlockState placementState = stateForPlacement(serverLevel, pos, state);
        if (!serverLevel.setBlockAndUpdate(pos, placementState)) {
            return false;
        }
        updateAdjacentChestHalf(serverLevel, pos, placementState);
        if (playEffects) {
            this.playPlaceEffects(serverLevel, pos, placementState);
        }
        return true;
    }

    public boolean placeHeldBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.placeHeldBlock(serverLevel, pos, state, true);
    }

    public boolean placeHeldBlock(ServerLevel serverLevel, BlockPos pos, BlockState state, boolean playEffects) {
        if (this.playerNpc.getMainHandItem().isEmpty()) {
            return false;
        }
        if (!this.placeBlock(serverLevel, pos, state, playEffects)) {
            return false;
        }

        this.consumeHeldPlacementItem();
        return true;
    }

    public void finishHeldPlacement(ItemStack previousMainHand) {
        this.consumeHeldPlacementItem();
        this.stashPreviousMainHand(previousMainHand);
    }

    public void consumeHeldPlacementItem() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (mainHand.isEmpty()) {
            return;
        }

        mainHand.shrink(1);
        if (mainHand.isEmpty()) {
            this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    public void playPlaceEffects(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        this.playMainHandAction();
        PlayerNpcBlockSoundUtil.playPlaceSound(serverLevel, pos, state, this.playerNpc);
    }

    public void playMainHandAction() {
        this.playerNpc.triggerMainHandUseAnimation();
    }

    public boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !this.hasSelfPlacementCollision(serverLevel, pos, state)
                && this.findBlockingPlacementEntities(serverLevel, pos, state).isEmpty();
    }

    public boolean hasSelfPlacementCollision(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        AABB selfBox = this.playerNpc.getBoundingBox().inflate(0.05D);
        return this.placementCollisionBoxes(serverLevel, pos, state)
                .stream()
                .anyMatch(box -> box.intersects(selfBox));
    }

    public List<Entity> findBlockingPlacementEntities(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.placementCollisionBoxes(serverLevel, pos, state)
                .stream()
                .flatMap(box -> PlayerNpcCollisionUtil.blockingEntitiesInBox(serverLevel, this.playerNpc, box.inflate(0.05D)).stream())
                .distinct()
                .toList();
    }

    public List<AABB> placementCollisionBoxes(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
    }

    private static int rollDelay(RandomSource random, DelayProfile profile) {
        int min = Math.max(0, profile.minTicks());
        int max = Math.max(min, profile.maxTicks());
        int delay = min + random.nextInt(max - min + 1);
        if (random.nextFloat() < Math.max(0.0F, profile.slowChance())) {
            delay += random.nextInt(Math.max(0, profile.maxSlowExtraTicks()) + 1);
        }
        return delay;
    }

    private void stashPreviousMainHand(ItemStack stack) {
        ItemStack previous = stack == null ? ItemStack.EMPTY : stack.copy();
        if (previous.isEmpty() || this.playerNpc.promoteMainWeaponItem(previous)) {
            return;
        }

        if (!InventoryUtils.addItem(this.playerNpc, previous)) {
            this.playerNpc.spawnAtLocation(previous);
        }
    }

    private static BlockState stateForPlacement(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!isChestState(state) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return state;
        }

        Direction connectedDirection = ChestBlock.getConnectedDirection(state);
        BlockState neighbor = serverLevel.getBlockState(pos.relative(connectedDirection));
        return isCompatibleChestNeighbor(state, neighbor) ? state : state.setValue(ChestBlock.TYPE, ChestType.SINGLE);
    }

    private static void updateAdjacentChestHalf(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!isChestState(state) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return;
        }

        Direction connectedDirection = ChestBlock.getConnectedDirection(state);
        BlockPos neighborPos = pos.relative(connectedDirection);
        BlockState neighbor = serverLevel.getBlockState(neighborPos);
        if (!isChestState(neighbor)
                || neighbor.getBlock() != state.getBlock()
                || neighbor.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                || neighbor.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return;
        }

        BlockState joinedNeighbor = neighbor.setValue(ChestBlock.TYPE, oppositeChestType(state.getValue(ChestBlock.TYPE)));
        serverLevel.setBlockAndUpdate(neighborPos, joinedNeighbor);
    }

    private static boolean isCompatibleChestNeighbor(BlockState state, BlockState neighbor) {
        return isChestState(neighbor)
                && neighbor.getBlock() == state.getBlock()
                && neighbor.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)
                && (neighbor.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                || neighbor.getValue(ChestBlock.TYPE) == oppositeChestType(state.getValue(ChestBlock.TYPE)));
    }

    private static boolean isChestState(BlockState state) {
        return state != null
                && state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.hasProperty(ChestBlock.FACING);
    }

    private static ChestType oppositeChestType(ChestType type) {
        if (type == ChestType.LEFT) {
            return ChestType.RIGHT;
        }
        if (type == ChestType.RIGHT) {
            return ChestType.LEFT;
        }
        return ChestType.SINGLE;
    }

    public record DelayProfile(int minTicks, int maxTicks, float slowChance, int maxSlowExtraTicks) {
    }
}
