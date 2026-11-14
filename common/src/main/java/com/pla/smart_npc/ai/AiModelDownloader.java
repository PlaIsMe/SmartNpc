package com.pla.smart_npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** One explicit install worker verifies GGUF downloads; the native library is bundled in the mod. */
public final class AiModelDownloader implements AutoCloseable {
    private static final Set<String> HOSTS = Set.of("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "huggingface.co", "cdn-lfs.huggingface.co", "cdn-lfs.hf.co", "cdn-lfs-us-1.hf.co", "cas-bridge.xethub.hf.co", "us.aws.cdn.hf.co", "eu.aws.cdn.hf.co");
    private final Path directory;
    private Thread worker;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private volatile long received;
    private volatile long expected;
    private volatile String phase = "idle";
    private long transferStarted;
    public record Progress(String phase, long received, long total, long secondsRemaining) {}
    private CompletableFuture<Void> termination = CompletableFuture.completedFuture(null);
    public record Installation(Path model, Path executable) {}

    public AiModelDownloader(Path directory) { this.directory = directory; }

    public synchronized CompletableFuture<Path> download(AiModelManifest.Model model, boolean confirmed) {
        return install(model, null, confirmed).thenApply(Installation::model);
    }

    public synchronized CompletableFuture<Installation> install(AiModelManifest.Model model, AiModelManifest.Runtime runtime, boolean confirmed) {
        if (!confirmed) return CompletableFuture.failedFuture(new IllegalArgumentException("Model license, size and URL must be explicitly confirmed"));
        if (worker != null && worker.isAlive()) return CompletableFuture.failedFuture(new IllegalStateException("A model download is already active"));
        model.validate();
        if (runtime != null) return CompletableFuture.failedFuture(new IllegalArgumentException("Native runtime downloads are no longer used; the library ships inside Smart NPC"));
        cancelled = false;
        received = 0;
        expected = model.downloadBytes();
        CompletableFuture<Installation> result = new CompletableFuture<>();
        termination = new CompletableFuture<>();
        worker = new Thread(() -> {
            try {
                checkCancelled();
                beginPhase("model_verify", model.downloadBytes());
                Path target = AiModelManifest.modelPath(directory, model.file());
                if (Files.exists(target)) verify(target, model.downloadBytes(), model.sha256());
                else target = downloadVerified(model);
                beginPhase("complete", 0);
                result.complete(new Installation(target, null));
            }
            catch (Exception exception) { result.completeExceptionally(exception); }
            finally {
                HttpURLConnection current = connection;
                if (current != null) current.disconnect();
                connection = null;
                termination.complete(null);
            }
        }, "SmartNpc-AI-model-download");
        worker.setDaemon(true);
        worker.start();
        return result;
    }

    public String progress() { return phase + ": " + received + "/" + expected + " bytes"; }
    synchronized void beginPhase(String name, long total) {
        phase = name; received = 0; expected = total; transferStarted = System.nanoTime();
    }
    private synchronized void advanced(int bytes) { received += bytes; }
    public synchronized Progress transferProgress() {
        long elapsed = System.nanoTime() - transferStarted;
        long remaining = phase.endsWith("_download") && received > 0 && elapsed >= 2_000_000_000L
                ? (long) Math.ceil((expected - received) / (double) received * elapsed / 1_000_000_000.0) : -1;
        return new Progress(phase, received, expected, remaining);
    }
    public synchronized CompletableFuture<Void> termination() { return termination; }
    public synchronized boolean isDownloading() { return worker != null && worker.isAlive(); }
    public synchronized void cancel() {
        cancelled = true;
        HttpURLConnection current = connection;
        if (current != null) current.disconnect();
        if (worker != null) worker.interrupt();
    }
    @Override public void close() { cancel(); }

    private Path downloadVerified(AiModelManifest.Model model) throws Exception {
        Path target = AiModelManifest.modelPath(directory, model.file());
        if (Files.exists(target)) throw new IOException("Model file already exists; unload and delete it explicitly before installing a replacement");
        Path partial = Files.createTempFile(target.getParent(), "download-", ".part");
        try {
            fetch(model.url(), model.downloadBytes(), model.sha256(), partial);
            try (InputStream source = Files.newInputStream(partial)) {
                if (!new String(source.readNBytes(4), StandardCharsets.US_ASCII).equals("GGUF")) throw new IOException("Downloaded asset is not GGUF");
            }
            checkCancelled();
            AiModelManifest.modelPath(directory, model.file());
            if (Files.exists(target)) throw new IOException("Model destination appeared during download");
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    void fetch(String url, long size, String hash, Path partial) throws Exception {
            beginPhase(phase.startsWith("runtime") ? "runtime_download" : "model_download", size);
            URI uri = URI.create(url);
            InputStream input = null;
            for (int redirect = 0; redirect <= 5; redirect++) {
                checkCancelled();
                validateUri(uri);
                connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "SmartNpc-optional-model-installer/1");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) throw new IOException("Model download redirect has no destination");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) throw new IOException("Model download HTTP status " + status);
                long contentLength = connection.getContentLengthLong();
                if (contentLength >= 0 && contentLength != size) throw new IOException("Download size differs from reviewed manifest");
                input = connection.getInputStream();
                break;
            }
            if (input == null) throw new IOException("Too many model download redirects");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = input; OutputStream output = Files.newOutputStream(partial, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    checkCancelled();
                    if (read > size - received) throw new IOException("Download exceeds reviewed byte limit");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    advanced(read);
                }
            }
            if (received != size || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(hash)) throw new IOException("Download size or SHA-256 verification failed");
    }

    void verify(Path file, long size, String hash) throws Exception {
        if (Files.size(file) != size) throw new IOException("Installed file size differs from catalog; remove the invalid file before reinstalling");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream source = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = source.read(buffer)) != -1) { checkCancelled(); digest.update(buffer, 0, read); advanced(read); }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(hash)) throw new IOException("Installed file SHA-256 differs from catalog");
    }

    static void validateUri(URI uri) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null) {
            throw new IllegalArgumentException("Model URL must use HTTPS on an approved GitHub/Hugging Face host");
        }
        if (uri.getHost().equalsIgnoreCase("github.com") && !uri.getPath().matches("/[^/]+/[^/]+/releases/download/[^/]+/[^/]+")) throw new IllegalArgumentException("GitHub model URL must point to a versioned Release asset");
    }

    JsonArray llamaReleases() throws Exception {
        checkCancelled();
        // A fixed endpoint, separate from the asset URL allowlist. Never accept an API URL from a catalog.
        connection = (HttpURLConnection) URI.create("https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=10").toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "SmartNpc-optional-model-installer/1");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("GitHub runtime lookup HTTP " + status + "; retry later or configure a pinned runtime");
            try (InputStream input = connection.getInputStream(); var output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (output.size() + read > 4 * 1024 * 1024) throw new IOException("GitHub release metadata exceeds 4 MiB");
                    output.write(buffer, 0, read);
                }
                return JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonArray();
            }
        } finally { connection.disconnect(); }
    }

    void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Model download cancelled");
    }
}
