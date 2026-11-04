package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.DangerousReactionAi;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Explosion avoidance; healing keeps its own item lifecycle and uses the same movement helper. */
public final class DangerousReactionGoal extends Goal {
    private final PlayerNpcEntity npc;
    private final DangerousReactionAi reactionAi;
    private Entity threat;

    public DangerousReactionGoal(PlayerNpcEntity npc) {
        this.npc = npc;
        this.reactionAi = new DangerousReactionAi(npc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.canReact()) {
            return false;
        }
        this.threat = this.reactionAi.findThreat(false);
        return this.reactionAi.isWithinDangerRange(this.threat, 0.0D);
    }

    @Override
    public boolean canContinueToUse() {
        // O(1) continuation; at a safe margin release MOVE so healing can begin.
        return this.canReact() && DangerousReactionAi.isExplosiveThreat(this.threat)
                && this.reactionAi.isWithinDangerRange(this.threat, 2.0D);
    }

    @Override
    public void start() {
        this.npc.setCurrentAiState("ai.player_npc.dangerous_reaction");
        this.reactionAi.tickRetreat(this.threat, 0.79D, true);
    }

    @Override
    public void tick() {
        this.threat = this.reactionAi.findThreat(false);
        this.reactionAi.tickRetreat(this.threat, 0.79D, true);
    }

    @Override
    public void stop() {
        this.reactionAi.stop();
        this.threat = null;
        this.npc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean canReact() {
        return !this.npc.level().isClientSide() && this.npc.isAlive()
                && !this.npc.isNoAi() && !this.npc.isPassenger() && !this.npc.isHealing();
    }
}
