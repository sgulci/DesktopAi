package com.desktopai.service;

import com.desktopai.model.LLMModel;
import com.desktopai.model.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.desktopai.model.ProviderType;
import com.desktopai.repository.ProviderRepository;
import com.desktopai.service.llm.OllamaProvider;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Service for managing LLM providers and models.
 */
public class ModelManagerService {
    private static final Logger log = LoggerFactory.getLogger(ModelManagerService.class);
    private final ProviderRepository providerRepository;
    private final HttpClient httpClient;
    private final Gson gson;

    public ModelManagerService() {
        this.providerRepository = new ProviderRepository();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.gson = new Gson();
    }

    /** Represents a HuggingFace model repository. */
    public record HFModel(String id, long downloads) {
        @Override public String toString() {
            return downloads > 0
                    ? id + "  (" + formatNum(downloads) + " downloads)"
                    : id;
        }
        private static String formatNum(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }

    /** Represents a model from the Ollama library (ollama.com). */
    public record OllamaLibraryModel(String name, String description, long pulls) {
        @Override public String toString() {
            return pulls > 0 ? name + "  (" + formatNum(pulls) + " pulls)" : name;
        }
        private static String formatNum(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }

    /** Represents a single GGUF file inside a HuggingFace repo. */
    public record HFFile(String filename, long sizeBytes) {
        @Override public String toString() {
            if (sizeBytes <= 0) return filename;
            double gb = sizeBytes / 1_073_741_824.0;
            if (gb >= 1) return String.format("%s  (%.1f GB)", filename, gb);
            return String.format("%s  (%.0f MB)", filename, sizeBytes / 1_048_576.0);
        }
    }

    /**
     * Searches HuggingFace for GGUF model repositories.
     * Runs synchronously — call from a background thread.
     */
    public List<HFModel> searchHuggingFaceModels(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://huggingface.co/api/models?search=" + encoded
                + "&filter=gguf&sort=downloads&direction=-1&limit=30";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "DesktopAI/1.0")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HuggingFace API error: " + response.statusCode());
        }

