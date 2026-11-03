package com.pla.smart_npc;

import com.mojang.serialization.MapCodec;
import com.pla.smart_npc.client.SmartNpcClientItemProperties;
import com.pla.smart_npc.client.gui.InventoryViewerScreen;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.config.SmartNpcNamesConfig;
import com.pla.smart_npc.event.NpcGearLoadEvent;
import com.pla.smart_npc.init.SmartNpcModCreativeTabs;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.init.SmartNpcModEntityRenderers;
import com.pla.smart_npc.init.SmartNpcModItems;
import com.pla.smart_npc.init.SmartNpcModMenus;
import com.pla.smart_npc.network.SmartNpcNetwork;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.world.PlayerNpcMobSpawnBiomeModifier;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(SmartNpc.MODID)
public class SmartNpc {
    public static final Logger LOGGER = LogManager.getLogger(SmartNpc.class);
    public static final String MODID = "smart_npc";
    public static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MODID);
    public static final DeferredHolder<MapCodec<? extends BiomeModifier>, MapCodec<PlayerNpcMobSpawnBiomeModifier>> PLAYER_NPC_SPAWNS =
            BIOME_MODIFIERS.register("player_npc_spawns", PlayerNpcMobSpawnBiomeModifier::makeCodec);

    public SmartNpc(IEventBus modEventBus, ModContainer modContainer) {
        SmartNpcModItems.REGISTRY.register(modEventBus);
        SmartNpcModMenus.REGISTRY.register(modEventBus);
        SmartNpcModEntities.REGISTRY.register(modEventBus);
        modEventBus.register(SmartNpcModEntities.class);
        SmartNpcModCreativeTabs.register(modEventBus);
        modEventBus.addListener(SmartNpcNetwork::register);
        modEventBus.addListener(this::registerTicketControllers);

        BIOME_MODIFIERS.register(modEventBus);

        NeoForge.EVENT_BUS.register(new NpcGearLoadEvent());
        modEventBus.addListener(SmartNpcNamesConfig::onConfigLoading);
        modEventBus.addListener(SmartNpcNamesConfig::onConfigReloading);
        modContainer.registerConfig(ModConfig.Type.COMMON, SmartNpcConfig.SPEC, "smart_npc-server.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, SmartNpcNamesConfig.SPEC, "smart_npc-names.toml");


        if (FMLEnvironment.getDist().isClient()) {
            modEventBus.register(SmartNpcModEntityRenderers.class);
            modEventBus.addListener(this::clientSetup);
            modEventBus.addListener(this::registerScreens);
        }
    }

    private void registerTicketControllers(final RegisterTicketControllersEvent event) {
        event.register(PlayerNpcForceTickManager.PLAYER_NPC_TICKET);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(SmartNpcClientItemProperties::register);
    }

    private void registerScreens(final RegisterMenuScreensEvent event) {
        event.register(SmartNpcModMenus.INVENTORY_VIEWER.get(), InventoryViewerScreen::new);
    }
}
