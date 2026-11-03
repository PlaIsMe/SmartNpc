package com.pla.smart_npc.client.renderer;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;

public class FakePlayerRenderState extends AvatarRenderState {
    public @Nullable PlayerNpcEntity playerNpc;
    public boolean slim;
    public boolean hideHead;
    public float swimXRot;
}
