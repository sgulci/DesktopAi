package com.desktopai.model;

/**
 * Represents an LLM model available from a provider.
 */
public class LLMModel {
    private String name;
    private String displayName;
    private String providerId;
    private ProviderType providerType;
    private long sizeBytes;
    private boolean isDefault;

    public LLMModel() {
    }

    public LLMModel(String name, String providerId, ProviderType providerType) {
        this.name = name;
        this.displayName = name;
        this.providerId = providerId;
        this.providerType = providerType;
    }

    // Getters and setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    /**
     * Returns a formatted size string (e.g., "3.8 GB").
     */
    public String getFormattedSize() {
        if (sizeBytes <= 0)
            return "";
        double gb = sizeBytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) {
            return String.format("%.1f GB", gb);
        }
        double mb = sizeBytes / (1024.0 * 1024.0);
        return String.format("%.0f MB", mb);
    }

    @Override
    public String toString() {
        String size = getFormattedSize();
        return displayName + (size.isEmpty() ? "" : " (" + size + ")");
    }
}
