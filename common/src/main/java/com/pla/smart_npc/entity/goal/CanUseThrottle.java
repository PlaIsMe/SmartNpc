package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;

/**
 * Keeps expensive goal activation checks from running every selector pass.
 */
final class CanUseThrottle {
    static final int DEFAULT_INTERVAL_TICKS = 20;

    private final int intervalTicks;
    private int nextCheckTick;
    private boolean initialized;

    CanUseThrottle() {
        this(DEFAULT_INTERVAL_TICKS);
    }

    CanUseThrottle(int intervalTicks) {
        this.intervalTicks = Math.max(1, intervalTicks);
    }

    boolean canCheck(PlayerNpcEntity playerNpc) {
        if (!this.initialized) {
            this.initialized = true;
            this.nextCheckTick = playerNpc.tickCount + playerNpc.getRandom().nextInt(this.intervalTicks);
        }
        if (playerNpc.tickCount < this.nextCheckTick) {
            return false;
        }

        this.nextCheckTick = playerNpc.tickCount
                + this.intervalTicks
                + playerNpc.getRandom().nextInt(Math.max(1, this.intervalTicks / 2 + 1));
        return true;
    }

    /** Overrides the normal one-second cadence after a shared-budget deferral or partial slice. */
    void retryIn(PlayerNpcEntity playerNpc, int ticks) {
        this.initialized = true;
        this.nextCheckTick = playerNpc.tickCount + Math.max(1, ticks);
    }

    void reset() {
        this.nextCheckTick = 0;
        this.initialized = false;
    }
}
