package com.pla.smart_npc.util;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.UUID;

/**
 * Bridges the mod's UUID persistence to the codec-oriented NBT API used by
 * Minecraft 1.21.11. UUIDs remain encoded as the vanilla four-int array, so old
 * SmartNpc worlds continue to load without a data migration.
 */
public final class SmartNpcNbt {
    public static boolean contains(ValueInput input, String key) {
        return input.read(key, Codec.PASSTHROUGH).isPresent();
    }
    private SmartNpcNbt() {
    }

    public static void putUuid(CompoundTag tag, String key, UUID uuid) {
        tag.putIntArray(key, UUIDUtil.uuidToIntArray(uuid));
    }

    public static boolean hasUuid(CompoundTag tag, String key) {
        return tag.getIntArray(key).filter(value -> value.length == 4).isPresent();
    }

    public static UUID getUuid(CompoundTag tag, String key) {
        return tag.getIntArray(key)
                .filter(value -> value.length == 4)
                .map(UUIDUtil::uuidFromIntArray)
                .orElseThrow(() -> new IllegalArgumentException("Missing or invalid UUID tag: " + key));
    }

    public static void putUuid(ValueOutput output, String key, UUID uuid) {
        output.putIntArray(key, UUIDUtil.uuidToIntArray(uuid));
    }

    public static boolean hasUuid(ValueInput input, String key) {
        return input.getIntArray(key).filter(value -> value.length == 4).isPresent();
    }

    public static UUID getUuid(ValueInput input, String key) {
        return input.getIntArray(key)
                .filter(value -> value.length == 4)
                .map(UUIDUtil::uuidFromIntArray)
                .orElseThrow(() -> new IllegalArgumentException("Missing or invalid UUID value: " + key));
    }
}
