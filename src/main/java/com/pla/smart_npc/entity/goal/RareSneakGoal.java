package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.CautiousThreatAi;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class RareSneakGoal extends Goal {
    private static final double RANGE = 8.0D;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private LivingEntity threat;
    private int sneakTicks;

    public RareSneakGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getRareSneakCooldown() > 0
                || !this.canUseThrottle.canCheck(this.playerNpc)
                || this.playerNpc.getRandom().nextFloat() > 0.06F) {
            return false;
        }

        this.threat = CautiousThreatAi.findNearestThreat(this.playerNpc, RANGE);
        return this.threat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.sneakTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && CautiousThreatAi.isValidThreat(this.playerNpc, this.threat)
                && this.playerNpc.distanceToSqr(this.threat) <= RANGE * RANGE;
    }

    @Override
    public void start() {
        this.sneakTicks = 40 + this.playerNpc.getRandom().nextInt(50);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setShiftKeyDown(true);
        this.playerNpc.setPose(Pose.CROUCHING);
        this.playerNpc.setCurrentAiState("ai.player_npc.sneaking");
        this.updateDetail();
    }

    @Override
    public void tick() {
        this.sneakTicks--;
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
            this.playerNpc.setRareSneakCooldown(20 * 90 + this.playerNpc.getRandom().nextInt(20 * 180));
        }
        this.sneakTicks = 0;
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
            this.playerNpc.setCurrentAiDetail("sneaking from: " + this.threat.getDisplayName().getString());
        }
    }
}
