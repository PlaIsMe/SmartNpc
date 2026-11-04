package com.pla.smart_npc.event;
import com.pla.smart_npc.platform.event.NpcEvents.*;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.util.EquipmentDataLoader;
import com.pla.smart_npc.util.PlayerNpcChatTemplateLoader;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import net.minecraft.resources.Identifier;

public class NpcGearLoadEvent {

    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(SmartNpc.MODID, "equipment"), new EquipmentDataLoader());
        event.addListener(Identifier.fromNamespaceAndPath(SmartNpc.MODID, "build_layouts"), new PlayerNpcBuildLayoutLoader());
        event.addListener(Identifier.fromNamespaceAndPath(SmartNpc.MODID, "chat_templates"), new PlayerNpcChatTemplateLoader());
    }
}
