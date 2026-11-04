package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.event.PlayerNpcChestProtectEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class PlayerNpcBlockBreakUtil {
    private PlayerNpcBlockBreakUtil() {
    }

    public static boolean destroyBlock(ServerLevel serverLevel, BlockPos pos, BlockState state, PlayerNpcEntity playerNpc) {
        return destroyBlock(serverLevel, pos, state, playerNpc, false);
    }

    public static boolean destroyBlock(
            ServerLevel serverLevel,
            BlockPos pos,
            BlockState state,
            PlayerNpcEntity playerNpc,
            boolean allowOwnedFarmDestruction
    ) {
        if (!allowOwnedFarmDestruction && FarmAi.isOwnedFarmDestructionProtected(playerNpc, pos)) {
            playerNpc.setIdleTraceDetail("block break protected by owned farm @ "
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ(), 40);
            return false;
        }
        ItemStack heldStack = playerNpc.getMainHandItem();
        boolean ownedChestOffense = state.is(net.minecraft.world.level.block.Blocks.CHEST);
        if (!shouldDropResources(state, heldStack)) {
            boolean destroyed = serverLevel.destroyBlock(pos, false, playerNpc);
            if (destroyed && ownedChestOffense) {
                PlayerNpcChestProtectEvent.reportOffense(serverLevel, pos, playerNpc, "broke");
            }
            return destroyed;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, playerNpc, heldStack);
        if (!serverLevel.destroyBlock(pos, false, playerNpc)) {
            return false;
        }
        if (ownedChestOffense) {
            PlayerNpcChestProtectEvent.reportOffense(serverLevel, pos, playerNpc, "broke");
        }

        awardExperienceDrop(serverLevel, pos, state, blockEntity, playerNpc, heldStack);

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            Block.popResource(serverLevel, pos, drop);
        }
        return true;
    }

    public static boolean shouldDropResources(BlockState state, ItemStack heldStack) {
        return !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
    }

    private static void awardExperienceDrop(
            ServerLevel serverLevel,
            BlockPos pos,
            BlockState state,
            BlockEntity blockEntity,
            PlayerNpcEntity playerNpc,
            ItemStack heldStack
    ) {
        int xp = com.pla.smart_npc.platform.NpcPlatform.experienceDrop(state, serverLevel, pos, blockEntity, playerNpc, heldStack);
        xp = EnchantmentHelper.processBlockExperience(serverLevel, heldStack, xp);
        if (xp <= 0) {
            return;
        }

        playerNpc.awardStoredExperience(xp);
        playerNpc.playExperiencePickupSound();
    }
}
