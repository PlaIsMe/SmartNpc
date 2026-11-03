package com.pla.smart_npc.network;
import com.pla.smart_npc.platform.NpcPlatform;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import com.pla.smart_npc.network.NpcPacketContext;

import java.util.function.Supplier;

public class PlayerNpcInspectatorModePacket {
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

    public static void encode(PlayerNpcInspectatorModePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.active);
        buffer.writeVarInt(packet.entityId);
    }

    public static PlayerNpcInspectatorModePacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectatorModePacket(buffer.readBoolean(), buffer.readVarInt());
    }

    public static void handle(PlayerNpcInspectatorModePacket packet, Supplier<NpcPacketContext> contextSupplier) {
        NpcPacketContext context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }

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

    public static void restorePlayer(ServerPlayer player) {
        CompoundTag data = NpcPlatform.persistentData(player);
        boolean wasActive = data.getBoolean(ACTIVE_KEY);
        int originalGameMode = data.contains(ORIGINAL_GAME_MODE_KEY)
                ? data.getInt(ORIGINAL_GAME_MODE_KEY)
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
            SmartNpcNetwork.CHANNEL.sendToPlayer(player,
                    PlayerNpcInspectorPacket.clear()
            );
        }
    }

    public static boolean isInspectatorActive(Entity entity) {
        return entity != null && NpcPlatform.persistentData(entity).getBoolean(ACTIVE_KEY);
    }

    public static boolean isInspecting(ServerPlayer player, PlayerNpcEntity playerNpc) {
        if (!isInspectatorActive(player) || playerNpc == null) {
            return false;
        }

        CompoundTag data = NpcPlatform.persistentData(player);
        return player.getCamera() == playerNpc
                || data.hasUUID(TARGET_UUID_KEY) && data.getUUID(TARGET_UUID_KEY).equals(playerNpc.getUUID());
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

        CompoundTag data = NpcPlatform.persistentData(player);
        return data.hasUUID(TARGET_UUID_KEY)
                && data.getUUID(TARGET_UUID_KEY).equals(playerNpc.getUUID());
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
            player.teleportTo(serverLevel, playerNpc.getX(), playerNpc.getY(), playerNpc.getZ(), playerNpc.getYRot(), playerNpc.getXRot());
        }

        PlayerNpcGoalTraceLogger.stopIfTracingDifferentNpc(player, playerNpc);

        CompoundTag data = NpcPlatform.persistentData(player);
        if (!data.getBoolean(ACTIVE_KEY)) {
            data.putInt(ORIGINAL_GAME_MODE_KEY, player.gameMode.getGameModeForPlayer().getId());
            data.putBoolean(ACTIVE_KEY, true);
        }
        data.putUUID(TARGET_UUID_KEY, playerNpc.getUUID());

        player.setGameMode(GameType.SPECTATOR);
        player.setCamera(playerNpc);
    }
}
