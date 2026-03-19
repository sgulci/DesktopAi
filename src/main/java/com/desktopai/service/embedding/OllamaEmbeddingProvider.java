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
 * Embedding provider that calls Ollama's /api/embeddings endpoint.
 */
public class OllamaEmbeddingProvider implements EmbeddingProvider {
    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;

    public OllamaEmbeddingProvider(String baseUrl, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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
            body.addProperty("prompt", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/embeddings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama embeddings API returned " + response.statusCode()
                        + ": " + response.body());
            }

            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            var embeddingArray = json.getAsJsonArray("embedding");
            float[] result = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                result[i] = embeddingArray.get(i).getAsFloat();
            }
            return result;

        } catch (Exception e) {
            throw new RuntimeException("Ollama embedding failed: " + e.getMessage(), e);
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
        return 0; // unknown statically; depends on model
    }

    @Override
    public boolean testConnection() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Ollama embedding connection test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "Ollama (" + model + ")";
    }

    @Override
    public void close() {
        // HttpClient does not require explicit closing
    }
}
