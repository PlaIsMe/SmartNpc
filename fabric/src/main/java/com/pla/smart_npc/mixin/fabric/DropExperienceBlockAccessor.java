package com.pla.smart_npc.mixin.fabric;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.util.valueproviders.IntProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(DropExperienceBlock.class)
public interface DropExperienceBlockAccessor {
    @Accessor("xpRange") IntProvider smartNpc$experience();
}
