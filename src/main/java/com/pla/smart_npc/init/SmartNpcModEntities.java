package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityType.Builder;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;

public class SmartNpcModEntities {

    public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, SmartNpc.MODID);
    public static final String PLAYER_NPC_ID = "player_npc";
    public static final DeferredHolder<EntityType<?>, EntityType<PlayerNpcEntity>> PLAYER_NPC = register(PLAYER_NPC_ID, Builder.<PlayerNpcEntity>of(PlayerNpcEntity::new, MobCategory.CREATURE).setShouldReceiveVelocityUpdates(true).setTrackingRange(256).setUpdateInterval(3).sized(0.6F, 1.8F));
    public static final String PLAYER_NPC_FISHING_BOBBER_ID = "player_npc_fishing_bobber";
    public static final DeferredHolder<EntityType<?>, EntityType<PlayerNpcFishingBobberEntity>> PLAYER_NPC_FISHING_BOBBER = register(PLAYER_NPC_FISHING_BOBBER_ID, Builder.<PlayerNpcFishingBobberEntity>of(PlayerNpcFishingBobberEntity::new, MobCategory.MISC).setShouldReceiveVelocityUpdates(true).setTrackingRange(64).setUpdateInterval(5).sized(0.25F, 0.25F));

    private static <T extends Entity> DeferredHolder<EntityType<?>, EntityType<T>> register(String s, Builder<T> builder) {
        return SmartNpcModEntities.REGISTRY.register(s, id -> builder.build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id)));
    }

    @SubscribeEvent
    public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
        event.register(
                SmartNpcModEntities.PLAYER_NPC.get(),
                net.minecraft.world.entity.SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                PlayerNpcEntity::canSpawn,
                RegisterSpawnPlacementsEvent.Operation.REPLACE
        );
    }

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent entityAttributeCreationEvent) {
        entityAttributeCreationEvent.put(SmartNpcModEntities.PLAYER_NPC.get(), PlayerNpcEntity.createAttributes().build());
    }
}
