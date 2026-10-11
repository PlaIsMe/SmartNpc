package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.DangerousReactionAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class EatHealingFoodGoal extends Goal {
    private static final int EAT_TICKS = 32;
    private static final int PATH_RECALCULATE_TICKS = 20;
    private static final double EATING_RETREAT_SPEED = 0.79D;
    private static final double EATING_WANDER_SPEED = 0.55D;
    private static final float REGULAR_FOOD_HEAL_AMOUNT = 4.0F;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;

    private final PlayerNpcEntity playerNpc;
    private final DangerousReactionAi dangerousReactionAi;
    private ItemStack foodStack = ItemStack.EMPTY;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int eatTicks;
    private int pathRecalculateTicks;
    private boolean usingTemporaryFood;
    private boolean finishedEating;
    private boolean wasSprinting;

    public EatHealingFoodGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.dangerousReactionAi = new DangerousReactionAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean isInterruptable() {
        return false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Item use and retained-threat reactions tick cheaply; scans and paths are throttled.
        return true;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getGapCooldown() > 0
                || this.playerNpc.getHealth() >= this.playerNpc.getMaxHealth() * 0.70F) {
            return false;
        }

        return InventoryUtils.selectHealingFood(this.playerNpc, this.playerNpc.getRandom())
                .filter(stack -> !stack.isEmpty())
                .map(stack -> {
                    this.foodStack = stack;
                    return true;
                })
                .orElse(false);
    }

    @Override
    public boolean canContinueToUse() {
        return this.eatTicks > 0
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && this.isHoldingFood()
                && !this.foodStack.isEmpty()
                && this.playerNpc.getHealth() < this.playerNpc.getMaxHealth();
    }

    @Override
    public void start() {
        ItemStack equippedFood = InventoryUtils.consumeItem(this.playerNpc, stack -> ItemStack.isSameItemSameComponents(stack, this.foodStack), 1)
                .orElse(ItemStack.EMPTY);
        if (equippedFood.isEmpty()) {
            this.foodStack = ItemStack.EMPTY;
            return;
        }

        this.eatTicks = EAT_TICKS;
        this.previousMainHand = this.playerNpc.getMainHandItem().copy();
        this.foodStack = equippedFood.copy();
        this.foodStack.setCount(1);
        this.usingTemporaryFood = true;
        this.finishedEating = false;
        this.pathRecalculateTicks = 0;
        this.wasSprinting = this.playerNpc.isSprinting();
        this.playerNpc.setHealing(true);
        this.playerNpc.setSprinting(false);
        this.playerNpc.setCurrentAiState("ai.player_npc.eating");
        this.playerNpc.setMainHandItemForAi(this.foodStack);
        this.playerNpc.startUsingItem(InteractionHand.MAIN_HAND);
        // Epic Fight compatibility is disabled.
        this.updateEatingMovement();
    }

    @Override
    public void stop() {
        if (!this.usingTemporaryFood && !this.finishedEating) {
            return;
        }
        boolean shouldApplyCooldown = this.usingTemporaryFood || this.finishedEating;
        this.playerNpc.stopUsingItem();
        // Epic Fight compatibility is disabled.
        if (this.usingTemporaryFood) {
            ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
            if (!this.finishedEating && this.isSameFood(currentMainHand)) {
                this.giveOrDrop(currentMainHand);
            } else if (!currentMainHand.isEmpty()
                    && !this.isSameFood(currentMainHand)
                    && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)) {
                this.giveOrDrop(currentMainHand);
            }
            this.playerNpc.setMainHandItemForAi(this.previousMainHand);
        }

        this.eatTicks = 0;
        this.pathRecalculateTicks = 0;
        this.foodStack = ItemStack.EMPTY;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryFood = false;
        this.finishedEating = false;
        this.dangerousReactionAi.stop();
        this.playerNpc.setSprinting(this.wasSprinting);
        this.wasSprinting = false;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setHealing(false);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");

        if (shouldApplyCooldown) {
            this.playerNpc.setGapCooldown();
        }
    }

    @Override
    public void tick() {
        if (this.eatTicks <= 0) {
            return;
        }
        if (!this.canContinueToUse()) {
            this.stop();
            return;
        }
        // Epic Fight compatibility is disabled.

        if (this.eatTicks % 8 == 0) {
            this.playerNpc.level().playSound(null, this.playerNpc.blockPosition(), SoundEvents.GENERIC_EAT.value(), SoundSource.HOSTILE, 0.8F, 1.0F);
        }

        this.updateEatingMovement();

        this.eatTicks--;
        if (this.eatTicks > 0) {
            return;
        }

        ItemStack heldFood = this.playerNpc.getMainHandItem();
        ItemStack eatenFood = this.foodStack.copy();
        eatenFood.setCount(1);
        if (this.isSameFood(heldFood)) {
            heldFood.shrink(1);
        }
        this.finishedEating = true;

        if (eatenFood.is(Items.GOLDEN_APPLE)) {
            this.playerNpc.heal(this.getHealAmount(eatenFood));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 0));
        } else if (eatenFood.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            this.playerNpc.heal(this.getHealAmount(eatenFood));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 400, 1));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 3));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 6000, 0));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        } else {
            this.healDirectly(REGULAR_FOOD_HEAL_AMOUNT);
        }
        this.playerNpc.level().playSound(null, this.playerNpc.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 0.5F, 1.0F);
        this.stop();
    }

    private void healDirectly(float amount) {
        if (amount <= 0.0F) {
            return;
        }
        this.playerNpc.setHealth(Math.min(this.playerNpc.getMaxHealth(), this.playerNpc.getHealth() + amount));
    }

    private boolean isHoldingFood() {
        return this.isSameFood(this.playerNpc.getMainHandItem());
    }

    private boolean isSameFood(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, this.foodStack);
    }

    private void updateEatingMovement() {
        Entity threat = this.dangerousReactionAi.findThreat(true);
        if (threat != null) {
            this.dangerousReactionAi.tickRetreat(threat, EATING_RETREAT_SPEED, true);
            this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
            return;
        }
        this.dangerousReactionAi.tickRetreat(null, EATING_RETREAT_SPEED, false);
        if (this.pathRecalculateTicks-- > 0) {
            return;
        }
        this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
        this.playerNpc.setSprinting(false);
        Vec3 movePos = DefaultRandomPos.getPos(this.playerNpc, 8, 4);
        if (movePos == null) {
            return;
        }
        Path path = PathNavigationAi.createBoundedPath(this.playerNpc, BlockPos.containing(movePos), PATH_NODE_MULTIPLIER);
        if (path != null && path.getNodeCount() > 0) {
            this.playerNpc.getNavigation().moveTo(path, EATING_WANDER_SPEED);
        }
        this.playerNpc.getLookControl().setLookAt(movePos.x, movePos.y, movePos.z, 45.0F, 45.0F);
    }

    private void giveOrDrop(ItemStack stack) {
        ItemStack remainder = InventoryUtils.addItemAndReturnRemainder(this.playerNpc, stack);
        if (!remainder.isEmpty()) {
            this.playerNpc.spawnAtLocation(remainder);
        }
    }

    private float getHealAmount(ItemStack foodStack) {
        if (foodStack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            return 12.0F;
        }
        if (foodStack.is(Items.GOLDEN_APPLE)) {
            return 8.0F;
        }
        return REGULAR_FOOD_HEAL_AMOUNT;
    }
}
