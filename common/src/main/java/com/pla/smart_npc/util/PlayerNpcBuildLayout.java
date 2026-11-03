package com.pla.smart_npc.util;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlayerNpcBuildLayout {
    private final String id;
    private final int width;
    private final int height;
    private final int depth;
    private final String name;
    private final List<RelativeBlock> blocks;
    private final Set<Long> footprint;

    public PlayerNpcBuildLayout(String id, int width, int height, int depth, String name, List<RelativeBlock> blocks) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.name = name;
        this.blocks = List.copyOf(blocks);
        this.footprint = buildFootprint(this.blocks);
    }

    public String id() {
        return this.id;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int depth() {
        return this.depth;
    }

    public String name() {
        return this.name;
    }

    public String shape() {
        return "exact";
    }

    public List<RelativeBlock> blocks() {
        return this.blocks;
    }

    public int requiredBlocks() {
        int count = 0;
        for (RelativeBlock block : this.blocks) {
            if (!block.optional()
                    && !block.state().isAir()
                    && !PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(block.state())) {
                count++;
            }
        }
        return count;
    }

    public boolean isInFootprint(int x, int z) {
        return this.footprint.contains(packFootprint(x, z));
    }

    public Set<Long> footprint() {
        return this.footprint;
    }

    private static Set<Long> buildFootprint(List<RelativeBlock> blocks) {
        Set<Long> result = new HashSet<>();
        for (RelativeBlock block : blocks) {
            if (!block.state().isAir() && (block.y() == 0 || block.marker().contains("foundation") || block.marker().contains("floor"))) {
                result.add(packFootprint(block.x(), block.z()));
            }
        }
        return Set.copyOf(result);
    }

    private static long packFootprint(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public record RelativeBlock(int x, int y, int z, BlockState state, boolean optional, String marker, CompoundTag blockEntityTag) {
        public RelativeBlock(int x, int y, int z, BlockState state, boolean optional, String marker) {
            this(x, y, z, state, optional, marker, null);
        }

        public RelativeBlock {
            marker = marker == null ? "" : marker;
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }

        public BlockPos toWorld(BlockPos origin) {
            return origin.offset(this.x, this.y, this.z);
        }

        public String role() {
            return this.marker;
        }

        public ItemStack requiredItem() {
            if (this.state.isAir()
                    || this.state.is(Blocks.AIR)
                    || PlayerNpcBuildMaterialUtil.isBlueprintPlaceholder(this.state)
                    || this.isSecondHalfOfSingleItemBlock()) {
                return ItemStack.EMPTY;
            }

            return new ItemStack(this.state.getBlock());
        }

        public boolean isSecondHalfOfSingleItemBlock() {
            return PlayerNpcBuildMaterialUtil.isSecondHalfOfSingleItemBlock(this.state);
        }
    }
}
