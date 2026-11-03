package com.pla.smart_npc.network;

import com.pla.smart_npc.SmartNpc;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class SmartNpcNetwork {
    private static final String PROTOCOL_VERSION = "7";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(SmartNpc.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId;

    public static void register() {
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcInspectorPacket.class,
                PlayerNpcInspectorPacket::encode,
                PlayerNpcInspectorPacket::decode,
                PlayerNpcInspectorPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcInspectorRequestPacket.class,
                PlayerNpcInspectorRequestPacket::encode,
                PlayerNpcInspectorRequestPacket::decode,
                PlayerNpcInspectorRequestPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcInspectatorModePacket.class,
                PlayerNpcInspectatorModePacket::encode,
                PlayerNpcInspectatorModePacket::decode,
                PlayerNpcInspectatorModePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcInspectatorCyclePacket.class,
                PlayerNpcInspectatorCyclePacket::encode,
                PlayerNpcInspectatorCyclePacket::decode,
                PlayerNpcInspectatorCyclePacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcInspectatorCycleResultPacket.class,
                PlayerNpcInspectatorCycleResultPacket::encode,
                PlayerNpcInspectatorCycleResultPacket::decode,
                PlayerNpcInspectatorCycleResultPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcGoalTracePacket.class,
                PlayerNpcGoalTracePacket::encode,
                PlayerNpcGoalTracePacket::decode,
                PlayerNpcGoalTracePacket::handle
        );
    }
}
