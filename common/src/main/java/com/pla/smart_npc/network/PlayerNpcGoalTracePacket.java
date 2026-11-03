package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import com.pla.smart_npc.network.NpcPacketContext;

import java.util.function.Supplier;

public class PlayerNpcGoalTracePacket {
    private static final double MAX_TRACE_DISTANCE_SQR = 64.0D * 64.0D;

    private final int entityId;
    private final boolean enabled;

    public PlayerNpcGoalTracePacket(int entityId, boolean enabled) {
        this.entityId = entityId;
        this.enabled = enabled;
    }

    public static void encode(PlayerNpcGoalTracePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeBoolean(packet.enabled);
    }

    public static PlayerNpcGoalTracePacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        boolean enabled = buffer.readBoolean();
        return new PlayerNpcGoalTracePacket(entityId, enabled);
    }

    public static void handle(PlayerNpcGoalTracePacket packet, Supplier<NpcPacketContext> contextSupplier) {
        NpcPacketContext context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }

            Entity entity = sender.level().getEntity(packet.entityId);
            if (!(entity instanceof PlayerNpcEntity playerNpc)
                    || !playerNpc.isAlive()
                    || !canTrace(sender, playerNpc)) {
                PlayerNpcGoalTraceLogger.stopTrace(sender, "invalid trace target");
                return;
            }

            if (!packet.enabled && PlayerNpcGoalTraceLogger.isAllTraceEnabled()) {
                PlayerNpcGoalTraceLogger.setAllTraceEnabled(false, sender.getGameProfile().getName());
                PlayerNpcGoalTraceLogger.stopTrace(sender, "all trace disabled by viewer");
            } else {
                PlayerNpcGoalTraceLogger.setTraceEnabled(sender, playerNpc, packet.enabled);
            }
            SmartNpcNetwork.CHANNEL.sendToPlayer(sender,
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

    private static boolean canTrace(ServerPlayer sender, PlayerNpcEntity playerNpc) {
        if (sender.distanceToSqr(playerNpc) <= MAX_TRACE_DISTANCE_SQR) {
            return true;
        }

        return PlayerNpcInspectatorModePacket.isInspectatorActive(sender)
                && sender.getVehicle() == playerNpc;
    }
}
