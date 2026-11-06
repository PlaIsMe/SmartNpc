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
        // YSM's native adapter targets 26.1; this branch deliberately skips YSM.
        return false;
    }

    public static boolean isEnabled() {
        return SmartNpcCompatConfig.YES_STEVE_MODEL_ENABLED.get() && isSupported();
    }

    public static void initializeSpawn(PlayerNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level) || !npc.hasUsername() || (npc.isYsmChoiceMade() && !npc.getYsmModelId().isEmpty()) || !isEnabled()) return;
        try {
            if (!Ysm265ServerAdapter.isServerReady(level.getServer())) return;
            String preferred = SmartNpcNamesConfig.getYsmModelFileName(npc.getUsername().getSkinName()).orElse(null);
            npc.setYsmRandomModel(preferred == null);
            npc.setYsmChoiceMade();
            ModelChoice custom = wantsCustomModel(npc) ? chooseCustomModel(npc, preferred) : null;
            applyModel(npc, custom != null ? custom : chooseBaseModel(npc));
        } catch (LinkageError | RuntimeException error) {
            npc.setYsmChoiceMade();
            disable(error);
        }
    }

    public static void refreshAvailability(PlayerNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        boolean active = false;
        if (!npc.getYsmModelId().isEmpty() && isEnabled()) {
            try {
                if (Ysm265ServerAdapter.isServerReady(level.getServer())) {
                    if (npc.getYsmRandomModel() == null) npc.setYsmRandomModel(SmartNpcNamesConfig.getYsmModelFileName(npc.getUsername().getSkinName()).isEmpty());
                    if (!wantsCustomModel(npc)) {
                        // Profile resolution can finish after spawn: revisit the base skin shape.
                        active = applyModel(npc, chooseBaseModel(npc));
                    } else if (YsmBaseModels.isBaseModel(npc.getYsmModelId())) {
                        String preferred = Boolean.FALSE.equals(npc.getYsmRandomModel()) ? SmartNpcNamesConfig.getYsmModelFileName(npc.getUsername().getSkinName()).orElse(null) : null;
                        ModelChoice custom = chooseCustomModel(npc, preferred);
                        if (custom != null) npc.setYsmRandomModel(preferred == null);
                        active = applyModel(npc, custom != null ? custom : chooseBaseModel(npc));
                    } else {
                        active = Ysm265ServerAdapter.hasServerModel(npc.getYsmModelId(), npc.getYsmModelTexture());
                        if (!active) {
                            ModelChoice replacement = Boolean.TRUE.equals(npc.getYsmRandomModel()) ? Ysm265ServerAdapter.recoverServerModel(npc.getYsmModelId(), npc.getYsmModelTexture(), npc.getRandom()) : null;
                            active = applyModel(npc, replacement != null ? replacement : chooseBaseModel(npc));
                        }
                    }
                }
            } catch (LinkageError | RuntimeException error) { disable(error); }
        }
        npc.setYsmActive(active);
    }

    private static boolean wantsCustomModel(PlayerNpcEntity npc) {
        double chance = SmartNpcCompatConfig.YES_STEVE_MODEL_CHANCE.get();
        Boolean custom = npc.getYsmCustomModel();
        if (chance <= 0.0D) custom = false;
        else if (chance >= 1.0D) custom = true;
        else if (custom == null) custom = npc.getYsmModelId().isEmpty() ? npc.getRandom().nextDouble() < chance : !YsmBaseModels.isBaseModel(npc.getYsmModelId());
        npc.setYsmCustomModel(custom);
        return custom;
    }

    private static ModelChoice chooseCustomModel(PlayerNpcEntity npc, String preferred) {
        ModelChoice choice = preferred == null ? Ysm265ServerAdapter.chooseServerModel(npc.getRandom()) : Ysm265ServerAdapter.chooseNamedServerModelOrDefault(preferred);
        return choice != null && !YsmBaseModels.isBaseModel(choice.modelId()) ? choice : Ysm265ServerAdapter.chooseServerModel(npc.getRandom());
    }

    private static ModelChoice chooseBaseModel(PlayerNpcEntity npc) {
        var profile = npc.getProfile();
        boolean slim = false;
        if (profile != null) {
            slim = Math.floorMod(profile.id().hashCode(), 18) < 9;
            try {
                // Read already resolved skin properties; never initiate another profile lookup.
                var skin = npc.level().getServer().services().sessionService().getTextures(profile).skin();
                if (skin != null) slim = "slim".equals(skin.getMetadata("model"));
            } catch (RuntimeException ignored) { /* Keep vanilla's UUID fallback. */ }
        }
        return Ysm265ServerAdapter.chooseBaseServerModel(slim);
    }

    private static boolean applyModel(PlayerNpcEntity npc, ModelChoice choice) {
        if (choice == null) { npc.setYsmActive(false); return false; }
        if (!choice.modelId().equals(npc.getYsmModelId()) || !choice.texture().equals(npc.getYsmModelTexture())) {
            npc.stopYsmEmote(npc.getYsmEmoteSequence());
            npc.setYsmModel(choice.modelId(), choice.texture());
        }
        npc.setYsmActive(true);
        return true;
    }

    /** Returns an ownership token; only that owner may cancel the resulting animation. */
    public static int playRandomEmote(PlayerNpcEntity npc, int ticks) {
        if (!(npc.level() instanceof ServerLevel serverLevel) || !npc.isYsmActive() || !isEnabled()) {
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
