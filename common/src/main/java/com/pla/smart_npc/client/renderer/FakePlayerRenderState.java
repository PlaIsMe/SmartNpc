package com.pla.smart_npc.client.renderer;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat.Frame;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.jspecify.annotations.Nullable;

public class FakePlayerRenderState extends AvatarRenderState {
    public @Nullable PlayerNpcEntity playerNpc;
    public @Nullable Frame ysmFrame;
    public float partialTick;
    public boolean slim;
    public boolean hideHead;
    public float swimXRot;
}
