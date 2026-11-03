package com.pla.smart_npc.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

public final class StoneAi {
    // This is one admitted time slice, not a complete ore survey. Keep the synchronous slice small;
    // a failed nearest-first pass is retried after the goal throttle or from a changed search center.
    private static final int MAX_CLUSTER_BLOCKS = 8;
    private static final int SEARCH_VERTICAL_DOWN = 6;
    private static final int SEARCH_VERTICAL_UP = 6;
    private static final int MAX_SEARCH_CLUSTERS = 2;
    private static final int MAX_SEARCH_POSITIONS = 16;

    private StoneAi() {
    }

    public static List<StoneCluster> findNearby(
            ServerLevel serverLevel,
            BlockPos center,
            int radius,
            Predicate<BlockPos> allowedStone
    ) {
        Set<BlockPos> visited = new HashSet<>();
        List<StoneCluster> clusters = new ArrayList<>();

        int inspected = 0;
        for (int ring = 0; ring <= radius && inspected < MAX_SEARCH_POSITIONS && clusters.size() < MAX_SEARCH_CLUSTERS; ring++) {
            for (int dx = -ring; dx <= ring && inspected < MAX_SEARCH_POSITIONS && clusters.size() < MAX_SEARCH_CLUSTERS; dx++) {
                for (int dz = -ring; dz <= ring && inspected < MAX_SEARCH_POSITIONS && clusters.size() < MAX_SEARCH_CLUSTERS; dz++) {
                    if (ring > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos column = center.offset(dx, 0, dz);
                    if (!serverLevel.hasChunkAt(column)) {
                        continue;
                    }
                    for (int dy = -SEARCH_VERTICAL_DOWN; dy <= SEARCH_VERTICAL_UP && inspected < MAX_SEARCH_POSITIONS; dy++) {
                        inspected++;
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (visited.contains(pos) || !canUseStone(serverLevel, pos, allowedStone)) {
                            continue;
                        }

                        StoneCluster cluster = scan(serverLevel, pos, visited, allowedStone);
                        if (!cluster.stones().isEmpty()) {
                            clusters.add(cluster);
                            if (clusters.size() >= MAX_SEARCH_CLUSTERS) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        clusters.sort(Comparator.comparingDouble(cluster -> cluster.nearestTo(center).distSqr(center)));
        return clusters;
    }

    public static boolean isStone(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private static StoneCluster scan(
            ServerLevel serverLevel,
            BlockPos start,
            Set<BlockPos> globalVisited,
            Predicate<BlockPos> allowedStone
    ) {
        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> localVisited = new HashSet<>();
        List<BlockPos> stones = new ArrayList<>();
        open.add(start.immutable());

        while (!open.isEmpty() && stones.size() < MAX_CLUSTER_BLOCKS) {
            BlockPos pos = open.poll();
            if (!localVisited.add(pos) || !canUseStone(serverLevel, pos, allowedStone)) {
                continue;
            }

            stones.add(pos);
            globalVisited.add(pos);
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (!localVisited.contains(next) && canUseStone(serverLevel, next, allowedStone)) {
                    open.add(next.immutable());
                }
            }
        }

        return new StoneCluster(stones);
    }

    private static boolean canUseStone(ServerLevel serverLevel, BlockPos pos, Predicate<BlockPos> allowedStone) {
        return serverLevel.hasChunkAt(pos)
                && isStone(serverLevel.getBlockState(pos))
                && (allowedStone == null || allowedStone.test(pos));
    }

    public static final class StoneCluster {
        private final List<BlockPos> stones;

        private StoneCluster(List<BlockPos> stones) {
            this.stones = List.copyOf(stones);
        }

        public List<BlockPos> stones() {
            return this.stones;
        }

        public BlockPos nearestTo(BlockPos origin) {
            return this.stones.stream()
                    .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)))
                    .orElse(origin)
                    .immutable();
        }

        public List<BlockPos> stonesNearestFirst(BlockPos origin) {
            List<BlockPos> sorted = new ArrayList<>(this.stones);
            sorted.sort(Comparator
                    .comparingDouble((BlockPos pos) -> pos.distSqr(origin))
                    .thenComparingInt(BlockPos::getY));
            return sorted;
        }
    }
}
