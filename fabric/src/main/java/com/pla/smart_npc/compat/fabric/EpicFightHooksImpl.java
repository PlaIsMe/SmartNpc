package com.pla.smart_npc.compat.fabric;

import com.pla.smart_npc.entity.PlayerNpcEntity;

public final class EpicFightHooksImpl {
    public static void keepDiggingState(PlayerNpcEntity npc) {  }
    public static void playDiggingAnimation(PlayerNpcEntity npc) {  }
    public static boolean playMainHandUseAnimation(PlayerNpcEntity npc) { return false; }
    public static void playEatingAnimation(PlayerNpcEntity npc) {  }
    public static void keepEatingAnimation(PlayerNpcEntity npc) {  }
    public static void stopEatingAnimation(PlayerNpcEntity npc) {  }
    public static void stopDiggingAnimation(PlayerNpcEntity npc) {  }
    public static void keepSleepingState(PlayerNpcEntity npc) {  }
    public static void playSleepingAnimation(PlayerNpcEntity npc) {  }
    public static void stopSleepingAnimation(PlayerNpcEntity npc) {  }
}
