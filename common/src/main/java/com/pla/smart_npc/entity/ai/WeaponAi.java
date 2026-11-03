package com.pla.smart_npc.entity.ai;

import net.minecraft.core.component.DataComponents;

import com.pla.smart_npc.util.SmartNpcItemUtil;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public final class WeaponAi {
    private final PlayerNpcEntity playerNpc;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private MainHandSource currentMainHandSource = MainHandSource.NONE;
    private boolean swappedMainHand;

    public WeaponAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public boolean equipBestMeleeWeapon() {
        WeaponCandidate best = this.bestMeleeWeapon();
        if (best == null || best.stack().isEmpty()) {
            return false;
        }

        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (ItemStack.isSameItemSameComponents(mainHand, best.stack())) {
            this.playerNpc.promoteMainWeaponItem(best.stack());
            return true;
        }

        if (best.source() == MainHandSource.INVENTORY) {
            ItemStack weapon = this.takeInventoryWeapon(best.slot());
            if (weapon.isEmpty()) {
                return false;
            }
            this.swapMainHandWith(weapon, MainHandSource.INVENTORY);
            return true;
        }

        if (best.source() == MainHandSource.MAIN_WEAPON) {
            ItemStack weapon = this.playerNpc.takeMainWeaponItem(stack -> ItemStack.isSameItemSameComponents(stack, best.stack()));
            if (weapon.isEmpty()) {
                return false;
            }
            this.swapMainHandWith(weapon, MainHandSource.MAIN_WEAPON);
            return true;
        }

        if (best.source() == MainHandSource.OFF_WEAPON) {
            ItemStack weapon = this.playerNpc.takeOffWeaponItem(stack -> ItemStack.isSameItemSameComponents(stack, best.stack()));
            if (weapon.isEmpty()) {
                return false;
            }
            this.swapMainHandWith(weapon, MainHandSource.OFF_WEAPON);
            return true;
        }

        return best.source() == MainHandSource.NONE;
    }

    public void restoreMainHand() {
        if (!this.swappedMainHand) {
            return;
        }

        ItemStack current = this.playerNpc.getMainHandItem().copy();
        this.stashCurrentMainHand(current);
        this.playerNpc.setMainHandItemForAi(this.previousMainHand);

        this.previousMainHand = ItemStack.EMPTY;
        this.currentMainHandSource = MainHandSource.NONE;
        this.swappedMainHand = false;
    }

    private WeaponCandidate bestMeleeWeapon() {
        WeaponCandidate best = candidate(this.playerNpc.getMainHandItem(), MainHandSource.NONE, -1);

        ItemStack cachedWeapon = this.playerNpc.getMainWeaponItem();
        if (!cachedWeapon.isEmpty()) {
            best = better(best, candidate(cachedWeapon, MainHandSource.MAIN_WEAPON, -1));
        }

        ItemStack offWeapon = this.playerNpc.getOffWeaponItem();
        if (!offWeapon.isEmpty()) {
            best = better(best, candidate(offWeapon, MainHandSource.OFF_WEAPON, -1));
        }

        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                best = better(best, candidate(stack, MainHandSource.INVENTORY, slot));
            }
        }

        return best;
    }

    private ItemStack takeInventoryWeapon(int slot) {
        SimpleContainer inventory = this.playerNpc.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack weapon = stack.copy();
        weapon.setCount(1);
        stack.shrink(1);
        if (stack.isEmpty()) {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        inventory.setChanged();
        return weapon;
    }

    private void swapMainHandWith(ItemStack stack, MainHandSource source) {
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        this.stashCurrentMainHand(current);
        this.playerNpc.setMainHandItemForAi(stack.copy());
        this.currentMainHandSource = source;
        this.swappedMainHand = true;
    }

    private void stashCurrentMainHand(ItemStack stack) {
        if (!this.swappedMainHand) {
            this.previousMainHand = stack.copy();
            return;
        }

        if (stack.isEmpty()) {
            this.currentMainHandSource = MainHandSource.NONE;
            return;
        }

        if (this.currentMainHandSource == MainHandSource.MAIN_WEAPON) {
            this.playerNpc.cacheMainWeaponItemForAi(stack);
        } else if (this.currentMainHandSource == MainHandSource.OFF_WEAPON) {
            this.playerNpc.setOffWeaponItem(stack);
        } else if (!this.playerNpc.promoteMainWeaponItem(stack)
                && !InventoryUtils.addItem(this.playerNpc.getInventory(), stack.copy())) {
            this.playerNpc.spawnAtLocation(stack.copy());
        }
        this.currentMainHandSource = MainHandSource.NONE;
    }

    private static WeaponCandidate better(WeaponCandidate current, WeaponCandidate candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.score() > current.score()) {
            return candidate;
        }
        return current;
    }

    private static WeaponCandidate candidate(ItemStack stack, MainHandSource source, int slot) {
        double score = weaponScore(stack);
        return score <= 0.0D ? null : new WeaponCandidate(stack.copy(), source, slot, score);
    }

    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        if (stack.is(ItemTags.SWORDS)) {
            return 100.0D + SmartNpcItemUtil.attackDamage(stack) + tierBonus(stack);
        }
        if (stack.getItem() instanceof AxeItem) {
            return 90.0D + SmartNpcItemUtil.attackDamage(stack) + tierBonus(stack);
        }
        return 0.0D;
    }

    private static double tierBonus(ItemStack stack) {
        return stack.has(DataComponents.TOOL) ? stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) * 0.05D : 0.0D;
    }

    private enum MainHandSource {
        NONE,
        INVENTORY,
        MAIN_WEAPON,
        OFF_WEAPON
    }

    private record WeaponCandidate(ItemStack stack, MainHandSource source, int slot, double score) {
    }
}
