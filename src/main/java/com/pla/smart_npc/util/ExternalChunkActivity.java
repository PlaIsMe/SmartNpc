package com.pla.smart_npc.util;

import com.pla.smart_npc.mixin.DistanceManagerAccessor;
import com.pla.smart_npc.mixin.ServerChunkCacheAccessor;
import com.pla.smart_npc.mixin.TicketOwnerAccessor;
import com.pla.smart_npc.mixin.TicketStorageAccessor;
import com.pla.smart_npc.mixin.TicketTrackerAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.neoforged.neoforge.common.NeoForgeMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Read-only ticket inspection. Never requests chunks or temporarily removes an NPC ticket. */
public final class ExternalChunkActivity {
    private static final Map<ServerLevel, Snapshot> CACHE = new WeakHashMap<>();
    private ExternalChunkActivity() {}

    public static boolean isExternallyLoaded(ServerLevel level, ChunkPos chunk) {
        return snapshot(level).covers(chunk);
    }

    public static boolean isExternallyLoadedNow(ServerLevel level, ChunkPos chunk) {
        Snapshot snapshot = capture(level);
        CACHE.put(level, snapshot);
        return snapshot.covers(chunk);
    }

    public static void clear() { CACHE.clear(); }

    private static Snapshot snapshot(ServerLevel level) {
        Snapshot old = CACHE.get(level);
        long now = level.getGameTime();
        if (old == null || now < old.tick || now - old.tick >= 20) {
            old = capture(level);
            CACHE.put(level, old);
        }
        return old;
    }

    private static Snapshot capture(ServerLevel level) {
        var manager = ((ServerChunkCacheAccessor) level.getChunkSource()).smartNpc$getDistanceManager();
        TicketStorage storage = ((DistanceManagerAccessor) manager).smartNpc$getTicketStorage();
        var tickets = ((TicketStorageAccessor) storage).smartNpc$getTickets();
        List<Anchor> anchors = new ArrayList<>();
        for (var entry : tickets.long2ObjectEntrySet()) {
            int minLevel = 34;
            for (Ticket ticket : entry.getValue()) {
                TicketType type = ticket.getType();
                Identifier id = BuiltInRegistries.TICKET_TYPE.getKey(type);
                // Both mods can be installed together. NPCs must not keep one another's
                // departure timers alive. UNKNOWN/LIGHT are transient chunk reads by AI.
                // Vanilla spawn chunks remain loaded after all players travel away. They
                // are not attendance, otherwise initial-spawn NPCs would never leave.
                if (type == TicketType.UNKNOWN || type == TicketType.PLAYER_SPAWN
                        || isOnlySmartNpcEntityTicket(storage, entry.getLongKey(), type)
                        || id.equals(Identifier.fromNamespaceAndPath("annoyingvillagers", "persistent_player_npc"))) continue;
                minLevel = Math.min(minLevel, ticket.getTicketLevel());
            }
            if (minLevel <= 33) anchors.add(new Anchor(new ChunkPos(entry.getLongKey()), 33 - minLevel));
        }
        return new Snapshot(level.getGameTime(), anchors);
    }

    private static boolean isOnlySmartNpcEntityTicket(TicketStorage storage, long chunk, TicketType type) {
        if (type != NeoForgeMod.ENTITY_TICKET.value()) return false;
        var sources = ((TicketTrackerAccessor) storage.getEntityForcedChunks()).smartNpc$getSourcesLoading().get(chunk);
        if (sources == null || sources.isEmpty()) return false;
        Identifier ownController = PlayerNpcForceTickManager.PLAYER_NPC_TICKET.id();
        for (Object source : sources) {
            if (!((TicketOwnerAccessor) source).smartNpc$getControllerId().equals(ownController)) return false;
        }
        return true;
    }

    private record Anchor(ChunkPos center, int radius) {}
    private record Snapshot(long tick, List<Anchor> anchors) {
        boolean covers(ChunkPos chunk) {
            for (Anchor anchor : anchors) {
                if (Math.max(Math.abs((long) chunk.x - anchor.center.x),
                        Math.abs((long) chunk.z - anchor.center.z)) <= anchor.radius) return true;
            }
            return false;
        }
    }
}
