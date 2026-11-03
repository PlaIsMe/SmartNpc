package com.pla.smart_npc.entity.goal;

import net.minecraft.core.component.DataComponents;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcTrashUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;
import java.util.List;

public class RecoverWeaponInCombatGoal extends Goal {
    private final Mob mob;
    private final double speed;
    private final double searchRadius;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(10);

    private ItemEntity targetItem;
    private int inventoryWeaponSlot = -1;
    private boolean restoreCachedWeapon;
    private boolean finished;

    private LivingEntity savedCombatTarget;
    private int lockTicks;
    private int repathCooldown;

    private static final int MAX_LOCK_TICKS = 60;
    private static final int REPATH_INTERVAL_TICKS = 10;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;

    public RecoverWeaponInCombatGoal(Mob mob, double speed, double searchRadius) {
        this.mob = mob;
        this.speed = speed;
        this.searchRadius = searchRadius;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean isInterruptable() {
        return false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide()) return false;
        if (!mob.isAlive() || mob.isRemoved() || mob.isDeadOrDying()) return false;
        if (mob.isPassenger()) return false;
        if (mob.isNoAi()) return false;
        if (mob instanceof PlayerNpcEntity playerNpcEntity && playerNpcEntity.isMainHandReservedForAi()) return false;

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }

        if (!mainWeaponIsEmpty()) {
            return false;
        }
        if (mob instanceof PlayerNpcEntity playerNpcEntity
                && !this.canUseThrottle.canCheck(playerNpcEntity)) {
            return false;
        }

        inventoryWeaponSlot = findWeaponSlotInNpcInventory();
        if (inventoryWeaponSlot >= 0) {
            restoreCachedWeapon = false;
            targetItem = null;
            return true;
        }

        restoreCachedWeapon = !getCachedMainWeapon().isEmpty();
        if (restoreCachedWeapon) {
            targetItem = null;
            inventoryWeaponSlot = -1;
            return true;
        }

        if (mob instanceof PlayerNpcEntity playerNpcEntity
                && playerNpcEntity.isItemPickupSuppressed()) {
            return false;
        }

        targetItem = findNearestWeaponItem();
        return targetItem != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (finished) return false;

        if (mob.level().isClientSide()) return false;
        if (!mob.isAlive() || mob.isRemoved() || mob.isDeadOrDying()) return false;
        if (mob.isPassenger()) return false;
        if (mob.isNoAi()) return false;
        if (mob instanceof PlayerNpcEntity playerNpcEntity && playerNpcEntity.isMainHandReservedForAi()) return false;

        if (!mainWeaponIsEmpty()) {
            return false;
        }

