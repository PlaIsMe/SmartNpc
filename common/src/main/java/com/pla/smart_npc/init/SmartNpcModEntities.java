package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import com.pla.smart_npc.entity.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityType.Builder;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;

public class SmartNpcModEntities {

    public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(SmartNpc.MODID, Registries.ENTITY_TYPE);
    public static final String PLAYER_NPC_ID = "player_npc";
    public static final RegistrySupplier<EntityType<PlayerNpcEntity>> PLAYER_NPC = register(PLAYER_NPC_ID, Builder.<PlayerNpcEntity>of(PlayerNpcEntity::new, MobCategory.CREATURE).clientTrackingRange(256).updateInterval(3).sized(0.6F, 1.8F));
    public static final String PLAYER_NPC_FISHING_BOBBER_ID = "player_npc_fishing_bobber";
    public static final RegistrySupplier<EntityType<PlayerNpcFishingBobberEntity>> PLAYER_NPC_FISHING_BOBBER = register(PLAYER_NPC_FISHING_BOBBER_ID, Builder.<PlayerNpcFishingBobberEntity>of(PlayerNpcFishingBobberEntity::new, MobCategory.MISC).clientTrackingRange(64).updateInterval(5).sized(0.25F, 0.25F));

    private static <T extends Entity> RegistrySupplier<EntityType<T>> register(String s, Builder<T> builder) {
        return SmartNpcModEntities.REGISTRY.register(s, () -> builder.build(s));
    }

}
