package com.desktopai.model;

public enum EmbeddingProviderType {
    LOCAL_ONNX("Local ONNX (offline)", "mdmz-memory"),
    OLLAMA("Ollama", "mdal-computer"),
    OPENAI("OpenAI", "mdal-cloud");

    private final String displayName;
    private final String iconLiteral;

    EmbeddingProviderType(String displayName, String iconLiteral) {
        this.displayName = displayName;
        this.iconLiteral = iconLiteral;
    }

    public String getDisplayName() { return displayName; }
    public String getIconLiteral() { return iconLiteral; }

    @Override
    public String toString() { return displayName; }
}
