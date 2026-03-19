package com.desktopai.service.embedding;

import com.desktopai.model.DAIDocumentChunk;

import java.util.List;

/**
 * Abstraction for text embedding providers.
 */
public interface EmbeddingProvider {
    float[] embed(String text);
    void embedChunks(List<DAIDocumentChunk> chunks);
    int getDimension();
    boolean testConnection();
    String getProviderName();
    void close();
}
