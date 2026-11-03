package com.pla.smart_npc.util;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public final class PlayerNpcHomeUtil {
    private static final int PROTECTED_HOME_HEIGHT = 6;
    private static final int[][] HOME_SIZES = {
            {5, 5},
            {6, 6},
            {7, 7},
            {8, 8},
            {9, 9},
            {10, 10},
            {5, 6},
            {6, 7},
            {7, 8},
            {8, 9},
            {9, 10}
    };
    private static final String HOME_X = "PlayerNpcHomeX";
    private static final String HOME_Y = "PlayerNpcHomeY";
    private static final String HOME_Z = "PlayerNpcHomeZ";
    private static final String HOME_WIDTH = "PlayerNpcHomeWidth";
    private static final String HOME_DEPTH = "PlayerNpcHomeDepth";
    private static final String HOME_LAYOUT_ID = "PlayerNpcHomeLayoutId";

    private PlayerNpcHomeUtil() {
    }

    public static Optional<HomeArea> getHome(PlayerNpcEntity playerNpc) {
        CompoundTag persistentData = com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc);
        if (!hasHomeTag(persistentData)) {
            return Optional.empty();
        }

        return Optional.of(readHome(persistentData));
    }

    public static Optional<String> getHomeLayoutId(PlayerNpcEntity playerNpc) {
        CompoundTag persistentData = com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc);
        if (!persistentData.contains(HOME_LAYOUT_ID, Tag.TAG_STRING)) {
            return Optional.empty();
        }

        String layoutId = persistentData.getString(HOME_LAYOUT_ID);
        return layoutId.isBlank() ? Optional.empty() : Optional.of(layoutId);
    }

    public static HomeArea getOrCreateHome(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return getHome(playerNpc).orElseGet(() -> {
            HomeArea homeArea = findHomeArea(playerNpc, serverLevel).orElseGet(() -> {
                BlockPos origin = playerNpc.blockPosition().offset(-2, 0, -2);
                return new HomeArea(origin, 5, 5);
            });
            setHome(playerNpc, homeArea);
            return homeArea;
        });
    }

    public static void setHome(PlayerNpcEntity playerNpc, HomeArea homeArea) {
        writeHome(com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc), homeArea);
    }

    public static void setHome(PlayerNpcEntity playerNpc, HomeArea homeArea, String layoutId) {
        setHome(playerNpc, homeArea);
        setHomeLayoutId(playerNpc, layoutId);
    }

    public static void setHomeLayoutId(PlayerNpcEntity playerNpc, String layoutId) {
        if (layoutId == null || layoutId.isBlank()) {
            com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).remove(HOME_LAYOUT_ID);
            return;
        }

        com.pla.smart_npc.platform.NpcPlatform.persistentData(playerNpc).putString(HOME_LAYOUT_ID, layoutId);
    }

    public static void saveHomeToTag(PlayerNpcEntity playerNpc, CompoundTag tag) {
        getHome(playerNpc).ifPresent(homeArea -> writeHome(tag, homeArea));
        getHomeLayoutId(playerNpc).ifPresent(layoutId -> tag.putString(HOME_LAYOUT_ID, layoutId));
    }

    public static void readHomeFromTag(PlayerNpcEntity playerNpc, CompoundTag tag) {
        if (!hasHomeTag(tag)) {
            return;
        }

        setHome(playerNpc, readHome(tag));
        if (tag.contains(HOME_LAYOUT_ID, Tag.TAG_STRING)) {
            setHomeLayoutId(playerNpc, tag.getString(HOME_LAYOUT_ID));
        }
    }

    public static boolean isInside(HomeArea homeArea, BlockPos pos) {
        return isInsideFootprint(homeArea, pos)
                && pos.getY() >= homeArea.origin().getY()
                && pos.getY() <= homeArea.origin().getY() + PROTECTED_HOME_HEIGHT;
    }

    public static boolean isInsideFootprint(HomeArea homeArea, BlockPos pos) {
        return homeArea != null
                && pos != null
                && pos.getX() >= homeArea.origin().getX()
                && pos.getX() < homeArea.origin().getX() + homeArea.width()
                && pos.getZ() >= homeArea.origin().getZ()
                && pos.getZ() < homeArea.origin().getZ() + homeArea.depth();
    }

    public static boolean isInsideBuildFootprint(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || pos == null) {
            return false;
        }

        Optional<HomeArea> home = getHome(playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        HomeArea homeArea = home.get();
        if (!isInsideFootprint(homeArea, pos)) {
            return false;
        }

        Optional<PlayerNpcBuildLayout> layout = getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != homeArea.width()
                || layout.get().depth() != homeArea.depth()) {
            return true;
        }

        int relX = pos.getX() - homeArea.origin().getX();
        int relZ = pos.getZ() - homeArea.origin().getZ();
        return layout.get().isInFootprint(relX, relZ);
    }

    public static BlockPos center(HomeArea homeArea) {
        return homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
    }

    public static boolean isInsideActivityRadius(PlayerNpcEntity playerNpc, BlockPos pos, int radius, boolean bypassForExplorers) {
        if (bypassForExplorers && playerNpc.hasInterest(PlayerNpcInterest.EXPLORING)) {
            return true;
        }

        Optional<HomeArea> homeArea = getHome(playerNpc);
        if (homeArea.isEmpty()) {
            return true;
        }

        return center(homeArea.get()).distSqr(pos) <= radius * radius;
    }

    public static BlockPos interiorPos(HomeArea homeArea, int x, int z) {
        int safeX = Math.max(1, Math.min(homeArea.width() - 2, x));
        int safeZ = Math.max(1, Math.min(homeArea.depth() - 2, z));
        return homeArea.origin().offset(safeX, 1, safeZ);
    }

    public static boolean isReplaceableForNpcBuild(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return state.isAir()
                || state.canBeReplaced()
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null;
    }

    private static boolean hasHomeTag(CompoundTag tag) {
        return tag.contains(HOME_X, Tag.TAG_INT)
                && tag.contains(HOME_Y, Tag.TAG_INT)
                && tag.contains(HOME_Z, Tag.TAG_INT)
                && tag.contains(HOME_WIDTH, Tag.TAG_INT)
                && tag.contains(HOME_DEPTH, Tag.TAG_INT);
    }

    private static HomeArea readHome(CompoundTag tag) {
        BlockPos origin = new BlockPos(
                tag.getInt(HOME_X),
                tag.getInt(HOME_Y),
                tag.getInt(HOME_Z)
        );
        int width = Math.max(3, tag.getInt(HOME_WIDTH));
        int depth = Math.max(3, tag.getInt(HOME_DEPTH));
        return new HomeArea(origin, width, depth);
    }

    private static void writeHome(CompoundTag tag, HomeArea homeArea) {
        tag.putInt(HOME_X, homeArea.origin().getX());
        tag.putInt(HOME_Y, homeArea.origin().getY());
        tag.putInt(HOME_Z, homeArea.origin().getZ());
        tag.putInt(HOME_WIDTH, homeArea.width());
        tag.putInt(HOME_DEPTH, homeArea.depth());
    }

    private static Optional<HomeArea> findHomeArea(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        BlockPos center = playerNpc.blockPosition();
        int[] size = HOME_SIZES[playerNpc.getRandom().nextInt(HOME_SIZES.length)];
        int width = size[0];
        int depth = size[1];

        for (BlockPos originCandidate : BlockPos.betweenClosed(center.offset(-12, -1, -12), center.offset(12, 1, 12))) {
            HomeArea homeArea = new HomeArea(originCandidate.immutable(), width, depth);
            if (canUseArea(serverLevel, homeArea)) {
                return Optional.of(homeArea);
            }
        }
        return Optional.empty();
    }

    private static boolean canUseArea(ServerLevel serverLevel, HomeArea homeArea) {
        for (int x = 0; x < homeArea.width(); x++) {
            for (int z = 0; z < homeArea.depth(); z++) {
                BlockPos floor = homeArea.origin().offset(x, 0, z);
                if (!serverLevel.getBlockState(floor.below()).isSolidRender(serverLevel, floor.below())) {
                    return false;
                }
                for (int y = 0; y <= 3; y++) {
                    if (!serverLevel.getBlockState(homeArea.origin().offset(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public record HomeArea(BlockPos origin, int width, int depth) {}
}
