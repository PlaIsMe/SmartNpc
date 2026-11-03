package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.compat.YesSteveModelCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/** Cooperative animation only: home/camp/stroll keep their movement and work ownership. */
public final class YsmIdleEmoteGoal extends Goal {
    private final PlayerNpcEntity npc;
    private final CanUseThrottle throttle = new CanUseThrottle(20);
    private int ownerSequence;

    public YsmIdleEmoteGoal(PlayerNpcEntity npc) {
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        return this.npc.isYsmActive() && this.npc.getYsmEmoteCooldown() == 0
                && !this.npc.getSocialNoticeAi().isActive() && this.throttle.canCheck(this.npc)
                && this.npc.canPlayIdleYsmEmote();
    }

    @Override
    public void start() {
        this.npc.setYsmEmoteCooldown(200 + this.npc.getRandom().nextInt(401));
        this.ownerSequence = YesSteveModelCompat.playRandomEmote(this.npc, 60 + this.npc.getRandom().nextInt(101));
    }

    @Override
    public boolean canContinueToUse() {
        return this.ownerSequence != 0 && this.npc.isYsmActive()
                && this.npc.getYsmEmoteSequence() == this.ownerSequence && !this.npc.getYsmEmote().isEmpty()
                && !this.npc.getSocialNoticeAi().isActive() && this.npc.canPlayIdleYsmEmote();
    }

    @Override
    public void stop() {
        this.npc.stopYsmEmote(this.ownerSequence);
        this.ownerSequence = 0;
    }
}
