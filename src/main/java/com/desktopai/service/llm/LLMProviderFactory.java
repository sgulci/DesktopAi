package com.desktopai.service.llm;

import com.desktopai.model.ProviderConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Factory for creating LLM providers based on configuration.
 * LocalNativeProvider instances are cached to avoid repeated model loads and resource leaks.
 */
public class LLMProviderFactory {

    private static final Map<String, LocalNativeProvider> nativeProviderCache = new ConcurrentHashMap<>();

    public static LLMProvider create(ProviderConfig config) {
        return create(config, null);
    }

    public static LLMProvider create(ProviderConfig config, String modelName) {
        return switch (config.getType()) {
            case OLLAMA -> new OllamaProvider(config, modelName);
            case LOCAL_NATIVE -> {
                String cacheKey = config.getId() + ":" + (modelName != null ? modelName : "");
                yield nativeProviderCache.computeIfAbsent(
                        cacheKey, k -> new LocalNativeProvider(config, modelName));
            }
            case OPENAI -> new OpenAIProvider(config, modelName);
            case ANTHROPIC -> new AnthropicProvider(config, modelName);
            case CUSTOM -> new CustomProvider(config, modelName);
        };
    }

    public static LLMProvider createDefaultOllama() {
        ProviderConfig config = ProviderConfig.createOllama("default-ollama", "Ollama");
        return new OllamaProvider(config, null);
    }

    /**
     * Closes all cached native providers and releases their native resources.
     * Call from Application.stop().
     */
    public static void closeAll() {
        nativeProviderCache.values().forEach(LocalNativeProvider::close);
        nativeProviderCache.clear();
    }
}
