package com.pla.smart_npc.network.neoforge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public final class NpcPacketsImpl {
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) { net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload); }
    public static void sendToServer(CustomPacketPayload payload) { net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload); }
    public static boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type) { return player.connection.hasChannel(type); }
}
