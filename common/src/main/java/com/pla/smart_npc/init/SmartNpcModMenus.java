package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import com.pla.smart_npc.inventory.InventoryViewerMenu;
import net.minecraft.world.inventory.MenuType;

public class SmartNpcModMenus {
    public static final DeferredRegister<MenuType<?>> REGISTRY = DeferredRegister.create(SmartNpc.MODID, Registries.MENU);

    public static final RegistrySupplier<MenuType<InventoryViewerMenu>> INVENTORY_VIEWER = REGISTRY.register(
            "inventory_viewer",
            () -> dev.architectury.registry.menu.MenuRegistry.ofExtended(InventoryViewerMenu::new)
    );
}
