package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;

public class SmartNpcModItems {
    public static final DeferredRegister.Items REGISTRY = DeferredRegister.createItems(SmartNpc.MODID);

    public static final DeferredItem<InventoryViewerItem> INVENTORY_VIEWER = SmartNpcModItems.REGISTRY.registerItem(
            "player_npc_inspector", InventoryViewerItem::new, properties -> properties.stacksTo(1));
    public static final DeferredItem<SpawnEggItem> PLAYER_NPC_SPAWN_EGG = SmartNpcModItems.REGISTRY.registerItem(
            "player_npc_spawn_egg",
            properties -> new SpawnEggItem(properties.spawnEgg(SmartNpcModEntities.PLAYER_NPC.get()))
    );

}
