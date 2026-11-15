package com.pla.smart_npc.network;

import com.pla.smart_npc.client.gui.AiSetupClient;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.Supplier;

public record AiSetupStatePacket(String json, boolean open) {
    public static void encode(AiSetupStatePacket packet, FriendlyByteBuf buffer) { buffer.writeUtf(packet.json(), 65536); buffer.writeBoolean(packet.open()); }
    public static AiSetupStatePacket decode(FriendlyByteBuf buffer) { return new AiSetupStatePacket(buffer.readUtf(65536), buffer.readBoolean()); }
    public static void handle(AiSetupStatePacket packet, Supplier<NpcPacketContext> supplier) {
        var context = supplier.get();
        var expectedPlayer = context.context().getPlayer();
        context.enqueueWork(() -> EnvExecutor.runInEnv(Env.CLIENT, () -> () -> AiSetupClient.receive(packet, expectedPlayer)));
    }
}
