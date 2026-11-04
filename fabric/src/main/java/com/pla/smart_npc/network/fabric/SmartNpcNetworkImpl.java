package com.pla.smart_npc.network.fabric;
import com.pla.smart_npc.network.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
public final class SmartNpcNetworkImpl {
    public static void register() {
        if (Platform.getEnvironment() == Env.CLIENT) {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlayerNpcInspectorPacket.TYPE, PlayerNpcInspectorPacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectorPacket.handle(packet, new NpcPacketContext(context.getPlayer(), context::queue)));
        } else { NetworkManager.registerS2CPayloadType(PlayerNpcInspectorPacket.TYPE, PlayerNpcInspectorPacket.STREAM_CODEC); }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, PlayerNpcInspectorRequestPacket.TYPE, PlayerNpcInspectorRequestPacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectorRequestPacket.handle(packet, new NpcPacketContext(context.getPlayer(), context::queue)));
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, PlayerNpcInspectatorModePacket.TYPE, PlayerNpcInspectatorModePacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectatorModePacket.handle(packet, new NpcPacketContext(context.getPlayer(), context::queue)));
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, PlayerNpcInspectatorCyclePacket.TYPE, PlayerNpcInspectatorCyclePacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectatorCyclePacket.handle(packet, new NpcPacketContext(context.getPlayer(), context::queue)));
        if (Platform.getEnvironment() == Env.CLIENT) {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlayerNpcInspectatorCycleResultPacket.TYPE, PlayerNpcInspectatorCycleResultPacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectatorCycleResultPacket.handle(packet, new NpcPacketContext(context.getPlayer(), context::queue)));
        } else { NetworkManager.registerS2CPayloadType(PlayerNpcInspectatorCycleResultPacket.TYPE, PlayerNpcInspectatorCycleResultPacket.STREAM_CODEC); }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, PlayerNpcGoalTracePacket.TYPE, PlayerNpcGoalTracePacket.STREAM_CODEC, (packet, context) -> PlayerNpcGoalTracePacket.handle(packet, new NpcPacketContext(context.getPlayer(), context::queue)));
    }
}
