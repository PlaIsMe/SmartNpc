package com.pla.smart_npc.fabric;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.*;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.platform.event.NpcEvents;
import com.pla.smart_npc.platform.event.NpcEventHooks;
import com.pla.smart_npc.mixin.fabric.SpawnPlacementsInvoker;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.EntityEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import fuzs.forgeconfigapiport.api.config.v2.ModConfigEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.tags.BiomeTags;
import net.minecraftforge.fml.config.ModConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SmartNpcFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ModConfigEvents.loading(SmartNpc.MODID).register(SmartNpcNamesConfig::onConfigLoading);
        ModConfigEvents.reloading(SmartNpc.MODID).register(SmartNpcNamesConfig::onConfigReloading);
        SmartNpcCompatConfig.migrateLegacyEpicFightConfig();
        ForgeConfigRegistry.INSTANCE.register(SmartNpc.MODID, ModConfig.Type.COMMON, SmartNpcConfig.SPEC, "smart_npc-server.toml");
        ForgeConfigRegistry.INSTANCE.register(SmartNpc.MODID, ModConfig.Type.COMMON, SmartNpcCompatConfig.SPEC, "smart_npc-compat.toml");
        ForgeConfigRegistry.INSTANCE.register(SmartNpc.MODID, ModConfig.Type.COMMON, SmartNpcNamesConfig.SPEC, "smart_npc-names.toml");
        SmartNpc.init();
        FabricDefaultAttributeRegistry.register(SmartNpcModEntities.PLAYER_NPC.get(), PlayerNpcEntity.createAttributes());
        SpawnPlacementsInvoker.smartNpc$register(SmartNpcModEntities.PLAYER_NPC.get(), SpawnPlacements.Type.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, PlayerNpcEntity::canSpawn);
        SmartNpcConfig.SpawnConfig spawn = SmartNpcConfig.getPlayerNpcSpawnConfig();
        if (spawn.weight() > 0) BiomeModifications.addSpawn(BiomeSelectors.tag(BiomeTags.IS_OVERWORLD), SmartNpcModEntities.PLAYER_NPC.get().getCategory(), SmartNpcModEntities.PLAYER_NPC.get(), spawn.weight(), spawn.minCount(), spawn.maxCount());
        registerEvents();
        NpcEventHooks.fire(new NpcEvents.AddReloadListenerEvent(listener -> ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new ReloadAdapter(listener))));
    }

    private static void registerEvents() {
        LifecycleEvent.SERVER_STARTED.register(server -> NpcEventHooks.fire(new NpcEvents.ServerStartedEvent(server)));
        LifecycleEvent.SERVER_STOPPING.register(server -> NpcEventHooks.fire(new NpcEvents.ServerStoppingEvent(server)));
        LifecycleEvent.SERVER_STOPPED.register(server -> NpcEventHooks.fire(new NpcEvents.ServerStoppedEvent(server)));
        TickEvent.SERVER_PRE.register(server -> NpcEventHooks.fire(new NpcEvents.TickEvent.ServerTickEvent(server, NpcEvents.TickEvent.Phase.START)));
        TickEvent.SERVER_POST.register(server -> NpcEventHooks.fire(new NpcEvents.TickEvent.ServerTickEvent(server, NpcEvents.TickEvent.Phase.END)));
        TickEvent.PLAYER_POST.register(player -> NpcEventHooks.fire(new NpcEvents.TickEvent.PlayerTickEvent(player, NpcEvents.TickEvent.Phase.END)));
        PlayerEvent.PLAYER_JOIN.register(player -> NpcEventHooks.fire(new NpcEvents.PlayerEvent.PlayerLoggedInEvent(player)));
        PlayerEvent.PLAYER_QUIT.register(player -> NpcEventHooks.fire(new NpcEvents.PlayerEvent.PlayerLoggedOutEvent(player)));
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd) -> NpcEventHooks.fire(new NpcEvents.PlayerEvent.PlayerRespawnEvent(player)));
        PlayerEvent.PLAYER_CLONE.register((oldPlayer, newPlayer, wonGame) -> NpcEventHooks.fire(new NpcEvents.PlayerEvent.Clone(newPlayer, oldPlayer)));
        PlayerEvent.CHANGE_DIMENSION.register((player, oldDimension, newDimension) -> NpcEventHooks.fire(new NpcEvents.PlayerEvent.PlayerChangedDimensionEvent(player)));
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> NpcEventHooks.fire(new NpcEvents.EntityJoinLevelEvent(entity, level)));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> NpcEventHooks.fire(new NpcEvents.EntityLeaveLevelEvent(entity, level)));
        EntityEvent.LIVING_HURT.register((entity, source, amount) -> {
            var event = new NpcEvents.LivingAttackEvent(entity, source);
            NpcEventHooks.fire(event);
            return event.isCanceled() ? EventResult.interruptFalse() : EventResult.pass();
        });
        EntityEvent.LIVING_DEATH.register((entity, source) -> { NpcEventHooks.fire(new NpcEvents.LivingDeathEvent(entity)); return EventResult.pass(); });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> NpcEventHooks.fire(new NpcEvents.RegisterCommandsEvent(dispatcher)));
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            var event = new NpcEvents.BlockEvent.BreakEvent(level, pos, state, player);
            NpcEventHooks.fire(event);
            return !event.isCanceled();
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide && hand == InteractionHand.MAIN_HAND) NpcEventHooks.fire(new NpcEvents.PlayerInteractEvent.RightClickBlock(level, hit.getBlockPos(), player));
            return InteractionResult.PASS;
        });
    }

    private record ReloadAdapter(PreparableReloadListener delegate) implements IdentifiableResourceReloadListener {
        @Override public ResourceLocation getFabricId() { return new ResourceLocation(SmartNpc.MODID, delegate.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT)); }
        @Override public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources, ProfilerFiller preparation, ProfilerFiller apply, Executor prepareExecutor, Executor applyExecutor) {
            return delegate.reload(barrier, resources, preparation, apply, prepareExecutor, applyExecutor);
        }
    }
}
