package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

public class PlayerNpcGoalTracePacket implements CustomPacketPayload {
    public static final Type<PlayerNpcGoalTracePacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smart_npc", "goal_trace"));
    public static final StreamCodec<FriendlyByteBuf, PlayerNpcGoalTracePacket> STREAM_CODEC = StreamCodec.ofMember(PlayerNpcGoalTracePacket::encode, PlayerNpcGoalTracePacket::decode);
    private static final double MAX_TRACE_DISTANCE_SQR = 64.0D * 64.0D;

    private final int entityId;
    private final boolean enabled;

    public PlayerNpcGoalTracePacket(int entityId, boolean enabled) {
        this.entityId = entityId;
        this.enabled = enabled;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(this.entityId);
        buffer.writeBoolean(this.enabled);
    }

    public static PlayerNpcGoalTracePacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        boolean enabled = buffer.readBoolean();
        return new PlayerNpcGoalTracePacket(entityId, enabled);
    }

    public static void handle(PlayerNpcGoalTracePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = (ServerPlayer) context.player();

            Entity entity = sender.level().getEntity(packet.entityId);
            if (!(entity instanceof PlayerNpcEntity playerNpc)
                    || !playerNpc.isAlive()
                    || !canTrace(sender, playerNpc)) {
                PlayerNpcGoalTraceLogger.stopTrace(sender, "invalid trace target");
                return;
            }

            if (!packet.enabled && PlayerNpcGoalTraceLogger.isAllTraceEnabled()) {
                PlayerNpcGoalTraceLogger.setAllTraceEnabled(false, sender.getGameProfile().name());
                PlayerNpcGoalTraceLogger.stopTrace(sender, "all trace disabled by viewer");
            } else {
                PlayerNpcGoalTraceLogger.setTraceEnabled(sender, playerNpc, packet.enabled);
            }
            PacketDistributor.sendToPlayer(
                    sender,
                    new PlayerNpcInspectorPacket(
                            playerNpc.getId(),
                            PlayerNpcInspectorData.createSnapshot(playerNpc),
                            PlayerNpcInspectorData.createBuildStatusText(playerNpc),
                            PlayerNpcInspectorData.createPerformanceText(),
                            PlayerNpcInspectorData.createDailyJobText(playerNpc),
                            PlayerNpcInspectorData.createBuildRequirementsText(playerNpc),
                            PlayerNpcInspectorData.createTeamInfo(playerNpc),
                            PlayerNpcGoalTraceLogger.isEffectivelyTracing(sender, playerNpc)
                    )
            );
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static boolean canTrace(ServerPlayer sender, PlayerNpcEntity playerNpc) {
        if (sender.distanceToSqr(playerNpc) <= MAX_TRACE_DISTANCE_SQR) {
            return true;
        }

        return PlayerNpcInspectatorModePacket.isInspectatorActive(sender)
                && sender.getVehicle() == playerNpc;
    }
}
