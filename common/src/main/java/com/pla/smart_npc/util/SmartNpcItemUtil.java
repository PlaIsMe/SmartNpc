package com.pla.smart_npc.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** Item-category helpers for item types that became data-driven in 26.1. */
public final class SmartNpcItemUtil {
    private SmartNpcItemUtil() {
    }

    @SuppressWarnings("unchecked")
    public static boolean matches(ItemStack stack, Object matcher) {
        if (stack.isEmpty() || matcher == null) {
            return false;
        }
        if (matcher instanceof TagKey<?> tag) {
            return stack.is((TagKey<Item>) tag);
        }
        return matcher instanceof Class<?> type && type.isInstance(stack.getItem());
    }

    public static boolean matches(Object matcher, ItemStack stack) {
        return matches(stack, matcher);
    }

    @SuppressWarnings("unchecked")
    public static boolean matches(Item item, Object matcher) {
        if (item == null || matcher == null) {
            return false;
        }
        if (matcher instanceof TagKey<?> tag) {
            return item.builtInRegistryHolder().is((TagKey<Item>) tag);
        }
        return matcher instanceof Class<?> type && type.isInstance(item);
    }

    public static boolean matches(Object matcher, Item item) {
        return matches(item, matcher);
    }

    public static double attackDamage(ItemStack stack) {
        return com.pla.smart_npc.platform.NpcPlatform.itemAttributes(stack).compute(Attributes.ATTACK_DAMAGE, 0.0D, EquipmentSlot.MAINHAND);
    }

    public static double armorScore(ItemStack stack) {
        return armor(stack) * 3.0D + toughness(stack) * 1.5D;
    }

    public static double armor(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) {
            return 0.0D;
        }
        EquipmentSlot slot = equippable.slot();
        return com.pla.smart_npc.platform.NpcPlatform.itemAttributes(stack).compute(Attributes.ARMOR, 0.0D, slot);
    }

    public static double toughness(ItemStack stack) {
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null) {
            return 0.0D;
        }
        return com.pla.smart_npc.platform.NpcPlatform.itemAttributes(stack).compute(Attributes.ARMOR_TOUGHNESS, 0.0D, equippable.slot());
    }

    public static boolean isArmor(ItemStack stack) {
        return armorScore(stack) > 0.0D;
    }
}
