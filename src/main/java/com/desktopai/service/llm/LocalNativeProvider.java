package com.desktopai.service.llm;

import com.desktopai.model.DAIChatMessage;
import com.desktopai.model.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.LogLevel;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.args.LogFormat;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Local Native LLM provider using java-llama.cpp.
 * Instances are cached by LLMProviderFactory to avoid repeated model loads and resource leaks.
 */
public class LocalNativeProvider implements LLMProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalNativeProvider.class);
    private static final Logger llamaCppLog = LoggerFactory.getLogger("llama.cpp");
    private static final AtomicBoolean loggerInstalled = new AtomicBoolean(false);
    // Ordinals: DEBUG=0, INFO=1, WARN=2, ERROR=3; OFF=4 (no matching LogLevel)
    private static volatile int minLevelOrdinal = 2; // default: WARN

    /**
     * Routes native llama.cpp log output through SLF4J under the "llama.cpp" logger.
     * Filtering happens inside the callback (independent of Logback) so that messages
     * below the configured level are dropped before reaching SLF4J.
     * Only installs the hook once (idempotent). Call this before any LlamaModel is created.
     */
    public static void installLoggerHook() {
        if (loggerInstalled.compareAndSet(false, true)) {
            LlamaModel.setLogger(LogFormat.TEXT, (level, msg) -> {
                if (level.ordinal() < minLevelOrdinal) return;
                String trimmed = msg.trim();
                if (trimmed.isEmpty()) return;
                switch (level) {
                    case DEBUG -> llamaCppLog.debug(trimmed);
                    case INFO  -> llamaCppLog.info(trimmed);
                    case WARN  -> llamaCppLog.warn(trimmed);
                    case ERROR -> llamaCppLog.error(trimmed);
                }
            });
        }
    }

    /** Updates the minimum log level applied inside the native callback. */
    public static void setMinLogLevel(String levelStr) {
        minLevelOrdinal = switch (levelStr.toUpperCase()) {
            case "DEBUG" -> 0;
            case "INFO"  -> 1;
            case "WARN"  -> 2;
            case "ERROR" -> 3;
            default      -> 4; // OFF — drop everything
        };
    }

    private final ProviderConfig config;
    private final String modelName;
    private final Path modelPath;
    private LlamaModel model;

    public LocalNativeProvider(ProviderConfig config, String modelName) {
        installLoggerHook();
        this.config = config;
        this.modelName = modelName;

        String userHome = System.getProperty("user.home");
        Path modelsDir = Path.of(userHome, ".desktopai", "models");

        File modelsDirFile = modelsDir.toFile();
        if (!modelsDirFile.exists()) {
            modelsDirFile.mkdirs();
        }

        if (modelName == null || modelName.isEmpty()) {
            File[] ggufFiles = modelsDirFile.listFiles((dir, name) -> name.endsWith(".gguf"));
            if (ggufFiles != null && ggufFiles.length > 0) {
                this.modelPath = ggufFiles[0].toPath();
            } else {
                this.modelPath = modelsDir.resolve("no-model-specified");
            }
        } else if (modelName.endsWith(".gguf")) {
            this.modelPath = modelsDir.resolve(modelName);
        } else {
            File[] ggufFiles = modelsDirFile.listFiles(
                    (dir, name) -> name.toLowerCase().contains(modelName.toLowerCase()) && name.endsWith(".gguf"));
            if (ggufFiles != null && ggufFiles.length > 0) {
                this.modelPath = ggufFiles[0].toPath();
            } else {
                this.modelPath = modelsDir.resolve(modelName);
            }
        }
    }

    private synchronized void ensureModelLoaded() throws Exception {
        if (model == null) {
            File modelFile = modelPath.toFile();
            if (!modelFile.exists()) {
                throw new java.io.FileNotFoundException("Model not found: " + modelPath);
            }

            if (!modelPath.toString().endsWith(".gguf")) {
                throw new IllegalArgumentException("Only GGUF models are supported. File: " + modelPath);
            }

            log.info("Loading GGUF model from: {}", modelPath);

            ModelParameters modelParams = new ModelParameters()
                    .setModel(modelPath.toString());

            model = new LlamaModel(modelParams);
            log.info("GGUF model loaded successfully");
        }
    }

    @Override
    public String generate(String prompt) {
        try {
            ensureModelLoaded();

            InferenceParameters inferParams = new InferenceParameters(prompt)
                    .setTemperature(0.7f);

            StringBuilder response = new StringBuilder();
            for (LlamaOutput output : model.generate(inferParams)) {
                response.append(output);
            }

            return response.toString();
        } catch (Exception e) {
            return "[Error: " + e.getMessage() + "]";
        }
    }

    @Override
    public CompletableFuture<Void> generateStream(String prompt, Consumer<String> tokenConsumer) {
        return generateStreamWithHistory(null, prompt, tokenConsumer);
    }

    @Override
    public CompletableFuture<Void> generateStreamWithHistory(
            List<DAIChatMessage> history, String currentPrompt, Consumer<String> tokenConsumer) {
        // Build a formatted prompt that includes conversation history
        String fullPrompt = buildPrompt(history, currentPrompt);

        return CompletableFuture.runAsync(() -> {
            try {
                ensureModelLoaded();

                InferenceParameters inferParams = new InferenceParameters(fullPrompt)
                        .setTemperature(0.7f);

                for (LlamaOutput output : model.generate(inferParams)) {
                    tokenConsumer.accept(output.toString());
                }

            } catch (Exception e) {
                tokenConsumer.accept("[Error: " + e.getMessage() + "]");
                throw new RuntimeException(e);
            }
        });
    }

    private String buildPrompt(List<DAIChatMessage> history, String currentPrompt) {
        if (history == null || history.isEmpty()) {
            return currentPrompt;
        }
        // Use ChatML format which most GGUF instruction models understand
        StringBuilder sb = new StringBuilder();
        for (DAIChatMessage msg : history) {
            String role = switch (msg.getRole()) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case SYSTEM -> "system";
            };
            sb.append("<|im_start|>").append(role).append("\n")
              .append(msg.getContent()).append("<|im_end|>\n");
        }
        sb.append("<|im_start|>user\n").append(currentPrompt).append("<|im_end|>\n");
        sb.append("<|im_start|>assistant\n");
        return sb.toString();
    }

    @Override
    public List<String> listModels() {
        return Collections.singletonList(modelName != null ? modelName : modelPath.getFileName().toString());
    }

    @Override
    public String getProviderName() {
        return "Native Local";
    }

    @Override
    public String getProviderId() {
        return config.getId();
    }

    @Override
    public boolean testConnection() {
        return modelPath.toFile().exists();
    }

    /** Releases native resources. Called by LLMProviderFactory.closeAll() on app shutdown. */
    public synchronized void close() {
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
