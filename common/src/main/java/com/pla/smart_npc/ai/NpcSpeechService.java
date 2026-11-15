package com.pla.smart_npc.ai;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class NpcSpeechService {
    private NpcSpeechService() {}
    public static boolean say(PlayerNpcEntity npc, String text, double radius) {
        return say(npc, text, radius, false);
    }
    public static boolean sayInDimension(PlayerNpcEntity npc, String text) {
        return say(npc, text, 0, true);
    }
    private static boolean say(PlayerNpcEntity npc, String text, double radius, boolean broadcast) {
        if (!SmartNpcConfig.TURN_ON_NPC_CHAT.get() || text == null || text.isBlank()) return false;
        // Literal components cannot execute model-supplied commands/click events.
        Component message = ChatUtil.formatNpcChat(npc, Component.literal(text));
        boolean delivered = false;
        for (ServerPlayer player : npc.getServer().getPlayerList().getPlayers()) {
            if (player.level() == npc.level() && (broadcast || player.distanceToSqr(npc) <= radius * radius)
                    && AiPlayerPreferences.get(npc.getServer()).allows(player.getUUID())) {
                player.sendSystemMessage(message);
                delivered = true;
            }
        }
        return delivered;
    }
}
