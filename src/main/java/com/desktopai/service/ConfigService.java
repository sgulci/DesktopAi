package com.desktopai.service;

import com.desktopai.model.EmbeddingProviderType;
import com.desktopai.util.DatabaseManager;

import java.sql.*;

/**
 * Service for managing application settings.
 */
public class ConfigService {
    private final DatabaseManager dbManager;

    // Setting keys
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_CHUNK_SIZE = "chunk_size";
    public static final String KEY_CONTEXT_CHUNKS = "context_chunks";
    public static final String KEY_DEFAULT_MODEL = "default_model";
    public static final String KEY_DEFAULT_PROVIDER = "default_provider";

    public static final String KEY_EMBEDDING_TYPE = "embedding.type";
    public static final String KEY_EMBEDDING_MODEL = "embedding.model";
    public static final String KEY_EMBEDDING_API_URL = "embedding.api_url";
    public static final String KEY_EMBEDDING_API_KEY = "embedding.api_key";

    public static final String KEY_LLAMA_LOG_LEVEL = "llama.log_level";

    // Default values
    private static final String DEFAULT_DARK_MODE = "true";
    private static final String DEFAULT_CHUNK_SIZE = "800";
    private static final String DEFAULT_CONTEXT_CHUNKS = "5";

    public ConfigService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    /**
     * Gets a setting value.
     */
    public String get(String key) throws SQLException {
        return get(key, null);
    }

    /**
     * Gets a setting value with a default.
     */
    public String get(String key, String defaultValue) throws SQLException {
        String sql = "SELECT value FROM settings WHERE key = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString("value");
                    return value != null ? value : defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * Sets a setting value.
     */
    public void set(String key, String value) throws SQLException {
        String sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setString(2, value);
            stmt.executeUpdate();
        }
    }

    /**
     * Gets boolean setting.
     */
    public boolean getBoolean(String key, boolean defaultValue) throws SQLException {
        String value = get(key);
        if (value == null)
            return defaultValue;
        return Boolean.parseBoolean(value);
    }

    /**
     * Gets integer setting.
     */
    public int getInt(String key, int defaultValue) throws SQLException {
        String value = get(key);
        if (value == null)
            return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Checks if dark mode is enabled.
     */
    public boolean isDarkMode() throws SQLException {
        return getBoolean(KEY_DARK_MODE, true);
    }

    /**
     * Sets dark mode.
     */
    public void setDarkMode(boolean enabled) throws SQLException {
        set(KEY_DARK_MODE, String.valueOf(enabled));
    }

    /**
     * Gets chunk size setting.
     */
    public int getChunkSize() throws SQLException {
        return getInt(KEY_CHUNK_SIZE, Integer.parseInt(DEFAULT_CHUNK_SIZE));
    }

    /**
     * Gets context chunk count setting.
     */
    public int getContextChunks() throws SQLException {
        return getInt(KEY_CONTEXT_CHUNKS, Integer.parseInt(DEFAULT_CONTEXT_CHUNKS));
    }

    /**
     * Gets the configured embedding provider type (defaults to LOCAL_ONNX).
     */
    public EmbeddingProviderType getEmbeddingType() throws SQLException {
        String value = get(KEY_EMBEDDING_TYPE);
        if (value == null) return EmbeddingProviderType.LOCAL_ONNX;
        try {
            return EmbeddingProviderType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return EmbeddingProviderType.LOCAL_ONNX;
        }
    }

    public String getEmbeddingModel() throws SQLException {
        return get(KEY_EMBEDDING_MODEL);
    }

    public String getEmbeddingApiUrl() throws SQLException {
        return get(KEY_EMBEDDING_API_URL);
    }

    public String getEmbeddingApiKey() throws SQLException {
        return get(KEY_EMBEDDING_API_KEY);
    }

    /** Returns the configured llama.cpp log level (default: WARN). */
    public String getLlamaLogLevel() throws SQLException {
        return get(KEY_LLAMA_LOG_LEVEL, "WARN");
    }
}
