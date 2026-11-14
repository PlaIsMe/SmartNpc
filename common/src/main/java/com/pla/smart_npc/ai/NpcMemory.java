package com.pla.smart_npc.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.platform.NpcPlatform;
import net.minecraft.nbt.CompoundTag;
import java.util.List;
import java.util.UUID;

/** Only personality and a bounded relationship ledger persist; raw player chat does not. */
public final class NpcMemory {
    private static final String KEY = "SmartNpcAiMemory";
    public static final List<String> PERSONALITIES = List.of("quiet", "playful", "serious", "kind", "cautious", "greedy");
    private NpcMemory() {}
    /** Describe the existing diagnostic state to the model; never use this to authorize behavior. */
    static String activityText(String state) {
        if (state == null || state.isBlank()) return "unknown";
        String prefix = "ai.player_npc.";
        return state.startsWith(prefix) ? state.substring(prefix.length()).replace('_', ' ') : state;
    }
    public static String personality(PlayerNpcEntity npc) {
        String saved = NpcPlatform.persistentData(npc).getCompound(KEY).getString("Personality");
        return PERSONALITIES.contains(saved) ? saved : PERSONALITIES.get(Math.floorMod(npc.getUUID().hashCode(), PERSONALITIES.size()));
    }
    public static void personality(PlayerNpcEntity npc, String value) {
        if (!PERSONALITIES.contains(value)) throw new IllegalArgumentException("Unknown personality");
        CompoundTag data = NpcPlatform.persistentData(npc).getCompound(KEY);
        data.putString("Personality", value);
        NpcPlatform.persistentData(npc).put(KEY, data);
    }
    public static int relationship(PlayerNpcEntity npc, UUID player) {
        return NpcPlatform.persistentData(npc).getCompound(KEY).getCompound("Relationships").getInt(player.toString());
    }
    public static void appreciate(PlayerNpcEntity npc, UUID player) {
        CompoundTag data = NpcPlatform.persistentData(npc).getCompound(KEY);
        CompoundTag relationships = data.getCompound("Relationships");
        if (!relationships.contains(player.toString()) && relationships.getAllKeys().size() >= 16) return;
        relationships.putInt(player.toString(), Math.min(20, relationship(npc, player) + 1));
        data.put("Relationships", relationships);
        NpcPlatform.persistentData(npc).put(KEY, data);
    }
}
