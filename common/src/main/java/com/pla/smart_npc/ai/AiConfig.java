package com.pla.smart_npc.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Instance-local, opt-in server configuration. Models download only on explicit request. */
public final class AiConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public boolean enabled = false;
    public boolean showSetupReminder = true;
    public boolean ambientChat = true;
    public boolean npcConversations = true;
    public boolean eventReactions = true;
    public boolean witnessDeathReactions = true;
    public boolean witnessDeathFlee = true;
    public boolean actionsEnabled = true;
    public boolean giftsEnabled = true;
    public boolean harmlessTrolling = true;
    public boolean fakeProvider = false;
    public double hearingRadius = 16;
    public double ambientProbability = 0.25;
    public int playerCooldownTicks = 200;
    public int npcCooldownTicks = 600;
    public int socialCooldownTicks = 2400;
    public int maxGiftCount = 8;
    /** '*' permits any carried item; an explicit list restricts the actual transferred IDs. */
    public List<String> giftItems = new ArrayList<>(List.of("*"));
    public List<String> disabledDimensions = new ArrayList<>();
    public String endpoint = "http://127.0.0.1:8765";
    public String modelId = "qwen2.5-0.5b-en_us";
    public String modelLanguage = "en_us";
    public String modelFile = "en_us_smartnpc-qwen2.5-0.5b-instruct-Q4_K_M.gguf";
    /** Set true only to connect to a separately managed localhost llama-server. */
    public boolean externalRuntime = false;
    /** Historical setting retained for config compatibility; never executed by the mod. */
    public String executable = "";
    public int contextSize = 2048;
    public int maxOutputTokens = 160;
    public int cpuThreads = 2;
    public int gpuLayers = 0;
    public int queueCapacity = 8;
    public int queueExpirySeconds = 5;
    public int inferenceTimeoutSeconds = 30;
    public int startupTimeoutSeconds = 120;
    public double temperature = 0.6;

    public static AiConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("ai.json");
        if (!Files.exists(file)) {
            AiConfig defaults = new AiConfig();
            defaults.save(directory);
            return defaults;
        }
        if (Files.size(file) > 65536) throw new IOException("ai.json exceeds 64 KiB");
        try {
            AiConfig config = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AiConfig.class);
            if (config == null) throw new IllegalArgumentException("Empty AI configuration");
            config.validate();
            if (!config.externalRuntime && !config.executable.isBlank()) {
                config.executable = ""; // Migrate the former child-process setting; leave old files untouched.
                config.save(directory);
            }
            return config;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid ai.json: " + exception.getMessage(), exception);
        }
    }

    public void save(Path directory) throws IOException {
        validate();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "ai-", ".json.part");
        try {
            Files.writeString(temporary, JSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve("ai.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void validate() {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !List.of("127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getPort() < 1024 || uri.getPort() > 65535 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("endpoint must be an HTTP numeric loopback address with a port (1024-65535)");
        }
        if (modelId == null || !modelId.matches("[a-zA-Z0-9._-]{1,80}")) throw new IllegalArgumentException("Invalid modelId");
        if (!List.of("en_us", "zh_cn", "vi_vn").contains(modelLanguage)) throw new IllegalArgumentException("Only English, Chinese and Vietnamese model releases are supported");
        if (giftItems != null && giftItems.stream().anyMatch(item -> item == null || !(item.equals("*") || item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))) throw new IllegalArgumentException("giftItems must contain item IDs or '*'");
        AiModelManifest.validateFileName(modelFile);
        if (executable == null) throw new IllegalArgumentException("executable cannot be null");
        if (!Double.isFinite(hearingRadius) || hearingRadius < 2 || hearingRadius > 32
                || !Double.isFinite(ambientProbability) || ambientProbability < 0 || ambientProbability > 1
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 2
                || contextSize < 1024 || contextSize > 8192 || maxOutputTokens < 64 || maxOutputTokens > 256
                || cpuThreads < 1 || cpuThreads > 32 || gpuLayers < 0 || gpuLayers > 999
                || queueCapacity < 1 || queueCapacity > 32 || queueExpirySeconds < 1 || queueExpirySeconds > 30
                || inferenceTimeoutSeconds < 1 || inferenceTimeoutSeconds > 120 || startupTimeoutSeconds < 5 || startupTimeoutSeconds > 300
                || playerCooldownTicks < 20 || npcCooldownTicks < 20 || socialCooldownTicks < 400
                || maxGiftCount < 1 || maxGiftCount > 64 || giftItems == null || giftItems.size() > 64
                || disabledDimensions == null || disabledDimensions.size() > 64) {
            throw new IllegalArgumentException("AI configuration is outside its supported limits");
        }
    }
}
