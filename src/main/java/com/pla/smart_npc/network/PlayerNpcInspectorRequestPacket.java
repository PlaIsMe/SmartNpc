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

public class PlayerNpcInspectorRequestPacket implements CustomPacketPayload {
    public static final Type<PlayerNpcInspectorRequestPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smart_npc", "inspector_request"));
    public static final StreamCodec<FriendlyByteBuf, PlayerNpcInspectorRequestPacket> STREAM_CODEC = StreamCodec.ofMember(PlayerNpcInspectorRequestPacket::encode, PlayerNpcInspectorRequestPacket::decode);
    private static final double MAX_REFRESH_DISTANCE_SQR = 64.0D * 64.0D;

    private final int entityId;
    private final boolean includeRequirements;

    public PlayerNpcInspectorRequestPacket(int entityId) {
        this(entityId, false);
    }

    public PlayerNpcInspectorRequestPacket(int entityId, boolean includeRequirements) {
        this.entityId = entityId;
        this.includeRequirements = includeRequirements;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(this.entityId);
        buffer.writeBoolean(this.includeRequirements);
    }

    public static PlayerNpcInspectorRequestPacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectorRequestPacket(buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(PlayerNpcInspectorRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = (ServerPlayer) context.player();

            if (packet.entityId == PlayerNpcInspectorPacket.OVERALL_ENTITY_ID) {
                PacketDistributor.sendToPlayer(
                        sender,
                        PlayerNpcInspectorPacket.overall(PlayerNpcInspectorData.createAiResourceText(sender.level().getServer(), null))
                );
                return;
            }

            Entity entity = sender.level().getEntity(packet.entityId);
            if (!(entity instanceof PlayerNpcEntity playerNpc)
                    || !playerNpc.isAlive()
                    || sender.distanceToSqr(playerNpc) > MAX_REFRESH_DISTANCE_SQR) {
                PacketDistributor.sendToPlayer(
                        sender,
                        PlayerNpcInspectorPacket.clear()
                );
                return;
            }

            PacketDistributor.sendToPlayer(
                    sender,
                    new PlayerNpcInspectorPacket(
                            playerNpc.getId(),
                            PlayerNpcInspectorData.createSnapshot(playerNpc),
                            PlayerNpcInspectorData.createBuildStatusText(playerNpc),
                            PlayerNpcInspectorData.createPerformanceText(),
                            PlayerNpcInspectorData.createDailyJobText(playerNpc),
                            packet.includeRequirements ? PlayerNpcInspectorData.createBuildRequirementsText(playerNpc) : "",
                            PlayerNpcInspectorData.createTeamInfo(playerNpc),
                            PlayerNpcGoalTraceLogger.isEffectivelyTracing(sender, playerNpc)
                    )
            );
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
