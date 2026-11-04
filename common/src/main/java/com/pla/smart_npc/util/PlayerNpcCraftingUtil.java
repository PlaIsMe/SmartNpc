package com.pla.smart_npc.util;

import net.minecraft.resources.Identifier;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.ItemLike;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class PlayerNpcCraftingUtil {
    private PlayerNpcCraftingUtil() {
    }

    public static int countPlankEquivalent(SimpleContainer inventory) {
        return countPlankEquivalent(inventory, 0);
    }

    public static int countPlankEquivalent(SimpleContainer inventory, int rawLogReserve) {
        return countItem(inventory, PlayerNpcCraftingUtil::isPlanks)
                + Math.max(0, countItem(inventory, PlayerNpcCraftingUtil::isLogs) - Math.max(0, rawLogReserve)) * 4;
    }

    public static int countSticks(SimpleContainer inventory) {
        return countItem(inventory, stack -> stack.is(Items.STICK));
    }

    public static int countPlanks(SimpleContainer inventory) {
        return countItem(inventory, PlayerNpcCraftingUtil::isPlanks);
    }

    public static int countLogs(SimpleContainer inventory) {
        return countItem(inventory, PlayerNpcCraftingUtil::isLogs);
    }

    public static boolean canProvidePlanksAndSticks(SimpleContainer inventory, int planksNeeded, int sticksNeeded) {
        return canProvidePlanksAndSticks(inventory, planksNeeded, sticksNeeded, 0);
    }

    public static boolean canProvidePlanksAndSticks(SimpleContainer inventory, int planksNeeded, int sticksNeeded, int rawLogReserve) {
        int availablePlanks = countPlankEquivalent(inventory, rawLogReserve);
        int availableSticks = countSticks(inventory);
        int missingSticks = Math.max(0, sticksNeeded - availableSticks);
        int planksForSticks = ((missingSticks + 3) / 4) * 2;
        return availablePlanks >= planksNeeded + planksForSticks;
    }

    public static boolean canCraftCraftingTable(SimpleContainer inventory) {
        return canCraftCraftingTable(inventory, 0);
    }

    public static boolean canCraftCraftingTable(SimpleContainer inventory, int rawLogReserve) {
        return countPlankEquivalent(inventory, rawLogReserve) >= 4;
    }

    public static boolean canCraftChest(SimpleContainer inventory) {
        return canCraftChest(inventory, 0);
    }

    public static boolean canCraftChest(SimpleContainer inventory, int rawLogReserve) {
        return countPlankEquivalent(inventory, rawLogReserve) >= 8;
    }

    public static boolean canCraftFurnace(SimpleContainer inventory) {
        return countFurnaceStone(inventory) >= 8;
    }

    public static boolean canCraftBed(SimpleContainer inventory) {
        return canCraftBed(inventory, 0);
    }

    public static boolean canCraftBed(SimpleContainer inventory, int rawLogReserve) {
        return getCraftableBedItem(inventory) != null
                && countPlankEquivalent(inventory, rawLogReserve) >= 3;
    }

    public static boolean canCraft(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable) {
        return findCraftingPlan(serverLevel, inventory, result, craftingTable).isPresent();
    }

    public static boolean tryCraft(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable) {
        Optional<ItemStack> crafted = craftItem(serverLevel, inventory, result, craftingTable);
        return crafted.isPresent() && InventoryUtils.addItem(inventory, crafted.get());
    }

    public static boolean canCraftWithLogConversion(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable) {
        return canCraftWithLogConversion(serverLevel, inventory, result, craftingTable, 0);
    }

    public static boolean canCraftWithLogConversion(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable, int rawLogReserve) {
        SimpleContainer copy = copyContainer(inventory);
        return tryCraftWithLogConversion(serverLevel, copy, result, craftingTable, rawLogReserve);
    }

    public static Optional<ItemStack> craftItem(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable) {
        Optional<CraftingPlan> plan = findCraftingPlan(serverLevel, inventory, result, craftingTable);
        return craftItem(serverLevel, inventory, plan);
    }

    public static boolean canCraftMatching(
            ServerLevel serverLevel,
            SimpleContainer inventory,
            Predicate<ItemStack> resultMatcher,
            boolean craftingTable
    ) {
        return findCraftingPlan(serverLevel, inventory, resultMatcher, craftingTable).isPresent();
    }

    public static Optional<ItemStack> craftItemMatching(
            ServerLevel serverLevel,
            SimpleContainer inventory,
            Predicate<ItemStack> resultMatcher,
            boolean craftingTable
    ) {
        Optional<CraftingPlan> plan = findCraftingPlan(serverLevel, inventory, resultMatcher, craftingTable);
        return craftItem(serverLevel, inventory, plan);
    }

    private static Optional<ItemStack> craftItem(
            ServerLevel serverLevel,
            SimpleContainer inventory,
            Optional<CraftingPlan> plan
    ) {
        if (plan.isEmpty()) {
            return Optional.empty();
        }

        CraftingPlan craftingPlan = plan.get();
        ItemStack crafted = craftingPlan.recipe().assemble(craftingPlan.grid());
        if (crafted.isEmpty()) {
            return Optional.empty();
        }

        // Prove the complete mutation on a copy before consuming any real ingredient. A full
        // inventory can still accept the result when this recipe empties an input slot, while a
        // recipe whose inputs remain stacked fails without deleting either inputs or output.
        SimpleContainer committed = copyContainer(inventory);
        for (int inventorySlot : craftingPlan.inventorySlots()) {
            if (inventorySlot < 0) {
                continue;
            }
            ItemStack stack = committed.getItem(inventorySlot);
            if (!stack.isEmpty()) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    committed.setItem(inventorySlot, ItemStack.EMPTY);
                }
            }
        }

        NonNullList<ItemStack> remainingItems = craftingPlan.recipe().getRemainingItems(craftingPlan.grid());
        for (ItemStack remaining : remainingItems) {
            if (!remaining.isEmpty() && !InventoryUtils.addItem(committed, remaining.copy())) {
                return Optional.empty();
            }
        }
        SimpleContainer capacityCheck = copyContainer(committed);
        if (!InventoryUtils.addItem(capacityCheck, crafted.copy())) {
            return Optional.empty();
        }
        copyContainerContents(committed, inventory);
        return Optional.of(crafted);
    }

    public static boolean tryConsumePlanksAndSticks(SimpleContainer inventory, int planksNeeded, int sticksNeeded) {
        return tryConsumePlanksAndSticks(inventory, planksNeeded, sticksNeeded, 0);
    }

    public static boolean tryConsumePlanksAndSticks(SimpleContainer inventory, int planksNeeded, int sticksNeeded, int rawLogReserve) {
        if (!canProvidePlanksAndSticks(inventory, planksNeeded, sticksNeeded, rawLogReserve)) {
            return false;
        }

        while (countSticks(inventory) < sticksNeeded) {
            if (!tryConsumePlanks(inventory, 2, rawLogReserve)) {
                return false;
            }
            InventoryUtils.addItem(inventory, new ItemStack(Items.STICK, 4));
        }

        return consumeItem(inventory, stack -> stack.is(Items.STICK), sticksNeeded)
                && tryConsumePlanks(inventory, planksNeeded, rawLogReserve);
    }

    private static Optional<CraftingPlan> findCraftingPlan(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable) {
        Item targetItem = result.asItem();
        return findCraftingPlan(serverLevel, inventory, stack -> stack.is(targetItem), craftingTable);
    }

    private static Optional<CraftingPlan> findCraftingPlan(
            ServerLevel serverLevel,
            SimpleContainer inventory,
            Predicate<ItemStack> resultMatcher,
            boolean craftingTable
    ) {
        int width = craftingTable ? 3 : 2;
        int height = craftingTable ? 3 : 2;
        for (var recipeHolder : serverLevel.getServer().getRecipeManager().getRecipes()) {
            if (!(recipeHolder.value() instanceof CraftingRecipe recipe)
                    || recipe.placementInfo().isImpossibleToPlace()
                    || recipe.placementInfo().slotsToIngredientIndex().size() > width * height
                    || recipe instanceof ShapedRecipe shaped
                    && (shaped.getWidth() > width || shaped.getHeight() > height)) {
                continue;
            }

            Optional<CraftingPlan> plan = recipe instanceof ShapedRecipe shapedRecipe
                    ? createShapedPlan(serverLevel, inventory, shapedRecipe, width, height)
                    : createIngredientPlan(serverLevel, inventory, recipe, width, height);
            if (plan.isPresent() && resultMatcher.test(recipe.assemble(plan.get().grid()))) {
                return plan;
            }
        }
        return Optional.empty();
    }

    private static Optional<CraftingPlan> createShapedPlan(ServerLevel serverLevel, SimpleContainer inventory, ShapedRecipe recipe, int gridWidth, int gridHeight) {
        if (recipe.getWidth() > gridWidth || recipe.getHeight() > gridHeight) {
            return Optional.empty();
        }

        NonNullList<ItemStack> gridItems = NonNullList.withSize(gridWidth * gridHeight, ItemStack.EMPTY);
        int[] inventorySlots = new int[gridWidth * gridHeight];
        Arrays.fill(inventorySlots, -1);
        int[] remainingCounts = copyInventoryCounts(inventory);
        List<Optional<Ingredient>> ingredients = recipe.getIngredients();

        for (int y = 0; y < recipe.getHeight(); y++) {
            for (int x = 0; x < recipe.getWidth(); x++) {
                Optional<Ingredient> optionalIngredient = ingredients.get(x + y * recipe.getWidth());
                if (optionalIngredient.isEmpty()) {
                    continue;
                }
                Ingredient ingredient = optionalIngredient.get();

                int inventorySlot = findIngredientSlot(inventory, remainingCounts, ingredient);
                if (inventorySlot < 0) {
                    return Optional.empty();
                }

                int gridSlot = x + y * gridWidth;
                remainingCounts[inventorySlot]--;
                inventorySlots[gridSlot] = inventorySlot;
                gridItems.set(gridSlot, oneCraftingItem(inventory.getItem(inventorySlot)));
            }
        }

        return validatePlan(serverLevel, recipe, gridWidth, gridHeight, gridItems, inventorySlots);
    }

    private static Optional<CraftingPlan> createIngredientPlan(ServerLevel serverLevel, SimpleContainer inventory, CraftingRecipe recipe, int gridWidth, int gridHeight) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.size() > gridWidth * gridHeight) {
            return Optional.empty();
        }

        NonNullList<ItemStack> gridItems = NonNullList.withSize(gridWidth * gridHeight, ItemStack.EMPTY);
        int[] inventorySlots = new int[gridWidth * gridHeight];
        Arrays.fill(inventorySlots, -1);
        int[] remainingCounts = copyInventoryCounts(inventory);
        int gridSlot = 0;

        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }

            int inventorySlot = findIngredientSlot(inventory, remainingCounts, ingredient);
            if (inventorySlot < 0 || gridSlot >= gridItems.size()) {
                return Optional.empty();
            }

            remainingCounts[inventorySlot]--;
            inventorySlots[gridSlot] = inventorySlot;
            gridItems.set(gridSlot, oneCraftingItem(inventory.getItem(inventorySlot)));
            gridSlot++;
        }

        return validatePlan(serverLevel, recipe, gridWidth, gridHeight, gridItems, inventorySlots);
    }

    private static Optional<CraftingPlan> validatePlan(ServerLevel serverLevel, CraftingRecipe recipe, int gridWidth, int gridHeight, NonNullList<ItemStack> gridItems, int[] inventorySlots) {
        CraftingInput grid = CraftingInput.of(gridWidth, gridHeight, gridItems);
        if (!recipe.matches(grid, serverLevel)) {
            return Optional.empty();
        }
        return Optional.of(new CraftingPlan(recipe, grid, inventorySlots));
    }

    private static int[] copyInventoryCounts(SimpleContainer inventory) {
        int[] counts = new int[inventory.getContainerSize()];
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            counts[i] = inventory.getItem(i).getCount();
        }
        return counts;
    }

    private static int findIngredientSlot(SimpleContainer inventory, int[] remainingCounts, Ingredient ingredient) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && remainingCounts[i] > 0 && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack oneCraftingItem(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    public static boolean tryConsumePlanks(SimpleContainer inventory, int count) {
        return tryConsumePlanks(inventory, count, 0);
    }

    public static boolean tryConsumePlanks(SimpleContainer inventory, int count, int rawLogReserve) {
        if (count <= 0) {
            return true;
        }

        while (countItem(inventory, PlayerNpcCraftingUtil::isPlanks) < count) {
            if (!convertOneLogToPlanks(inventory, rawLogReserve)) {
                return false;
            }
        }

        return consumeItem(inventory, PlayerNpcCraftingUtil::isPlanks, count);
    }

    public static boolean tryConvertOneLogToPlanks(SimpleContainer inventory) {
        return tryConvertOneLogToPlanks(inventory, 0);
    }

    public static boolean tryConvertOneLogToPlanks(SimpleContainer inventory, int rawLogReserve) {
        return convertOneLogToPlanks(inventory, rawLogReserve);
    }

    public static boolean tryCraftSticks(SimpleContainer inventory) {
        return tryCraftSticks(inventory, 0);
    }

    public static boolean tryCraftSticks(SimpleContainer inventory, int rawLogReserve) {
        if (!tryConsumePlanks(inventory, 2, rawLogReserve)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.STICK, 4));
    }

    public static boolean tryCraftSticks(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraftSticks(serverLevel, inventory, 0);
    }

    public static boolean tryCraftSticks(ServerLevel serverLevel, SimpleContainer inventory, int rawLogReserve) {
        return tryCraftWithLogConversion(serverLevel, inventory, Items.STICK, false, rawLogReserve);
    }

    public static boolean tryCraftChest(SimpleContainer inventory) {
        return tryCraftChest(inventory, 0);
    }

    public static boolean tryCraftChest(SimpleContainer inventory, int rawLogReserve) {
        if (!tryConsumePlanks(inventory, 8, rawLogReserve)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.CHEST));
    }

    public static boolean tryCraftChest(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraftChest(serverLevel, inventory, 0);
    }

    public static boolean tryCraftChest(ServerLevel serverLevel, SimpleContainer inventory, int rawLogReserve) {
        return tryCraftWithLogConversion(serverLevel, inventory, Items.CHEST, true, rawLogReserve);
    }

    public static boolean tryCraftFurnace(SimpleContainer inventory) {
        if (!consumeFurnaceStone(inventory, 8)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.FURNACE));
    }

    public static boolean tryCraftFurnace(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraft(serverLevel, inventory, Items.FURNACE, true);
    }

    public static boolean canCraftTorches(SimpleContainer inventory) {
        return canCraftTorches(inventory, 0);
    }

    public static boolean canCraftTorches(SimpleContainer inventory, int rawLogReserve) {
        return countItem(inventory, PlayerNpcCraftingUtil::isTorchFuel) > 0
                && canProvidePlanksAndSticks(inventory, 0, 1, rawLogReserve);
    }

    public static boolean tryCraftTorches(SimpleContainer inventory) {
        return tryCraftTorches(inventory, 0);
    }

    public static boolean tryCraftTorches(SimpleContainer inventory, int rawLogReserve) {
        if (!canCraftTorches(inventory, rawLogReserve)
                || !tryConsumePlanksAndSticks(inventory, 0, 1, rawLogReserve)
                || !consumeItem(inventory, PlayerNpcCraftingUtil::isTorchFuel, 1)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.TORCH, 4));
    }

    public static boolean tryCraftTorches(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraft(serverLevel, inventory, Items.TORCH, false);
    }

    public static boolean canCraftFlintAndSteel(SimpleContainer inventory) {
        SimpleContainer copy = copyContainer(inventory);
        return countItem(copy, stack -> stack.is(Items.FLINT)) > 0
                && countItem(copy, stack -> stack.is(Items.IRON_INGOT)) > 0
                && consumeItem(copy, stack -> stack.is(Items.FLINT), 1)
                && consumeItem(copy, stack -> stack.is(Items.IRON_INGOT), 1)
                && InventoryUtils.addItem(copy, new ItemStack(Items.FLINT_AND_STEEL));
    }

    public static boolean tryCraftFlintAndSteel(SimpleContainer inventory) {
        if (!canCraftFlintAndSteel(inventory)
                || !consumeItem(inventory, stack -> stack.is(Items.FLINT), 1)
                || !consumeItem(inventory, stack -> stack.is(Items.IRON_INGOT), 1)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.FLINT_AND_STEEL));
    }

    public static boolean canCraftArrows(SimpleContainer inventory, int rawLogReserve) {
        SimpleContainer copy = copyContainer(inventory);
        return countItem(copy, stack -> stack.is(Items.FLINT)) > 0
                && countItem(copy, stack -> stack.is(Items.FEATHER)) > 0
                && canProvidePlanksAndSticks(copy, 0, 1, rawLogReserve)
                && tryConsumePlanksAndSticks(copy, 0, 1, rawLogReserve)
                && consumeItem(copy, stack -> stack.is(Items.FLINT), 1)
                && consumeItem(copy, stack -> stack.is(Items.FEATHER), 1)
                && InventoryUtils.addItem(copy, new ItemStack(Items.ARROW, 4));
    }

    public static boolean tryCraftArrows(SimpleContainer inventory, int rawLogReserve) {
        if (!canCraftArrows(inventory, rawLogReserve)
                || !tryConsumePlanksAndSticks(inventory, 0, 1, rawLogReserve)
                || !consumeItem(inventory, stack -> stack.is(Items.FLINT), 1)
                || !consumeItem(inventory, stack -> stack.is(Items.FEATHER), 1)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.ARROW, 4));
    }

    public static boolean canCraftFences(SimpleContainer inventory) {
        return canCraftFences(inventory, 0);
    }

    public static boolean canCraftFences(SimpleContainer inventory, int rawLogReserve) {
        return canProvidePlanksAndSticks(inventory, 4, 2, rawLogReserve);
    }

    public static boolean tryCraftFences(SimpleContainer inventory) {
        return tryCraftFences(inventory, 0);
    }

    public static boolean tryCraftFences(SimpleContainer inventory, int rawLogReserve) {
        if (!tryConsumePlanksAndSticks(inventory, 4, 2, rawLogReserve)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.OAK_FENCE, 3));
    }

    public static boolean tryCraftFences(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraft(serverLevel, inventory, Items.OAK_FENCE, true);
    }

    public static boolean canCraftFenceGate(SimpleContainer inventory) {
        return canCraftFenceGate(inventory, 0);
    }

    public static boolean canCraftFenceGate(SimpleContainer inventory, int rawLogReserve) {
        return canProvidePlanksAndSticks(inventory, 2, 4, rawLogReserve);
    }

    public static boolean tryCraftFenceGate(SimpleContainer inventory) {
        return tryCraftFenceGate(inventory, 0);
    }

    public static boolean tryCraftFenceGate(SimpleContainer inventory, int rawLogReserve) {
        if (!tryConsumePlanksAndSticks(inventory, 2, 4, rawLogReserve)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.OAK_FENCE_GATE));
    }

    public static boolean tryCraftFenceGate(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraft(serverLevel, inventory, Items.OAK_FENCE_GATE, true);
    }

    public static boolean canCraftBoneMeal(SimpleContainer inventory) {
        return countItem(inventory, stack -> stack.is(Items.BONE)) > 0;
    }

    public static boolean tryCraftBoneMeal(SimpleContainer inventory) {
        if (!consumeItem(inventory, stack -> stack.is(Items.BONE), 1)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.BONE_MEAL, 3));
    }

    public static boolean tryCraftBoneMeal(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraft(serverLevel, inventory, Items.BONE_MEAL, false);
    }

    public static boolean canCraftBread(SimpleContainer inventory) {
        return countItem(inventory, stack -> stack.is(Items.WHEAT)) >= 3;
    }

    public static boolean tryCraftBread(SimpleContainer inventory) {
        if (!consumeItem(inventory, stack -> stack.is(Items.WHEAT), 3)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.BREAD));
    }

    public static boolean tryCraftBread(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraft(serverLevel, inventory, Items.BREAD, true);
    }

    public static boolean tryCraftBed(SimpleContainer inventory) {
        return tryCraftBed(inventory, 0);
    }

    public static boolean tryCraftBed(SimpleContainer inventory, int rawLogReserve) {
        Item wool = getCraftableWool(inventory);
        Item bed = wool == null ? null : getBedForWool(wool);
        if (bed == null || !tryConsumePlanks(inventory, 3, rawLogReserve)) {
            return false;
        }
        if (!consumeItem(inventory, stack -> stack.is(wool), 3)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(bed));
    }

    public static boolean tryCraftBed(ServerLevel serverLevel, SimpleContainer inventory) {
        return tryCraftBed(serverLevel, inventory, 0);
    }

    public static boolean tryCraftBed(ServerLevel serverLevel, SimpleContainer inventory, int rawLogReserve) {
        return tryCraftBed(inventory, rawLogReserve);
    }

    public static boolean tryCraftWithLogConversion(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable) {
        return tryCraftWithLogConversion(serverLevel, inventory, result, craftingTable, 0);
    }

    public static boolean tryCraftWithLogConversion(ServerLevel serverLevel, SimpleContainer inventory, ItemLike result, boolean craftingTable, int rawLogReserve) {
        while (!canCraft(serverLevel, inventory, result, craftingTable) && countLogs(inventory) > Math.max(0, rawLogReserve)) {
            if (!convertOneLogToPlanks(inventory, rawLogReserve)) {
                break;
            }
        }
        return tryCraft(serverLevel, inventory, result, craftingTable);
    }

    private static SimpleContainer copyContainer(SimpleContainer inventory) {
        SimpleContainer copy = new SimpleContainer(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            copy.setItem(i, inventory.getItem(i).copy());
        }
        return copy;
    }

    private static void copyContainerContents(SimpleContainer from, SimpleContainer to) {
        int size = Math.min(from.getContainerSize(), to.getContainerSize());
        for (int i = 0; i < size; i++) {
            to.setItem(i, from.getItem(i).copy());
        }
        to.setChanged();
    }

    public static int countItem(SimpleContainer inventory, Predicate<ItemStack> matcher) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int countFurnaceStone(SimpleContainer inventory) {
        return countItem(inventory, stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    public static boolean consumeFurnaceStone(SimpleContainer inventory, int count) {
        return consumeItem(inventory, stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE), count);
    }

    public static boolean consumeItem(SimpleContainer inventory, Predicate<ItemStack> matcher, int count) {
        if (count <= 0) {
            return true;
        }
        if (countItem(inventory, matcher) < count) {
            return false;
        }

        int remaining = count;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }

            int used = Math.min(remaining, stack.getCount());
            stack.shrink(used);
            remaining -= used;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
        return remaining == 0;
    }

    public static boolean isLogs(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.LOGS) && getPlanksForLog(stack) != null;
    }

    public static boolean isPlanks(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.PLANKS);
    }

    private static boolean isTorchFuel(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.COAL) || stack.is(Items.CHARCOAL));
    }

    private static boolean convertOneLogToPlanks(SimpleContainer inventory, int rawLogReserve) {
        if (countLogs(inventory) <= Math.max(0, rawLogReserve)) {
            return false;
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(ItemTags.LOGS)) {
                continue;
            }

            Item planks = getPlanksForLog(stack);
            if (planks == null) {
                continue;
            }

            SimpleContainer converted = copyContainer(inventory);
            ItemStack convertedLog = converted.getItem(i);
            convertedLog.shrink(1);
            if (convertedLog.isEmpty()) {
                converted.setItem(i, ItemStack.EMPTY);
            }
            if (!InventoryUtils.addItem(converted, new ItemStack(planks, 4))) {
                continue;
            }
            copyContainerContents(converted, inventory);
            return true;
        }
        return false;
    }

    private static Item getCraftableWool(SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(ItemTags.WOOL) || getBedForWool(stack.getItem()) == null) {
                continue;
            }
            Item wool = stack.getItem();
            if (countItem(inventory, candidate -> candidate.is(wool)) >= 3) {
                return wool;
            }
        }
        return null;
    }

    public static Item getCraftableBedItem(SimpleContainer inventory) {
        Item wool = getCraftableWool(inventory);
        return wool == null ? null : getBedForWool(wool);
    }

    private static Item getPlanksForLog(ItemStack stack) {
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return null;
        }

        String path = key.getPath();
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }

        String plankPath = null;
        if (path.endsWith("_log")) {
            plankPath = path.substring(0, path.length() - "_log".length()) + "_planks";
        } else if (path.endsWith("_wood")) {
            plankPath = path.substring(0, path.length() - "_wood".length()) + "_planks";
        } else if (path.endsWith("_stem")) {
            plankPath = path.substring(0, path.length() - "_stem".length()) + "_planks";
        } else if (path.endsWith("_hyphae")) {
            plankPath = path.substring(0, path.length() - "_hyphae".length()) + "_planks";
        } else if (path.equals("bamboo_block")) {
            plankPath = "bamboo_planks";
        }

        if (plankPath == null) {
            return null;
        }

        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(key.getNamespace(), plankPath));
    }

    private static Item getBedForWool(Item wool) {
        Identifier key = BuiltInRegistries.ITEM.getKey(wool);
        if (key == null || !key.getPath().endsWith("_wool")) {
            return null;
        }

        String bedPath = key.getPath().substring(0, key.getPath().length() - "_wool".length()) + "_bed";
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(key.getNamespace(), bedPath));
    }

    private record CraftingPlan(CraftingRecipe recipe, CraftingInput grid, int[] inventorySlots) {
    }
}
