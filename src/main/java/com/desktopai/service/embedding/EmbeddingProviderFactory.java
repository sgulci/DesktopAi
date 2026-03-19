package com.desktopai.service.embedding;

import com.desktopai.model.EmbeddingProviderType;
import com.desktopai.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating and caching EmbeddingProvider instances.
 */
public class EmbeddingProviderFactory {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingProviderFactory.class);

    private static volatile LocalOnnxEmbeddingProvider cachedLocalProvider;
    private static final Object LOCK = new Object();

    public static EmbeddingProvider create(ConfigService configService) {
        try {
            EmbeddingProviderType type = configService.getEmbeddingType();
            return switch (type) {
                case LOCAL_ONNX -> getOrCreateLocal();
                case OLLAMA -> {
                    String url = configService.getEmbeddingApiUrl();
                    if (url == null || url.isBlank()) url = "http://localhost:11434";
                    String model = configService.getEmbeddingModel();
                    if (model == null || model.isBlank()) model = "nomic-embed-text";
                    yield new OllamaEmbeddingProvider(url, model);
                }
                case OPENAI -> {
                    String url = configService.getEmbeddingApiUrl();
                    if (url == null || url.isBlank()) url = "https://api.openai.com";
                    String key = configService.getEmbeddingApiKey();
                    String model = configService.getEmbeddingModel();
                    if (model == null || model.isBlank()) model = "text-embedding-3-small";
                    yield new OpenAIEmbeddingProvider(url, key, model);
                }
            };
        } catch (Exception e) {
            log.warn("Failed to create configured embedding provider, falling back to LOCAL_ONNX: {}", e.getMessage());
            return getOrCreateLocal();
        }
    }

    private static LocalOnnxEmbeddingProvider getOrCreateLocal() {
        if (cachedLocalProvider == null) {
            synchronized (LOCK) {
                if (cachedLocalProvider == null) {
                    cachedLocalProvider = new LocalOnnxEmbeddingProvider();
                }
            }
        }
        return cachedLocalProvider;
    }

    public static void closeAll() {
        synchronized (LOCK) {
            if (cachedLocalProvider != null) {
                cachedLocalProvider.close();
                cachedLocalProvider = null;
            }
        }
    }
}
