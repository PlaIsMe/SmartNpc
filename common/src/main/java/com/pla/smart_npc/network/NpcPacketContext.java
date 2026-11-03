package com.pla.smart_npc.network;
import net.minecraft.world.entity.player.Player;
import java.util.function.Consumer;
public record NpcPacketContext(Player player, Consumer<Runnable> executor) {
    public void enqueueWork(Runnable task) { executor.accept(task); }
}
