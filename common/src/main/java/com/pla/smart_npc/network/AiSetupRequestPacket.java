package com.pla.smart_npc.network;

import com.pla.smart_npc.ai.AiSetupManager;
import com.pla.smart_npc.ai.AiChatService;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.Supplier;

/** Only catalog IDs/actions cross the wire. URLs and filesystem destinations are server-owned. */
public record AiSetupRequestPacket(AiSetupManager.Action action, String modelId) {
    public static void encode(AiSetupRequestPacket packet, FriendlyByteBuf buffer) { buffer.writeEnum(packet.action()); buffer.writeUtf(packet.modelId(), 80); }
    public static AiSetupRequestPacket decode(FriendlyByteBuf buffer) { return new AiSetupRequestPacket(buffer.readEnum(AiSetupManager.Action.class), buffer.readUtf(80)); }
    public static void handle(AiSetupRequestPacket packet, Supplier<NpcPacketContext> supplier) {
        NpcPacketContext context = supplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            var service = AiChatService.get(player.getServer());
            if (service != null) service.settings(player, packet.action(), packet.modelId(), false);
        });
    }
}
