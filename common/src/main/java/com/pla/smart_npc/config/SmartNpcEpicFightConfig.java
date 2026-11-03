package com.pla.smart_npc.config;

import com.pla.smart_npc.SmartNpc;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SmartNpcEpicFightConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WEAPON_CAPABILITY_REDIRECTS;

    private static volatile List<? extends String> cachedRawEntries = List.of();
    private static volatile Map<ResourceLocation, ResourceLocation> cachedRedirects = Map.of();

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("advancedMobPatch");
        WEAPON_CAPABILITY_REDIRECTS = builder.comment(
                        "Redirect an item's Epic Fight weapon capability preset only for Smart NPC combat.",
                        "Format: source_item_id;target_weapon_capability_preset_id",
                        "The held item is not replaced. Only the Epic Fight capability/moveset used by the NPC is redirected.",
                        "Example: wom:agony;epicfight:spear")
                .defineList("weaponCapabilityRedirects", List.of(
                        "wom:agony;epicfight:spear",
                        "wom:ender_slayer_scythe;epicfight:spear",
                        "wom:enderblaster;epicfight:fist"
                ), value -> value instanceof String);
        builder.pop();
        SPEC = builder.build();
    }

    private SmartNpcEpicFightConfig() {
    }

    public static ResourceLocation getWeaponCapabilityRedirect(ResourceLocation sourceItemId) {
        if (sourceItemId == null) {
            return null;
        }
        refreshCacheIfNeeded();
        return cachedRedirects.get(sourceItemId);
    }

    private static void refreshCacheIfNeeded() {
        List<? extends String> rawEntries = WEAPON_CAPABILITY_REDIRECTS.get();
        if (rawEntries == cachedRawEntries || rawEntries.equals(cachedRawEntries)) {
            return;
        }

        synchronized (SmartNpcEpicFightConfig.class) {
            rawEntries = WEAPON_CAPABILITY_REDIRECTS.get();
            if (rawEntries == cachedRawEntries || rawEntries.equals(cachedRawEntries)) {
                return;
            }

            Map<ResourceLocation, ResourceLocation> parsed = new LinkedHashMap<>();
            for (String rawEntry : rawEntries) {
                if (rawEntry == null) {
                    continue;
                }

                // Accept both normal ResourceLocation text and the escaped-colon form
                // people often paste from config/documentation examples (wom\:agony).
                String entry = rawEntry.trim().replace("\\:", ":");
                int separator = entry.indexOf(';');
                if (separator <= 0 || separator != entry.lastIndexOf(';') || separator >= entry.length() - 1) {
                    SmartNpc.LOGGER.warn("Ignoring invalid Smart NPC Epic Fight weapon capability redirect '{}'. Expected source_item;target_preset.", rawEntry);
                    continue;
                }

                ResourceLocation source = ResourceLocation.tryParse(entry.substring(0, separator).trim());
                ResourceLocation target = ResourceLocation.tryParse(entry.substring(separator + 1).trim());
                if (source == null || target == null) {
                    SmartNpc.LOGGER.warn("Ignoring invalid Smart NPC Epic Fight weapon capability redirect '{}'.", rawEntry);
                    continue;
                }
                parsed.put(source, target);
            }

            cachedRawEntries = List.copyOf(rawEntries);
            cachedRedirects = Map.copyOf(parsed);
        }
    }
}
