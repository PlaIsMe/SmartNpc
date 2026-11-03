package com.pla.smart_npc.compat.ysm.fabric;
import com.pla.smart_npc.compat.ysm.YsmModelFileIndex;
import com.pla.smart_npc.compat.ysm.YsmBaseModels;

import com.elfmcys.yesstevemodel.o0oooOoOOoOo0ooOOOOoOoOO;
import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.oO0o0oOOoooOo00000OO00oO;
import com.elfmcys.yesstevemodel.O000O000OO0o0o0ooO0ooo0o;
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
 * Released YSM 2.6.5 Fabric 1.21.1 API names, verified against Modrinth eGOUtHWJ.
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
        registryBeforeStartup = o0oooOoOOoOo0ooOOOOoOoOO.OO0ooo0OooOoO0OoO0ooOO0o();
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
        var registry = o0oooOoOOoOo0ooOOOOoOoOO.OO0ooo0OooOoO0OoO0ooOO0o();
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
                && o0oooOoOOoOo0ooOOOOoOoOO.OO0ooo0OooOoO0OoO0ooOO0o() != registryBeforeStartup;
    }

    public static ModelChoice chooseServerModel(RandomSource random) {
        List<ModelChoice> candidates = new ArrayList<>();
        for (Map.Entry<String, O000O000OO0o0o0ooO0ooo0o> entry : o0oooOoOOoOo0ooOOOOoOoOO.OO0ooo0OooOoO0OoO0ooOO0o().entrySet()) {
            List<String> textures = entry.getValue().Ooo0OOOOO00O0Oo0oo0OOOo0().Ooo0OOOOO00O0Oo0oo0OOOo0();
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
        return o0oooOoOOoOo0ooOOOOoOoOO.OO0ooo0OooOoO0OoO0ooOO0o(modelId)
                .map(model -> model.Ooo0OOOOO00O0Oo0oo0OOOo0().Ooo0OOOOO00O0Oo0oo0OOOo0().contains(texture))
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
        var defaults = o0oooOoOOoOo0ooOOOOoOoOO.ooOoOOO00Oo0o0oO0Ooo0oO0();
        ModelChoice fallback = serverModelChoice(defaults.getLeft(), defaults.getRight());
        return fallback != null && !YsmBaseModels.isBaseModel(fallback.modelId()) ? fallback : serverModelChoice("default", "default");
    }

    private static ModelChoice serverModelChoice(String modelId, String texture) {
        O000O000OO0o0o0ooO0ooo0o model = o0oooOoOOoOo0ooOOOOoOoOO.OO0ooo0OooOoO0OoO0ooOO0o(modelId).orElse(null);
        if (model == null) {
            return null;
        }
        List<String> textures = model.Ooo0OOOOO00O0Oo0oo0OOOo0().Ooo0OOOOO00O0Oo0oo0OOOo0();
        if (textures.isEmpty()) {
            return null;
        }
        if (texture == null || !textures.contains(texture)) {
            String modelDefault = model.O0O0Ooo0OO0o00O0OO0ooOOo().Ooo0OOOOO00O0Oo0oo0OOOo0().ooOoOOO00Oo0o0oO0Ooo0oO0();
            texture = textures.contains(modelDefault) ? modelDefault : textures.get(0);
        }
        return new ModelChoice(modelId, texture);
    }

    public static String chooseServerEmote(String modelId, RandomSource random) {
        O000O000OO0o0o0ooO0ooo0o model = o0oooOoOOoOo0ooOOOOoOoOO.OO0ooo0OooOoO0OoO0ooOO0o(modelId).orElse(null);
        if (model == null) {
            return "";
        }
        oO0o0oOOoooOo00000OO00oO properties = model.O0O0Ooo0OO0o00O0OO0ooOOo().Ooo0OOOOO00O0Oo0oo0OOOo0();
        LinkedHashSet<String> emotes = new LinkedHashSet<>(properties.O0O0Ooo0OO0o00O0OO0ooOOo().keySet());
        properties.oOoO0OOO000OooO0OoooOOO0().values().forEach(group -> emotes.addAll(group.keySet()));
        emotes.removeIf(String::isBlank);
        List<String> choices = new ArrayList<>(emotes);
        return choices.isEmpty() ? "" : choices.get(random.nextInt(choices.size()));
    }
}
