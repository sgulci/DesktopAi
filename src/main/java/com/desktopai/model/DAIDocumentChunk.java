package com.desktopai.model;

/**
 * Represents a chunk of a document with its embedding.
 */
public class DAIDocumentChunk {
    private String id;
    private String documentId;
    private String content;
    private float[] embedding;
    private int position;
    private ChunkMetadata metadata;

    public DAIDocumentChunk() {
    }

    public DAIDocumentChunk(String id, String documentId, String content, int position) {
        this.id = id;
        this.documentId = documentId;
        this.content = content;
        this.position = position;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public ChunkMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ChunkMetadata metadata) {
        this.metadata = metadata;
    }

    /**
     * Metadata about the chunk's location in the source document.
     */
    public static class ChunkMetadata {
        private Integer pageNumber;
        private String heading;
        private String section;

        public Integer getPageNumber() {
            return pageNumber;
        }

        public void setPageNumber(Integer pageNumber) {
            this.pageNumber = pageNumber;
        }

        public String getHeading() {
            return heading;
        }

        public void setHeading(String heading) {
            this.heading = heading;
        }

        public String getSection() {
            return section;
        }

        public void setSection(String section) {
            this.section = section;
        }
    }
}
