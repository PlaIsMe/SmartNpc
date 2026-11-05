package com.pla.smart_npc.compat;

import com.pla.smart_npc.platform.event.NpcEvents.*;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.compat.ysm.Ysm265ServerAdapter;
import com.pla.smart_npc.compat.ysm.YsmBaseModels;
import com.pla.smart_npc.config.SmartNpcCompatConfig;
import com.pla.smart_npc.config.SmartNpcNamesConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.server.level.ServerLevel;
import dev.architectury.platform.Platform;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;

/** The generally loaded boundary contains no YSM types in its fields or method signatures. */
public final class YesSteveModelCompat {
    public static final String MOD_ID = "yes_steve_model";
    private static boolean failed;
    private static Boolean supportedVersion;

    private YesSteveModelCompat() {
    }
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (isSupported()) {
            try {
                Ysm265ServerAdapter.beginServer(event.getServer());
            } catch (LinkageError | RuntimeException error) {
                disable(error);
            }
        }
    }
    public static void onServerStopped(ServerStoppedEvent event) {
        if (Boolean.TRUE.equals(supportedVersion)) {
            try {
                Ysm265ServerAdapter.endServer();
            } catch (LinkageError | RuntimeException error) {
                disable(error);
            }
        }
    }

    public static boolean isSupported() {
        if (failed) {
            return false;
        }
        if (supportedVersion == null) {
            supportedVersion = Platform.getOptionalMod(MOD_ID).map(mod -> mod.getVersion().equals("2.6.5-" + (Platform.isFabric() ? "fabric" : "forge") + "+mc1.20.1")).orElse(false);
            if (!supportedVersion && Platform.isModLoaded(MOD_ID)) {
                SmartNpc.LOGGER.warn("Smart NPC YSM compatibility requires the pinned YSM 2.6.5 API for the current loader and Minecraft 1.20.1; using normal NPC skins.");
            }
        }
        if (!supportedVersion) {
            return false;
        }
        try {
            return Ysm265ServerAdapter.isAvailable();
        } catch (LinkageError | RuntimeException error) {
            disable(error);
            return false;
        }
    }

    public static boolean isEnabled() {
        return SmartNpcCompatConfig.YES_STEVE_MODEL_ENABLED.get() && isSupported();
    }

    public static void initializeSpawn(PlayerNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel) || (npc.isYsmChoiceMade() && !npc.getYsmModelId().isEmpty())) {
            return;
        }
        if (!isEnabled()) {
            return;
        }
        try {
            // YSM publishes the loaded server registry asynchronously. Wait for publication,
            // including an explicitly empty result, before consuming the sole spawn lottery.
            if (!Ysm265ServerAdapter.isServerReady(npc.getServer())) {
                return;
            }
            String preferredModel = SmartNpcNamesConfig.getYsmModelFileName(npc.getUsername().getSkinName()).orElse(null);
            npc.setYsmRandomModel(preferredModel == null);
            npc.setYsmChoiceMade();
            ModelChoice choice = wantsCustomModel(npc) ? chooseCustomModel(npc, preferredModel) : null;
            applyModel(npc, choice != null ? choice : chooseBaseModel(npc));
        } catch (LinkageError | RuntimeException error) {
            npc.setYsmChoiceMade();
            disable(error);
        }
    }

    public static void refreshAvailability(PlayerNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel)) {
            return;
        }
        boolean active = false;
        if (!npc.getYsmModelId().isEmpty() && isEnabled()) {
            try {
                if (Ysm265ServerAdapter.isServerReady(npc.getServer())) {
                    // Older saves did not record random/configured origin. Infer it once
                    // from the current roster, then persist it independently of config edits.
                    if (npc.getYsmRandomModel() == null) {
                        npc.setYsmRandomModel(SmartNpcNamesConfig.getYsmModelFileName(npc.getUsername().getSkinName()).isEmpty());
                    }
                    if (!wantsCustomModel(npc)) {
                        active = applyModel(npc, chooseBaseModel(npc));
                    } else if (YsmBaseModels.isBaseModel(npc.getYsmModelId())) {
                        // Retry a custom choice after server model reloads. Base-only
                        // choices above also revisit asynchronously resolved skin shape.
                        String preferredModel = Boolean.FALSE.equals(npc.getYsmRandomModel())
                                ? SmartNpcNamesConfig.getYsmModelFileName(npc.getUsername().getSkinName()).orElse(null) : null;
                        ModelChoice custom = chooseCustomModel(npc, preferredModel);
                        if (custom != null) {
                            npc.setYsmRandomModel(preferredModel == null);
                        }
                        active = applyModel(npc, custom != null ? custom : chooseBaseModel(npc));
                    } else {
                        active = Ysm265ServerAdapter.hasServerModel(npc.getYsmModelId(), npc.getYsmModelTexture());
                        if (!active) {
                            ModelChoice replacement = Boolean.TRUE.equals(npc.getYsmRandomModel())
                                    ? Ysm265ServerAdapter.recoverServerModel(npc.getYsmModelId(), npc.getYsmModelTexture(), npc.getRandom()) : null;
                            active = applyModel(npc, replacement != null ? replacement : chooseBaseModel(npc));
                        }
                    }
                }
            } catch (LinkageError | RuntimeException error) {
                disable(error);
            }
        }
        npc.setYsmActive(active);
    }

    private static boolean wantsCustomModel(PlayerNpcEntity npc) {
        double chance = SmartNpcCompatConfig.YES_STEVE_MODEL_CHANCE.get();
        Boolean custom = npc.getYsmCustomModel();
        if (chance <= 0.0D) {
            custom = false;
        } else if (chance >= 1.0D) {
            custom = true;
        } else if (custom == null) {
            // Infer existing appearances; migrate legacy empty choices with one roll.
            custom = npc.getYsmModelId().isEmpty() ? npc.getRandom().nextDouble() < chance : !YsmBaseModels.isBaseModel(npc.getYsmModelId());
        }
        npc.setYsmCustomModel(custom);
        return custom;
    }

    private static ModelChoice chooseCustomModel(PlayerNpcEntity npc, String preferredModel) {
        if (preferredModel == null) {
            return Ysm265ServerAdapter.chooseServerModel(npc.getRandom());
        }
        ModelChoice choice = Ysm265ServerAdapter.chooseNamedServerModelOrDefault(preferredModel);
        return choice != null && !YsmBaseModels.isBaseModel(choice.modelId()) ? choice : Ysm265ServerAdapter.chooseServerModel(npc.getRandom());
    }

    private static ModelChoice chooseBaseModel(PlayerNpcEntity npc) {
        GameProfile profile = npc.getProfile();
        boolean slim = false;
        if (profile != null && profile.isComplete()) {
            // Same 18-skin UUID fallback as 1.20.1 DefaultPlayerSkin, without loading
            // that client-only class on the server. Authlib reads existing properties;
            // this does not perform a network profile lookup.
            slim = Math.floorMod(profile.getId().hashCode(), 18) < 9;
            try {
                MinecraftProfileTexture skin = npc.getServer().getSessionService().getTextures(profile, false).get(MinecraftProfileTexture.Type.SKIN);
                if (skin != null) {
                    slim = "slim".equals(skin.getMetadata("model"));
                }
            } catch (RuntimeException ignored) {
                // Malformed/unavailable skin properties retain the UUID fallback.
            }
        }
        return Ysm265ServerAdapter.chooseBaseServerModel(slim);
    }

    private static boolean applyModel(PlayerNpcEntity npc, ModelChoice choice) {
        if (choice == null) {
            npc.setYsmActive(false);
            return false;
        }
        if (!choice.modelId().equals(npc.getYsmModelId()) || !choice.texture().equals(npc.getYsmModelTexture())) {
            npc.stopYsmEmote(npc.getYsmEmoteSequence());
            npc.setYsmModel(choice.modelId(), choice.texture());
        }
        npc.setYsmActive(true);
        return true;
    }

    /** Returns an ownership token; only that owner may cancel the resulting animation. */
    public static int playRandomEmote(PlayerNpcEntity npc, int ticks) {
        if (!(npc.level() instanceof ServerLevel) || !npc.isYsmActive() || !isEnabled()) {
            return 0;
        }
        try {
            String emote = Ysm265ServerAdapter.chooseServerEmote(npc.getYsmModelId(), npc.getRandom());
            return emote.isEmpty() ? 0 : npc.startYsmEmote(emote, ticks);
        } catch (LinkageError | RuntimeException error) {
            disable(error);
            return 0;
        }
    }

    public static void disable(Throwable error) {
        if (!failed) {
            failed = true;
            SmartNpc.LOGGER.warn("Smart NPC disabled its YSM adapter after an API failure; normal NPC rendering remains available.", error);
        }
    }

    public record ModelChoice(String modelId, String texture) {
    }
}
