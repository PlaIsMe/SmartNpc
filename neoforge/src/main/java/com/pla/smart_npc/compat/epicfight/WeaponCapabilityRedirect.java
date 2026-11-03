package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcEpicFightConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.WeaponTypeReloadListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public final class WeaponCapabilityRedirect {
    private static final Set<String> WARNED_INVALID_PRESETS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private WeaponCapabilityRedirect() {
    }

    public static CapabilityItem resolve(ItemStack heldStack, CapabilityItem originalCapability) {
        if (heldStack == null || heldStack.isEmpty()) {
            return originalCapability;
        }

        Item sourceItem = heldStack.getItem();
        ResourceLocation sourceItemId = BuiltInRegistries.ITEM.getKey(sourceItem);
        ResourceLocation targetPresetId = SmartNpcEpicFightConfig.getWeaponCapabilityRedirect(sourceItemId);
        if (targetPresetId == null) {
            return originalCapability;
        }

        Function<Item, ? extends CapabilityItem.Builder<?>> preset = WeaponTypeReloadListener.get(targetPresetId);
        if (preset == null) {
            warnMissingPreset(sourceItemId, targetPresetId);
            return originalCapability;
        }

        CacheKey cacheKey = new CacheKey(sourceItem, targetPresetId);
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && cached.presetFactory() == preset) {
            return cached.capability();
        }

        try {
            CapabilityItem redirected = preset.apply(sourceItem).build();
            if (redirected == null) {
                warnMissingPreset(sourceItemId, targetPresetId);
                return originalCapability;
            }
            CACHE.put(cacheKey, new CacheEntry(preset, redirected));
            return redirected;
        } catch (RuntimeException exception) {
            String warningKey = sourceItemId + "->" + targetPresetId + ":build";
            if (WARNED_INVALID_PRESETS.add(warningKey)) {
                SmartNpc.LOGGER.warn(
                        "Could not build redirected Epic Fight capability {} for Smart NPC item {}; using the item's original capability.",
                        targetPresetId,
                        sourceItemId,
                        exception
                );
            }
            return originalCapability;
        }
    }

    private static void warnMissingPreset(ResourceLocation sourceItemId, ResourceLocation targetPresetId) {
        String warningKey = sourceItemId + "->" + targetPresetId;
        if (WARNED_INVALID_PRESETS.add(warningKey)) {
            SmartNpc.LOGGER.warn(
                    "Smart NPC Epic Fight redirect for {} points to unknown weapon capability preset {}; using the item's original capability.",
                    sourceItemId,
                    targetPresetId
            );
        }
    }

    private record CacheKey(Item sourceItem, ResourceLocation targetPresetId) {
    }

    private record CacheEntry(
            Function<Item, ? extends CapabilityItem.Builder<?>> presetFactory,
            CapabilityItem capability
    ) {
    }
}
