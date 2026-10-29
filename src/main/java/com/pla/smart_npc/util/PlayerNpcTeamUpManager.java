package com.pla.smart_npc.util;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/** Server-side invitation, merge, defense, and persisted team relationship support. */
public final class PlayerNpcTeamUpManager {
    public static final double INVITE_RADIUS = 4.0D;
    public static final double FOLLOW_RANGE = 30.0D;
    private static final double ALLY_DEFENSE_RADIUS = 36.0D;
    private static final int MAX_ALLY_DEFENDERS = 12;
    private static final int DEFENSE_ALERT_COOLDOWN_TICKS = 10;
    private static final Map<UUID, PendingInvitation> INVITATIONS = new HashMap<>();
    private static final Map<UUID, Long> NEXT_DEFENSE_ALERT_TICK = new HashMap<>();

    private PlayerNpcTeamUpManager() {
    }

    @Nullable
    public static LivingEntity findInviteCandidate(PlayerNpcEntity initiator) {
        if (!(initiator.level() instanceof ServerLevel serverLevel)
                || !initiator.hasInterest(PlayerNpcInterest.TEAMUP)
                || initiator.isTeamFollower()
                || initiator.isTeamUpRequestPending()) {
            return null;
        }
        AABB area = initiator.getBoundingBox().inflate(INVITE_RADIUS, 4.0D, INVITE_RADIUS);
        return serverLevel.getEntitiesOfClass(
                        LivingEntity.class,
                        area,
                        entity -> isAvailableCandidate(initiator, entity)
                ).stream()
                .min(Comparator.comparingDouble(initiator::distanceToSqr))
                .orElse(null);
    }

    public static boolean beginInvitation(PlayerNpcEntity initiator, LivingEntity target, long expiresAtTick) {
        if (getInvitation(initiator) != null || getInvitation(target) != null
                || !isAvailableCandidate(initiator, target)) {
            return false;
        }
        PendingInvitation invitation = new PendingInvitation(initiator, target, expiresAtTick);
        INVITATIONS.put(initiator.getUUID(), invitation);
        INVITATIONS.put(target.getUUID(), invitation);
        initiator.setTeamUpRequestPending(true);
        if (target instanceof PlayerNpcEntity npcTarget) {
            npcTarget.setTeamUpRequestPending(true);
        }
        return true;
    }

    public static void cancelInvitation(PlayerNpcEntity initiator, @Nullable LivingEntity target) {
        PendingInvitation current = INVITATIONS.get(initiator.getUUID());
        if (current == null || current.initiator() != initiator || current.target() != target) {
            return;
        }
        INVITATIONS.remove(initiator.getUUID(), current);
        INVITATIONS.remove(target.getUUID(), current);
        initiator.setTeamUpRequestPending(false);
        initiator.setShiftKeyDown(false);
        if (target instanceof PlayerNpcEntity npcTarget) {
            npcTarget.setTeamUpRequestPending(false);
            npcTarget.setShiftKeyDown(false);
        }
    }

    public static void cancelInvitationFor(Entity participant) {
        PendingInvitation current = INVITATIONS.get(participant.getUUID());
        if (current != null) {
            cancelInvitation(current.initiator(), current.target());
        }
    }

    public static void clearSession() {
        for (PendingInvitation invitation : new HashSet<>(INVITATIONS.values())) {
            cancelInvitation(invitation.initiator(), invitation.target());
        }
        INVITATIONS.clear();
        NEXT_DEFENSE_ALERT_TICK.clear();
    }

    @Nullable
    private static PendingInvitation getInvitation(LivingEntity participant) {
        PendingInvitation invitation = INVITATIONS.get(participant.getUUID());
        if (invitation != null && (participant.getServer() == null
                || participant.getServer().getTickCount() >= invitation.expiresAtTick()
                || invitation.initiator().isRemoved() || invitation.target().isRemoved())) {
            cancelInvitation(invitation.initiator(), invitation.target());
            return null;
        }
        return invitation;
    }

