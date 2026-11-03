package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FurnaceAi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

public final class PlayerNpcBuildMaterialUtil {
    private static final int BUILD_CRAFT_RAW_LOG_RESERVE = 0;
    private static final int GLASS_PANE_CRAFT_INPUT = 6;
    private static final int MISSING_NEED_CACHE_TICKS = 20 * 3;
    // Missing-material refreshes run from goal predicates on the server thread. Resume the
    // existing cursor after eight blocks instead of turning one admission into a layout sweep.
    private static final int MAX_MISSING_NEED_BLOCKS_PER_SLICE = 8;
    private static final EnumMap<MaterialFamily, List<Item>> CANDIDATE_CACHE = new EnumMap<>(MaterialFamily.class);
    private static final Map<PlayerNpcEntity, MissingNeedCache> MISSING_NEED_CACHE = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, MissingNeedSearch> MISSING_NEED_SEARCHES = new WeakHashMap<>();
    private static final Set<String> WOOD_PREFIXES = Set.of(
            "oak",
            "spruce",
            "birch",
            "jungle",
            "acacia",
            "dark_oak",
            "mangrove",
            "cherry",
            "bamboo",
            "crimson",
            "warped"
    );
    private static final List<Item> COBBLESTONE_LIKE = List.of(
            Items.COBBLESTONE,
            Items.MOSSY_COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.BLACKSTONE,
            Items.SANDSTONE,
            Items.RED_SANDSTONE,
            Items.PACKED_MUD,
            Items.DEEPSLATE_TILES
    );
    private static final List<Item> LOOSE_FILL = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.PODZOL,
            Items.ROOTED_DIRT,
            Items.GRASS_BLOCK,
            Items.SAND,
            Items.RED_SAND,
            Items.GRAVEL,
            Items.MUD
    );
    private static final List<Item> STONE_MASONRY = List.of(
            Items.STONE,
            Items.SMOOTH_STONE,
            Items.STONE_BRICKS,
            Items.CRACKED_STONE_BRICKS,
            Items.MOSSY_STONE_BRICKS,
            Items.BRICKS,
            Items.ANDESITE,
            Items.POLISHED_ANDESITE,
            Items.DIORITE,
            Items.POLISHED_DIORITE,
            Items.GRANITE,
            Items.POLISHED_GRANITE,
            Items.DEEPSLATE,
            Items.POLISHED_DEEPSLATE,
            Items.DEEPSLATE_BRICKS,
            Items.CRACKED_DEEPSLATE_BRICKS,
            Items.TUFF,
            Items.CALCITE,
            Items.SANDSTONE,
            Items.SMOOTH_SANDSTONE,
            Items.CUT_SANDSTONE,
            Items.RED_SANDSTONE,
            Items.SMOOTH_RED_SANDSTONE,
            Items.CUT_RED_SANDSTONE,
            Items.MUD_BRICKS,
            Items.DEEPSLATE_BRICKS
    );
    private static final List<Item> STONE_STAIRS = List.of(
            Items.COBBLESTONE_STAIRS,
            Items.MOSSY_COBBLESTONE_STAIRS,
            Items.STONE_STAIRS,
            Items.STONE_BRICK_STAIRS,
            Items.MOSSY_STONE_BRICK_STAIRS,
            Items.BRICK_STAIRS,
            Items.ANDESITE_STAIRS,
            Items.POLISHED_ANDESITE_STAIRS,
            Items.DIORITE_STAIRS,
            Items.POLISHED_DIORITE_STAIRS,
            Items.GRANITE_STAIRS,
            Items.POLISHED_GRANITE_STAIRS,
            Items.COBBLED_DEEPSLATE_STAIRS,
            Items.POLISHED_DEEPSLATE_STAIRS,
            Items.DEEPSLATE_BRICK_STAIRS,
            Items.BLACKSTONE_STAIRS,
            Items.POLISHED_BLACKSTONE_STAIRS,
            Items.SANDSTONE_STAIRS,
            Items.SMOOTH_SANDSTONE_STAIRS,
            Items.RED_SANDSTONE_STAIRS,
            Items.SMOOTH_RED_SANDSTONE_STAIRS
    );
    private static final List<Item> STONE_SLABS = List.of(
            Items.COBBLESTONE_SLAB,
            Items.MOSSY_COBBLESTONE_SLAB,
            Items.STONE_SLAB,
            Items.SMOOTH_STONE_SLAB,
            Items.STONE_BRICK_SLAB,
            Items.MOSSY_STONE_BRICK_SLAB,
            Items.BRICK_SLAB,
            Items.ANDESITE_SLAB,
            Items.POLISHED_ANDESITE_SLAB,
            Items.DIORITE_SLAB,
            Items.POLISHED_DIORITE_SLAB,
            Items.GRANITE_SLAB,
            Items.POLISHED_GRANITE_SLAB,
            Items.COBBLED_DEEPSLATE_SLAB,
            Items.POLISHED_DEEPSLATE_SLAB,
            Items.DEEPSLATE_BRICK_SLAB,
            Items.BLACKSTONE_SLAB,
            Items.POLISHED_BLACKSTONE_SLAB,
            Items.SANDSTONE_SLAB,
            Items.SMOOTH_SANDSTONE_SLAB,
            Items.RED_SANDSTONE_SLAB,
            Items.SMOOTH_RED_SANDSTONE_SLAB
    );
    private static final List<Item> WOODEN_BUTTONS = List.of(
            Items.OAK_BUTTON,
            Items.SPRUCE_BUTTON,
            Items.BIRCH_BUTTON,
            Items.JUNGLE_BUTTON,
            Items.ACACIA_BUTTON,
            Items.DARK_OAK_BUTTON,
            Items.MANGROVE_BUTTON,
            Items.CHERRY_BUTTON,
            Items.BAMBOO_BUTTON,
            Items.CRIMSON_BUTTON,
            Items.WARPED_BUTTON
    );
    private static final List<Item> STONE_BUTTONS = List.of(
            Items.STONE_BUTTON,
            Items.POLISHED_BLACKSTONE_BUTTON
    );
    private static final List<Item> WOODEN_PRESSURE_PLATES = List.of(
            Items.OAK_PRESSURE_PLATE,
            Items.SPRUCE_PRESSURE_PLATE,
            Items.BIRCH_PRESSURE_PLATE,
            Items.JUNGLE_PRESSURE_PLATE,
            Items.ACACIA_PRESSURE_PLATE,
            Items.DARK_OAK_PRESSURE_PLATE,
            Items.MANGROVE_PRESSURE_PLATE,
            Items.CHERRY_PRESSURE_PLATE,
            Items.BAMBOO_PRESSURE_PLATE,
            Items.CRIMSON_PRESSURE_PLATE,
            Items.WARPED_PRESSURE_PLATE
    );
    private static final List<Item> STONE_OR_METAL_PRESSURE_PLATES = List.of(
            Items.STONE_PRESSURE_PLATE,
            Items.POLISHED_BLACKSTONE_PRESSURE_PLATE,
            Items.LIGHT_WEIGHTED_PRESSURE_PLATE,
            Items.HEAVY_WEIGHTED_PRESSURE_PLATE
    );
    private static final List<Item> GLASS_BLOCKS = List.of(
            Items.GLASS,
            Items.WHITE_STAINED_GLASS,
            Items.ORANGE_STAINED_GLASS,
            Items.MAGENTA_STAINED_GLASS,
            Items.LIGHT_BLUE_STAINED_GLASS,
            Items.YELLOW_STAINED_GLASS,
            Items.LIME_STAINED_GLASS,
            Items.PINK_STAINED_GLASS,
            Items.GRAY_STAINED_GLASS,
            Items.LIGHT_GRAY_STAINED_GLASS,
            Items.CYAN_STAINED_GLASS,
            Items.PURPLE_STAINED_GLASS,
            Items.BLUE_STAINED_GLASS,
            Items.BROWN_STAINED_GLASS,
            Items.GREEN_STAINED_GLASS,
            Items.RED_STAINED_GLASS,
            Items.BLACK_STAINED_GLASS
    );
    private static final List<Item> GLASS_PANES = List.of(
            Items.GLASS_PANE,
            Items.WHITE_STAINED_GLASS_PANE,
            Items.ORANGE_STAINED_GLASS_PANE,
            Items.MAGENTA_STAINED_GLASS_PANE,
            Items.LIGHT_BLUE_STAINED_GLASS_PANE,
            Items.YELLOW_STAINED_GLASS_PANE,
            Items.LIME_STAINED_GLASS_PANE,
            Items.PINK_STAINED_GLASS_PANE,
            Items.GRAY_STAINED_GLASS_PANE,
            Items.LIGHT_GRAY_STAINED_GLASS_PANE,
            Items.CYAN_STAINED_GLASS_PANE,
            Items.PURPLE_STAINED_GLASS_PANE,
            Items.BLUE_STAINED_GLASS_PANE,
            Items.BROWN_STAINED_GLASS_PANE,
            Items.GREEN_STAINED_GLASS_PANE,
            Items.RED_STAINED_GLASS_PANE,
            Items.BLACK_STAINED_GLASS_PANE
    );
    private static final List<Item> FLOWERS = List.of(
            Items.DANDELION,
            Items.POPPY,
            Items.BLUE_ORCHID,
            Items.ALLIUM,
            Items.AZURE_BLUET,
            Items.RED_TULIP,
            Items.ORANGE_TULIP,
            Items.WHITE_TULIP,
            Items.PINK_TULIP,
            Items.OXEYE_DAISY,
            Items.CORNFLOWER,
            Items.LILY_OF_THE_VALLEY,
            Items.WITHER_ROSE,
            Items.TORCHFLOWER
    );
    private static final List<Item> DECORATIVE_PLANTS = List.of(
            Items.FERN,
            Items.DEAD_BUSH,
            Items.RED_MUSHROOM,
            Items.BROWN_MUSHROOM,
            Items.CRIMSON_FUNGUS,
            Items.WARPED_FUNGUS,
            Items.CRIMSON_ROOTS,
            Items.WARPED_ROOTS
    );
    private static final List<Item> BUILD_PLANTS = buildPlantCandidates();
    private static final List<Item> POTTABLE_PLANTS = List.of(
            Items.DANDELION,
            Items.POPPY,
            Items.BLUE_ORCHID,
            Items.ALLIUM,
            Items.AZURE_BLUET,
            Items.RED_TULIP,
            Items.ORANGE_TULIP,
            Items.WHITE_TULIP,
            Items.PINK_TULIP,
            Items.OXEYE_DAISY,
            Items.CORNFLOWER,
            Items.LILY_OF_THE_VALLEY,
            Items.WITHER_ROSE,
            Items.TORCHFLOWER,
            Items.OAK_SAPLING,
            Items.SPRUCE_SAPLING,
            Items.BIRCH_SAPLING,
            Items.JUNGLE_SAPLING,
            Items.ACACIA_SAPLING,
            Items.DARK_OAK_SAPLING,
            Items.MANGROVE_PROPAGULE,
            Items.CHERRY_SAPLING,
            Items.FERN,
            Items.DEAD_BUSH,
            Items.CACTUS,
            Items.BAMBOO,
            Items.RED_MUSHROOM,
            Items.BROWN_MUSHROOM,
            Items.CRIMSON_FUNGUS,
            Items.WARPED_FUNGUS,
            Items.CRIMSON_ROOTS,
            Items.WARPED_ROOTS,
            Items.AZALEA,
            Items.FLOWERING_AZALEA
    );

    private PlayerNpcBuildMaterialUtil() {
    }

    public static boolean matches(BlockState existingState, BlockState targetState) {
        if (existingState.equals(targetState)) {
            return true;
        }
        if (isPartialDoubleChestMatch(existingState, targetState)) {
            return true;
        }
        if (targetState.isAir()) {
            return existingState.isAir();
        }

        MaterialFamily family = familyForState(targetState);
        return family != null
                && family == familyForState(existingState)
                && sharedPropertiesMatch(targetState, existingState);
    }

    public static boolean isBlueprintPlaceholder(BlockState state) {
        if (state == null) {
            return false;
        }

        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null
                && "structurize".equals(key.getNamespace())
                && "blocksolidsubstitution".equals(key.getPath());
    }

    public static boolean hasMaterialFor(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block, BlockPos origin) {
        BlockState targetState = block.state();
        if (targetState.isAir() || isBlueprintPlaceholder(targetState)) {
            return true;
        }
        if (block.isSecondHalfOfSingleItemBlock()) {
            return resolveSecondHalfState(serverLevel, block.toWorld(origin), targetState).isPresent();
        }
        if (isPottedPlant(targetState)) {
            return hasFlowerPot(playerNpc) && hasPottablePlant(playerNpc);
        }

        return findAvailableItem(serverLevel, playerNpc, targetState).isPresent();
    }

    public static boolean needsCraftingForPlacement(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block.state().isAir()
                || isBlueprintPlaceholder(block.state())
                || block.isSecondHalfOfSingleItemBlock()
                || isPottedPlant(block.state())) {
            return false;
        }

        return findDirectItem(playerNpc, block.state()).isEmpty()
                && findCraftableItem(serverLevel, playerNpc, block.state()).isPresent();
    }

    public static boolean craftMaterialFor(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block.state().isAir()
                || isBlueprintPlaceholder(block.state())
                || block.requiredItem().isEmpty()
                || findDirectItem(playerNpc, block.state()).isPresent()) {
            return false;
        }

        Optional<Item> item = findCraftableItem(serverLevel, playerNpc, block.state());
        if (item.isEmpty()) {
            return false;
        }
        if (item.get() == Items.TORCH) {
            return PlayerNpcCraftingUtil.tryCraftTorches(playerNpc.getInventory(), BUILD_CRAFT_RAW_LOG_RESERVE);
        }
        if (item.get() == Items.GLASS_PANE && tryCraftGlassPaneFromAnyGlass(playerNpc.getInventory())) {
            return true;
        }
        if (item.get() instanceof BedItem) {
            return PlayerNpcCraftingUtil.tryCraftBed(playerNpc.getInventory(), BUILD_CRAFT_RAW_LOG_RESERVE);
        }
        return PlayerNpcCraftingUtil.tryCraftWithLogConversion(serverLevel, playerNpc.getInventory(), item.get(), true, BUILD_CRAFT_RAW_LOG_RESERVE);
    }

    public static Optional<PlacementMaterial> resolvePlacement(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block, BlockPos origin) {
        BlockState targetState = block.state();
        if (targetState.isAir() || isBlueprintPlaceholder(targetState)) {
            return Optional.of(new PlacementMaterial(targetState, ItemStack.EMPTY));
        }
        if (isPottedPlant(targetState)) {
            return resolvePottedPlantPlacement(playerNpc, targetState);
        }
        if (block.isSecondHalfOfSingleItemBlock()) {
            return resolveSecondHalfState(serverLevel, block.toWorld(origin), targetState)
                    .map(state -> new PlacementMaterial(state, ItemStack.EMPTY));
        }
        if (block.requiredItem().isEmpty()) {
            return Optional.of(new PlacementMaterial(targetState, ItemStack.EMPTY));
        }

        Optional<Item> item = findAvailableItem(serverLevel, playerNpc, targetState);
        if (item.isEmpty()) {
            return Optional.empty();
        }

        ItemStack consumed = consumeOrCraft(serverLevel, playerNpc, item.get());
        if (consumed.isEmpty()) {
            return Optional.empty();
        }

        BlockState placementState = stateForItem(targetState, consumed.getItem()).orElse(targetState);
        return Optional.of(new PlacementMaterial(placementState, consumed));
    }

    public static ItemStack previewItem(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block.state().isAir()
                || isBlueprintPlaceholder(block.state())
                || block.isSecondHalfOfSingleItemBlock()) {
            return ItemStack.EMPTY;
        }
        if (isPottedPlant(block.state())) {
            return new ItemStack(Items.FLOWER_POT);
        }

        return findAvailableItem(serverLevel, playerNpc, block.state())
                .map(ItemStack::new)
                .orElse(block.requiredItem());
    }

    public static boolean needsSandForBuildMaterial(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block, BlockPos origin) {
        BlockState targetState = block.state();
        MaterialFamily family = familyForState(targetState);
        return (family == MaterialFamily.GLASS_BLOCKS || family == MaterialFamily.GLASS_PANES)
                && !hasMaterialFor(serverLevel, playerNpc, block, origin)
                && countFamilyItems(playerNpc, MaterialFamily.GLASS_BLOCKS) + countGlassSmeltingInput(playerNpc) < requiredGlassFor(family);
    }

    public static Optional<MissingBuildMaterialNeed> findMissingBuildMaterialNeed(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            MISSING_NEED_SEARCHES.remove(playerNpc);
            return Optional.empty();
        }

        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc);
        String currentLayoutId = layoutId.orElse("");
        int inventoryHash = missingNeedInventoryHash(playerNpc);
        MissingNeedCache cache = MISSING_NEED_CACHE.get(playerNpc);
        if (cache != null && cache.matches(playerNpc.tickCount, home.get(), currentLayoutId, inventoryHash)) {
            return cache.need();
        }

        Optional<PlayerNpcBuildLayout> layout = layoutId.flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != home.get().width()
                || layout.get().depth() != home.get().depth()) {
            MISSING_NEED_SEARCHES.remove(playerNpc);
            MISSING_NEED_CACHE.put(playerNpc, new MissingNeedCache(
                    playerNpc.tickCount,
                    home.get().origin(),
                    home.get().width(),
                    home.get().depth(),
                    currentLayoutId,
                    inventoryHash,
                    Optional.empty()
            ));
            return Optional.empty();
        }

        MissingNeedSearch search = MISSING_NEED_SEARCHES.get(playerNpc);
        if (search == null || !search.matches(
                serverLevel.dimension().identifier(),
                home.get(),
                currentLayoutId,
                inventoryHash
        )) {
            search = MissingNeedSearch.create(
                    serverLevel.dimension().identifier(),
                    home.get(),
                    currentLayoutId,
                    inventoryHash,
                    playerNpc
            );
        }
        if (search.lastSliceTick() == playerNpc.tickCount) {
            // Active routine workers are allowed through the global work scheduler on every
            // predicate call. Several goals ask for the same missing need in one selector pass,
            // so without a per-search cadence those callers advance the entire blueprint in one
            // tick despite the eight-block cursor bound.
            MISSING_NEED_SEARCHES.put(playerNpc, search);
            return cachedNeedWhileSearchPending(cache, home.get(), currentLayoutId);
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, playerNpc)) {
            MISSING_NEED_SEARCHES.put(playerNpc, search);
            return cachedNeedWhileSearchPending(cache, home.get(), currentLayoutId);
        }

        BlockPos origin = home.get().origin();
        List<PlayerNpcBuildLayout.RelativeBlock> blocks = layout.get().blocks();
        int endIndex = Math.min(blocks.size(), search.nextBlockIndex() + MAX_MISSING_NEED_BLOCKS_PER_SLICE);
        for (int index = search.nextBlockIndex(); index < endIndex; index++) {
            PlayerNpcBuildLayout.RelativeBlock block = blocks.get(index);
            if (block.optional()
                    || block.state().isAir()
                    || isBlueprintPlaceholder(block.state())
                    || isSecondHalfOfSingleItemBlock(block.state())
                    || matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())
                    || search.materialLedger().consumePlacement(serverLevel, block)) {
                continue;
            }

            MissingBuildMaterialKind kind = missingKindFor(serverLevel, playerNpc, block, origin);
            search = search.withCandidate(new MissingBuildMaterialNeed(
                    kind,
                    block.state(),
                    block.toWorld(origin),
                    describeTarget(block.state())
            ));
        }
        search = search.withNextBlockIndex(endIndex, playerNpc.tickCount);
        if (endIndex < blocks.size()) {
            MISSING_NEED_SEARCHES.put(playerNpc, search);
            // Do not expose the new cursor's early candidate while a later slice may contain a
            // higher-priority primary material. Preserve the last completed answer until this
            // deterministic bounded refresh replaces it, so pending is never mistaken for none.
            return cachedNeedWhileSearchPending(cache, home.get(), currentLayoutId);
        }

        MISSING_NEED_SEARCHES.remove(playerNpc);
        Optional<MissingBuildMaterialNeed> selectedNeed = Optional.ofNullable(search.bestNeed());
        MISSING_NEED_CACHE.put(playerNpc, new MissingNeedCache(
                playerNpc.tickCount,
                home.get().origin(),
                home.get().width(),
                home.get().depth(),
                currentLayoutId,
                inventoryHash,
                selectedNeed
        ));
        return selectedNeed;
    }

    /** True while the loaded-layout missing-material query has more cursor slices to inspect. */
    public static boolean isMissingBuildMaterialSearchPending(PlayerNpcEntity playerNpc) {
        return playerNpc != null && MISSING_NEED_SEARCHES.containsKey(playerNpc);
    }

    private static Optional<MissingBuildMaterialNeed> cachedNeedWhileSearchPending(
            MissingNeedCache cache,
            PlayerNpcHomeUtil.HomeArea homeArea,
            String layoutId
    ) {
        return cache != null && cache.sameBuildContext(homeArea, layoutId)
                ? cache.need()
                : Optional.empty();
    }

    private static int missingNeedInventoryHash(PlayerNpcEntity playerNpc) {
        int hash = 1;
        hash = 31 * hash + missingNeedStackHash(playerNpc.getMainHandItem());
        hash = 31 * hash + missingNeedStackHash(playerNpc.getOffhandItem());
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            hash = 31 * hash + missingNeedStackHash(playerNpc.getInventory().getItem(i));
        }
        return hash;
    }

    private static int missingNeedStackHash(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        int hash = id == null ? 0 : id.hashCode();
        hash = 31 * hash + stack.getCount();
        hash = 31 * hash + stack.getComponents().hashCode();
        return hash;
    }

    public static boolean needsNonPrimaryBuildMaterial(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return findMissingBuildMaterialNeed(serverLevel, playerNpc)
                .map(MissingBuildMaterialNeed::kind)
                .map(kind -> kind != MissingBuildMaterialKind.LOG
                        && kind != MissingBuildMaterialKind.STONE
                        && kind != MissingBuildMaterialKind.TORCH
                        && kind != MissingBuildMaterialKind.NONE)
                .orElse(false);
    }

    public static boolean needsLogsForCurrentBuild(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<MissingBuildMaterialNeed> need = findMissingBuildMaterialNeed(serverLevel, playerNpc);
        if (need.isEmpty()) {
            return false;
        }

        MissingBuildMaterialNeed materialNeed = need.get();
        MissingBuildMaterialKind kind = materialNeed.kind();
        if (kind == MissingBuildMaterialKind.LOG) {
            return true;
        }
        if (kind != MissingBuildMaterialKind.TORCH
                || canCraftCharcoalBuildTarget(serverLevel, playerNpc, materialNeed.targetState())) {
            return false;
        }

        if (isCampfireTarget(materialNeed.targetState())) {
            return countTorchFuel(playerNpc) <= 0
                    ? !canProvideCampfireWood(playerNpc, 1)
                    : !canProvideCampfireWood(playerNpc, 0);
        }
        if (countTorchFuel(playerNpc) <= 0) {
            return countLogs(playerNpc) < requiredLogsForTorchCharcoal(playerNpc);
        }
        return !PlayerNpcCraftingUtil.canProvidePlanksAndSticks(playerNpc.getInventory(), 0, 1, BUILD_CRAFT_RAW_LOG_RESERVE);
    }

    public static boolean needsStoneForCurrentBuild(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<MissingBuildMaterialNeed> need = findMissingBuildMaterialNeed(serverLevel, playerNpc);
        if (need.isEmpty()) {
            return false;
        }

        MissingBuildMaterialNeed materialNeed = need.get();
        MissingBuildMaterialKind kind = materialNeed.kind();
        if (kind == MissingBuildMaterialKind.STONE) {
            return !needsStoneSmelting(serverLevel, playerNpc);
        }
        return kind == MissingBuildMaterialKind.TORCH
                && !canCraftCharcoalBuildTarget(serverLevel, playerNpc, materialNeed.targetState())
                && countTorchFuel(playerNpc) <= 0
                && canProvideCharcoalTargetWood(playerNpc, materialNeed.targetState())
                && !canUseOrCreateHomeFurnace(serverLevel, playerNpc);
    }

    public static boolean needsBedForCurrentBuild(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return findMissingBuildMaterialNeed(serverLevel, playerNpc)
                .map(MissingBuildMaterialNeed::kind)
                .map(kind -> kind == MissingBuildMaterialKind.BED)
                .orElse(false);
    }

    public static boolean isSandSourceBlock(BlockState state) {
        return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND);
    }

    public static boolean isGatherablePlantBlock(BlockState state) {
        Item item = state.getBlock().asItem();
        return item != Items.AIR
                && containsItem(BUILD_PLANTS, item);
    }

    private static MissingBuildMaterialKind missingKindFor(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            PlayerNpcBuildLayout.RelativeBlock block,
            BlockPos origin
    ) {
        BlockState targetState = block.state();
        MaterialFamily family = familyForState(targetState);
        if (family == MaterialFamily.GLASS_BLOCKS || family == MaterialFamily.GLASS_PANES) {
            return needsSandForBuildMaterial(serverLevel, playerNpc, block, origin)
                    ? MissingBuildMaterialKind.SAND
                    : MissingBuildMaterialKind.NONE;
        }
        if (isWoodSupplyFamily(family)) {
            return MissingBuildMaterialKind.LOG;
        }
        if (isStoneSupplyFamily(family)) {
            return MissingBuildMaterialKind.STONE;
        }
        if (family == MaterialFamily.BEDS || family == MaterialFamily.CARPETS) {
            return MissingBuildMaterialKind.BED;
        }
        if (family == MaterialFamily.FLOWERS || family == MaterialFamily.POTTED_FLOWERS) {
            return MissingBuildMaterialKind.PLANT;
        }
        if (family == MaterialFamily.LOOSE_FILL && isSandSourceBlock(targetState)) {
            return MissingBuildMaterialKind.SAND;
        }
        if (isTorchTarget(targetState) || isCampfireTarget(targetState)) {
            return MissingBuildMaterialKind.TORCH;
        }
        return MissingBuildMaterialKind.OTHER;
    }

    private static boolean isTorchTarget(BlockState state) {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
    }

    private static boolean isCampfireTarget(BlockState state) {
        return state.is(Blocks.CAMPFIRE);
    }

    private static boolean canCraftCharcoalBuildTarget(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockState targetState) {
        if (isTorchTarget(targetState)) {
            return PlayerNpcCraftingUtil.canCraftTorches(playerNpc.getInventory(), BUILD_CRAFT_RAW_LOG_RESERVE);
        }
        if (isCampfireTarget(targetState)) {
            return PlayerNpcCraftingUtil.canCraftWithLogConversion(
                    serverLevel,
                    playerNpc.getInventory(),
                    Items.CAMPFIRE,
                    true,
                    BUILD_CRAFT_RAW_LOG_RESERVE
            );
        }
        return false;
    }

    private static boolean canProvideCharcoalTargetWood(PlayerNpcEntity playerNpc, BlockState targetState) {
        if (isCampfireTarget(targetState)) {
            return canProvideCampfireWood(playerNpc, 1);
        }
        return countLogs(playerNpc) >= requiredLogsForTorchCharcoal(playerNpc);
    }

    private static boolean canProvideCampfireWood(PlayerNpcEntity playerNpc, int charcoalLogReserve) {
        int reservedLogs = 3 + Math.max(0, charcoalLogReserve);
        return countLogs(playerNpc) >= reservedLogs
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(playerNpc.getInventory(), 0, 3, reservedLogs);
    }

    private static int requiredLogsForTorchCharcoal(PlayerNpcEntity playerNpc) {
        if (countItems(playerNpc, stack -> stack.is(Items.STICK)) > 0
                || countItems(playerNpc, stack -> stack.is(ItemTags.PLANKS)) >= 2) {
            return 2;
        }
        return 3;
    }

    private static int countTorchFuel(PlayerNpcEntity playerNpc) {
        return countItems(playerNpc, stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL));
    }

    private static int countLogs(PlayerNpcEntity playerNpc) {
        return countItems(playerNpc, stack -> stack.is(ItemTags.LOGS));
    }

    private static int countItems(PlayerNpcEntity playerNpc, java.util.function.Predicate<ItemStack> matcher) {
        return countHeldAndInventoryItems(playerNpc, matcher);
    }

    private static boolean isWoodSupplyFamily(MaterialFamily family) {
        return family == MaterialFamily.LOGS
                || family == MaterialFamily.PLANKS
                || family == MaterialFamily.WOODEN_DOORS
                || family == MaterialFamily.WOODEN_TRAPDOORS
                || family == MaterialFamily.WOODEN_FENCES
                || family == MaterialFamily.WOODEN_FENCE_GATES
                || family == MaterialFamily.WOODEN_STAIRS
                || family == MaterialFamily.WOODEN_SLABS
                || family == MaterialFamily.WOODEN_BUTTONS
                || family == MaterialFamily.WOODEN_PRESSURE_PLATES;
    }

    private static boolean isStoneSupplyFamily(MaterialFamily family) {
        return family == MaterialFamily.COBBLESTONE_LIKE
                || family == MaterialFamily.STONE_MASONRY
                || family == MaterialFamily.STONE_STAIRS
                || family == MaterialFamily.STONE_SLABS
                || family == MaterialFamily.STONE_BUTTONS
                || family == MaterialFamily.STONE_OR_METAL_PRESSURE_PLATES;
    }

    public static boolean isSecondHalfOfSingleItemBlock(BlockState state) {
        if (state.getBlock() instanceof BedBlock
                && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return true;
        }

        if (state.getBlock() instanceof DoublePlantBlock
                && state.hasProperty(DoublePlantBlock.HALF)
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }

        return state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    public static String describeTarget(BlockState state) {
        MaterialFamily family = familyForState(state);
        return family == null ? state.getBlock().getName().getString() : family.displayName;
    }

    public static boolean needsGlassSmelting(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return missingGlassForProduction(serverLevel, playerNpc) > 0 && countGlassSmeltingInput(playerNpc) > 0;
    }

    public static boolean needsStoneSmelting(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return missingStoneForProduction(serverLevel, playerNpc) > 0
                && (hasStoneProductionFurnaceWork(serverLevel, playerNpc)
                || countStoneSmeltingInput(playerNpc) > 0
                && hasFuel(playerNpc)
                && canUseOrCreateHomeFurnace(serverLevel, playerNpc));
    }

    public static boolean hasMissingTorchBuildMaterial(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return missingTorchesForProduction(serverLevel, playerNpc) > 0;
    }

    public static boolean needsTorchCharcoalSmelting(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<MissingBuildMaterialNeed> need = findMissingBuildMaterialNeed(serverLevel, playerNpc);
        return need.isPresent()
                && need.get().kind() == MissingBuildMaterialKind.TORCH
                && !canCraftCharcoalBuildTarget(serverLevel, playerNpc, need.get().targetState())
                && countTorchFuel(playerNpc) <= 0
                && canProvideCharcoalTargetWood(playerNpc, need.get().targetState());
    }

    public static boolean hasMissingCharcoalBuildMaterial(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<MissingBuildMaterialNeed> need = findMissingBuildMaterialNeed(serverLevel, playerNpc);
        return need.isPresent()
                && need.get().kind() == MissingBuildMaterialKind.TORCH
                && (isTorchTarget(need.get().targetState()) || isCampfireTarget(need.get().targetState()))
                && !canCraftCharcoalBuildTarget(serverLevel, playerNpc, need.get().targetState());
    }

    public static int missingTorchesForProduction(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return 0;
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != home.get().width()
                || layout.get().depth() != home.get().depth()) {
            return 0;
        }

        int missing = 0;
        BlockPos origin = home.get().origin();
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.optional()
                    || block.state().isAir()
                    || isBlueprintPlaceholder(block.state())
                    || block.isSecondHalfOfSingleItemBlock()
                    || !isTorchTarget(block.state())
                    || matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())
                    || hasMaterialFor(serverLevel, playerNpc, block, origin)) {
                continue;
            }
            missing++;
        }
        return missing;
    }

    public static int missingGlassForProduction(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<GlassProductionNeed> need = findGlassProductionNeed(serverLevel, playerNpc);
        if (need.isEmpty()) {
            return 0;
        }

        return Math.max(0, need.get().requiredGlass() - countFamilyItems(playerNpc, MaterialFamily.GLASS_BLOCKS));
    }

    public static int missingStoneForProduction(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<StoneProductionNeed> need = findStoneProductionNeed(serverLevel, playerNpc);
        if (need.isEmpty()) {
            return 0;
        }

        return Math.max(0, need.get().requiredStone() - countFamilyItems(playerNpc, MaterialFamily.STONE_MASONRY));
    }

    public static boolean isGlassSmeltingInput(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.SAND) || stack.is(Items.RED_SAND));
    }

    public static boolean isStoneSmeltingInput(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    public static boolean isTorchCharcoalInput(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.LOGS);
    }

    public static boolean isCurrentBuildInputForTarget(ItemStack stack, BlockState targetState) {
        if (stack.isEmpty() || targetState.isAir() || isBlueprintPlaceholder(targetState)) {
            return false;
        }

        Item item = stack.getItem();
        if (item == targetState.getBlock().asItem()) {
            return true;
        }

        MaterialFamily targetFamily = familyForState(targetState);
        MaterialFamily stackFamily = familyForItem(item);
        if (targetFamily != null && targetFamily == stackFamily) {
            return true;
        }

        if (isWoodSupplyFamily(targetFamily)) {
            return stack.is(ItemTags.LOGS)
                    || stack.is(ItemTags.PLANKS)
                    || stack.is(Items.STICK)
                    || isWoodSupplyFamily(stackFamily);
        }

        if (isStoneSupplyFamily(targetFamily)) {
            return stack.is(Items.COBBLESTONE)
                    || stack.is(Items.COBBLED_DEEPSLATE)
                    || stack.is(Items.STONE)
                    || stack.is(Items.DEEPSLATE)
                    || isStoneSupplyFamily(stackFamily);
        }

        if (targetFamily == MaterialFamily.GLASS_BLOCKS || targetFamily == MaterialFamily.GLASS_PANES) {
            return isGlassSmeltingInput(stack)
                    || stackFamily == MaterialFamily.GLASS_BLOCKS
                    || stackFamily == MaterialFamily.GLASS_PANES;
        }

        if (targetFamily == MaterialFamily.BEDS || targetFamily == MaterialFamily.CARPETS) {
            return item instanceof BedItem
                    || stack.is(ItemTags.WOOL)
                    || stackFamily == MaterialFamily.BEDS
                    || stackFamily == MaterialFamily.CARPETS;
        }

        if (targetFamily == MaterialFamily.FLOWER_POTS || targetFamily == MaterialFamily.POTTED_FLOWERS) {
            return stack.is(Items.FLOWER_POT)
                    || isPottablePlantStack(stack)
                    || stackFamily == MaterialFamily.FLOWERS;
        }

        if (isTorchTarget(targetState)) {
            return stack.is(Items.TORCH)
                    || stack.is(Items.COAL)
                    || stack.is(Items.CHARCOAL)
                    || stack.is(Items.STICK)
                    || stack.is(ItemTags.LOGS)
                    || stack.is(ItemTags.PLANKS);
        }

        if (isCampfireTarget(targetState)) {
            return stack.is(Items.CAMPFIRE)
                    || stack.is(Items.COAL)
                    || stack.is(Items.CHARCOAL)
                    || stack.is(Items.STICK)
                    || stack.is(ItemTags.LOGS)
                    || stack.is(ItemTags.PLANKS);
        }

        return false;
    }

    private static Optional<Item> findAvailableItem(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockState targetState) {
        if (isPottedPlant(targetState)) {
            return hasFlowerPot(playerNpc) && hasPottablePlant(playerNpc) ? Optional.of(Items.FLOWER_POT) : Optional.empty();
        }

        Optional<Item> direct = findDirectItem(playerNpc, targetState);
        if (direct.isPresent()) {
            return direct;
        }
        return findCraftableItem(serverLevel, playerNpc, targetState);
    }

    private static Optional<Item> findDirectItem(PlayerNpcEntity playerNpc, BlockState targetState) {
        Item targetItem = targetState.getBlock().asItem();
        MaterialFamily family = familyForState(targetState);
        List<Item> candidates = candidateItems(family, targetItem);

        for (Item item : candidates) {
            if (InventoryUtils.hasItem(playerNpc, item)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private static Optional<Item> findCraftableItem(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockState targetState) {
        Item targetItem = targetState.getBlock().asItem();
        MaterialFamily family = familyForState(targetState);
        if (targetItem == Items.TORCH
                && PlayerNpcCraftingUtil.canCraftTorches(playerNpc.getInventory(), BUILD_CRAFT_RAW_LOG_RESERVE)) {
            return Optional.of(Items.TORCH);
        }
        if (family == MaterialFamily.GLASS_PANES && canCraftGlassPaneFromAnyGlass(playerNpc.getInventory())) {
            return Optional.of(Items.GLASS_PANE);
        }
        if (family == MaterialFamily.BEDS) {
            Item bed = PlayerNpcCraftingUtil.getCraftableBedItem(playerNpc.getInventory());
            return bed != null && PlayerNpcCraftingUtil.canCraftBed(playerNpc.getInventory(), BUILD_CRAFT_RAW_LOG_RESERVE)
                    ? Optional.of(bed)
                    : Optional.empty();
        }

        List<Item> candidates = candidateItems(family, targetItem);

        SimpleContainer inventory = playerNpc.getInventory();
        for (Item item : candidates) {
            if (PlayerNpcCraftingUtil.canCraftWithLogConversion(serverLevel, inventory, item, true, BUILD_CRAFT_RAW_LOG_RESERVE)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private static ItemStack consumeOrCraft(ServerLevel serverLevel, PlayerNpcEntity playerNpc, Item item) {
        ItemStack consumed = playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
        if (!consumed.isEmpty()) {
            return consumed;
        }

        if (item == Items.GLASS_PANE && tryCraftGlassPaneFromAnyGlass(playerNpc.getInventory())) {
            return playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
        }
        if (item == Items.TORCH
                && PlayerNpcCraftingUtil.tryCraftTorches(playerNpc.getInventory(), BUILD_CRAFT_RAW_LOG_RESERVE)) {
            return playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
        }
        if (item instanceof BedItem
                && PlayerNpcCraftingUtil.tryCraftBed(playerNpc.getInventory(), BUILD_CRAFT_RAW_LOG_RESERVE)) {
            return playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
        }

        if (!PlayerNpcCraftingUtil.tryCraftWithLogConversion(serverLevel, playerNpc.getInventory(), item, true, BUILD_CRAFT_RAW_LOG_RESERVE)) {
            return ItemStack.EMPTY;
        }
        return playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
    }

    private static List<Item> candidateItems(MaterialFamily family, Item targetItem) {
        List<Item> result = new ArrayList<>();
        if (targetItem != null && targetItem != Items.AIR) {
            result.add(targetItem);
        }
        if (family == null) {
            return result;
        }

        for (Item item : candidatesForFamily(family)) {
            if (item != targetItem && !result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private static List<Item> candidatesForFamily(MaterialFamily family) {
        return CANDIDATE_CACHE.computeIfAbsent(family, key -> {
            List<Item> curated = curatedCandidates(key);
            if (!curated.isEmpty()) {
                return curated;
            }

            List<Item> result = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (familyForItem(item) == key) {
                    result.add(item);
                }
            }
            return List.copyOf(result);
        });
    }

    private static List<Item> curatedCandidates(MaterialFamily family) {
        return switch (family) {
            case COBBLESTONE_LIKE -> COBBLESTONE_LIKE;
            case LOOSE_FILL -> LOOSE_FILL;
            case STONE_MASONRY -> STONE_MASONRY;
            case STONE_STAIRS -> STONE_STAIRS;
            case STONE_SLABS -> STONE_SLABS;
            case WOODEN_BUTTONS -> WOODEN_BUTTONS;
            case STONE_BUTTONS -> STONE_BUTTONS;
            case WOODEN_PRESSURE_PLATES -> WOODEN_PRESSURE_PLATES;
            case STONE_OR_METAL_PRESSURE_PLATES -> STONE_OR_METAL_PRESSURE_PLATES;
            case GLASS_BLOCKS -> GLASS_BLOCKS;
            case GLASS_PANES -> GLASS_PANES;
            case FLOWERS -> BUILD_PLANTS;
            case FLOWER_POTS -> List.of(Items.FLOWER_POT);
            default -> List.of();
        };
    }

    private static Optional<BlockState> stateForItem(BlockState targetState, Item item) {
        if (item == Items.TORCH && isTorchTarget(targetState)) {
            return Optional.of(targetState);
        }
        if (!(item instanceof BlockItem blockItem)) {
            return Optional.empty();
        }
        return Optional.of(copySharedProperties(targetState, blockItem.getBlock().defaultBlockState()));
    }

    private static Optional<BlockState> resolveSecondHalfState(ServerLevel serverLevel, BlockPos pos, BlockState targetState) {
        if (targetState.getBlock() instanceof DoorBlock
                && targetState.hasProperty(DoorBlock.HALF)
                && targetState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            BlockState lower = serverLevel.getBlockState(pos.below());
            if (familyForState(lower) == MaterialFamily.WOODEN_DOORS) {
                return Optional.of(copySharedProperties(targetState, lower.getBlock().defaultBlockState()));
            }
        }

        if (targetState.getBlock() instanceof DoublePlantBlock
                && targetState.hasProperty(DoublePlantBlock.HALF)
                && targetState.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            BlockState lower = serverLevel.getBlockState(pos.below());
            if (lower.getBlock() instanceof DoublePlantBlock) {
                return Optional.of(copySharedProperties(targetState, lower.getBlock().defaultBlockState()));
            }
        }

        if (targetState.getBlock() instanceof BedBlock
                && targetState.hasProperty(BedBlock.PART)
                && targetState.hasProperty(BedBlock.FACING)
                && targetState.getValue(BedBlock.PART) == BedPart.HEAD) {
            Direction facing = targetState.getValue(BedBlock.FACING);
            BlockState foot = serverLevel.getBlockState(pos.relative(facing.getOpposite()));
            if (familyForState(foot) == MaterialFamily.BEDS) {
                return Optional.of(copySharedProperties(targetState, foot.getBlock().defaultBlockState()));
            }
        }

        return Optional.empty();
    }

    private static MaterialFamily familyForState(BlockState state) {
        if (state.is(Blocks.FLOWER_POT)) {
            return MaterialFamily.FLOWER_POTS;
        }
        if (isPottedPlant(state)) {
            return MaterialFamily.POTTED_FLOWERS;
        }
        return familyForItem(state.getBlock().asItem());
    }

    private static MaterialFamily familyForItem(Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return null;
        }

        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();
        ItemStack stack = new ItemStack(item);
        if (block instanceof BedBlock) {
            return MaterialFamily.BEDS;
        }
        if (block instanceof CarpetBlock) {
            return MaterialFamily.CARPETS;
        }
        if (block instanceof ButtonBlock) {
            if (containsItem(WOODEN_BUTTONS, item) || isWoodNamedItem(item, "_button")) {
                return MaterialFamily.WOODEN_BUTTONS;
            }
            if (containsItem(STONE_BUTTONS, item)) {
                return MaterialFamily.STONE_BUTTONS;
            }
        }
        if (block instanceof PressurePlateBlock || block instanceof WeightedPressurePlateBlock) {
            if (containsItem(WOODEN_PRESSURE_PLATES, item) || isWoodNamedItem(item, "_pressure_plate")) {
                return MaterialFamily.WOODEN_PRESSURE_PLATES;
            }
            if (containsItem(STONE_OR_METAL_PRESSURE_PLATES, item)) {
                return MaterialFamily.STONE_OR_METAL_PRESSURE_PLATES;
            }
        }
        if (item == Items.FLOWER_POT) {
            return MaterialFamily.FLOWER_POTS;
        }
        if (containsItem(GLASS_BLOCKS, item)) {
            return MaterialFamily.GLASS_BLOCKS;
        }
        if (containsItem(GLASS_PANES, item)) {
            return MaterialFamily.GLASS_PANES;
        }
        if (containsItem(BUILD_PLANTS, item)) {
            return MaterialFamily.FLOWERS;
        }
        if (stack.is(ItemTags.PLANKS)) {
            return MaterialFamily.PLANKS;
        }
        if (stack.is(ItemTags.LOGS)) {
            return MaterialFamily.LOGS;
        }
        if (block instanceof DoorBlock && isWoodNamedItem(item, "_door")) {
            return MaterialFamily.WOODEN_DOORS;
        }
        if (block instanceof TrapDoorBlock && isWoodNamedItem(item, "_trapdoor")) {
            return MaterialFamily.WOODEN_TRAPDOORS;
        }
        if (block instanceof FenceGateBlock && isWoodNamedItem(item, "_fence_gate")) {
            return MaterialFamily.WOODEN_FENCE_GATES;
        }
        if (block instanceof FenceBlock && isWoodNamedItem(item, "_fence")) {
            return MaterialFamily.WOODEN_FENCES;
        }
        if (block instanceof StairBlock) {
            return isWoodNamedItem(item, "_stairs") ? MaterialFamily.WOODEN_STAIRS : stoneFamilyForItem(item, MaterialFamily.STONE_STAIRS);
        }
        if (block instanceof SlabBlock) {
            return isWoodNamedItem(item, "_slab") ? MaterialFamily.WOODEN_SLABS : stoneFamilyForItem(item, MaterialFamily.STONE_SLABS);
        }
        if (containsItem(COBBLESTONE_LIKE, item)) {
            return MaterialFamily.COBBLESTONE_LIKE;
        }
        if (containsItem(LOOSE_FILL, item)) {
            return MaterialFamily.LOOSE_FILL;
        }
        if (containsItem(STONE_MASONRY, item)) {
            return MaterialFamily.STONE_MASONRY;
        }
        return null;
    }

    private static MaterialFamily stoneFamilyForItem(Item item, MaterialFamily family) {
        List<Item> candidates = family == MaterialFamily.STONE_STAIRS ? STONE_STAIRS : STONE_SLABS;
        return containsItem(candidates, item) ? family : null;
    }

    private static boolean isWoodNamedItem(Item item, String suffix) {
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        if (key == null || !key.getPath().endsWith(suffix)) {
            return false;
        }

        String path = key.getPath().substring(0, key.getPath().length() - suffix.length());
        return WOOD_PREFIXES.contains(path);
    }

    private static boolean containsItem(List<Item> items, Item item) {
        for (Item candidate : items) {
            if (candidate == item) {
                return true;
            }
        }
        return false;
    }

    private static List<Item> buildPlantCandidates() {
        ArrayList<Item> result = new ArrayList<>(FLOWERS);
        for (Item item : DECORATIVE_PLANTS) {
            if (!result.contains(item)) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    private static Optional<PlacementMaterial> resolvePottedPlantPlacement(PlayerNpcEntity playerNpc, BlockState targetState) {
        ItemStack pot = playerNpc.consumeInventoryItem(Items.FLOWER_POT, 1).orElse(ItemStack.EMPTY);
        if (pot.isEmpty()) {
            return Optional.empty();
        }

        ItemStack plant = playerNpc.consumeInventoryItem(PlayerNpcBuildMaterialUtil::isPottablePlantStack, 1).orElse(ItemStack.EMPTY);
        if (plant.isEmpty()) {
            InventoryUtils.addItem(playerNpc, pot);
            return Optional.empty();
        }

        return Optional.of(new PlacementMaterial(targetState, pot, List.of(plant)));
    }

    private static boolean hasFlowerPot(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, Items.FLOWER_POT);
    }

    private static boolean hasPottablePlant(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, PlayerNpcBuildMaterialUtil::isPottablePlantStack);
    }

    private static boolean hasSmeltableSand(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, stack -> stack.is(Items.SAND) || stack.is(Items.RED_SAND));
    }

    private static boolean isFlowerStack(ItemStack stack) {
        return !stack.isEmpty() && containsItem(BUILD_PLANTS, stack.getItem());
    }

    private static boolean isPottablePlantStack(ItemStack stack) {
        return !stack.isEmpty() && containsItem(POTTABLE_PLANTS, stack.getItem());
    }

    private static boolean isPottedPlant(BlockState state) {
        if (!(state.getBlock() instanceof FlowerPotBlock) || state.is(Blocks.FLOWER_POT)) {
            return false;
        }

        Identifier key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && key.getPath().startsWith("potted_");
    }

    private static boolean isPartialDoubleChestMatch(BlockState existingState, BlockState targetState) {
        if (!isChestState(existingState) || !isChestState(targetState)) {
            return false;
        }
        if (existingState.getBlock() != targetState.getBlock()
                || existingState.getValue(ChestBlock.FACING) != targetState.getValue(ChestBlock.FACING)) {
            return false;
        }

        ChestType existingType = existingState.getValue(ChestBlock.TYPE);
        ChestType targetType = targetState.getValue(ChestBlock.TYPE);
        return existingType == targetType
                || existingType == ChestType.SINGLE && targetType != ChestType.SINGLE;
    }

    private static boolean isChestState(BlockState state) {
        return state != null
                && state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.hasProperty(ChestBlock.FACING);
    }

    private static Optional<GlassProductionNeed> findGlassProductionNeed(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return Optional.empty();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()) {
            return Optional.empty();
        }

        BlockPos origin = home.get().origin();
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.optional()
                    || isBlueprintPlaceholder(block.state())
                    || matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())) {
                continue;
            }

            MaterialFamily family = familyForState(block.state());
            if ((family == MaterialFamily.GLASS_BLOCKS || family == MaterialFamily.GLASS_PANES)
                    && !hasMaterialFor(serverLevel, playerNpc, block, origin)) {
                return Optional.of(new GlassProductionNeed(requiredGlassFor(family)));
            }
        }
        return Optional.empty();
    }

    private static Optional<StoneProductionNeed> findStoneProductionNeed(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return Optional.empty();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()) {
            return Optional.empty();
        }

        BlockPos origin = home.get().origin();
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.optional()
                    || isBlueprintPlaceholder(block.state())
                    || matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())) {
                continue;
            }

            MaterialFamily family = familyForState(block.state());
            if (family == MaterialFamily.STONE_MASONRY
                    && !hasMaterialFor(serverLevel, playerNpc, block, origin)) {
                return Optional.of(new StoneProductionNeed(1));
            }
        }
        return Optional.empty();
    }

    private static boolean hasStoneProductionFurnaceWork(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        boolean hasInventoryFuel = hasFuel(playerNpc);
        BlockPos temporaryFurnace = getTemporaryFurnacePos(playerNpc);
        if (temporaryFurnace != null && hasStoneProductionFurnaceWorkAt(serverLevel, temporaryFurnace, hasInventoryFuel)) {
            return true;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (hasStoneProductionFurnaceWorkAt(serverLevel, pos, hasInventoryFuel)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStoneProductionFurnaceWorkAt(ServerLevel serverLevel, BlockPos pos, boolean hasInventoryFuel) {
        if (!(serverLevel.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        ItemStack input = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        ItemStack output = furnace.getItem(2);
        return familyForItem(output.getItem()) == MaterialFamily.STONE_MASONRY
                || isStoneSmeltingInput(input) && (!fuel.isEmpty() || hasInventoryFuel);
    }

    private static BlockPos getTemporaryFurnacePos(PlayerNpcEntity playerNpc) {
        if (!playerNpc.getPersistentData().contains(FurnaceAi.TEMP_FURNACE_X)) {
            return null;
        }

        return new BlockPos(
                playerNpc.getPersistentData().getIntOr(FurnaceAi.TEMP_FURNACE_X, 0),
                playerNpc.getPersistentData().getIntOr(FurnaceAi.TEMP_FURNACE_Y, 0),
                playerNpc.getPersistentData().getIntOr(FurnaceAi.TEMP_FURNACE_Z, 0)
        );
    }

    private static int requiredGlassFor(MaterialFamily family) {
        return family == MaterialFamily.GLASS_PANES ? GLASS_PANE_CRAFT_INPUT : 1;
    }

    private static int countGlassSmeltingInput(PlayerNpcEntity playerNpc) {
        return countHeldAndInventoryItems(playerNpc, PlayerNpcBuildMaterialUtil::isGlassSmeltingInput);
    }

    private static int countStoneSmeltingInput(PlayerNpcEntity playerNpc) {
        return countHeldAndInventoryItems(playerNpc, PlayerNpcBuildMaterialUtil::isStoneSmeltingInput);
    }

    private static boolean hasFuel(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, stack -> !stack.isEmpty() && playerNpc.level().fuelValues().isFuel(stack));
    }

    private static boolean canUseOrCreateHomeFurnace(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return InventoryUtils.hasItem(playerNpc, Items.FURNACE)
                    || PlayerNpcCraftingUtil.canCraftFurnace(playerNpc.getInventory());
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.FURNACE)) {
                return true;
            }
        }
        return InventoryUtils.hasItem(playerNpc, Items.FURNACE)
                || PlayerNpcCraftingUtil.canCraftFurnace(playerNpc.getInventory());
    }

    private static int countFamilyItems(PlayerNpcEntity playerNpc, MaterialFamily family) {
        return countHeldAndInventoryItems(playerNpc, stack -> familyForItem(stack.getItem()) == family);
    }

    private static int countHeldAndInventoryItems(PlayerNpcEntity playerNpc, java.util.function.Predicate<ItemStack> matcher) {
        int count = 0;
        ItemStack mainHand = playerNpc.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            count += mainHand.getCount();
        }
        ItemStack offhand = playerNpc.getOffhandItem();
        if (!offhand.isEmpty() && matcher.test(offhand)) {
            count += offhand.getCount();
        }

        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean canCraftGlassPaneFromAnyGlass(SimpleContainer inventory) {
        return PlayerNpcCraftingUtil.countItem(inventory, stack -> familyForItem(stack.getItem()) == MaterialFamily.GLASS_BLOCKS) >= GLASS_PANE_CRAFT_INPUT;
    }

    private static boolean tryCraftGlassPaneFromAnyGlass(SimpleContainer inventory) {
        if (!PlayerNpcCraftingUtil.consumeItem(inventory, stack -> familyForItem(stack.getItem()) == MaterialFamily.GLASS_BLOCKS, GLASS_PANE_CRAFT_INPUT)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.GLASS_PANE, 16));
    }

    private static boolean sharedPropertiesMatch(BlockState targetState, BlockState existingState) {
        for (Property<?> targetProperty : targetState.getProperties()) {
            if (!shouldCompareStructuralProperty(targetState, targetProperty)) {
                continue;
            }
            Property<?> existingProperty = existingState.getBlock().getStateDefinition().getProperty(targetProperty.getName());
            if (existingProperty == null) {
                continue;
            }
            if (!propertyValueName(targetState, targetProperty).equals(propertyValueName(existingState, existingProperty))) {
                return false;
            }
        }
        return true;
    }

    private static boolean shouldCompareStructuralProperty(BlockState targetState, Property<?> property) {
        String name = property.getName();
        if (targetState.getBlock() instanceof BedBlock) {
            return "part".equals(name) || "facing".equals(name);
        }
        if (targetState.getBlock() instanceof DoorBlock) {
            return "half".equals(name) || "facing".equals(name) || "hinge".equals(name);
        }
        return true;
    }

    private static BlockState copySharedProperties(BlockState source, BlockState replacement) {
        BlockState result = replacement;
        for (Property<?> sourceProperty : source.getProperties()) {
            Property<?> replacementProperty = result.getBlock().getStateDefinition().getProperty(sourceProperty.getName());
            if (replacementProperty == null) {
                continue;
            }
            String valueName = propertyValueName(source, sourceProperty);
            result = setPropertyByName(result, replacementProperty, valueName);
        }
        return result;
    }

    private static String propertyValueName(BlockState state, Property<?> property) {
        return propertyValueNameUnchecked(state, property);
    }

    private static <T extends Comparable<T>> String propertyValueNameUnchecked(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState setPropertyByName(BlockState state, Property<T> property, String valueName) {
        Optional<T> value = property.getValue(valueName);
        return value.map(t -> state.setValue(property, t)).orElse(state);
    }

    public record PlacementMaterial(BlockState state, ItemStack consumedItem, List<ItemStack> extraConsumedItems) {
        public PlacementMaterial(BlockState state, ItemStack consumedItem) {
            this(state, consumedItem, List.of());
        }
    }

    public record MissingBuildMaterialNeed(MissingBuildMaterialKind kind, BlockState targetState, BlockPos targetPos, String description) {
    }

    public enum MissingBuildMaterialKind {
        NONE,
        LOG,
        STONE,
        SAND,
        PLANT,
        BED,
        TORCH,
        OTHER
    }

    private record GlassProductionNeed(int requiredGlass) {
    }

    private record StoneProductionNeed(int requiredStone) {
    }

    private record MissingNeedCache(
            int tick,
            BlockPos homeOrigin,
            int homeWidth,
            int homeDepth,
            String layoutId,
            int inventoryHash,
            Optional<MissingBuildMaterialNeed> need) {
        private boolean matches(int currentTick, PlayerNpcHomeUtil.HomeArea homeArea, String currentLayoutId, int currentInventoryHash) {
            int age = currentTick - this.tick;
            return age >= 0
                    && age <= MISSING_NEED_CACHE_TICKS
                    && this.sameContext(homeArea, currentLayoutId, currentInventoryHash);
        }

        private boolean sameContext(PlayerNpcHomeUtil.HomeArea homeArea, String currentLayoutId, int currentInventoryHash) {
            return this.sameBuildContext(homeArea, currentLayoutId)
                    && this.inventoryHash == currentInventoryHash;
        }

        private boolean sameBuildContext(PlayerNpcHomeUtil.HomeArea homeArea, String currentLayoutId) {
            return this.homeOrigin.equals(homeArea.origin())
                    && this.homeWidth == homeArea.width()
                    && this.homeDepth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId);
        }
    }

    private record MissingNeedSearch(
            Identifier dimension,
            BlockPos homeOrigin,
            int homeWidth,
            int homeDepth,
            String layoutId,
            int inventoryHash,
            int nextBlockIndex,
            int lastSliceTick,
            MissingBuildMaterialNeed bestNeed,
            MissingMaterialLedger materialLedger
    ) {
        private static MissingNeedSearch create(
                Identifier dimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String layoutId,
                int inventoryHash,
                PlayerNpcEntity playerNpc
        ) {
            return new MissingNeedSearch(
                    dimension,
                    homeArea.origin(),
                    homeArea.width(),
                    homeArea.depth(),
                    layoutId,
                    inventoryHash,
                    0,
                    Integer.MIN_VALUE,
                    null,
                    MissingMaterialLedger.create(playerNpc)
            );
        }

        private boolean matches(
                Identifier currentDimension,
                PlayerNpcHomeUtil.HomeArea homeArea,
                String currentLayoutId,
                int currentInventoryHash
        ) {
            return this.dimension.equals(currentDimension)
                    && this.homeOrigin.equals(homeArea.origin())
                    && this.homeWidth == homeArea.width()
                    && this.homeDepth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId)
                    && this.inventoryHash == currentInventoryHash;
        }

        private MissingNeedSearch withNextBlockIndex(int nextBlockIndex, int lastSliceTick) {
            return new MissingNeedSearch(
                    this.dimension,
                    this.homeOrigin,
                    this.homeWidth,
                    this.homeDepth,
                    this.layoutId,
                    this.inventoryHash,
                    nextBlockIndex,
                    lastSliceTick,
                    this.bestNeed,
                    this.materialLedger
            );
        }

        private MissingNeedSearch withCandidate(MissingBuildMaterialNeed candidate) {
            if (candidate == null
                    || this.bestNeed != null
                    && missingNeedPriority(this.bestNeed) <= missingNeedPriority(candidate)) {
                return this;
            }
            return new MissingNeedSearch(
                    this.dimension,
                    this.homeOrigin,
                    this.homeWidth,
                    this.homeDepth,
                    this.layoutId,
                    this.inventoryHash,
                    this.nextBlockIndex,
                    this.lastSliceTick,
                    candidate,
                    this.materialLedger
            );
        }
    }

    /**
     * Mutable inventory simulation owned by one bounded missing-material search. Each unmatched
     * blueprint placement consumes from the snapshot once, so one carried block or one craft cannot
     * falsely satisfy every later placement. The surrounding search context/inventory hash replaces
     * this ledger whenever the real inventory, home, dimension, or layout changes.
     */
    private static final class MissingMaterialLedger {
        private final SimpleContainer inventory;

        private MissingMaterialLedger(SimpleContainer inventory) {
            this.inventory = inventory;
        }

        private static MissingMaterialLedger create(PlayerNpcEntity playerNpc) {
            SimpleContainer source = playerNpc.getInventory();
            SimpleContainer copy = new SimpleContainer(source.getContainerSize() + 2);
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                copy.setItem(slot, source.getItem(slot).copy());
            }
            copy.setItem(source.getContainerSize(), playerNpc.getMainHandItem().copy());
            copy.setItem(source.getContainerSize() + 1, playerNpc.getOffhandItem().copy());
            return new MissingMaterialLedger(copy);
        }

        private boolean consumePlacement(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
            BlockState targetState = block.state();
            if (targetState.isAir() || isBlueprintPlaceholder(targetState)
                    || block.isSecondHalfOfSingleItemBlock()) {
                return true;
            }
            if (isPottedPlant(targetState)) {
                if (PlayerNpcCraftingUtil.countItem(this.inventory, stack -> stack.is(Items.FLOWER_POT)) < 1
                        || PlayerNpcCraftingUtil.countItem(this.inventory,
                        PlayerNpcBuildMaterialUtil::isPottablePlantStack) < 1) {
                    return false;
                }
                consume(Items.FLOWER_POT);
                return PlayerNpcCraftingUtil.consumeItem(this.inventory,
                        PlayerNpcBuildMaterialUtil::isPottablePlantStack, 1);
            }

            Item targetItem = targetState.getBlock().asItem();
            MaterialFamily family = familyForState(targetState);
            List<Item> candidates = candidateItems(family, targetItem);
            for (Item candidate : candidates) {
                if (consume(candidate)) {
                    return true;
                }
            }

            Item craftedItem = null;
            if (targetItem == Items.TORCH
                    && PlayerNpcCraftingUtil.tryCraftTorches(this.inventory, BUILD_CRAFT_RAW_LOG_RESERVE)) {
                craftedItem = Items.TORCH;
            } else if (family == MaterialFamily.GLASS_PANES && tryCraftGlassPaneFromAnyGlass(this.inventory)) {
                craftedItem = Items.GLASS_PANE;
            } else if (family == MaterialFamily.BEDS) {
                Item bed = PlayerNpcCraftingUtil.getCraftableBedItem(this.inventory);
                if (bed != null && PlayerNpcCraftingUtil.tryCraftBed(this.inventory, BUILD_CRAFT_RAW_LOG_RESERVE)) {
                    craftedItem = bed;
                }
            } else {
                for (Item candidate : candidates) {
                    if (PlayerNpcCraftingUtil.tryCraftWithLogConversion(
                            serverLevel,
                            this.inventory,
                            candidate,
                            true,
                            BUILD_CRAFT_RAW_LOG_RESERVE
                    )) {
                        craftedItem = candidate;
                        break;
                    }
                }
            }
            return craftedItem != null && consume(craftedItem);
        }

        private boolean consume(Item item) {
            return item != null && item != Items.AIR
                    && PlayerNpcCraftingUtil.consumeItem(this.inventory, stack -> stack.is(item), 1);
        }
    }

    private static int missingNeedPriority(MissingBuildMaterialNeed need) {
        return need == null ? Integer.MAX_VALUE : buildMaterialPhasePriority(need.targetState());
    }

    /**
     * Shared construction/material phase order.  Acquisition, visible requirements and actual
     * placement must agree on this order; otherwise a later craftable block can advertise ready
     * build work while the earlier missing-material goal is trying to leave the site.
     */
    public static int buildMaterialPhasePriority(BlockState state) {
        if (state == null) {
            return 70;
        }
        MaterialFamily family = familyForState(state);
        if (family == MaterialFamily.LOOSE_FILL) {
            return 0;
        }
        if (family == MaterialFamily.LOGS
                || family == MaterialFamily.PLANKS
                || family == MaterialFamily.WOODEN_DOORS
                || family == MaterialFamily.WOODEN_TRAPDOORS
                || family == MaterialFamily.WOODEN_STAIRS
                || family == MaterialFamily.WOODEN_SLABS) {
            return 10;
        }
        if (isStoneSupplyFamily(family)) {
            return 20;
        }
        if (family == MaterialFamily.BEDS
                || state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.FURNACE)
                || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)) {
            return 30;
        }
        if (family == MaterialFamily.FLOWERS
                || family == MaterialFamily.FLOWER_POTS
                || family == MaterialFamily.POTTED_FLOWERS) {
            return 40;
        }
        if (family == MaterialFamily.WOODEN_FENCES
                || family == MaterialFamily.WOODEN_FENCE_GATES
                || family == MaterialFamily.WOODEN_BUTTONS
                || family == MaterialFamily.WOODEN_PRESSURE_PLATES
                || isTorchTarget(state)
                || isCampfireTarget(state)) {
            return 50;
        }
        if (family == MaterialFamily.GLASS_BLOCKS || family == MaterialFamily.GLASS_PANES) {
            return 60;
        }
        return 35;
    }

    public static int buildMaterialPhasePriority(Item item) {
        if (item instanceof BlockItem blockItem) {
            return buildMaterialPhasePriority(blockItem.getBlock().defaultBlockState());
        }
        return 35;
    }

    private enum MaterialFamily {
        PLANKS("any planks"),
        LOGS("any log"),
        WOODEN_DOORS("any wooden door"),
        WOODEN_TRAPDOORS("any wooden trapdoor"),
        WOODEN_FENCES("any wooden fence"),
        WOODEN_FENCE_GATES("any wooden fence gate"),
        WOODEN_STAIRS("any wooden stairs"),
        WOODEN_SLABS("any wooden slab"),
        BEDS("any bed"),
        CARPETS("any carpet"),
        COBBLESTONE_LIKE("cobblestone-like block"),
        LOOSE_FILL("dirt/sand/gravel"),
        STONE_MASONRY("stone masonry block"),
        STONE_STAIRS("any stone stairs"),
        STONE_SLABS("any stone slab"),
        WOODEN_BUTTONS("any wooden button"),
        STONE_BUTTONS("any stone button"),
        WOODEN_PRESSURE_PLATES("any wooden pressure plate"),
        STONE_OR_METAL_PRESSURE_PLATES("any stone or metal pressure plate"),
        GLASS_BLOCKS("any glass"),
        GLASS_PANES("any glass pane"),
        FLOWERS("any plant"),
        FLOWER_POTS("flower pot"),
        POTTED_FLOWERS("potted flower");

        private final String displayName;

        MaterialFamily(String displayName) {
            this.displayName = displayName;
        }
    }
}
