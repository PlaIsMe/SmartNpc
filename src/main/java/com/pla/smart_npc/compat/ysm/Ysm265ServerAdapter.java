package com.pla.smart_npc.compat.ysm;

import com.elfmcys.yesstevemodel.ooo000Oo0O0o0oOoO0O00oOO;
import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.oOoOoO0OOO0ooO0OOOOOO000;
import com.elfmcys.yesstevemodel.O0000o0OO0o0oOOoooO00ooO;
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
 * Released YSM 2.6.5 NeoForge 26.1 hotfix API names, verified against Modrinth 1rPJlKDJ.
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
        registryBeforeStartup = ooo000Oo0O0o0oOoO0O00oOO.OO0OoO00ooOOo0o00O000OoO();
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
        var registry = ooo000Oo0O0o0oOoO0O00oOO.OO0OoO00ooOOo0o00O000OoO();
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
                && ooo000Oo0O0o0oOoO0O00oOO.OO0OoO00ooOOo0o00O000OoO() != registryBeforeStartup;
    }

    public static ModelChoice chooseServerModel(RandomSource random) {
        List<ModelChoice> candidates = new ArrayList<>();
        for (Map.Entry<String, O0000o0OO0o0oOOoooO00ooO> entry : ooo000Oo0O0o0oOoO0O00oOO.OO0OoO00ooOOo0o00O000OoO().entrySet()) {
            List<String> textures = entry.getValue().O0oo00O0OoooOOOO00ooO000().O0oo00O0OoooOOOO00ooO000();
            if (!entry.getKey().isBlank() && !textures.isEmpty()) {
                candidates.add(new ModelChoice(entry.getKey(), textures.get(0)));
            }
        }
        // One entry per MODEL, so models with many textures do not receive extra lottery weight.
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    public static boolean hasServerModel(String modelId, String texture) {
        return ooo000Oo0O0o0oOoO0O00oOO.OO0OoO00ooOOo0o00O000OoO(modelId)
                .map(model -> model.O0oo00O0OoooOOOO00ooO000().O0oo00O0OoooOOOO00ooO000().contains(texture))
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
        var defaults = ooo000Oo0O0o0oOoO0O00oOO.oo000Oo000o0Ooooooo00oOo();
        ModelChoice fallback = serverModelChoice(defaults.getLeft(), defaults.getRight());
        return fallback != null ? fallback : serverModelChoice("default", "default");
    }

    private static ModelChoice serverModelChoice(String modelId, String texture) {
        O0000o0OO0o0oOOoooO00ooO model = ooo000Oo0O0o0oOoO0O00oOO.OO0OoO00ooOOo0o00O000OoO(modelId).orElse(null);
        if (model == null) {
            return null;
        }
        List<String> textures = model.O0oo00O0OoooOOOO00ooO000().O0oo00O0OoooOOOO00ooO000();
        if (textures.isEmpty()) {
            return null;
        }
        if (texture == null || !textures.contains(texture)) {
            String modelDefault = model.Oo0o00OoooOoo00o00O00O0o().O0oo00O0OoooOOOO00ooO000().oo000Oo000o0Ooooooo00oOo();
            texture = textures.contains(modelDefault) ? modelDefault : textures.get(0);
        }
        return new ModelChoice(modelId, texture);
    }

    public static String chooseServerEmote(String modelId, RandomSource random) {
        O0000o0OO0o0oOOoooO00ooO model = ooo000Oo0O0o0oOoO0O00oOO.OO0OoO00ooOOo0o00O000OoO(modelId).orElse(null);
        if (model == null) {
            return "";
        }
        oOoOoO0OOO0ooO0OOOOOO000 properties = model.Oo0o00OoooOoo00o00O00O0o().O0oo00O0OoooOOOO00ooO000();
        LinkedHashSet<String> emotes = new LinkedHashSet<>(properties.Oo0o00OoooOoo00o00O00O0o().keySet());
        properties.O0OoOoO0oo0O00O0ooo0o0OO().values().forEach(group -> emotes.addAll(group.keySet()));
        emotes.removeIf(String::isBlank);
        List<String> choices = new ArrayList<>(emotes);
        return choices.isEmpty() ? "" : choices.get(random.nextInt(choices.size()));
    }
}
