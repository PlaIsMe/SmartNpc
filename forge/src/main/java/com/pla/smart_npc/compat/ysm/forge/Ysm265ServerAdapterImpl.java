package com.pla.smart_npc.compat.ysm.forge;
import com.pla.smart_npc.compat.YesSteveModelCompat.ModelChoice;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
public final class Ysm265ServerAdapterImpl {
    public static boolean isAvailable() { return ForgeYsm265ServerAdapter.isAvailable(); }
    public static void beginServer(MinecraftServer server) { ForgeYsm265ServerAdapter.beginServer(server); }
    public static void endServer() { ForgeYsm265ServerAdapter.endServer(); }
    public static boolean isServerReady(MinecraftServer server) { return ForgeYsm265ServerAdapter.isServerReady(server); }
    public static ModelChoice chooseServerModel(RandomSource random) { return ForgeYsm265ServerAdapter.chooseServerModel(random); }
    public static ModelChoice chooseBaseServerModel(boolean slim) { return ForgeYsm265ServerAdapter.chooseBaseServerModel(slim); }
    public static boolean hasServerModel(String modelId, String texture) { return ForgeYsm265ServerAdapter.hasServerModel(modelId, texture); }
    public static ModelChoice recoverServerModel(String modelId, String texture, RandomSource random) { return ForgeYsm265ServerAdapter.recoverServerModel(modelId, texture, random); }
    public static ModelChoice chooseNamedServerModelOrDefault(String filename) { return ForgeYsm265ServerAdapter.chooseNamedServerModelOrDefault(filename); }
    public static String chooseServerEmote(String modelId, RandomSource random) { return ForgeYsm265ServerAdapter.chooseServerEmote(modelId, random); }
}
