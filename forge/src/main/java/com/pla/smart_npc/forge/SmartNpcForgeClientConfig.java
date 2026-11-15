package com.pla.smart_npc.forge;

import com.pla.smart_npc.client.gui.AiSetupClient;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/** Loaded only on the physical client. */
final class SmartNpcForgeClientConfig {
    static void register(ModLoadingContext context) {
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> AiSetupClient.screen(parent)));
    }
}
