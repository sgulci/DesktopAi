package com.desktopai.model;

import java.time.LocalDateTime;

/**
 * Configuration for an LLM provider.
 */
public class ProviderConfig {
    private String id;
    private String name;
    private ProviderType type;
    private String apiUrl;
    private String apiKey;
    private boolean isDefault;
    private LocalDateTime createdAt;

    public ProviderConfig() {
        this.createdAt = LocalDateTime.now();
    }

    public ProviderConfig(String id, String name, ProviderType type) {
        this();
        this.id = id;
        this.name = name;
        this.type = type;
    }

    /**
     * Creates an Ollama provider with default URL.
     */
    public static ProviderConfig createOllama(String id, String name) {
        ProviderConfig config = new ProviderConfig(id, name, ProviderType.OLLAMA);
        config.setApiUrl("http://localhost:11434");
        return config;
    }

    /**
     * Creates an OpenAI provider.
     */
    public static ProviderConfig createOpenAI(String id, String name, String apiKey) {
        ProviderConfig config = new ProviderConfig(id, name, ProviderType.OPENAI);
        config.setApiUrl("https://api.openai.com/v1");
        config.setApiKey(apiKey);
        return config;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ProviderType getType() {
        return type;
    }

    public void setType(ProviderType type) {
        this.type = type;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return name;
    }
}
