package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Persistent living identities and tested automatic-cap evidence for natural-spawn admission. */
public final class PlayerNpcPopulationData extends SavedData {
    private static final String DATA_NAME = SmartNpc.MODID + "_player_npc_population";
    private static final SavedDataType<PlayerNpcPopulationData> TYPE = new SavedDataType<>(
            DATA_NAME,
            PlayerNpcPopulationData::new,
            CompoundTag.CODEC.xmap(tag -> load(tag, null), data -> data.save(new CompoundTag(), null)),
            null
    );
    private static final String NPCS_TAG = "Npcs";
    private static final String ID_TAG = "Id";
    private static final String INITIALIZED_TAG = "Initialized";
    private static final String LEARNED_AUTO_CAP_TAG = "LearnedAutoCap";

    private final Set<UUID> npcIds = new LinkedHashSet<>();
    private boolean initialized;
    private int learnedAutoCap;

    public static PlayerNpcPopulationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static PlayerNpcPopulationData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerNpcPopulationData data = new PlayerNpcPopulationData();
        data.initialized = tag.getBooleanOr(INITIALIZED_TAG, false);
        data.learnedAutoCap = Math.max(0, tag.getIntOr(LEARNED_AUTO_CAP_TAG, 0));
        ListTag npcs = tag.getListOrEmpty(NPCS_TAG);
        for (int index = 0; index < npcs.size(); index++) {
            CompoundTag npcTag = npcs.getCompoundOrEmpty(index);
            if (SmartNpcNbt.hasUuid(npcTag, ID_TAG)) {
                data.npcIds.add(SmartNpcNbt.getUuid(npcTag, ID_TAG));
            }
        }
        return data;
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag npcs = new ListTag();
        for (UUID npcId : this.npcIds) {
            CompoundTag npcTag = new CompoundTag();
            SmartNpcNbt.putUuid(npcTag, ID_TAG, npcId);
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
