package com.pla.smart_npc.world;

import com.mojang.serialization.MapCodec;
import com.pla.smart_npc.SmartNpc;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

public final class PlayerNpcMobSpawnBiomeModifier implements BiomeModifier {

    @Override
    public void modify(net.minecraft.core.RegistryAccess registries, Holder<Biome> biomeHolder, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.ADD) {
            return;
        }
        if (!biomeHolder.is(BiomeTags.IS_OVERWORLD)) return;
        PlayerNpcWorldSpawns.addBiomeSpawns(builder);
    }

    @Override public MapCodec<? extends BiomeModifier> codec() {
        return com.pla.smart_npc.neoforge.SmartNpcNeoForge.PLAYER_NPC_SPAWNS.get();
    }
    public static MapCodec<PlayerNpcMobSpawnBiomeModifier> makeCodec() {
        return MapCodec.unit(PlayerNpcMobSpawnBiomeModifier::new);
    }
}
