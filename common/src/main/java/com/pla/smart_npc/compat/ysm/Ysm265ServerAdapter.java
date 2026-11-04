package com.pla.smart_npc.compat.ysm;
import com.pla.smart_npc.compat.YesSteveModelCompat.ModelChoice;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import dev.architectury.injectables.annotations.ExpectPlatform;
public final class Ysm265ServerAdapter {
    @ExpectPlatform
    public static boolean isAvailable() { throw new AssertionError(); }
    @ExpectPlatform
    public static void beginServer(MinecraftServer server) { throw new AssertionError(); }
    @ExpectPlatform
    public static void endServer() { throw new AssertionError(); }
    @ExpectPlatform
    public static boolean isServerReady(MinecraftServer server) { throw new AssertionError(); }
    @ExpectPlatform
    public static ModelChoice chooseServerModel(RandomSource random) { throw new AssertionError(); }
    @ExpectPlatform
    public static ModelChoice chooseBaseServerModel(boolean slim) { throw new AssertionError(); }
    @ExpectPlatform
    public static boolean hasServerModel(String modelId, String texture) { throw new AssertionError(); }
    @ExpectPlatform
    public static ModelChoice recoverServerModel(String modelId, String texture, RandomSource random) { throw new AssertionError(); }
    @ExpectPlatform
    public static ModelChoice chooseNamedServerModelOrDefault(String filename) { throw new AssertionError(); }
    @ExpectPlatform
    public static String chooseServerEmote(String modelId, RandomSource random) { throw new AssertionError(); }
}
