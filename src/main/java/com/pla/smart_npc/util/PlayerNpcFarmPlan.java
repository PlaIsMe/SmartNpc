package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;

public final class PlayerNpcFarmPlan {
    private static final int CURRENT_VERSION = 1;
    private static final String PLAN_TAG = "SmartNpcFarmPlan";
    private static final String VERSION = "Version";
    private static final String DIMENSION = "Dimension";
    private static final String ORIGIN = "Origin";
    private static final String WIDTH = "Width";
    private static final String DEPTH = "Depth";
    private static final String SHAPE = "Shape";
    private static final String PHASE = "Phase";
    private static final String WATER = "Water";
    private static final String GATE = "Gate";

    private static final String LEGACY_X = "PlayerNpcFarmX";
    private static final String LEGACY_Y = "PlayerNpcFarmY";
    private static final String LEGACY_Z = "PlayerNpcFarmZ";
    private static final String LEGACY_WIDTH = "PlayerNpcFarmWidth";
    private static final String LEGACY_DEPTH = "PlayerNpcFarmDepth";
    private static final Map<PlayerNpcEntity, Plan> PLAN_CACHE = new WeakHashMap<>();

    private PlayerNpcFarmPlan() {
    }

    public static Optional<Plan> get(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return Optional.empty();
        }

        Plan cached = PLAN_CACHE.get(playerNpc);
        if (cached != null) {
            return Optional.of(cached);
        }

