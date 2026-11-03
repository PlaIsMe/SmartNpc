package com.pla.smart_npc.mixin;

import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.neoforged.neoforge.common.world.chunk.ForcedChunkManager$TicketOwner")
public interface TicketOwnerAccessor {
    @Accessor("id")
    Identifier smartNpc$getControllerId();
}
