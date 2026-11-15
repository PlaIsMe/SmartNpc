package com.pla.smart_npc.fabric;

import com.pla.smart_npc.client.gui.AiSetupClient;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class SmartNpcModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<?> getModConfigScreenFactory() { return AiSetupClient::screen; }
}
