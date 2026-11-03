package com.pla.smart_npc.network;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class PlayerNpcInspectatorCycleResultPacket implements CustomPacketPayload {
    public static final Type<PlayerNpcInspectatorCycleResultPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smart_npc", "inspectator_cycle_result"));
    public static final StreamCodec<FriendlyByteBuf, PlayerNpcInspectatorCycleResultPacket> STREAM_CODEC = StreamCodec.ofMember(PlayerNpcInspectatorCycleResultPacket::encode, PlayerNpcInspectatorCycleResultPacket::decode);
    private final boolean handledByServer;
    private final int entityId;
    private final int direction;

    public PlayerNpcInspectatorCycleResultPacket(boolean handledByServer, int entityId, int direction) {
        this.handledByServer = handledByServer;
        this.entityId = entityId;
        this.direction = direction;
    }

    public static PlayerNpcInspectatorCycleResultPacket handled(int entityId) {
        return new PlayerNpcInspectatorCycleResultPacket(true, entityId, 0);
    }

    public static PlayerNpcInspectatorCycleResultPacket unhandled(int currentEntityId, int direction) {
        return new PlayerNpcInspectatorCycleResultPacket(false, currentEntityId, direction);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(this.handledByServer);
        buffer.writeVarInt(this.entityId);
        buffer.writeVarInt(this.direction);
    }

    public static PlayerNpcInspectatorCycleResultPacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectatorCycleResultPacket(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
    }

    public boolean handledByServer() {
        return handledByServer;
    }

    public int entityId() {
        return entityId;
    }

    public int direction() {
        return direction;
    }

    public static void handle(PlayerNpcInspectatorCycleResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> SmartNpcInspectorOverlay.handleInspectatorCycleResult(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
