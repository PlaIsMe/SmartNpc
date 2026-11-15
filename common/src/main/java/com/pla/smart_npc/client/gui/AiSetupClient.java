package com.pla.smart_npc.client.gui;

import com.google.gson.Gson;
import com.pla.smart_npc.ai.AiModelDownloader;
import com.pla.smart_npc.ai.AiSetupManager;
import com.pla.smart_npc.network.AiSetupRequestPacket;
import com.pla.smart_npc.network.AiSetupStatePacket;
import com.pla.smart_npc.network.SmartNpcNetwork;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;

/** Client-only controller; multiplayer settings always operate on the logical server. */
public final class AiSetupClient {
    private static final Gson JSON = new Gson();
    private static AiSetupManager.Snapshot snapshot;
    private static AiSetupManager local;
    private static AiModelDownloader downloader;
    private static String error = "";
    private AiSetupClient() {}
    public static Screen screen(Screen parent) { snapshot = null; error = ""; return new AiChatConfigScreen(parent); }
    public static AiSetupManager.Snapshot snapshot() { return snapshot; }
    public static String error() { return error; }
    public static boolean hasLocalModels() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getConnection() == null || minecraft.hasSingleplayerServer();
    }
    public static void openModels() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!hasLocalModels()) {
            if (snapshot != null && snapshot.canManage()) minecraft.keyboardHandler.setClipboard(snapshot.storage());
            return;
        }
        // Resolve locally; a remote settings packet must never choose a path to open on the client.
        var models = Platform.getConfigFolder().resolve("smart_npc").resolve("models").toAbsolutePath().normalize();
        error = "";
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                java.nio.file.Files.createDirectories(models);
                Util.getPlatform().openFile(models.toFile());
            } catch (Exception failure) {
                minecraft.execute(() -> error = "Could not open model directory: " + message(failure));
            }
        }, Util.ioPool());
    }
    public static void receive(AiSetupStatePacket packet, Player expectedPlayer) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != expectedPlayer || minecraft.getConnection() == null) return;
        try {
            var state = JSON.fromJson(packet.json(), AiSetupManager.Snapshot.class);
            if (state == null || state.models() == null || state.models().size() > 32) return;
            if (state.models().stream().anyMatch(card -> card == null || card.id() == null || card.family() == null || card.language() == null)) return;
            snapshot = state;
            if (packet.open()) minecraft.setScreen(new AiChatConfigScreen(minecraft.screen));
            else if (minecraft.screen instanceof AiChatConfigScreen screen) screen.update();
            else if (minecraft.screen instanceof AiModelLanguageScreen screen) screen.update();
        } catch (RuntimeException ignored) { error = "Invalid AI settings response."; }
    }
    public static void request(AiSetupManager.Action action, String id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            closeLocal();
            SmartNpcNetwork.CHANNEL.sendToServer(new AiSetupRequestPacket(action, id));
            return;
        }
        if (local == null) {
            var directory = Platform.getConfigFolder().resolve("smart_npc");
            downloader = new AiModelDownloader(directory);
            local = new AiSetupManager(directory, downloader);
        }
        AiSetupManager manager = local;
        var operation = action == AiSetupManager.Action.STATE
                ? java.util.concurrent.CompletableFuture.completedFuture(new AiSetupManager.Change(false, ""))
                : manager.change(action, id);
        operation.whenComplete((change, failure) -> {
            String message = failure == null ? change.message() : message(failure);
            manager.state(true, true, "AI runs when a world is open.", message).whenComplete((state, stateFailure) -> minecraft.execute(() -> {
                if (local != manager || minecraft.getConnection() != null) return;
                if (stateFailure == null) snapshot = state;
                else error = message(stateFailure);
                if (minecraft.screen instanceof AiChatConfigScreen screen) screen.update();
                else if (minecraft.screen instanceof AiModelLanguageScreen screen) screen.update();
            }));
        });
    }
    public static void reset() { snapshot = null; error = ""; closeLocal(); }
    private static void closeLocal() {
        if (local != null) local.close();
        if (downloader != null) downloader.close();
        local = null; downloader = null;
    }
    private static String message(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return String.valueOf(error.getMessage());
    }
}
