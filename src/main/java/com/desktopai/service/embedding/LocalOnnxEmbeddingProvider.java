package com.desktopai.service.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.desktopai.model.DAIDocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local ONNX embedding provider using AllMiniLM-L6-v2.
 * Model and tokenizer are downloaded once to ~/.desktopai/embedding-model/ on first use.
 */
public class LocalOnnxEmbeddingProvider implements EmbeddingProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalOnnxEmbeddingProvider.class);

    private static final String MODEL_URL =
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx";
    private static final String TOKENIZER_URL =
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json";
    private static final int EMBEDDING_DIM = 384;

    private static volatile OrtEnvironment sharedEnv;
    private static volatile OrtSession sharedSession;
    private static volatile HuggingFaceTokenizer sharedTokenizer;
    static final Object LOCK = new Object();

    private final Path modelDir;

    public LocalOnnxEmbeddingProvider() {
        this.modelDir = Path.of(System.getProperty("user.home"), ".desktopai", "embedding-model");
    }

    private void ensureLoaded() {
        if (sharedSession != null) return;
        synchronized (LOCK) {
            if (sharedSession != null) return;
            try {
                Files.createDirectories(modelDir);
                Path modelPath = modelDir.resolve("model.onnx");
                Path tokenizerPath = modelDir.resolve("tokenizer.json");

                if (!Files.exists(modelPath)) {
                    log.info("Downloading AllMiniLM-L6-v2 ONNX model to {}", modelPath);
                    downloadFile(MODEL_URL, modelPath);
                    log.info("Model download complete");
                }
                if (!Files.exists(tokenizerPath)) {
                    log.info("Downloading tokenizer to {}", tokenizerPath);
                    downloadFile(TOKENIZER_URL, tokenizerPath);
                }

                sharedEnv = OrtEnvironment.getEnvironment();
                sharedSession = sharedEnv.createSession(
                        modelPath.toString(), new OrtSession.SessionOptions());
                sharedTokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize embedding model: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public float[] embed(String text) {
        ensureLoaded();
        try {
            Encoding encoding = sharedTokenizer.encode(text);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            long[] tokenTypeIds = encoding.getTypeIds();

            Set<String> inputNames = sharedSession.getInputNames();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids",
                    OnnxTensor.createTensor(sharedEnv, new long[][]{ inputIds }));
            inputs.put("attention_mask",
                    OnnxTensor.createTensor(sharedEnv, new long[][]{ attentionMask }));
            if (inputNames.contains("token_type_ids")) {
                inputs.put("token_type_ids",
                        OnnxTensor.createTensor(sharedEnv, new long[][]{ tokenTypeIds }));
            }

            try (OrtSession.Result result = sharedSession.run(inputs)) {
                float[][][] hiddenState = (float[][][]) result.get(0).getValue();
                float[] pooled = meanPool(hiddenState[0], attentionMask);
                return normalize(pooled);
            } finally {
                inputs.values().forEach(OnnxTensor::close);
            }
        } catch (OrtException e) {
            throw new RuntimeException("Embedding inference failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void embedChunks(List<DAIDocumentChunk> chunks) {
        for (DAIDocumentChunk chunk : chunks) {
            chunk.setEmbedding(embed(chunk.getContent()));
        }
    }

    @Override
    public int getDimension() {
        return EMBEDDING_DIM;
    }

    @Override
    public boolean testConnection() {
        try {
            embed("test");
            return true;
        } catch (Exception e) {
            log.warn("Local ONNX test failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "Local ONNX (AllMiniLM-L6-v2)";
    }

    @Override
    public void close() {
        synchronized (LOCK) {
            if (sharedTokenizer != null) {
                sharedTokenizer.close();
                sharedTokenizer = null;
            }
            if (sharedSession != null) {
                try { sharedSession.close(); } catch (OrtException e) { /* ignore */ }
                sharedSession = null;
            }
            if (sharedEnv != null) {
                sharedEnv.close();
                sharedEnv = null;
            }
        }
    }

    private float[] meanPool(float[][] hiddenState, long[] attentionMask) {
        int dim = hiddenState[0].length;
        float[] pooled = new float[dim];
        long tokenCount = 0;
        for (int i = 0; i < hiddenState.length; i++) {
            if (attentionMask[i] == 1) {
                for (int j = 0; j < dim; j++) pooled[j] += hiddenState[i][j];
                tokenCount++;
            }
        }
        if (tokenCount > 0) {
            for (int j = 0; j < dim; j++) pooled[j] /= tokenCount;
        }
        return pooled;
    }

    private float[] normalize(float[] v) {
        double norm = 0.0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0.0) return v;
        float[] result = new float[v.length];
        for (int i = 0; i < v.length; i++) result[i] = (float) (v[i] / norm);
        return result;
    }

    private void downloadFile(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofFile(target));
    }
}
