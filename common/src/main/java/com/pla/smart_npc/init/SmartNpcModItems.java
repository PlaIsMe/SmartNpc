package com.pla.smart_npc.init;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.item.InventoryViewerItem;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
public class SmartNpcModItems {
    public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(SmartNpc.MODID, Registries.ITEM);
    public static final RegistrySupplier<InventoryViewerItem> INVENTORY_VIEWER = REGISTRY.register("player_npc_inspector", () -> new InventoryViewerItem(properties("player_npc_inspector").stacksTo(1)));
    public static final RegistrySupplier<SpawnEggItem> PLAYER_NPC_SPAWN_EGG = REGISTRY.register("player_npc_spawn_egg", () -> new SpawnEggItem(properties("player_npc_spawn_egg").spawnEgg(SmartNpcModEntities.PLAYER_NPC.get())));
    private static Item.Properties properties(String name) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(SmartNpc.MODID, name)));
    }
}
