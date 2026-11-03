package com.pla.smart_npc.compat;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import dev.architectury.injectables.annotations.ExpectPlatform;

public final class EpicFightHooks {
    @ExpectPlatform
    public static void keepDiggingState(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void playDiggingAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static boolean playMainHandUseAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void playEatingAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void keepEatingAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void stopEatingAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void stopDiggingAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void keepSleepingState(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void playSleepingAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static void stopSleepingAnimation(PlayerNpcEntity npc) { throw new AssertionError(); }
}
