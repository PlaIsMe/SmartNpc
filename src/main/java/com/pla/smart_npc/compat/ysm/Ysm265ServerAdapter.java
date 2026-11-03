package com.pla.smart_npc.compat.ysm;

import com.elfmcys.yesstevemodel.O0Ooo000O0ooO00Oooo00oOO;
import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.O00000000OO0O0ooO0000oo0;
import com.elfmcys.yesstevemodel.oo0OoOo00o0oOOo000OoOOOO;
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
 * Released YSM 2.6.5 NeoForge 1.21.1 API names, verified against Modrinth HZWaR0LY.
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
        registryBeforeStartup = O0Ooo000O0ooO00Oooo00oOO.oOo0OO0O0o000OO0O000oo0o();
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
        var registry = O0Ooo000O0ooO00Oooo00oOO.oOo0OO0O0o000OO0O000oo0o();
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
                && O0Ooo000O0ooO00Oooo00oOO.oOo0OO0O0o000OO0O000oo0o() != registryBeforeStartup;
    }

    public static ModelChoice chooseServerModel(RandomSource random) {
        List<ModelChoice> candidates = new ArrayList<>();
        for (Map.Entry<String, oo0OoOo00o0oOOo000OoOOOO> entry : O0Ooo000O0ooO00Oooo00oOO.oOo0OO0O0o000OO0O000oo0o().entrySet()) {
            List<String> textures = entry.getValue().oOoo00O0o0oO0o0oO00OO0O0().oOoo00O0o0oO0o0oO00OO0O0();
            if (!entry.getKey().isBlank() && !textures.isEmpty()) {
                candidates.add(new ModelChoice(entry.getKey(), textures.get(0)));
            }
        }
        // One entry per MODEL, so models with many textures do not receive extra lottery weight.
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    public static boolean hasServerModel(String modelId, String texture) {
        return O0Ooo000O0ooO00Oooo00oOO.oOo0OO0O0o000OO0O000oo0o(modelId)
                .map(model -> model.oOoo00O0o0oO0o0oO00OO0O0().oOoo00O0o0oO0o0oO00OO0O0().contains(texture))
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
        var defaults = O0Ooo000O0ooO00Oooo00oOO.OO000o0ooOooooOOOOO0Ooo0();
        ModelChoice fallback = serverModelChoice(defaults.getLeft(), defaults.getRight());
        return fallback != null ? fallback : serverModelChoice("default", "default");
    }

    private static ModelChoice serverModelChoice(String modelId, String texture) {
        oo0OoOo00o0oOOo000OoOOOO model = O0Ooo000O0ooO00Oooo00oOO.oOo0OO0O0o000OO0O000oo0o(modelId).orElse(null);
        if (model == null) {
            return null;
        }
        List<String> textures = model.oOoo00O0o0oO0o0oO00OO0O0().oOoo00O0o0oO0o0oO00OO0O0();
        if (textures.isEmpty()) {
            return null;
        }
        if (texture == null || !textures.contains(texture)) {
            String modelDefault = model.Oo0O0OoOo0O0oOoo0000O0oO().oOoo00O0o0oO0o0oO00OO0O0().OO000o0ooOooooOOOOO0Ooo0();
            texture = textures.contains(modelDefault) ? modelDefault : textures.get(0);
        }
        return new ModelChoice(modelId, texture);
    }

    public static String chooseServerEmote(String modelId, RandomSource random) {
        oo0OoOo00o0oOOo000OoOOOO model = O0Ooo000O0ooO00Oooo00oOO.oOo0OO0O0o000OO0O000oo0o(modelId).orElse(null);
        if (model == null) {
            return "";
        }
        O00000000OO0O0ooO0000oo0 properties = model.Oo0O0OoOo0O0oOoo0000O0oO().oOoo00O0o0oO0o0oO00OO0O0();
        LinkedHashSet<String> emotes = new LinkedHashSet<>(properties.Oo0O0OoOo0O0oOoo0000O0oO().keySet());
        properties.OoooO0OO0000O00oo0Oo00OO().values().forEach(group -> emotes.addAll(group.keySet()));
        emotes.removeIf(String::isBlank);
        List<String> choices = new ArrayList<>(emotes);
        return choices.isEmpty() ? "" : choices.get(random.nextInt(choices.size()));
    }
}
