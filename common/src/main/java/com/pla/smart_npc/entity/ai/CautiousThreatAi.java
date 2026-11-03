package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;

/** Shared cautious-characteristic threat classification and bounded acquisition. */
public final class CautiousThreatAi {
    private CautiousThreatAi() {
    }

    public static boolean isValidThreat(PlayerNpcEntity playerNpc, @Nullable LivingEntity candidate) {
        if (playerNpc == null
                || candidate == null
                || candidate == playerNpc
                || !candidate.isAlive()
                || candidate.isRemoved()
                || candidate.isSpectator()
                || playerNpc.isAlliedTo(candidate)
                || candidate.isAlliedTo(playerNpc)) {
            return false;
        }

        // Creative players commonly approach NPCs for inspection and are not a survival threat.
        // Player NPCs remain threats even though they also extend Player.
        if (candidate instanceof Player player
                && !(candidate instanceof PlayerNpcEntity)
                && player.isCreative()) {
            return false;
        }
        if (candidate instanceof Player || candidate instanceof PlayerNpcEntity) {
            return true;
        }
        return candidate == playerNpc.getLastHurtByMob()
                || candidate instanceof Enemy
                || candidate instanceof Monster
                || candidate.getType().getCategory() == MobCategory.MONSTER
                || candidate instanceof Mob mob && mob.getTarget() == playerNpc
                || playerNpc.isSmartNpcCompatMonsterTarget(candidate)
                || playerNpc.isSmartNpcCompatHighDangerThreat(candidate);
    }

    /**
     * Call only from a throttled planning/activation path. Active cautious ticks should retain the
     * selected entity and use {@link #isValidThreat(PlayerNpcEntity, LivingEntity)} instead.
     */
    @Nullable
    public static LivingEntity findNearestThreat(PlayerNpcEntity playerNpc, double range) {
        if (playerNpc == null || range <= 0.0D) {
            return null;
        }
        AABB searchBox = playerNpc.getBoundingBox().inflate(range, 8.0D, range);
        List<LivingEntity> threats = playerNpc.level().getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                candidate -> isValidThreat(playerNpc, candidate)
        );

        LivingEntity nearest = null;
        double nearestDistanceSqr = range * range;
        for (LivingEntity candidate : threats) {
            double distanceSqr = playerNpc.distanceToSqr(candidate);
            if (distanceSqr <= nearestDistanceSqr) {
                nearest = candidate;
                nearestDistanceSqr = distanceSqr;
            }
        }
        return nearest;
    }
}
