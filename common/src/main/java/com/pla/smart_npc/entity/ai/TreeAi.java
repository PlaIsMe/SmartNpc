package com.pla.smart_npc.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import com.pla.smart_npc.util.PlayerNpcAdaptiveSearchScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

public final class TreeAi {
    private static final int MAX_LOGS = 96;
    private static final int MIN_LEAVES = 3;
    private static final int LEAF_RADIUS = 4;
    // Exploration advances the search center after a miss, so a smaller nearest-first slice makes
    // steady progress without letting several gatherers scan thousands of blocks in one tick.
    // A miss is part of goal eligibility and runs synchronously on the server thread. The old
    // 1,536-read pass repeatedly measured at 97-192 ms with only four idle NPCs; ExploreAround's
    // log-priority predicate paid the same pass before choosing a destination. Keep one pass
    // deliberately small and let exploration move the observation center after a miss.
    private static final int MAX_SEARCH_BLOCK_READS = 50;
    private static final int MAX_LEAF_BLOCK_READS = 32;
    private static final int LOCAL_FOOTPRINT_RADIUS = 2;
    private static final Map<Integer, List<ColumnOffset>> SEARCH_COLUMNS_BY_RADIUS = new HashMap<>();

    private TreeAi() {
    }

    public static Optional<Tree> findNearest(ServerLevel serverLevel, BlockPos center, int radius) {
        return findNearest(serverLevel, center, radius, pos -> true);
    }

    public static Optional<Tree> findNearest(ServerLevel serverLevel, BlockPos center, int radius, Predicate<BlockPos> allowedLogPos) {
        return findNearestSlice(serverLevel, center, radius, allowedLogPos, 0).tree();
    }

    /**
     * Observes every loaded surface column in a 5x5 footprint around {@code center}.
     *
     * <p>The general retained search intentionally spends several reads down each column, so its
     * 50-read slice may cover only five or six columns. Exploration needs a different guarantee:
     * when an NPC walks beside a normal surface tree, it must inspect all 25 nearby columns before
     * deciding to keep walking. The no-leaves heightmap normally points directly at the top log;
     * tree expansion remains separately bounded and begins only after that one-read-per-column
     * observation finds a log.</p>
     */
    public static Optional<Tree> findNearestLocalFootprint(
            ServerLevel serverLevel,
            BlockPos center,
            Predicate<BlockPos> allowedLogPos
    ) {
        return findNearestLocalFootprintSlice(
                serverLevel, center, LOCAL_FOOTPRINT_RADIUS, allowedLogPos, 0
        ).tree();
    }

