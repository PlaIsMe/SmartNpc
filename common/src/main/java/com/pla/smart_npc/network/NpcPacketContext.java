package com.pla.smart_npc.network;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;
public record NpcPacketContext(NetworkManager.PacketContext context) {
    public ServerPlayer getSender() { return context.getPlayer() instanceof ServerPlayer player ? player : null; }
    public void enqueueWork(Runnable task) { context.queue(task); }
}
