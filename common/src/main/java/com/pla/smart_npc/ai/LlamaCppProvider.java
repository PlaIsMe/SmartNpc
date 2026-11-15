package com.pla.smart_npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Optional adapter for an administrator-managed loopback server. Never launches a process. */
public final class LlamaCppProvider implements AiInferenceProvider {
    private final AiConfig config;
    private final HttpClient client;
    private final URI endpoint;

    public LlamaCppProvider(Path directory, AiConfig config) {
        config.validate();
        if (!config.externalRuntime) throw new IllegalArgumentException("HTTP provider requires explicit externalRuntime opt-in");
        this.config = config;
        this.endpoint = URI.create(config.endpoint);
        // Numeric loopback + no proxy + no redirect prevents accidentally sending private context remotely.
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
        }).build();
    }

    @Override
    public void start() throws Exception {
        JsonArray models = JsonParser.parseString(send(HttpRequest.newBuilder(endpoint.resolve("/v1/models")).timeout(Duration.ofSeconds(2)).GET().build())).getAsJsonObject().getAsJsonArray("data");
        boolean matches = false;
        for (var model : models) matches |= config.modelId.equals(model.getAsJsonObject().get("id").getAsString());
        if (!matches) throw new IOException("External llama-server model alias differs from configured modelId");
        AiResponseDecoder.decode(generate(new AiRequest(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, "Return compact JSON. Say ready. Use NONE with null target/item and count 0.", "{\"allowed_actions\":[\"NONE\"]}", 0)));
    }

    @Override
    public String generate(AiRequest request) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.modelId);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", config.maxOutputTokens);
        body.addProperty("temperature", config.temperature);
        JsonArray messages = new JsonArray();
        messages.add(message("system", request.systemPrompt()));
        messages.add(message("user", request.contextJson()));
        body.add("messages", messages);
        JsonObject template = new JsonObject();
        template.addProperty("enable_thinking", false);
        body.add("chat_template_kwargs", template);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "smart_npc_reply");
        definition.addProperty("strict", true);
        definition.add("schema", AiResponseDecoder.schema(request.contextJson()));
        format.add("json_schema", definition);
        body.add("response_format", format);
        String raw = send(HttpRequest.newBuilder(endpoint.resolve("/v1/chat/completions")).timeout(Duration.ofSeconds(config.inferenceTimeoutSeconds)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
        JsonObject choice = JsonParser.parseString(raw).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
        if (!"stop".equals(choice.get("finish_reason").getAsString())) throw new IOException("Model response was truncated or did not finish normally");
        return choice.getAsJsonObject("message").get("content").getAsString();
    }

    private String send(HttpRequest request) throws Exception {
        // A bounded subscriber retains timeout/cancellation for the entire response body.
        HttpResponse<byte[]> response = client.send(request, info -> new LimitedBodySubscriber(65536));
        if (response.statusCode() != 200) throw new IOException("Local inference HTTP status " + response.statusCode());
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    @Override public void close() {
        // The administrator owns this optional server. The actor interrupts pending HTTP calls.
    }
}
