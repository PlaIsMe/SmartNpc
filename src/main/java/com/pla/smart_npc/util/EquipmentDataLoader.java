package com.pla.smart_npc.util;

import net.minecraft.tags.ItemTags;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pla.smart_npc.clazz.Difficulty;
import com.pla.smart_npc.config.SmartNpcConfig;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.fml.ModList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

public class EquipmentDataLoader extends SimpleJsonResourceReloadListener<JsonElement> {
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();
    private static final Map<String, List<EquipmentEntry>> EQUIP_ITEMS = new HashMap<>();
    private static final Logger LOGGER = LogManager.getLogger();
    private static final float MIN_ARMOR_SET_MATCH_CHANCE = 0.30F;
    private static final float MAX_ARMOR_SET_MATCH_CHANCE = 0.50F;
    private static final float EASY_MAINHAND_EQUIP_CHANCE = 0.15F;
    private static final float MEDIUM_MAINHAND_EQUIP_CHANCE = 0.65F;
    private static final float HARD_MAINHAND_EQUIP_CHANCE = 0.95F;
    private static final float EASY_OFFHAND_EQUIP_CHANCE = 0.03F;
    private static final float MEDIUM_OFFHAND_EQUIP_CHANCE = 0.35F;
    private static final float HARD_OFFHAND_EQUIP_CHANCE = 1.0F;
    private static final float MEDIUM_SHIELD_OFFHAND_CHANCE = 0.30F;
    private static final float EASY_ARMOR_EQUIP_CHANCE = 0.08F;
    private static final float MEDIUM_ARMOR_EQUIP_CHANCE = 0.45F;
    private static final String MINECRAFT = "minecraft";
    private static final String SHIELD_ITEM_ID = "minecraft:shield";
    private static final List<String> EQUIPMENT_SLOTS = List.of("MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET");
    private static final Map<String, List<String>> ARMOR_SLOT_SUFFIXES = Map.of(
            "HEAD", List.of("helmet"),
            "CHEST", List.of("chestplate"),
            "LEGS", List.of("leggings", "legging"),
            "FEET", List.of("boots", "boot")
    );

    public EquipmentDataLoader() {
        super(ExtraCodecs.JSON, FileToIdConverter.json("mobs_equipment"));
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> map, ResourceManager manager, ProfilerFiller profiler) {
        EQUIP_ITEMS.clear();
        for (Map.Entry<Identifier, JsonElement> entry : map.entrySet()) {
            Identifier fileId = entry.getKey();
            JsonObject root = GsonHelper.convertToJsonObject(entry.getValue(), "equipment");
            String modId = fileId.getPath().replace(".json", "");

            if (SmartNpcConfig.isCompatWeaponBlacklisted(modId)) {
                continue;
            }
            if (!MINECRAFT.equals(modId) && !ModList.get().isLoaded(modId)) {
                continue;
            }

            for (String slot : EQUIPMENT_SLOTS) {
                if (!root.has(slot)) continue;

                JsonArray array = root.getAsJsonArray(slot);
                List<EquipmentEntry> items = EQUIP_ITEMS.computeIfAbsent(slot, k -> new ArrayList<>());

                for (JsonElement el : array) {
                    parseEquipmentEntry(modId, slot, el).ifPresent(items::add);
                }
            }
        }

        int loadedEntries = EQUIP_ITEMS.values().stream().mapToInt(List::size).sum();
        LOGGER.info("Loaded {} Player NPC equipment entries", loadedEntries);
    }

    private static Optional<EquipmentEntry> parseEquipmentEntry(String modId, String slot, JsonElement element) {
        if (element.isJsonPrimitive()) {
            return Optional.of(new EquipmentEntry(qualifyItemId(modId, element.getAsString()), Difficulty.EASY));
        }

        if (!element.isJsonObject()) {
            LOGGER.warn("Skipping invalid equipment entry in {}: {}", slot, element);
            return Optional.empty();
        }

        JsonObject object = element.getAsJsonObject();
        if (!object.has("id")) {
            LOGGER.warn("Skipping equipment entry without id in {}: {}", slot, object);
            return Optional.empty();
        }

        String itemId = qualifyItemId(modId, GsonHelper.getAsString(object, "id"));
        String difficultyName = object.has("min_difficulty")
                ? GsonHelper.getAsString(object, "min_difficulty")
                : Difficulty.EASY.name();
        Difficulty minDifficulty = Difficulty.findByName(difficultyName);
        if (minDifficulty == null) {
            LOGGER.warn("Unknown min_difficulty '{}' for {}; using EASY", difficultyName, itemId);
            minDifficulty = Difficulty.EASY;
        }

        return Optional.of(new EquipmentEntry(itemId, minDifficulty));
    }

