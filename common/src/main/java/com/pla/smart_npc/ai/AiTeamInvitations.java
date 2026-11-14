package com.pla.smart_npc.ai;

import com.google.gson.JsonObject;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import java.util.Set;
import java.util.UUID;

/** Dialogue can resolve a real invitation; it cannot create arbitrary membership. */
final class AiTeamInvitations {
    private AiTeamInvitations() {}
    static PlayerNpcTeamUpManager.PendingInvitation between(PlayerNpcEntity npc, Entity partner) {
        var invitation = PlayerNpcTeamUpManager.pendingInvitation(npc);
        return invitation != null && (invitation.initiator() == npc && invitation.target() == partner
                || invitation.target() == npc && invitation.initiator() == partner)
                && PlayerNpcTeamUpManager.canContinueInvitation(invitation.initiator(), invitation.target()) ? invitation : null;
    }
    static boolean decision(AiAction.Type type) { return type == AiAction.Type.ACCEPT_TEAM_INVITE || type == AiAction.Type.DECLINE_TEAM_INVITE; }
    static Set<AiAction.Type> allowed() { return Set.of(AiAction.Type.NONE, AiAction.Type.ACCEPT_TEAM_INVITE, AiAction.Type.DECLINE_TEAM_INVITE); }
    static void context(PlayerNpcEntity npc, Entity partner, JsonObject context) {
        var invitation = between(npc, partner);
        JsonObject team = new JsonObject();
        team.addProperty("same_team", partner instanceof LivingEntity living && npc.isTeamAlliedWith(living));
        if (invitation != null) {
            team.addProperty("pending", true);
            team.addProperty("npc_role", invitation.initiator() == npc ? "inviter" : "recipient");
            team.addProperty("partner", partner.getName().getString());
        } else team.addProperty("pending", false);
        context.add("team_invitation", team);
    }
    static boolean apply(PlayerNpcEntity npc, Entity partner, UUID token, AiAction action, Set<AiAction.Type> offered) {
        return token != null && decision(action.type()) && partner instanceof LivingEntity living && offered.contains(action.type())
                && action.target() == null && action.item() == null && action.count() == 0
                && PlayerNpcTeamUpManager.respondToInvitation(token, npc, living, action.type() == AiAction.Type.ACCEPT_TEAM_INVITE);
    }
}