        if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
            return false;
        }
        if (mob instanceof PlayerNpcEntity playerNpcEntity
                && playerNpcEntity.isItemPickupSuppressed()) {
            return false;
        }

        return lockTicks < MAX_LOCK_TICKS;
    }

    @Override
    public void start() {
        this.savedCombatTarget = mob.getTarget();
        this.lockTicks = 0;
        this.repathCooldown = 0;
        this.finished = false;

        if (restoreCachedWeapon && restoreCachedMainWeapon()) {
            this.finished = true;
            this.targetItem = null;
            this.restoreCachedWeapon = false;
            return;
        }

        if (inventoryWeaponSlot >= 0 && tryEquipWeaponFromInventory(inventoryWeaponSlot)) {
            this.finished = true;
            this.targetItem = null;
            return;
        }

        inventoryWeaponSlot = -1;

        if (mob instanceof PlayerNpcEntity playerNpcEntity
                && playerNpcEntity.isItemPickupSuppressed()) {
            targetItem = null;
        } else if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
            targetItem = findNearestWeaponItem();
        }

        if (targetItem != null) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            this.moveToTargetItem();
            this.repathCooldown = REPATH_INTERVAL_TICKS;
        }
    }

    private int findWeaponSlotInNpcInventory() {
        SimpleContainer inventory = getNpcInventory();

        if (inventory == null) {
            return -1;
        }

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && isUsefulWeapon(stack)) {
                return i;
            }
        }

        return -1;
    }

    private boolean tryEquipWeaponFromInventory(int slot) {
        SimpleContainer inventory = getNpcInventory();

        if (inventory == null) {
            return false;
        }
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return false;
        }
        if (!mob.getMainHandItem().isEmpty()) {
            return false;
        }
        ItemStack slotStack = inventory.getItem(slot);
        if (slotStack.isEmpty() || !isUsefulWeapon(slotStack)) {
            return false;
        }
        ItemStack equipStack = slotStack.split(1);
        if (slotStack.isEmpty()) {
            inventory.setItem(slot, ItemStack.EMPTY);
        } else {
            inventory.setItem(slot, slotStack);
        }
        inventory.setChanged();
        return equipRecoveredWeapon(equipStack, true);
    }

    private boolean equipRecoveredWeapon(ItemStack equipStack, boolean playEquipFeedback) {
        if (equipStack.isEmpty() || !isUsefulWeapon(equipStack)) {
            return false;
        }

        equipStack.setCount(1);
        mob.setItemSlot(EquipmentSlot.MAINHAND, equipStack.copy());
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setMainWeaponItem(equipStack.copy());
            playerNpcEntity.setMainWeaponDisarmed(false);
        }

        if (playEquipFeedback) {
            mob.swing(InteractionHand.MAIN_HAND);
            mob.level().playSound(
                    null,
                    mob.blockPosition(),
                    SoundEvents.ITEM_PICKUP,
                    SoundSource.HOSTILE,
                    0.35F,
                    1.0F
            );
        }

        return true;
    }

    private boolean restoreCachedMainWeapon() {
        ItemStack weapon = getCachedMainWeapon();

        if (weapon.isEmpty()) {
            return false;
        }

        mob.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());

        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setMainWeaponItem(weapon.copy());
            playerNpcEntity.setMainWeaponDisarmed(false);
        }

        return true;
    }

    private SimpleContainer getNpcInventory() {
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            return playerNpcEntity.getInventory();
        }

        return null;
    }

    @Override
    public void tick() {
        lockTicks++;

        if (mob instanceof PlayerNpcEntity playerNpcEntity
                && playerNpcEntity.isItemPickupSuppressed()) {
            targetItem = null;
            finished = true;
            mob.getNavigation().stop();
            return;
        }

        if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
            return;
        }

        mob.setTarget(null);

        mob.getLookControl().setLookAt(
                targetItem.getX(),
                targetItem.getY() + targetItem.getBbHeight() * 0.5D,
                targetItem.getZ(),
                60.0F,
                60.0F
        );

        if (this.isWithinItemPickupReach(targetItem)) {
            if (forceEquipWeaponFromItemEntity(targetItem)) {
                finished = true;
            }
            targetItem = null;
            return;
        }

        if (repathCooldown-- <= 0) {
            repathCooldown = REPATH_INTERVAL_TICKS;

            this.moveToTargetItem();
        }
    }

    private void moveToTargetItem() {
        if (this.targetItem == null) {
            return;
        }
        if (this.mob instanceof PlayerNpcEntity playerNpc) {
            if (playerNpc.isItemPickupSuppressed()) {
                this.targetItem = null;
                this.mob.getNavigation().stop();
                return;
            }
            Path path = PathNavigationAi.createBoundedPath(
                    playerNpc,
                    BlockPos.containing(this.targetItem.getX(), this.targetItem.getY(), this.targetItem.getZ()),
                    PATH_NODE_MULTIPLIER
            );
            if (path != null && path.getNodeCount() > 0) {
                this.mob.getNavigation().moveTo(path, this.speed);
            }
            return;
        }
        this.mob.getNavigation().moveTo(this.targetItem, this.speed);
    }

    private boolean forceEquipWeaponFromItemEntity(ItemEntity itemEntity) {
        if (itemEntity == null || !itemEntity.isAlive()) {
            return false;
        }
        if (mob instanceof PlayerNpcEntity playerNpcEntity
                && playerNpcEntity.isItemPickupSuppressed()) {
            return false;
        }
        if (!mob.getMainHandItem().isEmpty()) {
            return false;
        }
        ItemStack groundStack = itemEntity.getItem();
        if (groundStack.isEmpty() || PlayerNpcTrashUtil.isDiscarded(groundStack) || !isUsefulWeapon(groundStack)) {
            return false;
        }
        ItemStack equipStack = groundStack.copy();
        equipStack.setCount(1);
        if (!equipRecoveredWeapon(equipStack, false)) {
            return false;
        }
        mob.onItemPickup(itemEntity);
        mob.take(itemEntity, 1);
        groundStack.shrink(1);
        if (groundStack.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(groundStack);
        }
        return true;
    }

    private boolean isWithinItemPickupReach(ItemEntity itemEntity) {
        if (itemEntity == null || itemEntity.hasPickUpDelay()) {
            return false;
        }
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            return playerNpcEntity.isWithinPlayerLikeItemPickupReach(itemEntity);
        }
        return mob.getBoundingBox().intersects(itemEntity.getBoundingBox());
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();

        if (savedCombatTarget != null && savedCombatTarget.isAlive()) {
            mob.setTarget(savedCombatTarget);
        }

        savedCombatTarget = null;
        targetItem = null;
        inventoryWeaponSlot = -1;
        lockTicks = 0;
        repathCooldown = 0;
        restoreCachedWeapon = false;
        finished = false;
    }

    private ItemEntity findNearestWeaponItem() {
        if (mob instanceof PlayerNpcEntity playerNpcEntity
                && playerNpcEntity.isItemPickupSuppressed()) {
            return null;
        }
        List<ItemEntity> items = mob.level().getEntitiesOfClass(
                ItemEntity.class,
                mob.getBoundingBox().inflate(searchRadius),
                itemEntity -> itemEntity.isAlive()
                        && !itemEntity.hasPickUpDelay()
                        && !itemEntity.getItem().isEmpty()
                        && !PlayerNpcTrashUtil.isDiscarded(itemEntity.getItem())
                        && isUsefulWeapon(itemEntity.getItem())
        );

        if (items.isEmpty()) {
            return null;
        }

        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (ItemEntity itemEntity : items) {
            double distance = mob.distanceToSqr(itemEntity);

            if (distance < bestDistance) {
                bestDistance = distance;
                best = itemEntity;
            }
        }

        return best;
    }

    private boolean mainWeaponIsEmpty() {
        return mob.getMainHandItem().isEmpty()
                || isFlintAndSteel(mob.getMainHandItem());
    }

    private ItemStack getCachedMainWeapon() {
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            return playerNpcEntity.getMainWeaponItem();
        }

        return ItemStack.EMPTY;
    }

    private boolean isFlintAndSteel(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == Items.FLINT_AND_STEEL;
    }

    private boolean isUsefulWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        Item item = stack.getItem();

        return item.builtInRegistryHolder().is(ItemTags.SWORDS)
                || item.components().has(DataComponents.TOOL)
                || item instanceof TridentItem;
    }
}
