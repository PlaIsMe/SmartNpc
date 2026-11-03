package com.pla.smart_npc.neoforge;
import com.pla.smart_npc.SmartNpc;

import com.mojang.serialization.MapCodec;
import com.pla.smart_npc.client.SmartNpcClientItemProperties;
import com.pla.smart_npc.client.gui.InventoryViewerScreen;
import com.pla.smart_npc.compat.epicfight.EpicFight;
import com.pla.smart_npc.compat.epicfight.EpicFightCloneAnimations;
import com.pla.smart_npc.compat.epicfight.EpicFightSmartNpcPatchedRenderer;
import com.pla.smart_npc.compat.epicfight.EpicFightSmartNpcPatches;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.config.SmartNpcCompatConfig;
import com.pla.smart_npc.config.SmartNpcEpicFightConfig;
import com.pla.smart_npc.config.SmartNpcNamesConfig;
import com.pla.smart_npc.event.NpcGearLoadEvent;
import com.pla.smart_npc.init.SmartNpcModCreativeTabs;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.neoforge.client.SmartNpcModEntityRenderers;
import com.pla.smart_npc.init.SmartNpcModItems;
import com.pla.smart_npc.init.SmartNpcModMenus;
import com.pla.smart_npc.network.SmartNpcNetwork;
import com.pla.smart_npc.world.PlayerNpcMobSpawnBiomeModifier;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(SmartNpc.MODID)
public class SmartNpcNeoForge {
    public static IEventBus MOD_BUS;
    public static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, SmartNpc.MODID);
    public static final DeferredHolder<MapCodec<? extends BiomeModifier>, MapCodec<PlayerNpcMobSpawnBiomeModifier>> PLAYER_NPC_SPAWNS =
            BIOME_MODIFIERS.register("player_npc_spawns", PlayerNpcMobSpawnBiomeModifier::makeCodec);

    public SmartNpcNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        MOD_BUS = modEventBus;
        SmartNpc.init();
        modEventBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) -> event.put(SmartNpcModEntities.PLAYER_NPC.get(), com.pla.smart_npc.entity.PlayerNpcEntity.createAttributes().build()));
        modEventBus.addListener((net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent event) -> event.register(SmartNpcModEntities.PLAYER_NPC.get(), net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, com.pla.smart_npc.entity.PlayerNpcEntity::canSpawn, net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent.Operation.REPLACE));

        BIOME_MODIFIERS.register(modEventBus);

        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Loading event) -> SmartNpcNamesConfig.onConfigLoading(event.getConfig()));
        modEventBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) -> SmartNpcNamesConfig.onConfigReloading(event.getConfig()));
        modContainer.registerConfig(ModConfig.Type.COMMON, SmartNpcConfig.SPEC, "smart_npc-server.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, SmartNpcCompatConfig.SPEC, "smart_npc-compat.toml");
        modContainer.registerConfig(ModConfig.Type.COMMON, SmartNpcNamesConfig.SPEC, "smart_npc-names.toml");
        if (ModList.get().isLoaded("epicfight")) {
            modContainer.registerConfig(ModConfig.Type.COMMON, SmartNpcEpicFightConfig.SPEC, "smart_npc-epicfight.toml");
            modEventBus.register(EpicFightCloneAnimations.class);
            modEventBus.register(EpicFightSmartNpcPatches.class);
            EpicFightSmartNpcPatches.registerPatchHook();
            if (FMLEnvironment.dist.isClient()) {
                EpicFightSmartNpcPatchedRenderer.registerRendererHook();
            }
        }

        if (FMLEnvironment.dist.isClient()) {
            modEventBus.register(SmartNpcModEntityRenderers.class);
            modEventBus.addListener(this::clientSetup);
            modEventBus.addListener(this::registerScreens);
        }
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("epicfight")) {
            event.enqueueWork(EpicFight::registerArmatures);
        }
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(SmartNpcClientItemProperties::register);
    }

    private void registerScreens(final RegisterMenuScreensEvent event) {
        event.register(SmartNpcModMenus.INVENTORY_VIEWER.get(), InventoryViewerScreen::new);
    }
}
