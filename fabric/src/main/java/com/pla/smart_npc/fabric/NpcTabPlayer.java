package com.pla.smart_npc.fabric;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Display-only player used to build tab packets; never joins the level or player list. */
public final class NpcTabPlayer extends ServerPlayer {
    public NpcTabPlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        this.connection = new TabListener(this);
    }

    private static final class TabListener extends ServerGamePacketListenerImpl {
        private TabListener(ServerPlayer player) {
            super(player.level().getServer(), new net.minecraft.network.Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        @Override public void send(Packet<?> packet) {}
        @Override public void tick() {}
    }
}
