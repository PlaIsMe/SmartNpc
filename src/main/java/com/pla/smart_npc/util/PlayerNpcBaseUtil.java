package com.pla.smart_npc.util;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/** Resolves the persistent base used by Player NPCs that do not own a builder home. */
public final class PlayerNpcBaseUtil {
    private static final String CAMP_BASE_POS = "PlayerNpcCampBasePos";
    private static final String CAMP_BASE_DIMENSION = "PlayerNpcCampBaseDimension";

    private PlayerNpcBaseUtil() {
    }

    public static boolean hasCampBaseJobs(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && !playerNpc.hasInterest(PlayerNpcInterest.FARMING)
                && playerNpc.hasAnyInterest(PlayerNpcInterest.MINING, PlayerNpcInterest.FISHING);
    }

    public static boolean isExplorerOnly(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && playerNpc.hasInterest(PlayerNpcInterest.EXPLORING)
                && !playerNpc.hasAnyInterest(
                PlayerNpcInterest.BUILDING,
                PlayerNpcInterest.FARMING,
                PlayerNpcInterest.MINING,
                PlayerNpcInterest.FISHING
        );
    }

    public static Optional<BlockPos> getCampBase(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null || !hasCampBaseJobs(playerNpc)) {
            return Optional.empty();
        }
        Optional<CampBase> campBase = getStoredCampBase(playerNpc);
        if (campBase.isEmpty()
                || !serverLevel.dimension().identifier().toString().equals(campBase.get().dimension())) {
            return Optional.empty();
        }
        return Optional.of(campBase.get().pos());
    }

    public static boolean hasStoredCampBase(PlayerNpcEntity playerNpc) {
        return getStoredCampBase(playerNpc).isPresent();
    }

    public static Optional<BlockPos> setCampBaseIfAbsent(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos campBase
    ) {
        Optional<CampBase> stored = getStoredCampBase(playerNpc);
        if (stored.isPresent()) {
            return serverLevel.dimension().identifier().toString().equals(stored.get().dimension())
                    ? Optional.of(stored.get().pos())
                    : Optional.empty();
        }
        BlockPos saved = campBase.immutable();
        playerNpc.getPersistentData().putLong(CAMP_BASE_POS, saved.asLong());
        playerNpc.getPersistentData().putString(
                CAMP_BASE_DIMENSION,
                serverLevel.dimension().identifier().toString()
        );
        return Optional.of(saved);
    }

    /**
     * Returns the non-builder storage/night anchor. Farming always wins over a camp base.
     * Builder callers intentionally get no result so their legacy home remains authoritative.
     */
    public static Optional<BlockPos> getNonBuilderBase(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null || playerNpc.hasInterest(PlayerNpcInterest.BUILDING)) {
            return Optional.empty();
        }
        if (playerNpc.hasInterest(PlayerNpcInterest.FARMING)) {
            return FarmAi.getPlan(playerNpc, serverLevel).map(plan -> plan.gatePos().immutable());
        }
        return getCampBase(playerNpc, serverLevel);
    }

    private static Optional<CampBase> getStoredCampBase(PlayerNpcEntity playerNpc) {
        if (playerNpc == null
                || !playerNpc.getPersistentData().contains(CAMP_BASE_POS)
                || !playerNpc.getPersistentData().contains(CAMP_BASE_DIMENSION)) {
            return Optional.empty();
        }
        String dimension = playerNpc.getPersistentData().getStringOr(CAMP_BASE_DIMENSION, "");
        if (dimension.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new CampBase(
                BlockPos.of(playerNpc.getPersistentData().getLongOr(CAMP_BASE_POS, 0L)),
                dimension
        ));
    }

    private record CampBase(BlockPos pos, String dimension) {
    }
}
