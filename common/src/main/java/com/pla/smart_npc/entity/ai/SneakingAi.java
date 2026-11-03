package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;

public final class SneakingAi {
    private final PlayerNpcEntity playerNpc;
    private int heldSneakTicks;

    public SneakingAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public void setSneaking(boolean sneaking) {
        this.playerNpc.setShiftKeyDown(sneaking);
        this.playerNpc.setPose(sneaking ? Pose.CROUCHING : Pose.STANDING);
        this.playerNpc.setDisplayNameHiddenBySneakingAi(sneaking);
    }

    public void stopSneaking() {
        this.heldSneakTicks = 0;
        this.setSneaking(false);
    }

    public boolean rollHeldSneak(RandomSource random, float chance, int minTicks, int randomTicks) {
        if (random.nextFloat() >= chance) {
            this.heldSneakTicks = 0;
            this.setSneaking(false);
            return false;
        }

        this.heldSneakTicks = Math.max(1, minTicks) + random.nextInt(Math.max(1, randomTicks + 1));
        this.setSneaking(true);
        return true;
    }

    public boolean tickHeldSneak() {
        if (this.heldSneakTicks <= 0) {
            this.setSneaking(false);
            return false;
        }

        this.heldSneakTicks--;
        this.setSneaking(true);
        return true;
    }
}
