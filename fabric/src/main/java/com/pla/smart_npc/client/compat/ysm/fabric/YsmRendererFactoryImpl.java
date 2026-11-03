package com.pla.smart_npc.client.compat.ysm.fabric;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
public final class YsmRendererFactoryImpl {
    public static YesSteveModelClientCompat.Renderer create(EntityRendererProvider.Context context) { return new Ysm265Renderer(context); }
}
