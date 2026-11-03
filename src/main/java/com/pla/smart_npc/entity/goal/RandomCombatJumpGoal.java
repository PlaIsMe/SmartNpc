package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class RandomCombatJumpGoal extends Goal {
    private static final int MIN_COOLDOWN_TICKS = 15;
    private static final int MAX_COOLDOWN_TICKS = 45;
    private static final int JUMP_CHANCE_BOUND = 4;

    private final PlayerNpcEntity playerNpc;
    private int cooldownTicks;

    public RandomCombatJumpGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.cooldownTicks = this.nextCooldown();
        this.setFlags(EnumSet.of(Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!this.hasCombatTarget()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isInWater()
                || this.playerNpc.isInLava()
                || !this.playerNpc.onGround()) {
            return false;
        }

        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return false;
        }

        return this.playerNpc.getRandom().nextInt(JUMP_CHANCE_BOUND) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        this.playerNpc.jump();
        this.cooldownTicks = this.nextCooldown();
    }

    private boolean hasCombatTarget() {
        LivingEntity target = this.playerNpc.getTarget();
        return target != null && target.isAlive();
    }

    private int nextCooldown() {
        return MIN_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS + 1);
    }
}
