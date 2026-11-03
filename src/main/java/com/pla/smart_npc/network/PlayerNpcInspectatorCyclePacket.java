package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

public class PlayerNpcInspectatorCyclePacket implements CustomPacketPayload {
    public static final Type<PlayerNpcInspectatorCyclePacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smart_npc", "inspectator_cycle"));
    public static final StreamCodec<FriendlyByteBuf, PlayerNpcInspectatorCyclePacket> STREAM_CODEC = StreamCodec.ofMember(PlayerNpcInspectatorCyclePacket::encode, PlayerNpcInspectatorCyclePacket::decode);
    private final int currentEntityId;
    private final int direction;
    private final boolean includeRequirements;

    public PlayerNpcInspectatorCyclePacket(int currentEntityId, int direction, boolean includeRequirements) {
        this.currentEntityId = currentEntityId;
        this.direction = direction;
        this.includeRequirements = includeRequirements;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(this.currentEntityId);
        buffer.writeVarInt(this.direction);
        buffer.writeBoolean(this.includeRequirements);
    }

    public static PlayerNpcInspectatorCyclePacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectatorCyclePacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(PlayerNpcInspectatorCyclePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = (ServerPlayer) context.player();

            if (!PlayerNpcInspectatorModePacket.isInspectatorActive(sender)) {
                sendResult(sender, PlayerNpcInspectatorCycleResultPacket.handled(packet.currentEntityId));
                return;
            }

            if (!PlayerNpcForceTickManager.isEnabled()) {
                sendResult(sender, PlayerNpcInspectatorCycleResultPacket.unhandled(packet.currentEntityId, packet.direction));
                return;
            }

            if (!(sender.getCamera() instanceof PlayerNpcEntity currentNpc)
                    || currentNpc.getId() != packet.currentEntityId
                    || !PlayerNpcInspectatorModePacket.isInspecting(sender, currentNpc)) {
                PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(sender);
                sendResult(sender, PlayerNpcInspectatorCycleResultPacket.handled(-1));
                return;
            }
            var nextNpc = PlayerNpcForceTickManager.findNextForInspectator(
                    sender.level().getServer(),
                    currentNpc,
                    packet.direction
            );
            if (nextNpc.isEmpty()) {
                sendResult(sender, PlayerNpcInspectatorCycleResultPacket.handled(packet.currentEntityId));
                return;
            }

            PlayerNpcEntity target = nextNpc.get();
            PlayerNpcInspectatorModePacket.beginInspectator(sender, target, true);
            sendResult(sender, PlayerNpcInspectatorCycleResultPacket.handled(target.getId()));
            PacketDistributor.sendToPlayer(
                    sender,
                    new PlayerNpcInspectorPacket(
                            target.getId(),
                            PlayerNpcInspectorData.createSnapshot(target),
                            PlayerNpcInspectorData.createBuildStatusText(target),
                            PlayerNpcInspectorData.createPerformanceText(),
                            PlayerNpcInspectorData.createDailyJobText(target),
                            packet.includeRequirements ? PlayerNpcInspectorData.createBuildRequirementsText(target) : "",
                            PlayerNpcInspectorData.createTeamInfo(target),
                            PlayerNpcGoalTraceLogger.isEffectivelyTracing(sender, target)
                    )
            );
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void sendResult(ServerPlayer player, PlayerNpcInspectatorCycleResultPacket packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
