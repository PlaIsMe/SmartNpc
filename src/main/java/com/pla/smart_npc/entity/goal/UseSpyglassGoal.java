package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;

public class UseSpyglassGoal extends Goal {
    private static final int MIN_USE_TICKS = 50;
    private static final int MAX_USE_TICKS = 110;
    private static final int COOLDOWN_TICKS = 20 * 80;
    private static final double LOOK_RANGE = 32.0D;

    private final PlayerNpcEntity playerNpc;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private LivingEntity lookTarget;
    private Vec3 lookPos;
    private int useTicks;
    private boolean usingTemporarySpyglass;

    public UseSpyglassGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.LOOK, Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getSpyglassCooldown() > 0
                || this.playerNpc.getRandom().nextFloat() > 0.035F
                || !this.hasSpyglass()) {
            return false;
        }

        this.lookTarget = this.findLookTarget();
        this.lookPos = this.lookTarget == null ? this.randomLookPos() : null;
        return this.lookTarget != null || this.lookPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.useTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.isHoldingSpyglass();
    }

    @Override
    public void start() {
        if (!this.equipSpyglassIfNeeded()) {
            this.lookTarget = null;
            this.lookPos = null;
            return;
        }

        this.useTicks = MIN_USE_TICKS + this.playerNpc.getRandom().nextInt(MAX_USE_TICKS - MIN_USE_TICKS + 1);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.using_spyglass");
        this.playerNpc.startUsingItem(InteractionHand.MAIN_HAND);
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
    }

    @Override
    public void tick() {
        this.useTicks--;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.startUsingItem(InteractionHand.MAIN_HAND);

        if (this.lookTarget != null && this.lookTarget.isAlive()) {
            this.playerNpc.getLookControl().setLookAt(this.lookTarget, 60.0F, 60.0F);
            this.playerNpc.setCurrentAiDetail(this.lookTarget.getDisplayName().getString());
        } else if (this.lookPos != null) {
            this.playerNpc.getLookControl().setLookAt(this.lookPos.x, this.lookPos.y, this.lookPos.z, 60.0F, 60.0F);
            this.playerNpc.setCurrentAiDetail("scanning");
        }
    }

    @Override
    public void stop() {
        this.playerNpc.stopUsingItem();
        if (this.usingTemporarySpyglass) {
            ItemStack spyglass = this.playerNpc.getMainHandItem().copy();
            if (!spyglass.isEmpty() && spyglass.is(Items.SPYGLASS) && !InventoryUtils.addItem(this.playerNpc, spyglass)) {
                this.playerNpc.spawnAtLocation(spyglass);
            }
            this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, this.previousMainHand.copy());
        }

        this.previousMainHand = ItemStack.EMPTY;
        this.lookTarget = null;
        this.lookPos = null;
        this.useTicks = 0;
        this.usingTemporarySpyglass = false;
        this.playerNpc.setSpyglassCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 100));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean hasSpyglass() {
        return this.playerNpc.getMainHandItem().is(Items.SPYGLASS)
                || InventoryUtils.hasItem(this.playerNpc, Items.SPYGLASS);
    }

    private boolean isHoldingSpyglass() {
        return this.playerNpc.getMainHandItem().is(Items.SPYGLASS);
    }

    private boolean equipSpyglassIfNeeded() {
        if (this.isHoldingSpyglass()) {
            return true;
        }

        ItemStack spyglass = this.playerNpc.consumeInventoryItem(Items.SPYGLASS, 1).orElse(ItemStack.EMPTY);
        if (spyglass.isEmpty()) {
            return false;
        }

        this.previousMainHand = this.playerNpc.getMainHandItem().copy();
        this.usingTemporarySpyglass = true;
        this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, spyglass);
        return true;
    }

    private LivingEntity findLookTarget() {
        AABB area = this.playerNpc.getBoundingBox().inflate(LOOK_RANGE, 12.0D, LOOK_RANGE);
        return this.playerNpc.level()
                .getEntitiesOfClass(LivingEntity.class, area, entity -> entity != this.playerNpc && entity.isAlive())
                .stream()
                .max(Comparator.comparingDouble(this.playerNpc::distanceToSqr))
                .orElse(null);
    }

    private Vec3 randomLookPos() {
        double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
        double distance = 16.0D + this.playerNpc.getRandom().nextDouble() * 16.0D;
        return this.playerNpc.position().add(Math.cos(angle) * distance, 3.0D + this.playerNpc.getRandom().nextDouble() * 8.0D, Math.sin(angle) * distance);
    }
}
