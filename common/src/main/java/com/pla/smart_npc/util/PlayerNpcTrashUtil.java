package com.pla.smart_npc.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;

/** Conservative inventory cleanup; a stack marker also prevents merging with ordinary loot. */
public final class PlayerNpcTrashUtil {
    private static final String DISCARDED = "SmartNpcDiscardedTrash";
    private static final int MIN_ARROW_RESERVE = 64;
    private static final int MIN_ENDER_PEARL_RESERVE = 16;
    private static final int MIN_USABLE_BOW_DURABILITY = 16;

    public static final int NOT_TRASH = 0;
    public static final int SURPLUS_VALUABLE_PRIORITY = 50;
    public static final int SURPLUS_AMMUNITION_PRIORITY = 100;
    public static final int SURPLUS_UTILITY_PRIORITY = 200;
    public static final int OBSOLETE_TOOL_PRIORITY = 300;
    public static final int EXPLICIT_JUNK_PRIORITY = 400;

    private PlayerNpcTrashUtil() {}

    public static boolean isDiscarded(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(DISCARDED);
    }

    public static ItemStack discardedCopy(ItemStack stack) {
        ItemStack copy = stack.copy();
        CustomData.update(DataComponents.CUSTOM_DATA, copy, tag -> tag.putBoolean(DISCARDED, true));
        return copy;
    }

