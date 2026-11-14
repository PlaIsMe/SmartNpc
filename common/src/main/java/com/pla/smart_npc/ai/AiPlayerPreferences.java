package com.pla.smart_npc.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** World-scoped consent survives logout, death and server restarts. */
public final class AiPlayerPreferences extends SavedData {
    private final Set<UUID> optedOut = new HashSet<>();
    public static AiPlayerPreferences get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(AiPlayerPreferences::load, AiPlayerPreferences::new, "smart_npc_ai_preferences");
    }
    private static AiPlayerPreferences load(CompoundTag tag) {
        AiPlayerPreferences result = new AiPlayerPreferences();
        ListTag entries = tag.getList("OptOut", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.getCompound(i).hasUUID("Id")) result.optedOut.add(entries.getCompound(i).getUUID("Id"));
        }
        return result;
    }
    public boolean allows(UUID player) { return !optedOut.contains(player); }
    public void setAllowed(UUID player, boolean allowed) {
        if (allowed ? optedOut.remove(player) : optedOut.add(player)) setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag) {
        ListTag entries = new ListTag();
        for (UUID id : optedOut) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            entries.add(entry);
        }
        tag.put("OptOut", entries);
        return tag;
    }
}
