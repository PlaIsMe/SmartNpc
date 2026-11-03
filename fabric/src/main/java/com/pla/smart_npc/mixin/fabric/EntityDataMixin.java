package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.fabric.PersistentEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public abstract class EntityDataMixin implements PersistentEntityData {
    @Unique private CompoundTag smartNpc$data = new CompoundTag();
    @Override public CompoundTag smartNpc$persistentData() { return smartNpc$data; }
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void smartNpc$save(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) { if (!smartNpc$data.isEmpty()) tag.put("NeoForgeData", smartNpc$data.copy()); }
    @Inject(method = "load", at = @At("HEAD"))
    private void smartNpc$load(CompoundTag tag, CallbackInfo ci) { smartNpc$data = tag.getCompound("NeoForgeData").copy(); }
}
