package com.desktopai.service.llm;

import com.desktopai.model.DAIChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interface for LLM providers.
 */
public interface LLMProvider {

    String generate(String prompt);

    CompletableFuture<Void> generateStream(String prompt, Consumer<String> tokenConsumer);

    /**
     * Generates a streaming response with conversation history.
     * The default implementation builds a plain-text prompt from the history;
     * providers that support native multi-turn APIs should override this.
     */
    default CompletableFuture<Void> generateStreamWithHistory(
            List<DAIChatMessage> history, String currentPrompt, Consumer<String> tokenConsumer) {
        if (history == null || history.isEmpty()) {
            return generateStream(currentPrompt, tokenConsumer);
        }
        StringBuilder fullPrompt = new StringBuilder();
        for (DAIChatMessage msg : history) {
            switch (msg.getRole()) {
                case USER -> fullPrompt.append("User: ").append(msg.getContent()).append("\n");
                case ASSISTANT -> fullPrompt.append("Assistant: ").append(msg.getContent()).append("\n");
                case SYSTEM -> fullPrompt.append("System: ").append(msg.getContent()).append("\n");
            }
        }
        fullPrompt.append("User: ").append(currentPrompt).append("\nAssistant:");
        return generateStream(fullPrompt.toString(), tokenConsumer);
    }

    List<String> listModels();

    String getProviderName();

    String getProviderId();

    boolean testConnection();
}
