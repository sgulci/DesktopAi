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
 * OpenAI LLM provider — uses OpenAI's /v1/chat/completions HTTP endpoint directly.
 */
public class OpenAIProvider implements LLMProvider {
    private static final String BASE_URL = "https://api.openai.com/v1/chat/completions";
    private static final List<String> AVAILABLE_MODELS = Arrays.asList(
            "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo");

    private final ProviderConfig config;
    private final String modelName;
    private final HttpClient httpClient;
    private final Gson gson;

    public OpenAIProvider(ProviderConfig config, String modelName) {
        this.config = config;
        this.modelName = modelName != null ? modelName : "gpt-4o-mini";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.gson = new Gson();
    }

    @Override
    public String generate(String prompt) {
        try {
            JsonObject body = chatBody(List.of(userMsg(prompt)), false);
            HttpResponse<String> response = httpClient.send(
                    postJson(BASE_URL, body, Duration.ofMinutes(2)),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenAI error " + response.statusCode() + ": " + response.body());
            }
            return gson.fromJson(response.body(), JsonObject.class)
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
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
                JsonObject body = chatBody(buildMessages(history, currentPrompt), true);
                httpClient.send(
                        postJson(BASE_URL, body, Duration.ofMinutes(2)),
                        HttpResponse.BodyHandlers.ofLines())
                        .body()
                        .forEach(line -> {
                            if (!line.startsWith("data: ")) return;
                            String data = line.substring(6).trim();
                            if (data.equals("[DONE]")) return;
                            try {
                                JsonObject event = gson.fromJson(data, JsonObject.class);
                                JsonArray choices = event.getAsJsonArray("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    JsonObject delta = choices.get(0).getAsJsonObject()
                                            .getAsJsonObject("delta");
                                    if (delta != null && delta.has("content")
                                            && !delta.get("content").isJsonNull()) {
                                        tokenConsumer.accept(delta.get("content").getAsString());
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

    private HttpRequest postJson(String url, JsonObject body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .timeout(timeout);
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + config.getApiKey());
        }
        return builder.build();
    }

    private JsonObject chatBody(List<JsonObject> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelName);
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
                String role = switch (msg.getRole()) {
                    case USER -> "user";
                    case ASSISTANT -> "assistant";
                    case SYSTEM -> "system";
                };
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
