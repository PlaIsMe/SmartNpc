package com.pla.smart_npc.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PlayerNpcBuildLayoutLoader extends SimpleJsonResourceReloadListener {
    public static final String FORMAT = "smart_npc:structure_v1";
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogManager.getLogger();
    private static List<PlayerNpcBuildLayout> layouts = List.of();

    public PlayerNpcBuildLayoutLoader() {
        super(GSON, "builds");
    }

    @Override
    protected void apply(
            java.util.Map<ResourceLocation, JsonElement> map,
            ResourceManager resourceManager,
            ProfilerFiller profilerFiller
    ) {
        List<PlayerNpcBuildLayout> parsedLayouts = new ArrayList<>();
        for (java.util.Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
            try {
                parseLayout(entry.getKey(), GsonHelper.convertToJsonObject(entry.getValue(), "build layout"))
                        .ifPresent(parsedLayouts::add);
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping invalid Player NPC build layout {}: {}", entry.getKey(), exception.getMessage());
            }
        }
        loadBlueprintLayouts(resourceManager, parsedLayouts);

        layouts = parsedLayouts.stream()
                .filter(layout -> !layout.blocks().isEmpty() && !layout.footprint().isEmpty())
                .sorted(Comparator.comparing(PlayerNpcBuildLayout::id))
                .collect(Collectors.toUnmodifiableList());
        LOGGER.info("Loaded {} Player NPC build layouts", layouts.size());
    }

    public static List<PlayerNpcBuildLayout> getLayouts() {
        return layouts;
    }

    public static Optional<PlayerNpcBuildLayout> getLayout(String id) {
        return layouts.stream()
                .filter(layout -> layout.id().equals(id))
                .findFirst();
    }

    public static int countMissingRequired(net.minecraft.server.level.ServerLevel serverLevel, PlayerNpcBuildLayout layout, net.minecraft.core.BlockPos origin) {
        int missing = 0;
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            if (!block.optional()
                    && !PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())
                    && !PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())) {
                missing++;
            }
        }
        return missing;
    }

    public static Optional<PlayerNpcBuildLayout> getRandomLayout(RandomSource randomSource, int maxBlocks) {
        List<PlayerNpcBuildLayout> candidates = layouts.stream()
                .filter(layout -> layout.requiredBlocks() <= maxBlocks)
                .toList();
        if (candidates.isEmpty()) {
            candidates = layouts;
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(candidates.get(randomSource.nextInt(candidates.size())));
    }

    private static Optional<PlayerNpcBuildLayout> parseLayout(ResourceLocation id, JsonObject root) {
        String format = GsonHelper.getAsString(root, "format", FORMAT);
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("expected format " + FORMAT + ", got " + format);
        }

        JsonObject size = GsonHelper.getAsJsonObject(root, "size");
        int width = GsonHelper.getAsInt(size, "width");
        int height = GsonHelper.getAsInt(size, "height");
        int depth = GsonHelper.getAsInt(size, "depth");
        if (width < 1 || height < 1 || depth < 1) {
            return Optional.empty();
        }

        String name = GsonHelper.getAsString(root, "name", id.toString());
        List<PlayerNpcBuildLayout.RelativeBlock> blocks = new ArrayList<>();
        JsonArray blocksArray = GsonHelper.getAsJsonArray(root, "blocks");
        for (JsonElement blockElement : blocksArray) {
            JsonObject block = GsonHelper.convertToJsonObject(blockElement, "block");
            JsonArray pos = GsonHelper.getAsJsonArray(block, "pos");
            if (pos.size() != 3) {
                continue;
            }

            int x = pos.get(0).getAsInt();
            int y = pos.get(1).getAsInt();
            int z = pos.get(2).getAsInt();
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
                continue;
            }

            BlockState state = parseBlockState(block);
            boolean optional = GsonHelper.getAsBoolean(block, "optional", false);
            String marker = GsonHelper.getAsString(block, "marker", GsonHelper.getAsString(block, "role", ""));
            blocks.add(new PlayerNpcBuildLayout.RelativeBlock(x, y, z, state, optional, marker));
        }

        if (blocks.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new PlayerNpcBuildLayout(id.toString(), width, height, depth, name, blocks));
    }

    private static void loadBlueprintLayouts(ResourceManager resourceManager, List<PlayerNpcBuildLayout> parsedLayouts) {
        java.util.Map<ResourceLocation, Resource> blueprintResources = resourceManager.listResources(
                "builds",
                resourceLocation -> resourceLocation.getPath().endsWith(".blueprint")
        );
        for (java.util.Map.Entry<ResourceLocation, Resource> entry : blueprintResources.entrySet()) {
            ResourceLocation layoutId = normalizeBlueprintId(entry.getKey());
            try (InputStream inputStream = entry.getValue().open()) {
                PlayerNpcBlueprintLayoutReader.read(layoutId, inputStream).ifPresent(parsedLayouts::add);
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Skipping invalid Player NPC blueprint layout {}: {}", entry.getKey(), exception.getMessage());
            }
        }
    }

    private static ResourceLocation normalizeBlueprintId(ResourceLocation resourceLocation) {
        String path = resourceLocation.getPath();
        if (path.startsWith("builds/")) {
            path = path.substring("builds/".length());
        }
        if (path.endsWith(".blueprint")) {
            path = path.substring(0, path.length() - ".blueprint".length());
        }
        return ResourceLocation.fromNamespaceAndPath(resourceLocation.getNamespace(), path);
    }

    private static BlockState parseBlockState(JsonObject block) {
        String blockName = GsonHelper.getAsString(block, "state", GsonHelper.getAsString(block, "block", ""));
        if (blockName.isBlank()) {
            throw new IllegalArgumentException("missing state");
        }
        int propertiesStart = blockName.indexOf('[');
        if (propertiesStart >= 0) {
            blockName = blockName.substring(0, propertiesStart);
        }

        ResourceLocation blockId = ResourceLocation.tryParse(blockName);
        if (blockId == null) {
            throw new IllegalArgumentException("invalid block id " + blockName);
        }

        Block parsedBlock = BuiltInRegistries.BLOCK.get(blockId);
        if (parsedBlock == null) {
            throw new IllegalArgumentException("unknown block " + blockName);
        }

        BlockState state = parsedBlock.defaultBlockState();
        if (block.has("properties")) {
            JsonObject properties = GsonHelper.getAsJsonObject(block, "properties");
            for (java.util.Map.Entry<String, JsonElement> entry : properties.entrySet()) {
                state = setProperty(state, entry.getKey(), entry.getValue().getAsString());
            }
        }
        return state;
    }

    private static BlockState setProperty(BlockState state, String propertyName, String value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return setPropertyValue(state, property, value);
            }
        }
        throw new IllegalArgumentException("unknown property " + propertyName + " for " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("invalid value " + value + " for property " + property.getName());
        }
        return state.setValue(property, parsed.get());
    }
}
