package com.pla.smart_npc.compat.ysm.fabric;
import com.pla.smart_npc.compat.YesSteveModelCompat.ModelChoice;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
public final class Ysm265ServerAdapterImpl {
    public static boolean isAvailable() { return false; }
    public static void beginServer(MinecraftServer server) {  }
    public static void endServer() {  }
    public static boolean isServerReady(MinecraftServer server) { return false; }
    public static ModelChoice chooseServerModel(RandomSource random) { return null; }
    public static ModelChoice chooseBaseServerModel(boolean slim) { return null; }
    public static boolean hasServerModel(String modelId, String texture) { return false; }
    public static ModelChoice recoverServerModel(String modelId, String texture, RandomSource random) { return null; }
    public static ModelChoice chooseNamedServerModelOrDefault(String filename) { return null; }
    public static String chooseServerEmote(String modelId, RandomSource random) { return ""; }
}
