package com.desktopai.service.embedding;

import com.desktopai.model.DAIDocumentChunk;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Embedding provider that calls the OpenAI-compatible /v1/embeddings endpoint.
 */
public class OpenAIEmbeddingProvider implements EmbeddingProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenAIEmbeddingProvider.class);
    private static final Gson GSON = new Gson();

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public OpenAIEmbeddingProvider(String apiUrl, String apiKey, String model) {
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.apiUrl = base;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public float[] embed(String text) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("input", text);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .timeout(Duration.ofSeconds(60));

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("OpenAI embeddings API returned " + response.statusCode()
                        + ": " + response.body());
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            var embeddingArray = json.getAsJsonArray("data")
                    .get(0).getAsJsonObject()
                    .getAsJsonArray("embedding");

            float[] result = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                result[i] = embeddingArray.get(i).getAsFloat();
            }
            return result;

        } catch (Exception e) {
            throw new RuntimeException("OpenAI embedding failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void embedChunks(List<DAIDocumentChunk> chunks) {
        for (DAIDocumentChunk chunk : chunks) {
            chunk.setEmbedding(embed(chunk.getContent()));
        }
    }

    @Override
    public int getDimension() {
        return 0; // depends on model
    }

    @Override
    public boolean testConnection() {
        try {
            embed("test");
            return true;
        } catch (Exception e) {
            log.warn("OpenAI embedding connection test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "OpenAI (" + model + ")";
    }

    @Override
    public void close() {
        // HttpClient does not require explicit closing
    }
}
