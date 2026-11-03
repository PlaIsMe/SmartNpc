package com.pla.smart_npc.world;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PlayerNpcWorldSpawns {
    private static final Logger LOGGER = LogManager.getLogger();

    private PlayerNpcWorldSpawns() {}
    public static void addBiomeSpawns(ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        SmartNpcConfig.SpawnConfig spawnConfig = SmartNpcConfig.getPlayerNpcSpawnConfig();
        addSpawn(builder, Identifier.fromNamespaceAndPath(SmartNpc.MODID, SmartNpcModEntities.PLAYER_NPC_ID), spawnConfig);
    }

    private static void addSpawn(ModifiableBiomeInfo.BiomeInfo.Builder builder,
                                 Identifier entityId,
                                 SmartNpcConfig.SpawnConfig spawnConfig) {

        if (spawnConfig.weight() <= 0) return;
        EntityType<?> rawType = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);
        if (rawType == null) {
            LOGGER.warn("Spawn config refers to missing entity type: {}", entityId);
            return;
        }

        @SuppressWarnings("unchecked")
        EntityType<? extends Mob> mobType = (EntityType<? extends Mob>) rawType;

        builder.getMobSpawnSettings()
                .getSpawner(mobType.getCategory())
                .add(new MobSpawnSettings.SpawnerData(mobType, spawnConfig.minCount(), spawnConfig.maxCount()), spawnConfig.weight());
    }
}