    public static int occupiedSlots(Container inventory) {
        int occupied = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) occupied++;
        }
        return occupied;
    }

    public static boolean isTrash(ItemStack stack, Container inventory, ItemStack mainHand, ItemStack offHand) {
        return trashPriority(stack, inventory, mainHand, offHand, createProfile(inventory, mainHand, offHand))
                > NOT_TRASH;
    }

    public static int findTrashSlot(Container inventory, ItemStack mainHand, ItemStack offHand) {
        TrashProfile profile = createProfile(inventory, mainHand, offHand);
        int selectedPriority = NOT_TRASH;
        int selectedSlot = -1;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            int priority = trashPriority(inventory.getItem(slot), inventory, mainHand, offHand, profile);
            if (priority > selectedPriority) {
                selectedPriority = priority;
                selectedSlot = slot;
            }
        }
        return selectedSlot;
    }

    /**
     * Returns a conservative disposal priority. Reserve-based candidates are recalculated after
     * every removal, so cleanup cannot throw the last useful copy or dip below its ammunition
     * reserve while trying to relieve slot pressure.
     */
    public static int trashPriority(ItemStack stack, Container inventory, ItemStack mainHand, ItemStack offHand) {
        return trashPriority(stack, inventory, mainHand, offHand, createProfile(inventory, mainHand, offHand));
    }

    private static int trashPriority(
            ItemStack stack,
            Container inventory,
            ItemStack mainHand,
            ItemStack offHand,
            TrashProfile profile
    ) {
        if (stack.isEmpty() || stack.has(DataComponents.CUSTOM_NAME) || stack.isEnchanted()) return NOT_TRASH;
        // Explicit harmful fishing loot is safe to discard even though vanilla marks it edible.
        if (stack.is(Items.ROTTEN_FLESH)) return EXPLICIT_JUNK_PRIORITY;
        if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return NOT_TRASH;
        // Saplings are planting stock and valid furnace fuel; never classify them as generic junk.
        if (isCommonFlower(stack) || isDecorativePlant(stack)
                || stack.is(Items.BOWL) || stack.is(Items.LILY_PAD) || stack.is(Items.TRIPWIRE_HOOK)) {
            return EXPLICIT_JUNK_PRIORITY;
        }
        if (toolKind(stack) != 0) {
            if (isUpgrade(stack, mainHand) || isUpgrade(stack, offHand)) return OBSOLETE_TOOL_PRIORITY;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (isUpgrade(stack, inventory.getItem(slot))) return OBSOLETE_TOOL_PRIORITY;
            }
        }

        // Overflow reserves are deliberately narrow. They free space in otherwise all-useful
        // inventories without guessing about food, build stock, ores, or modded items. Valuable
        // pearls use the final tier below and retain one full stack.
        if (stack.is(Items.WATER_BUCKET)
                && profile.waterBucketCount() > 1) {
            return SURPLUS_UTILITY_PRIORITY;
        }
        if (stack.is(Items.BOW)
                && profile.usableBowCount() - (isUsableBow(stack) ? 1 : 0) > 0) {
            // Prefer the most worn plain duplicate while always retaining another usable bow.
            return SURPLUS_UTILITY_PRIORITY + durabilityWearScore(stack);
        }
        if (stack.is(Items.ARROW)
                && profile.arrowCount() - stack.getCount() >= MIN_ARROW_RESERVE) {
            // Prefer a partial overflow stack, then re-evaluate the 64-arrow reserve.
            return SURPLUS_AMMUNITION_PRIORITY + Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        if (stack.is(Items.ENDER_PEARL)
                && profile.enderPearlCount() - stack.getCount() >= MIN_ENDER_PEARL_RESERVE) {
            // Pearls are valuable, so this is the last overflow tier and always retains a full stack.
            return SURPLUS_VALUABLE_PRIORITY + Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        return NOT_TRASH;
    }

    private static TrashProfile createProfile(Container inventory, ItemStack mainHand, ItemStack offHand) {
        int waterBuckets = countIf(mainHand, Items.WATER_BUCKET) + countIf(offHand, Items.WATER_BUCKET);
        int arrows = countIf(mainHand, Items.ARROW) + countIf(offHand, Items.ARROW);
        int enderPearls = countIf(mainHand, Items.ENDER_PEARL) + countIf(offHand, Items.ENDER_PEARL);
        int usableBows = (isUsableBow(mainHand) ? 1 : 0) + (isUsableBow(offHand) ? 1 : 0);
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack candidate = inventory.getItem(slot);
            waterBuckets += countIf(candidate, Items.WATER_BUCKET);
            arrows += countIf(candidate, Items.ARROW);
            enderPearls += countIf(candidate, Items.ENDER_PEARL);
            if (isUsableBow(candidate)) usableBows++;
        }
        return new TrashProfile(waterBuckets, arrows, enderPearls, usableBows);
    }

    private static int countIf(ItemStack stack, Item item) {
        return stack.is(item) ? stack.getCount() : 0;
    }

    private static boolean isUsableBow(ItemStack stack) {
        return stack.is(Items.BOW)
                && stack.getMaxDamage() - stack.getDamageValue() >= MIN_USABLE_BOW_DURABILITY;
    }

    private static int durabilityWearScore(ItemStack stack) {
        return stack.getMaxDamage() <= 0 ? 0 : 99 * stack.getDamageValue() / stack.getMaxDamage();
    }

    private record TrashProfile(int waterBucketCount, int arrowCount, int enderPearlCount, int usableBowCount) {}

    private static boolean isDecorativePlant(ItemStack stack) {
        return stack.is(Items.SHORT_GRASS) || stack.is(Items.TALL_GRASS)
                || stack.is(Items.FERN) || stack.is(Items.LARGE_FERN)
                || stack.is(Items.DEAD_BUSH) || stack.is(Items.VINE)
                || stack.is(Items.GLOW_LICHEN) || stack.is(Items.HANGING_ROOTS)
                || stack.is(Items.SEAGRASS);
    }

    private static boolean isCommonFlower(ItemStack stack) {
        return stack.is(Items.DANDELION) || stack.is(Items.POPPY)
                || stack.is(Items.BLUE_ORCHID) || stack.is(Items.ALLIUM)
                || stack.is(Items.AZURE_BLUET) || stack.is(Items.RED_TULIP)
                || stack.is(Items.ORANGE_TULIP) || stack.is(Items.WHITE_TULIP)
                || stack.is(Items.PINK_TULIP) || stack.is(Items.OXEYE_DAISY)
                || stack.is(Items.CORNFLOWER) || stack.is(Items.LILY_OF_THE_VALLEY)
                || stack.is(Items.SUNFLOWER) || stack.is(Items.LILAC)
                || stack.is(Items.ROSE_BUSH) || stack.is(Items.PEONY);
    }

    private static boolean isUpgrade(ItemStack oldStack, ItemStack replacement) {
        if (replacement.isEmpty() || isDiscarded(replacement) || toolKind(oldStack) != toolKind(replacement)
                || !(oldStack.getItem() instanceof TieredItem oldTool)
                || !(replacement.getItem() instanceof TieredItem newTool)) return false;
        // Never throw away a working tool for a nearly broken replacement, or guess modded tier ordering.
        if (!(oldTool.getTier() instanceof Tiers) || !(newTool.getTier() instanceof Tiers)) return false;
        if (replacement.getMaxDamage() - replacement.getDamageValue() < Math.max(16, replacement.getMaxDamage() / 10)) return false;
        return newTool.getTier().getAttackDamageBonus() > oldTool.getTier().getAttackDamageBonus()
                && newTool.getTier().getUses() > oldTool.getTier().getUses();
    }

    private static int toolKind(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof PickaxeItem) return 1;
        if (item instanceof AxeItem) return 2;
        if (item instanceof ShovelItem) return 3;
        if (item instanceof HoeItem) return 4;
        if (item instanceof SwordItem) return 5;
        return 0;
    }
}
