package com.pla.smart_npc.util;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class PlayerNpcGearUtil {
    private PlayerNpcGearUtil() {
    }

    public static ToolTier bestToolTier(ItemStack mainHand, ItemStack offHand, SimpleContainer inventory, ToolKind kind) {
        ToolTier best = max(tierFor(mainHand, kind), tierFor(offHand, kind));
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            best = max(best, tierFor(inventory.getItem(i), kind));
        }
        return best;
    }

    public static boolean hasToolAtLeast(ItemStack mainHand, ItemStack offHand, SimpleContainer inventory, ToolKind kind, ToolTier tier) {
        return bestToolTier(mainHand, offHand, inventory, kind).isAtLeast(tier);
    }

    public static ToolTier tierFor(ItemStack stack, ToolKind kind) {
        if (stack.isEmpty()) {
            return ToolTier.NONE;
        }

        Item item = stack.getItem();
        if (item == kind.woodItem) {
            return ToolTier.WOOD;
        }
        if (item == kind.stoneItem) {
            return ToolTier.STONE;
        }
        if (item == kind.ironItem) {
            return ToolTier.IRON;
        }
        if (item == kind.diamondItem) {
            return ToolTier.DIAMOND;
        }
        if (item == kind.netheriteItem) {
            return ToolTier.NETHERITE;
        }
        return ToolTier.NONE;
    }

    public static Item itemFor(ToolKind kind, ToolTier tier) {
        return switch (tier) {
            case WOOD -> kind.woodItem;
            case STONE -> kind.stoneItem;
            case IRON -> kind.ironItem;
            case DIAMOND -> kind.diamondItem;
            case NETHERITE -> kind.netheriteItem;
            case NONE -> Items.AIR;
        };
    }

    private static ToolTier max(ToolTier first, ToolTier second) {
        return first.level >= second.level ? first : second;
    }

    public enum ToolKind {
        PICKAXE(3, 2, Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE),
        AXE(3, 2, Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE),
        SWORD(2, 1, Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD),
        SHOVEL(1, 2, Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL),
        HOE(2, 2, Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE);

        private final int materialCost;
        private final int stickCost;
        private final Item woodItem;
        private final Item stoneItem;
        private final Item ironItem;
        private final Item diamondItem;
        private final Item netheriteItem;

        ToolKind(int materialCost, int stickCost, Item woodItem, Item stoneItem, Item ironItem, Item diamondItem, Item netheriteItem) {
            this.materialCost = materialCost;
            this.stickCost = stickCost;
            this.woodItem = woodItem;
            this.stoneItem = stoneItem;
            this.ironItem = ironItem;
            this.diamondItem = diamondItem;
            this.netheriteItem = netheriteItem;
        }

        public int materialCost() {
            return this.materialCost;
        }

        public int stickCost() {
            return this.stickCost;
        }
    }

    public enum ToolTier {
        NONE(0),
        WOOD(1),
        STONE(2),
        IRON(3),
        DIAMOND(4),
        NETHERITE(5);

        private final int level;

        ToolTier(int level) {
            this.level = level;
        }

        public boolean isBelow(ToolTier other) {
            return this.level < other.level;
        }

        public boolean isAtLeast(ToolTier other) {
            return this.level >= other.level;
        }
    }
}
