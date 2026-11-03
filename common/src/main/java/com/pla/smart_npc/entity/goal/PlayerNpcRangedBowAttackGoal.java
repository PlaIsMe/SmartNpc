package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import com.pla.smart_npc.platform.NpcPlatform;
import net.minecraft.world.item.BowItem;

public class PlayerNpcRangedBowAttackGoal extends Goal {
    private final PlayerNpcEntity playerNpc;
    private final Goal delegate;

    public PlayerNpcRangedBowAttackGoal(PlayerNpcEntity playerNpc, double speedModifier, int attackIntervalMin, float attackRadius) {
        this.delegate = NpcPlatform.rangedBowGoal(playerNpc, speedModifier, attackIntervalMin, attackRadius);
        this.setFlags(this.delegate.getFlags());
        this.playerNpc = playerNpc;
    }

    @Override
    public boolean canUse() {
        return this.canUseBow()
                && (this.isHoldingBow() || this.hasInventoryBow())
                && this.delegate.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUseBow()
                && this.isHoldingBow()
                && this.delegate.canContinueToUse();
    }

    @Override
    public void start() {
        if (this.playerNpc.isHealing() || this.playerNpc.isClearingCombatObstruction()) {
            return;
        }
        this.equipInventoryBowIfNeeded();
        this.playerNpc.setCurrentAiState("ai.player_npc.ranged_bow");
        this.delegate.start();
    }

    @Override
    public void tick() {
        if (!this.playerNpc.isHealing() && !this.playerNpc.isClearingCombatObstruction()) {
            this.delegate.tick();
        }
    }

    @Override
    public void stop() {
        this.delegate.stop();
        this.restorePreviousMainHand();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    @Override
    public boolean requiresUpdateEveryTick() { return this.delegate.requiresUpdateEveryTick(); }

    protected boolean isHoldingBow() {
        return this.playerNpc.isHolding(stack -> stack.getItem() instanceof BowItem);
    }

    private boolean canUseBow() {
        LivingEntity target = this.playerNpc.getTarget();
        return !this.playerNpc.level().isClientSide
                && this.playerNpc.isAlive()
                && !this.playerNpc.isRemoved()
                && !this.playerNpc.isDeadOrDying()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && !this.playerNpc.isClearingCombatObstruction()
                && !this.playerNpc.isOnFire()
                && !this.playerNpc.isInLava()
                && this.playerNpc.isUseBow()
                && this.playerNpc.getSwapToBowCooldown() <= 0
                && InventoryUtils.hasArrowAmmo(this.playerNpc)
                && target != null
                && target.isAlive();
    }

    private boolean hasInventoryBow() {
        return this.playerNpc.hasInventoryItem(stack -> stack.getItem() instanceof BowItem);
    }

    private void equipInventoryBowIfNeeded() {
        if (this.isHoldingBow()) {
            return;
        }
        this.playerNpc.equipTemporaryBowFromInventory();
    }

    private void restorePreviousMainHand() {
        this.playerNpc.restoreMainHandAfterTemporaryBow();
    }
}
