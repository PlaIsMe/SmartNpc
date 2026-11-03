package com.pla.smart_npc.compat.neoforge;

import com.pla.smart_npc.entity.PlayerNpcEntity;

public final class EpicFightHooksImpl {
    public static void keepDiggingState(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.keepDiggingState(npc); }
    public static void playDiggingAnimation(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.playDiggingAnimation(npc); }
    public static boolean playMainHandUseAnimation(PlayerNpcEntity npc) { return com.pla.smart_npc.compat.epicfight.EpicFight.playMainHandUseAnimation(npc); }
    public static void playEatingAnimation(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.playEatingAnimation(npc); }
    public static void keepEatingAnimation(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.keepEatingAnimation(npc); }
    public static void stopEatingAnimation(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.stopEatingAnimation(npc); }
    public static void stopDiggingAnimation(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.stopDiggingAnimation(npc); }
    public static void keepSleepingState(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.keepSleepingState(npc); }
    public static void playSleepingAnimation(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.playSleepingAnimation(npc); }
    public static void stopSleepingAnimation(PlayerNpcEntity npc) { com.pla.smart_npc.compat.epicfight.EpicFight.stopSleepingAnimation(npc); }
}
