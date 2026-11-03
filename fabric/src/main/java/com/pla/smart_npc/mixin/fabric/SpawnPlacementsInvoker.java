package com.pla.smart_npc.mixin.fabric;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(SpawnPlacements.class)
public interface SpawnPlacementsInvoker {
    @Invoker("register")
    static <T extends Mob> void smartNpc$register(EntityType<T> type, net.minecraft.world.entity.SpawnPlacementType placement, Heightmap.Types heightmap, SpawnPlacements.SpawnPredicate<T> predicate) { throw new AssertionError(); }
}
