package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.platform.NpcPlatform;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import com.pla.smart_npc.entity.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public class SmartNpcModEntities {

    public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(SmartNpc.MODID, Registries.ENTITY_TYPE);
    public static final String PLAYER_NPC_ID = "player_npc";
    public static final RegistrySupplier<EntityType<PlayerNpcEntity>> PLAYER_NPC = register(PLAYER_NPC_ID, PlayerNpcEntity::new, MobCategory.CREATURE, 0.6F, 1.8F, 256, 3);
    public static final String PLAYER_NPC_FISHING_BOBBER_ID = "player_npc_fishing_bobber";
    public static final RegistrySupplier<EntityType<PlayerNpcFishingBobberEntity>> PLAYER_NPC_FISHING_BOBBER = register(PLAYER_NPC_FISHING_BOBBER_ID, PlayerNpcFishingBobberEntity::new, MobCategory.MISC, 0.25F, 0.25F, 64, 5);

    private static <T extends Entity> RegistrySupplier<EntityType<T>> register(String id, EntityType.EntityFactory<T> factory,
            MobCategory category, float width, float height, int trackingRangeChunks, int updateInterval) {
        return REGISTRY.register(id, () -> NpcPlatform.createEntityType(SmartNpc.MODID + ":" + id, factory,
                category, width, height, trackingRangeChunks, updateInterval));
    }

}
