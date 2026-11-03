package com.pla.smart_npc.network;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
public final class NpcPackets {
    private NpcPackets() {}
    @ExpectPlatform
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) { throw new AssertionError(); }
    @ExpectPlatform
    public static void sendToServer(CustomPacketPayload payload) { throw new AssertionError(); }
    @ExpectPlatform
    public static boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type) { throw new AssertionError(); }
}
