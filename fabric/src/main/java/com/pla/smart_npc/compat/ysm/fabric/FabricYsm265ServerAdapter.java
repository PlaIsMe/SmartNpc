package com.pla.smart_npc.compat.ysm.fabric;
import com.pla.smart_npc.compat.ysm.YsmModelFileIndex;
import com.pla.smart_npc.compat.ysm.YsmBaseModels;

import com.elfmcys.yesstevemodel.oOO000000OooOoo0o0OoO00o;
import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.oO0Oo0ooOOoOOOooOoo0O00o;
import com.elfmcys.yesstevemodel.o00o0OO0OooO00Oo0oOoooo0;
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
 * Released YSM 2.6.5 Fabric API names, verified against Modrinth LF0NYrRj.
 * Do not substitute the similarly shaped CLIENT model cache: these are server-authoritative
 * models used by YSM's own server command and Touhou Little Maid roulette handling.
 */
public final class FabricYsm265ServerAdapter {
    private static MinecraftServer registryServer;
    private static Object registryBeforeStartup;
    private static Object indexedRegistry;
    private static Map<String, String> filenameIndex = Map.of();
    private static final Set<String> warnedFilenames = new HashSet<>();
    private static final Set<String> resolvedFilenames = new HashSet<>();
    private FabricYsm265ServerAdapter() {
    }

    public static boolean isAvailable() {
        return YesSteveModel.isAvailable();
    }

    public static void beginServer(MinecraftServer server) {
        registryServer = server;
        registryBeforeStartup = oOO000000OooOoo0o0OoO00o.OoOo0OooO0OOO0Oo00000o00();
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
        var registry = oOO000000OooOoo0o0OoO00o.OoOo0OooO0OOO0Oo00000o00();
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
                && oOO000000OooOoo0o0OoO00o.OoOo0OooO0OOO0Oo00000o00() != registryBeforeStartup;
    }

    public static ModelChoice chooseServerModel(RandomSource random) {
        List<ModelChoice> candidates = new ArrayList<>();
        for (Map.Entry<String, o00o0OO0OooO00Oo0oOoooo0> entry : oOO000000OooOoo0o0OoO00o.OoOo0OooO0OOO0Oo00000o00().entrySet()) {
            List<String> textures = entry.getValue().OoooO000OO0OoO0o0o0ooooO().OoooO000OO0OoO0o0o0ooooO();
            if (!entry.getKey().isBlank() && !YsmBaseModels.isBaseModel(entry.getKey()) && !textures.isEmpty()) {
                candidates.add(new ModelChoice(entry.getKey(), textures.get(0)));
            }
        }
        // One entry per MODEL, so models with many textures do not receive extra lottery weight.
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    public static ModelChoice chooseBaseServerModel(boolean slim) {
        return serverModelChoice(slim ? YsmBaseModels.ALEX : YsmBaseModels.STEVE, null);
    }

    public static boolean hasServerModel(String modelId, String texture) {
        return oOO000000OooOoo0o0OoO00o.OoOo0OooO0OOO0Oo00000o00(modelId)
                .map(model -> model.OoooO000OO0OoO0o0o0ooooO().OoooO000OO0OoO0o0o0ooooO().contains(texture))
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
        var defaults = oOO000000OooOoo0o0OoO00o.o0OOoooO000O0O0oo0O0o00o();
        ModelChoice fallback = serverModelChoice(defaults.getLeft(), defaults.getRight());
        return fallback != null && !YsmBaseModels.isBaseModel(fallback.modelId()) ? fallback : serverModelChoice("default", "default");
    }

    private static ModelChoice serverModelChoice(String modelId, String texture) {
        o00o0OO0OooO00Oo0oOoooo0 model = oOO000000OooOoo0o0OoO00o.OoOo0OooO0OOO0Oo00000o00(modelId).orElse(null);
        if (model == null) {
            return null;
        }
        List<String> textures = model.OoooO000OO0OoO0o0o0ooooO().OoooO000OO0OoO0o0o0ooooO();
        if (textures.isEmpty()) {
            return null;
        }
        if (texture == null || !textures.contains(texture)) {
            String modelDefault = model.o00O00o0Oo0O0oOO00oOOOO0().OoooO000OO0OoO0o0o0ooooO().o0OOoooO000O0O0oo0O0o00o();
            texture = textures.contains(modelDefault) ? modelDefault : textures.get(0);
        }
        return new ModelChoice(modelId, texture);
    }

    public static String chooseServerEmote(String modelId, RandomSource random) {
        o00o0OO0OooO00Oo0oOoooo0 model = oOO000000OooOoo0o0OoO00o.OoOo0OooO0OOO0Oo00000o00(modelId).orElse(null);
        if (model == null) {
            return "";
        }
        oO0Oo0ooOOoOOOooOoo0O00o properties = model.o00O00o0Oo0O0oOO00oOOOO0().OoooO000OO0OoO0o0o0ooooO();
        LinkedHashSet<String> emotes = new LinkedHashSet<>(properties.o00O00o0Oo0O0oOO00oOOOO0().keySet());
        properties.OO0OOo0o0oOOOOoOO00O0o0O().values().forEach(group -> emotes.addAll(group.keySet()));
        emotes.removeIf(String::isBlank);
        List<String> choices = new ArrayList<>(emotes);
        return choices.isEmpty() ? "" : choices.get(random.nextInt(choices.size()));
    }
}
