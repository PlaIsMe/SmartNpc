package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.Optional;

/** Helpers for safely treating a two-block bed as one build obstruction. */
public final class PlayerNpcBedUtil {
    private PlayerNpcBedUtil() {
    }

    public static boolean isSafeBuildObstruction(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos pos,
            BlockState state
    ) {
        if (!isBedState(state)
                || isOccupied(state)
                || !serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || FarmAi.isOwnedFarmDestructionProtected(playerNpc, pos)) {
            return false;
        }

        boolean targetInsideFootprint = PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos);
        BlockPos companionPos = companionPos(pos, state);
        if (!serverLevel.isInWorldBounds(companionPos)
                || !serverLevel.getWorldBorder().isWithinBounds(companionPos)) {
            return false;
        }

        BlockState companionState = serverLevel.getBlockState(companionPos);
        if (!isMatchingCompanion(state, companionState)) {
            // An orphaned bed half is safe only when that half itself obstructs the build.
            return targetInsideFootprint;
        }

        return (targetInsideFootprint
                || PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, companionPos))
                && !isOccupied(companionState)
                && !FarmAi.isOwnedFarmDestructionProtected(playerNpc, companionPos);
    }

    public static Optional<BlockPos> findMatchingCompanion(
            ServerLevel serverLevel,
            BlockPos pos,
            BlockState state
    ) {
        if (!isBedState(state)) {
            return Optional.empty();
        }

        BlockPos companionPos = companionPos(pos, state);
        if (!serverLevel.isInWorldBounds(companionPos)
                || !serverLevel.getWorldBorder().isWithinBounds(companionPos)
                || !isMatchingCompanion(state, serverLevel.getBlockState(companionPos))) {
            return Optional.empty();
        }
        return Optional.of(companionPos.immutable());
    }

    public static void removeRemainingCompanion(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockState brokenBedState,
            BlockPos companionPos
    ) {
        if (companionPos == null) {
            return;
        }

        BlockState companionState = serverLevel.getBlockState(companionPos);
        if (isMatchingCompanion(brokenBedState, companionState)
                && !isOccupied(companionState)
                && !FarmAi.isOwnedFarmDestructionProtected(playerNpc, companionPos)) {
            // Vanilla normally removes the other half via neighbor updates. This no-drop fallback
            // repairs a surviving half while leaving the targeted half responsible for the loot.
            serverLevel.removeBlock(companionPos, false);
        }
    }

    private static boolean isBedState(BlockState state) {
        return state != null
                && state.getBlock() instanceof BedBlock
                && state.hasProperty(BedBlock.PART)
                && state.hasProperty(BedBlock.FACING);
    }

    private static boolean isOccupied(BlockState state) {
        return state.hasProperty(BedBlock.OCCUPIED) && state.getValue(BedBlock.OCCUPIED);
    }

    private static BlockPos companionPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(BedBlock.FACING);
        return state.getValue(BedBlock.PART) == BedPart.FOOT
                ? pos.relative(facing)
                : pos.relative(facing.getOpposite());
    }

    private static boolean isMatchingCompanion(BlockState bedState, BlockState companionState) {
        return companionState.getBlock() == bedState.getBlock()
                && companionState.hasProperty(BedBlock.PART)
                && companionState.hasProperty(BedBlock.FACING)
                && companionState.getValue(BedBlock.PART) != bedState.getValue(BedBlock.PART)
                && companionState.getValue(BedBlock.FACING) == bedState.getValue(BedBlock.FACING);
    }
}
