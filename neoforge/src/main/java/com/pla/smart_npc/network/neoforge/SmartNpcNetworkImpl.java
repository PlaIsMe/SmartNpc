package com.pla.smart_npc.network.neoforge;
import com.pla.smart_npc.network.*;
import com.pla.smart_npc.neoforge.SmartNpcNeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
public final class SmartNpcNetworkImpl {
    public static void register() { SmartNpcNeoForge.MOD_BUS.addListener(SmartNpcNetworkImpl::registerPayloads); }
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(SmartNpcNetwork.PROTOCOL_VERSION);
        registrar.playToClient(PlayerNpcInspectorPacket.TYPE, PlayerNpcInspectorPacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectorPacket.handle(packet, new NpcPacketContext(context.player(), context::enqueueWork)));
        registrar.playToServer(PlayerNpcInspectorRequestPacket.TYPE, PlayerNpcInspectorRequestPacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectorRequestPacket.handle(packet, new NpcPacketContext(context.player(), context::enqueueWork)));
        registrar.playToServer(PlayerNpcInspectatorModePacket.TYPE, PlayerNpcInspectatorModePacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectatorModePacket.handle(packet, new NpcPacketContext(context.player(), context::enqueueWork)));
        registrar.playToServer(PlayerNpcInspectatorCyclePacket.TYPE, PlayerNpcInspectatorCyclePacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectatorCyclePacket.handle(packet, new NpcPacketContext(context.player(), context::enqueueWork)));
        registrar.playToClient(PlayerNpcInspectatorCycleResultPacket.TYPE, PlayerNpcInspectatorCycleResultPacket.STREAM_CODEC, (packet, context) -> PlayerNpcInspectatorCycleResultPacket.handle(packet, new NpcPacketContext(context.player(), context::enqueueWork)));
        registrar.playToServer(PlayerNpcGoalTracePacket.TYPE, PlayerNpcGoalTracePacket.STREAM_CODEC, (packet, context) -> PlayerNpcGoalTracePacket.handle(packet, new NpcPacketContext(context.player(), context::enqueueWork)));
    }
}
