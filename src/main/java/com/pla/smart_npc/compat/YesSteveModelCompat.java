package com.pla.smart_npc.compat;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.compat.ysm.Ysm265ServerAdapter;
import com.pla.smart_npc.config.SmartNpcCompatConfig;
import com.pla.smart_npc.config.SmartNpcNamesConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;

/** The generally loaded boundary contains no YSM types in its fields or method signatures. */
@EventBusSubscriber(modid = SmartNpc.MODID)
public final class YesSteveModelCompat {
    public static final String MOD_ID = "yes_steve_model";
    private static boolean failed;
    private static Boolean supportedVersion;

    private YesSteveModelCompat() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (isSupported()) {
            try {
                Ysm265ServerAdapter.beginServer(event.getServer());
            } catch (LinkageError | RuntimeException error) {
                disable(error);
            }
        }
    }

    @SubscribeEvent
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
            supportedVersion = ModList.get().getModContainerById(MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString().equals("2.6.5-neoforge+mc1.21.1"))
                    .orElse(false);
            if (!supportedVersion && ModList.get().isLoaded(MOD_ID)) {
                SmartNpc.LOGGER.warn("Smart NPC YSM compatibility requires the pinned YSM 2.6.5 NeoForge 1.21.1 API; using normal NPC skins.");
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

    public static void initializeSpawn(PlayerNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel) || npc.isYsmChoiceMade()) {
            return;
        }
        if (!isSupported() || SmartNpcCompatConfig.YES_STEVE_MODEL_CHANCE.get() <= 0.0D) {
            npc.setYsmChoiceMade();
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
            if (npc.getRandom().nextDouble() >= SmartNpcCompatConfig.YES_STEVE_MODEL_CHANCE.get()) {
                return;
            }
            ModelChoice choice = preferredModel == null ? Ysm265ServerAdapter.chooseServerModel(npc.getRandom())
                    : Ysm265ServerAdapter.chooseNamedServerModelOrDefault(preferredModel);
            if (choice != null) {
                npc.setYsmModel(choice.modelId(), choice.texture());
                npc.setYsmActive(true);
            }
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
        if (!npc.getYsmModelId().isEmpty() && SmartNpcCompatConfig.YES_STEVE_MODEL_CHANCE.get() > 0.0D && isSupported()) {
            try {
                if (Ysm265ServerAdapter.isServerReady(npc.getServer())) {
                    // Older saves did not record random/configured origin. Infer it once
                    // from the current roster, then persist it independently of config edits.
                    if (npc.getYsmRandomModel() == null) {
                        npc.setYsmRandomModel(SmartNpcNamesConfig.getYsmModelFileName(npc.getUsername().getSkinName()).isEmpty());
                    }
                    active = Ysm265ServerAdapter.hasServerModel(npc.getYsmModelId(), npc.getYsmModelTexture());
                    if (!active && Boolean.TRUE.equals(npc.getYsmRandomModel())) {
                        ModelChoice replacement = Ysm265ServerAdapter.recoverServerModel(npc.getYsmModelId(), npc.getYsmModelTexture(), npc.getRandom());
                        if (replacement != null) {
                            npc.stopYsmEmote(npc.getYsmEmoteSequence());
                            npc.setYsmModel(replacement.modelId(), replacement.texture());
                            active = true;
                        }
                    }
                }
            } catch (LinkageError | RuntimeException error) {
                disable(error);
            }
        }
        npc.setYsmActive(active);
    }

    /** Returns an ownership token; only that owner may cancel the resulting animation. */
    public static int playRandomEmote(PlayerNpcEntity npc, int ticks) {
        if (!(npc.level() instanceof ServerLevel) || !npc.isYsmActive() || !isSupported()) {
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
