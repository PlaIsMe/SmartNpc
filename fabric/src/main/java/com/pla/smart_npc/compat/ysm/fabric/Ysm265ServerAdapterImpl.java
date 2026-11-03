package com.pla.smart_npc.compat.ysm.fabric;
import com.pla.smart_npc.compat.YesSteveModelCompat.ModelChoice;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
public final class Ysm265ServerAdapterImpl {
    public static boolean isAvailable() { return FabricYsm265ServerAdapter.isAvailable(); }
    public static void beginServer(MinecraftServer server) { FabricYsm265ServerAdapter.beginServer(server); }
    public static void endServer() { FabricYsm265ServerAdapter.endServer(); }
    public static boolean isServerReady(MinecraftServer server) { return FabricYsm265ServerAdapter.isServerReady(server); }
    public static ModelChoice chooseServerModel(RandomSource random) { return FabricYsm265ServerAdapter.chooseServerModel(random); }
    public static ModelChoice chooseBaseServerModel(boolean slim) { return FabricYsm265ServerAdapter.chooseBaseServerModel(slim); }
    public static boolean hasServerModel(String modelId, String texture) { return FabricYsm265ServerAdapter.hasServerModel(modelId, texture); }
    public static ModelChoice recoverServerModel(String modelId, String texture, RandomSource random) { return FabricYsm265ServerAdapter.recoverServerModel(modelId, texture, random); }
    public static ModelChoice chooseNamedServerModelOrDefault(String filename) { return FabricYsm265ServerAdapter.chooseNamedServerModelOrDefault(filename); }
    public static String chooseServerEmote(String modelId, RandomSource random) { return FabricYsm265ServerAdapter.chooseServerEmote(modelId, random); }
}
