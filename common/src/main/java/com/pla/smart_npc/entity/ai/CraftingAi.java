package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;

public final class CraftingAi {
    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;

    public CraftingAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
    }

    public boolean canCraft(ServerLevel serverLevel, ItemLike result, boolean craftingTable, int rawLogReserve) {
        return PlayerNpcCraftingUtil.canCraftWithLogConversion(
                serverLevel,
                this.playerNpc.getInventory(),
                result,
                craftingTable,
                rawLogReserve
        );
    }

    public boolean tryCraft(ServerLevel serverLevel, ItemLike result, boolean craftingTable, int rawLogReserve, String detail) {
        if (!PlayerNpcCraftingUtil.tryCraftWithLogConversion(
                serverLevel,
                this.playerNpc.getInventory(),
                result,
                craftingTable,
                rawLogReserve
        )) {
            return false;
        }
        this.playCraftStep(serverLevel, detail, this.playerNpc.blockPosition());
        return true;
    }

    public boolean tryCraftFlintAndSteel(ServerLevel serverLevel) {
        if (!PlayerNpcCraftingUtil.tryCraftFlintAndSteel(this.playerNpc.getInventory())) {
            return false;
        }
        this.playCraftStep(serverLevel, "crafted flint and steel", this.playerNpc.blockPosition());
        return true;
    }

    public boolean tryCraftArrows(ServerLevel serverLevel, int rawLogReserve) {
        if (!PlayerNpcCraftingUtil.tryCraftArrows(this.playerNpc.getInventory(), rawLogReserve)) {
            return false;
        }
        this.playCraftStep(serverLevel, "crafted arrows", this.playerNpc.blockPosition());
        return true;
    }

    public boolean tryCraftBoat(ServerLevel serverLevel, int rawLogReserve) {
        if (!PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 5, rawLogReserve)) {
            return false;
        }
        InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.OAK_BOAT));
        this.playCraftStep(serverLevel, "crafted boat", this.playerNpc.blockPosition());
        return true;
    }

    public boolean tryPlaceCraftingTable(ServerLevel serverLevel, BlockPos tablePos, int rawLogReserve) {
        if (tablePos == null) {
            return false;
        }

        ItemStack table = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        boolean consumedTableItem = !table.isEmpty();
        if (table.isEmpty()
                && !PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4, rawLogReserve)) {
            return false;
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, tablePos, Blocks.CRAFTING_TABLE.defaultBlockState())) {
            InventoryUtils.addItem(
                    this.playerNpc,
                    consumedTableItem ? table : new ItemStack(Items.CRAFTING_TABLE)
            );
            return false;
        }
        this.playerNpc.getLookControl().setLookAt(
                tablePos.getX() + 0.5D,
                tablePos.getY() + 0.5D,
                tablePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        this.playerNpc.setCurrentAiDetail("placed crafting table");
        return true;
    }

    private void playCraftStep(ServerLevel serverLevel, String detail, BlockPos soundPos) {
        this.playerNpc.setCurrentAiDetail(detail == null || detail.isBlank() ? "crafted item" : detail);
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, soundPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
    }
}