    public static boolean canContinueInvitation(PlayerNpcEntity initiator, LivingEntity target) {
        PendingInvitation invitation = getInvitation(initiator);
        if (invitation == null || invitation.initiator() != initiator || invitation.target() != target
                || INVITATIONS.get(target.getUUID()) != invitation || !initiator.isTeamUpRequestPending()
                || !initiator.isAlive()
                || initiator.isRemoved()
                || initiator.isNoAi()
                || initiator.isHealing() || initiator.isSleeping() || initiator.isPassenger()
                || initiator.isTeamFollower()
                || initiator.getTarget() != null
                || !target.isAlive()
                || target.isRemoved()
                || target.level() != initiator.level()
                || initiator.distanceToSqr(target) > INVITE_RADIUS * INVITE_RADIUS
                || !initiator.hasLineOfSight(target)
                || target instanceof ServerPlayer player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        return !(target instanceof PlayerNpcEntity npcTarget)
                || npcTarget.hasInterest(PlayerNpcInterest.TEAMUP)
                && !npcTarget.isTeamFollower()
                && (!npcTarget.isTeamMember() || npcTarget.isTeamLeader())
                && !initiator.isTeamAlliedWith(npcTarget)
                && npcTarget.isTeamUpRequestPending()
                && !npcTarget.isNoAi() && !npcTarget.isHealing() && !npcTarget.isSleeping() && !npcTarget.isPassenger()
                && npcTarget.getTarget() == null;
    }

    public static boolean acceptNpcResponse(PlayerNpcEntity initiator, PlayerNpcEntity target) {
        if (!canContinueInvitation(initiator, target) || !target.isTeamUpRequestPending()) {
            return false;
        }
        MinecraftServer server = initiator.getServer();
        if (server == null) {
            return false;
        }

        ensureNpcLeaderTeam(initiator);
        UUID mergedTeamId = initiator.getTeamId();
        UUID founderUuid = initiator.getTeamFounderUuid();
        if (mergedTeamId == null || founderUuid == null) {
            return false;
        }
        String mergedTeamName = initiator.getTeamName();

        if (target.isTeamLeader() && target.getTeamId() != null) {
            UUID absorbedTeamId = target.getTeamId();
            if (!absorbedTeamId.equals(mergedTeamId)) {
                PlayerNpcTeamData.get(server).redirectTeam(
                        absorbedTeamId, mergedTeamId, mergedTeamName, founderUuid
                );
                migrateLoadedTeam(
                        server, absorbedTeamId, mergedTeamId, mergedTeamName,
                        founderUuid, initiator.getUUID()
                );
            }
        }
        // Recruitment is intentionally hierarchical: even a former leader and every member of
        // its absorbed team now depend on and physically follow the initiating leader.
        target.setTeamMembership(
                mergedTeamId, mergedTeamName, founderUuid, false, initiator.getUUID(), false
        );
        initiator.setTarget(null);
        target.setTarget(null);
        cancelInvitation(initiator, target);
        return true;
    }

    public static boolean acceptPlayerGesture(PlayerNpcEntity npc, ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || npc.isTeamMember() || !npc.isTeamUpRequestPending()
                || !canContinueInvitation(npc, player)) {
            return false;
        }
        PlayerNpcTeamData.PlayerTeam team = PlayerNpcTeamData.get(server).getOrCreatePlayerTeam(
                player.getUUID(), player.getDisplayName().getString()
        );
        npc.setTeamMembership(
                team.teamId(), team.teamName(), player.getUUID(), false, player.getUUID(), true
        );
        npc.setTarget(null);
        cancelInvitation(npc, player);
        return true;
    }

    public static void validateLoadedMembership(PlayerNpcEntity npc) {
        MinecraftServer server = npc.getServer();
        UUID teamId = npc.getTeamId();
        if (server == null || teamId == null) {
            return;
        }
        PlayerNpcTeamData data = PlayerNpcTeamData.get(server);
        PlayerNpcTeamData.TeamResolution resolution = data.resolve(
                teamId, npc.getTeamName(), npc.getTeamFounderUuid()
        );
        if (resolution.disbanded() || resolution.teamId() == null || resolution.founderUuid() == null) {
            npc.clearTeamMembership();
            return;
        }
        boolean shouldLead = npc.getUUID().equals(resolution.founderUuid());
        boolean followsPlayer = npc.isTeamFollower() && npc.isTeamLeaderPlayer();
        UUID followLeader = shouldLead
                ? null
                : followsPlayer
                ? npc.getTeamLeaderUuid()
                : resolution.founderUuid();
        boolean changed = !resolution.teamId().equals(teamId)
                || !resolution.teamName().equals(npc.getTeamName())
                || !resolution.founderUuid().equals(npc.getTeamFounderUuid())
                || npc.isTeamLeader() != shouldLead
                || !java.util.Objects.equals(followLeader, npc.getTeamLeaderUuid());
        if (changed) {
            npc.setTeamMembership(
                    resolution.teamId(), resolution.teamName(), resolution.founderUuid(),
                    shouldLead, followLeader, !shouldLead && followsPlayer
            );
        }
    }

