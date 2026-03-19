package com.desktopai.service;

import com.desktopai.model.DAIDocumentChunk;
import com.desktopai.repository.ChunkRepository;
import com.desktopai.service.embedding.EmbeddingProvider;
import com.desktopai.service.embedding.EmbeddingProviderFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Service for vector storage and similarity search.
 * Uses SQLite for persistent storage with in-memory cosine similarity search.
 */
public class VectorStoreService {
    private static final double MIN_SIMILARITY = 0.25;

    private final ChunkRepository chunkRepository;
    private final EmbeddingProvider embeddingProvider;

    public VectorStoreService() {
        this.chunkRepository = new ChunkRepository();
        this.embeddingProvider = EmbeddingProviderFactory.create(new ConfigService());
    }

    public VectorStoreService(ChunkRepository chunkRepository, EmbeddingProvider embeddingProvider) {
        this.chunkRepository = chunkRepository;
        this.embeddingProvider = embeddingProvider;
    }

    public void store(List<DAIDocumentChunk> chunks) throws SQLException {
        chunkRepository.saveAll(chunks);
    }

    public List<SearchResult> search(String query, int topK) throws SQLException {
        return search(query, topK, null);
    }

    /**
     * Searches for similar chunks, returning at most topK results above MIN_SIMILARITY threshold.
     * Falls back to best-available results if nothing crosses the threshold.
     */
    public List<SearchResult> search(String query, int topK, List<String> documentIds) throws SQLException {
        float[] queryEmbedding = embeddingProvider.embed(query);

        List<DAIDocumentChunk> chunks;
        if (documentIds != null && !documentIds.isEmpty()) {
            chunks = chunkRepository.findByDocumentIds(documentIds);
        } else {
            chunks = chunkRepository.findAll();
        }

        List<SearchResult> results = new ArrayList<>(chunks.size());
        for (DAIDocumentChunk chunk : chunks) {
            if (chunk.getEmbedding() != null && chunk.getEmbedding().length > 0) {
                double similarity = EmbeddingService.cosineSimilarity(queryEmbedding, chunk.getEmbedding());
                results.add(new SearchResult(chunk, similarity));
            }
        }

        results.sort(Comparator.comparingDouble(SearchResult::getSimilarity).reversed());
        List<SearchResult> top = results.subList(0, Math.min(topK, results.size()));

        // Filter by threshold; if everything is below threshold, return best available anyway
        List<SearchResult> filtered = top.stream()
                .filter(r -> r.getSimilarity() >= MIN_SIMILARITY)
                .toList();

        return filtered.isEmpty() ? top : filtered;
    }

    public void deleteByDocumentId(String documentId) throws SQLException {
        chunkRepository.deleteByDocumentId(documentId);
    }

    public static class SearchResult {
        private final DAIDocumentChunk chunk;
        private final double similarity;

        public SearchResult(DAIDocumentChunk chunk, double similarity) {
            this.chunk = chunk;
            this.similarity = similarity;
        }

        public DAIDocumentChunk getChunk() {
            return chunk;
        }

        public double getSimilarity() {
            return similarity;
        }
    }
}
