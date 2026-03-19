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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Custom LLM provider for OpenAI-compatible APIs (e.g. LM Studio, LocalAI, vLLM).
 */
public class CustomProvider implements LLMProvider {
    private final ProviderConfig config;
    private final String modelName;
    private final HttpClient httpClient;
    private final Gson gson;

    public CustomProvider(ProviderConfig config, String modelName) {
        this.config = config;
        this.modelName = modelName != null ? modelName : "default";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.gson = new Gson();
    }

    @Override
    public String generate(String prompt) {
        try {
            JsonObject body = buildRequestBody(List.of(userMessage(prompt)), false);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(chatEndpoint()))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .timeout(Duration.ofMinutes(5));

            if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                reqBuilder.header("Authorization", "Bearer " + config.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("API error " + response.statusCode() + ": " + response.body());
            }

            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            return json.getAsJsonArray("choices").get(0).getAsJsonObject()
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
                List<JsonObject> messages = buildMessages(history, currentPrompt);
                JsonObject body = buildRequestBody(messages, true);

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(chatEndpoint()))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                        .timeout(Duration.ofMinutes(5));

                if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                    reqBuilder.header("Authorization", "Bearer " + config.getApiKey());
                }

                httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofLines())
                        .body()
                        .forEach(line -> {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if (data.equals("[DONE]")) return;
                                try {
                                    JsonObject event = gson.fromJson(data, JsonObject.class);
                                    JsonArray choices = event.getAsJsonArray("choices");
                                    if (choices != null && choices.size() > 0) {
                                        JsonObject delta = choices.get(0).getAsJsonObject()
                                                .getAsJsonObject("delta");
                                        if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                                            tokenConsumer.accept(delta.get("content").getAsString());
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // Skip malformed SSE lines
                                }
                            }
                        });

            } catch (Exception e) {
                tokenConsumer.accept("[Error: " + e.getMessage() + "]");
                throw new RuntimeException(e);
            }
        });
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
                messages.add(textMessage(role, msg.getContent()));
            }
        }
        messages.add(userMessage(currentPrompt));
        return messages;
    }

    private JsonObject buildRequestBody(List<JsonObject> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelName);
        body.addProperty("stream", stream);

        JsonArray msgArray = new JsonArray();
        messages.forEach(msgArray::add);
        body.add("messages", msgArray);

        return body;
    }

    private JsonObject userMessage(String content) {
        return textMessage("user", content);
    }

    private JsonObject textMessage(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        return msg;
    }

    private String chatEndpoint() {
        String base = config.getApiUrl();
        if (base == null || base.isEmpty()) {
            throw new IllegalStateException("Custom provider requires an API URL. Set it in Settings.");
        }
        // Append /chat/completions if not already present
        if (!base.endsWith("/chat/completions")) {
            base = base.replaceAll("/+$", "") + "/chat/completions";
        }
        return base;
    }

    @Override
    public List<String> listModels() {
        return Collections.singletonList(modelName);
    }

    @Override
    public String getProviderName() {
        return config.getName();
    }

    @Override
    public String getProviderId() {
        return config.getId();
    }

    @Override
    public boolean testConnection() {
        return config.getApiUrl() != null && !config.getApiUrl().isEmpty();
    }
}
