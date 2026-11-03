package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent redirects/tombstones keep unloaded members consistent after merges and deaths. */
public final class PlayerNpcTeamData extends SavedData {
    private static final String DATA_NAME = SmartNpc.MODID + "_player_npc_teams";
    private static final String REDIRECTS_TAG = "Redirects";
    private static final String DISBANDED_TAG = "Disbanded";
    private static final String PLAYER_TEAMS_TAG = "PlayerTeams";
    private static final String LEADER_REPLACEMENTS_TAG = "LeaderReplacements";

    private final Map<UUID, TeamRedirect> redirects = new HashMap<>();
    private final Set<UUID> disbandedTeamIds = new HashSet<>();
    private final Map<UUID, PlayerTeam> playerTeams = new HashMap<>();
    private final Map<UUID, UUID> leaderReplacements = new HashMap<>();

    public static PlayerNpcTeamData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayerNpcTeamData::load,
                PlayerNpcTeamData::new,
                DATA_NAME
        );
    }

    public static PlayerNpcTeamData load(CompoundTag tag) {
        PlayerNpcTeamData data = new PlayerNpcTeamData();
        ListTag redirects = tag.getList(REDIRECTS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < redirects.size(); index++) {
            CompoundTag entry = redirects.getCompound(index);
            if (entry.hasUUID("From") && entry.hasUUID("To") && entry.hasUUID("Founder")) {
                data.redirects.put(entry.getUUID("From"), new TeamRedirect(
                        entry.getUUID("To"), entry.getString("Name"), entry.getUUID("Founder")
                ));
            }
        }
        ListTag disbanded = tag.getList(DISBANDED_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < disbanded.size(); index++) {
            CompoundTag entry = disbanded.getCompound(index);
            if (entry.hasUUID("Id")) {
                data.disbandedTeamIds.add(entry.getUUID("Id"));
            }
        }
        ListTag playerTeams = tag.getList(PLAYER_TEAMS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < playerTeams.size(); index++) {
            CompoundTag entry = playerTeams.getCompound(index);
            if (entry.hasUUID("Player") && entry.hasUUID("Team")) {
                data.playerTeams.put(entry.getUUID("Player"), new PlayerTeam(
                        entry.getUUID("Team"), entry.getString("Name")
                ));
            }
        }
        ListTag replacements = tag.getList(LEADER_REPLACEMENTS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < replacements.size(); index++) {
            CompoundTag entry = replacements.getCompound(index);
            if (entry.hasUUID("Old") && entry.hasUUID("New")) {
                data.leaderReplacements.put(entry.getUUID("Old"), entry.getUUID("New"));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag redirectTags = new ListTag();
        for (Map.Entry<UUID, TeamRedirect> entry : this.redirects.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("From", entry.getKey());
            value.putUUID("To", entry.getValue().teamId());
            value.putString("Name", entry.getValue().teamName());
            value.putUUID("Founder", entry.getValue().founderUuid());
            redirectTags.add(value);
        }
        tag.put(REDIRECTS_TAG, redirectTags);

        ListTag disbandedTags = new ListTag();
        for (UUID teamId : this.disbandedTeamIds) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Id", teamId);
            disbandedTags.add(value);
        }
        tag.put(DISBANDED_TAG, disbandedTags);

        ListTag playerTeamTags = new ListTag();
        for (Map.Entry<UUID, PlayerTeam> entry : this.playerTeams.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Player", entry.getKey());
            value.putUUID("Team", entry.getValue().teamId());
            value.putString("Name", entry.getValue().teamName());
            playerTeamTags.add(value);
        }
        tag.put(PLAYER_TEAMS_TAG, playerTeamTags);

        ListTag replacementTags = new ListTag();
        for (Map.Entry<UUID, UUID> entry : this.leaderReplacements.entrySet()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Old", entry.getKey());
            value.putUUID("New", entry.getValue());
            replacementTags.add(value);
        }
        tag.put(LEADER_REPLACEMENTS_TAG, replacementTags);
        return tag;
    }

    public TeamResolution resolve(UUID teamId, String fallbackName, @Nullable UUID fallbackFounder) {
        UUID resolvedId = teamId;
        String resolvedName = fallbackName == null ? "" : fallbackName;
        UUID resolvedFounder = fallbackFounder;
        Set<UUID> visited = new HashSet<>();
        while (resolvedId != null && visited.add(resolvedId)) {
            if (this.disbandedTeamIds.contains(resolvedId)) {
                return new TeamResolution(resolvedId, resolvedName, resolvedFounder, true);
            }
            TeamRedirect redirect = this.redirects.get(resolvedId);
            if (redirect == null) {
                break;
            }
            resolvedId = redirect.teamId();
            resolvedName = redirect.teamName();
            resolvedFounder = redirect.founderUuid();
        }
        return new TeamResolution(resolvedId, resolvedName, resolvedFounder, false);
    }

    public void redirectTeam(UUID oldTeamId, UUID newTeamId, String newTeamName, UUID founderUuid) {
        if (oldTeamId == null || newTeamId == null || founderUuid == null || oldTeamId.equals(newTeamId)) {
            return;
        }
        this.redirects.put(oldTeamId, new TeamRedirect(newTeamId, newTeamName, founderUuid));
        this.setDirty();
    }

    public void replaceLeader(UUID oldLeader, UUID newLeader) {
        if (oldLeader != null && newLeader != null && !oldLeader.equals(newLeader)) {
            this.leaderReplacements.put(oldLeader, newLeader);
            this.setDirty();
        }
    }

    public UUID resolveLeader(UUID leaderUuid) {
        UUID resolved = leaderUuid;
        Set<UUID> visited = new HashSet<>();
        while (resolved != null && visited.add(resolved) && this.leaderReplacements.containsKey(resolved)) {
            resolved = this.leaderReplacements.get(resolved);
        }
        return resolved;
    }

    public void disband(UUID teamId) {
        TeamResolution resolution = this.resolve(teamId, "", null);
        UUID resolvedId = resolution.teamId();
        if (resolvedId == null || !this.disbandedTeamIds.add(resolvedId)) {
            return;
        }
        this.playerTeams.entrySet().removeIf(entry -> entry.getValue().teamId().equals(resolvedId));
        this.setDirty();
    }

    public PlayerTeam getOrCreatePlayerTeam(UUID playerId, String playerName) {
        PlayerTeam existing = this.playerTeams.get(playerId);
        if (existing != null && !this.resolve(existing.teamId(), existing.teamName(), playerId).disbanded()) {
            return existing;
        }
        PlayerTeam created = new PlayerTeam(UUID.randomUUID(), namedTeam(playerName));
        this.playerTeams.put(playerId, created);
        this.setDirty();
        return created;
    }

    @Nullable
    public PlayerTeam getPlayerTeam(UUID playerId) {
        return playerId == null ? null : this.playerTeams.get(playerId);
    }

    @Nullable
    public UUID removePlayerTeam(UUID playerId) {
        PlayerTeam removed = this.playerTeams.remove(playerId);
        if (removed != null) {
            this.setDirty();
            return removed.teamId();
        }
        return null;
    }

    public static String namedTeam(String leaderName) {
        String safeName = leaderName == null || leaderName.isBlank() ? "Unnamed" : leaderName;
        return safeName + "'s team";
    }

    private record TeamRedirect(UUID teamId, String teamName, UUID founderUuid) {
    }

    public record TeamResolution(UUID teamId, String teamName, @Nullable UUID founderUuid, boolean disbanded) {
    }

    public record PlayerTeam(UUID teamId, String teamName) {
    }
}
