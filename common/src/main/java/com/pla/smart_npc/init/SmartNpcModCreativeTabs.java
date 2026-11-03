package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class SmartNpcModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(SmartNpc.MODID, Registries.CREATIVE_MODE_TAB);

    public static final RegistrySupplier<CreativeModeTab> PLAYER_NPC_TAB = CREATIVE_MODE_TABS.register("player_npc_tab",
            () -> dev.architectury.registry.CreativeTabRegistry.create(builder -> builder
                    .icon(() -> new ItemStack(SmartNpcModItems.INVENTORY_VIEWER.get()))
                    .title(Component.translatable("creativetab.player_npc_tab"))
                    .displayItems((pParameters, pOutput) -> {
                        pOutput.accept(SmartNpcModItems.INVENTORY_VIEWER.get());
                        pOutput.accept(SmartNpcModItems.PLAYER_NPC_SPAWN_EGG.get());
                    })
                    ));

    public static void register() {
        CREATIVE_MODE_TABS.register();
    }
}
