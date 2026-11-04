package com.pla.smart_npc.mixin.fabric;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.platform.event.NpcEvents;
import com.pla.smart_npc.platform.event.NpcEventHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntity.class)
public abstract class LivingTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void smartNpc$tick(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity)(Object)this;
        if (!entity.level().isClientSide() && (entity instanceof PlayerNpcEntity || entity instanceof EnderDragon)) NpcEventHooks.fire(new NpcEvents.EntityTickEvent.Post(entity));
    }
}
