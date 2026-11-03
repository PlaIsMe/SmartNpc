package com.pla.smart_npc.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public class SmartNpcConfig {
    public record SpawnConfig(int weight, int minCount, int maxCount) {}

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;
    public static ModConfigSpec.BooleanValue REMOTE_NPC_DEPARTURE_ENABLED;
    public static ModConfigSpec.IntValue REMOTE_NPC_DEPARTURE_MIN_MINUTES;
    public static ModConfigSpec.IntValue REMOTE_NPC_DEPARTURE_MAX_MINUTES;
    private static final SpawnConfig DEFAULT_PLAYER_NPC_SPAWN = new SpawnConfig(1, 1, 1);
    private static final int DEFAULT_MAX_NATURAL_PLAYER_NPCS = -1;

    public static ModConfigSpec.ConfigValue<Boolean> TURN_ON_NPC_CHAT;
    public static ModConfigSpec.ConfigValue<Boolean> SHOW_NPC_CHAT_PREFIX;
    public static ModConfigSpec.ConfigValue<String> NPC_CHAT_LOCALE;
    public static ModConfigSpec.IntValue FORCE_TICK_MANAGE;
    public static ModConfigSpec.IntValue MAX_NATURAL_PLAYER_NPCS;
    public static ModConfigSpec.IntValue AI_PROCESSING_NPC_LIMIT;
    public static ModConfigSpec.DoubleValue AI_TARGET_SERVER_MSPT;
    public static ModConfigSpec.ConfigValue<List<? extends Number>> PLAYER_NPC_SPAWN;
    public static ModConfigSpec.ConfigValue<List<? extends String>> BLACKLIST_COMPAT_MOD_WEAPON;
    public static ModConfigSpec.ConfigValue<Boolean> PERFORMANCE_MONITOR_ENABLED;

    static {
        BUILDER.push("remoteNpcDeparture");
        REMOTE_NPC_DEPARTURE_ENABLED = BUILDER.comment("Allow force-ticked NPCs to leave when no external chunk loader covers them.")
                .define("enabled", true);
        REMOTE_NPC_DEPARTURE_MIN_MINUTES = BUILDER.comment("Minimum unattended online simulation time in minutes. Counts only while this NPC owns a force ticket.")
                .defineInRange("minMinutes", 10, 1, 10080);
        REMOTE_NPC_DEPARTURE_MAX_MINUTES = BUILDER.comment("Maximum unattended time in minutes. External loading resets the timer; reversed bounds are normalized.")
                .defineInRange("maxMinutes", 30, 1, 10080);
        BUILDER.pop();

        TURN_ON_NPC_CHAT = BUILDER.comment(
                        "Enable Player NPC chat.")
                .define("turnOnNpcChat", true);
        SHOW_NPC_CHAT_PREFIX = BUILDER.comment(
                        "Add [NPC] before the existing <name> prefix in Player NPC chat.")
                .define("showNpcChatPrefix", false);
        NPC_CHAT_LOCALE = BUILDER.comment(
                        "Datapack locale used for Player NPC event chat, for example en_us.")
                .define("npcChatLocale", "en_us", value -> value instanceof String locale
                        && locale.matches("[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*"));

        FORCE_TICK_MANAGE = BUILDER.comment(
                        "Keep Player NPC chunks loaded: -1 automatic, 0 disabled, 1 all NPCs.")
                .defineInRange("forceTickManage", -1, -1, 1);

        MAX_NATURAL_PLAYER_NPCS = BUILDER.comment(
                        "Maximum naturally spawned Player NPCs: -1 automatic, 0 disabled, positive value fixed.")
                .defineInRange("maxNaturalPlayerNpcs", DEFAULT_MAX_NATURAL_PLAYER_NPCS, -1, Integer.MAX_VALUE);

        BUILDER.push("aiScheduler");
        AI_PROCESSING_NPC_LIMIT = BUILDER.comment(
                        "Maximum Player NPCs doing routine work at once: -1 automatic, 0 paused, positive value fixed.")
                .defineInRange("processingNpcLimit", -1, -1, 64);
        AI_TARGET_SERVER_MSPT = BUILDER.comment(
                        "Server MSPT target for automatic AI scheduling. -1 uses the default target.")
                .defineInRange("targetServerMspt", -1.0D, -1.0D, 49.0D);
        BUILDER.pop();

        PLAYER_NPC_SPAWN = BUILDER.comment(
                        "Player NPC spawn settings: [weight, minCount, maxCount]. Weight 0 disables spawning.")
                .defineList("spawnPlayerNpc", List.of(
                        DEFAULT_PLAYER_NPC_SPAWN.weight(),
                        DEFAULT_PLAYER_NPC_SPAWN.minCount(),
                        DEFAULT_PLAYER_NPC_SPAWN.maxCount()
                ), element -> element instanceof Number);

        BLACKLIST_COMPAT_MOD_WEAPON = BUILDER.comment(
                        "Mod IDs whose weapons Player NPCs cannot receive.")
                .defineList("blacklistCompatModWeapon", List.of(), element -> element instanceof String);

        BUILDER.push("performanceMonitor");
        PERFORMANCE_MONITOR_ENABLED = BUILDER.comment(
                        "Enable Smart NPC TPS warnings. TPS sampling, inspector data, and automatic resource controls remain active.")
                .define("enabled", false);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static SpawnConfig getPlayerNpcSpawnConfig() {
        return parseSpawnConfigOrDefault(PLAYER_NPC_SPAWN.get(), DEFAULT_PLAYER_NPC_SPAWN);
    }

    public static boolean isCompatWeaponBlacklisted(String modId) {
        return BLACKLIST_COMPAT_MOD_WEAPON.get().stream().anyMatch(entry -> entry.equalsIgnoreCase(modId));
    }

    public static boolean isForceTickManageEnabled() {
        return getForceTickMode() != 0;
    }

    public static int getForceTickMode() {
        return FORCE_TICK_MANAGE.get();
    }

    public static int getMaxNaturalPlayerNpcs() {
        return MAX_NATURAL_PLAYER_NPCS.get();
    }

    private static SpawnConfig parseSpawnConfigOrDefault(List<? extends Number> rawValues, SpawnConfig defaultConfig) {
        if (rawValues == null || rawValues.size() != 3) {
            return defaultConfig;
        }

        Integer weight = toExactInteger(rawValues.get(0));
        Integer minCount = toExactInteger(rawValues.get(1));
        Integer maxCount = toExactInteger(rawValues.get(2));

        if (weight == null) {
            return defaultConfig;
        }
        if (weight == 0) {
            return new SpawnConfig(0, 1, 1);
        }

        if (minCount == null || maxCount == null) {
            return defaultConfig;
        }
        if (weight < 0 || weight > 1000 || minCount < 1 || minCount > 64 || maxCount < minCount || maxCount > 64) {
            return defaultConfig;
        }

        return new SpawnConfig(weight, minCount, maxCount);
    }

    private static Integer toExactInteger(Number number) {
        if (number == null) return null;

        double valueAsDouble = number.doubleValue();
        long roundedValue = Math.round(valueAsDouble);

        if (Math.abs(valueAsDouble - roundedValue) > 1e-9) return null;
        if (roundedValue < Integer.MIN_VALUE || roundedValue > Integer.MAX_VALUE) return null;

        return (int) roundedValue;
    }
}
