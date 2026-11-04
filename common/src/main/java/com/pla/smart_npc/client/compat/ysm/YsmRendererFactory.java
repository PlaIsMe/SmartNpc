package com.pla.smart_npc.client.compat.ysm;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
public final class YsmRendererFactory {
    @ExpectPlatform
    public static YesSteveModelClientCompat.Renderer create(EntityRendererProvider.Context context) { throw new AssertionError(); }
}
