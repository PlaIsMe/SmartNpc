package com.pla.smart_npc.compat.ysm;

import com.elfmcys.yesstevemodel.OoOoOoooO0O00oOoO00OOo00;
import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.oO00O0OOOoO0000oOO0OOO00;
import com.elfmcys.yesstevemodel.oOoOOo00o00O0ooooOOO0oO0;
import com.pla.smart_npc.compat.YesSteveModelCompat.ModelChoice;
import com.pla.smart_npc.SmartNpc;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Released YSM 2.6.5 Forge API names, verified against Modrinth Zqooxsd2.
 * Do not substitute the similarly shaped CLIENT model cache: these are server-authoritative
 * models used by YSM's own server command and Touhou Little Maid roulette handling.
 */
public final class Ysm265ServerAdapter {
    private static MinecraftServer registryServer;
    private static Object registryBeforeStartup;
    private static Object indexedRegistry;
    private static Map<String, String> filenameIndex = Map.of();
    private static final Set<String> warnedFilenames = new HashSet<>();
    private static final Set<String> resolvedFilenames = new HashSet<>();
    private Ysm265ServerAdapter() {
    }

    public static boolean isAvailable() {
        return YesSteveModel.isAvailable();
    }

    public static void beginServer(MinecraftServer server) {
        registryServer = server;
        registryBeforeStartup = OoOoOoooO0O00oOoO00OOo00.Oo0Oo0o00O00Oo0OOoOOoooo();
        clearFilenameIndex();
    }

    public static void endServer() {
        registryServer = null;
        registryBeforeStartup = null;
        clearFilenameIndex();
    }

    private static void clearFilenameIndex() {
        indexedRegistry = null;
        filenameIndex = Map.of();
        warnedFilenames.clear();
        resolvedFilenames.clear();
    }

    private static Map<String, String> getFilenameIndex() {
        var registry = OoOoOoooO0O00oOoO00OOo00.Oo0Oo0o00O00Oo0OOoOOoooo();
        if (registry != indexedRegistry) {
            // In 2.6.5, loaded .ysm entries retain their filename (including extension)
            // in the server registry key. File-header hashes are NOT registry identities.
            filenameIndex = YsmModelFileIndex.build(registry.keySet());
            indexedRegistry = registry;
            warnedFilenames.clear();
            resolvedFilenames.clear();
        }
        return filenameIndex;
    }

    public static boolean isServerReady(MinecraftServer server) {
        // In the pinned release the native reload callback replaces the map on the server
        // thread. Identity also distinguishes a new integrated world from the previous one.
        return server == registryServer && registryBeforeStartup != null
                && OoOoOoooO0O00oOoO00OOo00.Oo0Oo0o00O00Oo0OOoOOoooo() != registryBeforeStartup;
    }

    public static ModelChoice chooseServerModel(RandomSource random) {
        List<ModelChoice> candidates = new ArrayList<>();
        for (Map.Entry<String, oOoOOo00o00O0ooooOOO0oO0> entry : OoOoOoooO0O00oOoO00OOo00.Oo0Oo0o00O00Oo0OOoOOoooo().entrySet()) {
            List<String> textures = entry.getValue().o0OOooo0o0OO00OoOOOo0o0O().o0OOooo0o0OO00OoOOOo0o0O();
            if (!entry.getKey().isBlank() && !textures.isEmpty()) {
                candidates.add(new ModelChoice(entry.getKey(), textures.get(0)));
            }
        }
        // One entry per MODEL, so models with many textures do not receive extra lottery weight.
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    public static boolean hasServerModel(String modelId, String texture) {
        return OoOoOoooO0O00oOoO00OOo00.Oo0Oo0o00O00Oo0OOoOOoooo(modelId)
                .map(model -> model.o0OOooo0o0OO00OoOOOo0o0O().o0OOooo0o0OO00OoOOOo0o0O().contains(texture))
                .orElse(false);
    }

    public static ModelChoice recoverServerModel(String modelId, String texture, RandomSource random) {
        // Keep a surviving model when only its texture changed. A removed/unusable
        // model gets the same equal-per-model lottery used for random spawn selection.
        ModelChoice existing = serverModelChoice(modelId, texture);
        return existing != null ? existing : chooseServerModel(random);
    }

    public static ModelChoice chooseNamedServerModelOrDefault(String filename) {
        String modelId = getFilenameIndex().get(filename);
        ModelChoice requested = modelId == null || modelId.isEmpty() ? null : serverModelChoice(modelId, null);
        if (requested != null) {
            if (resolvedFilenames.add(filename)) {
                SmartNpc.LOGGER.info("Smart NPC resolved server YSM file '{}.ysm' to model '{}' with texture '{}'.", filename, requested.modelId(), requested.texture());
            }
            return requested;
        }
        if (warnedFilenames.add(filename)) {
            SmartNpc.LOGGER.warn("Smart NPC could not uniquely match server YSM file '{}.ysm' to a loaded model; using YSM's default.", filename);
        }
        // YSM's own default resolver honors DefaultModelId/DefaultModelTexture and falls
        // back to the built-in "default" model when that configured ID is unavailable.
        var defaults = OoOoOoooO0O00oOoO00OOo00.O00OOOooOoooOoo0o0o0oO0O();
        ModelChoice fallback = serverModelChoice(defaults.getLeft(), defaults.getRight());
        return fallback != null ? fallback : serverModelChoice("default", "default");
    }

    private static ModelChoice serverModelChoice(String modelId, String texture) {
        oOoOOo00o00O0ooooOOO0oO0 model = OoOoOoooO0O00oOoO00OOo00.Oo0Oo0o00O00Oo0OOoOOoooo(modelId).orElse(null);
        if (model == null) {
            return null;
        }
        List<String> textures = model.o0OOooo0o0OO00OoOOOo0o0O().o0OOooo0o0OO00OoOOOo0o0O();
        if (textures.isEmpty()) {
            return null;
        }
        if (texture == null || !textures.contains(texture)) {
            String modelDefault = model.OOOOo0O0oO0OOo0O0O0Oo0O0().o0OOooo0o0OO00OoOOOo0o0O().O00OOOooOoooOoo0o0o0oO0O();
            texture = textures.contains(modelDefault) ? modelDefault : textures.get(0);
        }
        return new ModelChoice(modelId, texture);
    }

    public static String chooseServerEmote(String modelId, RandomSource random) {
        oOoOOo00o00O0ooooOOO0oO0 model = OoOoOoooO0O00oOoO00OOo00.Oo0Oo0o00O00Oo0OOoOOoooo(modelId).orElse(null);
        if (model == null) {
            return "";
        }
        oO00O0OOOoO0000oOO0OOO00 properties = model.OOOOo0O0oO0OOo0O0O0Oo0O0().o0OOooo0o0OO00OoOOOo0o0O();
        LinkedHashSet<String> emotes = new LinkedHashSet<>(properties.OOOOo0O0oO0OOo0O0O0Oo0O0().keySet());
        properties.oo0OoO00oOoo000O0000o0oo().values().forEach(group -> emotes.addAll(group.keySet()));
        emotes.removeIf(String::isBlank);
        List<String> choices = new ArrayList<>(emotes);
        return choices.isEmpty() ? "" : choices.get(random.nextInt(choices.size()));
    }
}
