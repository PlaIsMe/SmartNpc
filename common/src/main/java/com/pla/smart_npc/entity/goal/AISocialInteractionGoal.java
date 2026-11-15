package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.ai.AiPlayerPreferences;
import com.pla.smart_npc.ai.AiAction;
import com.pla.smart_npc.ai.AiActionGuard;
import com.pla.smart_npc.ai.AiChatService;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;

/** One bounded social lifecycle shared by look, approach, item request and harmless gesture actions. */
public final class AISocialInteractionGoal extends Goal {
    private final PlayerNpcEntity npc;
    private AiChatService.Gesture gesture;
    private ServerPlayer partner;
    private int nextPathTick;
    private boolean moved;
    private boolean performed;

    public AISocialInteractionGoal(PlayerNpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    @Override public boolean canUse() {
        if (npc.getServer() == null) return false;
        AiChatService service = AiChatService.get(npc.getServer());
        gesture = service == null ? null : service.gesture(npc.getUUID());
        // With AI disabled or no queued gesture, do not scan the NPC's running goal owners.
        if (gesture == null || !AiActionGuard.safe(npc) || !npc.canSociallyNotice()) return false;
        partner = gesture == null ? null : npc.getServer().getPlayerList().getPlayer(gesture.player());
        return valid();
    }
    @Override public boolean canContinueToUse() { return valid(); }
    private boolean valid() {
        AiChatService service = AiChatService.get(npc.getServer());
        return service != null && service.ready() && gesture != null && service.gesture(npc.getUUID()) == gesture
                && npc.getServer().getTickCount() < gesture.expiresAt() && AiActionGuard.safe(npc)
                && partner != null && partner.isAlive() && partner.level() == npc.level() && npc.distanceToSqr(partner) <= 256
                && AiPlayerPreferences.get(npc.getServer()).allows(partner.getUUID());
    }
    @Override public void start() { nextPathTick = 0; moved = false; performed = false; }
    @Override public void tick() {
        if (!valid()) return;
        npc.getLookControl().setLookAt(partner, 30, 30);
        if (!performed && (gesture.type() == AiAction.Type.TROLL_SWING || gesture.type() == AiAction.Type.SOCIAL_EMOTE)) {
            npc.swing(InteractionHand.MAIN_HAND); // Animation only: no attack call or target assignment.
            performed = true;
        }
        if (gesture.type() == AiAction.Type.APPROACH_ENTITY && npc.distanceToSqr(partner) > 9
                && npc.tickCount >= nextPathTick && PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(npc)) {
            nextPathTick = npc.tickCount + 20;
            var path = PathNavigationAi.createBoundedPath(npc, partner.blockPosition(), 0.05F);
            if (path != null) { npc.getNavigation().moveTo(path, 1.0D); moved = true; }
        }
    }
    @Override public void stop() {
        AiChatService service = AiChatService.get(npc.getServer());
        if (service != null && service.gesture(npc.getUUID()) == gesture) service.clearGesture(npc.getUUID());
        if (moved) npc.getNavigation().stop();
        gesture = null; partner = null;
    }
}