    /** One loaded-only surface slice for an adaptive, caller-retained nearby search episode. */
    public static SearchSlice findNearestLocalFootprintSlice(
            ServerLevel serverLevel,
            BlockPos center,
            int radius,
            Predicate<BlockPos> allowedLogPos,
            int startColumnIndex
    ) {
        List<ColumnOffset> columns = searchColumns(Math.max(LOCAL_FOOTPRINT_RADIUS, radius));
        int columnIndex = Math.max(0, Math.min(startColumnIndex, columns.size()));
        int endColumnIndex = Math.min(
                columns.size(),
                columnIndex + PlayerNpcAdaptiveSearchScope.MAX_COLUMNS_PER_SLICE
        );
        SearchBudget surfaceBudget = new SearchBudget(endColumnIndex - columnIndex);
        for (; columnIndex < endColumnIndex; columnIndex++) {
            ColumnOffset offset = columns.get(columnIndex);
            BlockPos column = center.offset(offset.dx(), 0, offset.dz());
            if (!serverLevel.hasChunkAt(column)) {
                continue;
            }
            int topY = serverLevel.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column.getX(),
                    column.getZ()
            ) - 1;
            BlockPos surface = new BlockPos(column.getX(), topY, column.getZ());
            if (!isLog(serverLevel, surface, allowedLogPos, surfaceBudget)) {
                continue;
            }
            Tree tree = scan(
                    serverLevel,
                    surface,
                    new HashSet<>(),
                    allowedLogPos,
                    new SearchBudget(MAX_SEARCH_BLOCK_READS),
                    new SearchBudget(MAX_LEAF_BLOCK_READS)
            );
            return new SearchSlice(Optional.of(tree), 0, true, columns.size());
        }
        boolean complete = columnIndex >= columns.size();
        return new SearchSlice(Optional.empty(), complete ? 0 : columnIndex, complete, columns.size());
    }

    /**
     * Runs one retained column slice. Callers that must eventually cover a stationary search
     * area retain {@link SearchSlice#nextColumnIndex()} between admitted passes.
     */
    public static SearchSlice findNearestSlice(
            ServerLevel serverLevel,
            BlockPos center,
            int radius,
            Predicate<BlockPos> allowedLogPos,
            int startColumnIndex
    ) {
        Set<BlockPos> visited = new HashSet<>();
        SearchBudget searchBudget = new SearchBudget(MAX_SEARCH_BLOCK_READS);
        SearchBudget leafBudget = new SearchBudget(MAX_LEAF_BLOCK_READS);
        Tree bestTree = null;
        Tree bestLooseLog = null;
        double bestTreeDistance = Double.MAX_VALUE;
        double bestLooseLogDistance = Double.MAX_VALUE;

        // Search columns nearest-first. Once a real tree exists, an unvisited column whose
        // horizontal lower-bound already exceeds that tree's full squared distance cannot contain
        // a closer stump. The previous cuboid traversal always read every block in the full
        // 65x25x65 volume even when a tree stood across a small river.
        List<ColumnOffset> columns = searchColumns(radius);
        int columnIndex = Math.max(0, Math.min(startColumnIndex, columns.size()));
        for (; columnIndex < columns.size(); columnIndex++) {
            ColumnOffset offset = columns.get(columnIndex);
            if (searchBudget.exhausted()) {
                break;
            }
            if (bestTree != null && offset.distanceSqr() > bestTreeDistance) {
                break;
            }
            BlockPos column = center.offset(offset.dx(), 0, offset.dz());
            if (!serverLevel.hasChunkAt(column)) {
                continue;
            }
            int topY = serverLevel.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column.getX(),
                    column.getZ()
            ) - 1;
            int bottomY = Math.max(serverLevel.getMinY(), topY - 8);
            for (int y = topY; y >= bottomY && !searchBudget.exhausted(); y--) {
                BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                if (visited.contains(pos) || !isLog(serverLevel, pos, allowedLogPos, searchBudget)) {
                    continue;
                }

                Tree candidate = scan(serverLevel, pos, visited, allowedLogPos, searchBudget, leafBudget);
                double distance = candidate.stump().distSqr(center);
                if (candidate.isTree() && distance < bestTreeDistance) {
                    bestTree = candidate;
                    bestTreeDistance = distance;
                } else if (!candidate.isTree() && distance < bestLooseLogDistance) {
                    bestLooseLog = candidate;
                    bestLooseLogDistance = distance;
                }
            }
        }

        Optional<Tree> result = Optional.ofNullable(bestTree != null ? bestTree : bestLooseLog);
        boolean complete = columnIndex >= columns.size();
        return new SearchSlice(result, complete ? 0 : columnIndex, complete, columns.size());
    }

    private static List<ColumnOffset> searchColumns(int radius) {
        int safeRadius = Math.max(0, radius);
        synchronized (SEARCH_COLUMNS_BY_RADIUS) {
            return SEARCH_COLUMNS_BY_RADIUS.computeIfAbsent(safeRadius, value -> {
                List<ColumnOffset> offsets = new ArrayList<>((value * 2 + 1) * (value * 2 + 1));
                for (int dx = -value; dx <= value; dx++) {
                    for (int dz = -value; dz <= value; dz++) {
                        offsets.add(new ColumnOffset(dx, dz, dx * dx + dz * dz));
                    }
                }
                offsets.sort(Comparator
                        .comparingInt(ColumnOffset::distanceSqr)
                        .thenComparingInt(ColumnOffset::dx)
                        .thenComparingInt(ColumnOffset::dz));
                return List.copyOf(offsets);
            });
        }
    }

    private static Tree scan(
            ServerLevel serverLevel,
            BlockPos start,
            Set<BlockPos> globalVisited,
            Predicate<BlockPos> allowedLogPos,
            SearchBudget searchBudget,
            SearchBudget leafBudget
    ) {
        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> localVisited = new HashSet<>();
        Set<BlockPos> queued = new HashSet<>();
        List<BlockPos> logs = new ArrayList<>();
        open.add(start.immutable());
        queued.add(start.immutable());

        while (!open.isEmpty() && logs.size() < MAX_LOGS) {
            BlockPos pos = open.poll();
            if (!localVisited.add(pos)) {
                continue;
            }

            logs.add(pos);
            globalVisited.add(pos);
            for (BlockPos adjacent : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                BlockPos next = adjacent.immutable();
                if (!localVisited.contains(next)
                        && queued.add(next)
                        && isLog(serverLevel, next, allowedLogPos, searchBudget)) {
                    open.add(next);
                }
                if (searchBudget.exhausted()) {
                    break;
                }
            }
        }

        BlockPos stump = logs.stream()
                .min(Comparator
                        .comparingInt((BlockPos pos) -> pos.getY())
                        .thenComparingDouble(pos -> start.distSqr(pos)))
                .orElse(start)
                .immutable();
        int leaves = countNearbyLeaves(serverLevel, logs, leafBudget);
        return new Tree(stump, logs, leaves >= MIN_LEAVES);
    }

    private static int countNearbyLeaves(
            ServerLevel serverLevel,
            List<BlockPos> logs,
            SearchBudget leafBudget
    ) {
        Set<BlockPos> leaves = new HashSet<>();
        for (BlockPos log : logs) {
            for (BlockPos mutable : BlockPos.betweenClosed(
                    log.offset(-LEAF_RADIUS, -1, -LEAF_RADIUS),
                    log.offset(LEAF_RADIUS, LEAF_RADIUS, LEAF_RADIUS))) {
                BlockPos pos = mutable.immutable();
                if (!leafBudget.tryConsume()) {
                    return leaves.size();
                }
                if (serverLevel.hasChunkAt(pos)
                        && serverLevel.getBlockState(pos).is(BlockTags.LEAVES)) {
                    leaves.add(pos);
                    if (leaves.size() >= MIN_LEAVES) {
                        return leaves.size();
                    }
                }
            }
        }
        return leaves.size();
    }

    private static boolean isLog(
            ServerLevel serverLevel,
            BlockPos pos,
            Predicate<BlockPos> allowedLogPos,
            SearchBudget searchBudget
    ) {
        if (!serverLevel.hasChunkAt(pos)
                || !allowedLogPos.test(pos)
                || !searchBudget.tryConsume()) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return state.is(BlockTags.LOGS);
    }

    private record ColumnOffset(int dx, int dz, int distanceSqr) {
    }

    public record SearchSlice(Optional<Tree> tree, int nextColumnIndex, boolean complete, int totalColumns) {
    }

    private static final class SearchBudget {
        private int remaining;

        private SearchBudget(int remaining) {
            this.remaining = Math.max(0, remaining);
        }

        private boolean tryConsume() {
            if (this.remaining <= 0) {
                return false;
            }
            this.remaining--;
            return true;
        }

        private boolean exhausted() {
            return this.remaining <= 0;
        }
    }

    public static final class Tree {
        private final BlockPos stump;
        private final List<BlockPos> logs;
        private final boolean tree;

        private Tree(BlockPos stump, List<BlockPos> logs, boolean tree) {
            this.stump = stump.immutable();
            this.logs = List.copyOf(logs);
            this.tree = tree;
        }

        public BlockPos stump() {
            return this.stump;
        }

        public List<BlockPos> logsNearestFirst(BlockPos origin) {
            List<BlockPos> sorted = new ArrayList<>(this.logs);
            sorted.sort(Comparator
                    .comparingDouble((BlockPos pos) -> pos.distSqr(origin))
                    .thenComparingInt(BlockPos::getY));
            return sorted;
        }

        public boolean isTree() {
            return this.tree;
        }
    }
}
