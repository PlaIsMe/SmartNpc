package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.util.SmartNpcItemUtil;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.PlayerNpcAlertManager;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

public class CallForHelpGoal extends Goal {
    private static final double SEARCH_RADIUS = 18.0D;
    private static final int COOLDOWN_TICKS = 20 * 20;
    private static final int PROACTIVE_SCAN_INTERVAL_TICKS = 20;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(PROACTIVE_SCAN_INTERVAL_TICKS);
    private LivingEntity threat;

    public CallForHelpGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getHelpAlertCooldown() > 0) {
            return false;
        }
        // Preserve immediate reaction to a mob that has actually hurt this NPC. The broad inverse
        // target lookup below is only proactive discovery and can safely run at a lower cadence.
        LivingEntity recentAttacker = this.playerNpc.getLastHurtByMob();
        if (recentAttacker instanceof Mob attackingMob && this.isChasingThisNpc(attackingMob)) {
            this.threat = attackingMob;
            if (this.shouldAvoidInsteadOfFight(this.threat)) {
                return true;
            }
            this.threat = null;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.threat = this.findChasingThreat();
        } finally {
            PlayerNpcPerformanceMonitor.recordGoalWork(
                    this.playerNpc,
                    this.getClass().getSimpleName() + ".proactiveScan",
                    timing
            );
        }
        return this.threat != null && this.shouldAvoidInsteadOfFight(this.threat);
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (this.playerNpc.level().isClientSide() || this.threat == null) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.threat, 40.0F, 40.0F);
        this.playerNpc.setCurrentAiState("ai.player_npc.calling_for_help");
        ChatUtil.callForHelp(this.playerNpc, this.threat);
        PlayerNpcAlertManager.raiseThreatAlert(this.playerNpc, this.threat);
        this.playerNpc.setHelpAlertCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 10));
        this.threat = null;
    }

    private LivingEntity findChasingThreat() {
        AABB searchBox = this.playerNpc.getBoundingBox().inflate(SEARCH_RADIUS, 6.0D, SEARCH_RADIUS);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Mob candidate : this.playerNpc.level().getEntitiesOfClass(Mob.class, searchBox, this::isChasingThisNpc)) {
            double distance = this.playerNpc.distanceToSqr(candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }

        return best;
    }

    private boolean isChasingThisNpc(Mob candidate) {
        return candidate != this.playerNpc
                && candidate.isAlive()
                && !candidate.isAlliedTo(this.playerNpc)
                && candidate.getTarget() == this.playerNpc;
    }

    private boolean shouldAvoidInsteadOfFight(LivingEntity threat) {
        if (this.playerNpc.shouldSmartNpcFleeFromTarget(threat)
                || this.playerNpc.isSmartNpcCompatHighDangerThreat(threat)) {
            return true;
        }

        float healthRatio = this.playerNpc.getHealth() / this.playerNpc.getMaxHealth();
        return healthRatio < 0.65F || this.powerScore(threat) > this.powerScore(this.playerNpc) + 5.0D;
    }

    private double powerScore(LivingEntity entity) {
        double score = entity.getHealth() * 0.4D + entity.getArmorValue();
        score += itemPower(entity.getMainHandItem()) * 1.4D;
        score += itemPower(entity.getOffhandItem()) * 0.5D;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            score += itemPower(entity.getItemBySlot(slot));
        }
        return score;
    }

    private double itemPower(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double score = 0.0D;
        if (stack.is(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem) {
            score += SmartNpcItemUtil.attackDamage(stack);
        }
        if (SmartNpcItemUtil.isArmor(stack)) {
            score += SmartNpcItemUtil.armor(stack) + SmartNpcItemUtil.toughness(stack);
        }
        if (stack.isEnchanted()) {
            score += 2.0D;
        }
        return score;
    }
}
