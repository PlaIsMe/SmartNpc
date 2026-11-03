package com.pla.smart_npc.platform.event;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/** Loader-neutral arguments passed to the shared event handlers. No event bus is installed here. */
public final class NpcEvents {
    private NpcEvents() {}
    public static class Cancellable {
        private boolean cancelled;
        public boolean isCanceled() { return cancelled; }
        public void setCanceled(boolean value) { cancelled = value; }
    }
    public record RegisterCommandsEvent(CommandDispatcher<CommandSourceStack> getDispatcher) {}
    public record EntityJoinLevelEvent(Entity getEntity, Level getLevel) {}
    public record EntityLeaveLevelEvent(Entity getEntity, Level getLevel) {}
    public record LivingDeathEvent(LivingEntity getEntity) {}
    public static final class LivingAttackEvent extends Cancellable {
        private final LivingEntity entity;
        private final DamageSource source;
        public LivingAttackEvent(LivingEntity entity, DamageSource source) { this.entity = entity; this.source = source; }
        public LivingEntity getEntity() { return entity; }
        public DamageSource getSource() { return source; }
    }
    public static final class LivingEvent {
        public record LivingTickEvent(LivingEntity getEntity) {}
    }
    public static final class PlayerEvent {
        public static final class TabListNameFormat {
            private final Player player;
            private net.minecraft.network.chat.Component displayName;
            public TabListNameFormat(Player player) { this.player = player; }
            public Player getEntity() { return player; }
            public void setDisplayName(net.minecraft.network.chat.Component value) { displayName = value; }
            public net.minecraft.network.chat.Component getDisplayName() { return displayName; }
        }
        public record PlayerLoggedInEvent(Player getEntity) {}
        public record PlayerLoggedOutEvent(Player getEntity) {}
        public record PlayerRespawnEvent(Player getEntity) {}
        public record PlayerChangedDimensionEvent(Player getEntity) {}
        public record Clone(Player getEntity, Player getOriginal) {}
    }
    public record ServerAboutToStartEvent(MinecraftServer getServer) {}
    public record ServerStartedEvent(MinecraftServer getServer) {}
    public record ServerStoppingEvent(MinecraftServer getServer) {}
    public record ServerStoppedEvent(MinecraftServer getServer) {}
    public static final class TickEvent {
        public enum Phase { START, END }
        public static final class ServerTickEvent {
            private final MinecraftServer server;
            public final Phase phase;
            public ServerTickEvent(MinecraftServer server, Phase phase) { this.server = server; this.phase = phase; }
            public MinecraftServer getServer() { return server; }
        }
        public static final class PlayerTickEvent {
            public final Player player;
            public final Phase phase;
            public PlayerTickEvent(Player player, Phase phase) { this.player = player; this.phase = phase; }
        }
        public record ClientTickEvent(Phase phase) {}
    }
    public static final class BlockEvent {
        public static final class BreakEvent extends Cancellable {
            private final LevelAccessor level; private final BlockPos pos; private final BlockState state; private final Player player;
            public BreakEvent(LevelAccessor level, BlockPos pos, BlockState state, Player player) { this.level=level; this.pos=pos; this.state=state; this.player=player; }
            public LevelAccessor getLevel() { return level; }
            public BlockPos getPos() { return pos; }
            public BlockState getState() { return state; }
            public Player getPlayer() { return player; }
        }
    }
    public static final class PlayerInteractEvent {
        public static final class RightClickBlock extends Cancellable {
            private final Level level; private final BlockPos pos; private final Player player;
            public RightClickBlock(Level level, BlockPos pos, Player player) { this.level=level; this.pos=pos; this.player=player; }
            public Level getLevel() { return level; }
            public BlockPos getPos() { return pos; }
            public Player getEntity() { return player; }
        }
    }
    public record AddReloadListenerEvent(java.util.function.Consumer<net.minecraft.server.packs.resources.PreparableReloadListener> add) {
        public void addListener(net.minecraft.server.packs.resources.PreparableReloadListener listener) { add.accept(listener); }
    }
}
