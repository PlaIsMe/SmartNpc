package com.pla.smart_npc;
import com.pla.smart_npc.init.*;
import com.pla.smart_npc.network.SmartNpcNetwork;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SmartNpc {
    public static final String MODID = "smart_npc";
    public static final Logger LOGGER = LogManager.getLogger(SmartNpc.class);
    private SmartNpc() {}
    public static void init() {
        com.pla.smart_npc.platform.NpcPlatform.initialize();
        SmartNpcModEntities.REGISTRY.register();
        SmartNpcModItems.REGISTRY.register();
        SmartNpcModMenus.REGISTRY.register();
        SmartNpcModCreativeTabs.register();
        SmartNpcNetwork.register();
    }
}