    @Nullable
    public static LivingEntity resolveLeader(PlayerNpcEntity follower) {
        UUID leaderUuid = follower.getTeamLeaderUuid();
        MinecraftServer server = follower.getServer();
        if (leaderUuid == null || server == null) {
            return null;
        }
        if (follower.isTeamLeaderPlayer()) {
            return server.getPlayerList().getPlayer(leaderUuid);
        }
        return findNpc(server, leaderUuid);
    }

    /** Returns the live leader only while TEAMUP should own the follower's activity. */
    @Nullable
    public static LivingEntity resolveNearbyLeader(PlayerNpcEntity follower) {
        LivingEntity leader = resolveLeader(follower);
        return isLeaderWithinFollowRange(follower, leader) ? leader : null;
    }

    public static boolean isLeaderWithinFollowRange(PlayerNpcEntity follower, @Nullable LivingEntity leader) {
        return follower != null
                && follower.isTeamFollower()
                && leader != null
                && leader.isAlive()
                && !leader.isRemoved()
                && leader.level() == follower.level()
                && follower.distanceToSqr(leader) <= FOLLOW_RANGE * FOLLOW_RANGE;
    }

    /**
     * Membership persists when the leader is away, but work is suspended only while that leader
     * is live, in the same dimension, and within the explicit follow range.
     */
    public static boolean shouldSuspendRoutineWork(PlayerNpcEntity follower) {
        return follower != null && resolveNearbyLeader(follower) != null;
    }

    public static void alertAlliesOfAttack(LivingEntity victim, LivingEntity attacker) {
        if (!(victim.level() instanceof ServerLevel serverLevel)
                || attacker == victim
                || attacker.level() != victim.level()
                || areTeamAllies(victim, attacker)
                || !attacker.isAlive()
                || attacker instanceof ServerPlayer player && (player.isCreative() || player.isSpectator())) {
            return;
        }

        UUID teamId = victim instanceof PlayerNpcEntity victimNpc && victimNpc.isTeamMember()
                ? victimNpc.getTeamId()
                : victim instanceof ServerPlayer playerVictim
                ? getPlayerLeaderTeamId(serverLevel, playerVictim)
                : null;
        long now = serverLevel.getGameTime();
        if (teamId == null || NEXT_DEFENSE_ALERT_TICK.getOrDefault(teamId, 0L) > now) {
            return;
        }
        NEXT_DEFENSE_ALERT_TICK.put(teamId, now + DEFENSE_ALERT_COOLDOWN_TICKS);

        AABB area = victim.getBoundingBox().inflate(ALLY_DEFENSE_RADIUS, 12.0D, ALLY_DEFENSE_RADIUS);
        var nearbyNpcs = serverLevel.getEntitiesOfClass(PlayerNpcEntity.class, area, PlayerNpcEntity::isTeamMember);
        int defenders = 0;
        for (PlayerNpcEntity ally : nearbyNpcs) {
            if (defenders >= MAX_ALLY_DEFENDERS) {
                break;
            }
            if (ally == victim
                    || !teamId.equals(ally.getTeamId())
                    || !ally.isAlive()
                    || ally.isNoAi()
                    || ally.isHealing()
                    || ally.isTeamAlliedWith(attacker)
                    || !ally.canAttack(attacker)
                    || attacker.isAlliedTo(ally)) {
                continue;
            }
            LivingEntity currentTarget = ally.getTarget();
            if (currentTarget != null && currentTarget.isAlive()
                    && !ally.isTeamAlliedWith(currentTarget)) {
                continue;
            }
            ally.interruptRoutineWork();
            ally.setTarget(attacker);
            ally.markCombatProgress();
            ally.setCurrentAiState("ai.player_npc.assisting_teammate");
            ally.setCurrentAiDetail("defending " + victim.getDisplayName().getString());
            defenders++;
        }
    }

    @Nullable
    private static UUID getPlayerLeaderTeamId(ServerLevel serverLevel, ServerPlayer player) {
        PlayerNpcTeamData.PlayerTeam team = PlayerNpcTeamData.get(serverLevel.getServer())
                .getPlayerTeam(player.getUUID());
        return team == null ? null : team.teamId();
    }