        List<HFModel> results = new ArrayList<>();
        JsonArray arr = gson.fromJson(response.body(), JsonArray.class);
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            String id = obj.get("id").getAsString();
            long downloads = obj.has("downloads") ? obj.get("downloads").getAsLong() : 0L;
            results.add(new HFModel(id, downloads));
        }
        return results;
    }

    /**
     * Fetches the list of GGUF files available in a HuggingFace repository.
     * Runs synchronously — call from a background thread.
     */
    public List<HFFile> getHuggingFaceFiles(String modelId) throws Exception {
        String url = "https://huggingface.co/api/models/" + modelId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "DesktopAI/1.0")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HuggingFace API error: " + response.statusCode());
        }

        List<HFFile> files = new ArrayList<>();
        JsonObject obj = gson.fromJson(response.body(), JsonObject.class);
        JsonArray siblings = obj.getAsJsonArray("siblings");
        if (siblings != null) {
            for (int i = 0; i < siblings.size(); i++) {
                JsonObject sibling = siblings.get(i).getAsJsonObject();
                String filename = sibling.get("rfilename").getAsString();
                if (!filename.endsWith(".gguf")) continue;
                long size = sibling.has("size") ? sibling.get("size").getAsLong() : 0L;
                files.add(new HFFile(filename, size));
            }
        }
        return files;
    }

    // Patterns for parsing ollama.com/search HTML fragments
    private static final Pattern PULL_COUNT_PATTERN =
            Pattern.compile("x-test-pull-count>([\\d.]+[KMB]?)</span>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC_PATTERN =
            Pattern.compile("<p[^>]*max-w-lg[^>]*>([^<]+)</p>");

    /**
     * Searches the Ollama library by scraping ollama.com/search via its htmx endpoint.
     * (Ollama has no official JSON search API — see github.com/ollama/ollama/issues/9142)
     * Runs synchronously — call from a background thread.
     */
    public List<OllamaLibraryModel> searchOllamaLibraryModels(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://ollama.com/search?q=" + encoded + "&sort=popular&p=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("HX-Request", "true")
                .header("HX-Target", "searchresults")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("ollama.com search returned " + response.statusCode());
        }

        List<OllamaLibraryModel> results = new ArrayList<>();
        String html = response.body();

        // Each model block starts after href="/library/{name}"
        String[] blocks = html.split("href=\"/library/");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int nameEnd = block.indexOf('"');
            if (nameEnd < 0) continue;
            String name = block.substring(0, nameEnd);
            if (name.isBlank()) continue;

            Matcher descMatcher = DESC_PATTERN.matcher(block);
            String desc = descMatcher.find()
                    ? descMatcher.group(1).replace("&#39;", "'").replace("&amp;", "&").trim()
                    : "";

            Matcher pullMatcher = PULL_COUNT_PATTERN.matcher(block);
            long pulls = pullMatcher.find() ? parseHumanNumber(pullMatcher.group(1)) : 0L;

            results.add(new OllamaLibraryModel(name, desc, pulls));
        }
        return results;
    }

    private static long parseHumanNumber(String s) {
        if (s == null || s.isBlank()) return 0L;
        s = s.trim().toUpperCase();
        try {
            if (s.endsWith("B")) return (long)(Double.parseDouble(s.substring(0, s.length()-1)) * 1_000_000_000);
            if (s.endsWith("M")) return (long)(Double.parseDouble(s.substring(0, s.length()-1)) * 1_000_000);
            if (s.endsWith("K")) return (long)(Double.parseDouble(s.substring(0, s.length()-1)) * 1_000);
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Returns the download URL for a HuggingFace file. */
    public static String huggingFaceDownloadUrl(String modelId, String filename) {
        return "https://huggingface.co/" + modelId + "/resolve/main/" + filename;
    }

    /**
     * Gets all configured providers.
     */
    public List<ProviderConfig> getAllProviders() throws SQLException {
        return providerRepository.findAll();
    }

    /**
     * Gets the default provider.
     */
    public Optional<ProviderConfig> getDefaultProvider() throws SQLException {
        return providerRepository.findDefault();
    }

    /**
     * Adds a new provider.
     */
    public ProviderConfig addProvider(ProviderConfig config) throws SQLException {
        return providerRepository.save(config);
    }

    /**
     * Updates a provider.
     */
    public void updateProvider(ProviderConfig config) throws SQLException {
        providerRepository.update(config);
    }

    /**
     * Deletes a provider.
     */
    public void deleteProvider(String id) throws SQLException {
        providerRepository.deleteById(id);
    }

    /**
     * Sets a provider as default.
     */
    public void setDefaultProvider(String id) throws SQLException {
        providerRepository.setDefault(id);
    }

    /**
     * Gets all available models from all providers.
     */
    /**
     * Downloads a model file from a URL synchronously.
     * The caller is responsible for running this on a background thread.
     */
    public void downloadModel(String url, String filename, Consumer<Double> progressCallback) throws Exception {
        java.io.File modelsDir = new java.io.File(System.getProperty("user.home"), ".desktopai/models");
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }

        java.io.File targetFile = new java.io.File(modelsDir, filename);
        java.net.URL downloadUrl = new java.net.URI(url).toURL();
        java.net.URLConnection connection = downloadUrl.openConnection();
        long fileSize = connection.getContentLengthLong();

        try (java.io.InputStream in = connection.getInputStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(targetFile)) {

            byte[] buffer = new byte[8192];
            long totalBytesRead = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                if (fileSize > 0) {
                    progressCallback.accept((double) totalBytesRead / fileSize);
                }
            }
        }
        progressCallback.accept(1.0);
    }

    public List<LLMModel> getAllModels() throws SQLException {
        List<LLMModel> models = new ArrayList<>();

        for (ProviderConfig provider : getAllProviders()) {
            if (provider.getType() == ProviderType.LOCAL_NATIVE) {
                // List files in .desktopai/models
                java.io.File modelsDir = new java.io.File(System.getProperty("user.home"), ".desktopai/models");
                if (modelsDir.exists()) {
                    java.io.File[] files = modelsDir.listFiles((dir, name) -> name.endsWith(".gguf"));
                    if (files != null) {
                        for (java.io.File file : files) {
                            LLMModel model = new LLMModel(file.getName(), provider.getId(), provider.getType());
                            model.setDisplayName(file.getName());
                            // Size in human-readable format could be added to LLMModel if needed, but for
                            // now passing 0/default
                            models.add(model);
                        }
                    }
                }
            } else {
                try {
                    List<String> modelNames = getModelsForProvider(provider);
                    for (String name : modelNames) {
                        LLMModel model = new LLMModel(name, provider.getId(), provider.getType());
                        model.setDisplayName(name);
                        models.add(model);
                    }
                } catch (Exception e) {
                    log.warn("Failed to list models for provider {}: {}", provider.getName(), e.getMessage());
                }
            }
        }

        return models;
    }

    /**
     * Gets models for a specific provider.
     */
    public List<String> getModelsForProvider(ProviderConfig provider) {
        return switch (provider.getType()) {
            case OLLAMA -> {
                OllamaProvider ollamaProvider = new OllamaProvider(provider, null);
                yield ollamaProvider.listModels();
            }
            case OPENAI -> List.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo");
            case ANTHROPIC -> List.of("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022",
                    "claude-3-opus-20240229", "claude-3-sonnet-20240229");
            case CUSTOM -> List.of("default");
            case LOCAL_NATIVE -> List.of(); // Handled in getAllModels directly
        };
    }

    /**
     * Pulls (downloads) an Ollama model.
     */
    public void pullOllamaModel(String providerId, String modelName, Consumer<String> progressCallback)
            throws SQLException {
        Optional<ProviderConfig> providerOpt = providerRepository.findById(providerId);
        if (providerOpt.isEmpty()) {
            throw new IllegalArgumentException("Provider not found: " + providerId);
        }

        ProviderConfig provider = providerOpt.get();
        if (provider.getType() != ProviderType.OLLAMA) {
            throw new IllegalArgumentException("Can only pull models for Ollama providers");
        }

        OllamaProvider ollamaProvider = new OllamaProvider(provider, null);
        ollamaProvider.pullModel(modelName, progressCallback);
    }

    /**
     * Deletes an Ollama model.
     */
    public boolean deleteOllamaModel(String providerId, String modelName) throws SQLException {
        Optional<ProviderConfig> providerOpt = providerRepository.findById(providerId);
        if (providerOpt.isEmpty()) {
            return false;
        }

        ProviderConfig provider = providerOpt.get();
        if (provider.getType() != ProviderType.OLLAMA) {
            return false;
        }

        OllamaProvider ollamaProvider = new OllamaProvider(provider, null);
        return ollamaProvider.deleteModel(modelName);
    }

    /**
     * Tests connection to a provider.
     */
    public boolean testProviderConnection(ProviderConfig provider) {
        return switch (provider.getType()) {
            case OLLAMA -> new OllamaProvider(provider, null).testConnection();
            case OPENAI -> provider.getApiKey() != null && !provider.getApiKey().isEmpty();
            case ANTHROPIC -> provider.getApiKey() != null && !provider.getApiKey().isEmpty();
            case CUSTOM -> provider.getApiUrl() != null && !provider.getApiUrl().isEmpty();
            case LOCAL_NATIVE -> true; // Always "connected" as it is local file system
        };
    }
}
