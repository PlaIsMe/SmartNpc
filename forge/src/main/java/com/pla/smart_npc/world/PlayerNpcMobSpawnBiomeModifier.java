package com.pla.smart_npc.world;

import com.mojang.serialization.Codec;
import com.pla.smart_npc.SmartNpc;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class PlayerNpcMobSpawnBiomeModifier implements BiomeModifier {

    private static final RegistryObject<Codec<? extends BiomeModifier>> SERIALIZER = RegistryObject
            .create(new ResourceLocation(SmartNpc.MODID, "player_npc_spawns"),
                    ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, SmartNpc.MODID);

    @Override
    public void modify(Holder<Biome> biomeHolder, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.ADD) {
            return;
        }
        if (!biomeHolder.is(BiomeTags.IS_OVERWORLD)) return;
        PlayerNpcWorldSpawns.addBiomeSpawns(builder);
    }

    @Override public Codec<? extends BiomeModifier> codec() {
        return SERIALIZER.get();
    }
    public static Codec<PlayerNpcMobSpawnBiomeModifier> makeCodec() {
        return Codec.unit(PlayerNpcMobSpawnBiomeModifier::new);
    }
}
