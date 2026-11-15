package com.pla.smart_npc.ai;

import com.pla.smart_npc.SmartNpc;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Runs native inference in the Minecraft JVM; all handle access belongs to the inference actor. */
public final class EmbeddedLlamaProvider implements AiInferenceProvider {
    private final Path directory;
    private final AiConfig config;
    private long handle;
    private boolean closed;

    public EmbeddedLlamaProvider(Path directory, AiConfig config) {
        config.validate();
        this.directory = directory;
        this.config = config;
    }

    @Override public void start() throws Exception {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("AI startup cancelled");
        Path model = AiModelManifest.modelPath(directory, config.modelFile);
        AiModelManifest.Model approved = AiModelManifest.load(directory).find(config.modelId);
        if (!approved.file().equals(config.modelFile) || !Files.isRegularFile(model) || Files.size(model) != approved.downloadBytes()) {
            throw new IOException("Selected model file/size does not match models.json; download it in AI settings");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(model)) {
            byte[] header = input.readNBytes(4);
            if (!new String(header, StandardCharsets.US_ASCII).equals("GGUF")) throw new IOException("Model is not a GGUF file");
            digest.update(header);
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                digest.update(buffer, 0, read);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(approved.sha256())) throw new IOException("Selected model SHA-256 differs from models.json");
        EmbeddedLlamaBridge.load(directory);
        handle = EmbeddedLlamaBridge.open(utf8(model.toAbsolutePath().toString()), config.contextSize, config.cpuThreads, config.startupTimeoutSeconds, Thread.currentThread());
        if (handle == 0) throw new IOException("Native runtime returned no model handle");
        // Test the real template/schema path before accepting player messages.
        AiResponseDecoder.decode(generate(new AiRequest(UUID.randomUUID(), UUID.randomUUID(), null, "Return one compact JSON object matching the schema. Say ready. Use action NONE with null target/item and count 0. Do not think.", "{\"allowed_actions\":[\"NONE\"]}", 0)));
        SmartNpc.LOGGER.info("SmartNpc embedded {} ready: {}; CPU threads={}; no child process or listening port", EmbeddedLlamaBridge.VERSION, config.modelId, config.cpuThreads);
        if (config.gpuLayers != 0) SmartNpc.LOGGER.info("Bundled embedded runtime uses CPU; gpuLayers applies only to an administrator-managed external runtime");
    }

    @Override public String generate(AiRequest request) throws Exception {
        if (closed || handle == 0) throw new IOException("Native model is closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("AI request cancelled");
        byte[] result = EmbeddedLlamaBridge.generate(handle, utf8(request.systemPrompt()), utf8(request.contextJson()), utf8(AiResponseDecoder.schema(request.contextJson()).toString()), config.maxOutputTokens, config.temperature, config.inferenceTimeoutSeconds, Thread.currentThread());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("AI request cancelled");
        return new String(result, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Override public void close() {
        closed = true;
        long previous = handle;
        handle = 0;
        if (previous != 0) EmbeddedLlamaBridge.close(previous);
    }
}
