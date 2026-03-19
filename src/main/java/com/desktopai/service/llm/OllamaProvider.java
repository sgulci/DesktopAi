package com.desktopai.service.llm;

import com.desktopai.model.DAIChatMessage;
import com.desktopai.model.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Ollama LLM provider — uses Ollama's /api/chat HTTP endpoint directly.
 */
public class OllamaProvider implements LLMProvider {
    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);
    private final ProviderConfig config;
    private final String modelName;
    private final HttpClient httpClient;
    private final Gson gson;

    public OllamaProvider(ProviderConfig config, String modelName) {
        this.config = config;
        this.modelName = modelName != null ? modelName : "llama3.2:3b";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    @Override
    public String generate(String prompt) {
        try {
            JsonObject body = chatBody(List.of(userMsg(prompt)), false);
            HttpResponse<String> response = httpClient.send(
                    postJson(chatUrl(), body, Duration.ofMinutes(5)),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama error " + response.statusCode() + ": " + response.body());
            }
            return gson.fromJson(response.body(), JsonObject.class)
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
                        postJson(chatUrl(), body, Duration.ofMinutes(5)),
                        HttpResponse.BodyHandlers.ofLines())
                        .body()
                        .forEach(line -> {
                            if (line.isBlank()) return;
                            try {
                                JsonObject json = gson.fromJson(line, JsonObject.class);
                                if (json.has("message")) {
                                    String token = json.getAsJsonObject("message")
                                            .get("content").getAsString();
                                    if (!token.isEmpty()) tokenConsumer.accept(token);
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
    public List<String> listModels() {
        List<String> models = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonArray arr = gson.fromJson(response.body(), JsonObject.class)
                        .getAsJsonArray("models");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        models.add(arr.get(i).getAsJsonObject().get("name").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list Ollama models: {}", e.getMessage());
        }
        return models;
    }

    @Override
    public String getProviderName() { return config.getName(); }

    @Override
    public String getProviderId() { return config.getId(); }

    @Override
    public boolean testConnection() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public void pullModel(String model, Consumer<String> progressConsumer) {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("name", model);
            requestBody.addProperty("stream", true);
            httpClient.send(
                    postJson(config.getApiUrl() + "/api/pull", requestBody, Duration.ofHours(1)),
                    HttpResponse.BodyHandlers.ofLines())
                    .body()
                    .forEach(line -> {
                        try {
                            JsonObject json = gson.fromJson(line, JsonObject.class);
                            if (json.has("status")) progressConsumer.accept(json.get("status").getAsString());
                        } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            progressConsumer.accept("Error: " + e.getMessage());
        }
    }

    public boolean deleteModel(String model) {
        try {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("name", model);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + "/api/delete"))
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String chatUrl() { return config.getApiUrl() + "/api/chat"; }

    private HttpRequest postJson(String url, JsonObject body, Duration timeout) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .timeout(timeout)
                .build();
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
