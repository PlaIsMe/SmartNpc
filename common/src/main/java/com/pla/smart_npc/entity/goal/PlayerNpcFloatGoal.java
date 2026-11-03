package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;

import java.util.EnumSet;

/** Cooperative buoyancy: a swimming combat/work owner keeps its MOVE and JUMP flags. */
public final class PlayerNpcFloatGoal extends FloatGoal {
    private final PlayerNpcEntity npc;

    public PlayerNpcFloatGoal(PlayerNpcEntity npc) {
        super(npc);
        this.npc = npc;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public void tick() {
        if (!this.npc.getWaterTravelAi().suppressFloating()) {
            super.tick();
        }
    }
}
