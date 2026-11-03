package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import com.pla.smart_npc.network.NpcPacketContext;

import java.util.function.Supplier;

public class PlayerNpcInspectorRequestPacket {
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

    public static void encode(PlayerNpcInspectorRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeBoolean(packet.includeRequirements);
    }

    public static PlayerNpcInspectorRequestPacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectorRequestPacket(buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(PlayerNpcInspectorRequestPacket packet, Supplier<NpcPacketContext> contextSupplier) {
        NpcPacketContext context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }

            if (packet.entityId == PlayerNpcInspectorPacket.OVERALL_ENTITY_ID) {
                SmartNpcNetwork.CHANNEL.sendToPlayer(sender,
                        PlayerNpcInspectorPacket.overall(PlayerNpcInspectorData.createAiResourceText(sender.server, null))
                );
                return;
            }

            Entity entity = sender.level().getEntity(packet.entityId);
            if (!(entity instanceof PlayerNpcEntity playerNpc)
                    || !playerNpc.isAlive()
                    || sender.distanceToSqr(playerNpc) > MAX_REFRESH_DISTANCE_SQR) {
                SmartNpcNetwork.CHANNEL.sendToPlayer(sender,
                        PlayerNpcInspectorPacket.clear()
                );
                return;
            }

            SmartNpcNetwork.CHANNEL.sendToPlayer(sender,
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
}
