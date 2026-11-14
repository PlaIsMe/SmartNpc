package com.pla.smart_npc.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Filesystem-only settings backend shared by the local config screen and server settings packets. */
public final class AiSetupManager implements AutoCloseable {
    public enum Action { STATE, DOWNLOAD, DELETE, USE, LEGACY, CANCEL }
    public record Card(String id, String name, String description, String ramEstimate, long bytes, String file, String license, String source, boolean installed, String language, String family) {
        public boolean unfiltered() { return AiSpeechStyle.unfiltered(id); }
    }
    public record Snapshot(boolean canManage, boolean enabled, boolean chatAllowed, boolean busy, boolean reminder, String selected, String storage, String catalog, AiModelDownloader.Progress progress, String runtimeStatus, String runtimeDownload, String message, List<Card> models) {}
    public record Change(boolean reload, String message) {}
    private final Path directory;
    private final AiModelDownloader downloader;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
        Thread thread = new Thread(task, "SmartNpc-AI-settings"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private volatile boolean installing;

    public AiSetupManager(Path directory, AiModelDownloader downloader) { this.directory = directory; this.downloader = downloader; }
    public CompletableFuture<Snapshot> state(boolean canManage, boolean chatAllowed, String runtimeStatus, String message) {
        return submit(() -> {
            try {
                AiConfig config = AiConfig.load(directory);
                AiModelManifest catalog = AiModelManifest.load(directory);
                var published = AiModelManifest.defaults().models().stream().map(AiModelManifest.Model::id).collect(java.util.stream.Collectors.toSet());
                List<Card> cards = new ArrayList<>();
                for (AiModelManifest.Model model : catalog.models()) {
                    Path file = AiModelManifest.modelPath(directory, model.file());
                    boolean installed = Files.isRegularFile(file) && Files.size(file) == model.downloadBytes();
                    // Preserve installed historical variants while awaiting the new release.
                    if (!installed && AiModelManifest.isBuiltInRelease(model) && AiSpeechStyle.unfiltered(model.id())
                            && AiModelTiers.all().stream().anyMatch(tier -> tier.family().equals(model.familyId())) && !published.contains(model.id())) continue;
                    cards.add(new Card(model.id(), model.name(), model.description() == null ? "Custom model" : model.description(),
                            model.ramEstimate() == null || model.ramEstimate().isBlank() ? "Not benchmarked; depends on context/runtime" : model.ramEstimate(),
                            model.downloadBytes(), model.file(), model.license(), model.url().substring(0, Math.min(512, model.url().length())), installed, model.locale(), model.familyId()));
                }
                String runtimeDownload;
                if (config.externalRuntime) runtimeDownload = "Externally managed runtime; no native download.";
                else {
                    try {
                        EmbeddedLlamaBridge.requirePlatform();
                        runtimeDownload = "Bundled " + EmbeddedLlamaBridge.VERSION + "; runs inside Minecraft (native RAM, outside Java heap).";
                    } catch (IllegalArgumentException error) { runtimeDownload = error.getMessage(); }
                }
                return new Snapshot(canManage, config.enabled, chatAllowed, installing || downloader.isDownloading(), config.showSetupReminder, config.modelId,
                        canManage ? directory.resolve("models").toAbsolutePath().normalize().toString() : "Server: config/smart_npc/models/",
                        canManage ? directory.resolve("models.json").toAbsolutePath().normalize().toString() : "Server: config/smart_npc/models.json",
                        downloader.transferProgress(), runtimeStatus, runtimeDownload, message, List.copyOf(cards));
            } catch (Exception error) { throw new IllegalArgumentException("AI settings: " + error.getMessage(), error); }
        });
    }
    public CompletableFuture<Change> change(Action action, String id) {
        if (action == Action.CANCEL) { downloader.cancel(); return CompletableFuture.completedFuture(new Change(false, "smart_npc.ai.install_cancelled")); }
        return submit(() -> {
            try {
                AiConfig config = AiConfig.load(directory);
                if (installing || downloader.isDownloading()) throw new IllegalStateException("An installation is in progress. Cancel it before changing models.");
                if (action == Action.LEGACY) {
                    config.enabled = false; config.save(directory);
                    return new Change(true, "smart_npc.ai.legacy_active");
                }
                AiModelManifest catalog = AiModelManifest.load(directory);
                AiModelManifest.Model model = catalog.find(id);
                if (action != Action.DELETE) model.requireSupportedLanguage();
                Path target = AiModelManifest.modelPath(directory, model.file());
                if (action == Action.DOWNLOAD) {
                    var nativeRuntime = catalog.runtimeToInstall(config, directory);
                    installing = true;
                    return new Download(model, nativeRuntime);
                }
                if (action == Action.DELETE) {
                    if (config.enabled && (config.modelId.equals(model.id()) || config.modelFile.equals(model.file()))) throw new IllegalStateException("Select another model or Legacy chat before deleting the active model.");
                    Files.deleteIfExists(target);
                    return new Change(false, "smart_npc.ai.deleted");
                }
                if (action == Action.USE) {
                    if (!Files.isRegularFile(target) || Files.size(target) != model.downloadBytes()) throw new IOException("Download this model first.");
                    config.modelId = model.id(); config.modelFile = model.file(); config.modelLanguage = model.locale(); config.enabled = true; config.save(directory);
                    return new Change(true, "smart_npc.ai.model_selected");
                }
                throw new IllegalArgumentException("Unsupported settings action");
            } catch (Exception error) { throw new IllegalArgumentException(error.getMessage(), error); }
        }).thenCompose(result -> {
            if (result instanceof Change change) return CompletableFuture.completedFuture(change);
            Download download = (Download) result;
            return downloader.install(download.model(), download.runtime(), true).thenCompose(installation -> submit(() -> {
                try {
                    finishInstallation(directory, installation);
                    AiConfig selected = AiConfig.load(directory);
                    selected.modelId = download.model().id(); selected.modelFile = download.model().file(); selected.modelLanguage = download.model().locale(); selected.enabled = true;
                    selected.save(directory);
                    return new Change(true, "smart_npc.ai.install_complete");
                } catch (IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
            })).whenComplete((change, error) -> installing = false);
        });
    }
    /** Library files stay loaded for the JVM lifetime; model allocations are freed by the actor. */
    public CompletableFuture<Void> cleanupStoppedRuntime() {
        return CompletableFuture.completedFuture(null);
    }
    /** Filesystem work only; call on the install/settings worker after all downloads verify. */
    static String finishInstallation(Path directory, AiModelDownloader.Installation installation) throws IOException {
        AiConfig config = AiConfig.load(directory);
        config.executable = "";
        config.save(directory);
        return "";
    }
    private record Download(AiModelManifest.Model model, AiModelManifest.Runtime runtime) {}
    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("AI settings closed"));
        try {
            io.execute(() -> {
                if (closed) { result.cancel(false); return; }
                try { result.complete(task.get()); } catch (Exception error) { result.completeExceptionally(error); }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) { result.completeExceptionally(error); }
        return result;
    }
    @Override public void close() { closed = true; io.shutdown(); }
}
