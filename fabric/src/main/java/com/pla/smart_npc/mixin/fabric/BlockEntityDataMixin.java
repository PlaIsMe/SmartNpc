package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.fabric.PersistentEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockEntity.class)
public abstract class BlockEntityDataMixin implements PersistentEntityData {
    @Unique private CompoundTag smartNpc$data = new CompoundTag();
    @Override public CompoundTag smartNpc$persistentData() { return smartNpc$data; }
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void smartNpc$save(net.minecraft.world.level.storage.ValueOutput output, CallbackInfo ci) { if (!smartNpc$data.isEmpty()) output.store("NeoForgeData", CompoundTag.CODEC, smartNpc$data.copy()); }
    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void smartNpc$load(net.minecraft.world.level.storage.ValueInput input, CallbackInfo ci) { smartNpc$data = input.read("NeoForgeData", CompoundTag.CODEC).orElseGet(CompoundTag::new).copy(); }
}
