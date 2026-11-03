package com.pla.smart_npc.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the core Structurize v1 .blueprint NBT shape into PlayerNpc's build layout model.
 *
 * Format details are based on Structurize's GPL-licensed BlueprintUtil:
 * com.ldtteam.structurize.blueprints.v1.BlueprintUtil.
 */
public final class PlayerNpcBlueprintLayoutReader {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final byte SUPPORTED_VERSION = 1;

    private PlayerNpcBlueprintLayoutReader() {
    }

    public static Optional<PlayerNpcBuildLayout> read(Identifier id, InputStream inputStream) throws IOException {
        CompoundTag tag = NbtIo.readCompressed(inputStream, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        if (tag == null) {
            return Optional.empty();
        }

        byte version = tag.getByteOr("version", (byte) 0);
        if (version != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("unsupported Structurize blueprint version " + version);
        }

        int width = tag.getShortOr("size_x", (short) 0);
        int height = tag.getShortOr("size_y", (short) 0);
        int depth = tag.getShortOr("size_z", (short) 0);
        if (width < 1 || height < 1 || depth < 1) {
            return Optional.empty();
        }

        warnForMissingRequiredMods(id, tag.getListOrEmpty("required_mods"));

        List<BlockState> palette = readPalette(tag.getListOrEmpty("palette"));
        if (palette.isEmpty()) {
            return Optional.empty();
        }

        short[] blockIndexes = unpackBlockIndexes(tag.getIntArray("blocks").orElseGet(() -> new int[0]), width, height, depth);
        Map<Long, CompoundTag> blockEntities = readBlockEntities(tag.getListOrEmpty("tile_entities"));
        List<PlayerNpcBuildLayout.RelativeBlock> blocks = new ArrayList<>(width * height * depth);

        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    BlockState state = stateFromPalette(palette, blockIndexes[index++]);
                    CompoundTag blockEntityTag = blockEntities.get(pack(x, y, z));
                    blocks.add(new PlayerNpcBuildLayout.RelativeBlock(
                            x,
                            y,
                            z,
                            state,
                            false,
                            markerFor(x, y, z, state),
                            blockEntityTag
                    ));
                }
            }
        }

        String name = tag.contains("name") ? tag.getStringOr("name", "") : id.toString();
        return Optional.of(new PlayerNpcBuildLayout(id.toString(), width, height, depth, name, blocks));
    }

    private static List<BlockState> readPalette(ListTag paletteTag) {
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            try {
                palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteTag.getCompoundOrEmpty(i)));
            } catch (RuntimeException exception) {
                LOGGER.warn("Blueprint reader replaced invalid palette entry {} with air: {}", i, exception.getMessage());
                palette.add(Blocks.AIR.defaultBlockState());
            }
        }
        return palette;
    }

    private static short[] unpackBlockIndexes(int[] packed, int width, int height, int depth) {
        int expectedBlocks = width * height * depth;
        short[] oneDimensional = new short[packed.length * 2];
        for (int i = 0; i < packed.length; i++) {
            oneDimensional[i * 2] = (short) (packed[i] >> 16);
            oneDimensional[(i * 2) + 1] = (short) packed[i];
        }

        if (oneDimensional.length < expectedBlocks) {
            throw new IllegalArgumentException("blueprint block data is shorter than declared size");
        }

        short[] result = new short[expectedBlocks];
        System.arraycopy(oneDimensional, 0, result, 0, expectedBlocks);
        return result;
    }

    private static Map<Long, CompoundTag> readBlockEntities(ListTag tileEntitiesTag) {
        Map<Long, CompoundTag> result = new HashMap<>();
        for (int i = 0; i < tileEntitiesTag.size(); i++) {
            CompoundTag tag = tileEntitiesTag.getCompoundOrEmpty(i);
            int x = tag.getIntOr("x", 0);
            int y = tag.getIntOr("y", 0);
            int z = tag.getIntOr("z", 0);
            result.put(pack(x, y, z), tag.copy());
        }
        return result;
    }

    private static BlockState stateFromPalette(List<BlockState> palette, short paletteIndex) {
        int index = paletteIndex;
        if (index < 0 || index >= palette.size()) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = palette.get(index);
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    private static void warnForMissingRequiredMods(Identifier id, ListTag requiredMods) {
        for (int i = 0; i < requiredMods.size(); i++) {
            String modId = requiredMods.getStringOr(i, "");
            if (!modId.isBlank() && !"minecraft".equals(modId) && !ModList.get().isLoaded(modId)) {
                LOGGER.warn("PlayerNpc blueprint {} requires missing mod {}; those blocks may load as air", id, modId);
            }
        }
    }

    private static String markerFor(int x, int y, int z, BlockState state) {
        if (state.isAir()) {
            return "air";
        }
        if (y == 0) {
            return "foundation";
        }
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)) {
            return "storage";
        }
        if (state.is(Blocks.CRAFTING_TABLE)) {
            return "crafting_table";
        }
        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)) {
            return "furnace";
        }
        if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            return "bed";
        }
        return "blueprint_" + x + "_" + y + "_" + z;
    }

    private static long pack(int x, int y, int z) {
        long result = x & 0xfffffL;
        result |= (long) (y & 0xfffff) << 20;
        result |= (long) (z & 0xfffff) << 40;
        return result;
    }
}
