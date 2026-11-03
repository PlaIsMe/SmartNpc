package com.pla.smart_npc.util;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

public final class PlayerNpcBuildStatusUtil {
    private static final int MAX_REQUIREMENT_LINES = 64;
    private static final int TEXT_CACHE_TICKS = 20;
    private static final String REQUIREMENTS_PAYLOAD_VERSION = "#smart_npc_requirements_v1";
    private static final Map<PlayerNpcEntity, TextCache> DESCRIPTION_CACHE = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, TextCache> REQUIREMENTS_CACHE = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, BooleanCache> CURRENT_MATERIAL_NEED_CACHE = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, KeepForBuildCache> KEEP_FOR_BUILD_CACHE = new WeakHashMap<>();

    private PlayerNpcBuildStatusUtil() {
    }

    public static String describe(PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc);
        if (home.isEmpty() || layoutId.isEmpty()) {
            return playerNpc.hasInterest(PlayerNpcInterest.BUILDING) ? "No build selected" : "No building interest";
        }
        TextCache cache = DESCRIPTION_CACHE.get(playerNpc);
        if (cache != null && cache.matches(playerNpc.tickCount, home.get(), layoutId.get())) {
            return cache.text();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcBuildLayoutLoader.getLayout(layoutId.get());
        if (layout.isEmpty()) {
            return cacheDescription(playerNpc, home.get(), layoutId.get(), "Missing layout " + layoutId.get());
        }
        if (playerNpc.level() instanceof ServerLevel serverLevel
                && !isLayoutLoaded(serverLevel, home.get(), layout.get())) {
            return cacheDescription(
                    playerNpc,
                    home.get(),
                    layoutId.get(),
                    "Build area unavailable (home chunks unloaded)"
            );
        }

        if (playerNpc.level() instanceof ServerLevel serverLevel) {
            int missing = PlayerNpcBuildLayoutLoader.countMissingRequired(serverLevel, layout.get(), home.get().origin());
            if (missing <= 0) {
                return cacheDescription(playerNpc, home.get(), layoutId.get(), "Finished " + layout.get().name());
            }
            return cacheDescription(playerNpc, home.get(), layoutId.get(), missing + " required blocks missing");
        }

        return cacheDescription(playerNpc, home.get(), layoutId.get(), layout.get().name());
    }

