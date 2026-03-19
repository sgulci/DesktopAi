package com.desktopai.model;

/**
 * Types of LLM providers supported.
 */
public enum ProviderType {
    OLLAMA("Ollama", "mdal-computer", true),
    LOCAL_NATIVE("Native (GGUF)", "mdmz-memory", true),
    OPENAI("OpenAI", "mdal-cloud", false),
    ANTHROPIC("Anthropic", "mdal-cloud_queue", false),
    CUSTOM("Custom", "mdal-build", false);

    private final String displayName;
    private final String iconLiteral;
    private final boolean isLocal;

    ProviderType(String displayName, String iconLiteral, boolean isLocal) {
        this.displayName = displayName;
        this.iconLiteral = iconLiteral;
        this.isLocal = isLocal;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Returns the Ikonli icon literal for this provider type. */
    public String getIconLiteral() {
        return iconLiteral;
    }

    public boolean isLocal() {
        return isLocal;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
