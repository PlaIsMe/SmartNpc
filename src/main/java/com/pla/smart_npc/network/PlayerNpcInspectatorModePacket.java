package com.pla.smart_npc.network;

import com.pla.smart_npc.util.SmartNpcNbt;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

public class PlayerNpcInspectatorModePacket implements CustomPacketPayload {
    public static final Type<PlayerNpcInspectatorModePacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smart_npc", "inspectator_mode"));
    public static final StreamCodec<FriendlyByteBuf, PlayerNpcInspectatorModePacket> STREAM_CODEC = StreamCodec.ofMember(PlayerNpcInspectatorModePacket::encode, PlayerNpcInspectatorModePacket::decode);
    private static final String ACTIVE_KEY = "PlayerNpcInspectatorActive";
    private static final String ORIGINAL_GAME_MODE_KEY = "PlayerNpcInspectatorOriginalGameMode";
    private static final String TARGET_UUID_KEY = "PlayerNpcInspectatorTarget";
    private static final double MAX_START_DISTANCE_SQR = 96.0D * 96.0D;

    private final boolean active;
    private final int entityId;

    public PlayerNpcInspectatorModePacket(boolean active, int entityId) {
        this.active = active;
        this.entityId = entityId;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBoolean(this.active);
        buffer.writeVarInt(this.entityId);
    }

    public static PlayerNpcInspectatorModePacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectatorModePacket(buffer.readBoolean(), buffer.readVarInt());
    }

    public static void handle(PlayerNpcInspectatorModePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer sender = (ServerPlayer) context.player();

            if (!packet.active) {
                restorePlayer(sender);
                return;
            }

            Entity entity = sender.level().getEntity(packet.entityId);
            if (!(entity instanceof PlayerNpcEntity playerNpc)
                    || !playerNpc.isAlive()
                    || sender.distanceToSqr(playerNpc) > MAX_START_DISTANCE_SQR) {
                restorePlayer(sender);
                return;
            }

            beginInspectator(sender, playerNpc);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void restorePlayer(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        boolean wasActive = data.getBooleanOr(ACTIVE_KEY, false);
        int originalGameMode = data.contains(ORIGINAL_GAME_MODE_KEY)
                ? data.getIntOr(ORIGINAL_GAME_MODE_KEY, 0)
                : player.gameMode.getGameModeForPlayer().getId();
        data.remove(ACTIVE_KEY);
        data.remove(ORIGINAL_GAME_MODE_KEY);
        data.remove(TARGET_UUID_KEY);

        player.setCamera(player);
        // Clean up passengers left by older inspector sessions, which used
        // forced riding as their camera anchor.
        if (player.getVehicle() instanceof PlayerNpcEntity) {
            player.stopRiding();
        }

        if (wasActive) {
            player.setGameMode(GameType.byId(originalGameMode));
        }
    }

    public static void restorePlayerAndClearInspector(ServerPlayer player) {
        restorePlayer(player);
        if (player.connection != null) {
            PacketDistributor.sendToPlayer(
                    player,
                    PlayerNpcInspectorPacket.clear()
            );
        }
    }

    public static boolean isInspectatorActive(Entity entity) {
        return entity != null && entity.getPersistentData().getBooleanOr(ACTIVE_KEY, false);
    }

    public static boolean isInspecting(ServerPlayer player, PlayerNpcEntity playerNpc) {
        if (!isInspectatorActive(player) || playerNpc == null) {
            return false;
        }

        CompoundTag data = player.getPersistentData();
        return player.getCamera() == playerNpc
                || SmartNpcNbt.hasUuid(data, TARGET_UUID_KEY) && SmartNpcNbt.getUuid(data, TARGET_UUID_KEY).equals(playerNpc.getUUID());
    }

    public static boolean hasValidInspectatorTarget(ServerPlayer player) {
        if (!isInspectatorActive(player)) {
            return true;
        }
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            return false;
        }

        Entity camera = player.getCamera();
        if (!(camera instanceof PlayerNpcEntity playerNpc)
                || !playerNpc.isAlive()
                || playerNpc.isRemoved()
                || playerNpc.level() != player.level()) {
            return false;
        }

        CompoundTag data = player.getPersistentData();
        return SmartNpcNbt.hasUuid(data, TARGET_UUID_KEY)
                && SmartNpcNbt.getUuid(data, TARGET_UUID_KEY).equals(playerNpc.getUUID());
    }

    public static void beginInspectator(ServerPlayer player, PlayerNpcEntity playerNpc) {
        beginInspectator(player, playerNpc, false);
    }

    public static void beginInspectator(ServerPlayer player, PlayerNpcEntity playerNpc, boolean teleportToNpc) {
        if (!player.isAlive()
                || player.isRemoved()
                || !playerNpc.isAlive()
                || playerNpc.isRemoved()
                || !(playerNpc.level() instanceof ServerLevel serverLevel)) {
            restorePlayerAndClearInspector(player);
            return;
        }

        if (player.getVehicle() instanceof PlayerNpcEntity) {
            player.stopRiding();
        }

        if (teleportToNpc && player.level() != serverLevel) {
            player.teleportTo(serverLevel, playerNpc.getX(), playerNpc.getY(), playerNpc.getZ(), java.util.Set.of(), playerNpc.getYRot(), playerNpc.getXRot(), false);
        }

        PlayerNpcGoalTraceLogger.stopIfTracingDifferentNpc(player, playerNpc);

        CompoundTag data = player.getPersistentData();
        if (!data.getBooleanOr(ACTIVE_KEY, false)) {
            data.putInt(ORIGINAL_GAME_MODE_KEY, player.gameMode.getGameModeForPlayer().getId());
            data.putBoolean(ACTIVE_KEY, true);
        }
        SmartNpcNbt.putUuid(data, TARGET_UUID_KEY, playerNpc.getUUID());

        player.setGameMode(GameType.SPECTATOR);
        player.setCamera(playerNpc);
    }
}
