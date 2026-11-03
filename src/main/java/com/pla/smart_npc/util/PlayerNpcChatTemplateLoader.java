package com.pla.smart_npc.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Loads selector-aware Player NPC chat from data/smart_npc/chat/&lt;locale&gt;/&lt;event&gt;.json. */
public class PlayerNpcChatTemplateLoader extends SimpleJsonResourceReloadListener<JsonElement> {
    public static final String CALL_HELP = "call_help";
    public static final String TEAMUP_REQUEST = "teamup_request";
    public static final String WARN_DEATH = "warn_death";
    public static final String MISSING_HOME_CHEST = "missing_home_chest";
    public static final String BROKEN_BED = "broken_bed";
    public static final String KILLER_TAUNT = "killer_taunt";
    public static final String DEATH_REACTION = "death_reaction";
    public static final String BURN_ITEM = "burn_item";
    public static final String THROW_TRASH = "throw_trash";

    private static final String DEFAULT_LOCALE = "en_us";
    private static final String DEFAULT_SELECTOR = "DEFAULT";
    private static final Set<String> EVENTS = Set.of(
            CALL_HELP,
            TEAMUP_REQUEST,
            WARN_DEATH,
            MISSING_HOME_CHEST,
            BROKEN_BED,
            KILLER_TAUNT,
            DEATH_REACTION,
            BURN_ITEM,
            THROW_TRASH
    );
    private static final Gson GSON = new Gson();
    private static volatile Map<String, Map<String, List<ChatEntry>>> entriesByLocale = Map.of();

    public PlayerNpcChatTemplateLoader() {
        super(ExtraCodecs.JSON, FileToIdConverter.json("chat"));
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Map<String, List<ChatEntry>>> loaded = new HashMap<>();

        for (Map.Entry<Identifier, JsonElement> resource : resources.entrySet()) {
            Identifier id = resource.getKey();
            if (!SmartNpc.MODID.equals(id.getNamespace())) {
                continue;
            }

            String[] pathParts = id.getPath().split("/", -1);
            if (pathParts.length != 2 || !EVENTS.contains(pathParts[1])) {
                SmartNpc.LOGGER.warn("Skipping Player NPC chat resource with unsupported path {}", id);
                continue;
            }

            String locale = normalizeResourceLocale(pathParts[0]);
            if (locale.isEmpty()) {
                SmartNpc.LOGGER.warn("Skipping Player NPC chat resource with invalid locale {}", id);
                continue;
            }

            List<ChatEntry> parsed = parseEntries(id, resource.getValue());
            loaded.computeIfAbsent(locale, ignored -> new HashMap<>()).put(pathParts[1], parsed);
        }

        Map<String, Map<String, List<ChatEntry>>> immutable = new HashMap<>();
        loaded.forEach((locale, events) -> immutable.put(locale, Map.copyOf(events)));
        entriesByLocale = Map.copyOf(immutable);
        int eventFileCount = immutable.values().stream().mapToInt(Map::size).sum();
        SmartNpc.LOGGER.info("Loaded {} Player NPC chat event files across {} locales", eventFileCount, immutable.size());
    }

    public static Optional<String> selectMessage(
            String event,
            PlayerNpcEntity speaker,
            @Nullable Entity target,
            RandomSource random
    ) {
        return selectMessage(event, speaker, target, random, false);
    }

    public static boolean hasExplicitTargetMatch(String event, PlayerNpcEntity speaker, @Nullable Entity target) {
        return matchingEntries(event, speaker, target, true).findAny().isPresent();
    }

    public static Optional<String> selectMessage(
            String event,
            PlayerNpcEntity speaker,
            @Nullable Entity target,
            RandomSource random,
            boolean requireExplicitTarget
    ) {
        List<ChatEntry> matching = matchingEntries(event, speaker, target, requireExplicitTarget).toList();
        if (matching.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matching.get(random.nextInt(matching.size())).message());
    }

