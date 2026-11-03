package com.pla.smart_npc.network.fabric;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public final class NpcPacketsImpl {
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) { dev.architectury.networking.NetworkManager.sendToPlayer(player, payload); }
    public static void sendToServer(CustomPacketPayload payload) { dev.architectury.networking.NetworkManager.sendToServer(payload); }
    public static boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type) { return dev.architectury.networking.NetworkManager.canPlayerReceive(player, type); }
}
