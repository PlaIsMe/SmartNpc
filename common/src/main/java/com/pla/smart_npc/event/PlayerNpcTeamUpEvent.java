package com.pla.smart_npc.event;

import com.pla.smart_npc.platform.event.NpcEvents.*;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Invitation lifecycle and friendly-fire cancellation for established TEAMUP relationships. */
public final class PlayerNpcTeamUpEvent {
    private PlayerNpcTeamUpEvent() {
    }
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.getEntity().level().isClientSide) {
            PlayerNpcTeamUpManager.cancelInvitationFor(event.getEntity());
        }
    }
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            PlayerNpcTeamUpManager.cancelInvitationFor(event.getEntity());
        }
    }
    public static void onServerStopped(ServerStoppedEvent event) {
        PlayerNpcTeamUpManager.clearSession();
    }
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide) {
            return;
        }
        // Incoming attacks cancel social intent even when the shield subsequently absorbs
        // the hit or a cautious NPC chooses not to assign an attack target.
        PlayerNpcTeamUpManager.cancelInvitationFor(event.getEntity());
        Entity attacker = event.getSource().getEntity();
        if (attacker != null) {
            PlayerNpcTeamUpManager.cancelInvitationFor(attacker);
        }
        if (PlayerNpcTeamUpManager.areTeamAllies(event.getEntity(), attacker)) {
            event.setCanceled(true);
            return;
        }
        if (attacker instanceof LivingEntity livingAttacker) {
            PlayerNpcTeamUpManager.alertAlliesOfAttack(event.getEntity(), livingAttacker);
        }
    }
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerNpcTeamUpManager.onPlayerLeaderDeath(player);
        }
    }
}