    private static java.util.stream.Stream<ChatEntry> matchingEntries(
            String event,
            PlayerNpcEntity speaker,
            @Nullable Entity target,
            boolean requireExplicitTarget
    ) {
        Map<String, Map<String, List<ChatEntry>>> snapshot = entriesByLocale;
        String locale = normalizeLocale(SmartNpcConfig.NPC_CHAT_LOCALE.get());
        Map<String, List<ChatEntry>> localizedEvents = snapshot.get(locale);
        List<ChatEntry> candidates = localizedEvents == null ? null : localizedEvents.get(event);

        // An absent localized event falls back. A present event whose selectors do not match is silent.
        if (candidates == null && !DEFAULT_LOCALE.equals(locale)) {
            Map<String, List<ChatEntry>> defaultEvents = snapshot.get(DEFAULT_LOCALE);
            candidates = defaultEvents == null ? null : defaultEvents.get(event);
        }
        if (candidates == null || candidates.isEmpty()) {
            return java.util.stream.Stream.empty();
        }

        return candidates.stream()
                .filter(entry -> !requireExplicitTarget || !isWildcard(entry.targetSelector()))
                .filter(entry -> matchesSpeaker(entry.speakerSelector(), speaker))
                .filter(entry -> matchesTarget(entry.targetSelector(), target));
    }

    private static List<ChatEntry> parseEntries(Identifier id, JsonElement root) {
        if (!root.isJsonArray()) {
            SmartNpc.LOGGER.warn("Skipping Player NPC chat resource {} because its root is not an array", id);
            return List.of();
        }

        JsonArray array = GsonHelper.convertToJsonArray(root, "chat entries");
        List<ChatEntry> parsed = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                SmartNpc.LOGGER.warn("Skipping malformed Player NPC chat entry {}[{}]: expected a string", id, index);
                continue;
            }

            String[] fields = element.getAsString().split("\\|", -1);
            if (fields.length > 3 || fields[0].isBlank()) {
                SmartNpc.LOGGER.warn("Skipping malformed Player NPC chat entry {}[{}]: expected message|speaker|target", id, index);
                continue;
            }

            String speakerSelector = selectorOrDefault(fields.length > 1 ? fields[1] : null);
            String targetSelector = selectorOrDefault(fields.length > 2 ? fields[2] : null);
            parsed.add(new ChatEntry(fields[0], speakerSelector, targetSelector));
        }
        return List.copyOf(parsed);
    }

    private static boolean matchesSpeaker(String selector, PlayerNpcEntity speaker) {
        return isWildcard(selector) || entityNameMatches(speaker, selector);
    }

    private static boolean matchesTarget(String selector, @Nullable Entity target) {
        if (isWildcard(selector)) {
            return true;
        }
        if (target == null) {
            return false;
        }

        if (selector.indexOf(':') > 0) {
            Identifier targetType = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            return targetType != null && targetType.toString().equalsIgnoreCase(selector);
        }
        return entityNameMatches(target, selector);
    }

    private static boolean entityNameMatches(Entity entity, String selector) {
        if (entity.getScoreboardName().equalsIgnoreCase(selector)
                || entity.getName().getString().equalsIgnoreCase(selector)
                || entity.getDisplayName().getString().equalsIgnoreCase(selector)) {
            return true;
        }
        if (entity instanceof PlayerNpcEntity playerNpc) {
            return playerNpc.getUsername().getSkinName().equalsIgnoreCase(selector)
                    || playerNpc.getUsername().getDisplayName().equalsIgnoreCase(selector);
        }
        return false;
    }

    private static boolean isWildcard(String selector) {
        return DEFAULT_SELECTOR.equalsIgnoreCase(selector) || "all".equalsIgnoreCase(selector);
    }

    private static String selectorOrDefault(@Nullable String selector) {
        return selector == null || selector.isBlank() ? DEFAULT_SELECTOR : selector.trim();
    }

    private static String normalizeLocale(@Nullable String locale) {
        if (locale == null) {
            return DEFAULT_LOCALE;
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.matches("[a-z0-9]+(?:_[a-z0-9]+)*") ? normalized : DEFAULT_LOCALE;
    }

    private static String normalizeResourceLocale(String locale) {
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9]+(?:_[a-z0-9]+)*") ? normalized : "";
    }

    private record ChatEntry(String message, String speakerSelector, String targetSelector) {}
}
