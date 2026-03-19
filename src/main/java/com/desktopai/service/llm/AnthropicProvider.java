package com.desktopai.service.llm;

import com.desktopai.model.DAIChatMessage;
import com.desktopai.model.ProviderConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Anthropic (Claude) LLM provider — uses Anthropic's /v1/messages HTTP endpoint directly.
 */
public class AnthropicProvider implements LLMProvider {
    private static final String BASE_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 4096;

    private static final List<String> AVAILABLE_MODELS = Arrays.asList(
            "claude-sonnet-4-6",
            "claude-opus-4-6",
            "claude-haiku-4-5-20251001",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022",
            "claude-3-opus-20240229");

    private final ProviderConfig config;
    private final String modelName;
    private final HttpClient httpClient;
    private final Gson gson;

    public AnthropicProvider(ProviderConfig config, String modelName) {
        this.config = config;
        this.modelName = modelName != null ? modelName : "claude-sonnet-4-6";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.gson = new Gson();
    }

    @Override
    public String generate(String prompt) {
        try {
            JsonObject body = requestBody(List.of(userMsg(prompt)), false);
            HttpResponse<String> response = httpClient.send(
                    buildRequest(body, Duration.ofMinutes(5)),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic error " + response.statusCode() + ": " + response.body());
            }
            return gson.fromJson(response.body(), JsonObject.class)
                    .getAsJsonArray("content").get(0).getAsJsonObject()
                    .get("text").getAsString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate response: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Void> generateStream(String prompt, Consumer<String> tokenConsumer) {
        return generateStreamWithHistory(null, prompt, tokenConsumer);
    }

    @Override
    public CompletableFuture<Void> generateStreamWithHistory(
            List<DAIChatMessage> history, String currentPrompt, Consumer<String> tokenConsumer) {
        return CompletableFuture.runAsync(() -> {
            try {
                JsonObject body = requestBody(buildMessages(history, currentPrompt), true);
                httpClient.send(
                        buildRequest(body, Duration.ofMinutes(5)),
                        HttpResponse.BodyHandlers.ofLines())
                        .body()
                        .forEach(line -> {
                            if (!line.startsWith("data: ")) return;
                            String data = line.substring(6).trim();
                            try {
                                JsonObject event = gson.fromJson(data, JsonObject.class);
                                if ("content_block_delta".equals(
                                        event.get("type").getAsString())) {
                                    JsonObject delta = event.getAsJsonObject("delta");
                                    if (delta != null && delta.has("text")) {
                                        String text = delta.get("text").getAsString();
                                        if (!text.isEmpty()) tokenConsumer.accept(text);
                                    }
                                }
                            } catch (Exception ignored) {}
                        });
            } catch (Exception e) {
                tokenConsumer.accept("[Error: " + e.getMessage() + "]");
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public List<String> listModels() { return AVAILABLE_MODELS; }

    @Override
    public String getProviderName() { return config.getName(); }

    @Override
    public String getProviderId() { return config.getId(); }

    @Override
    public boolean testConnection() {
        return config.getApiKey() != null && !config.getApiKey().trim().isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HttpRequest buildRequest(JsonObject body, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .timeout(timeout)
                .build();
    }

    private JsonObject requestBody(List<JsonObject> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelName);
        body.addProperty("max_tokens", MAX_TOKENS);
        body.addProperty("stream", stream);
        JsonArray arr = new JsonArray();
        messages.forEach(arr::add);
        body.add("messages", arr);
        return body;
    }

    private List<JsonObject> buildMessages(List<DAIChatMessage> history, String currentPrompt) {
        List<JsonObject> messages = new ArrayList<>();
        if (history != null) {
            for (DAIChatMessage msg : history) {
                // Anthropic only supports "user" and "assistant" roles in messages
                if (msg.getRole() == DAIChatMessage.Role.SYSTEM) continue;
                String role = msg.getRole() == DAIChatMessage.Role.USER ? "user" : "assistant";
                messages.add(textMsg(role, msg.getContent()));
            }
        }
        messages.add(userMsg(currentPrompt));
        return messages;
    }

    private JsonObject userMsg(String content) { return textMsg("user", content); }

    private JsonObject textMsg(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }
}
