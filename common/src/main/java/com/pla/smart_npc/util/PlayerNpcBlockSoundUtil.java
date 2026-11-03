package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

public final class PlayerNpcBlockSoundUtil {
    private PlayerNpcBlockSoundUtil() {
    }

    public static void playMiningHitSound(ServerLevel serverLevel, BlockPos pos, BlockState state, PlayerNpcEntity playerNpc) {
        SoundType soundType = com.pla.smart_npc.platform.NpcPlatform.soundType(state, serverLevel, pos, playerNpc);
        serverLevel.playSound(
                null,
                pos,
                soundType.getHitSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 8.0F,
                soundType.getPitch() * 0.5F
        );
    }

    public static void playPlaceSound(ServerLevel serverLevel, BlockPos pos, BlockState state, PlayerNpcEntity playerNpc) {
        SoundType soundType = com.pla.smart_npc.platform.NpcPlatform.soundType(state, serverLevel, pos, playerNpc);
        serverLevel.playSound(
                null,
                pos,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F
        );
    }
}
