package com.pla.smart_npc.platform.event;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import com.pla.smart_npc.platform.event.NpcEvents.Cancellable;
public final class NpcClientEvents {
    public static final class ClientPlayerNetworkEvent { public record LoggingOut() {} }
    public record MovementInputUpdateEvent(Player getEntity, Input getInput) {}
    public static final class RenderGuiEvent { public record Post(GuiGraphics getGuiGraphics) {} }
    public static final class ScreenEvent {
        public static final class Opening extends Cancellable {
            private final Screen screen;
            public Opening(Screen screen) { this.screen = screen; }
            public Screen getNewScreen() { return screen; }
        }
    }
    public static final class RenderGuiOverlayEvent {
        public record Overlay(ResourceLocation id) {}
        public static final class Pre extends Cancellable {
            private final Overlay overlay;
            public Pre(ResourceLocation id) { overlay = new Overlay(id); }
            public Overlay getOverlay() { return overlay; }
        }
    }
}
