package com.pla.smart_npc.forge;
import com.pla.smart_npc.SmartNpc;
import static com.pla.smart_npc.SmartNpc.MODID;

import com.mojang.serialization.Codec;
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
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.init.SmartNpcModMenus;
import com.pla.smart_npc.world.PlayerNpcMobSpawnBiomeModifier;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(SmartNpc.MODID)
public class SmartNpcForge {

    public SmartNpcForge(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        dev.architectury.platform.forge.EventBuses.registerModEventBus(MODID, modEventBus);
        SmartNpc.init();
        modEventBus.addListener(this::registerAttributes);
        modEventBus.addListener(this::registerSpawnPlacements);

        DeferredRegister<Codec<? extends BiomeModifier>> biomeModifiers =
                DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MODID);
        biomeModifiers.register(modEventBus);
        biomeModifiers.register("player_npc_spawns", PlayerNpcMobSpawnBiomeModifier::makeCodec);

        modEventBus.addListener((net.minecraftforge.fml.event.config.ModConfigEvent.Loading event) -> SmartNpcNamesConfig.onConfigLoading(event.getConfig()));
        modEventBus.addListener((net.minecraftforge.fml.event.config.ModConfigEvent.Reloading event) -> SmartNpcNamesConfig.onConfigReloading(event.getConfig()));
        context.registerConfig(ModConfig.Type.COMMON, SmartNpcConfig.SPEC, "smart_npc-server.toml");
        context.registerConfig(ModConfig.Type.COMMON, SmartNpcCompatConfig.SPEC, "smart_npc-compat.toml");
        context.registerConfig(ModConfig.Type.COMMON, SmartNpcNamesConfig.SPEC, "smart_npc-names.toml");
        if (ModList.get().isLoaded("epicfight")) {
            context.registerConfig(ModConfig.Type.COMMON, SmartNpcEpicFightConfig.SPEC, "smart_npc-epicfight.toml");
            modEventBus.register(EpicFightCloneAnimations.class);
            modEventBus.register(EpicFightSmartNpcPatches.class);
            if (FMLEnvironment.dist == Dist.CLIENT) {
                modEventBus.register(EpicFightSmartNpcPatchedRenderer.class);
            }
        }

        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(this::clientSetup);
        }
        modEventBus.addListener(this::commonSetup);
    }

    private void registerAttributes(net.minecraftforge.event.entity.EntityAttributeCreationEvent event) {
        event.put(SmartNpcModEntities.PLAYER_NPC.get(), com.pla.smart_npc.entity.PlayerNpcEntity.createAttributes().build());
    }
    private void registerSpawnPlacements(net.minecraftforge.event.entity.SpawnPlacementRegisterEvent event) {
        event.register(SmartNpcModEntities.PLAYER_NPC.get(), net.minecraft.world.entity.SpawnPlacements.Type.ON_GROUND, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, com.pla.smart_npc.entity.PlayerNpcEntity::canSpawn, net.minecraftforge.event.entity.SpawnPlacementRegisterEvent.Operation.REPLACE);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("epicfight")) {
            event.enqueueWork(EpicFight::registerArmatures);
        }
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(SmartNpcModMenus.INVENTORY_VIEWER.get(), InventoryViewerScreen::new);
            SmartNpcClientItemProperties.register();
        });
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
        }
    }
}
