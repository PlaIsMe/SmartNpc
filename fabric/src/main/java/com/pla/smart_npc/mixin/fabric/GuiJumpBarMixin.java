package com.pla.smart_npc.mixin.fabric;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.pla.smart_npc.platform.event.NpcClientEventHooks;
import com.pla.smart_npc.platform.event.NpcClientEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(Gui.class)
public abstract class GuiJumpBarMixin {
    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private boolean smartNpc$background(ContextualBarRenderer renderer, GuiGraphicsExtractor graphics, DeltaTracker tracker) { return smartNpc$showBar(); }
    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private boolean smartNpc$bar(ContextualBarRenderer renderer, GuiGraphicsExtractor graphics, DeltaTracker tracker) { return smartNpc$showBar(); }
    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
    private boolean smartNpc$experience(GuiGraphicsExtractor graphics, Font font, int level) { return smartNpc$showBar(); }
    private static boolean smartNpc$showBar() {
        var event = new NpcClientEvents.RenderGuiLayerEvent.Pre(Identifier.withDefaultNamespace("contextual_info_bar"));
        NpcClientEventHooks.fire(event);
        return !event.isCanceled();
    }
}
