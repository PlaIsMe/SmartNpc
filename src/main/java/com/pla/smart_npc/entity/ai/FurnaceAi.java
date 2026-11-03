package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.Level;

import java.util.Optional;
import java.util.function.Predicate;

public final class FurnaceAi {
    public static final String TEMP_FURNACE_X = "PlayerNpcTemporaryFurnaceX";
    public static final String TEMP_FURNACE_Y = "PlayerNpcTemporaryFurnaceY";
    public static final String TEMP_FURNACE_Z = "PlayerNpcTemporaryFurnaceZ";
    // The position keys predate separate cooking/camp lifecycles, so tag their
    // semantic owner instead of letting either goal claim every shared position.
    public static final String TEMP_FURNACE_KIND = "PlayerNpcTemporaryFurnaceKind";
    public static final String TEMP_FURNACE_KIND_COOKING = "cooking";
    public static final String TEMP_FURNACE_KIND_NIGHT_CAMP = "night_camp";

    private static final int MAX_INPUT_STACK = 64;
    private static final int COAL_FUEL_BATCH = 8;
    private static final int SMALL_FUEL_BATCH = 16;
    private static final int PLANK_FUEL_BATCH = 8;
    private static final int LOG_FUEL_BATCH = 2;
    private static final int CHARCOAL_INPUT_BATCH = 1;

    private final PlayerNpcEntity playerNpc;