        CompoundTag persistentData = playerNpc.getPersistentData();
        if (persistentData.contains(PLAN_TAG)) {
            Optional<Plan> read = read(persistentData.getCompoundOrEmpty(PLAN_TAG));
            read.ifPresent(plan -> PLAN_CACHE.put(playerNpc, plan));
            return read;
        }
        Optional<Plan> migrated = readLegacy(persistentData, playerNpc);
        migrated.ifPresent(plan -> save(playerNpc, plan));
        return migrated;
    }

    public static void save(PlayerNpcEntity playerNpc, Plan plan) {
        if (playerNpc == null || plan == null) {
            return;
        }

        CompoundTag tag = new CompoundTag();
        tag.putInt(VERSION, CURRENT_VERSION);
        tag.putString(DIMENSION, plan.dimension());
        tag.putLong(ORIGIN, plan.origin().asLong());
        tag.putInt(WIDTH, plan.width());
        tag.putInt(DEPTH, plan.depth());
        tag.putString(SHAPE, plan.shape().name());
        tag.putString(PHASE, plan.phase().name());
        tag.putLong(WATER, plan.waterPos().asLong());
        tag.putLong(GATE, plan.gatePos().asLong());

        CompoundTag persistentData = playerNpc.getPersistentData();
        persistentData.put(PLAN_TAG, tag);
        persistentData.putInt(LEGACY_X, plan.origin().getX());
        persistentData.putInt(LEGACY_Y, plan.origin().getY());
        persistentData.putInt(LEGACY_Z, plan.origin().getZ());
        persistentData.putInt(LEGACY_WIDTH, plan.width());
        persistentData.putInt(LEGACY_DEPTH, plan.depth());
        PLAN_CACHE.put(playerNpc, plan);
    }

    public static void clear(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return;
        }
        CompoundTag tag = playerNpc.getPersistentData();
        tag.remove(PLAN_TAG);
        tag.remove(LEGACY_X);
        tag.remove(LEGACY_Y);
        tag.remove(LEGACY_Z);
        tag.remove(LEGACY_WIDTH);
        tag.remove(LEGACY_DEPTH);
        PLAN_CACHE.remove(playerNpc);
    }

    public static boolean isOwnedFarmIrrigationWater(PlayerNpcEntity playerNpc, BlockPos pos) {
        return pos != null && get(playerNpc)
                .filter(plan -> plan.dimension().isBlank()
                        || playerNpc.level().dimension().identifier().toString().equals(plan.dimension()))
                .map(plan -> plan.waterPos().equals(pos))
                .orElse(false);
    }

    public static boolean isOwnedFarmEntrance(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return get(playerNpc).map(plan -> plan.gatePos().equals(pos)
                || plan.pathPositions().contains(pos)
                || plan.pathPositions().stream().anyMatch(path -> path.above().equals(pos))).orElse(false);
    }

    private static Optional<Plan> read(CompoundTag tag) {
        if (tag.contains(VERSION) && tag.getIntOr(VERSION, 0) > CURRENT_VERSION
                || !tag.contains(ORIGIN)
                || !tag.contains(WIDTH)
                || !tag.contains(DEPTH)
                || !tag.contains(WATER)
                || !tag.contains(GATE)) {
            return Optional.empty();
        }
        try {
            String dimension = tag.contains(DIMENSION) ? tag.getStringOr(DIMENSION, "") : "";
            return Optional.of(new Plan(
                    BlockPos.of(tag.getLongOr(ORIGIN, 0L)),
                    Math.max(1, tag.getIntOr(WIDTH, 0)),
                    Math.max(1, tag.getIntOr(DEPTH, 0)),
                    parseShape(tag.getStringOr(SHAPE, "")),
                    parsePhase(tag.getStringOr(PHASE, "")),
                    BlockPos.of(tag.getLongOr(WATER, 0L)),
                    BlockPos.of(tag.getLongOr(GATE, 0L)),
                    dimension
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Plan> readLegacy(CompoundTag tag, PlayerNpcEntity playerNpc) {
        if (!tag.contains(LEGACY_X)
                || !tag.contains(LEGACY_Y)
                || !tag.contains(LEGACY_Z)
                || !tag.contains(LEGACY_WIDTH)
                || !tag.contains(LEGACY_DEPTH)) {
            return Optional.empty();
        }
        BlockPos origin = new BlockPos(tag.getIntOr(LEGACY_X, 0), tag.getIntOr(LEGACY_Y, 0), tag.getIntOr(LEGACY_Z, 0));
        int width = Math.max(1, tag.getIntOr(LEGACY_WIDTH, 0));
        int depth = Math.max(1, tag.getIntOr(LEGACY_DEPTH, 0));
        BlockPos water = origin.offset(width / 2, 0, depth / 2);
        BlockPos anchor = PlayerNpcHomeUtil.getHome(playerNpc)
                .map(PlayerNpcHomeUtil::center)
                .orElseGet(playerNpc::blockPosition);
        BlockPos gate = chooseLegacyGate(origin, width, depth, anchor);
        String dimension = playerNpc.level().dimension().identifier().toString();
        return Optional.of(new Plan(origin, width, depth, Shape.RECTANGLE, Phase.CLEAR, water, gate, dimension));
    }

    private static Shape parseShape(String name) {
        try {
            return Shape.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Shape.RECTANGLE;
        }
    }

    private static Phase parsePhase(String name) {
        try {
            return Phase.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return Phase.GATHER_LOGS;
        }
    }

    private static BlockPos chooseLegacyGate(BlockPos origin, int width, int depth, BlockPos anchor) {
        BlockPos center = origin.offset(width / 2, 0, depth / 2);
        int dx = anchor.getX() - center.getX();
        int dz = anchor.getZ() - center.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx < 0
                    ? origin.offset(-1, 1, depth / 2)
                    : origin.offset(width, 1, depth / 2);
        }
        return dz < 0
                ? origin.offset(width / 2, 1, -1)
                : origin.offset(width / 2, 1, depth);
    }

    public enum Shape {
        RECTANGLE,
        ROUNDED
    }

    public enum Phase {
        GATHER_LOGS,
        GATHER_STONE,
        CLEAR,
        WATER,
        FENCE,
        GATE,
        TILL,
        READY;

        public boolean isReady() {
            return this == READY;
        }
    }

    public record Plan(
            BlockPos origin,
            int width,
            int depth,
            Shape shape,
            Phase phase,
            BlockPos waterPos,
            BlockPos gatePos,
            String dimension
    ) {
        public Plan {
            origin = origin.immutable();
            width = Math.max(1, width);
            depth = Math.max(1, depth);
            shape = shape == null ? Shape.RECTANGLE : shape;
            phase = phase == null ? Phase.CLEAR : phase;
            waterPos = waterPos.immutable();
            gatePos = gatePos.immutable();
            dimension = dimension == null ? "" : dimension;
        }

        public Plan withPhase(Phase nextPhase) {
            return new Plan(this.origin, this.width, this.depth, this.shape, nextPhase, this.waterPos, this.gatePos, this.dimension);
        }

        public Plan withDimension(String dimension) {
            return new Plan(this.origin, this.width, this.depth, this.shape, this.phase, this.waterPos, this.gatePos, dimension);
        }

        public boolean containsGround(BlockPos pos) {
            if (pos == null || pos.getY() != this.origin.getY()) {
                return false;
            }
            return this.includesOffset(pos.getX() - this.origin.getX(), pos.getZ() - this.origin.getZ());
        }

        public boolean isFarmPath(BlockPos pos) {
            if (pos == null) {
                return false;
            }
            BlockPos cursor = this.findInteriorGateNeighbor();
            int guard = this.width + this.depth + 4;
            while (cursor != null && !cursor.equals(this.waterPos) && guard-- > 0) {
                if (cursor.equals(pos)) {
                    return true;
                }
                BlockPos next = this.stepTowardWater(cursor, true);
                if (next == null) {
                    next = this.stepTowardWater(cursor, false);
                }
                if (next == null || next.equals(cursor)) {
                    break;
                }
                cursor = next;
            }
            return false;
        }

        public List<BlockPos> allGroundPositions() {
            List<BlockPos> positions = new ArrayList<>();
            for (int x = 0; x < this.width; x++) {
                for (int z = 0; z < this.depth; z++) {
                    if (this.includesOffset(x, z)) {
                        positions.add(this.origin.offset(x, 0, z));
                    }
                }
            }
            return List.copyOf(positions);
        }

        public List<BlockPos> pathPositions() {
            BlockPos entry = this.findInteriorGateNeighbor();
            if (entry == null) {
                return List.of();
            }

            List<BlockPos> positions = new ArrayList<>();
            BlockPos cursor = entry;
            int guard = this.width + this.depth + 4;
            while (!cursor.equals(this.waterPos) && guard-- > 0) {
                positions.add(cursor.immutable());
                BlockPos next = this.stepTowardWater(cursor, true);
                if (next == null) {
                    next = this.stepTowardWater(cursor, false);
                }
                if (next == null || next.equals(cursor)) {
                    break;
                }
                cursor = next;
            }
            return List.copyOf(positions);
        }

        public List<BlockPos> cropGroundPositions() {
            Set<BlockPos> excluded = new LinkedHashSet<>(this.pathPositions());
            excluded.add(this.waterPos);
            return this.allGroundPositions().stream().filter(pos -> !excluded.contains(pos)).toList();
        }

        public List<BlockPos> cropPositions() {
            return this.cropGroundPositions().stream().map(BlockPos::above).toList();
        }

        public List<BlockPos> fencePositions() {
            Set<BlockPos> positions = new LinkedHashSet<>();
            for (BlockPos ground : this.allGroundPositions()) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbor = ground.offset(dx, 0, dz);
                        if (this.containsGround(neighbor)) {
                            continue;
                        }
                        boolean cardinal = dx == 0 || dz == 0;
                        boolean closesConvexCorner = !this.containsGround(ground.offset(dx, 0, 0))
                                && !this.containsGround(ground.offset(0, 0, dz));
                        if (!cardinal && !closesConvexCorner) {
                            continue;
                        }
                        BlockPos fencePos = neighbor.above();
                        if (!fencePos.equals(this.gatePos)) {
                            positions.add(fencePos.immutable());
                        }
                    }
                }
            }
            return List.copyOf(positions);
        }

        public boolean owns(BlockPos pos) {
            if (pos == null) {
                return false;
            }
            return this.containsGround(pos)
                    || this.containsGround(pos.below())
                    || this.gatePos.equals(pos)
                    || this.isFencePosition(pos);
        }

        public boolean isFencePosition(BlockPos pos) {
            if (pos == null
                    || pos.equals(this.gatePos)
                    || pos.getY() != this.origin.getY() + 1
                    || this.containsGround(pos.below())) {
                return false;
            }
            BlockPos outsideGround = pos.below();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos farmGround = outsideGround.offset(-dx, 0, -dz);
                    if (!this.containsGround(farmGround)) {
                        continue;
                    }
                    boolean cardinal = dx == 0 || dz == 0;
                    boolean convexCorner = !this.containsGround(farmGround.offset(dx, 0, 0))
                            && !this.containsGround(farmGround.offset(0, 0, dz));
                    if (cardinal || convexCorner) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean includesOffset(int x, int z) {
            if (x < 0 || x >= this.width || z < 0 || z >= this.depth) {
                return false;
            }
            return this.shape != Shape.ROUNDED
                    || !((x == 0 || x == this.width - 1) && (z == 0 || z == this.depth - 1));
        }

        private BlockPos findInteriorGateNeighbor() {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = this.gatePos.relative(direction).below();
                if (this.containsGround(candidate)) {
                    return candidate.immutable();
                }
            }
            return null;
        }

        private BlockPos stepTowardWater(BlockPos from, boolean xFirst) {
            int dx = Integer.compare(this.waterPos.getX(), from.getX());
            int dz = Integer.compare(this.waterPos.getZ(), from.getZ());
            BlockPos first = xFirst && dx != 0 ? from.offset(dx, 0, 0) : dz != 0 ? from.offset(0, 0, dz) : null;
            BlockPos second = !xFirst && dx != 0 ? from.offset(dx, 0, 0) : dz != 0 ? from.offset(0, 0, dz) : null;
            if (first != null && this.containsGround(first)) {
                return first.immutable();
            }
            if (second != null && this.containsGround(second)) {
                return second.immutable();
            }
            return null;
        }
    }
}
