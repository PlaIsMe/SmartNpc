package com.pla.smart_npc.mixin.neoforge;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(value = ForcedChunkManager.TicketTracker.class, remap = false)
public interface TicketTrackerAccessor {
    @Accessor("sourcesLoading")
    Long2ObjectMap<Set<?>> smartNpc$getSourcesLoading();
}
