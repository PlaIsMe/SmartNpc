package com.pla.smart_npc.client.compat.ysm;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import dev.architectury.injectables.annotations.ExpectPlatform;
public final class YsmRendererFactory {
    @ExpectPlatform
    public static YesSteveModelClientCompat.Renderer create(EntityRendererProvider.Context context) { throw new AssertionError(); }
}
