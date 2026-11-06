package com.pla.smart_npc.mixin;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Monster.class)
public abstract class MonsterMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void player_npc$targetPlayerNpcs(EntityType<? extends Monster> entityType, Level level, CallbackInfo ci) {
        Monster monster = (Monster) (Object) this;
        if (!(monster instanceof Enderman)) {
            monster.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(monster, PlayerNpcEntity.class, true));
        }
    }
}