    private static String qualifyItemId(String modId, String itemName) {
        return itemName.contains(":") ? itemName : modId + ":" + itemName;
    }

    public static boolean itemExists(String itemId) {
        return getItem(itemId) != null;
    }

    private static Item getItem(String itemId) {
        String[] parts = itemId.split(":", 2);
        if (parts.length != 2) {
            return null;
        }

        Identifier id = Identifier.fromNamespaceAndPath(parts[0], parts[1]);
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : null;
    }

    public static String getItemId(ItemStack stack) {
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private static List<String> getAvailableItemIds(String slot, Difficulty difficulty) {
        return EQUIP_ITEMS.getOrDefault(slot, List.of()).stream()
                .filter(entry -> entry.canUse(difficulty))
                .map(EquipmentEntry::itemId)
                .filter(EquipmentDataLoader::itemExists)
                .toList();
    }

    private static boolean isArmorSlot(String slot) {
        return ARMOR_SLOT_SUFFIXES.containsKey(slot);
    }

    private static boolean shouldMatchPreviousArmorSet() {
        float chance = MIN_ARMOR_SET_MATCH_CHANCE
                + RANDOM.nextFloat() * (MAX_ARMOR_SET_MATCH_CHANCE - MIN_ARMOR_SET_MATCH_CHANCE);
        return RANDOM.nextFloat() < chance;
    }

    private static Optional<String> getArmorSetPrefix(String itemId) {
        String[] parts = itemId.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }

        String path = parts[1];
        for (List<String> suffixes : ARMOR_SLOT_SUFFIXES.values()) {
            for (String suffix : suffixes) {
                String suffixPattern = "_" + suffix;
                if (path.endsWith(suffixPattern) && path.length() > suffixPattern.length()) {
                    return Optional.of(path.substring(0, path.length() - suffixPattern.length()));
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<String> getMatchingArmorItem(String slot, List<String> pool, String previousArmorItemId) {
        if (previousArmorItemId == null || !shouldMatchPreviousArmorSet()) {
            return Optional.empty();
        }

        String[] parts = previousArmorItemId.split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }

        Optional<String> prefix = getArmorSetPrefix(previousArmorItemId);
        if (prefix.isEmpty()) {
            return Optional.empty();
        }

        for (String suffix : ARMOR_SLOT_SUFFIXES.getOrDefault(slot, List.of())) {
            String candidate = parts[0] + ":" + prefix.get() + "_" + suffix;
            if (pool.contains(candidate) && itemExists(candidate)) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    private static Optional<String> getArmorItemForSlot(String slot, List<String> pool, String previousArmorItemId) {
        return getMatchingArmorItem(slot, pool, previousArmorItemId)
                .or(() -> getRandomExistingItem(pool));
    }

    public static Optional<String> getRandomExistingItem(List<String> itemIds) {
        List<String> validItems = itemIds.stream()
                .filter(EquipmentDataLoader::itemExists)
                .toList();

        if (validItems.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(validItems.get(RANDOM.nextInt(validItems.size())));
    }

    private static Optional<String> getGeneratedOffhandItem(ItemStack mainHandStack, Difficulty difficulty, boolean allowOffhandShield) {
        String mainHandItemId = getItemId(mainHandStack);
        Optional<String> compatOffhandItem = getCompatGeneratedOffhandItem(mainHandItemId, mainHandStack, difficulty, allowOffhandShield)
                .filter(EquipmentDataLoader::itemExists);
        if (compatOffhandItem.isPresent()) {
            return compatOffhandItem;
        }

        boolean canGenerateShield = canUseShield(mainHandStack) && allowOffhandShield && itemExists(SHIELD_ITEM_ID);

        if (RANDOM.nextBoolean()) {
            if (canTwoHand(mainHandStack)) {
                return getDualWeaponOffhandItem(mainHandItemId, mainHandStack, difficulty);
            } else if (canGenerateShield) {
                return Optional.of(SHIELD_ITEM_ID);
            }
        } else {
            if (canGenerateShield) {
                return Optional.of(SHIELD_ITEM_ID);
            } else if (canTwoHand(mainHandStack)) {
                return getDualWeaponOffhandItem(mainHandItemId, mainHandStack, difficulty);
            }
        }

        return Optional.empty();
    }

    private static Optional<String> getDualWeaponOffhandItem(String mainHandItemId, ItemStack mainHandStack, Difficulty difficulty) {
        return getCompatDualWeaponOffhandItem(mainHandItemId, mainHandStack, difficulty)
                .filter(EquipmentDataLoader::itemExists)
                .or(() -> itemExists(mainHandItemId) ? Optional.of(mainHandItemId) : Optional.empty());
    }

    private static Optional<String> getRandomOffhandPoolItem(List<String> pool, boolean allowOffhandShield) {
        List<String> shieldItems = pool.stream()
                .filter(EquipmentDataLoader::isShieldItem)
                .toList();
        if (allowOffhandShield && !shieldItems.isEmpty()) {
            return Optional.of(shieldItems.get(RANDOM.nextInt(shieldItems.size())));
        }

        List<String> candidates = pool.stream()
                .filter(itemId -> !isShieldItem(itemId))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(RANDOM.nextInt(candidates.size())));
    }

    private static boolean rollOffhandShield(Difficulty difficulty) {
        return difficulty == Difficulty.MEDIUM && RANDOM.nextFloat() < MEDIUM_SHIELD_OFFHAND_CHANCE;
    }

    private static boolean isShieldItem(String itemId) {
        Item item = getItem(itemId);
        if (item instanceof ShieldItem) {
            return true;
        }
        int namespaceSeparator = itemId.indexOf(':');
        String path = namespaceSeparator >= 0 ? itemId.substring(namespaceSeparator + 1) : itemId;
        return path.contains("shield") || isCompatShieldItem(itemId);
    }

    public static boolean canUseShield(ItemStack stack) {
        return canUseVanillaShield(stack) || canUseCompatShield(stack);
    }

    private static boolean canUseVanillaShield(ItemStack stack) {
        Item item = stack.getItem();
        return item.builtInRegistryHolder().is(ItemTags.SWORDS) || item instanceof AxeItem || item instanceof TridentItem;
    }

    public static boolean canUseCompatShield(ItemStack stack) {
        return false;
    }

    public static boolean canTwoHand(ItemStack stack) {
        return canUseCompatDualWeapon(stack);
    }

    public static boolean canUseCompatDualWeapon(ItemStack stack) {
        return false;
    }

    public static Optional<String> getCompatGeneratedOffhandItem(String mainHandItemId, ItemStack mainHandStack, Difficulty difficulty, boolean allowOffhandShield) {
        return Optional.empty();
    }

    public static Optional<String> getCompatDualWeaponOffhandItem(String mainHandItemId, ItemStack mainHandStack, Difficulty difficulty) {
        return Optional.empty();
    }

    public static boolean isCompatShieldItem(String itemId) {
        return false;
    }

    public static int getRandomDamage(ItemStack itemStack) {
        int maxDamage = itemStack.getMaxDamage();
        int min = maxDamage / 3;
        int max = maxDamage * 3 / 4;
        return RANDOM.nextInt(max - min + 1) + min;
    }

    private static Difficulty getCurrentDifficulty(Entity entity) {
        MinecraftServer server = entity != null ? entity.level().getServer() : ServerLifecycleHooks.getCurrentServer();
        return server != null ? ProgressionUtil.getDifficulty(server) : Difficulty.EASY;
    }

    private static float getMainhandEquipChance(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> EASY_MAINHAND_EQUIP_CHANCE;
            case MEDIUM -> MEDIUM_MAINHAND_EQUIP_CHANCE;
            case HARD -> HARD_MAINHAND_EQUIP_CHANCE;
        };
    }

    private static float getOffhandEquipChance(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> EASY_OFFHAND_EQUIP_CHANCE;
            case MEDIUM -> MEDIUM_OFFHAND_EQUIP_CHANCE;
            case HARD -> HARD_OFFHAND_EQUIP_CHANCE;
        };
    }

    private static float getArmorEquipChance(Difficulty difficulty, float baseChance) {
        return switch (difficulty) {
            case EASY -> Math.min(baseChance, EASY_ARMOR_EQUIP_CHANCE);
            case MEDIUM -> Math.min(baseChance, MEDIUM_ARMOR_EQUIP_CHANCE);
            case HARD -> baseChance;
        };
    }

    private static boolean shouldEquipMainhand(Difficulty difficulty) {
        return RANDOM.nextFloat() < getMainhandEquipChance(difficulty);
    }

    private static boolean shouldEquipOffhand(Difficulty difficulty) {
        return RANDOM.nextFloat() < getOffhandEquipChance(difficulty);
    }

    private static boolean shouldEquipArmor(Difficulty difficulty, float baseChance) {
        return RANDOM.nextFloat() < getArmorEquipChance(difficulty, baseChance);
    }

    public static List<String> getEquipCommands(float equipChanceArmor, Entity entity) {
        List<String> cmds = new ArrayList<>();
        String generatedOffhandItem = null;
        String previousArmorItemId = null;
        Difficulty difficulty = getCurrentDifficulty(entity);
        boolean allowOffhandShield = rollOffhandShield(difficulty);

        for (String slot : EQUIPMENT_SLOTS) {
            List<String> pool = getAvailableItemIds(slot, difficulty);
            if (pool.isEmpty()) continue;

            if (slot.equals("MAINHAND") && !shouldEquipMainhand(difficulty)) {
                continue;
            }

            if (slot.equals("OFFHAND") && !shouldEquipOffhand(difficulty)) {
                continue;
            }

            if (isArmorSlot(slot) && !shouldEquipArmor(difficulty, equipChanceArmor)) {
                continue;
            }

            String itemId;
            if (isArmorSlot(slot)) {
                Optional<String> armorItem = getArmorItemForSlot(slot, pool, previousArmorItemId);
                if (armorItem.isEmpty()) continue;
                itemId = armorItem.get();
            } else if (slot.equals("OFFHAND") && generatedOffhandItem != null) {
                itemId = generatedOffhandItem;
            } else {
                Optional<String> selectedItem = slot.equals("OFFHAND")
                        ? getRandomOffhandPoolItem(pool, allowOffhandShield)
                        : Optional.of(pool.get(RANDOM.nextInt(pool.size())));
                if (selectedItem.isEmpty()) continue;
                itemId = selectedItem.get();
            }

            String[] parts = itemId.split(":", 2);
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(parts[0], parts[1]));
            if (item == null) continue;

            ItemStack itemStack = new ItemStack(item);
            String itemArgument = itemId;
            if (itemStack.isDamageableItem()) {
                int damage = getRandomDamage(itemStack);
                itemArgument += "[minecraft:damage=" + damage + "]";
            }
            cmds.add(String.format("item replace entity @s %s with %s", mapSlot(slot), itemArgument));

            if (slot.equals("MAINHAND")) {
                generatedOffhandItem = getGeneratedOffhandItem(itemStack, difficulty, allowOffhandShield).orElse(null);
            }
            if (isArmorSlot(slot)) {
                previousArmorItemId = itemId;
            }
        }

        return cmds;
    }

    public static Optional<String> getRandomSpecificSlot(String slot) {
        List<String> pool = getAvailableItemIds(slot, getCurrentDifficulty(null));
        if (pool.isEmpty()) return Optional.empty();

        String itemId = pool.get(RANDOM.nextInt(pool.size()));
        String[] parts = itemId.split(":", 2);
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(parts[0], parts[1]));
        if (item == null) return Optional.empty();

        ItemStack itemStack = new ItemStack(item);
        String itemArgument = itemId;
        if (itemStack.isDamageableItem()) {
            int damage = getRandomDamage(itemStack);
            itemArgument += "[minecraft:damage=" + damage + "]";
        }

        return Optional.of(String.format("item replace entity @s %s with %s", mapSlot(slot), itemArgument));
    }

    private record EquipmentEntry(String itemId, Difficulty minDifficulty) {
        private boolean canUse(Difficulty difficulty) {
            return difficulty.ordinal() >= this.minDifficulty.ordinal();
        }
    }

    private static String mapSlot(String slot) {
        return switch (slot) {
            case "MAINHAND" -> "weapon.mainhand";
            case "OFFHAND" -> "weapon.offhand";
            case "HEAD" -> "armor.head";
            case "CHEST" -> "armor.chest";
            case "LEGS" -> "armor.legs";
            case "FEET" -> "armor.feet";
            default -> slot.toLowerCase();
        };
    }
}
