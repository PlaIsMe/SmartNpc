package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.PlayerNpcFishingBobberEntity;
import com.pla.smart_npc.entity.ai.CombatMovementAi;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;

/** Offhand combat support; the active melee goal retains movement and looking. */
public class CombatFishingRodGoal extends Goal {
    private static final double MIN_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double MAX_DISTANCE_SQR = 15.0D * 15.0D;
    private static final int MAX_CAST_TICKS = 40;
    private static final int HOOK_HOLD_TICKS = 8;
    private static final int COOLDOWN_TICKS = 20 * 12;

    private final PlayerNpcEntity playerNpc;
    private LivingEntity target;
    private WrappedGoal pursuitGoal;
    private PlayerNpcFishingBobberEntity bobber;
    private ItemStack previousOffhand = ItemStack.EMPTY;
    private ItemStack equippedRod = ItemStack.EMPTY;
    private boolean usingTemporaryRod;
    private int nextAttemptTick;
    private int castUntilTick;
    private int retrieveAtTick;

    public CombatFishingRodGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        // No MOVE/LOOK flags: a running melee pursuit is required instead.
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.tickCount < this.nextAttemptTick) {
            return false;
        }
        this.nextAttemptTick = this.playerNpc.tickCount + 20 + this.playerNpc.getRandom().nextInt(20);
        this.pursuitGoal = null;
        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (!this.canSupportPursuit(currentTarget, true) || this.playerNpc.getCombatFishingCooldown() > 0) {
            return false;
        }
        double distanceSqr = this.playerNpc.distanceToSqr(currentTarget);
        if (distanceSqr < MIN_DISTANCE_SQR || distanceSqr > MAX_DISTANCE_SQR
                || this.playerNpc.getRandom().nextFloat() > 0.22F
                || !this.playerNpc.getSensing().hasLineOfSight(currentTarget)
                || !this.hasFishingRod()) {
            return false;
        }
        this.target = currentTarget;
        return true;
    }

    private boolean canSupportPursuit(LivingEntity currentTarget, boolean selectPursuit) {
        if (!(this.playerNpc.level() instanceof ServerLevel) || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi() || this.playerNpc.isPassenger() || this.playerNpc.isHealing()
                || this.playerNpc.isUsingItem() || this.playerNpc.isSleeping()
                || this.playerNpc.isClearingCombatObstruction()
                || this.playerNpc.hasInterest(PlayerNpcInterest.CAUTIOUS)
                || currentTarget == null || !currentTarget.isAlive() || currentTarget.isRemoved()
                || currentTarget != this.playerNpc.getTarget() || currentTarget.level() != this.playerNpc.level()
                || this.playerNpc.isAlliedTo(currentTarget) || currentTarget.isAlliedTo(this.playerNpc)
                || currentTarget instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        if (selectPursuit) {
            for (WrappedGoal goal : this.playerNpc.goalSelector.getAvailableGoals()) {
                if (goal.isRunning() && goal.getGoal() instanceof CombatMovementAi.PursuitGoal pursuit
                        && pursuit.canSupportCombatFishing()) {
                    this.pursuitGoal = goal;
                    break;
                }
            }
        }
        return this.pursuitGoal != null && this.pursuitGoal.isRunning()
                && ((CombatMovementAi.PursuitGoal) this.pursuitGoal.getGoal()).canSupportCombatFishing();
    }

    @Override
    public boolean canContinueToUse() {
        return this.bobber != null && !this.bobber.isRemoved()
                && this.playerNpc.tickCount < this.castUntilTick && this.canSupportPursuit(this.target, false)
                && this.playerNpc.getOffhandItem() == this.equippedRod && !this.equippedRod.isEmpty()
                && this.playerNpc.distanceToSqr(this.target) <= 32.0D * 32.0D;
    }

    @Override
    public void start() {
        this.playerNpc.setFishingRodCast(InteractionHand.OFF_HAND, false);
        if (!this.canSupportPursuit(this.target, false) || !this.equipRodIfNeeded()) {
            return;
        }
        PlayerNpcFishingBobberEntity nextBobber = SmartNpcModEntities.PLAYER_NPC_FISHING_BOBBER.get()
                .create(this.playerNpc.level());
        if (nextBobber == null) {
            return;
        }
        nextBobber.castAtCombatTarget(this.playerNpc, this.target);
        if (!this.playerNpc.level().addFreshEntity(nextBobber)) {
            return;
        }
        this.bobber = nextBobber;
        this.castUntilTick = this.playerNpc.tickCount + MAX_CAST_TICKS;
        this.retrieveAtTick = 0;
        this.playerNpc.setFishingRodCast(InteractionHand.OFF_HAND, true);
        this.playerNpc.swing(InteractionHand.OFF_HAND, true);
        this.playerNpc.playSound(SoundEvents.FISHING_BOBBER_THROW, 1.0F, 1.0F);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Only current-goal/equipment guards, a timer and the existing bobber are inspected.
        return true;
    }

    @Override
    public void tick() {
        if (!this.canContinueToUse()) {
            this.discardBobber();
            return;
        }
        this.bobber.setInUse();
        if (!this.bobber.isHookedTo(this.target)) {
            return;
        }
        if (this.retrieveAtTick == 0) {
            this.retrieveAtTick = this.playerNpc.tickCount + HOOK_HOLD_TICKS;
        }
        if (this.playerNpc.tickCount < this.retrieveAtTick) {
            return;
        }
        int damage = this.bobber.retrieveCombat(this.target);
        this.bobber = null;
        this.playerNpc.setFishingRodCast(InteractionHand.OFF_HAND, false);
        if (damage > 0) {
            this.playerNpc.hurtItemInHand(InteractionHand.OFF_HAND, damage);
        }
        this.playerNpc.swing(InteractionHand.OFF_HAND, true);
        this.playerNpc.level().playSound(null, this.playerNpc.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE,
                SoundSource.HOSTILE, 0.9F, 1.0F);
    }

    @Override
    public void stop() {
        this.discardBobber();
        if (this.usingTemporaryRod) {
            if (this.playerNpc.getOffhandItem() == this.equippedRod
                    || this.equippedRod.isEmpty() && this.playerNpc.getOffhandItem().isEmpty()) {
                ItemStack rod = this.playerNpc.getOffhandItem();
                this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, this.previousOffhand);
                this.storeOrDrop(rod);
            } else {
                // A higher-priority action owns this hand now; never overwrite its item.
                this.storeOrDrop(this.previousOffhand);
            }
        }
        this.target = null;
        this.pursuitGoal = null;
        this.previousOffhand = ItemStack.EMPTY;
        this.equippedRod = ItemStack.EMPTY;
        this.usingTemporaryRod = false;
        this.castUntilTick = 0;
        this.retrieveAtTick = 0;
        this.playerNpc.setCombatFishingCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 18));
    }

    private void discardBobber() {
        this.playerNpc.setFishingRodCast(InteractionHand.OFF_HAND, false);
        if (this.bobber != null) {
            this.bobber.discard();
            this.bobber = null;
        }
    }

    private void storeOrDrop(ItemStack stack) {
        ItemStack remainder = InventoryUtils.addItemAndReturnRemainder(this.playerNpc, stack);
        if (!remainder.isEmpty()) {
            this.playerNpc.spawnAtLocation(remainder);
        }
    }

    private boolean hasFishingRod() {
        return this.playerNpc.getOffhandItem().getItem() instanceof FishingRodItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof FishingRodItem);
    }

    private boolean equipRodIfNeeded() {
        if (!(this.playerNpc.getOffhandItem().getItem() instanceof FishingRodItem)) {
            ItemStack rod = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof FishingRodItem, 1)
                    .orElse(ItemStack.EMPTY);
            if (rod.isEmpty()) {
                return false;
            }
            this.previousOffhand = this.playerNpc.getOffhandItem();
            this.usingTemporaryRod = true;
            this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, rod);
        }
        this.equippedRod = this.playerNpc.getOffhandItem();
        return true;
    }
}