    public FurnaceAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public static boolean hasValidTrackedTemporaryFurnace(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        if (serverLevel == null
                || playerNpc == null
                || !playerNpc.getPersistentData().contains(TEMP_FURNACE_X)
                || !playerNpc.getPersistentData().contains(TEMP_FURNACE_Y)
                || !playerNpc.getPersistentData().contains(TEMP_FURNACE_Z)) {
            return false;
        }

        BlockPos pos = new BlockPos(
                playerNpc.getPersistentData().getIntOr(TEMP_FURNACE_X, 0),
                playerNpc.getPersistentData().getIntOr(TEMP_FURNACE_Y, 0),
                playerNpc.getPersistentData().getIntOr(TEMP_FURNACE_Z, 0)
        );
        return serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).is(Blocks.FURNACE)
                && serverLevel.getBlockEntity(pos) instanceof FurnaceBlockEntity;
    }

    public boolean hasFurnaceWork(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace
                && this.hasFurnaceWork(serverLevel, furnace);
    }

    public boolean hasFurnaceWork(ServerLevel serverLevel, FurnaceBlockEntity furnace) {
        if (hasOutput(furnace)) {
            return true;
        }

        boolean hasInput = !furnace.getItem(0).isEmpty();
        boolean hasFuelInFurnace = !furnace.getItem(1).isEmpty();
        boolean hasInventoryInput = this.hasInputForWork(serverLevel);
        boolean hasInventoryFuel = this.hasFuel();
        return hasInput && !hasFuelInFurnace && hasInventoryFuel
                || !hasInput && hasInventoryInput && (hasFuelInFurnace || hasInventoryFuel);
    }

    public boolean shouldPlaceFurnaceForWork(ServerLevel serverLevel) {
        return this.canUseOrCraftFurnace()
                && this.hasInputForWork(serverLevel)
                && this.hasFuel();
    }

    public String describePendingWork(ServerLevel serverLevel) {
        if (PlayerNpcBuildMaterialUtil.needsGlassSmelting(serverLevel, this.playerNpc)) {
            return "build glass";
        }
        if (PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, this.playerNpc)) {
            return "build stone";
        }
        if (PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc)) {
            return "build torch charcoal";
        }
        if (FarmAi.needsFarmTorchCharcoalSmelting(serverLevel, this.playerNpc)) {
            return "farm torch charcoal";
        }
        if (this.hasCookableFood()) {
            return "food";
        }
        if (this.hasHeldOrInventoryItem(FurnaceAi::isSmeltableOreMaterial)) {
            return "ore";
        }
        return "unknown";
    }

    public boolean hasInputForWork(ServerLevel serverLevel) {
        return PlayerNpcBuildMaterialUtil.needsGlassSmelting(serverLevel, this.playerNpc)
                || PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, this.playerNpc)
                || PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc)
                || FarmAi.needsFarmTorchCharcoalSmelting(serverLevel, this.playerNpc)
                || this.hasCookableFood()
                || this.hasHeldOrInventoryItem(FurnaceAi::isSmeltableOreMaterial);
    }

    public boolean hasFuel() {
        return this.hasHeldOrInventoryItem(stack -> isFuel(this.playerNpc.level(), stack));
    }

    public boolean isFurnaceEmpty(FurnaceBlockEntity furnace) {
        return furnace.getItem(0).isEmpty()
                && furnace.getItem(1).isEmpty()
                && furnace.getItem(2).isEmpty();
    }

    public boolean takeOutput(ServerLevel serverLevel, BlockPos furnacePos) {
        return serverLevel.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace
                && this.takeOutput(serverLevel, furnacePos, furnace);
    }

    public boolean takeOutput(ServerLevel serverLevel, BlockPos furnacePos, FurnaceBlockEntity furnace) {
        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) {
            return false;
        }

        ItemStack moved = output.copy();
        furnace.setItem(2, ItemStack.EMPTY);
        furnace.setChanged();
        if (!InventoryUtils.addItem(this.playerNpc, moved)) {
            this.playerNpc.spawnAtLocation(moved);
        }
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, furnacePos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
        this.playerNpc.setCurrentAiDetail("taking furnace output");
        return true;
    }

    public boolean fillFurnace(ServerLevel serverLevel, BlockPos furnacePos, FurnaceBlockEntity furnace) {
        boolean movedAny = false;
        boolean torchCharcoalWork = this.isTorchCharcoalWork(serverLevel, furnace);
        if (furnace.getItem(0).isEmpty()) {
            ItemStack input = this.takeCookingInput(serverLevel).orElse(ItemStack.EMPTY);
            if (!input.isEmpty()) {
                furnace.setItem(0, input);
                movedAny = true;
            }
        }

        if (furnace.getItem(1).isEmpty()) {
            ItemStack fuel = this.takeFuelInput(torchCharcoalWork).orElse(ItemStack.EMPTY);
            if (!fuel.isEmpty()) {
                furnace.setItem(1, fuel);
                movedAny = true;
            }
        }

        if (!movedAny) {
            return false;
        }

        furnace.setChanged();
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, furnacePos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
        this.playerNpc.setCurrentAiDetail("loading furnace");
        return true;
    }

    public boolean canUseOrCraftFurnace() {
        return InventoryUtils.hasItem(this.playerNpc, Items.FURNACE)
                || PlayerNpcCraftingUtil.canCraftFurnace(this.playerNpc.getInventory());
    }

    public static boolean hasOutput(ServerLevel serverLevel, BlockPos furnacePos) {
        return serverLevel.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace
                && hasOutput(furnace);
    }

    public static boolean hasOutput(FurnaceBlockEntity furnace) {
        return !furnace.getItem(2).isEmpty();
    }

    public static boolean isFuel(Level level, ItemStack stack) {
        return !stack.isEmpty() && level.fuelValues().isFuel(stack);
    }

    public static boolean isCookableFood(ItemStack stack) {
        return stack.is(Items.BEEF)
                || stack.is(Items.PORKCHOP)
                || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON)
                || stack.is(Items.RABBIT)
                || stack.is(Items.COD)
                || stack.is(Items.SALMON)
                || stack.is(Items.POTATO);
    }

    public static boolean isSmeltableOreMaterial(ItemStack stack) {
        return stack.is(Items.RAW_IRON)
                || stack.is(Items.RAW_COPPER)
                || stack.is(Items.RAW_GOLD)
                || stack.is(Items.IRON_ORE)
                || stack.is(Items.DEEPSLATE_IRON_ORE)
                || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)
                || stack.is(Items.GOLD_ORE)
                || stack.is(Items.DEEPSLATE_GOLD_ORE);
    }

    private Optional<ItemStack> takeCookingInput(ServerLevel serverLevel) {
        return this.takeGlassSandInput(serverLevel)
                .or(() -> this.takeStoneSmeltingInput(serverLevel))
                .or(() -> this.takeTorchCharcoalInput(serverLevel))
                .or(() -> this.consumeFirstMatchingStack(FurnaceAi::isCookableFood, MAX_INPUT_STACK))
                .or(() -> this.consumeFirstMatchingStack(FurnaceAi::isSmeltableOreMaterial, MAX_INPUT_STACK));
    }

    private Optional<ItemStack> takeGlassSandInput(ServerLevel serverLevel) {
        int missingGlass = PlayerNpcBuildMaterialUtil.missingGlassForProduction(serverLevel, this.playerNpc);
        if (missingGlass <= 0) {
            return Optional.empty();
        }

        return this.consumeFirstMatchingStack(
                PlayerNpcBuildMaterialUtil::isGlassSmeltingInput,
                Math.min(missingGlass, MAX_INPUT_STACK)
        );
    }

    private Optional<ItemStack> takeStoneSmeltingInput(ServerLevel serverLevel) {
        int missingStone = PlayerNpcBuildMaterialUtil.missingStoneForProduction(serverLevel, this.playerNpc);
        if (missingStone <= 0) {
            return Optional.empty();
        }

        return this.consumeFirstMatchingStack(
                PlayerNpcBuildMaterialUtil::isStoneSmeltingInput,
                Math.min(missingStone, MAX_INPUT_STACK)
        );
    }

    private Optional<ItemStack> takeTorchCharcoalInput(ServerLevel serverLevel) {
        if (!PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc)
                && !FarmAi.needsFarmTorchCharcoalSmelting(serverLevel, this.playerNpc)) {
            return Optional.empty();
        }

        return this.consumeFirstMatchingStack(
                PlayerNpcBuildMaterialUtil::isTorchCharcoalInput,
                CHARCOAL_INPUT_BATCH
        );
    }

    private Optional<ItemStack> takeFuelInput(boolean torchCharcoalWork) {
        if (torchCharcoalWork) {
            return this.takeTorchCharcoalFuelInput()
                    .or(this::takeNormalFuelInput);
        }
        return this.takeNormalFuelInput();
    }

    private Optional<ItemStack> takeNormalFuelInput() {
        return this.consumeFirstMatchingStack(stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL), COAL_FUEL_BATCH)
                .or(() -> this.consumeFirstMatchingStack(stack -> stack.is(ItemTags.SAPLINGS), SMALL_FUEL_BATCH))
                .or(() -> this.consumeFirstMatchingStack(stack -> stack.is(Items.STICK), SMALL_FUEL_BATCH))
                .or(() -> this.consumeFirstMatchingStack(stack -> stack.is(ItemTags.PLANKS), PLANK_FUEL_BATCH))
                .or(this::takeExcessLogFuelInput)
                .or(() -> this.consumeFirstMatchingStack(stack -> isFuel(this.playerNpc.level(), stack), 1));
    }

    private Optional<ItemStack> takeTorchCharcoalFuelInput() {
        return this.consumeFirstMatchingStack(stack -> stack.is(ItemTags.SAPLINGS), SMALL_FUEL_BATCH)
                .or(this::takeExcessLogFuelInput)
                .or(() -> this.consumeFirstMatchingStack(stack -> stack.is(ItemTags.PLANKS), 1))
                .or(() -> this.consumeFirstMatchingStack(stack -> isFuel(this.playerNpc.level(), stack), 1));
    }

    private boolean isTorchCharcoalWork(ServerLevel serverLevel, FurnaceBlockEntity furnace) {
        return PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc)
                || FarmAi.needsFarmTorchCharcoalSmelting(serverLevel, this.playerNpc)
                || furnace.getItem(0).is(ItemTags.LOGS)
                && (PlayerNpcBuildMaterialUtil.hasMissingCharcoalBuildMaterial(serverLevel, this.playerNpc)
                || FarmAi.hasPendingFarmLighting(serverLevel, this.playerNpc));
    }

    private boolean hasCookableFood() {
        return this.hasHeldOrInventoryItem(FurnaceAi::isCookableFood);
    }

    private Optional<ItemStack> takeExcessLogFuelInput() {
        int excessLogs = ResourceAi.countLogs(this.playerNpc) - this.playerNpc.getRawLogReserveTarget();
        if (excessLogs <= 0) {
            return Optional.empty();
        }
        return this.consumeFirstMatchingStack(stack -> stack.is(ItemTags.LOGS), Math.min(LOG_FUEL_BATCH, excessLogs));
    }

    private Optional<ItemStack> consumeFirstMatchingStack(Predicate<ItemStack> matcher, int maxCount) {
        if (maxCount <= 0) {
            return Optional.empty();
        }

        SimpleContainer inventory = this.playerNpc.getInventory();
        ItemStack template = this.firstMatchingStack(inventory, matcher);
        if (template.isEmpty()) {
            template = this.firstMatchingHeldStack(matcher);
        }
        if (template.isEmpty()) {
            return Optional.empty();
        }

        int available = this.countMatchingItems(inventory, template);
        available += this.countMatchingHeldItems(template);
        if (available <= 0) {
            return Optional.empty();
        }

        int remaining = Math.min(maxCount, Math.min(available, template.getMaxStackSize()));
        ItemStack consumed = template.copy();
        consumed.setCount(0);
        remaining = this.consumeInventoryItems(inventory, template, remaining, consumed);
        this.consumeHeldItems(template, remaining, consumed);

        if (consumed.isEmpty()) {
            return Optional.empty();
        }
        inventory.setChanged();
        return Optional.of(consumed);
    }

    private boolean hasHeldOrInventoryItem(Predicate<ItemStack> matcher) {
        return InventoryUtils.hasItem(this.playerNpc, matcher)
                || matcher.test(this.playerNpc.getMainHandItem())
                || matcher.test(this.playerNpc.getOffhandItem());
    }

    private ItemStack firstMatchingStack(SimpleContainer inventory, Predicate<ItemStack> matcher) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            ItemStack template = stack.copy();
            template.setCount(1);
            return template;
        }
        return ItemStack.EMPTY;
    }

    private ItemStack firstMatchingHeldStack(Predicate<ItemStack> matcher) {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            ItemStack template = mainHand.copy();
            template.setCount(1);
            return template;
        }

        ItemStack offhand = this.playerNpc.getOffhandItem();
        if (!offhand.isEmpty() && matcher.test(offhand)) {
            ItemStack template = offhand.copy();
            template.setCount(1);
            return template;
        }
        return ItemStack.EMPTY;
    }

    private int countMatchingItems(SimpleContainer inventory, ItemStack template) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countMatchingHeldItems(ItemStack template) {
        int count = 0;
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (!mainHand.isEmpty() && ItemStack.isSameItemSameComponents(mainHand, template)) {
            count += mainHand.getCount();
        }
        ItemStack offhand = this.playerNpc.getOffhandItem();
        if (!offhand.isEmpty() && ItemStack.isSameItemSameComponents(offhand, template)) {
            count += offhand.getCount();
        }
        return count;
    }

    private int consumeInventoryItems(SimpleContainer inventory, ItemStack template, int remaining, ItemStack consumed) {
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) {
                continue;
            }

            int moving = Math.min(remaining, stack.getCount());
            stack.shrink(moving);
            consumed.grow(moving);
            remaining -= moving;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        return remaining;
    }

    private void consumeHeldItems(ItemStack template, int remaining, ItemStack consumed) {
        remaining = this.consumeHeldItem(EquipmentSlot.MAINHAND, template, remaining, consumed);
        this.consumeHeldItem(EquipmentSlot.OFFHAND, template, remaining, consumed);
    }

    private int consumeHeldItem(EquipmentSlot slot, ItemStack template, int remaining, ItemStack consumed) {
        if (remaining <= 0) {
            return 0;
        }

        ItemStack held = slot == EquipmentSlot.MAINHAND
                ? this.playerNpc.getMainHandItem()
                : this.playerNpc.getOffhandItem();
        if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, template)) {
            return remaining;
        }

        int moving = Math.min(remaining, held.getCount());
        ItemStack updated = held.copy();
        updated.shrink(moving);
        consumed.grow(moving);
        if (slot == EquipmentSlot.MAINHAND) {
            this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, updated);
        } else {
            this.playerNpc.setItemSlot(EquipmentSlot.OFFHAND, updated);
        }
        return remaining - moving;
    }
}
