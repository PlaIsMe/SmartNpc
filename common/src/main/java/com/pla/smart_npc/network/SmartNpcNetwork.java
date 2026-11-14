package com.pla.smart_npc.network;
import com.pla.smart_npc.SmartNpc;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SmartNpcNetwork {
    public static final int PROTOCOL_VERSION = 11;
    public static final Channel CHANNEL = new Channel();
    public static void register() {
        CHANNEL.register(NetworkManager.Side.C2S, AiSetupRequestPacket.class, AiSetupRequestPacket::encode, AiSetupRequestPacket::decode, AiSetupRequestPacket::handle);
        CHANNEL.register(NetworkManager.Side.S2C, AiSetupStatePacket.class, AiSetupStatePacket::encode, AiSetupStatePacket::decode, AiSetupStatePacket::handle);
        CHANNEL.register(NetworkManager.Side.S2C, PlayerNpcInspectorPacket.class, PlayerNpcInspectorPacket::encode, PlayerNpcInspectorPacket::decode, PlayerNpcInspectorPacket::handle);
        CHANNEL.register(NetworkManager.Side.C2S, PlayerNpcInspectorRequestPacket.class, PlayerNpcInspectorRequestPacket::encode, PlayerNpcInspectorRequestPacket::decode, PlayerNpcInspectorRequestPacket::handle);
        CHANNEL.register(NetworkManager.Side.C2S, PlayerNpcInspectatorModePacket.class, PlayerNpcInspectatorModePacket::encode, PlayerNpcInspectatorModePacket::decode, PlayerNpcInspectatorModePacket::handle);
        CHANNEL.register(NetworkManager.Side.C2S, PlayerNpcInspectatorCyclePacket.class, PlayerNpcInspectatorCyclePacket::encode, PlayerNpcInspectatorCyclePacket::decode, PlayerNpcInspectatorCyclePacket::handle);
        CHANNEL.register(NetworkManager.Side.S2C, PlayerNpcInspectatorCycleResultPacket.class, PlayerNpcInspectatorCycleResultPacket::encode, PlayerNpcInspectatorCycleResultPacket::decode, PlayerNpcInspectatorCycleResultPacket::handle);
        CHANNEL.register(NetworkManager.Side.C2S, PlayerNpcGoalTracePacket.class, PlayerNpcGoalTracePacket::encode, PlayerNpcGoalTracePacket::decode, PlayerNpcGoalTracePacket::handle);
    }
    public static final class Channel {
        private final Map<Class<?>, Registration<?>> packets = new HashMap<>();
        private <T> void register(NetworkManager.Side side, Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<NpcPacketContext>> handler) {
            ResourceLocation id = new ResourceLocation(SmartNpc.MODID, "v" + PROTOCOL_VERSION + "/" + type.getSimpleName().toLowerCase(java.util.Locale.ROOT));
            packets.put(type, new Registration<>(id, side, encoder));
            if (side == NetworkManager.Side.C2S || Platform.getEnvironment() == Env.CLIENT) {
                NetworkManager.registerReceiver(side, id, (buffer, context) -> handler.accept(decoder.apply(buffer), () -> new NpcPacketContext(context)));
            }
        }
        @SuppressWarnings("unchecked")
        private <T> net.minecraft.network.protocol.Packet<?> packet(T message, NetworkManager.Side side) {
            Registration<T> registration = (Registration<T>) packets.get(message.getClass());
            if (registration == null || registration.side != side) throw new IllegalArgumentException("Invalid packet direction");
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            registration.encoder.accept(message, buffer);
            return NetworkManager.toPacket(side, registration.id, buffer);
        }
        public <T> void sendToPlayer(ServerPlayer player, T message) { player.connection.send(packet(message, NetworkManager.Side.S2C)); }
        public <T> void sendToServer(T message) { ClientSender.send(packet(message, NetworkManager.Side.C2S)); }
    }
    private static final class ClientSender {
        private static void send(net.minecraft.network.protocol.Packet<?> packet) {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (connection != null) connection.send(packet);
        }
    }
    private record Registration<T>(ResourceLocation id, NetworkManager.Side side, BiConsumer<T, FriendlyByteBuf> encoder) {}
}
