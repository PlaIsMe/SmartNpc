package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import com.pla.smart_npc.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.Properties;

public class SmartNpcModItems {
    public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(SmartNpc.MODID, Registries.ITEM);

    public static final RegistrySupplier<Item> INVENTORY_VIEWER = SmartNpcModItems.REGISTRY.register("player_npc_inspector", InventoryViewerItem::new);
    public static final RegistrySupplier<Item> PLAYER_NPC_SPAWN_EGG = SmartNpcModItems.REGISTRY.register("player_npc_spawn_egg", () -> new dev.architectury.core.item.ArchitecturySpawnEggItem(SmartNpcModEntities.PLAYER_NPC, 0xFFF144, 0x69DFDA, (new Properties())));

}
