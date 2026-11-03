package com.pla.smart_npc.compat.ysm.neoforge;
import com.pla.smart_npc.compat.YesSteveModelCompat.ModelChoice;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
public final class Ysm265ServerAdapterImpl {
    public static boolean isAvailable() { return NeoForgeYsm265ServerAdapter.isAvailable(); }
    public static void beginServer(MinecraftServer server) { NeoForgeYsm265ServerAdapter.beginServer(server); }
    public static void endServer() { NeoForgeYsm265ServerAdapter.endServer(); }
    public static boolean isServerReady(MinecraftServer server) { return NeoForgeYsm265ServerAdapter.isServerReady(server); }
    public static ModelChoice chooseServerModel(RandomSource random) { return NeoForgeYsm265ServerAdapter.chooseServerModel(random); }
    public static ModelChoice chooseBaseServerModel(boolean slim) { return NeoForgeYsm265ServerAdapter.chooseBaseServerModel(slim); }
    public static boolean hasServerModel(String modelId, String texture) { return NeoForgeYsm265ServerAdapter.hasServerModel(modelId, texture); }
    public static ModelChoice recoverServerModel(String modelId, String texture, RandomSource random) { return NeoForgeYsm265ServerAdapter.recoverServerModel(modelId, texture, random); }
    public static ModelChoice chooseNamedServerModelOrDefault(String filename) { return NeoForgeYsm265ServerAdapter.chooseNamedServerModelOrDefault(filename); }
    public static String chooseServerEmote(String modelId, RandomSource random) { return NeoForgeYsm265ServerAdapter.chooseServerEmote(modelId, random); }
}
