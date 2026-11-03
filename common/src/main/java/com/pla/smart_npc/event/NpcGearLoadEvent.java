package com.pla.smart_npc.event;

import com.pla.smart_npc.platform.event.NpcEvents.*;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.util.EquipmentDataLoader;
import com.pla.smart_npc.util.PlayerNpcChatTemplateLoader;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;

public class NpcGearLoadEvent {
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new EquipmentDataLoader());
        event.addListener(new PlayerNpcBuildLayoutLoader());
        event.addListener(new PlayerNpcChatTemplateLoader());
    }
}
