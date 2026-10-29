package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.common.ToolActions;

import java.util.EnumSet;

public class ShieldGuardGoal extends Goal {
    private static final double GUARD_MOVE_SPEED = 0.45D;
    private static final double APPROACH_DISTANCE_SQR = 7.0D * 7.0D;
    private static final int MIN_GUARD_TICKS = 24;
    private static final int MAX_GUARD_TICKS = 56;
    private static final int COOLDOWN_TICKS = 80;
    private static final int MOVEMENT_REPATH_TICKS = 10;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;

    private final PlayerNpcEntity playerNpc;
    private LivingEntity target;
    private ItemStack previousOffhand = ItemStack.EMPTY;
    private int guardTicks;
    private int movementRepathTicks;
    private boolean usingTemporaryShield;
    private boolean wasSprinting;

    public ShieldGuardGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.isClearingCombatObstruction()
                || this.playerNpc.getShieldGuardCooldown() > 0
                || !this.hasShield()) {
            return false;
        }

        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (currentTarget == null || !currentTarget.isAlive()) {
            return false;
        }

        float chance = this.isRangedThreat(currentTarget) ? 0.62F : 0.18F;
        if (this.playerNpc.getRandom().nextFloat() > chance) {
            return false;
        }

        this.target = currentTarget;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.guardTicks > 0
                && !this.playerNpc.isClearingCombatObstruction()
                && this.playerNpc.getShieldGuardCooldown() <= 0
                && this.target != null
                && this.target.isAlive()
                && this.playerNpc.isAlive()
                && this.isHoldingShield();
    }

    @Override
    public void start() {
        if (!this.equipShieldIfNeeded()) {
            this.target = null;
            return;
        }

        this.guardTicks = MIN_GUARD_TICKS + this.playerNpc.getRandom().nextInt(MAX_GUARD_TICKS - MIN_GUARD_TICKS + 1);
        this.movementRepathTicks = 0;
        this.wasSprinting = this.playerNpc.isSprinting();
        this.playerNpc.setSprinting(false);
        this.playerNpc.setCurrentAiState("ai.player_npc.shield_guarding");
        if (this.target != null) {
            this.playerNpc.setCurrentAiDetail(this.target.getDisplayName().getString());
        }
        this.playerNpc.startUsingItem(InteractionHand.OFF_HAND);
        this.updateMovement();
    }

    @Override
    public void tick() {
        if (this.playerNpc.getShieldGuardCooldown() > 0 || !this.isHoldingShield()) {
            return;
        }
        this.guardTicks--;
        this.playerNpc.setSprinting(false);
        if (!this.playerNpc.isUsingItem() || this.playerNpc.getUsedItemHand() != InteractionHand.OFF_HAND) {
            this.playerNpc.startUsingItem(InteractionHand.OFF_HAND);
        }
        this.updateMovement();
    }

    @Override
    public void stop() {
        this.playerNpc.stopUsingItem();
        if (this.usingTemporaryShield) {
            ItemStack shield = this.playerNpc.getOffhandItem().copy();
            if (!shield.isEmpty() && shield.canPerformAction(ToolActions.SHIELD_BLOCK)) {
                ItemStack remainder = InventoryUtils.addItemAndReturnRemainder(this.playerNpc, shield);
                if (!remainder.isEmpty()) {
                    this.playerNpc.spawnAtLocation(remainder);
                }
            }
            this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, this.previousOffhand.copy());
        }

        this.playerNpc.setSprinting(this.wasSprinting);
        this.wasSprinting = false;
        this.target = null;
        this.previousOffhand = ItemStack.EMPTY;
        this.guardTicks = 0;
        this.movementRepathTicks = 0;
        this.usingTemporaryShield = false;
        this.playerNpc.setShieldGuardCooldown(Math.max(this.playerNpc.getShieldGuardCooldown(),
                COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 8)));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void updateMovement() {
        if (this.target == null) {
            this.playerNpc.getNavigation().stop();
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.target, 70.0F, 70.0F);
        if (this.playerNpc.distanceToSqr(this.target) > APPROACH_DISTANCE_SQR) {
            if (this.movementRepathTicks-- <= 0) {
                Path path = PathNavigationAi.createBoundedPath(
                        this.playerNpc,
                        this.target.blockPosition(),
                        PATH_NODE_MULTIPLIER
                );
                if (path != null && path.getNodeCount() > 0) {
                    this.playerNpc.getNavigation().moveTo(path, GUARD_MOVE_SPEED);
                }
                this.movementRepathTicks = MOVEMENT_REPATH_TICKS;
            }
        } else {
            this.playerNpc.getNavigation().stop();
            this.movementRepathTicks = 0;
        }
    }

    private boolean hasShield() {
        return this.isHoldingShield()
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.canPerformAction(ToolActions.SHIELD_BLOCK));
    }

    private boolean isHoldingShield() {
        return this.playerNpc.getOffhandItem().canPerformAction(ToolActions.SHIELD_BLOCK);
    }

    private boolean equipShieldIfNeeded() {
        if (this.isHoldingShield()) {
            return true;
        }

        ItemStack shield = this.playerNpc.consumeInventoryItem(stack -> stack.canPerformAction(ToolActions.SHIELD_BLOCK), 1).orElse(ItemStack.EMPTY);
        if (shield.isEmpty()) {
            return false;
        }

        this.previousOffhand = this.playerNpc.getOffhandItem().copy();
        this.usingTemporaryShield = true;
        this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, shield);
        return true;
    }

    private boolean isRangedThreat(LivingEntity entity) {
        return entity.isUsingItem() && this.isRangedWeapon(entity.getUseItem())
                || this.isRangedWeapon(entity.getMainHandItem())
                || this.isRangedWeapon(entity.getOffhandItem());
    }

    private boolean isRangedWeapon(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof ProjectileWeaponItem);
    }
}
