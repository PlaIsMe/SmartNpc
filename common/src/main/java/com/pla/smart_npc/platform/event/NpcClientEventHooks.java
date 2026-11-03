package com.pla.smart_npc.platform.event;
import com.pla.smart_npc.platform.event.NpcClientEvents.*;
import com.pla.smart_npc.platform.event.NpcEvents.*;
public final class NpcClientEventHooks {
    public static void fire(ClientPlayerNetworkEvent.LoggingOut event) {
        com.pla.smart_npc.client.compat.YesSteveModelClientCompat.onLogout(event);
    }
    public static void fire(TickEvent.ClientTickEvent event) {
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onClientTick(event);
    }
    public static void fire(ScreenEvent.Opening event) {
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onScreenOpening(event);
    }
    public static void fire(MovementInputUpdateEvent event) {
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onMovementInputUpdate(event);
    }
    public static void fire(RenderGuiOverlayEvent.Pre event) {
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onRenderGuiOverlayPre(event);
    }
    public static void fire(RenderGuiEvent.Post event) {
        com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.onRenderGui(event);
    }
}
