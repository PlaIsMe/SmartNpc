package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PlayerNpcForceTickData extends SavedData {
    private static final String DATA_NAME = SmartNpc.MODID + "_player_npc_force_tick";
    private static final String NPCS_TAG = "Npcs";
    private static final String ID_TAG = "Id";
    private static final String DIMENSION_TAG = "Dimension";
    private static final String CHUNK_X_TAG = "ChunkX";
    private static final String CHUNK_Z_TAG = "ChunkZ";
    private static final String USERNAME_TAG = "Username";

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static PlayerNpcForceTickData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayerNpcForceTickData::load,
                PlayerNpcForceTickData::new,
                DATA_NAME
        );
    }

    public static PlayerNpcForceTickData load(CompoundTag tag) {
        PlayerNpcForceTickData data = new PlayerNpcForceTickData();
        ListTag npcs = tag.getList(NPCS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < npcs.size(); i++) {
            CompoundTag npcTag = npcs.getCompound(i);
            if (!npcTag.hasUUID(ID_TAG) || !npcTag.contains(DIMENSION_TAG, Tag.TAG_STRING)) {
                continue;
            }

            ResourceLocation dimensionId = ResourceLocation.tryParse(npcTag.getString(DIMENSION_TAG));
            if (dimensionId == null) {
                continue;
            }

            UUID npcId = npcTag.getUUID(ID_TAG);
            ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
            ChunkPos centerChunk = new ChunkPos(npcTag.getInt(CHUNK_X_TAG), npcTag.getInt(CHUNK_Z_TAG));
            String username = npcTag.contains(USERNAME_TAG, Tag.TAG_STRING)
                    ? npcTag.getString(USERNAME_TAG)
                    : "";
            data.entries.put(npcId, new Entry(npcId, levelKey, centerChunk, username));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag npcs = new ListTag();
        for (Entry entry : this.entries.values()) {
            CompoundTag npcTag = new CompoundTag();
            npcTag.putUUID(ID_TAG, entry.npcId());
            npcTag.putString(DIMENSION_TAG, entry.levelKey().location().toString());
            npcTag.putInt(CHUNK_X_TAG, entry.centerChunk().x);
            npcTag.putInt(CHUNK_Z_TAG, entry.centerChunk().z);
            if (!entry.username().isBlank()) {
                npcTag.putString(USERNAME_TAG, entry.username());
            }
            npcs.add(npcTag);
        }
        tag.put(NPCS_TAG, npcs);
        return tag;
    }

    public List<Entry> entries() {
        return new ArrayList<>(this.entries.values());
    }

    public void put(UUID npcId, ResourceKey<Level> levelKey, ChunkPos centerChunk, String username) {
        Entry nextEntry = new Entry(npcId, levelKey, centerChunk, Objects.requireNonNullElse(username, ""));
        if (Objects.equals(this.entries.get(npcId), nextEntry)) {
            return;
        }

        this.entries.put(npcId, nextEntry);
        this.setDirty();
    }

    public void remove(UUID npcId) {
        if (this.entries.remove(npcId) != null) {
            this.setDirty();
        }
    }

    public record Entry(UUID npcId, ResourceKey<Level> levelKey, ChunkPos centerChunk, String username) {
    }
}
