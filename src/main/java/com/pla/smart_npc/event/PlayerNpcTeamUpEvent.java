package com.pla.smart_npc.event;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Invitation lifecycle and friendly-fire cancellation for established TEAMUP relationships. */
@EventBusSubscriber(modid = SmartNpc.MODID)
public final class PlayerNpcTeamUpEvent {
    private PlayerNpcTeamUpEvent() {
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            PlayerNpcTeamUpManager.cancelInvitationFor(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            PlayerNpcTeamUpManager.cancelInvitationFor(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PlayerNpcTeamUpManager.clearSession();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingAttack(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || event.getEntity().level().isClientSide()) {
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

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerNpcTeamUpManager.onPlayerLeaderDeath(player);
        }
    }
}
