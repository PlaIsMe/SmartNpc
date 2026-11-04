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

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent redirects/tombstones keep unloaded members consistent after merges and deaths. */
public final class PlayerNpcTeamData extends SavedData {
    private static final String DATA_NAME = SmartNpc.MODID + "_player_npc_teams";
    private static final SavedDataType<PlayerNpcTeamData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SmartNpc.MODID, "player_npc_teams"),
            PlayerNpcTeamData::new,
            CompoundTag.CODEC.xmap(tag -> load(tag, null), data -> data.save(new CompoundTag(), null)),
            null
    );
    private static final String REDIRECTS_TAG = "Redirects";
    private static final String DISBANDED_TAG = "Disbanded";
    private static final String PLAYER_TEAMS_TAG = "PlayerTeams";
    private static final String LEADER_REPLACEMENTS_TAG = "LeaderReplacements";

    private final Map<UUID, TeamRedirect> redirects = new HashMap<>();
    private final Set<UUID> disbandedTeamIds = new HashSet<>();
    private final Map<UUID, PlayerTeam> playerTeams = new HashMap<>();
    private final Map<UUID, UUID> leaderReplacements = new HashMap<>();

    public static PlayerNpcTeamData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static PlayerNpcTeamData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlayerNpcTeamData data = new PlayerNpcTeamData();
        ListTag redirects = tag.getListOrEmpty(REDIRECTS_TAG);
        for (int index = 0; index < redirects.size(); index++) {
            CompoundTag entry = redirects.getCompoundOrEmpty(index);
            if (SmartNpcNbt.hasUuid(entry, "From") && SmartNpcNbt.hasUuid(entry, "To") && SmartNpcNbt.hasUuid(entry, "Founder")) {
                data.redirects.put(SmartNpcNbt.getUuid(entry, "From"), new TeamRedirect(
                        SmartNpcNbt.getUuid(entry, "To"), entry.getStringOr("Name", ""), SmartNpcNbt.getUuid(entry, "Founder")
                ));
            }
        }
        ListTag disbanded = tag.getListOrEmpty(DISBANDED_TAG);
        for (int index = 0; index < disbanded.size(); index++) {
            CompoundTag entry = disbanded.getCompoundOrEmpty(index);
            if (SmartNpcNbt.hasUuid(entry, "Id")) {
                data.disbandedTeamIds.add(SmartNpcNbt.getUuid(entry, "Id"));
            }
        }
        ListTag playerTeams = tag.getListOrEmpty(PLAYER_TEAMS_TAG);
        for (int index = 0; index < playerTeams.size(); index++) {
            CompoundTag entry = playerTeams.getCompoundOrEmpty(index);
            if (SmartNpcNbt.hasUuid(entry, "Player") && SmartNpcNbt.hasUuid(entry, "Team")) {
                data.playerTeams.put(SmartNpcNbt.getUuid(entry, "Player"), new PlayerTeam(
                        SmartNpcNbt.getUuid(entry, "Team"), entry.getStringOr("Name", "")
                ));
            }
        }
        ListTag replacements = tag.getListOrEmpty(LEADER_REPLACEMENTS_TAG);
        for (int index = 0; index < replacements.size(); index++) {
            CompoundTag entry = replacements.getCompoundOrEmpty(index);
            if (SmartNpcNbt.hasUuid(entry, "Old") && SmartNpcNbt.hasUuid(entry, "New")) {
                data.leaderReplacements.put(SmartNpcNbt.getUuid(entry, "Old"), SmartNpcNbt.getUuid(entry, "New"));
            }
        }
        return data;
    }

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag redirectTags = new ListTag();
        for (Map.Entry<UUID, TeamRedirect> entry : this.redirects.entrySet()) {
            CompoundTag value = new CompoundTag();
            SmartNpcNbt.putUuid(value, "From", entry.getKey());
            SmartNpcNbt.putUuid(value, "To", entry.getValue().teamId());
            value.putString("Name", entry.getValue().teamName());
            SmartNpcNbt.putUuid(value, "Founder", entry.getValue().founderUuid());
            redirectTags.add(value);
        }
        tag.put(REDIRECTS_TAG, redirectTags);

        ListTag disbandedTags = new ListTag();
        for (UUID teamId : this.disbandedTeamIds) {
            CompoundTag value = new CompoundTag();
            SmartNpcNbt.putUuid(value, "Id", teamId);
            disbandedTags.add(value);
        }
        tag.put(DISBANDED_TAG, disbandedTags);

        ListTag playerTeamTags = new ListTag();
        for (Map.Entry<UUID, PlayerTeam> entry : this.playerTeams.entrySet()) {
            CompoundTag value = new CompoundTag();
            SmartNpcNbt.putUuid(value, "Player", entry.getKey());
            SmartNpcNbt.putUuid(value, "Team", entry.getValue().teamId());
            value.putString("Name", entry.getValue().teamName());
            playerTeamTags.add(value);
        }
        tag.put(PLAYER_TEAMS_TAG, playerTeamTags);

        ListTag replacementTags = new ListTag();
        for (Map.Entry<UUID, UUID> entry : this.leaderReplacements.entrySet()) {
            CompoundTag value = new CompoundTag();
            SmartNpcNbt.putUuid(value, "Old", entry.getKey());
            SmartNpcNbt.putUuid(value, "New", entry.getValue());
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