    public static void onNpcDeath(PlayerNpcEntity npc) {
        MinecraftServer server = npc.getServer();
        UUID teamId = npc.getTeamId();
        if (server == null || teamId == null) {
            return;
        }
        if (npc.isTeamLeader() && npc.getUUID().equals(npc.getTeamFounderUuid())) {
            disbandTeam(server, teamId);
            return;
        }
        npc.clearTeamMembership();
    }

    public static void onPlayerLeaderDeath(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        UUID teamId = PlayerNpcTeamData.get(server).removePlayerTeam(player.getUUID());
        if (teamId != null) {
            disbandTeam(server, teamId);
        }
    }

    public static boolean areTeamAllies(Entity first, Entity second) {
        return first instanceof PlayerNpcEntity firstNpc && firstNpc.isTeamAlliedWith(second)
                || second instanceof PlayerNpcEntity secondNpc && secondNpc.isTeamAlliedWith(first);
    }

    private static void ensureNpcLeaderTeam(PlayerNpcEntity leader) {
        if (leader.isTeamLeader()) {
            return;
        }
        UUID teamId = UUID.randomUUID();
        String teamName = PlayerNpcTeamData.namedTeam(leader.getDisplayName().getString());
        leader.setTeamMembership(teamId, teamName, leader.getUUID(), true, null, false);
    }

    private static void migrateLoadedTeam(MinecraftServer server, UUID oldTeamId, UUID newTeamId,
                                          String newTeamName, UUID founderUuid, UUID initiatingLeader) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof PlayerNpcEntity member) || !oldTeamId.equals(member.getTeamId())) {
                    continue;
                }
                member.setTeamMembership(
                        newTeamId, newTeamName, founderUuid, false, initiatingLeader, false
                );
            }
        }
    }

    private static void disbandTeam(MinecraftServer server, UUID teamId) {
        PlayerNpcTeamData data = PlayerNpcTeamData.get(server);
        PlayerNpcTeamData.TeamResolution deadTeam = data.resolve(teamId, "", null);
        data.disband(teamId);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof PlayerNpcEntity member) || member.getTeamId() == null) {
                    continue;
                }
                PlayerNpcTeamData.TeamResolution memberTeam = data.resolve(
                        member.getTeamId(), member.getTeamName(), member.getTeamFounderUuid()
                );
                if (memberTeam.disbanded()
                        && java.util.Objects.equals(memberTeam.teamId(), deadTeam.teamId())) {
                    member.clearTeamMembership();
                }
            }
        }
        NEXT_DEFENSE_ALERT_TICK.remove(deadTeam.teamId());
    }

    private static boolean isAvailableCandidate(PlayerNpcEntity initiator, LivingEntity entity) {
        if (entity == initiator || !entity.isAlive() || entity.isRemoved() || initiator.isTeamAlliedWith(entity)
                || initiator.distanceToSqr(entity) > INVITE_RADIUS * INVITE_RADIUS
                || getInvitation(entity) != null || !initiator.hasLineOfSight(entity)) {
            return false;
        }
        if (entity instanceof ServerPlayer player) {
            return !initiator.isTeamMember() && !player.isCreative() && !player.isSpectator();
        }
        return entity instanceof PlayerNpcEntity npc
                && npc.hasInterest(PlayerNpcInterest.TEAMUP)
                // Ordinary members/followers are never invited away from their existing team.
                && !npc.isTeamFollower()
                // A team leader is explicitly eligible so the two leader-led teams can merge.
                && (!npc.isTeamMember() || npc.isTeamLeader())
                && !npc.isTeamUpRequestPending()
                && !npc.isNoAi() && !npc.isHealing() && !npc.isSleeping() && !npc.isPassenger()
                && npc.getTarget() == null
                && (PlayerNpcEntity.AI_IDLE.equals(npc.getCurrentAiState())
                || "ai.player_npc.looking_for_work".equals(npc.getCurrentAiState()));
    }

    @Nullable
    private static PlayerNpcEntity findNpc(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof PlayerNpcEntity npc) {
                return npc;
            }
        }
        return null;
    }

    private record PendingInvitation(PlayerNpcEntity initiator, LivingEntity target, long expiresAtTick) {
    }
}
