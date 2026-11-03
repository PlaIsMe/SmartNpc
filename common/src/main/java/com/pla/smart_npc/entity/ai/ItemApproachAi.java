package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Session-local failed-route memory shared by pickup and disposal; goal restarts do not reset it. */
public final class ItemApproachAi {
    private static final int EPISODE_TICKS = 20 * 20;
    private static final int QUARANTINE_TICKS = 20 * 60;
    private final PlayerNpcEntity npc;
    private final Map<UUID, Episode> episodes = new LinkedHashMap<>();

    public ItemApproachAi(PlayerNpcEntity npc) {
        this.npc = npc;
    }

    public boolean canAttempt(ItemEntity item) {
        if (item == null) {
            return false;
        }
        Episode episode = this.episodes.get(item.getUUID());
        if (episode == null) {
            return true;
        }
        if (episode.quarantined) {
            if (this.npc.tickCount < episode.retryTick) {
                return false;
            }
            this.episodes.remove(item.getUUID());
            return true;
        }
        if (this.npc.tickCount >= episode.deadlineTick) {
            episode.quarantined = true;
            episode.retryTick = this.npc.tickCount + QUARANTINE_TICKS;
            return false;
        }
        return this.npc.tickCount >= episode.retryTick;
    }

    public void begin(ItemEntity item) {
        if (item != null) {
            this.episodes.computeIfAbsent(item.getUUID(), id -> new Episode(this.npc.tickCount + EPISODE_TICKS));
            while (this.episodes.size() > 24) {
                this.episodes.remove(this.episodes.keySet().iterator().next());
            }
        }
    }

    public void interrupted(ItemEntity item) {
        if (item == null || !item.isAlive() || item.getItem().isEmpty()) {
            return;
        }
        this.begin(item);
        Episode episode = this.episodes.get(item.getUUID());
        if (!episode.quarantined) {
            episode.retryTick = Math.max(episode.retryTick, this.npc.tickCount + 40);
        }
    }

    public void failed(ItemEntity item) {
        if (item != null) {
            this.begin(item);
            Episode episode = this.episodes.get(item.getUUID());
            episode.quarantined = true;
            episode.retryTick = this.npc.tickCount + QUARANTINE_TICKS;
        }
    }

    private static final class Episode {
        private final int deadlineTick;
        private int retryTick;
        private boolean quarantined;

        private Episode(int deadlineTick) {
            this.deadlineTick = deadlineTick;
        }
    }
}
