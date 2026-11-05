package com.pla.smart_npc.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.pla.smart_npc.SmartNpc;
import dev.architectury.platform.Platform;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SmartNpcCompatConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue YES_STEVE_MODEL_ENABLED;
    public static final ModConfigSpec.DoubleValue YES_STEVE_MODEL_CHANCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WEAPON_CAPABILITY_REDIRECTS;

    private static final String EPIC_FIGHT_REDIRECTS_PATH = "epicfight.advancedMobPatch.weaponCapabilityRedirects";
    private static volatile List<? extends String> cachedRawEntries = List.of();
    private static volatile Map<ResourceLocation, ResourceLocation> cachedRedirects = Map.of();

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("yes_steve_model");
        YES_STEVE_MODEL_ENABLED = builder.comment("Enable Yes Steve Model compatibility for Player NPCs when a supported YSM version is installed.",
                "False uses normal NPC skins, including for existing NPCs. Saved YSM choices are retained for re-enabling.")
                .define("enabled", true);
        YES_STEVE_MODEL_CHANCE = builder.comment("Chance to choose a custom server-installed or bundled YSM model instead of YSM Steve/Alex.",
                "0.0 uses YSM Steve/Alex for every NPC, matching the regular skin's wide/slim shape; YSM stays enabled.",
                "1.0 uses custom models, excluding YSM Steve/Alex. If none are usable, Steve/Alex is the fallback.",
                "Intermediate values roll once per NPC. Client-only models are never selected.")
                .defineInRange("modelChance", 1.0D, 0.0D, 1.0D);
        builder.pop();

        builder.comment("Epic Fight compatibility settings (NeoForge only).").push("epicfight");
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
        builder.pop();
        SPEC = builder.build();
    }

    private SmartNpcCompatConfig() {
    }

    /** Run before config registration/correction adds the new key's default. */
    public static void migrateLegacyEpicFightConfig() {
        Path configFolder = Platform.getConfigFolder();
        Path legacyPath = configFolder.resolve("smart_npc-epicfight.toml");
        if (!Files.isRegularFile(legacyPath)) {
            return;
        }
        try (CommentedFileConfig combined = CommentedFileConfig.builder(configFolder.resolve("smart_npc-compat.toml")).sync().onFileNotFound(FileNotFoundAction.READ_NOTHING).build()) {
            combined.load();
            // An explicit empty list also wins. Never overwrite settings in the new file.
            if (combined.contains(EPIC_FIGHT_REDIRECTS_PATH)) {
                return;
            }
            try (CommentedFileConfig legacy = CommentedFileConfig.builder(legacyPath).sync().build()) {
                legacy.load();
                Object value = legacy.get("advancedMobPatch.weaponCapabilityRedirects");
                if (!(value instanceof List<?> entries) || entries.stream().anyMatch(entry -> !(entry instanceof String))) {
                    SmartNpc.LOGGER.warn("Could not migrate Epic Fight redirects from {}: expected a list of strings.", legacyPath);
                    return;
                }
                combined.set(EPIC_FIGHT_REDIRECTS_PATH, List.copyOf(entries));
                combined.save();
                SmartNpc.LOGGER.info("Migrated Epic Fight redirects to smart_npc-compat.toml; the legacy file is retained as a backup.");
            }
        } catch (RuntimeException error) {
            SmartNpc.LOGGER.warn("Could not migrate {} to smart_npc-compat.toml; the legacy file is unchanged.", legacyPath, error);
        }
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

        synchronized (SmartNpcCompatConfig.class) {
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
