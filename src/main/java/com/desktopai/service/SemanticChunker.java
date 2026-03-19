package com.desktopai.service;

import com.desktopai.model.DAIDocumentChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for intelligently chunking documents for RAG.
 * Uses semantic-aware chunking with sentence boundaries and overlap.
 */
public class SemanticChunker {
    private static final int DEFAULT_CHUNK_SIZE = 800; // chars
    private static final int DEFAULT_OVERLAP = 100; // chars
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
            "[.!?]+\\s+|\\n\\n+",
            Pattern.MULTILINE);

    private final int chunkSize;
    private final int overlap;

    public SemanticChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public SemanticChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * Chunks a document into semantic segments.
     */
    public List<DAIDocumentChunk> chunk(String documentId, String content) {
        List<DAIDocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.isBlank()) {
            return chunks;
        }

        // Clean the content
        content = cleanContent(content);

        // Split into sentences/paragraphs
        List<String> sentences = splitIntoSentences(content);

        // Build chunks respecting sentence boundaries
        StringBuilder currentChunk = new StringBuilder();
        int position = 0;

        for (String sentence : sentences) {
            // If adding this sentence exceeds chunk size, save current chunk
            if (currentChunk.length() + sentence.length() > chunkSize && currentChunk.length() > 0) {
                DAIDocumentChunk chunk = createChunk(documentId, currentChunk.toString().trim(), position);
                chunks.add(chunk);
                position++;

                // Keep overlap from the end of current chunk
                String overlapText = getOverlapText(currentChunk.toString());
                currentChunk = new StringBuilder(overlapText);
            }

            currentChunk.append(sentence).append(" ");
        }

        // Add the last chunk
        if (currentChunk.length() > 0) {
            String finalText = currentChunk.toString().trim();
            if (!finalText.isEmpty()) {
                DAIDocumentChunk chunk = createChunk(documentId, finalText, position);
                chunks.add(chunk);
            }
        }

        return chunks;
    }

    /**
     * Splits content into sentences/logical segments.
     */
    private List<String> splitIntoSentences(String content) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(content);

        int lastEnd = 0;
        while (matcher.find()) {
            String sentence = content.substring(lastEnd, matcher.end()).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
            lastEnd = matcher.end();
        }

        // Add remaining content
        if (lastEnd < content.length()) {
            String remaining = content.substring(lastEnd).trim();
            if (!remaining.isEmpty()) {
                sentences.add(remaining);
            }
        }

        // If no sentence breaks found, split by word count
        if (sentences.isEmpty() && !content.isEmpty()) {
            sentences.add(content);
        }

        return sentences;
    }

    /**
     * Gets overlap text from the end of a chunk.
     */
    private String getOverlapText(String text) {
        if (text.length() <= overlap) {
            return text;
        }

        // Try to break at a word boundary
        String overlapRegion = text.substring(text.length() - overlap);
        int spaceIndex = overlapRegion.indexOf(' ');
        if (spaceIndex > 0) {
            return overlapRegion.substring(spaceIndex + 1);
        }
        return overlapRegion;
    }

    /**
     * Cleans content for better processing.
     */
    private String cleanContent(String content) {
        // Remove excessive whitespace
        content = content.replaceAll("\\s+", " ");
        // Remove control characters except newlines
        content = content.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return content.trim();
    }

    /**
     * Creates a DocumentChunk.
     */
    private DAIDocumentChunk createChunk(String documentId, String content, int position) {
        DAIDocumentChunk chunk = new DAIDocumentChunk();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setDocumentId(documentId);
        chunk.setContent(content);
        chunk.setPosition(position);
        return chunk;
    }
}
