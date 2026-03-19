package com.desktopai.repository;

import com.desktopai.model.DAIDocument;
import com.desktopai.model.DAIDocument.DocumentStatus;
import com.desktopai.util.DatabaseManager;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Document CRUD operations.
 */
public class DocumentRepository {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DatabaseManager dbManager;

    public DocumentRepository() {
        this.dbManager = DatabaseManager.getInstance();
    }

    /**
     * Saves a new document.
     */
    public DAIDocument save(DAIDocument document) throws SQLException {
        if (document.getId() == null) {
            document.setId(UUID.randomUUID().toString());
        }

        String sql = """
                    INSERT INTO documents (id, filename, filepath, content_type, size_bytes, created_at, chunk_count, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, document.getId());
            stmt.setString(2, document.getFilename());
            stmt.setString(3, document.getFilepath());
            stmt.setString(4, document.getContentType());
            stmt.setLong(5, document.getSizeBytes());
            stmt.setString(6, document.getCreatedAt().format(FORMATTER));
            stmt.setInt(7, document.getChunkCount());
            stmt.setString(8, document.getStatus().name());
            stmt.executeUpdate();
        }

        return document;
    }

    /**
     * Updates an existing document.
     */
    public void update(DAIDocument document) throws SQLException {
        String sql = """
                    UPDATE documents SET filename = ?, filepath = ?, content_type = ?,
                    size_bytes = ?, chunk_count = ?, status = ?
                    WHERE id = ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, document.getFilename());
            stmt.setString(2, document.getFilepath());
            stmt.setString(3, document.getContentType());
            stmt.setLong(4, document.getSizeBytes());
            stmt.setInt(5, document.getChunkCount());
            stmt.setString(6, document.getStatus().name());
            stmt.setString(7, document.getId());
            stmt.executeUpdate();
        }
    }

    /**
     * Finds a document by ID.
     */
    public Optional<DAIDocument> findById(String id) throws SQLException {
        String sql = "SELECT * FROM documents WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds all documents.
     */
    public List<DAIDocument> findAll() throws SQLException {
        String sql = "SELECT * FROM documents ORDER BY created_at DESC";
        List<DAIDocument> documents = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                documents.add(mapRow(rs));
            }
        }
        return documents;
    }

    /**
     * Finds documents by status.
     */
    public List<DAIDocument> findByStatus(DocumentStatus status) throws SQLException {
        String sql = "SELECT * FROM documents WHERE status = ? ORDER BY created_at DESC";
        List<DAIDocument> documents = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    documents.add(mapRow(rs));
                }
            }
        }
        return documents;
    }

    /**
     * Deletes a document by ID.
     */
    public void deleteById(String id) throws SQLException {
        String sql = "DELETE FROM documents WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        }
    }

    private DAIDocument mapRow(ResultSet rs) throws SQLException {
        DAIDocument doc = new DAIDocument();
        doc.setId(rs.getString("id"));
        doc.setFilename(rs.getString("filename"));
        doc.setFilepath(rs.getString("filepath"));
        doc.setContentType(rs.getString("content_type"));
        doc.setSizeBytes(rs.getLong("size_bytes"));
        doc.setChunkCount(rs.getInt("chunk_count"));
        doc.setStatus(DocumentStatus.valueOf(rs.getString("status")));

        String createdAt = rs.getString("created_at");
        if (createdAt != null) {
            doc.setCreatedAt(LocalDateTime.parse(createdAt, FORMATTER));
        }

        return doc;
    }
}
