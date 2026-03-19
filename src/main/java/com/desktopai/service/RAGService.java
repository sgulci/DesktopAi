package com.desktopai.service;

import com.desktopai.model.DAIChatMessage;
import com.desktopai.model.DAIDocument;
import com.desktopai.model.DAIDocument.DocumentStatus;
import com.desktopai.model.DAIDocumentChunk;
import com.desktopai.model.ProviderConfig;
import com.desktopai.repository.ChatHistoryRepository;
import com.desktopai.repository.DocumentRepository;
import com.desktopai.service.embedding.EmbeddingProvider;
import com.desktopai.service.embedding.EmbeddingProviderFactory;
import com.desktopai.service.llm.LLMProvider;
import com.desktopai.service.llm.LLMProviderFactory;

import java.io.File;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * RAG (Retrieval Augmented Generation) Service.
 * Orchestrates document processing and question answering with conversation history.
 */
public class RAGService {
    private static final int DEFAULT_CONTEXT_CHUNKS = 5;
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_OVERLAP = 100;

    private static final String RAG_PROMPT_TEMPLATE = """
            You are a helpful assistant. Answer the user's question based on the provided context.
            If the context doesn't contain relevant information, say so honestly.

            Context:
            %s

            Question: %s

            Answer:""";

    private final ParsingService parsingService;
    private final EmbeddingProvider embeddingProvider;
    private final VectorStoreService vectorStore;
    private final DocumentRepository documentRepository;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ConfigService configService;

    public RAGService() {
        this.configService = new ConfigService();
        this.parsingService = new ParsingService();
        this.embeddingProvider = EmbeddingProviderFactory.create(configService);
        this.vectorStore = new VectorStoreService(new com.desktopai.repository.ChunkRepository(), embeddingProvider);
        this.documentRepository = new DocumentRepository();
        this.chatHistoryRepository = new ChatHistoryRepository();
    }

    /**
     * Processes a document: parse, chunk, embed, and store.
     * Chunk size is read from settings at processing time.
     */
    public DAIDocument processDocument(File file, Consumer<String> progressCallback) throws Exception {
        progressCallback.accept("Creating document record...");

        int chunkSize = DEFAULT_CHUNK_SIZE;
        try {
            chunkSize = configService.getChunkSize();
        } catch (Exception ignored) {}

        SemanticChunker chunker = new SemanticChunker(chunkSize, DEFAULT_OVERLAP);

        DAIDocument doc = new DAIDocument();
        doc.setFilename(file.getName());
        doc.setFilepath(file.getAbsolutePath());
        doc.setSizeBytes(file.length());
        doc.setStatus(DocumentStatus.PROCESSING);
        doc = documentRepository.save(doc);

        try {
            progressCallback.accept("Parsing document...");
            ParsingService.ParseResult parseResult = parsingService.parse(file);
            doc.setContentType(parseResult.getContentType());

            progressCallback.accept("Chunking document...");
            List<DAIDocumentChunk> chunks = chunker.chunk(doc.getId(), parseResult.getContent());
            progressCallback.accept("Created " + chunks.size() + " chunks");

            progressCallback.accept("Generating embeddings...");
            embeddingProvider.embedChunks(chunks);

            progressCallback.accept("Storing chunks...");
            vectorStore.store(chunks);

            doc.setChunkCount(chunks.size());
            doc.setStatus(DocumentStatus.READY);
            documentRepository.update(doc);

            progressCallback.accept("Document ready!");
            return doc;

        } catch (Exception e) {
            doc.setStatus(DocumentStatus.ERROR);
            documentRepository.update(doc);
            throw e;
        }
    }

    /**
     * Answers a question using RAG (non-streaming, no history).
     */
    public String answer(String question, ProviderConfig providerConfig, String modelName) throws SQLException {
        return answer(question, providerConfig, modelName, null);
    }

    public String answer(String question, ProviderConfig providerConfig, String modelName,
            List<String> documentIds) throws SQLException {
        List<VectorStoreService.SearchResult> results = vectorStore.search(question, getContextChunkCount(), documentIds);

        if (results.isEmpty()) {
            return "No relevant information found in the documents.";
        }

        String context = results.stream()
                .map(r -> r.getChunk().getContent())
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = String.format(RAG_PROMPT_TEMPLATE, context, question);
        LLMProvider provider = LLMProviderFactory.create(providerConfig, modelName);
        return provider.generate(prompt);
    }

    /**
     * Answers a question with streaming response and conversation history.
     *
     * @param sessionId used to load prior conversation history for multi-turn context
     */
    public CompletableFuture<Void> answerStream(String question, ProviderConfig providerConfig,
            String modelName, List<String> documentIds, String sessionId,
            Consumer<String> tokenConsumer) throws SQLException {

        List<DAIChatMessage> history = chatHistoryRepository.findBySessionId(sessionId);
        LLMProvider provider = LLMProviderFactory.create(providerConfig, modelName);

        // Empty list = explicit "No context" mode → pure LLM chat, no RAG
        if (documentIds != null && documentIds.isEmpty()) {
            return provider.generateStreamWithHistory(history, question, tokenConsumer);
        }

        // null = "All documents"; non-empty list = specific documents — both go through RAG
        // documentIds == null means search across all indexed chunks
        List<VectorStoreService.SearchResult> results =
                vectorStore.search(question, getContextChunkCount(), documentIds);

        // No relevant chunks found (e.g. nothing indexed yet) → fall back to direct conversation
        if (results.isEmpty()) {
            return provider.generateStreamWithHistory(history, question, tokenConsumer);
        }

        // Build RAG prompt and stream with history
        String context = results.stream()
                .map(r -> r.getChunk().getContent())
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = String.format(RAG_PROMPT_TEMPLATE, context, question);
        return provider.generateStreamWithHistory(history, prompt, tokenConsumer);
    }

    public void deleteDocument(String documentId) throws SQLException {
        vectorStore.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
    }

    public List<DAIDocument> getAllDocuments() throws SQLException {
        return documentRepository.findAll();
    }

    private int getContextChunkCount() {
        try {
            return configService.getContextChunks();
        } catch (Exception e) {
            return DEFAULT_CONTEXT_CHUNKS;
        }
    }
}
