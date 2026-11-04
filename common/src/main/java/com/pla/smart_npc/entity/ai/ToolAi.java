package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.util.SmartNpcItemUtil;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ToolAi {
    private final PlayerNpcEntity playerNpc;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private MainHandSource currentMainHandSource = MainHandSource.NONE;
    private boolean swappedMainHand;

    public ToolAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public boolean equipBestToolFor(BlockState state) {
        Object toolClass = preferredToolFor(state);
        if (toolClass == null) {
            return true;
        }
        if (this.equipTool(toolClass)) {
            return true;
        }
        if (toolClass == ShovelItem.class) {
            this.equipEmptyMainHand();
        }
        return false;
    }

    public boolean hasPreferredToolFor(BlockState state) {
        Object toolClass = preferredToolFor(state);
        return toolClass == null || this.hasTool(toolClass);
    }

    public boolean hasTool(Object toolClass) {
        return this.playerNpc.hasCarriedTool(toolClass)
                || (this.swappedMainHand && SmartNpcItemUtil.matches(toolClass, this.previousMainHand.getItem()));
    }

    public static Object preferredToolFor(BlockState state) {
        if (state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return AxeItem.class;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)) {
            return ShovelItem.class;
        }
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return ItemTags.PICKAXES;
        }
        return null;
    }

    public boolean equipTool(Object toolClass) {
        if (SmartNpcItemUtil.matches(toolClass, this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (stack.isEmpty() || !SmartNpcItemUtil.matches(toolClass, stack.getItem())) {
                continue;
            }

            this.swapMainHandWithSlot(i, stack);
            return true;
        }

        ItemStack offhandTool = this.playerNpc.getOffhandItem();
        if (!offhandTool.isEmpty() && SmartNpcItemUtil.matches(toolClass, offhandTool.getItem())) {
            this.swapMainHandWithOffhand(offhandTool);
            return true;
        }

        ItemStack mainWeaponTool = this.playerNpc.takeMainWeaponItem(stack -> SmartNpcItemUtil.matches(toolClass, stack.getItem()));
        if (!mainWeaponTool.isEmpty()) {
            this.swapMainHandWithReserved(mainWeaponTool, MainHandSource.MAIN_WEAPON);
            return true;
        }

        ItemStack offWeaponTool = this.playerNpc.takeOffWeaponItem(stack -> SmartNpcItemUtil.matches(toolClass, stack.getItem()));
        if (!offWeaponTool.isEmpty()) {
            this.swapMainHandWithReserved(offWeaponTool, MainHandSource.OFF_WEAPON);
            return true;
        }
        return false;
    }

    public boolean equipItem(ItemLike itemLike) {
        if (this.playerNpc.getMainHandItem().is(itemLike.asItem())) {
            return true;
        }
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(itemLike.asItem())) {
                continue;
            }

            this.swapMainHandWithSlot(i, stack);
            return true;
        }
        return false;
    }

    public void restoreMainHand() {
        if (!this.swappedMainHand) {
            return;
        }
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        this.stashCurrentMainHand(current);
        this.playerNpc.setMainHandItemForAi(this.previousMainHand);
        this.playerNpc.promoteMainWeaponItem(this.previousMainHand);
        this.swappedMainHand = false;
        this.previousMainHand = ItemStack.EMPTY;
        this.currentMainHandSource = MainHandSource.NONE;
    }

    public void equipEmptyMainHand() {
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        if (current.isEmpty()) {
            return;
        }
        if (!this.swappedMainHand) {
            this.previousMainHand = current.copy();
            this.swappedMainHand = true;
        } else {
            this.stashCurrentMainHand(current);
        }
        this.playerNpc.setMainHandItemForAi(ItemStack.EMPTY);
        this.currentMainHandSource = MainHandSource.NONE;
    }

    private void swapMainHandWithSlot(int slot, ItemStack stack) {
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        if (!this.swappedMainHand) {
            this.previousMainHand = current.copy();
            this.swappedMainHand = true;
        } else {
            this.stashCurrentMainHand(current);
        }
        this.playerNpc.setMainHandItemForAi(stack.copy());
        this.playerNpc.getInventory().setItem(slot, ItemStack.EMPTY);
        this.currentMainHandSource = MainHandSource.INVENTORY;
    }

    private void swapMainHandWithOffhand(ItemStack stack) {
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        if (!this.swappedMainHand) {
            this.previousMainHand = current.copy();
            this.swappedMainHand = true;
        } else {
            this.stashCurrentMainHand(current);
        }
        this.playerNpc.setMainHandItemForAi(stack.copy());
        this.playerNpc.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        this.currentMainHandSource = MainHandSource.OFFHAND;
    }

    private void swapMainHandWithReserved(ItemStack stack, MainHandSource source) {
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        if (!this.swappedMainHand) {
            this.previousMainHand = current.copy();
            this.swappedMainHand = true;
        } else {
            this.stashCurrentMainHand(current);
        }
        this.playerNpc.setMainHandItemForAi(stack.copy());
        this.currentMainHandSource = source;
    }

    private void stashCurrentMainHand(ItemStack stack) {
        if (stack.isEmpty()) {
            this.currentMainHandSource = MainHandSource.NONE;
            return;
        }

        if (this.currentMainHandSource == MainHandSource.OFF_WEAPON) {
            this.playerNpc.setOffWeaponItem(stack);
        } else if (this.currentMainHandSource == MainHandSource.OFFHAND) {
            this.playerNpc.setItemSlot(EquipmentSlot.OFFHAND, stack);
        } else if (!InventoryUtils.addItem(this.playerNpc.getInventory(), stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
        this.currentMainHandSource = MainHandSource.NONE;
    }

    private enum MainHandSource {
        NONE,
        INVENTORY,
        OFFHAND,
        MAIN_WEAPON,
        OFF_WEAPON
    }
}
