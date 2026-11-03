package com.pla.smart_npc.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class SmartNpcNetwork {
    private static final String PROTOCOL_VERSION = "8";

    private SmartNpcNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToClient(PlayerNpcInspectorPacket.TYPE, PlayerNpcInspectorPacket.STREAM_CODEC,
                PlayerNpcInspectorPacket::handle);
        registrar.playToServer(PlayerNpcInspectorRequestPacket.TYPE, PlayerNpcInspectorRequestPacket.STREAM_CODEC,
                PlayerNpcInspectorRequestPacket::handle);
        registrar.playToServer(PlayerNpcInspectatorModePacket.TYPE, PlayerNpcInspectatorModePacket.STREAM_CODEC,
                PlayerNpcInspectatorModePacket::handle);
        registrar.playToServer(PlayerNpcInspectatorCyclePacket.TYPE, PlayerNpcInspectatorCyclePacket.STREAM_CODEC,
                PlayerNpcInspectatorCyclePacket::handle);
        registrar.playToClient(PlayerNpcInspectatorCycleResultPacket.TYPE, PlayerNpcInspectatorCycleResultPacket.STREAM_CODEC,
                PlayerNpcInspectatorCycleResultPacket::handle);
        registrar.playToServer(PlayerNpcGoalTracePacket.TYPE, PlayerNpcGoalTracePacket.STREAM_CODEC,
                PlayerNpcGoalTracePacket::handle);
    }
}
