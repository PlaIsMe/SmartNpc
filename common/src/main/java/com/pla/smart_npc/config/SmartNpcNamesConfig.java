package com.pla.smart_npc.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.config.ModConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SmartNpcNamesConfig {
    private static final Object ENTRY_CACHE_LOCK = new Object();
    private static volatile List<String> cachedPlayerNpcNameEntries;
    private static volatile long playerNpcNameEntriesRevision;
    private static final List<String> DEFAULT_PLAYER_NPC_NAMES = List.of(
            "Technoblade|EXPLORING|HUNT_MONSTERS|LOOTING|TROLL_HIT",
            "Dream|EXPLORING|MINING|HUNT_PLAYERS|LOOTING|HUNT_ANIMALS|TROLL_HIT|CHEST_PROTECT|COWARD",
            "MrBeast|BUILDING|EXPLORING|LOOTING|TROLL_HIT|CHEST_PROTECT",
            "Skeppy|BUILDING|FARMING|TROLL_HIT|LOOTING|HUNT_MONSTERS",
            "Sapnap|EXPLORING|HUNT_ANIMALS|HUNT_VILLAGERS|HUNT_PLAYERS|TEAMUP",
            "ExplodingTNT|MINING|EXPLORING|TROLL_HIT|LOOTING|HUNT_MONSTERS|CHEST_PROTECT|COWARD",
            "GeorgeNotFound|BUILDING|FISHING|CAUTIOUS",
            "TommyInnit|EXPLORING|FARMING|TROLL_HIT",
            "Philza|FISHING|HUNT_MONSTERS|LOOTING|CHEST_PROTECT",
            "Ranboo|FISHING|BUILDING|CAUTIOUS|HUNT_ANIMALS|TEAMUP",
            "Quackity|FARMING|BUILDING|EXPLORING|HUNT_PLAYERS|HUNT_ANIMALS|HUNT_MONSTERS|COWARD",
            "Tubbo|FARMING|CAUTIOUS|LOOTING",
            "DanTDM|EXPLORING|FISHING|HUNT_MONSTERS|TROLL_HIT|TEAMUP",
            "PopularMMOs|EXPLORING|BUILDING|HUNT_MONSTERS|TROLL_HIT|LOOTING|CHEST_PROTECT",
            "Darkere|BUILDING|FARMING|FISHING|HUNT_ANIMALS|LOOTING|TEAMUP",
            "Darkhax|FISHING|CAUTIOUS",
            "Emberwalker|FARMING|MINING|CAUTIOUS|TEAMUP|COWARD",
            "Gigabit101|BUILDING|FISHING|LOOTING|HUNT_PLAYERS|CHEST_PROTECT",
            "Kamefrede|MINING|FARMING|HUNT_MONSTERS|LOOTING",
            "KnightMiner_|MINING|EXPLORING|HUNT_MONSTERS|LOOTING",
            "Lat|MINING|TROLL_HIT|HUNT_VILLAGERS|CHEST_PROTECT|TEAMUP",
            "LexManos|EXPLORING|FISHING|LOOTING|HUNT_PLAYERS|COWARD",
            "Mrbysco|BUILDING|EXPLORING|MINING|LOOTING",
            "P3pp3rF1y|FARMING|EXPLORING|HUNT_MONSTERS|TROLL_HIT|TEAMUP",
            "Ray|BUILDING|HUNT_PLAYERS|LOOTING|TROLL_HIT|CHEST_PROTECT",
            "Ridanis|FISHING|HUNT_ANIMALS|CAUTIOUS|LOOTING",
            "SOTMead|FARMING|HUNT_ANIMALS|LOOTING|HUNT_MONSTERS|CHEST_PROTECT|COWARD",
            "ShyNieke|EXPLORING|HUNT_MONSTERS|LOOTING|TEAMUP",
            "SkySom|EXPLORING|BUILDING|LOOTING|HUNT_PLAYERS|TROLL_HIT",
            "Soaryn|EXPLORING|FARMING|HUNT_MONSTERS|LOOTING",
            "ValkyrieofNight|FISHING|FARMING|CAUTIOUS|LOOTING",
            "XCompWiz|FARMING|BUILDING|TROLL_HIT|LOOTING|HUNT_VILLAGERS|CHEST_PROTECT|COWARD",
            "DaReal_BingoBear|MINING|BUILDING|HUNT_ANIMALS|LOOTING|CAUTIOUS",
            "darkphan|BUILDING|FISHING|LOOTING|HUNT_MONSTERS|TEAMUP",
            "direwolf20|FISHING|FARMING|EXPLORING|LOOTING|HUNT_MONSTERS|HUNT_ANIMALS",
            "dmodoomsirius|BUILDING|FARMING|FISHING|LOOTING|TROLL_HIT|HUNT_PLAYERS|CHEST_PROTECT",
            "malte0811|FARMING|CAUTIOUS|HUNT_ANIMALS|LOOTING|TEAMUP|COWARD",
            "nekosune|MINING|FARMING|FISHING|HUNT_PLAYERS|TROLL_HIT|LOOTING",
            "neptunepink|FISHING|HUNT_PLAYERS|HUNT_MONSTERS|LOOTING|CHEST_PROTECT",
            "vadis365|BUILDING|FARMING|HUNT_VILLAGERS|TROLL_HIT|LOOTING|TEAMUP",
            "wyld|EXPLORING|HUNT_ANIMALS|LOOTING|CAUTIOUS|TEAMUP",
            "paulsoaresjr|BUILDING|FARMING|LOOTING|HUNT_MONSTERS|CHEST_PROTECT",
            "Mhykol|FISHING|EXPLORING|HUNT_MONSTERS|LOOTING|HUNT_ANIMALS|CHEST_PROTECT",
            "Vswe|BUILDING|EXPLORING|LOOTING|CAUTIOUS|TEAMUP|COWARD",
            "TurkeyDev|EXPLORING|FARMING|FISHING|TROLL_HIT|HUNT_ANIMALS|LOOTING",
            "Gen_Deathrow|EXPLORING|HUNT_MONSTERS|HUNT_PLAYERS|LOOTING|CHEST_PROTECT|TEAMUP",
            "Sevadus|EXPLORING|FISHING|HUNT_VILLAGERS|CAUTIOUS|LOOTING|CHEST_PROTECT|COWARD"
    );
    private static final CommentedConfig DEFAULT_PLAYER_NPC_ROSTER = createRosterConfig(DEFAULT_PLAYER_NPC_NAMES);

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<Object> PLAYER_NPC_NAMES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        PLAYER_NPC_NAMES = builder.comment(
                        "Player NPC roster. Each line uses: skinName = [\"INTEREST\", \"INTEREST\", ...]",
                        "The key must be a valid Minecraft username (1-16 letters, numbers, or underscores).",
                        "Each NPC must contain one job: BUILDING, MINING, FARMING, FISHING, or EXPLORING.",
                        "Other interests: HUNT_MONSTERS, HUNT_ANIMALS, HUNT_PLAYERS, HUNT_VILLAGERS, TROLL_HIT, LOOTING, CAUTIOUS, CHEST_PROTECT, TEAMUP, COWARD.",
                        "Optional appearance: \"YSM:<filename>\" without .ysm (example: GI_Paimon.ysm -> YSM:GI_Paimon).",
                        "The filename is case-sensitive and must be loaded by server YSM; modelChance in smart_npc-compat.toml still applies.",
                        "Missing or ambiguous filenames use YSM's default. The choice is saved once and only applies to newly spawned NPCs.",
                        "CAUTIOUS conflict with HUNT_MONSTERS and HUNT_PLAYERS since CAUTIOUS will let NPC run away from them.",
                        "For an optional display name, use a quoted key: \"skinName:Display Name\" = [\"BUILDING\", \"CAUTIOUS\"].",
                        "Duplicate skin names are ignored after the first entry. An empty table disables Player NPC spawning.")
                .<Object>define("playerNpcNames", DEFAULT_PLAYER_NPC_ROSTER, SmartNpcNamesConfig::isValidRoster);
        SPEC = builder.build();
    }

    private SmartNpcNamesConfig() {
    }

    public static List<String> getPlayerNpcNameEntries() {
        List<String> entries = cachedPlayerNpcNameEntries;
        if (entries != null) {
            return entries;
        }
        synchronized (ENTRY_CACHE_LOCK) {
            entries = cachedPlayerNpcNameEntries;
            if (entries == null) {
                entries = List.copyOf(toNameEntryStrings(PLAYER_NPC_NAMES.get()));
                cachedPlayerNpcNameEntries = entries;
            }
            return entries;
        }
    }

    /** Cheap generation check used by loaded NPCs to refresh interests after a config reload. */
    public static long getPlayerNpcNameEntriesRevision() {
        return playerNpcNameEntriesRevision;
    }

    /** Spawn-only lookup; preserve the same first-entry-wins rule as the configured name pool. */
    public static Optional<String> getYsmModelFileName(String skinName) {
        for (String rawEntry : getPlayerNpcNameEntries()) {
            Optional<NameEntry> parsed = parseNameEntry(rawEntry);
            if (parsed.isPresent() && parsed.get().skinName().equalsIgnoreCase(skinName)) {
                return Optional.ofNullable(parsed.get().ysmModelFileName());
            }
        }
        return Optional.empty();
    }

    public static void onConfigLoading(ModConfig config) {
        migrateLegacyList(config);
        invalidatePlayerNpcNameEntries(config);
    }

    public static void onConfigReloading(ModConfig config) {
        migrateLegacyList(config);
        invalidatePlayerNpcNameEntries(config);
    }

    public static Optional<NameEntry> parseNameEntry(String rawEntry) {
        if (rawEntry == null) {
            return Optional.empty();
        }

        String[] fields = rawEntry.split("\\|", -1);
        if (fields.length < 2) {
            return Optional.empty();
        }

        String[] names = fields[0].trim().split(":", 2);
        String skinName = names[0].trim();
        if (!skinName.matches("[A-Za-z0-9_]{1,16}")) {
            return Optional.empty();
        }

        String displayName = null;
        if (names.length > 1) {
            displayName = names[1].trim();
            if (displayName.isEmpty() || displayName.length() > 64) {
                return Optional.empty();
            }
        }

        Set<PlayerNpcInterest> interests = new LinkedHashSet<>();
        String ysmModelFileName = null;
        for (int i = 1; i < fields.length; i++) {
            String interestName = fields[i].trim();
            if (interestName.isEmpty()) {
                return Optional.empty();
            }
            if (interestName.regionMatches(true, 0, "YSM:", 0, 4)) {
                if (ysmModelFileName != null) {
                    return Optional.empty();
                }
                ysmModelFileName = interestName.substring(4).trim();
                if (ysmModelFileName.isEmpty()) {
                    return Optional.empty();
                }
                continue;
            }
            try {
                PlayerNpcInterest interest = PlayerNpcInterest.valueOf(interestName.toUpperCase(Locale.ROOT));
                if (!interests.add(interest)) {
                    return Optional.empty();
                }
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        if (interests.stream().noneMatch(PlayerNpcInterest::isJob)) {
            return Optional.empty();
        }
        return Optional.of(new NameEntry(skinName, displayName, List.copyOf(interests), ysmModelFileName));
    }

    private static boolean isValidNameEntry(Object value) {
        return value instanceof String entry && parseNameEntry(entry).isPresent();
    }

    private static boolean isValidRoster(Object value) {
        if (value instanceof List<?> legacyEntries) {
            return legacyEntries.stream().allMatch(SmartNpcNamesConfig::isValidNameEntry);
        }
        if (!(value instanceof UnmodifiableConfig roster)) {
            return false;
        }
        for (Map.Entry<String, Object> entry : roster.valueMap().entrySet()) {
            if (parseRosterEntry(entry.getKey(), entry.getValue()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> toNameEntryStrings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> legacyEntries) {
            for (Object legacyEntry : legacyEntries) {
                if (legacyEntry instanceof String entry && parseNameEntry(entry).isPresent()) {
                    result.add(entry);
                }
            }
            return result;
        }
        if (!(value instanceof UnmodifiableConfig roster)) {
            return result;
        }
        for (Map.Entry<String, Object> entry : roster.valueMap().entrySet()) {
            parseRosterEntry(entry.getKey(), entry.getValue())
                    .map(SmartNpcNamesConfig::toConfigEntry)
                    .ifPresent(result::add);
        }
        return result;
    }

    private static Optional<NameEntry> parseRosterEntry(String combinedName, Object value) {
        if (!(value instanceof List<?> rawInterests) || rawInterests.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder legacyEntry = new StringBuilder(combinedName);
        for (Object rawInterest : rawInterests) {
            if (!(rawInterest instanceof String interestName)) {
                return Optional.empty();
            }
            legacyEntry.append('|').append(interestName);
        }
        return parseNameEntry(legacyEntry.toString());
    }

    private static CommentedConfig createRosterConfig(List<String> entries) {
        CommentedConfig roster = TomlFormat.newConfig(LinkedHashMap::new);
        for (String rawEntry : entries) {
            parseNameEntry(rawEntry).ifPresent(entry -> roster.set(
                    combinedName(entry),
                    toRosterInterests(entry)
            ));
        }
        return roster;
    }

    private static String combinedName(NameEntry entry) {
        return entry.displayName() == null
                ? entry.skinName()
                : entry.skinName() + ":" + entry.displayName();
    }

    private static String toConfigEntry(NameEntry entry) {
        StringBuilder result = new StringBuilder(combinedName(entry));
        for (String interest : toRosterInterests(entry)) {
            result.append('|').append(interest);
        }
        return result.toString();
    }

    private static List<String> toRosterInterests(NameEntry entry) {
        List<String> result = new ArrayList<>();
        entry.interests().forEach(interest -> result.add(interest.name()));
        if (entry.ysmModelFileName() != null) {
            result.add("YSM:" + entry.ysmModelFileName());
        }
        return result;
    }

    private static void migrateLegacyList(ModConfig config) {
        if (config.getSpec() != SPEC || config.getLoadedConfig() == null) {
            return;
        }

        Object currentValue = config.getLoadedConfig().config().get("playerNpcNames");
        if (currentValue instanceof List<?> legacyEntries) {
            List<String> entries = new ArrayList<>();
            for (Object legacyEntry : legacyEntries) {
                if (legacyEntry instanceof String entry && parseNameEntry(entry).isPresent()) {
                    entries.add(entry);
                }
            }
            config.getLoadedConfig().config().set("playerNpcNames", createRosterConfig(entries));
        }
    }

    private static void invalidatePlayerNpcNameEntries(ModConfig config) {
        if (config.getSpec() != SPEC) {
            return;
        }
        invalidatePlayerNpcNameEntries();
    }

    static void invalidatePlayerNpcNameEntries() {
        synchronized (ENTRY_CACHE_LOCK) {
            // Reload may mutate the existing NightConfig object in place, so identity/equality
            // checks cannot prove freshness. The Forge config event is the cache boundary.
            cachedPlayerNpcNameEntries = null;
            playerNpcNameEntriesRevision++;
        }
    }

    public record NameEntry(String skinName, String displayName, List<PlayerNpcInterest> interests, String ysmModelFileName) {
    }
}
