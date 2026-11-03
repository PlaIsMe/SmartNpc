package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.CautiousThreatAi;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class ScaredHideGoal extends Goal {
    private static final double RANGE = 10.0D;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private LivingEntity threat;
    private int hideTicks;

    public ScaredHideGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getScaredHideCooldown() > 0
                || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        float chance = this.playerNpc.getHealth() < this.playerNpc.getMaxHealth() * 0.45F ? 0.12F : 0.025F;
        if (this.playerNpc.getRandom().nextFloat() > chance) {
            return false;
        }

        this.threat = CautiousThreatAi.findNearestThreat(this.playerNpc, RANGE);
        return this.threat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.hideTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && CautiousThreatAi.isValidThreat(this.playerNpc, this.threat)
                && this.playerNpc.distanceToSqr(this.threat) <= RANGE * RANGE;
    }

    @Override
    public void start() {
        this.hideTicks = 50 + this.playerNpc.getRandom().nextInt(80);
        this.playerNpc.setTarget(null);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setShiftKeyDown(true);
        this.playerNpc.setPose(Pose.CROUCHING);
        this.playerNpc.setCurrentAiState("ai.player_npc.scared_hiding");
        this.updateDetail();
    }

    @Override
    public void tick() {
        this.hideTicks--;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setShiftKeyDown(true);
        this.playerNpc.setPose(Pose.CROUCHING);
        if (CautiousThreatAi.isValidThreat(this.playerNpc, this.threat)) {
            this.playerNpc.getLookControl().setLookAt(this.threat, 45.0F, 45.0F);
        }
    }

    @Override
    public void stop() {
        this.playerNpc.setShiftKeyDown(false);
        this.playerNpc.setPose(Pose.STANDING);
        if (!this.playerNpc.level().isClientSide()) {
            this.playerNpc.setScaredHideCooldown(20 * 70 + this.playerNpc.getRandom().nextInt(20 * 120));
        }
        this.hideTicks = 0;
        this.threat = null;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void updateDetail() {
        if (this.threat != null) {
            this.playerNpc.setCurrentAiDetail("hiding from: " + this.threat.getDisplayName().getString());
        }
    }
}
