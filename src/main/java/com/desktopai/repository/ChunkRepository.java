package com.desktopai.repository;

import com.desktopai.model.DAIDocumentChunk;
import com.desktopai.model.DAIDocumentChunk.ChunkMetadata;
import com.desktopai.util.DatabaseManager;
import com.google.gson.Gson;

import java.nio.ByteBuffer;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Repository for DocumentChunk CRUD operations with persistent embeddings.
 */
public class ChunkRepository {
    private final DatabaseManager dbManager;
    private final Gson gson;

    public ChunkRepository() {
        this.dbManager = DatabaseManager.getInstance();
        this.gson = new Gson();
    }

    /**
     * Saves a chunk with its embedding.
     */
    public DAIDocumentChunk save(DAIDocumentChunk chunk) throws SQLException {
        if (chunk.getId() == null) {
            chunk.setId(UUID.randomUUID().toString());
        }

        String sql = """
                    INSERT INTO document_chunks (id, document_id, content, embedding, position, metadata)
                    VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, chunk.getId());
            stmt.setString(2, chunk.getDocumentId());
            stmt.setString(3, chunk.getContent());
            stmt.setBytes(4, serializeEmbedding(chunk.getEmbedding()));
            stmt.setInt(5, chunk.getPosition());
            stmt.setString(6, chunk.getMetadata() != null ? gson.toJson(chunk.getMetadata()) : null);
            stmt.executeUpdate();
        }

        return chunk;
    }

    /**
     * Saves multiple chunks in a batch.
     */
    public void saveAll(List<DAIDocumentChunk> chunks) throws SQLException {
        String sql = """
                    INSERT INTO document_chunks (id, document_id, content, embedding, position, metadata)
                    VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);

            for (DAIDocumentChunk chunk : chunks) {
                if (chunk.getId() == null) {
                    chunk.setId(UUID.randomUUID().toString());
                }
                stmt.setString(1, chunk.getId());
                stmt.setString(2, chunk.getDocumentId());
                stmt.setString(3, chunk.getContent());
                stmt.setBytes(4, serializeEmbedding(chunk.getEmbedding()));
                stmt.setInt(5, chunk.getPosition());
                stmt.setString(6, chunk.getMetadata() != null ? gson.toJson(chunk.getMetadata()) : null);
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();
            conn.setAutoCommit(true);
        }
    }

    /**
     * Finds all chunks for a document.
     */
    public List<DAIDocumentChunk> findByDocumentId(String documentId) throws SQLException {
        String sql = "SELECT * FROM document_chunks WHERE document_id = ? ORDER BY position";
        List<DAIDocumentChunk> chunks = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    chunks.add(mapRow(rs));
                }
            }
        }
        return chunks;
    }

    /**
     * Finds all chunks (for similarity search).
     */
    public List<DAIDocumentChunk> findAll() throws SQLException {
        String sql = "SELECT * FROM document_chunks ORDER BY document_id, position";
        List<DAIDocumentChunk> chunks = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                chunks.add(mapRow(rs));
            }
        }
        return chunks;
    }

    /**
     * Finds chunks by document IDs (for filtered search).
     */
    public List<DAIDocumentChunk> findByDocumentIds(List<String> documentIds) throws SQLException {
        if (documentIds.isEmpty()) {
            return new ArrayList<>();
        }

        String placeholders = String.join(",", documentIds.stream().map(id -> "?").toList());
        String sql = "SELECT * FROM document_chunks WHERE document_id IN (" + placeholders
                + ") ORDER BY document_id, position";
        List<DAIDocumentChunk> chunks = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < documentIds.size(); i++) {
                stmt.setString(i + 1, documentIds.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    chunks.add(mapRow(rs));
                }
            }
        }
        return chunks;
    }

    /**
     * Deletes all chunks for a document.
     */
    public void deleteByDocumentId(String documentId) throws SQLException {
        String sql = "DELETE FROM document_chunks WHERE document_id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, documentId);
            stmt.executeUpdate();
        }
    }

    /**
     * Gets chunk count for a document.
     */
    public int countByDocumentId(String documentId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, documentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private DAIDocumentChunk mapRow(ResultSet rs) throws SQLException {
        DAIDocumentChunk chunk = new DAIDocumentChunk();
        chunk.setId(rs.getString("id"));
        chunk.setDocumentId(rs.getString("document_id"));
        chunk.setContent(rs.getString("content"));
        chunk.setEmbedding(deserializeEmbedding(rs.getBytes("embedding")));
        chunk.setPosition(rs.getInt("position"));

        String metadataJson = rs.getString("metadata");
        if (metadataJson != null) {
            chunk.setMetadata(gson.fromJson(metadataJson, ChunkMetadata.class));
        }

        return chunk;
    }

    /**
     * Serializes a float array to bytes.
     */
    private byte[] serializeEmbedding(float[] embedding) {
        if (embedding == null)
            return new byte[0];
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * 4);
        for (float f : embedding) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * Deserializes bytes to a float array.
     */
    private float[] deserializeEmbedding(byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            return new float[0];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] embedding = new float[bytes.length / 4];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }
}
