package com.desktopai.model;

import java.time.LocalDateTime;

/**
 * Represents a document uploaded to the system.
 */
public class DAIDocument {
    private String id;
    private String filename;
    private String filepath;
    private String contentType;
    private long sizeBytes;
    private LocalDateTime createdAt;
    private int chunkCount;
    private DocumentStatus status;

    public enum DocumentStatus {
        PENDING, PROCESSING, READY, ERROR
    }

    public DAIDocument() {
        this.createdAt = LocalDateTime.now();
        this.status = DocumentStatus.PENDING;
    }

    public DAIDocument(String id, String filename, String filepath) {
        this();
        this.id = id;
        this.filename = filename;
        this.filepath = filepath;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getFilepath() {
        return filepath;
    }

    public void setFilepath(String filepath) {
        this.filepath = filepath;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public void setStatus(DocumentStatus status) {
        this.status = status;
    }
}
