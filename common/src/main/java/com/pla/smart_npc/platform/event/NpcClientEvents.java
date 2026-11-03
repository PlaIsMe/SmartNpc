package com.pla.smart_npc.platform.event;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.Identifier;
import com.pla.smart_npc.platform.event.NpcEvents.Cancellable;
public final class NpcClientEvents {
    public static final class ClientTickEvent { public record Post() {} }
    public static final class RenderGuiLayerEvent {
        public static final class Pre extends Cancellable {
            private final Identifier name;
            public Pre(Identifier name) { this.name = name; }
            public Identifier getName() { return name; }
        }
    }
    public static final class ClientPlayerNetworkEvent { public record LoggingOut() {} }
    public record MovementInputUpdateEvent(Player getEntity, ClientInput getInput) {}
    public static final class RenderGuiEvent { public record Post(GuiGraphics getGuiGraphics) {} }
    public static final class ScreenEvent {
        public static final class Opening extends Cancellable {
            private final Screen screen;
            public Opening(Screen screen) { this.screen = screen; }
            public Screen getNewScreen() { return screen; }
        }
    }
}
