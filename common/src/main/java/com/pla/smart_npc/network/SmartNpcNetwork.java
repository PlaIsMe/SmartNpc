package com.pla.smart_npc.network;
import dev.architectury.injectables.annotations.ExpectPlatform;
public final class SmartNpcNetwork {
    public static final String PROTOCOL_VERSION = "8";
    private SmartNpcNetwork() {}
    @ExpectPlatform
    public static void register() { throw new AssertionError(); }
}
