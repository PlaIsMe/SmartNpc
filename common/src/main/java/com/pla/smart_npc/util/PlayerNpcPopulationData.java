package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Persistent living identities and tested automatic-cap evidence for natural-spawn admission. */
public final class PlayerNpcPopulationData extends SavedData {
    private static final String DATA_NAME = SmartNpc.MODID + "_player_npc_population";
    private static final String NPCS_TAG = "Npcs";
    private static final String ID_TAG = "Id";
    private static final String INITIALIZED_TAG = "Initialized";
    private static final String LEARNED_AUTO_CAP_TAG = "LearnedAutoCap";

    private final Set<UUID> npcIds = new LinkedHashSet<>();
    private boolean initialized;
    private int learnedAutoCap;

    public static PlayerNpcPopulationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayerNpcPopulationData::load,
                PlayerNpcPopulationData::new,
                DATA_NAME
        );
    }

    public static PlayerNpcPopulationData load(CompoundTag tag) {
        PlayerNpcPopulationData data = new PlayerNpcPopulationData();
        data.initialized = tag.getBoolean(INITIALIZED_TAG);
        data.learnedAutoCap = Math.max(0, tag.getInt(LEARNED_AUTO_CAP_TAG));
        ListTag npcs = tag.getList(NPCS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < npcs.size(); index++) {
            CompoundTag npcTag = npcs.getCompound(index);
            if (npcTag.hasUUID(ID_TAG)) {
                data.npcIds.add(npcTag.getUUID(ID_TAG));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag npcs = new ListTag();
        for (UUID npcId : this.npcIds) {
            CompoundTag npcTag = new CompoundTag();
            npcTag.putUUID(ID_TAG, npcId);
            npcs.add(npcTag);
        }
        tag.put(NPCS_TAG, npcs);
        tag.putBoolean(INITIALIZED_TAG, this.initialized);
        tag.putInt(LEARNED_AUTO_CAP_TAG, this.learnedAutoCap);
        return tag;
    }

    public Set<UUID> npcIds() {
        return Set.copyOf(this.npcIds);
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public int learnedAutoCap() {
        return this.learnedAutoCap;
    }

    public void replace(Set<UUID> nextNpcIds, int nextLearnedAutoCap) {
        int boundedLearnedAutoCap = Math.max(0, nextLearnedAutoCap);
        if (this.initialized
                && this.npcIds.equals(nextNpcIds)
                && this.learnedAutoCap == boundedLearnedAutoCap) {
            return;
        }
        this.initialized = true;
        this.npcIds.clear();
        this.npcIds.addAll(nextNpcIds);
        this.learnedAutoCap = boundedLearnedAutoCap;
        this.setDirty();
    }
}
