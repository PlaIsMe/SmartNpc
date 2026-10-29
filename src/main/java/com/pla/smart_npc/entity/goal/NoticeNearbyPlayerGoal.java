package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/** Attention cooperates with retained job owners instead of preempting their MOVE/LOOK flags. */
public final class NoticeNearbyPlayerGoal extends Goal {
    private final PlayerNpcEntity npc;

    public NoticeNearbyPlayerGoal(PlayerNpcEntity npc) {
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        this.npc.getSocialNoticeAi().beforeAiTick();
        return this.npc.getSocialNoticeAi().isActive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.npc.getSocialNoticeAi().isActive();
    }

    @Override
    public void tick() {
        // Maintain encounter presence during the glance too. Selection/scanning retains its
        // own 20-25 tick cadence and never takes a routine worker or navigation permit.
        this.npc.getSocialNoticeAi().beforeAiTick();
    }

    @Override
    public void stop() {
        this.npc.getSocialNoticeAi().cancel();
    }
}