    public static String describeRequirements(PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc);
        if (home.isEmpty() || layoutId.isEmpty()) {
            return playerNpc.hasInterest(PlayerNpcInterest.BUILDING) ? "No build selected" : "No building interest";
        }
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return "";
        }
        TextCache cache = REQUIREMENTS_CACHE.get(playerNpc);
        if (cache != null && cache.matches(playerNpc.tickCount, home.get(), layoutId.get())) {
            return cache.text();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcBuildLayoutLoader.getLayout(layoutId.get());
        if (layout.isEmpty()) {
            return cacheRequirements(playerNpc, home.get(), layoutId.get(), "Missing layout " + layoutId.get());
        }
        if (!isLayoutLoaded(serverLevel, home.get(), layout.get())) {
            return cacheRequirements(
                    playerNpc,
                    home.get(),
                    layoutId.get(),
                    "Build area unavailable (home chunks unloaded)"
            );
        }

        Map<Item, RequirementLine> requirements = collectRequirements(serverLevel, playerNpc, home.get(), layout.get());
        if (requirements.isEmpty()) {
            return cacheRequirements(playerNpc, home.get(), layoutId.get(), "No required materials");
        }

        List<RequirementLine> lines = new ArrayList<>(requirements.values());
        for (RequirementLine line : lines) {
            line.carried = countHeldAndInventory(playerNpc, line.item);
        }
        lines.sort(Comparator
                .comparingInt((RequirementLine line) ->
                        PlayerNpcBuildMaterialUtil.buildMaterialPhasePriority(line.item))
                .thenComparing(Comparator.comparingInt(RequirementLine::missing).reversed())
                .thenComparing(Comparator.comparingInt(RequirementLine::remaining).reversed())
                .thenComparing(line -> line.name));

        StringBuilder builder = new StringBuilder(REQUIREMENTS_PAYLOAD_VERSION)
                .append('\n')
                .append("layout\t")
                .append(layout.get().name())
                .append('\n');
        int rendered = 0;
        for (RequirementLine line : lines) {
            if (rendered >= MAX_REQUIREMENT_LINES) {
                break;
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(line.item);
            if (itemId == null) {
                continue;
            }
            builder.append("item\t")
                    .append(itemId)
                    .append('\t')
                    .append(line.required)
                    .append('\t')
                    .append(line.placed)
                    .append('\t')
                    .append(line.carried)
                    .append('\t')
                    .append(line.missing())
                    .append('\n');
            rendered++;
        }

        if (lines.size() > rendered) {
            builder.append("more\t").append(lines.size() - rendered);
        }
        return cacheRequirements(playerNpc, home.get(), layoutId.get(), builder.toString().stripTrailing());
    }

    public static boolean shouldKeepForCurrentBuild(ServerLevel serverLevel, PlayerNpcEntity playerNpc, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc);
        Optional<PlayerNpcBuildLayout> layout = currentLayout(playerNpc, home);
        if (home.isEmpty() || layout.isEmpty()) {
            return false;
        }

        KeepForBuildCache cache = KEEP_FOR_BUILD_CACHE.get(playerNpc);
        if (cache == null || !cache.matches(playerNpc.tickCount, home.get(), layoutId.orElse(""))) {
            cache = new KeepForBuildCache(playerNpc.tickCount, home.get().origin(), home.get().width(), home.get().depth(), layoutId.orElse(""));
            KEEP_FOR_BUILD_CACHE.put(playerNpc, cache);
        }
        Boolean cachedResult = cache.results.get(stack.getItem());
        if (cachedResult != null) {
            return cachedResult;
        }

        BlockPos origin = home.get().origin();
        boolean keep = false;
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (!isActiveRequirement(serverLevel, origin, block)) {
                continue;
            }
            if (PlayerNpcBuildMaterialUtil.isCurrentBuildInputForTarget(stack, block.state())) {
                keep = true;
                break;
            }
        }
        cache.results.put(stack.getItem(), keep);
        return keep;
    }

    public static boolean needsCurrentBuildMaterial(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc);
        Optional<PlayerNpcBuildLayout> layout = currentLayout(playerNpc, home);
        if (home.isEmpty() || layout.isEmpty()) {
            return false;
        }

        BooleanCache cache = CURRENT_MATERIAL_NEED_CACHE.get(playerNpc);
        if (cache != null && cache.matches(playerNpc.tickCount, home.get(), layoutId.orElse(""))) {
            return cache.value();
        }

        Map<Item, RequirementLine> requirements = collectRequirements(serverLevel, playerNpc, home.get(), layout.get());
        boolean needed = false;
        for (RequirementLine line : requirements.values()) {
            line.carried = countHeldAndInventory(playerNpc, line.item);
            if (line.missing() > 0) {
                needed = true;
                break;
            }
        }
        CURRENT_MATERIAL_NEED_CACHE.put(playerNpc, new BooleanCache(
                playerNpc.tickCount,
                home.get().origin(),
                home.get().width(),
                home.get().depth(),
                layoutId.orElse(""),
                needed
        ));
        return needed;
    }

    private static Optional<PlayerNpcBuildLayout> currentLayout(PlayerNpcEntity playerNpc, Optional<PlayerNpcHomeUtil.HomeArea> home) {
        if (home.isEmpty()) {
            return Optional.empty();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != home.get().width()
                || layout.get().depth() != home.get().depth()) {
            return Optional.empty();
        }
        return layout;
    }

    private static boolean isActiveRequirement(ServerLevel serverLevel, BlockPos origin, PlayerNpcBuildLayout.RelativeBlock block) {
        return !block.optional()
                && !block.state().isAir()
                && !PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())
                && !block.isSecondHalfOfSingleItemBlock()
                && !PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(block.toWorld(origin)), block.state());
    }

    private static Map<Item, RequirementLine> collectRequirements(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            PlayerNpcHomeUtil.HomeArea homeArea,
            PlayerNpcBuildLayout layout
    ) {
        Map<Item, RequirementLine> requirements = new LinkedHashMap<>();
        Map<BlockState, ItemStack> previewItems = new HashMap<>();
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            if (block.optional()
                    || block.state().isAir()
                    || PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())
                    || block.isSecondHalfOfSingleItemBlock()) {
                continue;
            }

            ItemStack preview = previewItems.computeIfAbsent(
                    block.state(),
                    ignored -> PlayerNpcBuildMaterialUtil.previewItem(serverLevel, playerNpc, block)
            );
            if (preview.isEmpty()) {
                preview = block.requiredItem();
            }
            if (preview.isEmpty()) {
                continue;
            }

            RequirementLine line = requirements.computeIfAbsent(preview.getItem(), RequirementLine::new);
            line.required++;
            if (PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(block.toWorld(homeArea.origin())), block.state())) {
                line.placed++;
            }
        }
        return requirements;
    }

    /** Inspector diagnostics must never synchronously load an NPC's remote home chunks. */
    private static boolean isLayoutLoaded(
            ServerLevel serverLevel,
            PlayerNpcHomeUtil.HomeArea homeArea,
            PlayerNpcBuildLayout layout
    ) {
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            if (!serverLevel.hasChunkAt(block.toWorld(homeArea.origin()))) {
                return false;
            }
        }
        return true;
    }

    private static int countHeldAndInventory(PlayerNpcEntity playerNpc, Item item) {
        int count = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = playerNpc.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }

        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static final class RequirementLine {
        private final Item item;
        private final String name;
        private int required;
        private int placed;
        private int carried;

        private RequirementLine(Item item) {
            this.item = item;
            this.name = displayName(item);
        }

        private int remaining() {
            return Math.max(0, this.required - this.placed);
        }

        private int missing() {
            return Math.max(0, this.remaining() - this.carried);
        }
    }

    private static String displayName(Item item) {
        ItemStack stack = new ItemStack(item);
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }

        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "unknown" : key.toString();
    }

    private static String cacheDescription(PlayerNpcEntity playerNpc, PlayerNpcHomeUtil.HomeArea homeArea, String layoutId, String text) {
        DESCRIPTION_CACHE.put(playerNpc, new TextCache(playerNpc.tickCount, homeArea.origin(), homeArea.width(), homeArea.depth(), layoutId, text));
        return text;
    }

    private static String cacheRequirements(PlayerNpcEntity playerNpc, PlayerNpcHomeUtil.HomeArea homeArea, String layoutId, String text) {
        REQUIREMENTS_CACHE.put(playerNpc, new TextCache(playerNpc.tickCount, homeArea.origin(), homeArea.width(), homeArea.depth(), layoutId, text));
        return text;
    }

    private record TextCache(int tick, BlockPos homeOrigin, int homeWidth, int homeDepth, String layoutId, String text) {
        private boolean matches(int currentTick, PlayerNpcHomeUtil.HomeArea homeArea, String currentLayoutId) {
            return currentTick - this.tick <= TEXT_CACHE_TICKS
                    && this.homeOrigin.equals(homeArea.origin())
                    && this.homeWidth == homeArea.width()
                    && this.homeDepth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId);
        }
    }

    private record BooleanCache(int tick, BlockPos homeOrigin, int homeWidth, int homeDepth, String layoutId, boolean value) {
        private boolean matches(int currentTick, PlayerNpcHomeUtil.HomeArea homeArea, String currentLayoutId) {
            return currentTick - this.tick <= TEXT_CACHE_TICKS
                    && this.homeOrigin.equals(homeArea.origin())
                    && this.homeWidth == homeArea.width()
                    && this.homeDepth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId);
        }
    }

    private static final class KeepForBuildCache {
        private final int tick;
        private final BlockPos homeOrigin;
        private final int homeWidth;
        private final int homeDepth;
        private final String layoutId;
        private final Map<Item, Boolean> results = new HashMap<>();

        private KeepForBuildCache(int tick, BlockPos homeOrigin, int homeWidth, int homeDepth, String layoutId) {
            this.tick = tick;
            this.homeOrigin = homeOrigin;
            this.homeWidth = homeWidth;
            this.homeDepth = homeDepth;
            this.layoutId = layoutId;
        }

        private boolean matches(int currentTick, PlayerNpcHomeUtil.HomeArea homeArea, String currentLayoutId) {
            return currentTick - this.tick <= TEXT_CACHE_TICKS
                    && this.homeOrigin.equals(homeArea.origin())
                    && this.homeWidth == homeArea.width()
                    && this.homeDepth == homeArea.depth()
                    && this.layoutId.equals(currentLayoutId);
        }
    }
}
