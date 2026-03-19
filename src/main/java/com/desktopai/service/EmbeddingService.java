package com.desktopai.service;

import com.desktopai.service.embedding.EmbeddingProviderFactory;

/**
 * Utility class for embedding-related operations.
 * Actual embedding logic has moved to service/embedding/ providers.
 */
public class EmbeddingService {

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Embeddings must have same dimension");
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Delegates to EmbeddingProviderFactory to close all providers on shutdown. */
    public static void closeShared() {
        EmbeddingProviderFactory.closeAll();
    }
}
