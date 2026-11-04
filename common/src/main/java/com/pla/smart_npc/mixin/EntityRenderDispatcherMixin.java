package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.renderer.FakePlayerRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 26.1 selects a renderer again while submitting an extracted render
 * state. Every AvatarRenderState is routed to a vanilla AvatarRenderer before
 * the entity-type renderer map is consulted. Smart NPC needs an avatar state
 * for PlayerModel, but it is not an Avatar and must continue using its
 * registered FakePlayerRenderer.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(
            method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
            at = @At("HEAD"),
            cancellable = true
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private <S extends EntityRenderState> void player_npc$keepFakePlayerRenderer(
            S state,
            CallbackInfoReturnable<EntityRenderer<?, ? super S>> callback
    ) {
        if (state instanceof FakePlayerRenderState npcState && npcState.playerNpc != null) {
            EntityRenderDispatcher dispatcher = (EntityRenderDispatcher) (Object) this;
            callback.setReturnValue((EntityRenderer) dispatcher.getRenderer(npcState.playerNpc));
        }
    }
}
