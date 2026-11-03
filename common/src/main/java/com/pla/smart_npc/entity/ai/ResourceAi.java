package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public final class ResourceAi {
    private static final int[] LOG_SUPPLY_GOALS = {4, 5, 6, 7, 8, 9, 10, 11, 12};
    private static final int[] STONE_SUPPLY_GOALS = {12, 16, 20, 24};
    private static final int MIN_ADDITIONAL_SUPPLY = 3;
    private static final int MAX_ADDITIONAL_SUPPLY = 12;
    private static final Map<PlayerNpcEntity, ResourceCountCache> RESOURCE_COUNT_CACHE = new WeakHashMap<>();

    private ResourceAi() {
    }

    public static int randomLogSupplyGoal(RandomSource random) {
        return LOG_SUPPLY_GOALS[random.nextInt(LOG_SUPPLY_GOALS.length)];
    }

    public static int randomStoneSupplyGoal(RandomSource random) {
        return STONE_SUPPLY_GOALS[random.nextInt(STONE_SUPPLY_GOALS.length)];
    }

    public static int randomAdditionalSupplyAmount(RandomSource random) {
        return MIN_ADDITIONAL_SUPPLY
                + random.nextInt(MAX_ADDITIONAL_SUPPLY - MIN_ADDITIONAL_SUPPLY + 1);
    }

    public static int countLogs(PlayerNpcEntity playerNpc) {
        return resourceCounts(playerNpc).logs();
    }

    public static int countStone(PlayerNpcEntity playerNpc) {
        return resourceCounts(playerNpc).stone();
    }

    public static int countDirt(PlayerNpcEntity playerNpc) {
        return resourceCounts(playerNpc).dirt();
    }

    /** Invalidates the current-tick snapshot after a container mutation. */
    public static void invalidate(PlayerNpcEntity playerNpc) {
        if (playerNpc != null) {
            RESOURCE_COUNT_CACHE.remove(playerNpc);
        }
    }

    public static int countHeldAndInventoryItems(PlayerNpcEntity playerNpc, Predicate<ItemStack> matcher) {
        int count = 0;
        ItemStack mainHand = playerNpc.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            count += mainHand.getCount();
        }
        ItemStack offhand = playerNpc.getOffhandItem();
        if (!offhand.isEmpty() && matcher.test(offhand)) {
            count += offhand.getCount();
        }
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static ResourceCountCache resourceCounts(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return ResourceCountCache.EMPTY;
        }
        ItemStack mainHand = playerNpc.getMainHandItem();
        ItemStack offhand = playerNpc.getOffhandItem();
        int mainHandSignature = resourceStackSignature(mainHand);
        int offhandSignature = resourceStackSignature(offhand);
        ResourceCountCache cached = RESOURCE_COUNT_CACHE.get(playerNpc);
        if (cached != null && cached.matches(playerNpc.tickCount, mainHandSignature, offhandSignature)) {
            return cached;
        }

        int[] counts = new int[3];
        addResourceCounts(mainHand, counts);
        addResourceCounts(offhand, counts);
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            addResourceCounts(playerNpc.getInventory().getItem(i), counts);
        }
        ResourceCountCache result = new ResourceCountCache(
                playerNpc.tickCount,
                mainHandSignature,
                offhandSignature,
                counts[0],
                counts[1],
                counts[2]
        );
        RESOURCE_COUNT_CACHE.put(playerNpc, result);
        return result;
    }

    private static int resourceStackSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        int resourceBits = 0;
        if (stack.is(ItemTags.LOGS)) {
            resourceBits |= 1;
        }
        if (stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)) {
            resourceBits |= 2;
        }
        if (stack.is(Items.DIRT)) {
            resourceBits |= 4;
        }
        return resourceBits == 0 ? 0 : resourceBits | stack.getCount() << 3;
    }

    private static void addResourceCounts(ItemStack stack, int[] counts) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        int amount = stack.getCount();
        if (stack.is(ItemTags.LOGS)) {
            counts[0] += amount;
        }
        if (stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE)) {
            counts[1] += amount;
        }
        if (stack.is(Items.DIRT)) {
            counts[2] += amount;
        }
    }

    private record ResourceCountCache(
            int entityTick,
            int mainHandSignature,
            int offhandSignature,
            int logs,
            int stone,
            int dirt
    ) {
        private static final ResourceCountCache EMPTY = new ResourceCountCache(Integer.MIN_VALUE, 0, 0, 0, 0, 0);

        private boolean matches(int currentTick, int currentMainHandSignature, int currentOffhandSignature) {
            return this.entityTick == currentTick
                    && this.mainHandSignature == currentMainHandSignature
                    && this.offhandSignature == currentOffhandSignature;
        }
    }
}
